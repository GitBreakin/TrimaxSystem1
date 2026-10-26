-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261027_04_per_item_discounts.sql.
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC); phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana '00000000-0000-0000-0000-00000000a0a0'
-- agent.new, linked by test 44
\set other '00000000-0000-0000-0000-0000000a0001'
select (date_trunc('day', now() at time zone 'Asia/Manila')) at time zone 'Asia/Manila' as d0 \gset
select (date_trunc('day', now() at time zone 'Asia/Manila') + interval '1 day') at time zone 'Asia/Manila' as d1 \gset

-- ================================================================== 1. Who can call what
select test.ok(has_function_privilege('authenticated', 'public.trimax_agent_set_item_discounts(integer,jsonb)', 'execute')
               and not has_function_privilege('anon', 'public.trimax_agent_set_item_discounts(integer,jsonb)', 'execute')
               and not has_function_privilege('service_role', 'public.trimax_agent_set_item_discounts(integer,jsonb)', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_agent_discount_quote(jsonb)', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_agent_create_booking(uuid,text,text,text,double precision,double precision,jsonb,text,text,text,numeric)', 'execute'),
               'phones can quote, book and set item discounts; anonymous callers and the service role cannot set them');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.save_product(text,integer,text,text,numeric,text,numeric,numeric,numeric,integer,boolean)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.apply_item_discounts(integer,text,jsonb)', 'execute')
               and not has_function_privilege('authenticated', 'public.refresh_booking_total_for(integer)', 'execute')
               and not has_function_privilege('service_role', 'public.refresh_booking_total_for(integer)', 'execute'),
               'the Desktop and internal functions are for the database owner only');
select test.ok((select count(*) = 1 from pg_proc where proname = 'save_product')
               and (select count(*) = 1 from pg_proc where proname = 'trimax_agent_create_booking'),
               'one save_product and one trimax_agent_create_booking (no ambiguous overloads)');
select test.ok((select count(*) = 2 from information_schema.columns
                 where (table_name, column_name) in (('booking_items', 'discount_per_unit'), ('products', 'agent_discount_allowed')))
               and not exists (select 1 from booking_items where discount_per_unit <> 0 and booking_id not in
                                 (select booking_id from bookings where discount_by is not null)),
               'new columns are there and existing lines have no item discount');
select test.ok(not exists (select 1 from bookings b
                            where b.total_amount is distinct from
                                  coalesce(b.subtotal_amount, b.total_amount + b.discount_amount) - b.discount_amount),
               'every existing booking still has total = subtotal - discount after the update');

-- ================================================================== 2. Admin: which products agents may discount
set role app_owner;
-- Sprite: puhunan 20 + patong 5 = 25, minimum patong 1, agents may discount -> at most 4.00 off a piece
select trimax_private.save_product('marc.admin', null, 'Test Sprite 1L', 'Item Disc Supplier', 20, 'AMOUNT', 5, 1, null, 100, true) as sprite \gset
-- Coke: same price, agents may NOT discount
select trimax_private.save_product('marc.admin', null, 'Test Coke 1L', 'Item Disc Supplier', 20, 'AMOUNT', 5, 1, null, 100) as coke \gset
-- Candy: allowed, but no puhunan
select trimax_private.save_product('marc.admin', null, 'Test Candy Loose', 'Item Disc Supplier', null, null, null, null, 3, 100, true) as candy \gset
select test.ok((select agent_discount_allowed from products where product_id = :sprite)
               and not (select agent_discount_allowed from products where product_id = :coke),
               'a new product is not discountable unless the Admin ticks it');
-- an older Desktop calls the 10-argument form: the setting is kept
select trimax_private.save_product('marc.admin', :sprite, 'Test Sprite 1L', 'Item Disc Supplier', 20, 'AMOUNT', 5, 1, null, 0);
select test.ok((select agent_discount_allowed and price = 25 from products where product_id = :sprite),
               'saving from an older Desktop (10 arguments) keeps "agents may discount"');
select trimax_private.save_product('marc.admin', :coke, 'Test Coke 1L', 'Item Disc Supplier', 20, 'AMOUNT', 5, 1, null, 0, true);
select trimax_private.save_product('marc.admin', :coke, 'Test Coke 1L', 'Item Disc Supplier', 20, 'AMOUNT', 5, 1, null, 0, false);
select test.ok((select count(*) = 2 from audit_log where action = 'product.update' and target_id = :'coke'
                   and (details like '%agents may now discount%' or details like '%agents may no longer discount%')),
               'turning the setting on and off is audited');
select c.client_id as anas, c.client_name as anas_name, c.contact_num as anas_contact, c.address as anas_addr from clients c
  join agents a on a.client_id = c.client_id and a.username = 'agent.ana' order by c.client_id limit 1 \gset
reset role;

