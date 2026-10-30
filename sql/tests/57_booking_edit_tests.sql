-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261029_03_booking_edit_and_driver_summary.sql.
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC); phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana '00000000-0000-0000-0000-00000000a0a0'
\set dan '00000000-0000-0000-0000-00000000d0d0'
\set editsig 'trimax_private.update_booking_items(text,integer,text,jsonb,text)'

-- ================================================================== 1. Shape and access
select test.ok(to_regprocedure(:'editsig') is not null
               and to_regprocedure('trimax_private.booking_items_signature(integer)') is not null
               and to_regprocedure('public.trimax_driver_summary()') is not null
               and not has_function_privilege('authenticated', :'editsig', 'execute')
               and not has_function_privilege('anon', :'editsig', 'execute')
               and not has_function_privilege('service_role', :'editsig', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.booking_items_signature(integer)', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_driver_summary()', 'execute')
               and not has_function_privilege('anon', 'public.trimax_driver_summary()', 'execute')
               and not has_function_privilege('service_role', 'public.trimax_driver_summary()', 'execute'),
               'order editing is Desktop-only; the driver summary is for signed-in phones only');

-- ================================================================== 2. Fixtures: an agent books 3 Coke (5 off each) + 3 Sprite
set role app_owner;
select trimax_private.save_product('marc.admin', null, 'ED Coke Case', 'ED Supplier', 100, 'AMOUNT', 20, 0, null, 50, true) as coke \gset
select trimax_private.save_product('marc.admin', null, 'ED Sprite Case', 'ED Supplier', 80, 'AMOUNT', 20, 0, null, 30, false) as sprite \gset
select trimax_private.save_product('marc.admin', null, 'ED Royal Case', 'ED Supplier', 150, 'AMOUNT', 50, 0, null, 10, false) as royal \gset
select trimax_private.save_product('marc.admin', null, 'ED Rare Case', 'ED Supplier', 100, 'AMOUNT', 0, 0, null, 2, false) as rare \gset
select trimax_private.set_product_half_price('marc.admin', :royal, 110) \g /dev/null
select c.client_name as cn, c.contact_num as cc, c.address as ca from clients c
  join agents a on a.client_id = c.client_id and a.username = 'agent.ana' order by c.client_id limit 1 \gset
select count(*) as moves0 from inventory_movements \gset
reset role;

set role authenticated;
select test.jwt(:'ana');
select public.trimax_agent_create_booking(gen_random_uuid(), :'cn', :'cc', :'ca', 14.8, 121.0,
       format('[{"product_id": %s, "quantity": 3, "discount_per_unit": 5}, {"product_id": %s, "quantity": 3}]', :coke, :sprite)::jsonb) as bk \gset
select (:'bk'::jsonb ->> 'booking_id')::int as b \gset
reset role;

set role app_owner;
select test.ok((select total_amount = 3 * 120 - 15 + 3 * 100 from bookings where booking_id = :b)
               and (select stock = 47 from products where product_id = :coke)
               and (select stock = 27 from products where product_id = :sprite),
               'Aling Nene''s booking: 3 Coke (120, 5 off each) + 3 Sprite (100) = 645; stock 47 Coke, 27 Sprite');
select trimax_private.booking_items_signature(:b) as sig0 \gset

-- ================================================================== 3. Refused: nothing changes
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, 'stale', %L::jsonb)$$, :b,
                                format('[{"product_id": %s, "quantity": 3}]', :'coke')), 'TM409',
                         'a booking changed by someone else (old fingerprint) is refused');
select test.expect_error(format($$select trimax_private.update_booking_items('agent.ana', %s, %L, %L::jsonb)$$, :b, :'sig0',
                                format('[{"product_id": %s, "quantity": 3}]', :'coke')), 'TM403',
                         'only an Admin changes an order');
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, %L, '[]'::jsonb)$$, :b, :'sig0'),
                         'TM422', 'removing every item is refused (cancel the booking instead)');
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, %L, %L::jsonb)$$, :b, :'sig0',
                                format('[{"product_id": %s, "quantity": 0}]', :'coke')), 'TM422', 'a quantity of 0 is refused');
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, %L, '[{"product_id": 999999, "quantity": 1}]'::jsonb)$$, :b, :'sig0'),
                         'TM404', 'an unknown product is refused');
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, %L, %L::jsonb)$$, :b, :'sig0',
                                format('[{"product_id": %s, "quantity": 1, "unit": "HALF"}]', :'sprite')), 'TM422',
                         'a half case of a product without a half case price is refused');
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, %L, %L::jsonb)$$, :b, :'sig0',
                                format('[{"product_id": %s, "quantity": 3}, {"product_id": %s, "quantity": 3}]', :'coke', :'rare')), 'TM409',
                         'more than the stock left is refused (2 left, 3 asked)');
