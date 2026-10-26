-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261027_01_sales_inventory_payroll.sql.
-- Desktop steps run as app_owner (the database owner, like the Desktop over JDBC), phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

-- ------------------------------------------------------------------ helpers and fixtures (superuser)
create or replace function test.expect_detail(p_sql text, p_code text, p_detail text, p_label text) returns void
language plpgsql as $$
declare
    v_detail text;
begin
    begin
        execute p_sql;
    exception when others then
        get stacked diagnostics v_detail = pg_exception_detail;
        if sqlstate = p_code and coalesce(v_detail, '') = coalesce(p_detail, '') then
            raise notice 'PASS %  [% % %]', p_label, sqlstate, v_detail, sqlerrm;
            return;
        end if;
        raise exception 'FAIL %: expected % / %, got % / % (%)', p_label, p_code, p_detail, sqlstate, v_detail, sqlerrm;
    end;
    raise exception 'FAIL %: expected SQLSTATE % but the statement succeeded', p_label, p_code;
end $$;
grant execute on function test.expect_detail(text, text, text, text) to public;

\set ana '00000000-0000-0000-0000-00000000a0a0'
\set ben '00000000-0000-0000-0000-00000000b0b0'
-- agent.new, linked by test 44, is the "other agent"
\set other '00000000-0000-0000-0000-0000000a0001'
select (date_trunc('day', now() at time zone 'Asia/Manila')) at time zone 'Asia/Manila' as d0 \gset
select (date_trunc('day', now() at time zone 'Asia/Manila') + interval '1 day') at time zone 'Asia/Manila' as d1 \gset
select (now() at time zone 'Asia/Manila')::date as today \gset
-- Earlier test files left completed bookings dated today: the report checks below compare differences.
create table test.report_before as select * from trimax_private.profit_summary(:'d0', :'d1');

