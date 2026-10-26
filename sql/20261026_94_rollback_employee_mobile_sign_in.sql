-- =====================================================================================================
-- Rollback of 20261026_01_employee_mobile_sign_in.sql.
-- Removes the provisioning tickets and the 4 functions, and puts back the previous
-- public.trimax_service_finish_provisioning. Keeps every account, every Supabase sign-in,
-- every link in trimax_private.mobile_accounts and the provisioning_requests history: people who already
-- have a mobile sign-in keep it. Afterwards the Desktop shows "not installed" for automatic sign-ins and
-- "Link mobile sign-in" works as before. Re-runnable.
-- =====================================================================================================
begin;
drop function if exists public.trimax_service_use_setup_code(text, text);
drop function if exists public.trimax_service_claim_provisioning_ticket(uuid);
drop function if exists trimax_private.claim_provisioning_request(bigint, interval);
drop function if exists trimax_private.issue_provisioning_ticket(text, text);
drop table if exists trimax_private.provisioning_tickets;
-- The finish function as it was before 20261026_01 (from sql/20261025_01_live_baseline.sql).
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
begin
    if not trimax_private.is_service_request() then
        raise exception using errcode = 'TM403', message = 'Not allowed.';
    end if;
    if v_outcome not in ('invited', 'linked', 'failed') then
        raise exception using errcode = 'TM422', message = 'Unknown outcome.';
    end if;
    select pr.username, pr.email, pr.status into v_username, v_email, v_req_status
      from trimax_private.provisioning_requests pr where pr.request_id = p_request_id for update;
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
        perform trimax_private.audit_user('provisioning', 'account.mobile_invite_failed', v_username, v_problem);
        return jsonb_build_object('request_id', p_request_id, 'status', 'failed', 'message', v_problem);
    end if;

    insert into trimax_private.mobile_accounts (auth_user_id, username, linked_by)
    values (p_auth_user_id, v_username, 'provisioning')
    on conflict do nothing;
    update trimax_private.provisioning_requests
       set status = v_outcome, last_error = null, auth_user_id = p_auth_user_id, updated_at = now()
     where request_id = p_request_id;
    perform trimax_private.audit_user('provisioning',
        case v_outcome when 'invited' then 'account.mobile_invite_sent' else 'account.link_mobile' end, v_username,
        case v_outcome when 'invited' then 'Invitation email sent to ' || v_email
                       else 'Linked to existing TRIMAX-created sign-in ' || v_email end);
    return jsonb_build_object('request_id', p_request_id, 'status', v_outcome);
end $function$;
revoke all on function public.trimax_service_finish_provisioning(bigint, text, uuid, text) from public, anon, authenticated;
grant execute on function public.trimax_service_finish_provisioning(bigint, text, uuid, text) to service_role;
commit;
