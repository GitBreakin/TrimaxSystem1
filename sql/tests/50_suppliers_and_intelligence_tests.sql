-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261028_02_suppliers.sql and
-- 20261028_03_sales_inventory_intelligence.sql. Desktop steps as app_owner (the database owner, like the
-- Desktop over JDBC); phone steps as authenticated. Every check prints PASS or stops with FAIL.
-- Sales history is made up HERE ONLY (a disposable test database), with dates in the past, to check the rules.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana '00000000-0000-0000-0000-00000000a0a0'

-- ================================================================== 1. Suppliers
select test.ok(not has_table_privilege('authenticated', 'public.suppliers', 'select')
               and not has_table_privilege('anon', 'public.suppliers', 'select')
               and not has_function_privilege('authenticated', 'trimax_private.save_supplier(text,text)', 'execute'),
               'phones cannot read or add suppliers');
select test.ok((select count(*) from suppliers) = (select count(distinct lower(btrim(supplier))) from products where nullif(btrim(supplier), '') is not null),
               'every supplier name the products already use is in the list once');

set role app_owner;
select test.expect_error($$select * from trimax_private.save_supplier('agent.ana', 'Test Supplier')$$, 'TM403',
                         'only an Admin may add a supplier');
select test.expect_error($$select * from trimax_private.save_supplier('marc.admin', ' x ')$$, 'TM422',
                         'a one-letter supplier name is refused');
select * from trimax_private.save_supplier('marc.admin', '  Bulacan   Water  Co. ') as s1 \gset s1_
select test.ok(:'s1_created'::boolean and :'s1_name' = 'Bulacan Water Co.',
               'a new supplier is added with its spaces tidied');
select * from trimax_private.save_supplier('marc.admin', 'BULACAN WATER CO.') as s2 \gset s2_
select test.ok(not :'s2_created'::boolean and :s2_supplier_id = :s1_supplier_id
               and (select count(*) = 1 from suppliers where name_key = 'bulacan water co.'),
               'the same name in other capitals returns the existing supplier (no duplicate)');
select test.ok(exists (select 1 from audit_log where action = 'supplier.create' and target_id = :'s1_supplier_id'),
               'adding a supplier is audited');
select trimax_private.save_product('marc.admin', null, 'Test Supplier Trigger Item', 'Brand New Supplier Inc', null, null, null, null, 10, 0, false) as stp \gset
select test.ok(exists (select 1 from suppliers where name = 'Brand New Supplier Inc'),
               'saving a product with a new supplier name adds it to the list');
select test.ok((select supplier = 'Brand New Supplier Inc' from products where product_id = :stp),
               'products keep their supplier text as before');
reset role;

-- ================================================================== 2. Test products and made-up history
set role app_owner;
select c.client_id as cid from clients c order by c.client_id limit 1 \gset
select set_config('test.cid', :'cid', false);
select trimax_private.save_product('marc.admin', null, 'II Fast Low',   'II Supplier', 20, 'AMOUNT', 5, 0, null, 5,   false) as p_fast \gset
select trimax_private.save_product('marc.admin', null, 'II Slow Over',  'II Supplier', 20, 'AMOUNT', 5, 0, null, 200, false) as p_slow \gset
select trimax_private.save_product('marc.admin', null, 'II Never Sold', 'II Supplier', 20, 'AMOUNT', 5, 0, null, 50,  false) as p_never \gset
select trimax_private.save_product('marc.admin', null, 'II Stopped',    'II Supplier', 20, 'AMOUNT', 5, 0, null, 10,  false) as p_stop \gset
select trimax_private.save_product('marc.admin', null, 'II New',        'II Supplier', 20, 'AMOUNT', 5, 0, null, 40,  false) as p_new \gset
select trimax_private.save_product('marc.admin', null, 'II Zero Stock', 'II Supplier', 20, 'AMOUNT', 5, 0, null, 0,   false) as p_zero \gset
select trimax_private.save_product('marc.admin', null, 'II Decline',    'II Supplier', 20, 'AMOUNT', 5, 0, null, 100, false) as p_decl \gset

