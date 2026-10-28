-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261028_01_location_history_and_booking_notifications.sql.
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC); phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana '00000000-0000-0000-0000-00000000a0a0'
\set dan '00000000-0000-0000-0000-00000000d0d0'

-- ================================================================== 1. Who can reach what
select test.ok(not has_table_privilege('authenticated', 'trimax_private.location_history', 'select')
               and not has_table_privilege('anon', 'trimax_private.location_history', 'select')
               and not has_table_privilege('service_role', 'trimax_private.location_history', 'select')
               and not has_table_privilege('authenticated', 'trimax_private.location_history', 'insert')
               and not has_table_privilege('authenticated', 'trimax_private.location_history_settings', 'select'),
               'phones and the service role have no privilege on the location history or its settings');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.location_history_for(text,text,timestamptz,timestamptz,integer,integer)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.location_history_for(text,text,timestamptz,timestamptz,integer,integer)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.purge_location_history(text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.bookings_notify_new()', 'execute')
               and has_function_privilege('authenticated', 'public.trimax_update_location(double precision,double precision,double precision,double precision,double precision,boolean)', 'execute')
               and not has_function_privilege('anon', 'public.trimax_update_location(double precision,double precision,double precision,double precision,double precision,boolean)', 'execute'),
               'only the Desktop (owner) can read history; phones can still send their position');
select test.ok(not has_table_privilege('authenticated', 'public.admin_notifications', 'select')
               and not has_table_privilege('anon', 'public.admin_notifications', 'select'),
               'phones cannot read admin notifications');
select test.ok(not exists (select 1 from trimax_private.location_history),
               'history starts empty: nothing is back-filled or made up');

set role authenticated;
select test.jwt(:'ana');
select test.expect_error('select count(*) from trimax_private.location_history', '42501',
                         'an agent reading the history table directly is refused');
select test.expect_error($$select * from trimax_private.location_history_for('marc.admin', 'driver.dan', now() - interval '1 day', now())$$,
                         '42501', 'an agent calling the history function with an admin name is refused');
select test.expect_error('select count(*) from public.admin_notifications', '42501',
                         'an agent cannot read admin notifications');
reset role;

-- ================================================================== 2. Recording from the phone's normal call
-- Default settings: a sample every 30 s, or after 50 m (and at least 5 s), accuracy up to 100 m.
set role authenticated;
select test.jwt(:'ana');
select public.trimax_update_location(14.80000, 121.00000, 8, 0, 0, true) as r \gset
select test.ok((:'r'::jsonb ->> 'stored')::boolean and (:'r'::jsonb ->> 'history_stored')::boolean,
               'first position: current position stored and a history sample recorded');
select pg_sleep(2.2);
select public.trimax_update_location(14.80100, 121.00000, 10, 3, 0, true) as r \gset
select test.ok((:'r'::jsonb ->> 'stored')::boolean and :'r'::jsonb ->> 'history_skipped' = 'too_soon',
               '111 m away but under 5 s since the last sample: live marker moves, no history sample yet');
select pg_sleep(2.2);
select public.trimax_update_location(14.80200, 121.00000, 500, 0, 0, true) as r \gset
select test.ok((:'r'::jsonb ->> 'stored')::boolean and :'r'::jsonb ->> 'history_skipped' = 'inaccurate',
               'a 500 m accuracy fix updates the live marker but is not kept as history');
select pg_sleep(2.2);
select public.trimax_update_location(14.80001, 121.00001, 8, 0, 0, true) as r \gset
select test.ok((:'r'::jsonb ->> 'stored')::boolean and not (:'r'::jsonb ->> 'history_stored')::boolean
               and :'r'::jsonb ->> 'history_skipped' = 'too_soon',
               '6 s later, 1 m from the last sample: no new sample (no near-duplicates)');
select pg_sleep(2.2);
select public.trimax_update_location(14.80100, 121.00000, 10, 3, 0, true) as r \gset
select test.ok((:'r'::jsonb ->> 'history_stored')::boolean,
               'moved 111 m (>= 50 m) more than 5 s after the last sample: recorded');
reset role;

set role app_owner;
update trimax_private.location_history_settings set min_interval_s = 5;   -- the interval can be changed without code
reset role;
select pg_sleep(5.2);
set role authenticated;
select test.jwt(:'ana');
select public.trimax_update_location(14.80100, 121.00000, 10, 0, 0, true) as r \gset
select test.ok((:'r'::jsonb ->> 'history_stored')::boolean,
               'standing still: a sample again once the interval (set to 5 s here) has passed');
select test.expect_error('select public.trimax_update_location(14.8, 121.0, 8, 0, 0, false)', 'TM422',
                         'off duty: refused as before');
select test.expect_error('select public.trimax_update_location(0, 0, 8, 0, 0, true)', 'TM422',
                         'a 0,0 position is refused as before');
reset role;

select test.ok((select count(*) = 3 from trimax_private.location_history where username = 'agent.ana')
               and (select bool_and(role_code = 'AGENT') from trimax_private.location_history where username = 'agent.ana')
               and (select count(*) = 1 from public.user_locations where username = 'agent.ana' and latitude = 14.801),
               'agent.ana: 3 history samples, one current position (the latest)');

