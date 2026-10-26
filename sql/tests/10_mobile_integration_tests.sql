-- TEST ONLY. Run by sql/tests/run_tests.sh on a disposable database after the migration.
-- Every check prints "PASS ..." or stops the script with "FAIL ...".
-- Phone calls run as the Supabase "authenticated" role with a JWT subject, exactly as PostgREST does;
-- desktop steps run as app_owner using the same SQL statements the desktop code executes.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

-- ------------------------------------------------------------------ test helpers (superuser)
create schema test;
grant usage on schema test to public;

create function test.expect_error(p_sql text, p_code text, p_label text) returns void
language plpgsql as $$
begin
    begin
        execute p_sql;
    exception when others then
        if sqlstate = p_code then
            raise notice 'PASS %  [% %]', p_label, sqlstate, sqlerrm;
            return;
        end if;
        raise exception 'FAIL %: expected SQLSTATE %, got % (%)', p_label, p_code, sqlstate, sqlerrm;
    end;
    raise exception 'FAIL %: expected SQLSTATE % but the statement succeeded', p_label, p_code;
end $$;

create function test.ok(p_cond boolean, p_label text) returns void
language plpgsql as $$
begin
    if p_cond is true then
        raise notice 'PASS %', p_label;
    else
        raise exception 'FAIL %', p_label;
    end if;
end $$;
grant execute on all functions in schema test to public;

create function test.jwt(p_uid uuid) returns void
language sql as $$ select set_config('request.jwt.claims', json_build_object('sub', p_uid, 'role', 'authenticated')::text, false) $$;
grant execute on function test.jwt(uuid) to public;

-- ------------------------------------------------------------------ Supabase Auth users (fake)
insert into auth.users (id, email, email_confirmed_at) values
    ('00000000-0000-0000-0000-00000000a0a0', 'ana@example.test',   now()),
    ('00000000-0000-0000-0000-00000000b0b0', 'BEN@example.test',   now()),
    ('00000000-0000-0000-0000-00000000c0c0', 'rio@example.test',   now()),
    ('00000000-0000-0000-0000-00000000d0d0', 'dan@example.test',   now()),
    ('00000000-0000-0000-0000-00000000e0e0', 'dee@example.test',   now()),
    ('00000000-0000-0000-0000-00000000f0f0', 'old@example.test',   now()),
    ('00000000-0000-0000-0000-000000001111', 'marc@example.test',  now()),
    ('00000000-0000-0000-0000-000000002222', 'stranger@example.test', now()),
    ('00000000-0000-0000-0000-000000003333', 'unconfirmed@example.test', null);

\set ana  '00000000-0000-0000-0000-00000000a0a0'
\set ben  '00000000-0000-0000-0000-00000000b0b0'
\set rio  '00000000-0000-0000-0000-00000000c0c0'
\set dan  '00000000-0000-0000-0000-00000000d0d0'
\set dee  '00000000-0000-0000-0000-00000000e0e0'
\set old  '00000000-0000-0000-0000-00000000f0f0'
\set stranger '00000000-0000-0000-0000-000000002222'

