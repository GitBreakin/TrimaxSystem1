-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261028_04_bad_orders.sql.
-- Desktop steps run as app_owner (the database owner, like the Desktop over JDBC), phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana '00000000-0000-0000-0000-00000000a0a0'
select (date_trunc('day', now() at time zone 'Asia/Manila')) at time zone 'Asia/Manila' as d0 \gset
select (date_trunc('day', now() at time zone 'Asia/Manila') + interval '1 day') at time zone 'Asia/Manila' as d1 \gset
select (now() at time zone 'Asia/Manila')::date as today \gset
-- Earlier test files left sales, bookings, payroll and expenses dated today: report checks compare differences.
create table test.bo_before as select * from trimax_private.profit_summary(:'d0', :'d1');
create table test.bo_week_before as select * from trimax_private.profit_summary(:'d0'::timestamptz - interval '7 days', :'d1');

-- ================================================================== 1. Who can see and call what
select test.ok(not has_table_privilege('authenticated', 'public.bad_orders', 'select')
               and not has_table_privilege('anon', 'public.bad_orders', 'select')
               and not has_table_privilege('service_role', 'public.bad_orders', 'select')
               and not has_table_privilege('authenticated', 'public.bad_orders', 'insert')
               and not has_sequence_privilege('authenticated', pg_get_serial_sequence('public.bad_orders', 'bad_order_id'), 'usage')
               and not has_sequence_privilege('service_role', pg_get_serial_sequence('public.bad_orders', 'bad_order_id'), 'usage')
               and (select relrowsecurity from pg_class where oid = 'public.bad_orders'::regclass)
               and not exists (select 1 from pg_policies where tablename = 'bad_orders'),
               'bad orders: RLS on, no policies, invisible to phones, anonymous callers and the service role');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.void_bad_order(text,bigint,text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.void_bad_order(text,bigint,text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.profit_summary(timestamptz,timestamptz)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.profit_trend(timestamptz,timestamptz,text)', 'execute'),
               'bad-order and report functions are for the database owner only (also after DROP + CREATE)');
select test.ok(not has_sequence_privilege('authenticated', pg_get_serial_sequence('public.suppliers', 'supplier_id'), 'usage')
               and not has_sequence_privilege('service_role', pg_get_serial_sequence('public.suppliers', 'supplier_id'), 'usage'),
               'the supplier id sequence is not usable by the API roles either');
set role authenticated;
select test.jwt(:'ana');
select test.expect_error('select * from public.bad_orders', '42501', 'a phone cannot read bad orders');
reset role;

-- ================================================================== 2. The Desktop keeps working
select test.ok(pg_get_function_result('trimax_private.profit_summary(timestamptz,timestamptz)'::regprocedure) =
               'TABLE(bookings_count integer, bookings_gross numeric, bookings_discount numeric, bookings_net numeric, '
               || 'bookings_cogs numeric, bookings_lines_no_cost integer, sales_count integer, sales_gross numeric, '
               || 'sales_discount numeric, sales_net numeric, sales_cogs numeric, sales_lines_no_cost integer, '
               || 'unpaid_sales_count integer, unpaid_sales_total numeric, revenue_gross numeric, discounts numeric, '
               || 'revenue_net numeric, cogs numeric, gross_profit numeric, payroll_paid numeric, payroll_unpaid numeric, '
               || 'payroll_count integer, net_profit numeric, restock_cost numeric, restock_count integer, '
               || 'other_expenses numeric, other_expenses_paid numeric, other_expenses_unpaid numeric, other_expenses_count integer, '
               || 'bad_orders_loss numeric, bad_orders_count integer, bad_orders_no_cost integer)',
               'profit_summary: the 29 existing columns unchanged, 3 bad-order columns added at the end');
select test.ok(pg_get_function_result('trimax_private.profit_trend(timestamptz,timestamptz,text)'::regprocedure) =
               'TABLE(bucket_start date, revenue numeric, cogs numeric, gross_profit numeric, payroll numeric, net_profit numeric, other_expenses numeric, bad_orders numeric)',
               'profit_trend: the 7 existing columns unchanged, bad_orders added at the end');
select test.ok((select count(*) = 0 from test.bo_before where bad_orders_loss <> 0 or bad_orders_count <> 0),
               'before any bad order the loss is 0');

-- ================================================================== 3. Test products
set role app_owner;
select trimax_private.save_product('marc.admin', null, 'BO Glass Bottle', 'BO Supplier', 20, 'AMOUNT', 5, 0, null, 50, false) as p_glass \gset
select trimax_private.save_product('marc.admin', null, 'BO Old Item', 'BO Supplier', null, null, null, null, 10, 10, false) as p_nocost \gset
select trimax_private.save_product('marc.admin', null, 'BO To Delete', 'BO Supplier', 8, 'AMOUNT', 2, 0, null, 6, false) as p_del \gset
select test.ok((select stock = 50 and capital_cost = 20 from products where product_id = :p_glass)
               and (select stock = 10 and capital_cost is null from products where product_id = :p_nocost),
               'test products ready (one with puhunan 20, one without puhunan)');

-- ================================================================== 4. Refusals
select test.expect_error(format($$select * from trimax_private.record_bad_order('agent.ana', %s, 1, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid())$$, :p_glass),
                         'TM403', 'only an Admin may record a bad order');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 1, 'BROKEN', 'DISPOSED', null, null, null)$$, :p_glass),
                         'TM422', 'a missing request id is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 0, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid())$$, :p_glass),
                         'TM422', 'quantity 0 is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, -3, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid())$$, :p_glass),
                         'TM422', 'a negative quantity is refused (it cannot add stock)');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 51, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid())$$, :p_glass),
                         'TM409', 'more than the stock is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 1, 'LOST', 'DISPOSED', null, null, gen_random_uuid())$$, :p_glass),
                         'TM422', 'an unknown reason is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 1, 'BROKEN', 'SOLD', null, null, gen_random_uuid())$$, :p_glass),
                         'TM422', 'an unknown disposition is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 1, 'OTHER', 'DISPOSED', null, '  ', gen_random_uuid())$$, :p_glass),
                         'TM422', 'reason Other needs a note');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 1, 'BROKEN', 'DISPOSED', %L, null, gen_random_uuid())$$, :p_glass, :'today'::date + 1),
                         'TM422', 'a date found in the future is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 1, 'BROKEN', 'DISPOSED', %L, null, gen_random_uuid())$$, :p_glass, :'today'::date - 400),
                         'TM422', 'a date found more than a year ago is refused');
