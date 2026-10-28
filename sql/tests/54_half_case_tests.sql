-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261028_07_half_case.sql.
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC); phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana '00000000-0000-0000-0000-00000000a0a0'
select (date_trunc('day', now() at time zone 'Asia/Manila')) at time zone 'Asia/Manila' as d0 \gset
select (date_trunc('day', now() at time zone 'Asia/Manila') + interval '1 day') at time zone 'Asia/Manila' as d1 \gset
create table test.hc_before as select * from trimax_private.profit_summary(:'d0', :'d1');

-- ================================================================== 1. Shape and access
select test.ok((select data_type = 'numeric' from information_schema.columns where table_name = 'products' and column_name = 'stock')
               and (select bool_and(data_type = 'numeric') from information_schema.columns
                     where table_name = 'inventory_movements' and column_name in ('quantity_change', 'stock_before', 'stock_after'))
               and (select count(*) = 4 from information_schema.columns
                     where (table_name, column_name) in (('booking_items', 'unit'), ('booking_items', 'stock_qty'),
                                                         ('sale_items', 'unit'), ('sale_items', 'stock_qty'))),
               'stock and the stock history can hold halves; lines have a unit and the stock they take');
select test.ok((select count(*) = 0 from booking_items where unit <> 'WHOLE' or stock_qty <> quantity)
               and (select count(*) = 0 from sale_items where unit <> 'WHOLE' or stock_qty <> quantity)
               and (select count(*) = 0 from products where stock::text like '%.%'),
               'every existing line is WHOLE and every existing stock stays a whole number (no "10.0")');
select test.ok((select pg_get_constraintdef(oid) = 'UNIQUE (booking_id, product_id, unit)' from pg_constraint
                 where conname = 'booking_items_booking_product_unit_key')
               and not exists (select 1 from pg_constraint where conname = 'booking_items_booking_id_product_id_key'),
               'a booking has one line per product and unit (whole and half of the same product can be in one booking)');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.set_product_half_price(text,integer,numeric)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.set_product_half_price(text,integer,numeric)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.adjust_stock(text,integer,numeric,text,uuid)', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_agent_create_booking(uuid,text,text,text,double precision,double precision,jsonb,text,text,text,numeric)', 'execute')
               and not has_function_privilege('anon', 'public.trimax_list_products()', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_list_products()', 'execute')
               and (select count(*) = 1 from pg_proc where proname = 'trimax_agent_create_booking')
               and (select count(*) = 1 from pg_proc where proname = 'adjust_stock'),
               'Desktop functions for the owner only; phone functions for signed-in users; no duplicate versions');

-- ================================================================== 2. Admin: half case price
set role app_owner;
-- Case: puhunan 200 + patong 40 = 240, minimum patong 10, agents may discount; stock 10
select trimax_private.save_product('marc.admin', null, 'HC Coke Case (24)', 'HC Supplier', 200, 'AMOUNT', 40, 10, null, 10, true) as p \gset
select trimax_private.save_product('marc.admin', null, 'HC Whole Only', 'HC Supplier', 100, 'AMOUNT', 20, 0, null, 10, false) as w \gset
select test.expect_error(format($$select trimax_private.set_product_half_price('agent.ana', %s, 125)$$, :p), 'TM403', 'only an Admin sets the half case price');
select test.expect_error(format($$select trimax_private.set_product_half_price('marc.admin', %s, 0)$$, :p), 'TM422', 'a half case price of 0 is refused');
select test.expect_error(format($$select trimax_private.set_product_half_price('marc.admin', %s, 12.345)$$, :p), 'TM422', 'more than 2 decimals is refused');
select trimax_private.set_product_half_price('marc.admin', :p, 125);
select test.ok((select half_price = 125 from products where product_id = :p)
               and exists (select 1 from audit_log where action = 'product.half_price' and target_id = :'p' and details like '%turned on at%'),
               'half case price 125 set and audited');
reset role;

-- ================================================================== 3. Phone: product list and quote
set role authenticated;
select test.jwt(:'ana');
select public.trimax_list_products() as plist \gset
select test.ok((select (e ->> 'half_price')::numeric = 125 and (e ->> 'half_max_discount_per_unit')::numeric = 20
                       and (e ->> 'max_discount_per_unit')::numeric = 30
                  from jsonb_array_elements(:'plist'::jsonb) e where (e ->> 'product_id')::int = :p)
               and (select e -> 'half_price' = 'null'::jsonb from jsonb_array_elements(:'plist'::jsonb) e where (e ->> 'product_id')::int = :w),
               'the product list gives the half case price and the most off a half (125 - 100 - 5 = 20); none for whole-only');
