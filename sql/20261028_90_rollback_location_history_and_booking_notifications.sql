-- =====================================================================================================
-- ROLLBACK of 20261028_01_location_history_and_booking_notifications.sql (only if the owner decides to undo it).
--
-- Puts back the 20261025_01 version of public.trimax_update_location (current position only, no history
-- sample), and drops the new-booking notification trigger, its function and unique index, and the
-- Location History functions (location_history_for, purge_location_history, distance_m).
-- KEEPS all data: trimax_private.location_history (still append-only), its settings, and every
-- admin_notifications row (including "New booking" ones already created).
-- =====================================================================================================

begin;

drop trigger if exists trimax_bookings_notify_new on public.bookings;
drop function if exists trimax_private.bookings_notify_new();
drop index if exists public.admin_notifications_new_booking_key;

drop function if exists trimax_private.location_history_for(text, text, timestamptz, timestamptz, integer, integer);
drop function if exists trimax_private.purge_location_history(text);

-- 20261025_01 version (byte-for-byte as in 20261025_01_live_baseline.sql):
CREATE OR REPLACE FUNCTION public.trimax_update_location(p_latitude double precision, p_longitude double precision, p_accuracy double precision DEFAULT NULL::double precision, p_speed double precision DEFAULT NULL::double precision, p_bearing double precision DEFAULT NULL::double precision, p_is_on_duty boolean DEFAULT true)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_me text := trimax_private.require_account(null);
    v_role text;
    v_last timestamptz;
    v_accuracy double precision := p_accuracy;
    v_speed double precision := p_speed;
    v_bearing double precision := p_bearing;
    v_vehicles int := 0;
begin
    if p_is_on_duty is distinct from true then
        raise exception using errcode = 'TM422', message = 'Your location is only shared while you are on duty.';
    end if;
    -- "between" is false for NULL, NaN and infinity, so this also rejects those.
    if not (p_latitude between -90 and 90) or not (p_longitude between -180 and 180)
       or (p_latitude = 0 and p_longitude = 0) then
        raise exception using errcode = 'TM422', message = 'Invalid GPS position.';
    end if;
    if v_accuracy is not null and not (v_accuracy between 0 and 10000) then
        if v_accuracy > 10000 and v_accuracy < 'infinity'::float8 then
            raise exception using errcode = 'TM422', message = 'The GPS position is too inaccurate to share.';
        end if;
        v_accuracy := null;
    end if;
    if v_speed is not null and not (v_speed between 0 and 100) then v_speed := null; end if;
    if v_bearing is not null and not (v_bearing between 0 and 360) then v_bearing := null; end if;

    select max(l.recorded_at) into v_last from public.user_locations l where l.username = v_me;
    if v_last is not null and v_last > now() - interval '2 seconds' then
        return jsonb_build_object('stored', false, 'throttled', true, 'recorded_at', v_last);
    end if;

    update public.user_locations
       set latitude = p_latitude, longitude = p_longitude, accuracy_m = v_accuracy,
           speed_mps = v_speed, bearing_deg = v_bearing, recorded_at = now()
     where username = v_me;
    if not found then
        insert into public.user_locations (username, latitude, longitude, accuracy_m, speed_mps, bearing_deg, recorded_at)
        values (v_me, p_latitude, p_longitude, v_accuracy, v_speed, v_bearing, now());
    end if;

    v_role := trimax_private.role_code(v_me);
    if v_role = 'DRIVER' then
        update public.vehicles
           set latitude = p_latitude, longitude = p_longitude, last_location_at = now()
         where username = v_me;
        get diagnostics v_vehicles = row_count;
    end if;

    return jsonb_build_object('stored', true, 'throttled', false, 'recorded_at', now(), 'vehicles_updated', v_vehicles);
end $function$;

drop function if exists trimax_private.distance_m(double precision, double precision, double precision, double precision);

revoke all on function public.trimax_update_location(double precision, double precision, double precision, double precision, double precision, boolean) from public, anon, service_role;
grant execute on function public.trimax_update_location(double precision, double precision, double precision, double precision, double precision, boolean) to authenticated;

commit;