select test.expect_error($$select * from trimax_private.record_bad_order('marc.admin', -1, 1, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid())$$,
                         'TM404', 'a product that does not exist is refused');
select test.ok((select stock = 50 from products where product_id = :p_glass)
               and not exists (select 1 from bad_orders where product_id = :p_glass)
               and not exists (select 1 from inventory_movements where product_id = :p_glass and kind = 'BAD_ORDER'),
               'refused requests change nothing');

-- ================================================================== 5. Record, retry
select gen_random_uuid() as r1 \gset
select * from trimax_private.record_bad_order('marc.admin', :p_glass, 5, ' broken ', 'disposed', null, ' fell from the shelf ', :'r1') as b1 \gset b1_
select test.ok(:b1_stock_before = 50 and :b1_stock_after = 45 and :b1_loss_amount = 100 and not :'b1_already_existed'::boolean,
               'a bad order of 5 takes the stock from 50 to 45, loss 5 x 20 = 100');
select test.ok((select stock = 45 from products where product_id = :p_glass), 'the product stock is 45');
select test.ok((select reason = 'BROKEN' and disposition = 'DISPOSED' and found_on = :'today'::date and quantity = 5
                       and unit_cost = 20 and loss_amount = 100 and note = 'fell from the shelf'
                       and product_name = 'BO Glass Bottle' and supplier = 'BO Supplier'
                       and recorded_by = 'marc.admin' and voided_at is null and movement_id is not null
                  from bad_orders where bad_order_id = :b1_bad_order_id),
               'the bad order keeps reason, disposition, date found (Manila today by default), puhunan, loss, note and who');
