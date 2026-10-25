-- TEST ONLY. Run by sql/tests/run_tests.sh after sql/20261025_01_driver_started_delivered.sql, on the
-- same disposable database (tests 10 and 11 already ran: driver.dan / driver.dee are linked, dan is off duty).
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana  '00000000-0000-0000-0000-00000000a0a0'
\set ben  '00000000-0000-0000-0000-00000000b0b0'
\set dan  '00000000-0000-0000-0000-00000000d0d0'
\set dee  '00000000-0000-0000-0000-00000000e0e0'

-- Bookings: s1 Pending and s2 / s3 Confirmed for driver.dan, d1 Confirmed for driver.dee,
-- plus rows that older app builds left in intermediate states.
set role app_owner;
insert into bookings (client_id, driver_username, vehicle_id) values (2, 'driver.dan', 'TRK-001') returning booking_id as s1 \gset
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dan', 'TRK-001', 'Confirmed') returning booking_id as s2 \gset
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dan', 'TRK-001', 'Confirmed') returning booking_id as s3 \gset
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dee', 'TRK-002', 'Confirmed') returning booking_id as d1 \gset
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dan', 'TRK-001', 'In Progress') returning booking_id as l1 \gset
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dan', 'TRK-001', 'In Progress') returning booking_id as l2 \gset
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dan', 'TRK-001', 'In Progress') returning booking_id as l3 \gset
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dan', 'TRK-001', 'Confirmed') returning booking_id as x1 \gset
update bookings set delivery_status = 'ON_THE_WAY' where booking_id = :l1;
update bookings set delivery_status = 'ARRIVED' where booking_id = :l2;
update bookings set delivery_status = 'PICKED_UP' where booking_id = :l3;
select test.ok((select count(*) from bookings where booking_id in (:s1, :s2, :s3, :d1) and delivery_status = 'ASSIGNED') = 4,
    'setup: assigning a driver sets ASSIGNED');
reset role;

-- ================================================================== Who may update
set role anon;
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :s2), '42501', 'anonymous callers cannot update a delivery');
reset role;
set role authenticated;
select test.jwt(:'ben');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :s2), 'TM401', 'an unlinked sign-in cannot update a delivery');
select test.jwt(:'ana');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :s2), 'TM403', 'an agent cannot update a delivery');
select test.jwt(:'dee');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :s2), 'TM404', 'another driver cannot start dan''s delivery');
select test.jwt(:'dan');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :d1), 'TM404', 'dan cannot start dee''s delivery');
select test.expect_error($$select public.trimax_driver_update_delivery_status(999999, 'ASSIGNED', 'STARTED')$$, 'TM404', 'an unknown booking is refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :s1), 'TM409', 'a Pending (not yet approved) booking cannot be started');
reset role;

-- ================================================================== Duty does not touch deliveries
set role app_owner;
select (select count(*) from trimax_private.delivery_events where booking_id in (:s1, :s2, :s3)) as ev_before,
       (select string_agg(booking_id || ':' || status || ':' || delivery_status || ':' || coalesce(delivery_status_at::text, '-'), ',' order by booking_id)
          from bookings where driver_username = 'driver.dan') as dan_before \gset
reset role;
set role authenticated;
select test.jwt(:'dan');
insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
values ('duty-photos', :'dan' || '/duty-start-13.jpg', :'dan', :'dan', '{"size": 32000}');
select test.ok((public.trimax_record_duty_event('START', :'dan' || '/duty-start-13.jpg', 14.7827, 121.0476) ->> 'on_duty')::boolean, 'duty: Start Duty recorded');
reset role;
set role app_owner;
select test.ok((select string_agg(booking_id || ':' || status || ':' || delivery_status || ':' || coalesce(delivery_status_at::text, '-'), ',' order by booking_id)
                  from bookings where driver_username = 'driver.dan') = :'dan_before',
    'duty: Start Duty changes no booking status, delivery status or timestamp');
select test.ok((select count(*) from trimax_private.delivery_events where booking_id in (:s1, :s2, :s3)) = :ev_before,
    'duty: Start Duty writes no delivery event');
reset role;

-- ================================================================== Invalid transitions
set role authenticated;
select test.jwt(:'dan');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'ON_THE_WAY')$$, :s2), 'TM422', 'ASSIGNED -> ON_THE_WAY refused (no intermediate steps)');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'PICKED_UP')$$, :s2), 'TM422', 'ASSIGNED -> PICKED_UP refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'DELIVERED', null, null, null, 'Maria')$$, :s2), 'TM422', 'ASSIGNED -> DELIVERED refused (must start first)');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'FAILED', null, null, 'x')$$, :s2), 'TM422', 'ASSIGNED -> FAILED refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'ASSIGNED')$$, :s2), 'TM422', 'ASSIGNED -> ASSIGNED refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'NONSENSE')$$, :s2), 'TM422', 'an unknown status is refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'DELIVERED', null, null, null, 'Maria')$$, :s2), 'TM409', 'a wrong expected status is refused (nothing written)');