-- One booking with one line, completed (or not) at a given time in the past.
create function pg_temp.sold(p_product int, p_qty int, p_days_ago numeric, p_status text) returns void
language plpgsql as $f$
declare b int;
begin
    insert into bookings (client_id, status, transc_date) values (current_setting('test.cid')::int, 'Pending', now() - make_interval(secs => (p_days_ago * 86400)::float8 + 3600))
    returning booking_id into b;
    insert into booking_items (booking_id, product_id, quantity, unit_price, subtotal) values (b, p_product, p_qty, 25, p_qty * 25);
    if p_status <> 'Pending' then
        update bookings set status = p_status where booking_id = b;
    end if;
    if p_status = 'Completed' then
        update bookings set completed_at = now() - make_interval(secs => (p_days_ago * 86400)::float8) where booking_id = b;
    end if;
end $f$;
-- Known since: an old cancelled booking line (not a sale) dates when the product was first recorded.
select pg_temp.sold(:p_fast, 1, 60, 'Cancelled'), pg_temp.sold(:p_slow, 1, 60, 'Cancelled'),
       pg_temp.sold(:p_never, 1, 60, 'Cancelled'), pg_temp.sold(:p_stop, 1, 90, 'Cancelled'),
       pg_temp.sold(:p_zero, 1, 60, 'Cancelled'), pg_temp.sold(:p_decl, 1, 90, 'Cancelled');
-- Fast Low: 4 completed bookings of 10 in the last 30 days (+ a walk-in sale of 2 below) = 42
select pg_temp.sold(:p_fast, 10, 2, 'Completed'), pg_temp.sold(:p_fast, 10, 9, 'Completed'),
       pg_temp.sold(:p_fast, 10, 16, 'Completed'), pg_temp.sold(:p_fast, 10, 25, 'Completed');
-- ... and lines that must NOT count: cancelled, pending, confirmed, in progress
select pg_temp.sold(:p_fast, 100, 3, 'Cancelled'), pg_temp.sold(:p_fast, 50, 4, 'Pending'),
       pg_temp.sold(:p_fast, 30, 5, 'Confirmed');
-- Slow Over: 3 in the window
select pg_temp.sold(:p_slow, 3, 10, 'Completed');
-- Stopped: 10 sold 50 days ago, none since
select pg_temp.sold(:p_stop, 10, 50, 'Completed');
-- Zero stock: 6 sold in the window
select pg_temp.sold(:p_zero, 6, 5, 'Completed');
-- Decline: 20 in the previous 30 days, 5 in the last 30
select pg_temp.sold(:p_decl, 20, 45, 'Completed'), pg_temp.sold(:p_decl, 5, 10, 'Completed');
-- Walk-in sales: a completed one of 2 (counts) and a voided one of 30 (does not)
select sale_id as s_ok from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, 'Test buyer',
       format('[{"product_id": %s, "quantity": 2}]', :p_fast)::jsonb, 'PAID', 'CASH', 1000, null) \gset
select sale_id as s_void from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, 'Test buyer',
       format('[{"product_id": %s, "quantity": 30}]', :p_slow)::jsonb, 'PAID', 'CASH', 1000, null) \gset
select trimax_private.void_sale('marc.admin', :s_void, 'test: voided sale must not count');
-- stock as the scenarios need it (sales above moved it); corrections are recorded as stock history
update products set stock = 5 where product_id = :p_fast;
update products set stock = 200 where product_id = :p_slow;
update products set stock = 0 where product_id = :p_zero;
reset role;

-- ================================================================== 3. Rules over the last 30 days
set role app_owner;
create temp table ii as select * from trimax_private.product_insights(now() - interval '30 days', now())
 where product_name like 'II %';
reset role;

select test.ok((select units_sold = 42 and orders = 5 from ii where product_id = :p_fast),
               'Fast Low: 4 completed bookings x 10 + walk-in 2 = 42 (cancelled 100, pending 50, confirmed 30 not counted)');
select test.ok((select revenue = 4 * 250 + 2 * 25 from ii where product_id = :p_fast),
               'Fast Low: revenue = booking lines (qty x price - item discount) + sale line totals');
select test.ok((select avg_daily = 1.4 and coverage_days = 3.6 from ii where product_id = :p_fast),
               'Fast Low: 42 / 30 days = 1.4 per day; 5 in stock = 3.6 days');
select test.ok((select flags @> array['FAST', 'CRITICAL', 'RESTOCK'] and recommended_qty = 19 from ii where product_id = :p_fast),
               'high sales + low stock: Fast-Moving, Critical, Restock about 19 (1.4 x (3 + 14) = 24 - 5)');
