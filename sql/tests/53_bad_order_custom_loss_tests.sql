-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261028_06_bad_order_custom_loss.sql.
-- Desktop steps run as app_owner (the database owner, like the Desktop over JDBC). Every check prints PASS
-- or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

select (date_trunc('day', now() at time zone 'Asia/Manila')) at time zone 'Asia/Manila' as d0 \gset
select (date_trunc('day', now() at time zone 'Asia/Manila') + interval '1 day') at time zone 'Asia/Manila' as d1 \gset
create table test.bo6_before as select * from trimax_private.profit_summary(:'d0', :'d1');

-- ================================================================== 1. Shape and access
select test.ok(to_regprocedure('trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid,numeric)') is not null
               and to_regprocedure('trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid)') is not null
               and (select count(*) = 2 from pg_proc where proname = 'record_bad_order')
               and (select pronargdefaults = 0 from pg_proc where oid = 'trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid,numeric)'::regprocedure),
               'record_bad_order with the amount (9 arguments, no default) next to the 8-argument one older Desktops look for');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid,numeric)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid,numeric)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.void_bad_order(text,bigint,text)', 'execute'),
               'still for the database owner only');
select test.ok((select count(*) = 0 from bad_orders where loss_source <> 'PUHUNAN'),
               'bad orders recorded before this update are "puhunan x pieces", as they were');

-- ================================================================== 2. Products
set role app_owner;
select trimax_private.save_product('marc.admin', null, 'BO6 Sprite Pack (6pcs)', 'BO Supplier', 180, 'AMOUNT', 20, 0, null, 20, false) as p_pack \gset
select trimax_private.save_product('marc.admin', null, 'BO6 No Puhunan', 'BO Supplier', null, null, null, null, 30, 5, false) as p_nocost \gset

-- ================================================================== 3. Part of one item: 0 pieces, amount by hand
select gen_random_uuid() as r0 \gset
select * from trimax_private.record_bad_order('marc.admin', :p_pack, 0, 'BROKEN', 'DISPOSED', null, 'one bottle broke', :'r0', 10.00) as a \gset a_
select test.ok(:a_stock_before = 20 and :a_stock_after = 20 and :a_loss_amount = 10.00 and (select stock = 20 from products where product_id = :p_pack),
               'one bottle of a pack: 0 pieces, loss P10, the stock stays 20');
select test.ok((select quantity = 0 and loss_source = 'CUSTOM' and loss_amount = 10.00 and unit_cost = 180 and movement_id is null
                  from bad_orders where bad_order_id = :a_bad_order_id)
               and not exists (select 1 from inventory_movements where client_request_id = :'r0'::uuid),
               'it is recorded as a loss set by hand, with no stock-history line');
select test.ok(exists (select 1 from audit_log where action = 'inventory.bad_order' and target_id = :'p_pack'
                        and details like 'BO6 Sprite Pack (6pcs): part of one item broken%stock unchanged (20), loss set by hand%'),
               'the audit says "part of one item", "stock unchanged" and "loss set by hand"');
select * from trimax_private.record_bad_order('marc.admin', :p_pack, 0, 'BROKEN', 'DISPOSED', null, 'one bottle broke', :'r0', 10.00) as a2 \gset a2_
select test.ok(:'a2_already_existed'::boolean and :a2_bad_order_id = :a_bad_order_id and :a2_stock_after = 20
               and (select count(*) = 1 from bad_orders where client_request_id = :'r0'::uuid),
               'the same request again returns the first one');

-- ================================================================== 4. Pieces out, amount by hand
select * from trimax_private.record_bad_order('marc.admin', :p_pack, 2, 'DAMAGED', 'RETURNED_TO_SUPPLIER', null, null, gen_random_uuid(), 50.50) as b \gset b_
select test.ok(:b_stock_before = 20 and :b_stock_after = 18 and :b_loss_amount = 50.50,
               '2 packs out with a loss of P50.50 by hand: stock 20 -> 18');
select test.ok((select m.kind = 'BAD_ORDER' and m.quantity_change = -2 and m.total_cost = 360
                  from inventory_movements m join bad_orders o on o.movement_id = m.movement_id where o.bad_order_id = :b_bad_order_id),
               'the stock history still shows the 2 packs at puhunan (P360)');
select * from trimax_private.record_bad_order('marc.admin', :p_nocost, 1, 'EXPIRED', 'DISPOSED', null, null, gen_random_uuid(), 25) as c \gset c_
select test.ok(:c_loss_amount = 25 and (select loss_amount = 25 and unit_cost is null and loss_source = 'CUSTOM' from bad_orders where bad_order_id = :c_bad_order_id),
               'a product without puhunan can get a loss by hand (P25)');

