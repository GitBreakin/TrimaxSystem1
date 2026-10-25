-- TEST ONLY. Run by sql/tests/run_tests.sh on a disposable database, after 20261025_01, _02 and _03.
-- Checks the live-baseline functions (GPS, duty) and the new SOS / problem report function.
-- Every check prints "PASS ..." or stops with "FAIL ...". Phone calls run as "authenticated" with a JWT,
-- like PostgREST; desktop statements run as app_owner.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana  '00000000-0000-0000-0000-00000000a0a0'
\set dan  '00000000-0000-0000-0000-00000000d0d0'
\set dee  '00000000-0000-0000-0000-00000000e0e0'

-- ------------------------------------------------------------------ fixtures (superuser)
insert into trimax_private.mobile_accounts (auth_user_id, username) values
    (:'ana', 'agent.ana'), (:'dan', 'driver.dan'), (:'dee', 'driver.dee')
on conflict do nothing;
delete from public.admin_notifications;
delete from trimax_private.duty_events;
delete from public.user_locations;

-- A booking created by agent.ana and assigned to driver.dee; another assigned to driver.dan only.
insert into public.bookings (client_id, created_via, created_by_username, client_request_id, delivery_address,
                             delivery_latitude, delivery_longitude, driver_username, vehicle_id)
values (1, 'mobile', 'agent.ana', 'aaaaaaaa-0000-0000-0000-000000000001', 'Tungko', 14.79, 121.06, 'driver.dee', 'TRK-002')
returning booking_id as ana_booking \gset
insert into public.bookings (client_id, driver_username, vehicle_id) values (2, 'driver.dan', 'TRK-001')
returning booking_id as dan_booking \gset

-- ------------------------------------------------------------------ schema checks (superuser)
select test.ok((select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                 where (n.nspname, p.proname) in (('trimax_private','hub_location'), ('trimax_private','is_service_request'),
                                                  ('public','refresh_booking_total'), ('public','audit_log_block_changes'))
                   and exists (select 1 from unnest(p.proconfig) c where c like 'search_path=%')) = 4,
               'search_path is pinned on the 4 functions the advisor flagged');
select test.ok(not has_function_privilege('authenticated', 'public.refresh_booking_total()', 'execute'),
               'API role can no longer execute the booking-total trigger function');
select test.ok(not has_function_privilege('anon', 'public.trimax_report_issue(text,text,integer,double precision,double precision,uuid)', 'execute'),
               'anon cannot call trimax_report_issue');
select test.ok(has_function_privilege('authenticated', 'public.trimax_report_issue(text,text,integer,double precision,double precision,uuid)', 'execute'),
               'authenticated can call trimax_report_issue');
select test.ok(not has_table_privilege('authenticated', 'public.admin_notifications', 'select'),
               'phones cannot read admin_notifications directly');

-- ------------------------------------------------------------------ phone: driver.dan
set role authenticated;
select test.jwt(:'dan');

-- live GPS (baseline function)
select test.ok((public.trimax_update_location(14.78, 121.05, 8, 3, 90, true) ->> 'stored')::boolean, 'driver location stored');
select test.ok((public.trimax_update_location(14.78, 121.05, 8, 3, 90, true) ->> 'throttled')::boolean, 'second fix within 2 s is throttled');
select test.expect_error($$select public.trimax_update_location(14.78, 121.05, 8, 3, 90, false)$$, 'TM422', 'location refused when off duty');
select test.expect_error($$select public.trimax_update_location(0, 0)$$, 'TM422', 'location 0,0 refused');

-- duty with photo (baseline functions; no storage schema here, so the upload check passes)
select test.ok(not (public.trimax_my_duty_status() ->> 'on_duty')::boolean, 'driver starts off duty');
select test.expect_error(format($$select public.trimax_record_duty_event('START', %L)$$, 'someone-else/start.jpg'), 'TM422', 'duty photo outside own folder refused');
select test.ok((public.trimax_record_duty_event('START', :'dan' || '/start_1.jpg', 14.78, 121.05, 'dddddddd-0000-0000-0000-000000000001') ->> 'on_duty')::boolean, 'duty START recorded');
select test.ok((public.trimax_record_duty_event('START', :'dan' || '/start_1.jpg', 14.78, 121.05, 'dddddddd-0000-0000-0000-000000000001') ->> 'already_recorded')::boolean, 'duty START retry returns the saved event');
select test.expect_error(format($$select public.trimax_record_duty_event('START', %L)$$, :'dan' || '/start_2.jpg'), 'TM409', 'second START refused');
select test.ok(not (public.trimax_record_duty_event('END', :'dan' || '/end_1.jpg') ->> 'on_duty')::boolean, 'duty END recorded');

