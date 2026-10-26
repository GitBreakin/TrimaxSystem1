-- =====================================================================================================
-- Rollback for 20261025_02 and 20261025_03 (Stage B).
--
-- 20261025_01_live_baseline.sql is NOT rolled back here: it describes what production already runs,
-- and removing it would switch off live GPS, duty and mobile account features.
-- Note: 20261023_99_rollback_mobile_booking_integration.sql predates the baseline; if it is ever run,
-- the baseline's public functions (trimax_update_location, trimax_*_duty_*, trimax_service_*) must be
-- dropped first, because they depend on trimax_private helpers that rollback removes.
--
-- PART A keeps every row: removes the report function and restores the previous settings.
-- PART B (commented out) also drops the two columns added to admin_notifications, which deletes
-- who-reported-what and the retry ids. Run it only if you are sure.
-- =====================================================================================================

begin;

-- PART A ------------------------------------------------------------------------------------------
drop function if exists public.trimax_report_issue(text, text, integer, double precision, double precision, uuid);

alter function trimax_private.hub_location()        reset search_path;
alter function trimax_private.is_service_request()  reset search_path;
alter function public.refresh_booking_total()       reset search_path;
alter function public.audit_log_block_changes()     reset search_path;

grant execute on function public.refresh_booking_total()   to public, anon, authenticated;
grant execute on function public.audit_log_block_changes() to public, anon, authenticated;

-- PART B (data loss: uncomment deliberately) ------------------------------------------------------
-- drop index if exists public.admin_notifications_reporter_idx;
-- drop index if exists public.admin_notifications_client_request_id_key;
-- alter table public.admin_notifications drop column if exists client_request_id;
-- alter table public.admin_notifications drop column if exists reported_by;

commit;
