-- =====================================================================================================
-- Stage G: acknowledge and resolve SOS / problem reports (admin_notifications, kind 'Alert').
--
-- Until now an alert could only be marked read. An SOS needs more than that: the office has to record
-- that it was dealt with, by whom, when, and what was done, and must be able to find the alerts that are
-- still open. This adds:
--   * admin_notifications.resolved_at / resolved_by / resolution_note (all null = not resolved).
--     "Acknowledged" is the existing read_at / read_by (the office has seen it).
--   * trimax_private.resolve_alert(admin, notification_id, note): Desktop only (database owner, like the
--     other trimax_private functions). Checks the admin is an active Admin, the row is an Alert and still
--     open, needs a note (1-1000 characters), also acknowledges it if nobody had, writes the audit log.
--     Two admins resolving the same alert: the second gets "already resolved by ..." and nothing changes.
--   * An index for "open alerts".
--
-- Phones are not affected: admin_notifications has RLS on and no policies (phones cannot read it), and
-- trimax_report_issue (20261025_03) keeps inserting exactly as before. Resolving never deletes an alert.
--
-- ORDER: after 20261025_03. Re-runnable. Adds 3 nullable columns, 1 check, 1 index, 1 function.
-- Changes no rows. NOT APPLIED to production. Needs approval.
-- =====================================================================================================

begin;

-- ----------------------------------------------------------------------------------------------- 1
alter table public.admin_notifications add column if not exists resolved_at timestamptz;
alter table public.admin_notifications add column if not exists resolved_by text;
alter table public.admin_notifications add column if not exists resolution_note text;

do $$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.admin_notifications'::regclass
                   and conname = 'admin_notifications_resolution_chk') then
        alter table public.admin_notifications add constraint admin_notifications_resolution_chk check (
            (resolved_at is null and resolved_by is null and resolution_note is null)
            or (kind = 'Alert' and resolved_at is not null and resolved_by is not null
                and resolution_note is not null and length(btrim(resolution_note)) between 1 and 1000));
    end if;
end $$;

create index if not exists admin_notifications_open_alerts_idx
    on public.admin_notifications using btree (created_at desc) where (kind = 'Alert' and resolved_at is null);

-- ----------------------------------------------------------------------------------------------- 2
create or replace function trimax_private.resolve_alert(p_admin_username text, p_notification_id bigint, p_note text)
 returns jsonb
 language plpgsql
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_note text := btrim(coalesce(p_note, ''));
    v_kind text;
    v_category text;
    v_resolved_at timestamptz;
    v_resolved_by text;
    v_now timestamptz := now();
begin
    if not exists (select 1 from public.users u join public.roles r on r.role_id = u.role_id
                    where u.username = p_admin_username and r.role_name = 'Admin' and u.account_status = 'Active') then
        raise exception using errcode = 'TM403', message = 'Only an active Admin can resolve alerts.';
    end if;
    if length(v_note) = 0 then
        raise exception using errcode = 'TM422', message = 'Write what was done before resolving the alert.';
    end if;
    if length(v_note) > 1000 then
        raise exception using errcode = 'TM422', message = 'The note is too long (1000 characters at most).';
    end if;

    select n.kind, n.category, n.resolved_at, n.resolved_by
      into v_kind, v_category, v_resolved_at, v_resolved_by
      from public.admin_notifications n
     where n.notification_id = p_notification_id
       for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'That alert no longer exists.';
    end if;
    if v_kind <> 'Alert' then
        raise exception using errcode = 'TM422', message = 'Only alerts can be resolved; announcements can only be marked read.';
    end if;
    if v_resolved_at is not null then
        raise exception using errcode = 'TM409', message = format('Already resolved by %s at %s.', v_resolved_by,
                              to_char(v_resolved_at at time zone 'Asia/Manila', 'YYYY-MM-DD HH24:MI'));
    end if;

    update public.admin_notifications
       set resolved_at = v_now,
           resolved_by = p_admin_username,
           resolution_note = v_note,
           read_at = coalesce(read_at, v_now),        -- resolving also acknowledges
           read_by = coalesce(read_by, p_admin_username)
     where notification_id = p_notification_id;

    if to_regclass('public.audit_log') is not null then
        insert into public.audit_log (actor_username, action, target_type, target_id, result, details)
        values (p_admin_username, 'notification.resolve', 'notification', p_notification_id::text, 'success',
                left('Alert: ' || coalesce(v_category, ''), 500));
    end if;
    return jsonb_build_object('notification_id', p_notification_id, 'resolved_at', v_now, 'resolved_by', p_admin_username);
end $function$;

revoke all on function trimax_private.resolve_alert(text, bigint, text) from public;

commit;