select test.ok(trimax_private.booking_items_signature(:b) = :'sig0'
               and (select total_amount = 645 from bookings where booking_id = :b)
               and (select stock = 2 from products where product_id = :rare)
               and (select stock = 47 from products where product_id = :coke),
               'after every refusal the booking, its total and the stock are unchanged');
select trimax_private.update_booking_items('marc.admin', :b, :'sig0',
       format('[{"product_id": %s, "quantity": 3}, {"product_id": %s, "quantity": 3}]', :coke, :sprite)::jsonb) as same \gset
select test.ok(not (:'same'::jsonb ->> 'changed')::boolean and trimax_private.booking_items_signature(:b) = :'sig0',
               'saving the same order changes nothing');

-- ================================================================== 4. The customer calls: remove the Sprite
select trimax_private.update_booking_items('marc.admin', :b, :'sig0',
       format('[{"product_id": %s, "quantity": 3}]', :coke)::jsonb, 'Customer called: no Sprite') as e1 \gset
select test.ok((:'e1'::jsonb ->> 'changed')::boolean and (:'e1'::jsonb ->> 'old_total')::numeric = 645
               and (:'e1'::jsonb ->> 'total_amount')::numeric = 345,
               'Sprite removed: total 645 -> 345');
select test.ok((select count(*) = 1 from booking_items where booking_id = :b)
               and (select unit_price = 120 and discount_per_unit = 5 and quantity = 3 and subtotal = 360
                      from booking_items where booking_id = :b and product_id = :coke)
               and (select subtotal_amount = 360 and discount_amount = 15 and total_amount = 345 from bookings where booking_id = :b),
               'the Coke line kept its price and the agent''s 5 off; subtotal 360, discount 15, total 345');
select test.ok((select stock = 30 from products where product_id = :sprite)
               and (select stock = 47 from products where product_id = :coke)
               and exists (select 1 from inventory_movements where product_id = :sprite and kind = 'BOOKING_CHANGED'
                             and quantity_change = 3 and reference_type = 'booking' and reference_id = :'b'
                             and actor_username = 'marc.admin' and note like 'Order changed by the admin: Customer called%')
               and not exists (select 1 from inventory_movements where product_id = :coke and kind = 'BOOKING_CHANGED'),
               'the 3 Sprite went back to the stock (30), recorded as "Booking changed"; Coke did not move');
select test.ok(exists (select 1 from audit_log where action = 'booking.items_change' and target_id = :'b' and actor_username = 'marc.admin'
                         and details = 'ED Sprite Case: removed (was 3); total 645 -> 345; note: Customer called: no Sprite'),
               'the audit log says what changed, the old and new total and the note');
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, %L, %L::jsonb)$$, :b, :'sig0',
                                format('[{"product_id": %s, "quantity": 1}]', :'coke')), 'TM409',
                         'a second window still holding the old order is refused');

-- ================================================================== 5. More Coke and 2 half cases of Royal, while In Progress
update bookings set driver_username = 'driver.dan', vehicle_id = 'TRK-001', status = 'In Progress' where booking_id = :b;
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, %L, %L::jsonb)$$, :b,
                                :'e1'::jsonb ->> 'signature', format('[{"product_id": %s, "quantity": 3}]', :'coke')), 'TM409',
                         'the status changed since the window was opened: refused');
select trimax_private.booking_items_signature(:b) as sig1 \gset
select trimax_private.update_booking_items('marc.admin', :b, :'sig1',
       format('[{"product_id": %s, "quantity": 5}, {"product_id": %s, "quantity": 2, "unit": "HALF"}]', :coke, :royal)::jsonb) as e2 \gset
select test.ok((:'e2'::jsonb ->> 'total_amount')::numeric = 5 * 120 - 25 + 2 * 110
               and (select unit_price = 110 and quantity = 2 and discount_per_unit = 0 and unit_cost = 75
                      from booking_items where booking_id = :b and product_id = :royal and unit = 'HALF')
               and (select discount_per_unit = 5 from booking_items where booking_id = :b and product_id = :coke),
               'In Progress: 5 Coke (still 5 off) + 2 half cases of Royal at 110 (puhunan 75) = 795');
select test.ok((select stock = 45 from products where product_id = :coke) and (select stock = 9 from products where product_id = :royal),
               'stock: 2 more Coke (45) and one case of Royal (9) taken');
select test.ok(exists (select 1 from audit_log where action = 'booking.items_change' and target_id = :'b'
                         and details = 'ED Coke Case: 3 -> 5; ED Royal Case (half case): added 2; total 345 -> 795'),
               'audit: Coke 3 -> 5, Royal half case added 2, total 345 -> 795');