-- ================================================================== 1. Who can see and call what
select test.ok(not has_table_privilege('authenticated', 'public.sales', 'select')
               and not has_table_privilege('authenticated', 'public.sale_items', 'select')
               and not has_table_privilege('authenticated', 'public.inventory_movements', 'select')
               and not has_table_privilege('authenticated', 'public.payroll_records', 'select')
               and not has_table_privilege('anon', 'public.payroll_records', 'select')
               and not has_table_privilege('service_role', 'public.payroll_records', 'select')
               and not has_table_privilege('service_role', 'public.sales', 'select'),
               'phones, anonymous callers and the service role cannot read sales, payroll or stock history');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.create_sale(text,uuid,text,integer,text,jsonb,text,text,numeric,text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.save_payroll(text,bigint,uuid,text,text,date,date,numeric,numeric,numeric,text,date,text,text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.profit_summary(timestamptz,timestamptz)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.restock_product(text,integer,integer,numeric,text,boolean,uuid)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.save_product(text,integer,text,text,numeric,text,numeric,numeric,numeric,integer)', 'execute'),
               'Desktop functions are for the database owner only');
select test.ok(has_function_privilege('authenticated', 'public.trimax_agent_save_customer(text,text,text,double precision,double precision,text)', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_agent_list_customers()', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_agent_discount_quote(jsonb)', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_agent_set_discount(integer,text,numeric)', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_agent_create_booking(uuid,text,text,text,double precision,double precision,jsonb,text,text,text,numeric)', 'execute'),
               'signed-in phones can call the new agent functions');
select test.ok(not has_function_privilege('anon', 'public.trimax_agent_save_customer(text,text,text,double precision,double precision,text)', 'execute')
               and not has_function_privilege('anon', 'public.trimax_agent_list_customers()', 'execute')
               and not has_function_privilege('anon', 'public.trimax_agent_discount_quote(jsonb)', 'execute')
               and not has_function_privilege('anon', 'public.trimax_agent_set_discount(integer,text,numeric)', 'execute')
               and not has_function_privilege('anon', 'public.trimax_agent_create_booking(uuid,text,text,text,double precision,double precision,jsonb,text,text,text,numeric)', 'execute'),
               'anonymous callers cannot call them');
select test.ok((select count(*) from pg_proc where proname = 'trimax_agent_create_booking') = 1,
               'exactly one trimax_agent_create_booking (PostgREST never has to choose between two)');
set role authenticated;
select test.jwt(:'ana');
select test.expect_error('select * from public.sales', '42501', 'a phone cannot read sales');
select test.expect_error('select * from public.payroll_records', '42501', 'a phone cannot read payroll');
select test.expect_error('select capital_cost from public.products', '42501', 'a phone cannot read the puhunan');
select test.expect_error('update public.products set min_markup = 0', '42501', 'a phone cannot change the minimum patong');
select test.ok(public.trimax_list_products()::text !~ '(capital_cost|min_markup|markup)',
               'the phone''s product list has no puhunan or patong');
reset role;

-- ================================================================== 2. Products: puhunan + patong = price
set role app_owner;
select test.expect_error($$select trimax_private.save_product('agent.ana', null, 'X', 'S', 10, 'AMOUNT', 1, 0, null, 0)$$,
                         'TM403', 'only an Admin saves products');
select trimax_private.save_product('marc.admin', null, 'Test Cola 1L', 'Test Supplier', 40, 'AMOUNT', 10, 2, null, 20) as cola \gset
select test.ok((select price = 50 and stock = 20 and capital_cost = 40 and min_markup = 2 from products where product_id = :cola),
               'selling price = puhunan 40 + patong 10 = 50');
select test.ok((select count(*) = 1 from inventory_movements where product_id = :cola and kind = 'INITIAL'
                   and quantity_change = 20 and stock_after = 20 and actor_username = 'marc.admin'),
               'the starting stock is recorded as INITIAL, by the admin');
select trimax_private.save_product('marc.admin', null, 'Test Juice', 'Test Supplier', 80, 'PERCENT', 25, 0, null, 10) as juice \gset
select test.ok((select price = 100 from products where product_id = :juice), 'percent patong: 80 + 25% = 100');
select trimax_private.save_product('marc.admin', null, 'Test Candy', 'Test Supplier', null, null, null, null, 5.50, 100) as candy \gset
select test.ok((select price = 5.50 and capital_cost is null from products where product_id = :candy),
               'a product without puhunan keeps the price typed in');
select test.expect_error($$select trimax_private.save_product('marc.admin', null, 'Bad', 'S', null, 'AMOUNT', 5, 0, 10, 0)$$,
                         'TM422', 'patong without puhunan is refused');
select test.expect_error($$select trimax_private.save_product('marc.admin', null, 'Bad', 'S', 10, null, null, 0, 12, 0)$$,
                         'TM422', 'puhunan without patong is refused');
select test.expect_error($$select trimax_private.save_product('marc.admin', null, 'Bad', 'S', -1, 'AMOUNT', 5, 0, null, 0)$$,
                         'TM422', 'a negative puhunan is refused');
select test.expect_error($$select trimax_private.save_product('marc.admin', null, 'Bad', 'S', 10, 'AMOUNT', -5, 0, null, 0)$$,
                         'TM422', 'a negative patong is refused');
select test.expect_error($$select trimax_private.save_product('marc.admin', null, 'Bad', 'S', 10, 'AMOUNT', 5, -1, null, 0)$$,
                         'TM422', 'a negative minimum patong is refused');
select test.expect_error($$select trimax_private.save_product('marc.admin', null, 'Bad', 'S', null, null, null, 0, -3, 0)$$,
                         'TM422', 'a negative price is refused');
select test.expect_error($$select trimax_private.save_product('marc.admin', null, 'Bad', 'S', 10.555, 'AMOUNT', 5, 0, null, 0)$$,
                         'TM422', 'money with more than 2 decimals is refused');
select test.expect_error($$select trimax_private.save_product('marc.admin', null, 'Bad', 'S', 10, 'AMOUNT', 5, 0, null, -1)$$,
                         'TM422', 'negative starting stock is refused');
select test.expect_error($$select trimax_private.save_product('marc.admin', null, 'Test Cola 1L', 'Test Supplier', 1, 'AMOUNT', 1, 0, null, 0)$$,
                         'TM409', 'the same name and supplier twice is refused');
select trimax_private.save_product('marc.admin', :cola, 'Test Cola 1L', 'Test Supplier', 40, 'AMOUNT', 12, 2, null, 999);
select test.ok((select price = 52 and stock = 20 from products where product_id = :cola),
               'editing changes the price (40 + 12 = 52) but never the stock');
select test.ok((select count(*) = 1 from audit_log where action = 'product.update' and target_id = :'cola'
                   and details like '%price ₱50.00 -> ₱52.00%'), 'the price change is in the audit log');
update products set price = 1 where product_id = :cola;
select test.ok((select price = 52 from products where product_id = :cola),
               'a product with puhunan and patong always sells at puhunan + patong');

-- ================================================================== 3. Restock, adjustments, history
select gen_random_uuid() as r1 \gset
select * from trimax_private.restock_product('marc.admin', :cola, 10, 42, 'Supplier delivery', false, :'r1') \gset rs_
select test.ok(:rs_stock_before = 20 and :rs_stock_after = 30 and :rs_total_cost = 420 and not :'rs_already_existed'::boolean,
               'restock 10 at 42 each = 420; stock 20 -> 30');
select * from trimax_private.restock_product('marc.admin', :cola, 10, 42, 'Supplier delivery', false, :'r1') \gset rs2_
select test.ok(:'rs2_already_existed'::boolean and (select stock = 30 from products where product_id = :cola),
               'a retried restock (double click) adds nothing');
select test.ok((select count(*) = 1 from inventory_movements where client_request_id = :'r1' and kind = 'RESTOCK'
                   and unit_cost = 42 and total_cost = 420 and note = 'Supplier delivery' and occurred_at is not null),
               'restock history: date, quantity, cost per unit, total cost');
select test.ok((select capital_cost = 40 and price = 52 from products where product_id = :cola),
               'a restock without "use as new puhunan" keeps the puhunan and the price');
select * from trimax_private.restock_product('marc.admin', :cola, 5, 45, null, true, gen_random_uuid()) \gset rs3_
select test.ok((select capital_cost = 45 and price = 57 and stock = 35 from products where product_id = :cola) and :rs3_price = 57,
               'a restock used as the new puhunan: 45 + patong 12 = 57');
select test.expect_error(format($$select * from trimax_private.restock_product('marc.admin', %s, 0, 1, null, false, gen_random_uuid())$$, :cola),
                         'TM422', 'restock needs a quantity of 1 or more');
select test.expect_error(format($$select * from trimax_private.restock_product('marc.admin', %s, 5, -1, null, false, gen_random_uuid())$$, :cola),
                         'TM422', 'restock needs a cost per unit of 0 or more');
select test.expect_error(format($$select * from trimax_private.restock_product('marc.admin', %s, 5, null, null, false, gen_random_uuid())$$, :cola),
                         'TM422', 'restock needs the cost per unit');
select test.expect_error($$select * from trimax_private.restock_product('marc.admin', 999999, 5, 1, null, false, gen_random_uuid())$$,
                         'TM404', 'restock of an unknown product');
select test.expect_error(format($$select * from trimax_private.restock_product('marc.admin', %s, 1, 1, null, false, %L)$$, :juice, :'r1'),
                         'TM409', 'a used request id cannot restock another product');
select test.expect_error(format($$select * from trimax_private.restock_product('agent.ana', %s, 1, 1, null, false, gen_random_uuid())$$, :cola),
                         'TM403', 'only an Admin restocks');
select test.ok((select count(*) = 1 from audit_log where action = 'inventory.restock' and target_id = :'cola'
                   and details like '%+5 at ₱45.00 each = ₱225.00, stock 30 -> 35, puhunan ₱40.00 -> ₱45.00, price ₱52.00 -> ₱57.00%'),
               'the restock and the new puhunan and price are in the audit log');

select * from trimax_private.adjust_stock('marc.admin', :cola, -5, 'Damaged in storage', gen_random_uuid()) \gset adj_
select test.ok(:adj_stock_before = 35 and :adj_stock_after = 30, 'a correction of -5 with a reason');
select test.expect_error(format($$select * from trimax_private.adjust_stock('marc.admin', %s, -5, '', gen_random_uuid())$$, :cola),
                         'TM422', 'a correction needs a reason');
select test.expect_error(format($$select * from trimax_private.adjust_stock('marc.admin', %s, -500, 'count', gen_random_uuid())$$, :cola),
                         'TM409', 'stock cannot go below 0');

update products set stock = stock + 1 where product_id = :candy;
select test.ok((select count(*) = 1 from inventory_movements where product_id = :candy and kind = 'ADJUSTMENT'
                   and note like 'Stock changed without a TRIMAX reason%'),
               'a stock change made without a reason is still recorded');
select test.expect_error('update inventory_movements set quantity_change = 99', 'TM409', 'stock history cannot be edited');
select test.expect_error('delete from inventory_movements', 'TM409', 'stock history cannot be deleted');
select trimax_private.save_product('marc.admin', null, 'Test Temp', 'Test Supplier', null, null, null, null, 1, 3) as temp \gset
delete from products where product_id = :temp;
select test.ok((select count(*) = 1 from inventory_movements where product_id is null and product_name = 'Test Temp'),
               'deleting a product keeps its stock history (by name)');
begin;
select set_config('trimax.stock_kind', 'BOOKING', true), set_config('trimax.stock_ref_type', 'booking', true),
       set_config('trimax.stock_ref_id', 'desk-1', true), set_config('trimax.stock_actor', 'marc.admin', true);
update products set stock = stock - 1 where product_id = :juice;
commit;
select test.ok((select count(*) = 1 from inventory_movements where reference_id = 'desk-1' and kind = 'BOOKING'
                   and actor_username = 'marc.admin' and quantity_change = -1),
               'the Desktop labels its own stock changes the same way (set_config)');
reset role;

-- ================================================================== 4. Customers with a photo (phone)
set role authenticated;
select test.jwt(:'ana');
select test.expect_detail($$select public.trimax_agent_save_customer('New Shop', '0918 765 4321', 'Muzon', 14.8, 121.0)$$,
                          'TM422', 'customer_photo_required', 'a new customer cannot be saved without a photo');
select test.expect_detail(format($$select public.trimax_agent_save_customer('New Shop', '0918 765 4321', 'Muzon', 14.8, 121.0, %L)$$,
                                 :'ana' || '/customer_1.jpg'),
                          'TM422', 'customer_photo_missing', 'the photo must be uploaded before saving');
select test.expect_error(format($$select public.trimax_agent_save_customer('New Shop', '0918 765 4321', 'Muzon', 14.8, 121.0, %L)$$,
                                :'ben' || '/customer_1.jpg'), 'TM422', 'a photo from another phone''s folder is refused');
select test.expect_error(format($$insert into storage.objects (bucket_id, name, metadata) values ('customer-photos', %L, '{"size": 10}')$$,
                                :'ben' || '/customer_x.jpg'), '42501', 'a phone cannot upload into another phone''s folder');
insert into storage.objects (bucket_id, name, metadata) values ('customer-photos', :'ana' || '/customer_1.jpg', '{"size": 34567}');
select public.trimax_agent_save_customer('New Shop', '0918 765 4321', 'Muzon', 14.8, 121.0, :'ana' || '/customer_1.jpg') as saved \gset
select test.ok((:'saved'::jsonb ->> 'created')::boolean and :'saved'::jsonb ->> 'photo_path' = :'ana' || '/customer_1.jpg',
               'a new customer is saved with the photo');
select (:'saved'::jsonb ->> 'client_id')::int as shop \gset
select public.trimax_agent_save_customer('New Shop Renamed', '+63 918 765 4321', 'Muzon 2', 14.81, 121.01) as saved2 \gset
select test.ok(not (:'saved2'::jsonb ->> 'created')::boolean and :'saved2'::jsonb ->> 'name' = 'New Shop Renamed'
               and :'saved2'::jsonb ->> 'photo_path' = :'ana' || '/customer_1.jpg',
               'saving the same number again updates the customer and keeps the photo');
select test.ok((select count(*) = 1 from jsonb_array_elements(public.trimax_agent_list_customers()) e
                 where (e ->> 'client_id')::int = :shop and e ->> 'photo_path' is not null),
               'the agent''s customer list includes the photo');
select test.jwt(:'other');
select test.expect_error($$select public.trimax_agent_save_customer('Steal', '09187654321', 'X', 14.8, 121.0)$$,
                         'TM409', 'another agent cannot take over the customer');
select test.ok(not exists (select 1 from jsonb_array_elements(public.trimax_agent_list_customers()) e
                            where (e ->> 'client_id')::int = :shop),
               'another agent does not see the customer');
reset role;
set role app_owner;
select test.ok((select photo_path = :'ana' || '/customer_1.jpg' and photo_by = 'agent.ana' from clients where client_id = :shop)
               and (select count(*) = 1 from agents where client_id = :shop and username = 'agent.ana'),
               'the photo is saved with the customer record, assigned to the agent');
select test.ok(trimax_private.issue_media_ticket('marc.admin', 'customer-photos', :'ana' || '/customer_1.jpg') is not null,
               'an Admin can open the customer photo (view ticket)');
select test.ok((select count(*) >= 1 from audit_log where action = 'media.view' and details = 'customer-photos/' || :'ana' || '/customer_1.jpg'),
               'opening the photo is audited');
select test.expect_error(format($$select trimax_private.issue_media_ticket('agent.ana', 'customer-photos', %L)$$, :'ana' || '/customer_1.jpg'),
                         'TM403', 'only an Admin gets photo tickets');
select test.ok((select count(*) = 1 from audit_log where action = 'customer.create' and target_id = :'shop' and actor_username = 'agent.ana'),
               'a customer added from the phone is audited');
reset role;

-- ================================================================== 5. Agent discounts and the minimum price
set role authenticated;
select test.jwt(:'ana');
-- Cola: price 57, puhunan 45, minimum patong 2. Two of them: 114; floor 2 x 47 = 94; at most 20 off.
select public.trimax_agent_discount_quote(format('[{"product_id": %s, "quantity": 2}]', :cola)::jsonb) as q1 \gset
select test.ok((:'q1'::jsonb ->> 'subtotal')::numeric = 114 and (:'q1'::jsonb ->> 'max_discount')::numeric = 20
               and (:'q1'::jsonb ->> 'discount_available')::boolean,
               'quote: 2 x 57 = 114, floor 2 x (45 + 2) = 94, so at most 20 off');
select test.ok(not (:'q1'::jsonb ? 'floor_total') and not (:'q1'::jsonb ? 'capital_cost')
               and (select array_agg(k order by k) from jsonb_object_keys(:'q1'::jsonb) k)
                   = array['discount_available', 'max_discount', 'reason', 'subtotal'],
               'the quote gives only the subtotal and the largest discount, never the puhunan');
select public.trimax_agent_discount_quote(format('[{"product_id": %s, "quantity": 1}, {"product_id": %s, "quantity": 1}]', :cola, :candy)::jsonb) as q2 \gset
select test.ok(not (:'q2'::jsonb ->> 'discount_available')::boolean and :'q2'::jsonb ->> 'reason' like '%Test Candy%',
               'quote: no discount when a product has no puhunan, and it says which product');
select test.expect_detail(format($$select public.trimax_agent_create_booking(gen_random_uuid(), 'Ghost', '0999 000 1111', 'X', 14.8, 121.0,
                                   '[{"product_id": %s, "quantity": 1}]')$$, :cola),
                          'TM422', 'customer_photo_required', 'a booking for a customer that was never saved asks for the photo first');
select test.expect_detail(format($$select public.trimax_agent_create_booking(gen_random_uuid(), 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                                   '[{"product_id": %s, "quantity": 2}]', null, null, 'AMOUNT', 20.01)$$, :cola),
                          'TM422', 'discount_too_big', 'a discount below puhunan + minimum patong is refused (20.01 > 20)');
select test.expect_detail(format($$select public.trimax_agent_create_booking(gen_random_uuid(), 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                                   '[{"product_id": %s, "quantity": 2}]', null, null, 'PERCENT', 18)$$, :cola),
                          'TM422', 'discount_too_big', 'the same limit applies to a percent (18% of 114 = 20.52)');
select test.expect_detail(format($$select public.trimax_agent_create_booking(gen_random_uuid(), 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                                   '[{"product_id": %s, "quantity": 1}]', null, null, 'AMOUNT', 1)$$, :candy),
                          'TM422', 'discount_not_configured', 'no discount on a product without puhunan');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                                  '[{"product_id": %s, "quantity": 2}]', null, null, 'PERCENT', 101)$$, :cola),
                         'TM422', 'a percent above 100 is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                                  '[{"product_id": %s, "quantity": 2}]', null, null, 'AMOUNT', -5)$$, :cola),
                         'TM422', 'a negative discount is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                                  '[{"product_id": %s, "quantity": 2}]', null, null, null, 5)$$, :cola),
                         'TM422', 'a discount needs a type (peso or percent)');
