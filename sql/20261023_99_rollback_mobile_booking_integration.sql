-- =====================================================================================================
-- Rollback for sql/20261023_01_mobile_booking_integration.sql
--
-- Part A (default) turns the mobile integration off: drops the mobile functions, the bookings triggers,
-- the contact index and the trimax_private schema (mobile sign-in links and delivery history).
-- Bookings created from phones STAY in public.bookings with their items, so the desktop, stock and
-- reports remain correct. The added bookings columns are kept (the desktop ignores them).
--
-- Part B (commented out) also drops the added bookings columns. That permanently deletes the delivery
-- address, coordinates, schedule, instructions and driver progress of every mobile booking. Only run
-- it after exporting those columns, and only if you are sure.
--
-- Deliberately NOT undone: the revoked anon/authenticated access to the legacy tables. Restoring it
-- would expose password hashes and customer data through the public API key.
-- =====================================================================================================

begin;
set local lock_timeout = '10s';

-- ---------------------------------------------------------------- Part A
drop function if exists public.trimax_my_account();
drop function if exists public.trimax_list_products();
drop function if exists public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision, jsonb, text, text);
drop function if exists public.trimax_agent_list_bookings(int, int);
drop function if exists public.trimax_agent_get_booking(int);
drop function if exists public.trimax_driver_list_deliveries(text);
drop function if exists public.trimax_driver_get_delivery(int);
drop function if exists public.trimax_driver_update_delivery_status(int, text, text, double precision, double precision, text, text);

drop trigger if exists trimax_bookings_before_write on public.bookings;
drop trigger if exists trimax_bookings_after_write on public.bookings;
drop index if exists public.clients_contact_key_idx;

drop schema if exists trimax_private cascade;

-- ---------------------------------------------------------------- Part B (destructive, opt-in)
-- alter table public.bookings
--     drop constraint if exists bookings_created_via_chk,
--     drop constraint if exists bookings_delivery_status_chk,
--     drop constraint if exists bookings_delivery_point_chk,
--     drop constraint if exists bookings_mobile_text_len_chk,
--     drop constraint if exists bookings_mobile_required_chk,
--     drop constraint if exists bookings_created_by_fk;
-- drop index if exists public.bookings_client_request_id_key;
-- drop index if exists public.bookings_driver_status_idx;
-- drop index if exists public.bookings_created_by_idx;
-- alter table public.bookings
--     drop column if exists created_via,
--     drop column if exists created_by_username,
--     drop column if exists client_request_id,
--     drop column if exists delivery_address,
--     drop column if exists delivery_latitude,
--     drop column if exists delivery_longitude,
--     drop column if exists requested_schedule,
--     drop column if exists special_instructions,
--     drop column if exists delivery_status,
--     drop column if exists delivery_status_at,
--     drop column if exists assigned_at,
--     drop column if exists last_changed_at;

commit;
notify pgrst, 'reload schema';
