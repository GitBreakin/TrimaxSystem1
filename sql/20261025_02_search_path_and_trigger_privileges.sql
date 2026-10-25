-- =====================================================================================================
-- Clears the Supabase security advisor warnings "Function Search Path Mutable" (4 functions) and closes
-- the API's EXECUTE on the two legacy trigger functions.
--
-- ORDER: after 20261025_01_live_baseline.sql. Re-runnable. Changes no data and no behaviour:
--   * every function below only uses objects in "public" (or none), so pinning search_path to
--     public, pg_temp resolves names exactly as before;
--   * trigger functions are not checked for EXECUTE when a trigger fires (only when it is created), so
--     revoking EXECUTE from the API roles leaves the triggers working while /rest/v1/rpc can no longer
--     reach them.
-- NOT APPLIED to production. Needs approval.
-- =====================================================================================================

begin;

alter function trimax_private.hub_location()        set search_path = public, pg_temp;
alter function trimax_private.is_service_request()  set search_path = public, pg_temp;
alter function public.refresh_booking_total()       set search_path = public, pg_temp;
alter function public.audit_log_block_changes()     set search_path = public, pg_temp;

revoke execute on function public.refresh_booking_total()   from public, anon, authenticated;
revoke execute on function public.audit_log_block_changes() from public, anon, authenticated;

commit;
