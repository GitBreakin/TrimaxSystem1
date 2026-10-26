-- =====================================================================================================
-- Employee mobile sign-in without the Supabase Dashboard.
--
-- Before: Desktop "Create Employee Account" wrote public.users only. The Agent/Driver could not sign in
-- on the phone until someone added the user in Supabase -> Authentication -> Users by hand and then
-- pressed "Link mobile sign-in" on the Desktop. The provisioning queue that was meant to automate this
-- (trimax_private.provisioning_requests, trimax_service_claim/finish_provisioning) had no caller, and its
-- finish function could not have worked on production: it audited as 'provisioning', which audit_log's
-- foreign key to users refuses (fixed in part 5).
--
-- After:
--   1. Desktop (database owner, over JDBC) right after creating the account:
--        select trimax_private.issue_provisioning_ticket('<admin>', '<username>');
--      Checks the caller is an Active Admin, queues the request with the existing
--      trimax_private.request_mobile_access (same checks: Agent/Driver, Active, unique valid email, no
--      sign-in yet) and returns a one-time ticket (2 minutes).
--   2. Desktop posts the ticket to the Edge Function "trimax-accounts" (action "provision"). The function
--      holds the project's secret key on the Supabase server only. It redeems the ticket with
--      public.trimax_service_claim_provisioning_ticket (service role only), creates the Auth user with the
--      Auth Admin API (email confirmed, a random password nobody knows, app_metadata
--      trimax_provisioned/trimax_username), and records the result with the EXISTING
--      public.trimax_service_finish_provisioning, which links auth.users <-> public.users in
--      trimax_private.mobile_accounts. A retry finds the Auth user it made before and links it instead of
--      making a second one.
--   3. The person chooses the phone password with the setup code the Desktop already emails
--      (public.password_resets). The phone posts email + code + new password to the same Edge Function
--      (action "set_password"); it checks the code with public.trimax_service_use_setup_code (service role
--      only; single use, 5 wrong tries, expiry) and sets the password with the Auth Admin API.
--
-- Nothing here is callable by anon or by signed-in phones. No passwords are stored by TRIMAX.
-- ORDER: after 20261025_01 (provisioning queue) and 20261023_01 (mobile_accounts). Re-runnable.
-- Creates 1 table and 4 functions, and fixes the audit actor in public.trimax_service_finish_provisioning
-- (see part 5). Changes no existing rows.
-- NOT APPLIED to production. Rollback: 20261026_94_rollback_employee_mobile_sign_in.sql.
-- =====================================================================================================

begin;

create table if not exists trimax_private.provisioning_tickets (
    ticket      uuid        primary key default gen_random_uuid(),
    request_id  bigint      not null references trimax_private.provisioning_requests (request_id) on delete cascade,
    issued_by   text        not null,
    issued_at   timestamptz not null default now(),
    expires_at  timestamptz not null default now() + interval '2 minutes',
    used_at     timestamptz
);
create index if not exists provisioning_tickets_issued_at_idx on trimax_private.provisioning_tickets (issued_at);
create index if not exists provisioning_tickets_request_idx on trimax_private.provisioning_tickets (request_id);
alter table trimax_private.provisioning_tickets enable row level security;
revoke all on table trimax_private.provisioning_tickets from public, anon, authenticated, service_role;

-- ----------------------------------------------------------------------------------------------- 1
-- Desktop only (database owner). Queues the mobile sign-in of one account and returns a one-time ticket.
create or replace function trimax_private.issue_provisioning_ticket(p_admin_username text, p_username text)
 returns uuid
 language plpgsql
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_request bigint;
    v_ticket uuid;
begin
    if not exists (select 1 from public.users u join public.roles r on r.role_id = u.role_id
                    where u.username = p_admin_username and r.role_name = 'Admin' and u.account_status = 'Active') then
        raise exception using errcode = 'TM403', message = 'Only an active Admin can create mobile sign-ins.';
    end if;
    -- Existing queue and checks (raises TM422 with the reason: role, status, email, already linked).
    v_request := trimax_private.request_mobile_access(p_username, p_admin_username);

    -- Housekeeping: tickets are only useful for 2 minutes; keep a day for troubleshooting.
    delete from trimax_private.provisioning_tickets where issued_at < now() - interval '1 day';

    insert into trimax_private.provisioning_tickets (request_id, issued_by)
    values (v_request, p_admin_username)
    returning ticket into v_ticket;
    return v_ticket;