-- ================================================================== 1. Linking (administrator)
set role app_owner;
select trimax_private.link_mobile_account('agent.ana', 'ana@example.test', 'marc.admin') is not null as ok \gset
select test.ok(:'ok'::boolean, 'link agent.ana');
select test.ok(trimax_private.link_mobile_account('agent.ben', 'ben@example.test', 'marc.admin') is not null, 'link is case-insensitive on email (BEN@ vs ben@)');
select test.ok(trimax_private.link_mobile_account('rider.rio', 'rio@example.test') is not null, 'link legacy Rider account');
select test.ok(trimax_private.link_mobile_account('driver.dan', 'dan@example.test') is not null, 'link driver.dan');
select test.ok(trimax_private.link_mobile_account('driver.dee', 'dee@example.test') is not null, 'link driver.dee');
select test.ok(trimax_private.link_mobile_account('driver.old', 'old@example.test') is not null, 'link a Disabled account (access is still refused at sign-in, see below)');
select test.expect_error($$select trimax_private.link_mobile_account('agent.ana', 'stranger@example.test')$$, 'TM422', 'link refused when Auth email differs from the TRIMAX email');
select test.expect_error($$select trimax_private.link_mobile_account('marc.admin', 'marc@example.test')$$, 'TM422', 'link refused for an Admin account');
select test.expect_error($$select trimax_private.link_mobile_account('driver.nomail', 'x@example.test')$$, 'TM422', 'link refused when the TRIMAX account has no email');
select test.expect_error($$select trimax_private.link_mobile_account('nobody', 'ana@example.test')$$, 'TM404', 'link refused for unknown username');
update users set email = 'unconfirmed@example.test' where username = 'driver.nomail';
select test.expect_error($$select trimax_private.link_mobile_account('driver.nomail', 'unconfirmed@example.test')$$, 'TM422', 'link refused when the Auth email is not confirmed');
update users set email = 'ana@example.test' where username = 'driver.nomail';
select test.expect_error($$select trimax_private.link_mobile_account('driver.nomail', 'ana@example.test')$$, 'TM409', 'one sign-in cannot be linked to two TRIMAX accounts');
update users set email = null where username = 'driver.nomail';
select test.ok((select count(*) from audit_log where action = 'account.link_mobile') = 6, 'every successful link is in audit_log');
reset role;

-- ================================================================== 2. API roles cannot touch tables
set role anon;
select test.expect_error('select * from public.users', '42501', 'anon cannot read users (password hashes)');
select test.expect_error('select * from public.bookings', '42501', 'anon cannot read bookings');
select test.expect_error('select public.trimax_my_account()', '42501', 'anon cannot call mobile functions');
select test.expect_error('select * from trimax_private.mobile_accounts', '42501', 'anon cannot read the link table');
reset role;

set role authenticated;
select test.jwt(:'ana');
select test.expect_error('select * from public.users', '42501', 'signed-in user cannot read users');
select test.expect_error('select * from public.bookings', '42501', 'signed-in user cannot read bookings directly');
select test.expect_error($$update public.bookings set driver_username = 'driver.dan'$$, '42501', 'signed-in user cannot update bookings directly');
select test.expect_error('update public.products set price = 0', '42501', 'signed-in user cannot change prices');
select test.expect_error('insert into public.users (username, password) values (''x'', ''x'')', '42501', 'signed-in user cannot create users');
select test.expect_error('select * from trimax_private.delivery_events', '42501', 'signed-in user cannot read delivery events');
select test.expect_error('create function public.contact_key(text) returns text language sql as ''select 1::text''', '42501',
    'signed-in user cannot create objects in public (keeps the functions'' search_path safe)');
select test.expect_error($$select trimax_private.link_mobile_account('agent.ana', 'ana@example.test')$$, '42501', 'signed-in user cannot call the link function');
select test.expect_error($$select trimax_private.unlink_mobile_account('agent.ben')$$, '42501', 'signed-in user cannot call the unlink function');

-- ================================================================== 3. Identity
select test.jwt(:'stranger');
select test.expect_error('select public.trimax_my_account()', 'TM401', 'unlinked sign-in is refused');
select set_config('request.jwt.claims', '', false);
select test.expect_error('select public.trimax_my_account()', 'TM401', 'no JWT subject is refused');
select test.jwt(:'ana');
select test.ok(public.trimax_my_account() ->> 'role' = 'AGENT' and public.trimax_my_account() ->> 'username' = 'agent.ana', 'agent.ana resolves to AGENT');
select test.jwt(:'rio');
select test.ok(public.trimax_my_account() ->> 'role' = 'AGENT', 'legacy Rider resolves to AGENT');
select test.jwt(:'dan');
select test.ok(public.trimax_my_account() ->> 'role' = 'DRIVER', 'driver.dan resolves to DRIVER');
select test.jwt(:'old');
select test.expect_error('select public.trimax_my_account()', 'TM401', 'Disabled account is refused even though it is linked');

