-- =====================================================================================================
-- The Desktop admin can view the private photos (duty attendance photos, delivery proofs) without any
-- storage key on the admin PC.
--
-- How it works:
--   1. The Desktop (database owner, over JDBC) asks for a one-time ticket for ONE photo:
--        select trimax_private.issue_media_ticket('<admin username>', 'duty-photos', '<uid>/start_....jpg');
--      The function checks the admin is an Active Admin and the photo really exists, writes an audit row
--      and returns a random ticket that works once, within 2 minutes.
--   2. The Desktop posts the ticket to the Edge Function "trimax-admin-media". The function (which holds
--      the project's secret key on the Supabase server, never on a PC) redeems the ticket through
--      public.trimax_service_redeem_media_ticket(...) - callable by the service role only - downloads
--      that one photo from Storage and returns its bytes.
--
-- Phones are not affected: they cannot see the ticket table or call either function.
--
-- ORDER: after 20261025_04. Re-runnable. Creates 1 table in trimax_private and 2 functions. Changes no rows.
-- NOT APPLIED to production. Needs approval.
-- =====================================================================================================

begin;

create table if not exists trimax_private.media_tickets (
    ticket      uuid primary key default gen_random_uuid(),
    bucket      text not null check (bucket in ('duty-photos', 'delivery-proofs')),
    object_path text not null check (object_path ~ '^[A-Za-z0-9-]{1,64}/[A-Za-z0-9_.-]{1,120}\.jpg$'),
    issued_by   text not null,
    issued_at   timestamptz not null default now(),
    expires_at  timestamptz not null default now() + interval '2 minutes',
    used_at     timestamptz
);
create index if not exists media_tickets_issued_at_idx on trimax_private.media_tickets (issued_at);
alter table trimax_private.media_tickets enable row level security;
revoke all on table trimax_private.media_tickets from public, anon, authenticated, service_role;

-- ----------------------------------------------------------------------------------------------- 1
-- Desktop only (database owner). Returns a ticket for one existing photo.
create or replace function trimax_private.issue_media_ticket(p_admin_username text, p_bucket text, p_path text)
 returns uuid
 language plpgsql
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_ticket uuid;
    v_owner text;
begin
    if not exists (select 1 from public.users u join public.roles r on r.role_id = u.role_id
                    where u.username = p_admin_username and r.role_name = 'Admin' and u.account_status = 'Active') then
        raise exception using errcode = 'TM403', message = 'Only an active Admin can view photos.';
    end if;
    if p_bucket not in ('duty-photos', 'delivery-proofs')
       or p_path is null or p_path !~ '^[A-Za-z0-9-]{1,64}/[A-Za-z0-9_.-]{1,120}\.jpg$' then
        raise exception using errcode = 'TM422', message = 'Not a TRIMAX photo.';
    end if;
    if to_regclass('storage.objects') is not null
       and not exists (select 1 from storage.objects o where o.bucket_id = p_bucket and o.name = p_path) then
        raise exception using errcode = 'TM404', message = 'That photo is not on the server.';
    end if;

    -- Housekeeping: tickets are only useful for 2 minutes; keep a day for troubleshooting.
    delete from trimax_private.media_tickets where issued_at < now() - interval '1 day';

    insert into trimax_private.media_tickets (bucket, object_path, issued_by)
    values (p_bucket, p_path, p_admin_username)
    returning ticket into v_ticket;

    -- Who looked at whose photo.
    select m.username into v_owner from trimax_private.mobile_accounts m
     where m.auth_user_id::text = split_part(p_path, '/', 1);
    perform trimax_private.audit_user(p_admin_username, 'media.view', coalesce(v_owner, split_part(p_path, '/', 1)),
                                      p_bucket || '/' || p_path);
    return v_ticket;
end $function$;

revoke all on function trimax_private.issue_media_ticket(text, text, text) from public;

-- ----------------------------------------------------------------------------------------------- 2
-- Edge Function only (service role). Uses a ticket once and says which photo it is for.
create or replace function public.trimax_service_redeem_media_ticket(p_ticket uuid)
 returns jsonb
 language plpgsql
 security definer
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_bucket text;
    v_path text;
begin
    if not (trimax_private.is_service_request() or current_setting('role', true) = 'service_role') then
        raise exception using errcode = 'TM403', message = 'Not allowed.';
    end if;
    update trimax_private.media_tickets t
       set used_at = now()
     where t.ticket = p_ticket and t.used_at is null and t.expires_at > now()
    returning t.bucket, t.object_path into v_bucket, v_path;
    if v_bucket is null then
        raise exception using errcode = 'TM403', message = 'This photo link has expired. Open the photo again.';
    end if;
    return jsonb_build_object('bucket', v_bucket, 'path', v_path);
end $function$;

revoke all on function public.trimax_service_redeem_media_ticket(uuid) from public, anon, authenticated;
grant execute on function public.trimax_service_redeem_media_ticket(uuid) to service_role;

commit;
