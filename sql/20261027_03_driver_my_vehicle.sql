-- =====================================================================================================
-- The Driver app shows the vehicle the Admin assigned (type and plate number), read from the server.
--
-- Owner's request of 2026-10-27: when the Admin changes a vehicle's type or plate number on the Desktop
-- (Manage Vehicles), the Driver app shows the change without reinstalling. Until now the Driver profile
-- showed fixed sample text ("Motorcycle", "ABC 1234").
--
-- Source of truth: public.vehicles. The plate number is vehicles.vehicle_id (bookings.vehicle_id follows a
-- change through ON UPDATE CASCADE); the driver is vehicles.username.
--
-- What this adds (changes no row):
--   public.trimax_driver_my_vehicle(): the signed-in DRIVER's own assigned vehicles only
--     { "vehicles": [ { "plate_number", "vehicle_type", "status" } ... ], "checked_at": <server time> }
--     An empty list means no vehicle is assigned. The caller is taken from the sign-in (auth.uid() ->
--     trimax_private.mobile_accounts -> users); there is no parameter, so a driver cannot ask for
--     another driver's vehicle. Agents get TM403.
--
-- ORDER: after 20261026_01. Re-runnable. Rollback: 20261027_91_rollback_driver_my_vehicle.sql.
-- NOT applied to production yet.
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.require_account(text)') is null then
        raise exception 'Apply the earlier TRIMAX migrations first (20261023_01 to 20261026_01).';
    end if;
end $$;

create or replace function public.trimax_driver_my_vehicle()
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('DRIVER');
begin
    return jsonb_build_object(
        'vehicles', coalesce((
            select jsonb_agg(jsonb_build_object('plate_number', v.vehicle_id, 'vehicle_type', v.vehicle_type,
                                                'status', v.status)
                             order by v.vehicle_id)
              from public.vehicles v
             where v.username = v_me), '[]'::jsonb),
        'checked_at', now());
end $function$;

revoke all on function public.trimax_driver_my_vehicle() from public, anon;
grant execute on function public.trimax_driver_my_vehicle() to authenticated;
do $$
begin
    -- The Edge Functions and the dashboard use service_role; it does not need the phone functions.
    execute 'revoke all on function public.trimax_driver_my_vehicle() from service_role';
end $$;

commit;