set role authenticated;
select test.jwt(:'dan');
select public.trimax_update_location(14.70000, 121.10000, 6, 8, 90, true) as r \gset
select test.ok((:'r'::jsonb ->> 'history_stored')::boolean and (:'r'::jsonb ->> 'vehicles_updated')::int >= 0,
               'a driver''s position is recorded too, and the vehicle position still updates');
reset role;
select test.ok((select role_code = 'DRIVER' from trimax_private.location_history where username = 'driver.dan'),
               'the driver''s sample is labelled DRIVER');

-- ================================================================== 3. Append-only and retention
set role app_owner;
select test.expect_error($$update trimax_private.location_history set latitude = 1 where username = 'agent.ana'$$, 'TM409',
                         'a history sample cannot be changed');
select test.expect_error($$delete from trimax_private.location_history where username = 'agent.ana'$$, 'TM409',
                         'a recent history sample cannot be deleted');
insert into trimax_private.location_history (username, role_code, latitude, longitude, recorded_at)
values ('agent.ana', 'AGENT', 14.5, 121.0, now() - interval '100 days');
select test.expect_error($$select trimax_private.purge_location_history('agent.ana')$$, 'TM403',
                         'only an Admin may purge');
select trimax_private.purge_location_history('marc.admin') as purged \gset
select test.ok(:purged = 1 and (select count(*) = 3 from trimax_private.location_history where username = 'agent.ana'),
               'purge removes only the sample older than 90 days');
select test.ok(exists (select 1 from audit_log where action = 'location.history_purge' and actor_username = 'marc.admin'),
               'the purge is in the audit log');
reset role;

-- ================================================================== 4. Admin view: range, order, gaps, sampling
set role app_owner;
-- driver.dee: 60 samples one minute apart, then nothing for 20 minutes, then 40 more
insert into trimax_private.location_history (username, role_code, latitude, longitude, recorded_at)
select 'driver.dee', 'DRIVER', 14.6 + g * 0.0005, 121.0, date_trunc('minute', now()) - interval '3 hours' + g * interval '1 minute'
  from generate_series(0, 59) g;
insert into trimax_private.location_history (username, role_code, latitude, longitude, recorded_at)
select 'driver.dee', 'DRIVER', 14.7 + g * 0.0005, 121.0, date_trunc('minute', now()) - interval '3 hours' + interval '79 minutes' + g * interval '1 minute'
  from generate_series(0, 39) g;

select test.expect_error($$select * from trimax_private.location_history_for('agent.ana', 'driver.dee', now() - interval '1 day', now())$$,
                         'TM403', 'a non-admin name is refused by the history function itself');
select test.expect_error($$select * from trimax_private.location_history_for('marc.admin', 'driver.dee', now(), now() - interval '1 hour')$$,
                         'TM422', 'start after end is refused');
select test.expect_error($$select * from trimax_private.location_history_for('marc.admin', 'driver.dee', now() - interval '40 days', now())$$,
                         'TM422', 'more than 31 days is refused');
select test.expect_error($$select * from trimax_private.location_history_for('marc.admin', 'marc.admin', now() - interval '1 day', now())$$,
                         'TM404', 'only agents and drivers have a history');

create temp table h as select * from trimax_private.location_history_for('marc.admin', 'driver.dee', now() - interval '1 day', now() + interval '1 minute');
select test.ok((select count(*) = 100 from h) and (select bool_and(total_points = 100 and step = 1) from h),
               'all 100 samples come back when under the limit');
select test.ok((select bool_and(ok) from (select recorded_at >= lag(recorded_at, 1, '-infinity') over (order by sample_id) as ok from h) x),
               'samples are in time order');
select test.ok((select count(distinct segment) = 2 from h)
               and (select gap_before_s = 20 * 60 from h where segment = 1 order by recorded_at limit 1),
               'the 20-minute gap starts a new segment (no line is drawn across it)');
drop table h;

create temp table h as select * from trimax_private.location_history_for('marc.admin', 'driver.dee', now() - interval '1 day', now() + interval '1 minute', 10);
select test.ok((select bool_and(total_points = 100 and step = 10) from h)
               and (select count(*) between 10 and 14 from h),
               'over the limit: every 10th sample is returned and the answer says so (100 recorded, step 10)');
select test.ok((select min(recorded_at) = (select min(recorded_at) from trimax_private.location_history where username = 'driver.dee') from h)
               and (select max(recorded_at) = (select max(recorded_at) from trimax_private.location_history where username = 'driver.dee') from h),
               'the first and the last recorded sample are always included');
select test.ok((select count(*) >= 2 from h where segment = 0) and (select count(*) >= 2 from h where segment = 1)
               and exists (select 1 from h where gap_before_s = 20 * 60),
               'both sides of the gap are kept when sampling');
drop table h;

create temp table h as select * from trimax_private.location_history_for('marc.admin', 'driver.dee',
    date_trunc('minute', now()) - interval '3 hours', date_trunc('minute', now()) - interval '3 hours' + interval '30 minutes');