-- ================================================================== 5. As before without an amount (also an older Desktop)
select * from trimax_private.record_bad_order('marc.admin', :p_pack, 1, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid()) as d \gset d_
select test.ok(:d_loss_amount = 180 and :d_stock_after = 17
               and (select loss_source = 'PUHUNAN' from bad_orders where bad_order_id = :d_bad_order_id),
               'the 8-argument call still works: 1 pack, loss = puhunan P180');

-- ================================================================== 6. Refusals
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 0, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid())$$, :p_pack),
                         'TM422', '0 pieces without an amount is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 0, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid(), 0)$$, :p_pack),
                         'TM422', 'an amount of 0 is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 0, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid(), -5)$$, :p_pack),
                         'TM422', 'a negative amount is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 0, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid(), 10.005)$$, :p_pack),
                         'TM422', 'more than 2 decimals is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 0, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid(), 10000000.01)$$, :p_pack),
                         'TM422', 'more than P10,000,000 is refused');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, 18, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid(), 10)$$, :p_pack),
                         'TM409', 'more pieces than the stock is still refused with an amount');
select test.expect_error(format($$select * from trimax_private.record_bad_order('marc.admin', %s, -1, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid(), 10)$$, :p_pack),
                         'TM422', 'negative pieces are refused');
select test.expect_error($$select * from trimax_private.record_bad_order('agent.ana', 1, 0, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid(), 10)$$,
                         'TM403', 'only an Admin may record it');
select test.ok((select stock = 17 from products where product_id = :p_pack), 'refusals change nothing');
reset role;
select test.expect_error(format($$insert into bad_orders (product_id, product_name, quantity, reason, disposition, found_on, loss_source) values (%s, 'x', 0, 'BROKEN', 'DISPOSED', current_date, 'PUHUNAN')$$, :p_pack),
                         '23514', 'the table itself refuses 0 pieces unless the loss is set by hand');
select test.expect_error(format($$insert into bad_orders (product_id, product_name, quantity, reason, disposition, found_on, loss_source, loss_amount) values (%s, 'x', 0, 'BROKEN', 'DISPOSED', current_date, 'CUSTOM', 0)$$, :p_pack),
                         '23514', 'the table itself refuses a hand-set loss of 0');

-- ================================================================== 7. Sales & Profit
create table test.bo6_after as select * from trimax_private.profit_summary(:'d0', :'d1');
select test.ok((select a.bad_orders_loss - b.bad_orders_loss = 265.50 and a.bad_orders_count - b.bad_orders_count = 4
                       and a.bad_orders_no_cost - b.bad_orders_no_cost = 0 and a.net_profit = b.net_profit - 265.50
                  from test.bo6_after a, test.bo6_before b),
               'net profit goes down by the amounts: 10 + 50.50 + 25 + 180 = P265.50');

-- ================================================================== 8. Undo
set role app_owner;
select * from trimax_private.void_bad_order('marc.admin', :a_bad_order_id, 'Recorded on the wrong product') as v \gset v_
select test.ok(:v_stock_before = 17 and :v_stock_after = 17 and (select stock = 17 from products where product_id = :p_pack)
               and (select voided_at is not null from bad_orders where bad_order_id = :a_bad_order_id)
               and not exists (select 1 from inventory_movements where kind = 'BAD_ORDER_VOIDED' and reference_id = :'a_bad_order_id'),
               'undoing a 0-piece bad order puts no stock back and writes no stock-history line');
select * from trimax_private.void_bad_order('marc.admin', :b_bad_order_id, 'Supplier replaced them') as w \gset w_
select test.ok(:w_stock_before = 17 and :w_stock_after = 19, 'undoing the 2-pack one puts 2 back: 17 -> 19');
select test.ok(exists (select 1 from audit_log where action = 'inventory.bad_order_void' and details like '%part of one item undone, stock unchanged%'),
               'the audit of the undo says the stock did not change');
reset role;
select test.ok((select a.bad_orders_loss - b.bad_orders_loss = 205 and a.bad_orders_count - b.bad_orders_count = 2
                  from trimax_private.profit_summary(:'d0', :'d1') a, test.bo6_before b),
               'undone ones no longer count: 25 + 180 = P205 left');

drop table test.bo6_before, test.bo6_after;
select 'ALL BAD ORDER CUSTOM LOSS SQL TESTS PASSED' as result;
\echo ALL BAD ORDER CUSTOM LOSS SQL TESTS PASSED