select test.ok((select actions ->> 'RESTOCK' like '%Assumed lead time 3 days%' from ii where product_id = :p_fast),
               'the restock advice says the lead time is an assumption');
select test.ok((select trend_pct is null and prev_units = 0 from ii where product_id = :p_fast),
               'no sales in the previous period: no percentage (no division by zero)');

select test.ok((select flags @> array['SLOW', 'OVERSTOCK'] and units_sold = 3 and coverage_days = 2000 from ii where product_id = :p_slow),
               'high stock + few sales: Slow-Moving and Overstock (200 in stock lasts 2000 days); the voided sale of 30 is not counted');
select test.ok((select reasons ? 'SLOW' and actions ->> 'SLOW' like '%price%' from ii where product_id = :p_slow),
               'each flag has its reason and suggested action');

select test.ok((select flags @> array['NON_MOVING', 'OVERSTOCK'] and never_sold and avg_daily = 0 and coverage_days is null
                  from ii where product_id = :p_never),
               'no sales at all: Non-Moving (never sold), zero per day, no days-of-stock figure (no division by zero)');
select test.ok((select reasons ->> 'NON_MOVING' like 'Never sold%' from ii where product_id = :p_never),
               'a product that never sold is told apart ...');
select test.ok((select 'NON_MOVING' = any(flags) and not never_sold and days_since_last_sale = 50
                       and reasons ->> 'NON_MOVING' like 'Last sold 50 days ago%' from ii where product_id = :p_stop),
               '... from one that sold before and stopped (last sold 50 days ago)');

select test.ok((select flags = array['INSUFFICIENT_DATA'] and history_days < 1 from ii where product_id = :p_new),
               'a product added today with stock is Insufficient Data, not Non-Moving');

select test.ok((select flags @> array['CRITICAL', 'RESTOCK'] and stock = 0 and recommended_qty = 4
                       and reasons ->> 'CRITICAL' = 'Out of stock.' from ii where product_id = :p_zero),
               'no stock but selling: Critical (out of stock) and Restock about 4 (0.2 x 17)');

select test.ok((select 'DECLINE' = any(flags) and prev_units = 20 and units_sold = 5 and trend_pct = -75 from ii where product_id = :p_decl),
               'sales dropped from 20 to 5 compared with the previous 30 days (-75%)');
select test.ok(not exists (select 1 from ii where cardinality(flags) = 0),
               'every product gets at least one status (Normal when nothing applies)');

-- ================================================================== 4. Other ranges
set role app_owner;
create temp table ii7 as select * from trimax_private.product_insights(now() - interval '7 days', now()) where product_name like 'II %';
create temp table iiold as select * from trimax_private.product_insights(now() - interval '400 days', now() - interval '370 days') where product_name like 'II %';
select test.expect_error($$select * from trimax_private.product_insights(now(), now() - interval '1 day')$$, 'TM422', 'a backwards range is refused');
select test.expect_error($$select * from trimax_private.product_insights(now() - interval '400 days', now())$$, 'TM422', 'more than one year is refused');
reset role;
select test.ok((select units_sold = 12 and prev_units = 10 and trend_pct = 20 from ii7 where product_id = :p_fast),
               'last 7 days: 10 + 2 = 12, previous 7 days 10 (+20%)');
select test.ok((select bool_and(units_sold = 0 and revenue = 0) from iiold) and (select count(*) = 7 from iiold),
               'a period with no sales returns every product with zero (empty results, no error)');

-- ================================================================== 5. Alerts: one per new condition
set role app_owner;
select count(*) as n0 from admin_notifications where category = 'Inventory insight' \gset
select trimax_private.refresh_inventory_alerts('marc.admin') as made1 \gset
select trimax_private.refresh_inventory_alerts('marc.admin') as made2 \gset
reset role;
select test.ok(:made1 > 0 and :made2 = 0, 'first refresh creates alerts (' || :made1 || '); the second creates none (no duplicates)');
select test.ok((select count(*) = 1 from admin_notifications where category = 'Inventory insight' and related_id = :'p_fast' and title like 'Restock recommended:%')
               and (select count(*) = 1 from admin_notifications where category = 'Inventory insight' and related_id = :'p_fast' and title like 'Fast seller running low:%')
               and (select count(*) = 1 from admin_notifications where category = 'Inventory insight' and related_id = :'p_slow' and title like 'Possible overstock:%')
               and (select count(*) = 1 from admin_notifications where category = 'Inventory insight' and related_id = :'p_never' and title like 'Not selling:%')
               and (select count(*) = 1 from admin_notifications where category = 'Inventory insight' and related_id = :'p_zero' and title like 'Out of stock:%')
               and (select count(*) = 1 from admin_notifications where category = 'Inventory insight' and related_id = :'p_decl' and title like 'Sales dropped:%'),
               'restock, fast seller running low, overstock, not selling, out of stock and sales dropped each have one alert');