select test.ok((select m.kind = 'BAD_ORDER' and m.quantity_change = -5 and m.stock_before = 50 and m.stock_after = 45
                       and m.unit_cost = 20 and m.total_cost = 100 and m.reference_type = 'bad_order'
                       and m.reference_id = :'b1_bad_order_id' and m.actor_username = 'marc.admin'
                       and m.client_request_id = :'r1'::uuid and m.note like 'Bad order: broken, thrown away%'
                  from inventory_movements m join bad_orders b on b.movement_id = m.movement_id
                 where b.bad_order_id = :b1_bad_order_id),
               'the stock history has one BAD_ORDER line linked to it');
select test.ok(exists (select 1 from audit_log where action = 'inventory.bad_order' and target_id = :'p_glass'
                        and details like 'BO Glass Bottle: 5 broken%stock 50 -> 45, loss%'),
               'recording a bad order is audited');
select * from trimax_private.record_bad_order('marc.admin', :p_glass, 5, 'BROKEN', 'DISPOSED', null, 'fell from the shelf', :'r1') as b1r \gset b1r_
select test.ok(:'b1r_already_existed'::boolean and :b1r_bad_order_id = :b1_bad_order_id
               and (select stock = 45 from products where product_id = :p_glass)
               and (select count(*) = 1 from bad_orders where client_request_id = :'r1'::uuid)
               and (select count(*) = 1 from inventory_movements where client_request_id = :'r1'::uuid),
               'the same request again (a retry) returns the first bad order: no double stock-out');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 7, 'BROKEN', 'DISPOSED', null, null, %L)$$, :p_glass, :'r1'),
                         'TM409', 'the same request id with another quantity is refused');

select * from trimax_private.record_bad_order('marc.admin', :p_glass, 3, 'EXPIRED', 'RETURNED_TO_SUPPLIER', :'today'::date - 2, null, gen_random_uuid()) as b2 \gset b2_
select test.ok(:b2_stock_after = 42 and :b2_loss_amount = 60
               and (select disposition = 'RETURNED_TO_SUPPLIER' and found_on = :'today'::date - 2 from bad_orders where bad_order_id = :b2_bad_order_id),
               'expired, returned to supplier, found 2 days ago: stock 42, loss 60');
select * from trimax_private.record_bad_order('marc.admin', :p_nocost, 2, 'OTHER', 'DISPOSED', null, 'rats got into the box', gen_random_uuid()) as b3 \gset b3_
select test.ok(:b3_stock_after = 8
               and (select unit_cost is null and loss_amount is null from bad_orders where bad_order_id = :b3_bad_order_id),
               'a product without puhunan: stock goes down, no peso loss (recorded as "no puhunan")');
select * from trimax_private.record_bad_order('marc.admin', :p_glass, 1, 'DAMAGED', 'DISPOSED', :'today'::date - 20, null, gen_random_uuid()) as b4 \gset b4_
select test.ok(:b4_stock_after = 41, 'a fourth one, found 20 days ago');

-- ================================================================== 6. Sales & Profit
reset role;
create table test.bo_after as select * from trimax_private.profit_summary(:'d0', :'d1');
select test.ok((select a.bad_orders_loss - b.bad_orders_loss = 100 and a.bad_orders_count - b.bad_orders_count = 2
                       and a.bad_orders_no_cost - b.bad_orders_no_cost = 1
                  from test.bo_after a, test.bo_before b),
               'today: loss 100 from 2 bad orders, 1 of them without puhunan (the expired one is on its own day)');
select test.ok((select a.net_profit = b.net_profit - 100 and a.gross_profit = b.gross_profit and a.cogs = b.cogs
                       and a.revenue_net = b.revenue_net
                  from test.bo_after a, test.bo_before b),
               'net profit goes down by the loss; sales, cost of goods and gross profit do not change');
select test.ok((select net_profit = gross_profit - payroll_paid - payroll_unpaid - other_expenses - bad_orders_loss from test.bo_after),
               'net profit = gross profit - payroll - other expenses - bad orders');
