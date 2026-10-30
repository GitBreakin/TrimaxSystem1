-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261030_03_admin_duty.sql (and 20261030_02 for the payroll check).
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

-- ================================================================== 1. Shape and access
select test.ok(exists (select 1 from information_schema.columns where table_schema = 'trimax_private' and table_name = 'duty_events'
                        and column_name = 'recorded_by')
               and exists (select 1 from information_schema.columns where table_schema = 'trimax_private' and table_name = 'duty_events'
                            and column_name = 'note')
               and (select is_nullable = 'YES' from information_schema.columns where table_schema = 'trimax_private'
                     and table_name = 'duty_events' and column_name = 'photo_path')
               and not exists (select 1 from trimax_private.duty_events where recorded_by is not null or photo_path is null),
               'duty records: recorded_by and note added, the photo may be empty; no row changed');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.admin_duty_event(text,text,text,timestamptz,text)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.admin_duty_event(text,text,text,timestamptz,text)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.admin_duty_event(text,text,text,timestamptz,text)', 'execute'),
               'Time in / Time out is Desktop-only');
select test.expect_error($$insert into trimax_private.duty_events (username, event_type) values ('driver.dan', 'START')$$, '23514',
                         'a record with no photo and no Admin is refused (a phone record always has its photo)');

-- a driver with no duty yet, ₱600 a day
insert into users (username, password, name, role_id, account_status)
select 'driver.tim', 'x', 'Tim Driver', role_id, 'Active' from roles where role_name = 'Driver';
set role app_owner;
select trimax_private.set_employee_rate('marc.admin', 'driver.tim', null, 600) \g /dev/null

-- ================================================================== 2. Refusals
select test.expect_error($$select trimax_private.admin_duty_event('agent.ana', 'driver.tim', 'START', now(), null)$$, 'TM403', 'only an Admin records a Time in');
select test.expect_error($$select trimax_private.admin_duty_event('marc.admin', 'nobody.here', 'START', now(), null)$$, 'TM404', 'an unknown account is refused');
select test.expect_error($$select trimax_private.admin_duty_event('marc.admin', 'marc.admin', 'START', now(), null)$$, 'TM422', 'only agents and drivers');
select test.expect_error($$select trimax_private.admin_duty_event('marc.admin', 'driver.tim', 'LUNCH', now(), null)$$, 'TM422', 'only Time in or Time out');
select test.expect_error($$select trimax_private.admin_duty_event('marc.admin', 'driver.tim', 'END', now(), null)$$, 'TM409', 'no Time out while off duty');
select test.expect_error($$select trimax_private.admin_duty_event('marc.admin', 'driver.tim', 'START', now() + interval '1 hour', null)$$, 'TM422', 'not in the future');
select test.expect_error($$select trimax_private.admin_duty_event('marc.admin', 'driver.tim', 'START', now() - interval '8 days', null)$$, 'TM422', 'at most 7 days back');
select test.expect_error(format($$select trimax_private.admin_duty_event('marc.admin', 'driver.tim', 'START', now(), %L)$$, repeat('x', 201)), 'TM422', 'a note of 200 characters at most');

-- ================================================================== 3. Time in, then Time out
select trimax_private.admin_duty_event('marc.admin', 'driver.tim', 'START', now() - interval '9 hours', 'Forgot to tap Start duty') as tin \gset
select test.ok((:'tin'::jsonb ->> 'on_duty')::boolean
               and (trimax_private.duty_status_json('driver.tim') ->> 'on_duty')::boolean
               and (select photo_path is null and recorded_by = 'marc.admin' and note = 'Forgot to tap Start duty'
                      from trimax_private.duty_events where event_id = (:'tin'::jsonb ->> 'event_id')::bigint)
               and exists (select 1 from audit_log where action = 'duty.admin_start' and actor_username = 'marc.admin'
                             and target_id = 'driver.tim' and details like 'Tim Driver: Time in at %, recorded by the admin; note: Forgot to tap Start duty'),
               'Time in 9 hours ago: Tim is on duty (the app sees it too), no photo, who and why recorded, in the history');
select test.expect_error($$select trimax_private.admin_duty_event('marc.admin', 'driver.tim', 'START', now(), null)$$, 'TM409', 'no second Time in while on duty');
select test.expect_error($$select trimax_private.admin_duty_event('marc.admin', 'driver.tim', 'END', now() - interval '10 hours', null)$$, 'TM409',
                         'a Time out before the Time in is refused');
select trimax_private.admin_duty_event('marc.admin', 'driver.tim', 'END', now() - interval '1 hour', null) as tout \gset
select test.ok(not (:'tout'::jsonb ->> 'on_duty')::boolean
               and exists (select 1 from audit_log where action = 'duty.admin_end' and target_id = 'driver.tim'),
               'Time out 1 hour ago: Tim is off duty, in the history');
select test.ok((select auto and days_worked = 1 and daily_rate = 600 and basic_pay = 600 from payroll_records
                 where employee_username = 'driver.tim' and status = 'UNPAID'
                   and period_start = (select period_start from trimax_private.pay_period(((now() - interval '9 hours') at time zone 'Asia/Manila')::date))),
               'the Time out made Tim''s payroll of the week: 8 h = 1 day x ₱600 (20261030_02)');
-- a Time out more than 24 hours after the Time in is refused; within 24 hours it closes the phone's Start duty
reset role;
insert into users (username, password, name, role_id, account_status)
select 'driver.tom', 'x', 'Tom Driver', role_id, 'Active' from roles where role_name = 'Driver';
insert into trimax_private.duty_events (username, event_type, photo_path, recorded_at)
values ('driver.tom', 'START', 'test/admin-duty/tom-start.jpg', now() - interval '2 days');
set role app_owner;
select test.expect_error($$select trimax_private.admin_duty_event('marc.admin', 'driver.tom', 'END', now(), null)$$, 'TM422',
                         'a Time out 48 hours after the phone''s Start duty is refused (a duty over 24 hours is never counted)');
select trimax_private.admin_duty_event('marc.admin', 'driver.tom', 'END', now() - interval '2 days' + interval '8 hours', 'Phone battery died') \g /dev/null
select test.ok(not (trimax_private.duty_status_json('driver.tom') ->> 'on_duty')::boolean
               and (trimax_private.attendance_days('driver.tom', null, ((now() - interval '2 days') at time zone 'Asia/Manila')::date,
                                                   ((now() - interval '2 days') at time zone 'Asia/Manila')::date) ->> 'days')::numeric = 1,
               'the Admin''s Time out 8 hours after the phone''s Start duty closes it: 1 day counted');
reset role;

\echo ALL ADMIN DUTY SQL TESTS PASSED