end $function$;

-- ----------------------------------------------------------------------------------------------- 2
-- Claims ONE queued request for processing. Same checks and the same answer as each element of
-- public.trimax_service_claim_provisioning (which is left unchanged), plus a "status" for requests that
-- are already done, failed, or being processed by another call right now.
create or replace function trimax_private.claim_provisioning_request(p_request_id bigint, p_stale interval)
 returns jsonb
 language plpgsql
 set search_path to 'public', 'pg_temp'
as $function$
declare
    r record;
    v_problem text;
    v_auth_id uuid;
    v_confirmed boolean;
    v_meta jsonb;
    v_auth_count int;
    v_linked text;
begin
    select pr.request_id, pr.username, pr.email, pr.status, pr.attempts, pr.updated_at, pr.last_error, pr.requested_by into r
      from trimax_private.provisioning_requests pr where pr.request_id = p_request_id for update;
    if not found then
        return jsonb_build_object('status', 'failed', 'message', 'The request no longer exists. Try again.');
    end if;
    if r.status in ('invited', 'linked') then
        return jsonb_build_object('request_id', r.request_id, 'status', r.status, 'username', r.username);
    end if;
    if r.status = 'failed' then
        return jsonb_build_object('request_id', r.request_id, 'status', 'failed', 'message', r.last_error);
    end if;
    if r.status = 'processing' and r.updated_at > now() - p_stale then
        return jsonb_build_object('request_id', r.request_id, 'status', 'busy',
                                  'message', 'This mobile sign-in is being created right now. Try again in a minute.');
    end if;

    v_problem := trimax_private.provisioning_problem(r.username, r.email);
    if v_problem is null and r.attempts >= 5 then
        v_problem := 'Gave up after 5 attempts. Check the Edge Function logs, then try again.';
    end if;
    if v_problem is null then
        select count(*), min(a.id::text)::uuid into v_auth_count, v_auth_id
          from auth.users a where lower(a.email) = lower(r.email);
        if v_auth_count > 1 then
            v_problem := 'More than one Supabase sign-in uses this email. Remove the extra one in Supabase first.';
        end if;
    end if;
    if v_problem is not null then
        update trimax_private.provisioning_requests
           set status = 'failed', last_error = left(v_problem, 300), updated_at = now()
         where request_id = r.request_id;
        perform trimax_private.audit_user(
            case when exists (select 1 from public.users u where u.username = r.requested_by) then r.requested_by else r.username end,
            'account.mobile_invite_failed', r.username, left(v_problem, 300));
        return jsonb_build_object('request_id', r.request_id, 'status', 'failed', 'message', v_problem);
    end if;

    if v_auth_id is not null then
        select a.email_confirmed_at is not null, coalesce(to_jsonb(a) -> 'raw_app_meta_data', '{}'::jsonb)
          into v_confirmed, v_meta from auth.users a where a.id = v_auth_id;
        select m.username into v_linked from trimax_private.mobile_accounts m where m.auth_user_id = v_auth_id;
    end if;

    update trimax_private.provisioning_requests
       set status = 'processing', attempts = attempts + 1, updated_at = now()
     where request_id = r.request_id;

    return jsonb_build_object(
        'request_id', r.request_id,
        'status', 'processing',
        'username', r.username,
        'email', lower(r.email),
        'existing_auth_user_id', v_auth_id,
        'existing_auth_confirmed', v_confirmed,
        'existing_auth_created_by_trimax', coalesce(v_meta ->> 'trimax_provisioned', '') = 'true'
                                           and coalesce(v_meta ->> 'trimax_username', '') = r.username,
        'existing_auth_linked_username', v_linked);
end $function$;

-- ----------------------------------------------------------------------------------------------- 3
-- Edge Function only (service role). Uses a provisioning ticket once and claims its request.
create or replace function public.trimax_service_claim_provisioning_ticket(p_ticket uuid)
 returns jsonb
 language plpgsql
 security definer
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_request bigint;
begin
    if not (trimax_private.is_service_request() or current_setting('role', true) = 'service_role') then
        raise exception using errcode = 'TM403', message = 'Not allowed.';
    end if;
    update trimax_private.provisioning_tickets t
       set used_at = now()
     where t.ticket = p_ticket and t.used_at is null and t.expires_at > now()
    returning t.request_id into v_request;
    if v_request is null then
        raise exception using errcode = 'TM403', message = 'This request has expired. Try again.';
    end if;
    return trimax_private.claim_provisioning_request(v_request, interval '2 minutes');
