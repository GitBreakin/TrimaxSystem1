-- =====================================================================================================
-- ROLLBACK of sql/20261024_01_hub_gps_duty_provisioning.sql
--
-- Part A (default) removes the functions and storage policies this migration added and restores the
-- delivery rules of sql/20261023_01 (with the pickup step). It KEEPS all data: duty events, provisioning
-- requests, user_locations rows, vehicle positions, mobile links created by provisioning, and the
-- duty-photos bucket with its files.
-- Part B (commented out) also drops the two private tables. That deletes duty history; export it first.
--
-- After Part A, re-run sql/20261023_01_mobile_booking_integration.sql to restore its version of
-- trimax_driver_update_delivery_status (it is re-runnable and does not touch data).
-- =====================================================================================================

begin;
set local lock_timeout = '10s';

drop function if exists public.trimax_update_location(double precision, double precision, double precision, double precision, double precision, boolean);
drop function if exists public.trimax_record_duty_event(text, text, double precision, double precision, uuid);
drop function if exists public.trimax_my_duty_status();
drop function if exists public.trimax_service_claim_provisioning(int);
drop function if exists public.trimax_service_finish_provisioning(bigint, text, uuid, text);
drop function if exists trimax_private.request_mobile_access(text, text);
drop function if exists trimax_private.provisioning_problem(text, text);
drop function if exists trimax_private.is_service_request();
drop function if exists trimax_private.duty_status_json(text);
drop function if exists trimax_private.duty_photo_uploaded(text, uuid);
drop function if exists trimax_private.audit_user(text, text, text, text);
drop function if exists trimax_private.role_code(text);
drop function if exists trimax_private.hub_location();

-- Previous delivery rules (with STARTED and the optional PICKED_UP step).
create or replace function trimax_private.delivery_transition_allowed(p_from text, p_to text)
returns boolean
language sql immutable
set search_path = public, pg_temp
as $$
    select (p_from, p_to) in (
        ('ASSIGNED',   'STARTED'),
        ('STARTED',    'PICKED_UP'), ('STARTED', 'ON_THE_WAY'),
        ('PICKED_UP',  'ON_THE_WAY'),
        ('ON_THE_WAY', 'ARRIVED'),
        ('ARRIVED',    'DELIVERED'),
        ('STARTED',    'FAILED'), ('PICKED_UP', 'FAILED'), ('ON_THE_WAY', 'FAILED'), ('ARRIVED', 'FAILED'))
$$;

do $$
begin
    if to_regclass('storage.objects') is not null then
        execute 'drop policy if exists "trimax duty photo upload own folder" on storage.objects';
        execute 'drop policy if exists "trimax duty photo read own folder" on storage.objects';
    end if;
end $$;

-- Part B: uncomment to delete the duty history and the provisioning queue.
-- drop table if exists trimax_private.duty_events;
-- drop table if exists trimax_private.provisioning_requests;

commit;

notify pgrst, 'reload schema';
