-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261029_02_booking_order_via.sql.
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC); phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana '00000000-0000-0000-0000-00000000a0a0'
\set ben '00000000-0000-0000-0000-00000000b0b0'
\set dan '00000000-0000-0000-0000-00000000d0d0'
\set v2sig 'public.trimax_agent_create_booking_v2(uuid,text,text,text,double precision,double precision,jsonb,text,text,text,text,double precision,double precision,timestamptz)'

-- ================================================================== 1. Shape and access
select test.ok((select count(*) = 5 from information_schema.columns where table_name = 'bookings'
                 and column_name in ('order_via', 'visit_photo_path', 'visit_latitude', 'visit_longitude', 'visit_taken_at'))
               and exists (select 1 from pg_constraint where conname = 'bookings_order_via_chk')
               and to_regclass('public.bookings_visit_photo_key') is not null
               and (select count(*) = 0 from bookings where order_via is not null),
               'bookings have the order-via columns, the check and the one-photo-one-booking index; no row changed');
select test.ok(has_function_privilege('authenticated', :'v2sig', 'execute')
               and not has_function_privilege('anon', :'v2sig', 'execute')
               and not has_function_privilege('service_role', :'v2sig', 'execute')
               and (select count(*) = 1 from pg_proc where proname = 'trimax_agent_create_booking_v2')
               and (select count(*) = 1 from pg_proc where proname = 'trimax_agent_create_booking'),
               'the new booking function is for signed-in phones only; the old one is still there, one of each');

-- ================================================================== 2. Fixtures (Desktop)
set role app_owner;
select trimax_private.save_product('marc.admin', null, 'OV Juice Case', 'OV Supplier', 100, 'AMOUNT', 20, 0, null, 50, false) as p \gset
select c.client_id as anas, c.client_name as anas_name, c.contact_num as anas_contact, c.address as anas_addr from clients c
  join agents a on a.client_id = c.client_id and a.username = 'agent.ana' order by c.client_id limit 1 \gset
select count(*) as bookings0 from bookings \gset
reset role;
\set items '[{"product_id": ' :p ', "quantity": 2}]'

-- ================================================================== 3. Phone: the choice is required
set role authenticated;
select test.jwt(:'ana');
select test.expect_detail(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, null)$$,
                                 :'anas_name', :'anas_contact', :'anas_addr', :'items'),
                          'TM422', 'order_via_required', 'a booking without Visit or Text / Call is refused');
select test.expect_detail(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'EMAIL')$$,
                                 :'anas_name', :'anas_contact', :'anas_addr', :'items'),
                          'TM422', 'order_via_required', 'an unknown choice is refused');
select test.expect_detail(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'VISIT')$$,
                                 :'anas_name', :'anas_contact', :'anas_addr', :'items'),
                          'TM422', 'visit_photo_required', 'a visit without a photo is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'VISIT', null, null, %L)$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :'items', :'ben' || '/visit_1.jpg'),
                         'TM422', 'a photo from another phone''s folder is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'VISIT', null, null, %L)$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :'items', :'ana' || '/customer_9.jpg'),
                         'TM422', 'a photo not named visit_... is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'VISIT', null, null, %L, 14.8, null)$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :'items', :'ana' || '/visit_1.jpg'),
                         'TM422', 'half a location is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'VISIT', null, null, %L, null, null, now() + interval '1 hour')$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :'items', :'ana' || '/visit_1.jpg'),
                         'TM422', 'a photo time in the future is refused');
select test.expect_error(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'TEXT_CALL', null, null, %L)$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :'items', :'ana' || '/visit_1.jpg'),
                         'TM422', 'Text / Call with a photo is refused');
select test.expect_detail(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'VISIT', null, null, %L, 14.81, 121.01)$$,
                                 :'anas_name', :'anas_contact', :'anas_addr', :'items', :'ana' || '/visit_1.jpg'),
                          'TM422', 'visit_photo_missing', 'a visit photo that was not uploaded is refused');
reset role;
set role app_owner;
select test.ok((select count(*) = :bookings0 from bookings)
               and (select stock = 50 from products where product_id = :p),
               'nothing refused saved a booking or took stock');
reset role;

-- ================================================================== 4. Phone: Text / Call
set role authenticated;
select test.jwt(:'ana');
select public.trimax_agent_create_booking_v2(gen_random_uuid(), :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
       :'items'::jsonb, 'text_call', 'Tomorrow 9 AM', 'Call first') as tc \gset
select test.ok(:'tc'::jsonb ->> 'order_via' = 'TEXT_CALL' and not (:'tc'::jsonb ->> 'already_existed')::boolean
               and (:'tc'::jsonb ->> 'total_amount')::numeric = 240,
               'a Text / Call booking is saved (priced by the server, 2 x 120)');