select gen_random_uuid() as bk1req \gset
select public.trimax_agent_create_booking(:'bk1req', 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                                          format('[{"product_id": %s, "quantity": 2}]', :cola)::jsonb, null, null, 'AMOUNT', 20) as bk1 \gset
select test.ok((:'bk1'::jsonb ->> 'total_amount')::numeric = 94 and (:'bk1'::jsonb ->> 'subtotal_amount')::numeric = 114
               and (:'bk1'::jsonb -> 'discount' ->> 'amount')::numeric = 20 and :'bk1'::jsonb -> 'discount' ->> 'by' = 'agent.ana',
               'the largest allowed discount is accepted: 114 - 20 = 94, by the agent');
select (:'bk1'::jsonb ->> 'booking_id')::int as bk1id \gset
select test.ok((public.trimax_agent_create_booking(:'bk1req', 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                   format('[{"product_id": %s, "quantity": 2}]', :cola)::jsonb, null, null, 'AMOUNT', 20) ->> 'already_existed')::boolean,
               'a retried booking is not saved twice');
-- Juice: price 100, puhunan 80, minimum patong 0.
select public.trimax_agent_create_booking(gen_random_uuid(), 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                                          format('[{"product_id": %s, "quantity": 1}]', :juice)::jsonb, null, null, 'PERCENT', 10) as bk2 \gset
select test.ok((:'bk2'::jsonb ->> 'total_amount')::numeric = 90 and :'bk2'::jsonb -> 'discount' ->> 'type' = 'PERCENT',
               'percent discount: 10% of 100 = 10, final 90');