-- SOS / problem reports
select (public.trimax_report_issue('SOS', 'Flat tyre on the highway', null, 14.7801, 121.0502,
        'eeeeeeee-0000-0000-0000-000000000001') ->> 'notification_id')::bigint as sos_id \gset
select test.ok(:sos_id > 0, 'driver SOS saved');
select test.ok((public.trimax_report_issue('SOS', 'Flat tyre on the highway', null, 14.7801, 121.0502,
        'eeeeeeee-0000-0000-0000-000000000001') ->> 'already_recorded')::boolean, 'SOS retry returns the first alert');
select test.ok((public.trimax_report_issue('problem', 'Gate locked, customer not answering', :dan_booking) ->> 'notification_id') is not null,
               'driver problem on own booking saved');
select test.expect_error(format($$select public.trimax_report_issue('PROBLEM', 'Not mine', %s)$$, :ana_booking), 'TM404', 'driver cannot report on a booking not assigned to him');
select test.expect_error($$select public.trimax_report_issue('SOS', 'ok')$$, 'TM422', 'message under 3 characters refused');
select test.expect_error($$select public.trimax_report_issue('HELLO', 'Something happened')$$, 'TM422', 'unknown report type refused');
select test.expect_error($$select public.trimax_report_issue('SOS', 'Half a location', null, 14.7, null)$$, 'TM422', 'latitude without longitude refused');
select test.expect_error($$select * from public.admin_notifications$$, '42501', 'driver cannot read admin notifications');

-- ------------------------------------------------------------------ phone: agent.ana
select test.jwt(:'ana');
select test.ok((public.trimax_report_issue('PROBLEM', 'Customer changed the delivery address', :ana_booking) ->> 'notification_id') is not null,
               'agent problem on own booking saved');
select test.expect_error(format($$select public.trimax_report_issue('PROBLEM', 'Not mine', %s)$$, :dan_booking), 'TM404', 'agent cannot report on a booking she did not create');
select test.expect_error($$select public.trimax_report_issue('SOS', 'Reusing an id', null, null, null, 'eeeeeeee-0000-0000-0000-000000000001')$$, 'TM409', 'another person cannot reuse a request id');

-- flood guard: ana has 1 report; 9 more are allowed, the 11th is refused
select count(*) from (select public.trimax_report_issue('PROBLEM', 'Report number ' || g) from generate_series(1, 9) g) x \gset
select test.expect_error($$select public.trimax_report_issue('SOS', 'One too many')$$, 'TM409', 'eleventh report within 10 minutes refused');
reset role;

-- ------------------------------------------------------------------ desktop (owner): what the admin sees
set role app_owner;
select test.ok((select count(*) from admin_notifications where read_at is null) = 12, 'desktop counts 12 unread alerts (2 from dan, 10 from ana)');
select test.ok((select category from admin_notifications where notification_id = :sos_id) = 'SOS'
               and (select related_type from admin_notifications where notification_id = :sos_id) = 'user',
               'SOS shows as category SOS, related to the driver');
select test.ok((select title from admin_notifications where related_id = :'dan_booking' and category = 'Delivery problem')
               = 'Problem reported by Dan Driver (driver) on booking #' || :dan_booking,
               'problem title names the driver and the booking');
select test.ok((select body from admin_notifications where notification_id = :sos_id) like E'Flat tyre on the highway\nLocation: 14.780100, 121.050200',
               'SOS body carries the location');
-- The desktop's own statements (NotificationService) still work unchanged.
select count(*) from (select notification_id, created_at, kind, category, title, body, related_type, related_id, read_at, read_by
                        from admin_notifications order by created_at desc, notification_id desc limit 201) x \gset
update admin_notifications set read_at = now(), read_by = 'marc.admin' where read_at is null and notification_id = :sos_id;
select test.ok((select read_by from admin_notifications where notification_id = :sos_id) = 'marc.admin', 'desktop marks an alert read');
select test.ok((select count(*) from audit_log where action in ('report.sos', 'report.problem')) >= 3, 'reports are written to the audit log');
reset role;

\echo 'ALL STAGE B SQL TESTS PASSED'