-- ================================================================== 4. Agent creates bookings
select test.jwt(:'dan');
select test.expect_error('select public.trimax_list_products()', 'TM403', 'driver cannot list products');
select test.jwt(:'ana');
select test.ok(jsonb_array_length(public.trimax_list_products()) = 3, 'agent lists products');

select public.trimax_agent_create_booking(
    '11111111-1111-1111-1111-111111111111', 'New Customer Cuatro', '0918 777 8888',
    'Blk 1 Lot 2, Muzon, SJDM', 14.81, 121.05,
    '[{"product_id": 1, "quantity": 2}, {"product_id": 2, "quantity": 1}]',
    'ASAP', 'Gate code 1234') as r1 \gset
select (:'r1'::jsonb ->> 'booking_id')::int as b1 \gset
select test.ok((:'r1'::jsonb ->> 'status') = 'Pending', 'mobile booking is Pending');
select test.ok((:'r1'::jsonb -> 'driver') = 'null'::jsonb and (:'r1'::jsonb ->> 'delivery_status') is null, 'mobile booking has no driver (no fake fallback)');
select test.ok((:'r1'::jsonb ->> 'total_amount')::numeric = 540.00, 'total is priced by the database: 2 x 180 + 1 x 180 = 540');
select test.ok((:'r1'::jsonb ->> 'already_existed')::boolean = false, 'first submission creates the booking');
select test.ok((:'r1'::jsonb ->> 'customer_matched_existing')::boolean = false, 'new customer was created');

select public.trimax_agent_create_booking(
    '11111111-1111-1111-1111-111111111111', 'New Customer Cuatro', '0918 777 8888',
    'Blk 1 Lot 2, Muzon, SJDM', 14.81, 121.05,
    '[{"product_id": 1, "quantity": 2}, {"product_id": 2, "quantity": 1}]') as r1b \gset
select test.ok((:'r1b'::jsonb ->> 'booking_id')::int = :b1 and (:'r1b'::jsonb ->> 'already_existed')::boolean, 'retry with the same request id returns the same booking');

select test.ok(jsonb_array_length(public.trimax_agent_create_booking(
    '22222222-2222-2222-2222-222222222222', 'Sari-Sari Uno', '+63 917 111 2222',
    'Muzon, SJDM', 14.80, 121.04, '[{"product_id": 1, "quantity": 1}, {"product_id": 1, "quantity": 2}]') -> 'items') = 1,
    'repeated product lines are merged');
select (public.trimax_agent_list_bookings() -> 0) as r2 \gset
select (:'r2'::jsonb ->> 'booking_id')::int as b2 \gset
select test.ok((:'r2'::jsonb -> 'customer' ->> 'client_id')::int = 1, 'existing customer of this agent is reused (matched by contact number)');
select test.ok((:'r2'::jsonb -> 'items' -> 0 ->> 'quantity')::int = 3, 'merged quantity is 3');

select test.expect_error($$select public.trimax_agent_create_booking('33333333-3333-3333-3333-333333333333', 'X', '0917-333-4444', 'Tungko', 14.78, 121.06, '[{"product_id":1,"quantity":1}]')$$,
    'TM409', 'another agent''s customer cannot be used');
select test.expect_error($$select public.trimax_agent_create_booking('33333333-3333-3333-3333-333333333334', 'X', '09175556666', 'Graceville', 14.79, 121.05, '[{"product_id":1,"quantity":1}]')$$,
    'TM409', 'an unassigned customer cannot be claimed from the phone');
select test.expect_error($$select public.trimax_agent_create_booking('44444444-4444-4444-4444-444444444444', 'Stock Test', '0920 000 0001', 'Somewhere', 14.7, 121.0, '[{"product_id":2,"quantity":3}]')$$,
    'TM409', 'not enough stock (2 left, 3 ordered)');
select test.expect_error($$select public.trimax_agent_create_booking('44444444-4444-4444-4444-444444444445', 'Zero', '0920 000 0002', 'Somewhere', 14.7, 121.0, '[{"product_id":3,"quantity":1}]')$$,
    'TM409', 'product with zero stock');