select (:'bk2'::jsonb ->> 'booking_id')::int as bk2id \gset
select test.ok((public.trimax_agent_set_discount(:bk2id, 'AMOUNT', 20) ->> 'total_amount')::numeric = 80,
               'the agent can change the discount of a Pending booking, down to the floor (80)');
select test.expect_detail(format($$select public.trimax_agent_set_discount(%s, 'AMOUNT', 20.01)$$, :bk2id),
                          'TM422', 'discount_too_big', 'and not below it');
select test.ok((public.trimax_agent_set_discount(:bk2id, 'AMOUNT', 0) -> 'discount') = 'null'::jsonb,
               'a discount of 0 removes the discount');
select public.trimax_agent_create_booking(gen_random_uuid(), 'New Shop', '09187654321', 'Muzon', 14.8, 121.0,
                                          format('[{"product_id": %s, "quantity": 1}]', :juice)::jsonb, null, null, 'PERCENT', 5) as bk3 \gset
select (:'bk3'::jsonb ->> 'booking_id')::int as bk3id \gset
select test.jwt(:'other');
select test.expect_error(format($$select public.trimax_agent_set_discount(%s, 'AMOUNT', 1)$$, :bk2id),
                         'TM404', 'another agent cannot touch the discount');
reset role;

set role app_owner;
select test.ok((select stock = 28 from products where product_id = :cola),
               'stock: only the saved booking took 2; the refused ones took nothing');