-- ================================================================== 3. Quote: per item
set role authenticated;
select test.jwt(:'ana');
select public.trimax_agent_discount_quote(format('[{"product_id": %s, "quantity": 2}, {"product_id": %s, "quantity": 1}, {"product_id": %s, "quantity": 1}]',
                                                 :sprite, :coke, :candy)::jsonb) as q \gset
select test.ok((select (e ->> 'max_discount_per_unit')::numeric = 4 and (e ->> 'discount_allowed')::boolean
                  from jsonb_array_elements(:'q'::jsonb -> 'items') e where (e ->> 'product_id')::int = :sprite),
               'Sprite: up to 4.00 off each piece');
select test.ok((select (e ->> 'max_discount_per_unit')::numeric = 0 and not (e ->> 'discount_allowed')::boolean
                       and e ->> 'reason' like '%Admin does not allow%'
                  from jsonb_array_elements(:'q'::jsonb -> 'items') e where (e ->> 'product_id')::int = :coke),
               'Coke: no discount, because the Admin did not allow it');
select test.ok((select (e ->> 'max_discount_per_unit')::numeric = 0 and e ->> 'reason' like '%puhunan%'
                  from jsonb_array_elements(:'q'::jsonb -> 'items') e where (e ->> 'product_id')::int = :candy),
               'Candy: no discount, because it has no puhunan');
select test.ok((:'q'::jsonb ->> 'max_discount')::numeric = 8 and (:'q'::jsonb ->> 'discount_available')::boolean
               and (:'q'::jsonb ->> 'subtotal')::numeric = 78
               and :'q'::text !~ '(capital_cost|puhunan".*:|min_markup)',
               'the order: subtotal 78, at most 8.00 off in all; the puhunan itself is never sent');

select public.trimax_list_products() as plist \gset
select test.ok((select (e ->> 'discount_allowed')::boolean and (e ->> 'max_discount_per_unit')::numeric = 4
                  from jsonb_array_elements(:'plist'::jsonb) e where (e ->> 'product_id')::int = :sprite)
               and (select not (e ->> 'discount_allowed')::boolean and (e ->> 'max_discount_per_unit')::numeric = 0
                      from jsonb_array_elements(:'plist'::jsonb) e where (e ->> 'product_id')::int = :coke)
               and :'plist'::text !~ '(capital_cost|min_markup|markup_)',
               'the product list says per item whether a discount is possible and the most per piece (no puhunan)');

-- ================================================================== 4. Booking with a discount on one item
select public.trimax_agent_create_booking(gen_random_uuid(), :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
       format('[{"product_id": %s, "quantity": 2, "discount_per_unit": 2}, {"product_id": %s, "quantity": 1}]', :sprite, :coke)::jsonb) as bk \gset
select (:'bk'::jsonb ->> 'booking_id')::int as bkid \gset
select test.ok((:'bk'::jsonb ->> 'subtotal_amount')::numeric = 75 and (:'bk'::jsonb ->> 'total_amount')::numeric = 71
               and (:'bk'::jsonb -> 'discount' ->> 'amount')::numeric = 4 and :'bk'::jsonb -> 'discount' ->> 'type' = 'PER_ITEM'
               and :'bk'::jsonb -> 'discount' ->> 'by' = 'agent.ana',
               'Sprite x2 less 2.00 each + Coke x1: 75 - 4 = 71');
select test.ok((select (e ->> 'discount_per_unit')::numeric = 2 and (e ->> 'line_total')::numeric = 46
                  from jsonb_array_elements(:'bk'::jsonb -> 'items') e where (e ->> 'product_id')::int = :sprite)
               and (select (e ->> 'discount_per_unit')::numeric = 0 and (e ->> 'line_total')::numeric = 25
                      from jsonb_array_elements(:'bk'::jsonb -> 'items') e where (e ->> 'product_id')::int = :coke),
               'each item shows its discount per piece and its line total (Sprite 46, Coke 25)');

select test.expect_detail(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0,
    '[{"product_id": %s, "quantity": 1, "discount_per_unit": 1}]')$$, :'anas_name', :'anas_contact', :'anas_addr', :coke),
    'TM422', 'discount_not_allowed', 'a discount on Coke (not allowed by the Admin) is refused');
select test.expect_detail(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0,
    '[{"product_id": %s, "quantity": 1, "discount_per_unit": 1}]')$$, :'anas_name', :'anas_contact', :'anas_addr', :candy),
    'TM422', 'discount_not_configured', 'a discount on an item without puhunan is refused');
select test.expect_detail(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0,
    '[{"product_id": %s, "quantity": 1, "discount_per_unit": 4.01}]')$$, :'anas_name', :'anas_contact', :'anas_addr', :sprite),
    'TM422', 'discount_too_big', 'more than 4.00 off a Sprite is refused');