select test.ok(not exists (select 1 from admin_notifications where category = 'Inventory insight' and related_id = :'p_new'),
               'no alert for the new product (insufficient data)');
select test.ok((select body like '%Stock now: 5.%' and body like '%Sold in the last 30 days%: 42, 1.4 per day.%'
                       and body like '%Reason:%' and body like '%Suggested action:%' and related_type = 'product' and read_at is null
                  from admin_notifications where category = 'Inventory insight' and related_id = :'p_fast' and title like 'Restock%'),
               'an alert has the product, stock, sales, period, reason, action, a link to the product, and is unread');

-- the condition goes away (restocked), then comes back: within 7 days no repeat, after that a new alert
set role app_owner;
update products set stock = 50 where product_id = :p_fast;   -- about 36 days of stock: no longer low, not overstock
select trimax_private.refresh_inventory_alerts('marc.admin') as made3 \gset
select test.ok(:made3 = 0 and (select not active from trimax_private.inventory_alert_state where product_id = :p_fast and condition = 'RESTOCK'),
               'after a restock the condition is cleared without a new alert');
update products set stock = 5 where product_id = :p_fast;
select trimax_private.refresh_inventory_alerts('marc.admin') as made4 \gset
select test.ok(:made4 = 0, 'back to low within 7 days: no repeat alert');
update trimax_private.inventory_alert_state set active = false, last_notified_at = now() - interval '8 days' where product_id = :p_fast;
select trimax_private.refresh_inventory_alerts('marc.admin') as made5 \gset
select test.ok(:made5 >= 1 and (select count(*) = 2 from admin_notifications where category = 'Inventory insight' and related_id = :'p_fast' and title like 'Restock%'),
               'a condition that returns after the waiting time gives one new alert');
-- read / unread with the existing statement
update admin_notifications set read_at = now(), read_by = 'marc.admin' where read_at is null and category = 'Inventory insight' and related_id = :'p_slow';
select test.ok((select bool_and(read_at is not null) from admin_notifications where category = 'Inventory insight' and related_id = :'p_slow'),
               'alerts are marked read like any notification');
select test.expect_error($$select trimax_private.refresh_inventory_alerts('agent.ana')$$, 'TM403', 'only an Admin can refresh the alerts');
reset role;

-- ================================================================== 6. Who can reach it
select test.ok(not has_function_privilege('authenticated', 'trimax_private.product_insights(timestamptz,timestamptz)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.product_insights(timestamptz,timestamptz)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.product_insights(timestamptz,timestamptz)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.refresh_inventory_alerts(text)', 'execute')
               and not has_table_privilege('authenticated', 'trimax_private.inventory_insight_settings', 'select')
               and not has_table_privilege('authenticated', 'trimax_private.inventory_alert_state', 'select'),
               'phones and the service role cannot run the analysis, the alerts or read the settings');
set role authenticated;
select test.jwt(:'ana');
select test.expect_error($$select * from trimax_private.product_insights(now() - interval '7 days', now())$$, '42501',
                         'an agent calling the analysis directly is refused');
select test.expect_error($$select count(*) from public.admin_notifications$$, '42501',
                         'an agent cannot read the inventory alerts');
reset role;

-- existing stock data untouched by the analysis
select test.ok((select stock = 5 from products where product_id = :p_fast) and (select stock = 200 from products where product_id = :p_slow),
               'the analysis and the alerts never change stock');

select 'ALL SUPPLIER AND INTELLIGENCE SQL TESTS PASSED' as result;
\echo ALL SUPPLIER AND INTELLIGENCE SQL TESTS PASSED