-- ================================================================== ASSIGNED -> STARTED
select public.trimax_driver_update_delivery_status(:s2, 'ASSIGNED', 'STARTED', 14.7827, 121.0476) as s2_started \gset
select test.ok((:'s2_started'::jsonb ->> 'delivery_status') = 'STARTED' and (:'s2_started'::jsonb ->> 'status') = 'In Progress'
               and (:'s2_started'::jsonb ->> 'already_recorded') is null,
    'Start Delivery: ASSIGNED -> STARTED, booking becomes In Progress, the reply carries the saved status');
-- Duplicate (double tap / lost reply): same answer, nothing written twice.
select public.trimax_driver_update_delivery_status(:s2, 'ASSIGNED', 'STARTED', 14.7827, 121.0476) as s2_again \gset
select test.ok((:'s2_again'::jsonb ->> 'delivery_status') = 'STARTED' and (:'s2_again'::jsonb ->> 'status') = 'In Progress'
               and (:'s2_again'::jsonb ->> 'already_recorded')::boolean,
    'duplicate Start Delivery returns the saved booking instead of an error');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'STARTED')$$, :s2), 'TM422', 'STARTED -> STARTED with the current status as expected is refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'ON_THE_WAY')$$, :s2), 'TM422', 'STARTED -> ON_THE_WAY refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'ARRIVED')$$, :s2), 'TM422', 'STARTED -> ARRIVED refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'PICKED_UP')$$, :s2), 'TM422', 'STARTED -> PICKED_UP refused');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'ASSIGNED')$$, :s2), 'TM422', 'STARTED -> ASSIGNED refused (no going back)');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'DELIVERED')$$, :s2), 'TM422', 'Drop Off needs the recipient''s name');
select test.jwt(:'dee');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'DELIVERED', null, null, null, 'Maria')$$, :s2), 'TM404', 'another driver cannot complete dan''s delivery');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :s2), 'TM404', 'another driver gets no retry answer for dan''s delivery');
reset role;
set role app_owner;
select test.ok((select count(*) from trimax_private.delivery_events where booking_id = :s2 and event_type = 'delivery_status') = 1,
    'exactly one delivery event after start, duplicate and refused calls');
select test.ok((select count(*) from audit_log where target_id = :'s2' and action = 'booking.status_change' and details = 'Confirmed -> In Progress (driver started)') = 1,
    'the In Progress move is audited once as "driver started"');
reset role;

-- ================================================================== STARTED -> DELIVERED
set role authenticated;
select test.jwt(:'dan');
select public.trimax_driver_update_delivery_status(:s2, 'STARTED', 'DELIVERED', 14.80, 121.04, null, 'Maria Santos') as s2_done \gset
select test.ok((:'s2_done'::jsonb ->> 'delivery_status') = 'DELIVERED' and (:'s2_done'::jsonb ->> 'status') = 'Completed',
    'Drop Off: STARTED -> DELIVERED, booking becomes Completed');
select test.ok((public.trimax_driver_update_delivery_status(:s2, 'STARTED', 'DELIVERED', 14.80, 121.04, null, 'Maria Santos') ->> 'already_recorded')::boolean,
    'duplicate Drop Off returns the completed booking instead of an error');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'DELIVERED', 'FAILED', null, null, 'x')$$, :s2), 'TM409', 'a completed delivery cannot change any more');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :s2), 'TM409', 'an old Start retry after completion is refused, not replayed');
