-- =====================================================================================================
-- TRIMAX: simple driver flow  ASSIGNED -> STARTED -> DELIVERED  (FAILED once started)
-- File: sql/20261025_01_driver_started_delivered.sql
-- Requires: sql/20261023_01_mobile_booking_integration.sql. Works with or without
-- sql/20261024_01_hub_gps_duty_provisioning.sql; if you (re-)run 20261024_01 later, run this file again
-- after it, because 20261024_01 sets the older ON_THE_WAY / ARRIVED rules.
--
-- WHY: 20261024_01 made the first driver step ASSIGNED -> ON_THE_WAY, but the driver app sends
-- ASSIGNED -> STARTED, so "Start Delivery" failed with "A delivery cannot go from ASSIGNED to STARTED".
-- The agreed flow is now:
--   Start Delivery           ASSIGNED -> STARTED     (booking must be Confirmed; it becomes In Progress)
--   Drop Off / Complete      STARTED  -> DELIVERED   (recipient name required; booking becomes Completed)
--   Report a problem         STARTED  -> FAILED      (reason required; booking stays In Progress for the admin)
-- Nothing can move INTO PICKED_UP, ON_THE_WAY or ARRIVED any more. Rows that older app builds left in one
-- of those states can only finish (DELIVERED) or fail (FAILED), so no booking gets stuck.
--
-- WHAT CHANGES (no table, row, policy or grant is changed; only two functions are replaced):
--   1. trimax_private.delivery_transition_allowed(from, to): the rules above.
--   2. public.trimax_driver_update_delivery_status(...): same parameters, same checks (signed-in Active
--      driver, only his own assigned booking, Confirmed before starting, expected status must match).
--      New: an exact retry of a step this driver already made (double tap, or the reply was lost) returns
--      the saved booking with "already_recorded": true instead of an error. Nothing is written twice.
--   Duty START / END (trimax_record_duty_event) never touches bookings; that is unchanged.
--
-- BEFORE RUNNING: back up; run in the Supabase SQL editor as "postgres". One transaction: all or nothing.
-- Safe to re-run. ROLLBACK: sql/20261025_99_rollback_driver_started_delivered.sql
-- =====================================================================================================

begin;

do $$
begin
    if to_regclass('trimax_private.delivery_events') is null
       or to_regprocedure('trimax_private.require_account(text)') is null
       or to_regprocedure('trimax_private.booking_json(integer, boolean)') is null then
        raise exception 'Run sql/20261023_01_mobile_booking_integration.sql first.';
    end if;
end $$;

-- -----------------------------------------------------------------------------------------------------
-- 1. Allowed driver steps
-- -----------------------------------------------------------------------------------------------------
create or replace function trimax_private.delivery_transition_allowed(p_from text, p_to text)
returns boolean
language sql immutable
set search_path = public, pg_temp
as $$
    select (p_from, p_to) in (
        ('ASSIGNED',   'STARTED'),                              -- Start Delivery
        ('STARTED',    'DELIVERED'), ('STARTED',    'FAILED'),  -- Drop Off / Complete, or report a problem
        -- Only for rows saved by older app builds that still had intermediate steps:
        ('PICKED_UP',  'DELIVERED'), ('PICKED_UP',  'FAILED'),
        ('ON_THE_WAY', 'DELIVERED'), ('ON_THE_WAY', 'FAILED'),
        ('ARRIVED',    'DELIVERED'), ('ARRIVED',    'FAILED'))
$$;