select test.ok((select a.bad_orders_loss - b.bad_orders_loss = 160 and a.bad_orders_count - b.bad_orders_count = 3
                  from trimax_private.profit_summary(:'d0'::timestamptz - interval '7 days', :'d1') a, test.bo_week_before b),
               'last 7 days: 100 + 60 = 160 from 3 bad orders (the one 20 days ago is outside)');
select test.ok((select bad_orders_loss = 60 and bad_orders_count = 1
                  from trimax_private.profit_summary(:'d0'::timestamptz - interval '2 days', :'d0'::timestamptz - interval '1 day')),
               'the bad order is counted on the date it was found');
select test.ok((select sum(t.bad_orders) = s.bad_orders_loss and sum(t.net_profit) = s.net_profit
                  from trimax_private.profit_trend(:'d0'::timestamptz - interval '25 days', :'d1', 'day') t,
                       trimax_private.profit_summary(:'d0'::timestamptz - interval '25 days', :'d1') s
                 group by s.bad_orders_loss, s.net_profit),
               'the daily trend adds up to the summary (bad orders and net profit included)');
select test.ok((select sum(t.bad_orders) = s.bad_orders_loss and sum(t.net_profit) = s.net_profit
                  from trimax_private.profit_trend(:'d0'::timestamptz - interval '40 days', :'d1', 'month') t,
                       trimax_private.profit_summary(:'d0'::timestamptz - interval '40 days', :'d1') s
                 group by s.bad_orders_loss, s.net_profit),
               'the monthly trend adds up too');
select test.ok((select bad_orders = 60 from trimax_private.profit_trend(:'d0'::timestamptz - interval '5 days', :'d1', 'day')
                 where bucket_start = :'today'::date - 2),
               'the expired one is on its own day in the trend');
select test.ok((select bad_orders_loss = 0 and bad_orders_count = 0
                  from trimax_private.profit_summary(:'d1'::timestamptz + interval '10 days', :'d1'::timestamptz + interval '11 days')),
               'an empty period gives zeros, not errors');

-- ================================================================== 7. Cannot be changed or deleted
set role app_owner;
select test.expect_error(format('delete from bad_orders where bad_order_id = %s', :b1_bad_order_id), 'TM409', 'a bad order cannot be deleted');
select test.expect_error(format('update bad_orders set quantity = 1 where bad_order_id = %s', :b1_bad_order_id), 'TM409', 'the quantity cannot be changed');
select test.expect_error(format('update bad_orders set loss_amount = 0, unit_cost = 0 where bad_order_id = %s', :b1_bad_order_id), 'TM409', 'the loss cannot be changed');
select test.expect_error(format('update bad_orders set found_on = found_on - 1 where bad_order_id = %s', :b1_bad_order_id), 'TM409', 'the date found cannot be changed');
select test.expect_error(format('update bad_orders set movement_id = null where bad_order_id = %s', :b1_bad_order_id), 'TM409', 'the stock-history link cannot be changed');
select test.expect_error(format('update bad_orders set product_id = %s where bad_order_id = %s', :p_nocost, :b1_bad_order_id), 'TM409', 'it cannot be moved to another product');
select test.expect_error(format('update inventory_movements set quantity_change = -1 where client_request_id = %L', :'r1'), 'TM409', 'its stock-history line cannot be changed');

-- ================================================================== 8. Undo (void)
select test.expect_error(format($$select * from trimax_private.void_bad_order('marc.admin', %s, ' x ')$$, :b1_bad_order_id), 'TM422', 'undo needs a reason');
select test.expect_error(format($$select * from trimax_private.void_bad_order('agent.ana', %s, 'mistake')$$, :b1_bad_order_id), 'TM403', 'only an Admin may undo');
select test.expect_error($$select * from trimax_private.void_bad_order('marc.admin', -1, 'mistake')$$, 'TM404', 'undo of a missing bad order is refused');
select * from trimax_private.void_bad_order('marc.admin', :b1_bad_order_id, 'Counted twice') as v1 \gset v1_
select test.ok(:v1_stock_before = 41 and :v1_stock_after = 46 and (select stock = 46 from products where product_id = :p_glass),
               'undo puts the 5 back: stock 41 -> 46');