select test.ok((select count(*) = 30 from h), 'a narrower time range returns only the samples inside it (30)');
drop table h;
create temp table h as select * from trimax_private.location_history_for('marc.admin', 'driver.dee', now() - interval '20 days', now() - interval '10 days');
select test.ok((select count(*) = 0 from h), 'a range without samples returns nothing (nothing is made up)');
drop table h;
select test.ok((select count(*) >= 4 from audit_log where action = 'location.history_view' and actor_username = 'marc.admin' and target_id = 'driver.dee'),
               'every Admin view of a person''s history is in the audit log');
reset role;

-- deleting a person removes their history with them (no orphan rows)
set role app_owner;
insert into users (username, password, name, role_id, account_status)
select 'driver.temp', 'x', 'Temp Driver', role_id, 'Active' from roles where role_name = 'Driver';
insert into trimax_private.location_history (username, role_code, latitude, longitude) values ('driver.temp', 'DRIVER', 14.6, 121.0);
delete from users where username = 'driver.temp';
select test.ok(not exists (select 1 from trimax_private.location_history where username = 'driver.temp'),
               'deleting a user removes that user''s history (cascade passes the append-only rule)');
reset role;

-- ================================================================== 5. New booking notification
set role app_owner;
select c.client_id as anas, c.client_name as anas_name, c.contact_num as anas_contact, c.address as anas_addr from clients c
  join agents a on a.client_id = c.client_id and a.username = 'agent.ana' order by c.client_id limit 1 \gset
select p.product_id as prod from products p where p.stock >= 5 order by p.product_id limit 1 \gset
select count(*) as n0 from admin_notifications \gset
select count(*) as nb0 from admin_notifications where category = 'New booking' \gset
reset role;

set role authenticated;
select test.jwt(:'ana');
select gen_random_uuid() as req \gset
select public.trimax_agent_create_booking(:'req'::uuid, :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
       format('[{"product_id": %s, "quantity": 1}]', :prod)::jsonb) as bk \gset
select (:'bk'::jsonb ->> 'booking_id')::int as bkid \gset
-- the phone lost the answer and sends the same booking again
select public.trimax_agent_create_booking(:'req'::uuid, :'anas_name', :'anas_contact', :'anas_addr', 14.8, 121.0,
       format('[{"product_id": %s, "quantity": 1}]', :prod)::jsonb) as bk2 \gset
-- a booking that fails (no items) must not notify
select test.expect_error($$select public.trimax_agent_create_booking(gen_random_uuid(), 'X', '09123456789', 'Somewhere', 14.8, 121.0, '[]'::jsonb)$$,
                         'TM422', 'a booking without items is refused');
reset role;

set role app_owner;
select test.ok((:'bk2'::jsonb ->> 'booking_id')::int = :bkid, 'the retry returns the same booking');
select test.ok((select count(*) = :nb0 + 1 from admin_notifications where category = 'New booking'),
               'one booking, one retry, one failed booking: exactly one new notification');
select test.ok((select kind = 'Announcement' and title = 'New Booking Submitted' and related_type = 'booking'
                       and related_id = :'bkid' and reported_by = 'agent.ana' and read_at is null
                       and body like 'Booking #' || :bkid || ' for %' and body like '%submitted by % (agent.ana)%'
                  from admin_notifications where category = 'New booking' and related_id = :'bkid'),
               'the notification names the booking, the customer and the agent, links to the booking, and is unread');

-- edits, assignment and status changes do not notify again
update bookings set special_instructions = 'Ring twice' where booking_id = :bkid;
update bookings set driver_username = 'driver.dan', vehicle_id = 'TRK-001' where booking_id = :bkid;
update bookings set status = 'Confirmed' where booking_id = :bkid;
select test.ok((select count(*) = :nb0 + 1 from admin_notifications where category = 'New booking'),
               'editing, assigning a driver and confirming the booking add no new-booking notification');

-- a booking entered on the Desktop by an Admin does not notify
insert into bookings (client_id, status) values (:anas, 'Pending');
select test.ok((select count(*) = :nb0 + 1 from admin_notifications where category = 'New booking'),
               'a booking the Admin enters on the Desktop does not notify');

-- a second notification for the same booking is impossible
select test.expect_error(format($$insert into admin_notifications (kind, category, title, related_type, related_id) values ('Announcement', 'New booking', 'dup', 'booking', %L)$$, :'bkid'),
                         '23505', 'the database refuses a second new-booking notification for the same booking');

-- marking read uses the existing workflow (the Desktop's statement)
update admin_notifications set read_at = now(), read_by = 'marc.admin' where read_at is null and related_id = :'bkid' and category = 'New booking';
select test.ok((select read_at is not null and read_by = 'marc.admin' from admin_notifications where category = 'New booking' and related_id = :'bkid'),
               'the notification is marked read like any other');
select test.ok((select count(*) = :n0 + 1 from admin_notifications), 'no other notification was added or removed');
reset role;

select 'ALL LOCATION HISTORY AND BOOKING NOTIFICATION SQL TESTS PASSED' as result;
\echo ALL LOCATION HISTORY AND BOOKING NOTIFICATION SQL TESTS PASSED