reset role;
set role app_owner;
select test.ok((select count(*) from trimax_private.delivery_events where booking_id = :s2 and event_type = 'delivery_status' and to_status = 'DELIVERED') = 1,
    'exactly one DELIVERED event');
select test.ok((select delivery_status = 'DELIVERED' and status = 'Completed' from bookings where booking_id = :s2), 'database row matches what the phone was told');
-- A retry answer is only given for this driver's own last step: a status set by someone else is a conflict.
update bookings set delivery_status = 'STARTED', status = 'In Progress' where booking_id = :x1;
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :x1), 'TM409', 'no retry answer when the driver did not make that step');
select test.ok((public.trimax_driver_update_delivery_status(:x1, 'STARTED', 'FAILED', null, null, 'Test cleanup') ->> 'delivery_status') = 'FAILED', 'that delivery can still be resolved normally');

-- ================================================================== Rows from older app builds
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ON_THE_WAY', 'ARRIVED')$$, :l1), 'TM422', 'older row: ON_THE_WAY -> ARRIVED no longer accepted');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'PICKED_UP', 'STARTED')$$, :l3), 'TM422', 'older row: cannot move back into STARTED');
select test.ok((public.trimax_driver_update_delivery_status(:l1, 'ON_THE_WAY', 'DELIVERED', null, null, null, 'Ana') ->> 'status') = 'Completed', 'older ON_THE_WAY row can be completed');
select test.ok((public.trimax_driver_update_delivery_status(:l2, 'ARRIVED', 'FAILED', null, null, 'Gate closed') ->> 'delivery_status') = 'FAILED', 'older ARRIVED row can fail');
select test.ok((public.trimax_driver_update_delivery_status(:l3, 'PICKED_UP', 'DELIVERED', null, null, null, 'Ben') ->> 'delivery_status') = 'DELIVERED', 'older PICKED_UP row can be completed');

-- ================================================================== Problem report, End Duty
select test.ok((public.trimax_driver_update_delivery_status(:s3, 'ASSIGNED', 'STARTED') ->> 'delivery_status') = 'STARTED', 'second delivery started');
insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
values ('duty-photos', :'dan' || '/duty-end-13.jpg', :'dan', :'dan', '{"size": 31000}');
select test.expect_error(format($$select public.trimax_record_duty_event('END', '%s/duty-end-13.jpg')$$, :'dan'), 'TM409', 'End Duty still refused while a delivery is STARTED (existing rule)');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'STARTED', 'FAILED')$$, :s3), 'TM422', 'a failure needs a reason');
select public.trimax_driver_update_delivery_status(:s3, 'STARTED', 'FAILED', null, null, 'Customer not home') as s3_failed \gset
select test.ok((:'s3_failed'::jsonb ->> 'delivery_status') = 'FAILED' and (:'s3_failed'::jsonb ->> 'status') = 'In Progress',
    'STARTED -> FAILED keeps the booking In Progress for the administrator');
reset role;
set role app_owner;
select (select string_agg(booking_id || ':' || status || ':' || delivery_status || ':' || delivery_status_at::text, ',' order by booking_id)
          from bookings where driver_username = 'driver.dan') as dan_before_end \gset
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.ok((public.trimax_record_duty_event('END', :'dan' || '/duty-end-13.jpg') ->> 'on_duty') = 'false', 'duty: End Duty recorded');
reset role;
set role app_owner;
select test.ok((select string_agg(booking_id || ':' || status || ':' || delivery_status || ':' || delivery_status_at::text, ',' order by booking_id)
                  from bookings where driver_username = 'driver.dan') = :'dan_before_end',
    'duty: End Duty changes no booking status, delivery status or timestamp');
reset role;

-- ================================================================== Grants unchanged
select test.ok(not has_function_privilege('anon', 'public.trimax_driver_update_delivery_status(int, text, text, double precision, double precision, text, text)', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_driver_update_delivery_status(int, text, text, double precision, double precision, text, text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.delivery_transition_allowed(text, text)', 'execute'),
    'grants: only signed-in users may call the status function; the rule function stays private');

\echo 'ALL DRIVER STATUS TESTS PASSED'