select test.expect_error($$select public.trimax_agent_create_booking('55555555-5555-5555-5555-555555555555', 'X', '0920 000 0003', 'Somewhere', 14.7, 121.0, '[{"product_id":999,"quantity":1}]')$$,
    'TM404', 'unknown product');
select test.expect_error($$select public.trimax_agent_create_booking('55555555-5555-5555-5555-555555555556', 'X', '0920 000 0003', 'Somewhere', 14.7, 121.0, '[{"product_id":1,"quantity":0}]')$$,
    'TM422', 'quantity 0');
select test.expect_error($$select public.trimax_agent_create_booking('55555555-5555-5555-5555-555555555557', 'X', '0920 000 0003', 'Somewhere', 14.7, 121.0, '[]')$$,
    'TM422', 'no items');
select test.expect_error($$select public.trimax_agent_create_booking('55555555-5555-5555-5555-555555555558', 'X', '0920 000 0003', 'Somewhere', 14.7, 121.0, '[{"product_id":"1; drop table users","quantity":1}]')$$,
    'TM422', 'malformed item');
select test.expect_error($$select public.trimax_agent_create_booking('55555555-5555-5555-5555-555555555559', 'X', '0920 000 0003', 'Somewhere', 0, 0, '[{"product_id":1,"quantity":1}]')$$,
    'TM422', 'location 0,0 refused');
select test.expect_error($$select public.trimax_agent_create_booking('55555555-5555-5555-5555-55555555555a', 'X', '0920 000 0003', 'Somewhere', 'NaN', 121, '[{"product_id":1,"quantity":1}]')$$,
    'TM422', 'NaN latitude refused');
select test.expect_error($$select public.trimax_agent_create_booking('55555555-5555-5555-5555-55555555555b', '  ', '0920 000 0003', 'Somewhere', 14.7, 121.0, '[{"product_id":1,"quantity":1}]')$$,
    'TM422', 'blank customer name');
select test.expect_error($$select public.trimax_agent_create_booking('55555555-5555-5555-5555-55555555555c', 'X', 'call me', 'Somewhere', 14.7, 121.0, '[{"product_id":1,"quantity":1}]')$$,
    'TM422', 'contact without digits');
select test.expect_error($$select public.trimax_agent_create_booking(null, 'X', '0920 000 0003', 'Somewhere', 14.7, 121.0, '[{"product_id":1,"quantity":1}]')$$,
    'TM422', 'missing request id');
reset role;
select test.ok((select stock from products where product_id = 1) = 50 - 2 - 2 - 3 and (select stock from products where product_id = 2) = 2,
    'stock deducted exactly once per booking, and failed attempts deducted nothing');
select test.ok(not exists (select 1 from clients where contact_num in ('0920 000 0001', '0920 000 0002', '0920 000 0003')),
    'failed attempts left no customer behind');
select test.ok((select count(*) from agents a join clients c using (client_id) where a.username = 'agent.ana' and c.contact_num = '0918 777 8888') = 1,
    'the new customer is assigned to the agent who created it');
select test.ok((select unit_price from booking_items where booking_id = :b1 and product_id = 1) = 180.00, 'unit price copied from products, not from the phone');

set role authenticated;
select test.jwt(:'ben');
select test.expect_error($$select public.trimax_agent_create_booking('11111111-1111-1111-1111-111111111111', 'X', '0920 000 0009', 'Somewhere', 14.7, 121.0, '[{"product_id":1,"quantity":1}]')$$,
    'TM409', 'another agent cannot reuse a request id');
select test.ok(jsonb_array_length(public.trimax_agent_list_bookings()) = 0, 'agent.ben sees none of agent.ana''s bookings');
select test.expect_error(format('select public.trimax_agent_get_booking(%s)', :b1), 'TM404', 'agent.ben cannot open agent.ana''s booking');
select test.expect_error('select public.trimax_driver_list_deliveries()', 'TM403', 'agent cannot use driver functions');
select test.jwt(:'ana');
select test.ok(jsonb_array_length(public.trimax_agent_list_bookings()) = 2, 'agent.ana sees her 2 bookings');
reset role;