end $function$;

-- ----------------------------------------------------------------------------------------------- 4
-- Edge Function only (service role). Checks a setup / reset code from public.password_resets for the
-- phone. Wrong codes count against the code (5 tries) and are answered without saying whether the email
-- exists. A right code is used up and gives the person's linked Supabase sign-in. Returns jsonb instead of
-- raising, so a wrong try is still counted.
create or replace function public.trimax_service_use_setup_code(p_email text, p_code text)
 returns jsonb
 language plpgsql
 security definer
 set search_path to 'public', 'pg_temp'
as $function$
declare
    c_bad constant text := 'The email or code is not right, or the code has expired. Ask your administrator for a new code.';
    v_email text := lower(btrim(coalesce(p_email, '')));
    v_code text := upper(btrim(coalesce(p_code, '')));
    v_count int;
    v_username text;
    v_role text;
    v_status text;
    v_auth uuid;
    v_reset record;
begin
    if not (trimax_private.is_service_request() or current_setting('role', true) = 'service_role') then
        raise exception using errcode = 'TM403', message = 'Not allowed.';
    end if;
    if v_email = '' or char_length(v_email) > 254 or v_code !~ '^[A-Z0-9]{6,12}$' then
        return jsonb_build_object('ok', false, 'message', c_bad);
    end if;

    select count(*) into v_count from public.users u where lower(btrim(u.email)) = v_email;
    if v_count <> 1 then
        return jsonb_build_object('ok', false, 'message', c_bad);
    end if;
    select u.username, r.role_name, u.account_status into v_username, v_role, v_status
      from public.users u left join public.roles r on r.role_id = u.role_id
     where lower(btrim(u.email)) = v_email;

    select pr.reset_id, pr.code_hash, pr.attempts into v_reset
      from public.password_resets pr
     where pr.username = v_username and pr.used = false and pr.expires_at > now()
     order by pr.reset_id desc limit 1
       for update;
    if not found then
        return jsonb_build_object('ok', false, 'message', c_bad);
    end if;
    if v_reset.attempts >= 5 then
        update public.password_resets set used = true where reset_id = v_reset.reset_id;
        return jsonb_build_object('ok', false, 'message', 'Too many wrong tries. Ask your administrator for a new code.');
    end if;
    if btrim(v_reset.code_hash) <> encode(sha256(convert_to(v_code, 'UTF8')), 'hex') then
        update public.password_resets set attempts = attempts + 1 where reset_id = v_reset.reset_id;
        return jsonb_build_object('ok', false, 'message', c_bad);
    end if;

    -- The right code: only now say what else is wrong (the code is kept for after it is fixed).
    if v_role is null or v_role not in ('Agent', 'Rider', 'Driver') then
        return jsonb_build_object('ok', false, 'message', 'This account does not use the mobile app.');
    end if;
    if v_status is distinct from 'Active' then
        return jsonb_build_object('ok', false, 'message', 'This account is not active. Contact your administrator.');
    end if;
    select m.auth_user_id into v_auth from trimax_private.mobile_accounts m where m.username = v_username;
    if v_auth is null then
        return jsonb_build_object('ok', false, 'message',
            'Your mobile sign-in is not ready yet. Ask your administrator to press "Create mobile sign-in" on your account.');
    end if;

    update public.password_resets set used = true where reset_id = v_reset.reset_id;
    perform trimax_private.audit_user(v_username, 'account.mobile_password_set', v_username,
                                      'Phone password chosen with an emailed code');
    return jsonb_build_object('ok', true, 'auth_user_id', v_auth, 'username', v_username);
end $function$;

