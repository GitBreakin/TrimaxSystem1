-- =====================================================================================================
-- SOS and problem reports from the Android app reach the Desktop admin.
--
-- Today the app keeps SOS / problem reports on the phone, and the Desktop's Notifications screen reads
-- public.admin_notifications, which nothing writes. This adds one server function the app can call:
--
--   public.trimax_report_issue(p_kind, p_message, p_booking_id, p_latitude, p_longitude, p_client_request_id)
--     p_kind  'SOS'      -> admin_notifications(kind 'Alert', category 'SOS')
--             'PROBLEM'  -> admin_notifications(kind 'Alert', category 'Delivery problem')
--
-- Rules enforced on the server (not in the app):
--   * signed-in, linked, Active Agent or Driver only (trimax_private.require_account);
--   * a booking, when given, must be the caller's own: created by the agent or assigned to the driver;
--   * message 3..1000 characters; location both-or-neither and in range;
--   * a retry with the same p_client_request_id returns the first result instead of a second alert;
--   * at most 10 reports per person per 10 minutes (flood guard; SOS included).
--
-- Desktop needs no change: it already lists kind, category, title, body and the related booking.
-- ORDER: after 20261025_01 and _02. Re-runnable. Adds 2 nullable columns and 1 index; changes no rows.
-- APPLIED to production on 2026-10-25 with the owner's approval (migration 20261025_03_mobile_issue_reports).
-- =====================================================================================================

begin;

alter table public.admin_notifications add column if not exists reported_by text;
alter table public.admin_notifications add column if not exists client_request_id uuid;
create unique index if not exists admin_notifications_client_request_id_key
    on public.admin_notifications (client_request_id);
create index if not exists admin_notifications_reporter_idx
    on public.admin_notifications (reported_by, created_at desc);

create or replace function public.trimax_report_issue(
    p_kind text,
    p_message text,
    p_booking_id integer default null,
    p_latitude double precision default null,
    p_longitude double precision default null,
    p_client_request_id uuid default null)
 returns jsonb
 language plpgsql
 security definer
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_me text := trimax_private.require_account(null);
    v_role text := trimax_private.role_code(v_me);
    v_kind text := upper(btrim(coalesce(p_kind, '')));
    v_message text := btrim(coalesce(p_message, ''));
    v_category text;
    v_title text;
    v_body text;
    v_name text;
    v_existing record;
    v_id bigint;
    v_at timestamptz;
    v_recent int;
begin
    if v_role not in ('AGENT', 'DRIVER') then
        raise exception using errcode = 'TM403', message = 'Only Agent and Driver accounts can send reports.';
    end if;
    if v_kind not in ('SOS', 'PROBLEM') then
        raise exception using errcode = 'TM422', message = 'Unknown report type.';
    end if;

    -- Retry of a report that was already saved: answer with it, never a second alert.
    if p_client_request_id is not null then
        select n.notification_id, n.created_at, n.reported_by into v_existing
          from public.admin_notifications n where n.client_request_id = p_client_request_id;
        if found then
            if v_existing.reported_by is distinct from v_me then
                raise exception using errcode = 'TM409', message = 'This request id has already been used.';
            end if;
            return jsonb_build_object('notification_id', v_existing.notification_id,
                                      'created_at', v_existing.created_at, 'already_recorded', true);
        end if;
    end if;

    if char_length(v_message) < 3 or char_length(v_message) > 1000 then
        raise exception using errcode = 'TM422', message = 'Describe the problem in 3 to 1000 characters.';
    end if;
    if (p_latitude is null) <> (p_longitude is null)
       or (p_latitude is not null and not (p_latitude between -90 and 90 and p_longitude between -180 and 180))
       or (p_latitude = 0 and p_longitude = 0) then
        raise exception using errcode = 'TM422', message = 'Invalid location.';
    end if;
    if p_booking_id is not null and not exists (
            select 1 from public.bookings b
             where b.booking_id = p_booking_id
               and ((v_role = 'DRIVER' and b.driver_username = v_me)
                    or (v_role = 'AGENT' and b.created_by_username = v_me))) then
        raise exception using errcode = 'TM404', message = 'That booking is not yours.';
    end if;

    -- Flood guard, one report at a time per person.
    perform pg_advisory_xact_lock(hashtextextended('trimax-report:' || v_me, 0));
    select count(*) into v_recent from public.admin_notifications n
     where n.reported_by = v_me and n.created_at > now() - interval '10 minutes';
    if v_recent >= 10 then
        raise exception using errcode = 'TM409',
            message = 'Too many reports in a short time. Call the office if this is an emergency.';
    end if;

    select u.name into v_name from public.users u where u.username = v_me;
    v_category := case v_kind when 'SOS' then 'SOS' else 'Delivery problem' end;
    v_title := case v_kind when 'SOS' then 'SOS from ' else 'Problem reported by ' end
               || coalesce(nullif(btrim(v_name), ''), v_me) || ' (' || lower(v_role) || ')'
               || coalesce(' on booking #' || p_booking_id, '');
    v_body := v_message
              || case when p_latitude is not null
                      then E'\nLocation: ' || round(p_latitude::numeric, 6) || ', ' || round(p_longitude::numeric, 6)
                      else '' end;

    insert into public.admin_notifications (kind, category, title, body, related_type, related_id,
                                            reported_by, client_request_id)
    values ('Alert', v_category, left(v_title, 200), v_body,
            case when p_booking_id is not null then 'booking' else 'user' end,
            coalesce(p_booking_id::text, v_me), v_me, p_client_request_id)
    returning notification_id, created_at into v_id, v_at;

    if p_booking_id is not null then
        perform trimax_private.audit(v_me, case v_kind when 'SOS' then 'report.sos' else 'report.problem' end,
                                     p_booking_id::text, left(v_message, 200));
    else
        perform trimax_private.audit_user(v_me, case v_kind when 'SOS' then 'report.sos' else 'report.problem' end,
                                          v_me, left(v_message, 200));
    end if;

    return jsonb_build_object('notification_id', v_id, 'created_at', v_at, 'already_recorded', false);
end $function$;

revoke all on function public.trimax_report_issue(text, text, integer, double precision, double precision, uuid)
    from public, anon, service_role;
grant execute on function public.trimax_report_issue(text, text, integer, double precision, double precision, uuid)
    to authenticated;

commit;