-- ================================================================== 5. Desktop sees and assigns (as the desktop does it)
set role app_owner;
-- BookingsPanel "Unassigned" filter
select test.ok((select count(*) from bookings b left join clients c on c.client_id = b.client_id
                left join users u on u.username = b.driver_username
                where b.driver_username is null and b.booking_id = :b1) = 1, 'desktop bookings list shows the mobile booking once, as Unassigned');
-- BookingService.loadDetail
select test.ok((select c.client_name from bookings b left join clients c on c.client_id = b.client_id where b.booking_id = :b1) = 'New Customer Cuatro',
    'desktop detail query loads the customer');
-- BookingService.assignDriver
begin;
select v.status from vehicles v join users u on u.username = v.username join roles r on r.role_id = u.role_id
 where v.vehicle_id = 'TRK-001' and v.username = 'driver.dan' and u.account_status = 'Active' and r.role_name = 'Driver' for update of v;
update bookings set driver_username = 'driver.dan', vehicle_id = 'TRK-001'
 where booking_id = :b1 and status = 'Pending' and driver_username is not distinct from null;
insert into audit_log (actor_username, action, target_type, target_id, result, details)
values ('marc.admin', 'booking.assign_driver', 'booking', :'b1', 'success', 'unassigned -> driver.dan (vehicle TRK-001)');
commit;
select test.ok((select delivery_status = 'ASSIGNED' and assigned_at is not null from bookings where booking_id = :b1),
    'unchanged desktop assignment SQL sets delivery_status ASSIGNED via trigger');
select test.ok(exists (select 1 from trimax_private.delivery_events where booking_id = :b1 and event_type = 'assigned'), 'assignment is in the delivery history');
-- assign b2 to driver.dee
update bookings set driver_username = 'driver.dee', vehicle_id = 'TRK-002' where booking_id = :b2 and status = 'Pending' and driver_username is null;
reset role;

-- ================================================================== 6. Drivers see only their own
set role authenticated;
select test.jwt(:'dan');
select public.trimax_driver_list_deliveries('active') as dan_active \gset
select test.ok(exists (select 1 from jsonb_array_elements(:'dan_active'::jsonb) e where (e ->> 'booking_id')::int = :b1), 'driver.dan sees the booking assigned to him');
select test.ok(not exists (select 1 from jsonb_array_elements(:'dan_active'::jsonb) e where (e ->> 'booking_id')::int = :b2), 'driver.dan does not see driver.dee''s booking');
select test.ok(exists (select 1 from jsonb_array_elements(:'dan_active'::jsonb) e where (e ->> 'booking_id')::int = 1 and e ->> 'delivery_status' = 'ASSIGNED'),
    'a booking created on the desktop before the migration shows up for its driver');
select test.ok((select e -> 'delivery' ->> 'address' from jsonb_array_elements(:'dan_active'::jsonb) e where (e ->> 'booking_id')::int = 1) = 'Graceville',
    'desktop booking falls back to the customer address');
select test.expect_error('select public.trimax_agent_list_bookings()', 'TM403', 'driver cannot use agent functions');
select test.jwt(:'dee');
select test.expect_error(format('select public.trimax_driver_get_delivery(%s)', :b1), 'TM404', 'driver.dee cannot open driver.dan''s delivery');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :b1), 'TM404', 'driver.dee cannot update driver.dan''s delivery');
select test.expect_error(format($$update public.bookings set delivery_status = 'DELIVERED' where booking_id = %s$$, :b2), '42501', 'driver cannot write the bookings table directly');

