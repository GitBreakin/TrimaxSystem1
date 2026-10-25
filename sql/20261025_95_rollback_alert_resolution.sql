-- =====================================================================================================
-- Rollback for 20261025_07_alert_resolution.sql.
--
-- PART A keeps every alert and every resolution already recorded: it removes the resolve function, the
-- check and the index. The Desktop then shows "Resolve" as not available (it checks for the columns and
-- the function) and alerts can still be read and marked read as before.
-- PART B (commented out) also drops the three columns, which DELETES the recorded resolutions and notes.
-- Run it only if you are sure; export the resolved alerts first.
-- =====================================================================================================

begin;

-- PART A ------------------------------------------------------------------------------------------
drop function if exists trimax_private.resolve_alert(text, bigint, text);
alter table public.admin_notifications drop constraint if exists admin_notifications_resolution_chk;
drop index if exists public.admin_notifications_open_alerts_idx;

-- PART B (destructive: removes recorded resolutions) -----------------------------------------------
-- alter table public.admin_notifications drop column if exists resolution_note;
-- alter table public.admin_notifications drop column if exists resolved_by;
-- alter table public.admin_notifications drop column if exists resolved_at;

commit;