select test.ok((select count(*) = 1 from inventory_movements where product_id = :cola and kind = 'BOOKING'
                   and reference_id = :'bk1id' and quantity_change = -2 and actor_username = 'agent.ana'),
               'the booking''s stock movement names the booking and the agent');
select test.ok((select unit_cost = 45 from booking_items where booking_id = :bk1id),
               'the booking line keeps the puhunan of the day (45) for profit reports');
select test.ok((select count(*) = 1 from audit_log where action = 'booking.discount' and target_id = :'bk1id'
                   and actor_username = 'agent.ana' and details = 'Original ₱114.00, discount ₱20.00, final ₱94.00'),
               'the discount is audited: agent, original price, discount, final price');
select test.ok((select discount_by = 'agent.ana' and discount_at is not null and subtotal_amount = 114 and discount_amount = 20
                       and total_amount = 94 from bookings where booking_id = :bk1id),
               'the booking keeps original, discount, final, who and when');
-- The Desktop changes the lines of a booking with a 5% discount: the discount follows the new total.
update booking_items set quantity = 2, subtotal = 200 where booking_id = :bk3id;
select test.ok((select subtotal_amount = 200 and discount_amount = 10 and total_amount = 190 from bookings where booking_id = :bk3id),
               'a percent discount follows a change of the lines (5% of 200)');
-- The Admin raises the minimum patong: the agent's limit shrinks.
select trimax_private.save_product('marc.admin', :cola, 'Test Cola 1L', 'Test Supplier', 45, 'AMOUNT', 12, 10, null, 0);
reset role;
set role authenticated;
select test.jwt(:'ana');
select test.ok((public.trimax_agent_discount_quote(format('[{"product_id": %s, "quantity": 2}]', :cola)::jsonb) ->> 'max_discount')::numeric = 4,
               'a higher minimum patong (10) lowers what an agent can give (114 - 2 x 55 = 4)');