-- ================================================================== 6. Cancelling gives back exactly what the booking holds now
select trimax_private.booking_items_signature(:b) as sig2 \gset
-- the Desktop's cancel (BookingService.changeStatus)
begin;
update bookings set status = 'Cancelled' where booking_id = :b and status = 'In Progress';
update products p set stock = p.stock + s.qty from (
    select i.product_id, sum(coalesce((to_jsonb(i) ->> 'stock_qty')::numeric, i.quantity)) as qty
      from booking_items i where i.booking_id = :b group by i.product_id) s where p.product_id = s.product_id;
commit;
select test.ok((select stock = 50 from products where product_id = :coke) and (select stock = 30 from products where product_id = :sprite)
               and (select stock = 10 from products where product_id = :royal),
               'cancelling after the changes puts every product back to where it started (50, 30, 10)');
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, %L, %L::jsonb)$$, :b,
                                trimax_private.booking_items_signature(:b), format('[{"product_id": %s, "quantity": 1}]', :'coke')), 'TM422',
                         'a Cancelled booking cannot be changed');
insert into bookings (client_id, status) select client_id, 'Completed' from bookings where booking_id = :b returning booking_id as done \gset
insert into booking_items (booking_id, product_id, quantity, unit_price, subtotal) values (:done, :coke, 1, 120, 120);
select test.expect_error(format($$select trimax_private.update_booking_items('marc.admin', %s, %L, %L::jsonb)$$, :done,
                                trimax_private.booking_items_signature(:done), format('[{"product_id": %s, "quantity": 2}]', :'coke')), 'TM422',
                         'a Completed booking cannot be changed (it is already in Sales & Profit)');
reset role;

-- ================================================================== 7. Driver summary
-- (earlier test files also gave driver.dan deliveries and payroll today, so the checks compare before and after)
set role authenticated;
select test.jwt(:'dan');
select public.trimax_driver_summary() as s0 \gset
reset role;
set role app_owner;
-- driver.dan delivered one booking today (the Completed one above), gets one more to do, and a payroll record
update bookings set driver_username = 'driver.dan', vehicle_id = 'TRK-001' where booking_id = :done;
update bookings set delivery_status = 'DELIVERED', delivery_status_at = now() where booking_id = :done;
insert into bookings (client_id, status) select client_id, 'Confirmed' from bookings where booking_id = :b returning booking_id as todo \gset
insert into booking_items (booking_id, product_id, quantity, unit_price, subtotal) values (:todo, :coke, 1, 120, 120);
update bookings set driver_username = 'driver.dan', vehicle_id = 'TRK-001' where booking_id = :todo;
select (date_trunc('month', now() at time zone 'Asia/Manila'))::date - 1 as p0, (now() at time zone 'Asia/Manila')::date as d0 \gset
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), 'driver.dan', null, :'p0', :'d0',
       5000, 300, 100, 'UNPAID', null, null, null) as pay1 \gset
-- a voided record does not count
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), 'driver.dan', null, :'p0', :'p0',
       999, 0, 0, 'UNPAID', null, null, null) as pay2 \gset
select trimax_private.void_payroll('marc.admin', :pay2, 'Test: entered twice') \g /dev/null
reset role;

set role authenticated;
select test.jwt(:'dan');
select public.trimax_driver_summary() as s1 \gset
select test.ok((:'s1'::jsonb ->> 'delivered_today')::int = (:'s0'::jsonb ->> 'delivered_today')::int + 1
               and (:'s1'::jsonb ->> 'delivered_today_amount')::numeric = (:'s0'::jsonb ->> 'delivered_today_amount')::numeric + 120
               and (:'s1'::jsonb ->> 'delivered_month')::int = (:'s0'::jsonb ->> 'delivered_month')::int + 1
               and (:'s1'::jsonb ->> 'to_deliver')::int = (:'s0'::jsonb ->> 'to_deliver')::int + 1,
               'driver summary: one more delivered today (+120) and one more to deliver');
select test.ok((:'s1'::jsonb ->> 'pay_month')::numeric = (:'s0'::jsonb ->> 'pay_month')::numeric + 5200
               and exists (select 1 from jsonb_array_elements(:'s1'::jsonb -> 'pay_records') r
                            where (r ->> 'net_pay')::numeric = 5200 and r ->> 'status' = 'UNPAID' and r ->> 'period_end' = :'d0')
               and not exists (select 1 from jsonb_array_elements(:'s1'::jsonb -> 'pay_records') r where (r ->> 'net_pay')::numeric = 999),
               'earnings this month: + 5,000 + 300 - 100 = 5,200 (unpaid), listed; the voided record is not counted');
reset role;
set role authenticated;
select test.jwt(:'ana');
select test.expect_error($$select public.trimax_driver_summary()$$, 'TM403', 'an agent has no driver summary');
reset role;

\echo ALL BOOKING EDIT SQL TESTS PASSED
