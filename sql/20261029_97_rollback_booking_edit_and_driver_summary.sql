-- =====================================================================================================
-- Rollback of 20261029_03_booking_edit_and_driver_summary.sql: drops the 3 functions.
-- Orders already changed with it stay as they are (with their stock history and audit rows). The Desktop
-- then hides "Edit order", and the driver app shows "-" for its numbers.
-- =====================================================================================================

begin;
drop function if exists public.trimax_driver_summary();
drop function if exists trimax_private.update_booking_items(text, integer, text, jsonb, text);
drop function if exists trimax_private.booking_items_signature(integer);
commit;
