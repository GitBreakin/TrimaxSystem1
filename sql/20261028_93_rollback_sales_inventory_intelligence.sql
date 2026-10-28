-- =====================================================================================================
-- ROLLBACK of 20261028_03_sales_inventory_intelligence.sql (only if the owner decides to undo it).
-- Drops the three functions. KEEPS the settings, the alert state and every notification already created.
-- The Desktop then shows "not set up yet" on the Intelligence tab and an empty Insight column.
-- =====================================================================================================

begin;
drop function if exists trimax_private.refresh_inventory_alerts(text);
drop function if exists trimax_private.product_insights(timestamptz, timestamptz);
drop function if exists trimax_private.sold_lines();
commit;