select test.expect_detail(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0,
    '[{"product_id": %s, "quantity": 1}]', null, null, 'AMOUNT', 2)$$, :'anas_name', :'anas_contact', :'anas_addr', :sprite),
    'TM422', 'discount_per_item_only', 'an older app''s whole-order discount is refused with "update the app"');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0,
    '[{"product_id": %s, "quantity": 1, "discount_per_unit": 1}, {"product_id": %s, "quantity": 1, "discount_per_unit": 2}]')$$,
    :'anas_name', :'anas_contact', :'anas_addr', :sprite, :sprite), 'TM422', 'the same product with two different discounts is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), %L, %L, %L, 14.8, 121.0,
    '[{"product_id": %s, "quantity": 1, "discount_per_unit": 1.005}]')$$, :'anas_name', :'anas_contact', :'anas_addr', :sprite),
    'TM422', 'more than 2 decimals is refused');
select test.ok(public.trimax_agent_create_booking(gen_random_uuid(), :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
               format('[{"product_id": %s, "quantity": 1, "discount_per_unit": 4}]', :sprite)::jsonb) ->> 'total_amount' = '21.00',
               'exactly the most (4.00 off 25.00 = 21.00, puhunan 20 + minimum patong 1) is allowed');
select test.ok(public.trimax_agent_create_booking(gen_random_uuid(), :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
               format('[{"product_id": %s, "quantity": 1, "discount_per_unit": null}]', :coke)::jsonb) ->> 'total_amount' = '25.00',
               'no discount on an item (null or missing) books it at the full price');

-- ================================================================== 5. Changing the discounts of a Pending booking
select test.ok(public.trimax_agent_set_item_discounts(:bkid, format('[{"product_id": %s, "discount_per_unit": 4}]', :sprite)::jsonb)
               ->> 'total_amount' = '67.00', 'the agent changes Sprite to 4.00 off: 75 - 8 = 67');
select test.expect_detail(format($$select public.trimax_agent_set_item_discounts(%s, '[{"product_id": %s, "discount_per_unit": 1}]')$$,
                                 :bkid, :coke), 'TM422', 'discount_not_allowed', 'changing to a discount on Coke is refused');
select test.ok(public.trimax_agent_get_booking(:bkid) ->> 'total_amount' = '67.00', 'a refused change leaves the booking as it was');
select test.expect_detail(format($$select public.trimax_agent_set_discount(%s, 'AMOUNT', 5)$$, :bkid),
                          'TM422', 'discount_per_item_only', 'the old whole-order setter cannot give a discount any more');
select public.trimax_agent_set_discount(:bkid, null, 0) as cleared \gset
select test.ok(:'cleared'::jsonb ->> 'total_amount' = '75.00'
               and (select bool_and((e ->> 'discount_per_unit')::numeric = 0)
                      from jsonb_array_elements(public.trimax_agent_get_booking(:bkid) -> 'items') e),
               'the old setter with 0 removes every item discount (75.00)');
select public.trimax_agent_set_item_discounts(:bkid, format('[{"product_id": %s, "discount_per_unit": 2}]', :sprite)::jsonb);
select test.jwt(:'other');
select test.expect_error(format($$select public.trimax_agent_set_item_discounts(%s, '[]')$$, :bkid), 'TM404',
                         'another agent cannot change agent.ana''s discounts');
reset role;
set role app_owner;
select test.ok((select count(*) >= 4 from audit_log where action = 'booking.discount' and target_id = :'bkid'
                   and actor_username = 'agent.ana')
               and exists (select 1 from audit_log where action = 'booking.discount' and target_id = :'bkid'
                              and details like '%Test Sprite 1L less ₱2.00 x 2%'),
               'every discount change is audited with the item, the amount and the quantity');
reset role;
create table test.disc_before as select * from trimax_private.profit_summary(:'d0', :'d1');
set role app_owner;
update bookings set status = 'Confirmed' where booking_id = :bkid;
reset role;
set role authenticated;
select test.jwt(:'ana');
select test.expect_error(format($$select public.trimax_agent_set_item_discounts(%s, '[]')$$, :bkid), 'TM409',
                         'discounts cannot change after the booking is confirmed');
reset role;

-- ================================================================== 6. Sales & Profit
set role app_owner;
update bookings set status = 'Completed' where booking_id = :bkid;
reset role;
select test.ok((select a.bookings_gross - b.bookings_gross = 75 and a.bookings_discount - b.bookings_discount = 4
                       and a.bookings_net - b.bookings_net = 71 and a.bookings_cogs - b.bookings_cogs = 60
                  from trimax_private.profit_summary(:'d0', :'d1') a, test.disc_before b),
               'profit: gross 75, item discounts 4, net sales 71, cost of goods 60 (3 x 20)');
select test.ok(not exists (select 1 from bookings x
                            where x.total_amount is distinct from x.subtotal_amount - x.discount_amount
                              and x.subtotal_amount is not null),
               'every booking: total = subtotal - discount');
drop table test.disc_before;

select 'ALL PER-ITEM DISCOUNT SQL TESTS PASSED' as result;
\echo ALL PER-ITEM DISCOUNT SQL TESTS PASSED