-- -----------------------------------------------------------------------------------------------------
-- 2. The driver's status update (same signature and grants as before)
-- -----------------------------------------------------------------------------------------------------
create or replace function public.trimax_driver_update_delivery_status(
    p_booking_id int,
    p_expected_status text,
    p_new_status text,
    p_latitude double precision default null,
    p_longitude double precision default null,
    p_note text default null,
    p_recipient_name text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me text := trimax_private.require_account('DRIVER');
    v_status text;
    v_from text;
    v_to text := upper(btrim(coalesce(p_new_status, '')));
    v_expected text := upper(btrim(coalesce(p_expected_status, '')));
    v_note text := nullif(btrim(p_note), '');
    v_recipient text := nullif(btrim(p_recipient_name), '');
    v_new_booking_status text;
    v_event_note text;
    v_last record;
begin
    -- Only the driver the booking is assigned to; the row lock makes concurrent calls wait their turn.
    select b.status, coalesce(b.delivery_status, 'ASSIGNED')
      into v_status, v_from
      from public.bookings b
     where b.booking_id = p_booking_id and b.driver_username = v_me
       for update;
    if v_status is null then
        raise exception using errcode = 'TM404', message = 'This delivery is not assigned to you.';
    end if;

    -- A retry of a step that already happened (double tap, or the phone lost the reply): answer with the
    -- saved booking instead of an error, but only if this driver's latest step on it is exactly that one.
    if v_from = v_to and v_expected <> v_from then
        select e.from_status, e.to_status, e.actor_username
          into v_last
          from trimax_private.delivery_events e
         where e.booking_id = p_booking_id and e.event_type = 'delivery_status'
         order by e.occurred_at desc, e.event_id desc
         limit 1;
        if found and v_last.from_status = v_expected and v_last.to_status = v_to and v_last.actor_username = v_me then
            return trimax_private.booking_json(p_booking_id, true) || jsonb_build_object('already_recorded', true);
        end if;
    end if;

    if v_status in ('Completed', 'Cancelled') then
        raise exception using errcode = 'TM409', message = 'Booking #' || p_booking_id || ' is already ' || v_status || '.';
    end if;
    if v_expected <> v_from then
        raise exception using errcode = 'TM409', detail = v_from,
            message = 'This delivery changed since you opened it (it is now ' || v_from || '). Refresh and try again.';
    end if;
    if not trimax_private.delivery_transition_allowed(v_from, v_to) then
        raise exception using errcode = 'TM422', message = 'A delivery cannot go from ' || v_from || ' to ' || v_to || '.';
    end if;
    if (p_latitude is null) <> (p_longitude is null)
       or (p_latitude is not null and not (p_latitude between -90 and 90 and p_longitude between -180 and 180)) then
        raise exception using errcode = 'TM422', message = 'Invalid location.';
    end if;
    if char_length(coalesce(v_note, '')) > 500 or char_length(coalesce(v_recipient, '')) > 100 then
        raise exception using errcode = 'TM422', message = 'The note or the recipient name is too long.';
    end if;

    v_new_booking_status := v_status;
    if v_from = 'ASSIGNED' then
        -- Starting needs the administrator's approval.
        if v_status = 'Pending' then
            raise exception using errcode = 'TM409',
                message = 'Booking #' || p_booking_id || ' is waiting for the administrator to confirm it. You can start once it is Confirmed.';
        end if;
        if v_status = 'Confirmed' then
            v_new_booking_status := 'In Progress';
        end if;
    end if;
    if v_to = 'DELIVERED' then
        if v_recipient is null then
            raise exception using errcode = 'TM422', message = 'Enter the name of the person who received the delivery.';
        end if;
        v_new_booking_status := 'Completed';
    elsif v_to = 'FAILED' then
        if v_note is null then
            raise exception using errcode = 'TM422', message = 'Enter the reason the delivery failed.';
        end if;
    end if;

    update public.bookings
       set delivery_status = v_to,
           delivery_status_at = now(),
           status = v_new_booking_status
     where booking_id = p_booking_id;

    v_event_note := concat_ws(' | ', case when v_recipient is not null then 'Received by ' || v_recipient end, v_note);
    insert into trimax_private.delivery_events (booking_id, actor_username, source, event_type, from_status, to_status,
                                                booking_status, latitude, longitude, note)
    values (p_booking_id, v_me, 'mobile', 'delivery_status', v_from, v_to, v_new_booking_status,
            p_latitude, p_longitude, nullif(v_event_note, ''));

    perform trimax_private.audit(v_me, 'booking.delivery_status', p_booking_id::text,
        v_from || ' -> ' || v_to || coalesce(' (' || nullif(v_event_note, '') || ')', ''));
    if v_new_booking_status <> v_status then
        perform trimax_private.audit(v_me, 'booking.status_change', p_booking_id::text,
            v_status || ' -> ' || v_new_booking_status || ' (driver ' || lower(replace(v_to, '_', ' ')) || ')');
    end if;

    return trimax_private.booking_json(p_booking_id, true);
end $$;

comment on column public.bookings.delivery_status is
    'Driver progress: ASSIGNED, STARTED, DELIVERED, FAILED (PICKED_UP / ON_THE_WAY / ARRIVED only on rows from older app builds).';

commit;