reset role;

-- ================================================================== 6. Walk-In and In-House sales (Desktop)
set role app_owner;
select gen_random_uuid() as s1req \gset
select * from trimax_private.create_sale('marc.admin', :'s1req', 'WALK_IN', null, 'Juan',
    format('[{"product_id": %s, "quantity": 3, "discount": 5}, {"product_id": %s, "quantity": 10}]', :cola, :candy)::jsonb,
    'PAID', 'CASH', 250, 'test') \gset s1_
select test.ok(:s1_total_amount = 221 and :'s1_receipt_no' ~ '^TRX-[0-9]{8}-[0-9]{5}$' and not :'s1_already_existed'::boolean,
               'Walk-In sale: 3 x 57 - 5 + 10 x 5.50 = 221, receipt TRX-yyyymmdd-nnnnn');
select test.ok((select subtotal_amount = 226 and discount_amount = 5 and amount_tendered = 250 and change_due = 29
                       and payment_status = 'PAID' and paid_at is not null and cost_amount = 135 and cost_missing_lines = 1
                       and customer_name = 'Juan' and created_by = 'marc.admin' from sales where sale_id = :s1_sale_id),
               'sale totals, change 29, cost 3 x 45 = 135, one line without puhunan');
select test.ok((select count(*) = 2 from sale_items where sale_id = :s1_sale_id)
               and (select unit_price = 57 and unit_cost = 45 and line_subtotal = 171 and line_discount = 5 and line_total = 166
                      from sale_items where sale_id = :s1_sale_id and product_id = :cola),
               'each line keeps price, puhunan, discount and total');
select test.ok((select stock = 25 from products where product_id = :cola) and (select stock = 91 from products where product_id = :candy),
               'stock taken: cola 28 -> 25, candy 101 -> 91');
select test.ok((select count(*) = 2 from inventory_movements where kind = 'SALE' and reference_id = :'s1_receipt_no'),
               'one SALE movement per product, with the receipt number');
select * from trimax_private.create_sale('marc.admin', :'s1req', 'WALK_IN', null, 'Juan',
    format('[{"product_id": %s, "quantity": 3, "discount": 5}, {"product_id": %s, "quantity": 10}]', :cola, :candy)::jsonb,
    'PAID', 'CASH', 250, 'test') \gset s1b_
select test.ok(:'s1b_already_existed'::boolean and :'s1b_receipt_no' = :'s1_receipt_no' and (select stock = 25 from products where product_id = :cola),
               'a double-clicked sale is saved once and takes stock once');
select test.expect_error(format($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'IN_HOUSE', null, null,
    '[{"product_id": %s, "quantity": 1}]', 'PAID', 'CASH', null, null)$$, :cola), 'TM422', 'In-House needs a saved customer');
select test.expect_error(format($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', %s, null,
    '[{"product_id": %s, "quantity": 1}]', 'PAID', 'CASH', null, null)$$, :shop, :cola), 'TM422', 'Walk-In has no customer record');
select test.expect_error(format($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, null,
    '[{"product_id": %s, "quantity": 1000}]', 'PAID', 'CASH', null, null)$$, :cola), 'TM409', 'not enough stock: refused');
select test.expect_error(format($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, null,
    '[{"product_id": %s, "quantity": 1, "discount": 58}]', 'PAID', 'CASH', null, null)$$, :cola), 'TM422', 'a line discount above its subtotal is refused');
select test.expect_error(format($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, null,
    '[{"product_id": %s, "quantity": 1, "discount": -1}]', 'PAID', 'CASH', null, null)$$, :cola), 'TM422', 'a negative discount is refused');
select test.expect_error(format($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, null,
    '[{"product_id": %s, "quantity": 1}]', 'PAID', 'CASH', 1, null)$$, :cola), 'TM422', 'cash received below the total is refused');
select test.expect_error(format($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, null,
    '[{"product_id": %s, "quantity": 1}]', 'UNPAID', 'CASH', null, null)$$, :cola), 'TM422', 'an unpaid sale has no payment method');
select test.expect_error(format($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, null,
    '[{"product_id": %s, "quantity": 1}]', 'PAID', null, null, null)$$, :cola), 'TM422', 'a paid sale needs the payment method');
select test.expect_error($$select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, null,
    '[]', 'PAID', 'CASH', null, null)$$, 'TM422', 'a sale needs at least one product');
select test.expect_error(format($$select * from trimax_private.create_sale('agent.ana', gen_random_uuid(), 'WALK_IN', null, null,
    '[{"product_id": %s, "quantity": 1}]', 'PAID', 'CASH', null, null)$$, :cola), 'TM403', 'only an Admin records sales');
select test.ok((select stock = 25 from products where product_id = :cola), 'refused sales took no stock');

select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'IN_HOUSE', :shop, null,
    format('[{"product_id": %s, "quantity": 1}]', :juice)::jsonb, 'UNPAID', null, null, null) \gset s2_
select test.ok((select customer_name = 'New Shop Renamed' and client_id = :shop and payment_status = 'UNPAID' and paid_at is null
                  from sales where sale_id = :s2_sale_id), 'In-House sale on credit for a saved customer');