select test.ok((select voided_at is not null and voided_by = 'marc.admin' and void_reason = 'Counted twice' from bad_orders where bad_order_id = :b1_bad_order_id),
               'the bad order stays on record as undone, with who and why');
select test.ok((select count(*) = 1 from inventory_movements where kind = 'BAD_ORDER_VOIDED' and reference_id = :'b1_bad_order_id'
                        and quantity_change = 5 and stock_before = 41 and stock_after = 46 and actor_username = 'marc.admin'
                        and note = 'Bad order undone: Counted twice'),
               'the stock history has a BAD_ORDER_VOIDED line');
select test.ok(exists (select 1 from audit_log where action = 'inventory.bad_order_void' and target_id = :'p_glass'),
               'undo is audited');
reset role;
select test.ok((select a.bad_orders_loss - b.bad_orders_loss = 0 and a.bad_orders_count - b.bad_orders_count = 1
                       and a.net_profit = b.net_profit
                  from trimax_private.profit_summary(:'d0', :'d1') a, test.bo_before b),
               'an undone bad order no longer counts: today only the no-puhunan one is left, net profit back');
set role app_owner;
select test.expect_error(format($$select * from trimax_private.void_bad_order('marc.admin', %s, 'again')$$, :b1_bad_order_id), 'TM409', 'it cannot be undone twice');
select test.expect_error(format($$update bad_orders set void_reason = 'changed' where bad_order_id = %s$$, :b1_bad_order_id), 'TM409', 'an undone bad order cannot be changed');
select test.ok((select stock = 46 from products where product_id = :p_glass), 'refused undos change nothing');

-- ================================================================== 9. Deleted product, users
select * from trimax_private.record_bad_order('marc.admin', :p_del, 2, 'SPOILED', 'DISPOSED', null, null, gen_random_uuid()) as b5 \gset b5_
delete from products where product_id = :p_del;
select test.ok((select product_id is null and product_name = 'BO To Delete' and quantity = 2 and loss_amount = 16
                  from bad_orders where bad_order_id = :b5_bad_order_id),
               'deleting a product keeps its bad orders (name and loss kept)');
reset role;
select test.ok((select bad_orders_loss - (select bad_orders_loss from test.bo_before) = 16
                  from trimax_private.profit_summary(:'d0', :'d1')),
               'the loss of a deleted product still counts');
set role app_owner;
select test.expect_error(format($$select * from trimax_private.void_bad_order('marc.admin', %s, 'mistake')$$, :b5_bad_order_id), 'TM409',
                         'a bad order of a deleted product cannot be undone (no stock to put back)');
select test.ok((select count(*) = 1 from inventory_movements where kind = 'BAD_ORDER' and product_id is null and product_name = 'BO To Delete'),
               'its stock history line is kept too');
reset role;

-- ================================================================== 10. Earlier stock flows still work
set role app_owner;
select * from trimax_private.adjust_stock('marc.admin', :p_glass, -1, 'Count difference', gen_random_uuid()) as adj \gset adj_
select test.ok(:adj_stock_after = 45 and (select count(*) = 1 from inventory_movements where movement_id = :adj_movement_id and kind = 'ADJUSTMENT'),
               'Correct stock still records an ADJUSTMENT');
update products set stock = stock + 1 where product_id = :p_glass;
select test.ok((select kind = 'ADJUSTMENT' and note like 'Stock changed without a TRIMAX reason%' from inventory_movements
                 where product_id = :p_glass order by movement_id desc limit 1),
               'a change outside TRIMAX is still recorded as ADJUSTMENT (not as a bad order)');
reset role;

drop table test.bo_before, test.bo_week_before, test.bo_after;
select 'ALL BAD ORDER SQL TESTS PASSED' as result;
\echo ALL BAD ORDER SQL TESTS PASSED