select public.trimax_agent_discount_quote(format('[{"product_id": %s, "quantity": 1}, {"product_id": %s, "quantity": 2, "unit": "HALF"}]', :p, :p)::jsonb) as q \gset
select test.ok((:'q'::jsonb ->> 'subtotal')::numeric = 490 and (:'q'::jsonb ->> 'max_discount')::numeric = 70
               and (select (e ->> 'unit_price')::numeric = 125 and (e ->> 'max_discount_per_unit')::numeric = 20
                      from jsonb_array_elements(:'q'::jsonb -> 'items') e where e ->> 'unit' = 'HALF'),
               'quote: 1 case 240 + 2 half cases 250 = 490; at most 30 + 2 x 20 = 70 off');
select test.expect_error(format($$select public.trimax_agent_discount_quote('[{"product_id": %s, "quantity": 1, "unit": "HALF"}]')$$, :w),
                         'TM422', 'a half case of a whole-only product is refused in the quote');
reset role;

set role app_owner;
select c.client_id as anas, c.client_name as anas_name, c.contact_num as anas_contact, c.address as anas_addr from clients c
  join agents a on a.client_id = c.client_id and a.username = 'agent.ana' order by c.client_id limit 1 \gset
reset role;

-- ================================================================== 4. Phone: booking with a half case
set role authenticated;
select test.jwt(:'ana');
select public.trimax_agent_create_booking(gen_random_uuid(), :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
       format('[{"product_id": %s, "quantity": 1}, {"product_id": %s, "quantity": 3, "unit": "HALF", "discount_per_unit": 5}]', :p, :p)::jsonb) as bk \gset
reset role;
select (:'bk'::jsonb ->> 'booking_id')::int as bkid \gset
select test.ok((select stock = 7.5 and stock::text = '7.5' from products where product_id = :p),
               '1 case + 3 half cases: stock 10 -> 7.5');
select test.ok((:'bk'::jsonb ->> 'subtotal_amount')::numeric = 615 and (:'bk'::jsonb ->> 'total_amount')::numeric = 600
               and (select e ->> 'unit' = 'HALF' and (e ->> 'quantity')::int = 3 and (e ->> 'unit_price')::numeric = 125
                           and (e ->> 'line_total')::numeric = 360
                      from jsonb_array_elements(:'bk'::jsonb -> 'items') e where e ->> 'unit' = 'HALF')
               and (select e ->> 'unit' = 'WHOLE' from jsonb_array_elements(:'bk'::jsonb -> 'items') e where (e ->> 'unit_price')::numeric = 240),
               'booking: 240 + 3 x 125 = 615, less 5 x 3 on the half cases = 600; each item says WHOLE or HALF');
select test.ok((select unit_cost = 100 and stock_qty = 1.5 from booking_items where booking_id = :bkid and unit = 'HALF')
               and (select unit_cost = 200 and stock_qty = 1 from booking_items where booking_id = :bkid and unit = 'WHOLE'),
               'puhunan of a half case line is 100 (half of 200); the half line takes 1.5 from the stock');
select test.ok((select quantity_change = -2.5 and stock_before = 10 and stock_after = 7.5 and kind = 'BOOKING'
                  from inventory_movements where reference_type = 'booking' and reference_id = :'bkid'),
               'one stock-history line: -2.5, 10 -> 7.5');

set role authenticated;
select test.jwt(:'ana');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, '[{"product_id": %s, "quantity": 1, "unit": "HALF"}]')$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :w), 'TM422', 'a half case of a whole-only product is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, '[{"product_id": %s, "quantity": 16, "unit": "HALF"}]')$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :p), 'TM409', '16 half cases (8) when only 7.5 are left is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, '[{"product_id": %s, "quantity": 1, "unit": "QUARTER"}]')$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :p), 'TM422', 'an unknown unit is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, '[{"product_id": %s, "quantity": 1, "unit": "HALF", "discount_per_unit": 20.01}]')$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :p), 'TM422', 'more than 20 off a half case is refused');
-- an app without half cases sends no unit: whole cases as before
select public.trimax_agent_create_booking(gen_random_uuid(), :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
       format('[{"product_id": %s, "quantity": 1}]', :w)::jsonb) as old \gset
reset role;
select test.ok((select e ->> 'unit' = 'WHOLE' from jsonb_array_elements(:'old'::jsonb -> 'items') e)
               and (select stock = 9 from products where product_id = :w),
               'an older app (no unit) books whole cases as before');
select test.ok((select stock = 7.5 from products where product_id = :p), 'refused bookings change no stock');

-- ================================================================== 5. Cancel returns the half cases (as the Desktop does it)
begin;
select set_config('trimax.stock_kind', 'BOOKING_CANCELLED', true), set_config('trimax.stock_ref_type', 'booking', true),
       set_config('trimax.stock_ref_id', :'bkid', true) \g /dev/null