-- ================================================================== 7. Driver lifecycle
select test.jwt(:'dan');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :b1), 'TM409', 'cannot start before the administrator confirms');
reset role;
set role app_owner;
update bookings set status = 'Confirmed' where booking_id = :b1 and status = 'Pending';   -- BookingService.changeStatus
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.ok((public.trimax_driver_update_delivery_status(:b1, 'ASSIGNED', 'STARTED', 14.78, 121.04) ->> 'status') = 'In Progress', 'STARTED moves Confirmed to In Progress');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :b1), 'TM409', 'stale expected status is rejected');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'DELIVERED', null, null, null, 'Juan')$$, :b1), 'TM422', 'cannot skip to DELIVERED');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'BOGUS')$$, :b1), 'TM422', 'unknown status refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'PICKED_UP', 14.7, null)$$, :b1), 'TM422', 'half a location refused');
select test.ok((public.trimax_driver_update_delivery_status(:b1, 'started', 'PICKED_UP') ->> 'delivery_status') = 'PICKED_UP', 'PICKED_UP (input is case-insensitive)');
select test.ok((public.trimax_driver_update_delivery_status(:b1, 'PICKED_UP', 'ON_THE_WAY') ->> 'delivery_status') = 'ON_THE_WAY', 'ON_THE_WAY');
select test.ok((public.trimax_driver_update_delivery_status(:b1, 'ON_THE_WAY', 'ARRIVED', 14.81, 121.05) ->> 'delivery_status') = 'ARRIVED', 'ARRIVED');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ARRIVED', 'DELIVERED')$$, :b1), 'TM422', 'DELIVERED needs the recipient name');
select public.trimax_driver_update_delivery_status(:b1, 'ARRIVED', 'DELIVERED', 14.81, 121.05, null, 'Juan Dela Cruz') as done \gset
select test.ok((:'done'::jsonb ->> 'status') = 'Completed' and (:'done'::jsonb ->> 'delivery_status') = 'DELIVERED', 'DELIVERED completes the booking');
select test.ok(jsonb_array_length(:'done'::jsonb -> 'events') >= 6, 'delivery history returned to the driver');
select test.ok(not exists (select 1 from jsonb_array_elements(:'done'::jsonb -> 'events') e where e ? 'latitude'), 'history sent to phones contains no coordinates');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'DELIVERED', 'FAILED', null, null, 'x')$$, :b1), 'TM409', 'no changes after Completed');
select test.ok(not exists (select 1 from jsonb_array_elements(public.trimax_driver_list_deliveries('active')) e where (e ->> 'booking_id')::int = :b1), 'completed delivery leaves the active list');
select test.ok(exists (select 1 from jsonb_array_elements(public.trimax_driver_list_deliveries('history')) e where (e ->> 'booking_id')::int = :b1), 'completed delivery is in the history list');
select test.expect_error($$select public.trimax_driver_list_deliveries('everything')$$, 'TM422', 'unknown list scope');
select test.jwt(:'ana');
select test.ok((public.trimax_agent_get_booking(:b1) ->> 'status') = 'Completed', 'agent sees the driver''s update');
reset role;

set role app_owner;
select test.ok((select status from bookings where booking_id = :b1) = 'Completed', 'desktop reads Completed from bookings.status');
select test.ok((select count(*) from audit_log where target_id = :'b1' and action = 'booking.delivery_status') = 5, 'each driver step is in audit_log (desktop History)');
select test.ok((select count(*) from audit_log where target_id = :'b1' and action = 'booking.status_change') = 2, 'both booking status moves by the driver are in audit_log');
select test.ok((select count(*) from trimax_private.delivery_events where booking_id = :b1 and latitude is not null) = 3, 'driver positions are kept for the administrator');

