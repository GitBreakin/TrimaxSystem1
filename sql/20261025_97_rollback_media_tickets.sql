-- =====================================================================================================
-- Rollback for 20261025_05_admin_media_tickets.sql.
-- Removes both functions and the ticket table (tickets are short-lived; nothing of value is lost; the
-- audit_log rows of who viewed which photo stay). Undeploy the Edge Function "trimax-admin-media" in the
-- Supabase dashboard as well; without the redeem function it can no longer return any photo.
-- =====================================================================================================

begin;
drop function if exists public.trimax_service_redeem_media_ticket(uuid);
drop function if exists trimax_private.issue_media_ticket(text, text, text);
drop table if exists trimax_private.media_tickets;
commit;