select trimax_private.set_sale_paid('marc.admin', :s2_sale_id, 'GCASH');
select test.ok((select payment_status = 'PAID' and payment_method = 'GCASH' and paid_at is not null from sales where sale_id = :s2_sale_id),
               'it is marked paid later');
select test.expect_error(format($$select trimax_private.set_sale_paid('marc.admin', %s, 'CASH')$$, :s2_sale_id),
                         'TM409', 'a sale cannot be paid twice');
select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'IN_HOUSE', :shop, null,
    format('[{"product_id": %s, "quantity": 1}]', :juice)::jsonb, 'UNPAID', null, null, null) \gset s3_

select * from trimax_private.create_sale('marc.admin', gen_random_uuid(), 'WALK_IN', null, null,
    format('[{"product_id": %s, "quantity": 4}]', :cola)::jsonb, 'PAID', 'CASH', null, null) \gset s4_
select test.ok((select stock = 21 from products where product_id = :cola), 'a sale of 4 takes 4');
select test.expect_error(format($$select trimax_private.void_sale('marc.admin', %s, '')$$, :s4_sale_id),
                         'TM422', 'voiding needs a reason');
select trimax_private.void_sale('marc.admin', :s4_sale_id, 'Wrong item rung up');
select test.ok((select stock = 25 from products where product_id = :cola)
               and (select status = 'VOIDED' and voided_by = 'marc.admin' and void_reason = 'Wrong item rung up'
                      from sales where sale_id = :s4_sale_id),
               'voiding returns the stock and keeps who, when and why');
select test.ok((select count(*) = 1 from inventory_movements where kind = 'SALE_VOIDED' and reference_id = :'s4_receipt_no'
                   and quantity_change = 4), 'the returned stock is in the history');
select test.expect_error(format($$select trimax_private.void_sale('marc.admin', %s, 'again')$$, :s4_sale_id),
                         'TM409', 'a sale is voided only once');
select test.expect_error(format($$update sale_items set quantity = 1 where sale_id = %s$$, :s1_sale_id),
                         'TM409', 'sale lines cannot be edited');
select test.expect_error(format($$delete from sale_items where sale_id = %s$$, :s1_sale_id),
                         'TM409', 'sale lines cannot be deleted');
select test.ok((select count(*) = 1 from audit_log where action = 'sale.void' and target_id = :'s4_receipt_no')
               and (select count(*) = 1 from audit_log where action = 'sale.create' and target_id = :'s1_receipt_no'),
               'sales and voids are audited');

-- ================================================================== 7. Payroll (Desktop)
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), 'agent.ana', null, :'today'::date - 14, :'today',
                                   8000, 500, 300, 'PAID', :'today', 'CASH', null) as p1 \gset
select test.ok((select net_pay = 8200 and employee_name = 'Ana Agent' and status = 'PAID' from payroll_records where payroll_id = :p1),
               'payroll: 8000 + 500 - 300 = 8200; the name comes from the account');
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), 'agent.ana', null,
    %L::date - 14, %L::date, 1, 0, 0, 'UNPAID', null, null, null)$$, :'today', :'today'),
    'TM409', 'one payroll record per employee per pay period (never counted twice)');
select gen_random_uuid() as preq \gset
select trimax_private.save_payroll('marc.admin', null, :'preq', null, 'Pedro Helper', :'today', :'today', 600, 0, 0,
                                   'UNPAID', null, null, 'Daily helper') as p2 \gset
select test.ok(trimax_private.save_payroll('marc.admin', null, :'preq', null, 'Pedro Helper', :'today', :'today', 600, 0, 0,
                                           'UNPAID', null, null, 'Daily helper') = :p2,
               'a double-clicked payroll record is saved once');
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, 'X', %L, %L, 600, 0, 601,
    'UNPAID', null, null, null)$$, :'today', :'today'), 'TM422', 'deductions above pay are refused');
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, 'X', %L, %L, 600, 0, 0,
    'PAID', null, null, null)$$, :'today', :'today'), 'TM422', 'paid needs the payment date');
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, 'X', %L, %L::date - 1, 600, 0, 0,
    'UNPAID', null, null, null)$$, :'today', :'today'), 'TM422', 'the period cannot end before it starts');
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, 'X', %L, %L, -1, 0, 0,
    'UNPAID', null, null, null)$$, :'today', :'today'), 'TM422', 'negative pay is refused');
select test.expect_error(format($$select trimax_private.save_payroll('agent.ana', null, gen_random_uuid(), null, 'X', %L, %L, 1, 0, 0,
    'UNPAID', null, null, null)$$, :'today', :'today'), 'TM403', 'only an Admin records payroll');
select trimax_private.save_payroll('marc.admin', :p2, null, null, 'Pedro Helper', :'today', :'today', 700, 0, 50,
                                   'UNPAID', null, null, 'Daily helper');
select test.ok((select net_pay = 650 and updated_by = 'marc.admin' from payroll_records where payroll_id = :p2)
               and (select count(*) = 1 from audit_log where action = 'payroll.update' and target_id = :'p2'
                       and details like '%net ₱600.00 -> ₱650.00%'),
               'an edit keeps an audit trail of the amounts');
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), 'driver.dan', null, :'today', :'today', 1000, 0, 0,
                                   'UNPAID', null, null, null) as p3 \gset
