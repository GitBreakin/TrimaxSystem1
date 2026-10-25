-- =====================================================================================================
-- ROLLBACK of sql/20261025_01_driver_started_delivered.sql. Changes no data.
-- Puts back the driver rules that were installed before it:
--   * with sql/20261024_01 installed: ASSIGNED -> ON_THE_WAY -> ARRIVED -> DELIVERED (the app's
--     "Start Delivery" then fails again with "A delivery cannot go from ASSIGNED to STARTED");
--   * with only sql/20261023_01: ASSIGNED -> STARTED -> [PICKED_UP] -> ON_THE_WAY -> ARRIVED -> DELIVERED.
-- Bookings already in STARTED stay valid in both: they can still move on and finish.
-- The status function goes back to the version without the retry answer (same parameters and grants).
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('public.trimax_my_duty_status()') is not null then
        -- 20261024_01 rules
        create or replace function trimax_private.delivery_transition_allowed(p_from text, p_to text)
        returns boolean
        language sql immutable
        set search_path = public, pg_temp
        as $f$
            select (p_from, p_to) in (
                ('ASSIGNED',   'ON_THE_WAY'),
                ('ON_THE_WAY', 'ARRIVED'),   ('ON_THE_WAY', 'FAILED'),
                ('ARRIVED',    'DELIVERED'), ('ARRIVED',    'FAILED'),
                ('STARTED',    'ON_THE_WAY'), ('STARTED',   'ARRIVED'), ('STARTED',   'FAILED'),
                ('PICKED_UP',  'ON_THE_WAY'), ('PICKED_UP', 'ARRIVED'), ('PICKED_UP', 'FAILED'))
        $f$;
        comment on column public.bookings.delivery_status is
            'Driver progress: ASSIGNED, ON_THE_WAY, ARRIVED, DELIVERED, FAILED (STARTED / PICKED_UP only on rows from older app versions).';
    else
        -- 20261023_01 rules
        create or replace function trimax_private.delivery_transition_allowed(p_from text, p_to text)
        returns boolean
        language sql immutable
        set search_path = public, pg_temp
        as $f$
            select (p_from, p_to) in (
                ('ASSIGNED',   'STARTED'),
                ('STARTED',    'PICKED_UP'), ('STARTED',    'ON_THE_WAY'),
                ('PICKED_UP',  'ON_THE_WAY'),
                ('ON_THE_WAY', 'ARRIVED'),
                ('ARRIVED',    'DELIVERED'),
                ('STARTED',    'FAILED'), ('PICKED_UP', 'FAILED'), ('ON_THE_WAY', 'FAILED'), ('ARRIVED', 'FAILED'))
        $f$;
        comment on column public.bookings.delivery_status is
            'Driver progress: ASSIGNED, STARTED, PICKED_UP, ON_THE_WAY, ARRIVED, DELIVERED, FAILED. Maintained by trimax_private triggers and RPCs.';
    end if;
end $$;

-- The status function as it was before 20261025_01 (behaves the same under either set of rules above).
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
begin
    select b.status, coalesce(b.delivery_status, 'ASSIGNED')
      into v_status, v_from
      from public.bookings b
     where b.booking_id = p_booking_id and b.driver_username = v_me
       for update;
    if v_status is null then
        raise exception using errcode = 'TM404', message = 'This delivery is not assigned to you.';
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

commit;