update products p set stock = p.stock + s.qty
  from (select i.product_id, sum(i.stock_qty) as qty from booking_items i where i.booking_id = :bkid group by i.product_id) s
 where p.product_id = s.product_id;
update bookings set status = 'Cancelled' where booking_id = :bkid;
commit;
select test.ok((select stock = 10 and stock::text = '10' from products where product_id = :p),
               'cancelling returns 2.5: back to 10 (stored as 10, not 10.0)');

-- ================================================================== 6. Desktop: Walk-In sale with a half case, then void
set role app_owner;
select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, 'HC Juan',
       format('[{"product_id": %s, "quantity": 1, "unit": "HALF", "discount": 5}, {"product_id": %s, "quantity": 2}]', :p, :p)::jsonb,
       'PAID', 'CASH', null, null) as s \gset s_
select test.ok(:s_total_amount = 600 and (select stock = 7.5 from products where product_id = :p),
               'Walk-In: 1 half case 125 - 5 + 2 cases 480 = 600; stock 10 -> 7.5');
select test.ok((select unit = 'HALF' and unit_price = 125 and unit_cost = 100 and stock_qty = 0.5 and product_name like '%(half case)'
                  from sale_items where sale_id = :s_sale_id and unit = 'HALF')
               and (select cost_amount = 500 from sales where sale_id = :s_sale_id),
               'the sale line says "(half case)", price 125, puhunan 100; sale cost 100 + 400 = 500');
select test.expect_error(format($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, null, '[{"product_id": %s, "quantity": 1, "unit": "HALF"}]', 'PAID', 'CASH', null, null)$$, :w),
                         'TM422', 'Walk-In: a half case of a whole-only product is refused');
select trimax_private.void_sale('marc.admin', :s_sale_id, 'Test void');
select test.ok((select stock = 10 from products where product_id = :p), 'voiding the sale returns 2.5: back to 10');

-- ================================================================== 7. Stock functions with halves
select * from trimax_private.adjust_stock('marc.admin', :p, -0.5, 'Opened case: half sold loose', gen_random_uuid()) as a \gset a_
select test.ok(:a_stock_before = 10 and :a_stock_after = 9.5, 'Correct stock -0.5: 10 -> 9.5');
select test.expect_error(format($$select * from trimax_private.adjust_stock('marc.admin', %s, 0.3, 'x', gen_random_uuid())$$, :p), 'TM422', 'Correct stock 0.3 is refused');
select * from trimax_private.restock_product('marc.admin', :p, 2, 200, null, false, gen_random_uuid()) as r \gset r_
select test.ok(:r_stock_before = 9.5 and :r_stock_after = 11.5 and :r_total_cost = 400, 'Restock +2: 9.5 -> 11.5, cost 400');
select * from trimax_private.record_bad_order('marc.admin', :p, 1, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid()) as b \gset b_
select test.ok(:b_stock_before = 11.5 and :b_stock_after = 10.5 and :b_loss_amount = 200, 'Bad order of 1 case: 11.5 -> 10.5, loss 200');
select * from trimax_private.void_bad_order('marc.admin', :b_bad_order_id, 'Test undo') as v \gset v_
select test.ok(:v_stock_before = 10.5 and :v_stock_after = 11.5, 'Undo: 10.5 -> 11.5');
reset role;
select test.expect_error(format('update products set stock = 3.25 where product_id = %s', :p), '23514', 'stock 3.25 is refused (whole or .5 only)');

-- ================================================================== 8. Reports: a completed half case
set role authenticated;
select test.jwt(:'ana');
select public.trimax_agent_create_booking(gen_random_uuid(), :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
       format('[{"product_id": %s, "quantity": 1, "unit": "HALF"}]', :p)::jsonb) as bk2 \gset
reset role;
update bookings set status = 'Completed' where booking_id = (:'bk2'::jsonb ->> 'booking_id')::int;
create table test.hc_after as select * from trimax_private.profit_summary(:'d0', :'d1');
select test.ok((select coalesce(sum(qty), 0) = 0.5 from trimax_private.sold_lines() where product_id = :p),
               'sold lines count the completed half case as 0.5 (not the cancelled booking or the voided sale)');
select test.ok((select a.revenue_net - b.revenue_net = 125 and a.cogs - b.cogs = 100 from test.hc_after a, test.hc_before b),
               'Sales & Profit: the half case adds 125 sales and 100 cost of goods');
set role app_owner;
select test.ok((select stock = 11 and units_sold = 0.5 from trimax_private.product_insights(now() - interval '30 days', now() + interval '1 minute') where product_id = :p),
               'Intelligence: stock 11, 0.5 sold');
reset role;
drop table test.hc_before, test.hc_after;
select 'ALL HALF CASE SQL TESTS PASSED' as result;
\echo ALL HALF CASE SQL TESTS PASSED