select trimax_private.void_payroll('marc.admin', :p3, 'Entered twice');
select test.ok((select status = 'VOID' and voided_by = 'marc.admin' from payroll_records where payroll_id = :p3),
               'a wrong payroll record is voided, not deleted');
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', %s, null, 'driver.dan', null, %L, %L, 1, 0, 0,
    'UNPAID', null, null, null)$$, :p3, :'today', :'today'), 'TM409', 'a voided record cannot be edited');
select test.ok(trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), 'driver.dan', null, :'today', :'today', 1000, 0, 0,
                                           'UNPAID', null, null, null) > :p3,
               'after voiding, the same period can be entered again');
reset role;

-- ================================================================== 8. Sales and profit report
set role app_owner;
update bookings set status = 'Completed' where booking_id = :bk1id;
-- A Desktop booking of a product without puhunan (its cost is unknown).
insert into bookings (client_id, status) values (:shop, 'Pending') returning booking_id as deskbk \gset
insert into booking_items (booking_id, product_id, quantity, unit_price, subtotal) values (:deskbk, :candy, 2, 5.50, 11.00);
update bookings set status = 'Completed' where booking_id = :deskbk;
select test.ok((select completed_at is not null from bookings where booking_id = :bk1id),
               'completing a booking records when it was completed');
reset role;
create table test.report_after as select * from trimax_private.profit_summary(:'d0', :'d1');
select test.ok((select a.bookings_count - b.bookings_count = 2 and a.bookings_gross - b.bookings_gross = 125
                       and a.bookings_discount - b.bookings_discount = 20 and a.bookings_net - b.bookings_net = 105
                       and a.bookings_cogs - b.bookings_cogs = 90 and a.bookings_lines_no_cost - b.bookings_lines_no_cost = 1
                  from test.report_after a, test.report_before b),
               'bookings: only the 2 completed ones count; 125 - 20 discount = 105 net, cost 90, 1 line without cost');
select test.ok((select a.sales_count - b.sales_count = 2 and a.sales_gross - b.sales_gross = 326
                       and a.sales_discount - b.sales_discount = 5 and a.sales_net - b.sales_net = 321
                       and a.sales_cogs - b.sales_cogs = 215 and a.sales_lines_no_cost - b.sales_lines_no_cost = 1
                       and a.unpaid_sales_count - b.unpaid_sales_count = 1 and a.unpaid_sales_total - b.unpaid_sales_total = 100
                  from test.report_after a, test.report_before b),
               'sales: paid ones count (221 + 100), the unpaid one is shown apart, the voided one never');
select test.ok((select a.revenue_net - b.revenue_net = 426 and a.cogs - b.cogs = 305 and a.gross_profit - b.gross_profit = 121
                  from test.report_after a, test.report_before b),
               'net sales 426 - cost of goods 305 = gross profit 121');
select test.ok((select a.payroll_paid - b.payroll_paid = 8200 and a.payroll_unpaid - b.payroll_unpaid = 1650
                       and a.payroll_count - b.payroll_count = 3 and a.net_profit - b.net_profit = 121 - 9850
                  from test.report_after a, test.report_before b),
               'net profit = gross profit 121 - payroll 9850 (paid 8200, unpaid 1650; the voided one never)');
select test.ok((select a.restock_cost - b.restock_cost = 645 and a.restock_count - b.restock_count = 2
                       and a.revenue_net - b.revenue_net = 426
                  from test.report_after a, test.report_before b),
               'restocks (645) are shown as purchases and are not revenue');
select test.ok((select count(*) = 3 from trimax_private.profit_trend(:'d0'::timestamptz - interval '2 days', :'d1', 'day')),
               'daily trend lists every day of the range, also empty ones');
select test.ok((select sum(t.revenue) = s.revenue_net and sum(t.net_profit) = s.net_profit and sum(t.cogs) = s.cogs
                  from trimax_private.profit_trend(:'d0'::timestamptz - interval '2 days', :'d1', 'day') t,
                       trimax_private.profit_summary(:'d0'::timestamptz - interval '2 days', :'d1') s
                 group by s.revenue_net, s.net_profit, s.cogs),
               'the daily trend adds up to the summary of the same range');
select test.ok((select sum(t.revenue) = s.revenue_net and sum(t.payroll) = s.payroll_paid + s.payroll_unpaid
                  from trimax_private.profit_trend(:'d0'::timestamptz - interval '40 days', :'d1', 'month') t,
                       trimax_private.profit_summary(:'d0'::timestamptz - interval '40 days', :'d1') s
                 group by s.revenue_net, s.payroll_paid, s.payroll_unpaid),
               'the monthly trend adds up too');
select test.ok((select revenue_net = 0 and net_profit = 0 and payroll_count = 0
                  from trimax_private.profit_summary(:'d1'::timestamptz + interval '10 days', :'d1'::timestamptz + interval '11 days')),
               'an empty period gives zeros, not errors');
drop table test.report_before, test.report_after;

select 'ALL SALES / INVENTORY / PAYROLL SQL TESTS PASSED' as result;
\echo ALL SALES / INVENTORY / PAYROLL SQL TESTS PASSED