select (:'tc'::jsonb ->> 'booking_id')::int as tcid \gset
reset role;

-- ================================================================== 5. Phone: Visit with the photo
set role authenticated;
select test.jwt(:'ana');
insert into storage.objects (bucket_id, name, metadata) values ('customer-photos', :'ana' || '/visit_1.jpg', '{"size": 45678}');
select gen_random_uuid() as rq \gset
select public.trimax_agent_create_booking_v2(:'rq', :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
       :'items'::jsonb, 'VISIT', null, null, :'ana' || '/visit_1.jpg', 14.81, 121.01, now() - interval '2 minutes') as vb \gset
select test.ok(:'vb'::jsonb ->> 'order_via' = 'VISIT' and not (:'vb'::jsonb ->> 'already_existed')::boolean,
               'a Visit booking with its uploaded photo is saved');
select (:'vb'::jsonb ->> 'booking_id')::int as vid \gset
select public.trimax_agent_create_booking_v2(:'rq', :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
       :'items'::jsonb, 'VISIT', null, null, :'ana' || '/visit_1.jpg', 14.81, 121.01, now() - interval '2 minutes') as again \gset
select test.ok((:'again'::jsonb ->> 'already_existed')::boolean and (:'again'::jsonb ->> 'booking_id')::int = :vid
               and :'again'::jsonb ->> 'order_via' = 'VISIT',
               'a retry with the same request id returns the same booking, not a second one');
select test.expect_error(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'VISIT', null, null, %L)$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :'items', :'ana' || '/visit_1.jpg'),
                         'TM409', 'the same photo cannot prove a second booking');
-- the older app still books, without the choice
select public.trimax_agent_create_booking(gen_random_uuid(), :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0, :'items'::jsonb) as old \gset
select (:'old'::jsonb ->> 'booking_id')::int as oldid \gset
reset role;

set role authenticated;
select test.jwt(:'dan');
select test.expect_error(format($$select public.trimax_agent_create_booking_v2(gen_random_uuid(), %L, %L, %L, 14.8, 121.0, %L::jsonb, 'TEXT_CALL')$$,
                                :'anas_name', :'anas_contact', :'anas_addr', :'items'),
                         'TM403', 'a driver cannot create a booking');
reset role;

-- ================================================================== 6. Desktop: what the admin sees
set role app_owner;
select test.ok((select order_via = 'VISIT' and visit_photo_path = :'ana' || '/visit_1.jpg'
                       and visit_latitude = 14.81 and visit_longitude = 121.01
                       and visit_taken_at between now() - interval '3 minutes' and now() - interval '1 minute'
                  from bookings where booking_id = :vid)
               and (select order_via = 'TEXT_CALL' and visit_photo_path is null and visit_taken_at is null
                      from bookings where booking_id = :tcid)
               and (select order_via is null and visit_photo_path is null from bookings where booking_id = :oldid)
               and (select count(*) = :bookings0 + 3 from bookings)
               and (select stock = 44 from products where product_id = :p),
               'the admin reads Visit (photo, place, time), Text / Call, and "not recorded" for the older app; 3 bookings, stock 50 - 6');
select test.ok((select count(*) = 1 from audit_log where action = 'booking.order_via' and target_id = :'vid'
                 and actor_username = 'agent.ana' and details = 'Visit, photo ' || :'ana' || '/visit_1.jpg')
               and (select count(*) = 1 from audit_log where action = 'booking.order_via' and target_id = :'tcid' and details = 'Text or call')
               and (select count(*) = 1 from admin_notifications where category = 'New booking' and related_id = :'vid'),
               'audit says Visit (with the photo) or Text or call; the admin still gets the New booking notification');
select test.ok(trimax_private.issue_media_ticket('marc.admin', 'customer-photos', :'ana' || '/visit_1.jpg') is not null,
               'the admin can open the visit photo (one-time ticket, customer-photos)');
select test.expect_error(format($$update bookings set order_via = 'VISIT' where booking_id = %s$$, :oldid), '23514',
                         'a Visit without a photo cannot be stored, even by the owner');
select test.expect_error(format($$update bookings set visit_photo_path = %L where booking_id = %s$$, :'ana' || '/visit_1.jpg', :tcid), '23514',
                         'a Text / Call booking cannot carry a photo');
-- the Desktop's own statements still work
insert into bookings (client_id, status) values (:anas, 'Pending') returning booking_id as deskid \gset
select test.ok((select order_via is null from bookings where booking_id = :deskid), 'a Desktop booking is saved as before (not recorded)');
delete from bookings where booking_id = :deskid;
reset role;

\echo ALL BOOKING ORDER VIA SQL TESTS PASSED