-- ================================================================== 8. Reassignment rules and failures
update bookings set status = 'Confirmed' where booking_id = :b2 and status = 'Pending';
update bookings set driver_username = 'driver.dan', vehicle_id = 'TRK-001' where booking_id = :b2 and status = 'Confirmed' and driver_username = 'driver.dee';
select test.ok((select driver_username = 'driver.dan' and delivery_status = 'ASSIGNED' from bookings where booking_id = :b2), 'desktop can reassign before the driver starts');
reset role;
set role authenticated;
select test.jwt(:'dee');
select test.ok(not exists (select 1 from jsonb_array_elements(public.trimax_driver_list_deliveries('active')) e where (e ->> 'booking_id')::int = :b2), 'previous driver no longer sees a reassigned delivery');
select test.jwt(:'dan');
select test.ok((public.trimax_driver_update_delivery_status(:b2, 'ASSIGNED', 'STARTED') ->> 'status') = 'In Progress', 'new driver starts it');
reset role;
set role app_owner;
select test.expect_error(format($$update bookings set driver_username = 'driver.dee', vehicle_id = 'TRK-002' where booking_id = %s$$, :b2), 'TM409', 'database refuses to reassign a delivery that is under way');
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.ok((public.trimax_driver_update_delivery_status(:b2, 'STARTED', 'ON_THE_WAY') ->> 'delivery_status') = 'ON_THE_WAY', 'hub dispatch can skip PICKED_UP');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ON_THE_WAY', 'FAILED')$$, :b2), 'TM422', 'FAILED needs a reason');
select test.ok((public.trimax_driver_update_delivery_status(:b2, 'ON_THE_WAY', 'FAILED', null, null, 'Store closed') ->> 'status') = 'In Progress', 'FAILED leaves the booking In Progress for the admin');
select test.ok(exists (select 1 from jsonb_array_elements(public.trimax_driver_list_deliveries('history')) e where (e ->> 'booking_id')::int = :b2), 'failed delivery is in history');
reset role;
set role app_owner;
-- BookingService.changeStatus In Progress -> Cancelled returns stock in the same transaction
begin;
update bookings set status = 'Cancelled' where booking_id = :b2 and status = 'In Progress';
update products p set stock = p.stock + s.qty from (select product_id, sum(quantity) as qty from booking_items where booking_id = :b2 group by product_id) s where p.product_id = s.product_id;
commit;
select test.ok((select stock from products where product_id = 1) = 50 - 2 - 2, 'admin cancellation returned the failed delivery''s stock');

-- Desktop creates a booking with a driver (BookingService.createBooking): the driver sees it.
insert into bookings (client_id, driver_username, vehicle_id) values (2, 'driver.dee', 'TRK-002') returning booking_id as b3 \gset
select test.ok((select delivery_status = 'ASSIGNED' and created_via = 'desktop' from bookings where booking_id = :b3), 'desktop-created booking is ASSIGNED for its driver');
reset role;
set role authenticated;
select test.jwt(:'dee');
select test.ok(exists (select 1 from jsonb_array_elements(public.trimax_driver_list_deliveries('active')) e where (e ->> 'booking_id')::int = :b3), 'driver.dee sees the desktop booking');
reset role;

-- ================================================================== 9. Disabling and unlinking
set role app_owner;
update users set account_status = 'Disabled' where username = 'driver.dee' and account_status = 'Active';
reset role;
set role authenticated;
select test.jwt(:'dee');
select test.expect_error('select public.trimax_driver_list_deliveries()', 'TM401', 'disabling an account on the desktop revokes mobile access immediately');
reset role;
set role app_owner;
update users set account_status = 'Active' where username = 'driver.dee';
select test.ok(trimax_private.unlink_mobile_account('agent.ben', 'marc.admin'), 'unlink agent.ben');
reset role;
set role authenticated;
select test.jwt(:'ben');
select test.expect_error('select public.trimax_my_account()', 'TM401', 'unlinked account is refused');
reset role;

-- ================================================================== 10. Desktop reports still count each booking once
set role app_owner;
select test.ok((select sum(n) from (select count(*) as n from bookings group by status) s) = (select count(*) from bookings),
    'DashboardPanel status counts include every booking exactly once');
select test.ok((select count(*) from bookings b join clients c on c.client_id = b.client_id) = (select count(*) from bookings),
    'DashboardPanel recent-bookings inner join keeps mobile bookings (they always have a customer)');
reset role;

\echo 'ALL SQL TESTS PASSED'