-- ----------------------------------------------------------------------------------------------- 5
-- FIX of the existing public.trimax_service_finish_provisioning (live since before 2026-10-25, never
-- called until now): it wrote audit rows as actor 'provisioning', but production's audit_log has
-- audit_log_actor_username_fkey (actor must be a TRIMAX user), so every call failed and rolled back.
-- Same function, same checks and answers; only the audit actor is now the admin who asked (or the
-- account itself). Found by the local end-to-end test on a production-identical schema.
CREATE OR REPLACE FUNCTION public.trimax_service_finish_provisioning(p_request_id bigint, p_outcome text, p_auth_user_id uuid DEFAULT NULL::uuid, p_message text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_outcome text := lower(btrim(coalesce(p_outcome, '')));
    v_username text;
    v_email text;
    v_req_status text;
    v_problem text;
    v_auth_email text;
    v_meta jsonb;
    v_other text;
    v_actor text;
begin
    if not trimax_private.is_service_request() then
        raise exception using errcode = 'TM403', message = 'Not allowed.';
    end if;
    if v_outcome not in ('invited', 'linked', 'failed') then
        raise exception using errcode = 'TM422', message = 'Unknown outcome.';
    end if;
    select pr.username, pr.email, pr.status, pr.requested_by into v_username, v_email, v_req_status, v_actor
      from trimax_private.provisioning_requests pr where pr.request_id = p_request_id for update;
    -- audit_log.actor_username must be a TRIMAX user: the admin who asked, else the account itself.
    if not exists (select 1 from public.users u where u.username = v_actor) then
        v_actor := v_username;
    end if;
    if v_username is null then
        raise exception using errcode = 'TM404', message = 'Unknown request.';
    end if;
    if v_req_status <> 'processing' then
        raise exception using errcode = 'TM409', message = 'The request is not being processed (it is ' || v_req_status || ').';
    end if;

    if v_outcome = 'failed' then
        v_problem := left(coalesce(nullif(btrim(p_message), ''), 'Provisioning failed.'), 300);
    else
        v_problem := trimax_private.provisioning_problem(v_username, v_email);
        if v_problem is null then
            select a.email, coalesce(to_jsonb(a) -> 'raw_app_meta_data', '{}'::jsonb) into v_auth_email, v_meta
              from auth.users a where a.id = p_auth_user_id;
            if v_auth_email is null or lower(v_auth_email) <> lower(v_email) then
                v_problem := 'The Supabase sign-in does not have this account''s email address.';
            elsif coalesce(v_meta ->> 'trimax_provisioned', '') <> 'true' or coalesce(v_meta ->> 'trimax_username', '') <> v_username then
                v_problem := 'The Supabase sign-in was not created by TRIMAX for this account.';
            else
                select m.username into v_other from trimax_private.mobile_accounts m where m.auth_user_id = p_auth_user_id;
                if v_other is not null and v_other <> v_username then
                    v_problem := 'That Supabase sign-in is already linked to another TRIMAX account.';
                end if;
            end if;
        end if;
    end if;

    if v_problem is not null then
        update trimax_private.provisioning_requests
           set status = 'failed', last_error = v_problem, auth_user_id = coalesce(p_auth_user_id, auth_user_id), updated_at = now()
         where request_id = p_request_id;
        perform trimax_private.audit_user(v_actor, 'account.mobile_invite_failed', v_username, v_problem);
        return jsonb_build_object('request_id', p_request_id, 'status', 'failed', 'message', v_problem);
    end if;

    insert into trimax_private.mobile_accounts (auth_user_id, username, linked_by)
    values (p_auth_user_id, v_username, 'provisioning')
    on conflict do nothing;
    update trimax_private.provisioning_requests
       set status = v_outcome, last_error = null, auth_user_id = p_auth_user_id, updated_at = now()
     where request_id = p_request_id;
    perform trimax_private.audit_user(v_actor,
        case v_outcome when 'invited' then 'account.mobile_invite_sent' else 'account.link_mobile' end, v_username,
        case v_outcome when 'invited' then 'Invitation email sent to ' || v_email
                       else 'Linked to existing TRIMAX-created sign-in ' || v_email end);
    return jsonb_build_object('request_id', p_request_id, 'status', v_outcome);
end $function$;

revoke all on function trimax_private.issue_provisioning_ticket(text, text)               from public;
revoke all on function trimax_private.claim_provisioning_request(bigint, interval)        from public;
revoke all on function public.trimax_service_claim_provisioning_ticket(uuid)              from public, anon, authenticated;
revoke all on function public.trimax_service_use_setup_code(text, text)                   from public, anon, authenticated;
revoke all on function public.trimax_service_finish_provisioning(bigint, text, uuid, text) from public, anon, authenticated;
grant execute on function public.trimax_service_claim_provisioning_ticket(uuid)           to service_role;
grant execute on function public.trimax_service_use_setup_code(text, text)                to service_role;
grant execute on function public.trimax_service_finish_provisioning(bigint, text, uuid, text) to service_role;

commit;
