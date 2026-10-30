-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261030_01_payroll_attendance.sql.
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

-- ================================================================== 1. Shape and access
select test.ok(to_regclass('public.staff_attendance') is not null
               and (select relrowsecurity from pg_class where oid = 'public.staff_attendance'::regclass)
               and not has_table_privilege('authenticated', 'public.staff_attendance', 'select')
               and not has_table_privilege('anon', 'public.staff_attendance', 'select')
               and not has_table_privilege('service_role', 'public.staff_attendance', 'select')
               and (select count(*) = 2 from information_schema.columns where table_name = 'payroll_records'
                     and column_name in ('days_worked', 'daily_rate'))
               and (select count(*) = 0 from payroll_records where days_worked is not null or daily_rate is not null),
               'staff attendance table (RLS on, no API access) and the 2 payroll columns; no row changed');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.save_staff_attendance(text,date,jsonb)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.attendance_days(text,bigint,date,date)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.set_payroll_attendance(text,bigint,numeric,numeric)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.attendance_days(text,bigint,date,date)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.save_staff_attendance(text,date,jsonb)', 'execute'),
               'the 3 functions are Desktop-only');

-- ================================================================== 2. Agents and drivers: days from duty (8 hours = 1 day)
-- driver.dan's duty in 2025 (Manila times); each line is one Start / End from the phone
insert into trimax_private.duty_events (username, event_type, photo_path, recorded_at)
select 'driver.dan', t, 'test/pa/' || md5(t || at), (at::timestamp at time zone 'Asia/Manila')
  from (values ('START', '2025-01-31 20:00'), ('END', '2025-02-01 04:00'),   -- counted on Jan 31: outside the period
               ('START', '2025-02-03 08:00'), ('END', '2025-02-03 17:00'),   -- 9 h: 1 day (never more)
               ('START', '2025-02-04 08:00'), ('END', '2025-02-04 14:00'),   -- 6 h: 0.75
               ('START', '2025-02-05 08:00'), ('END', '2025-02-05 10:00'),   -- 2 h
               ('START', '2025-02-05 13:00'), ('END', '2025-02-05 17:00'),   -- + 4 h = 6 h: 0.75
               ('START', '2025-02-06 22:00'), ('END', '2025-02-07 06:00'),   -- 8 h over midnight: 1 (Feb 6)
               ('START', '2025-02-08 08:00'),                                -- no End duty: open, 0
               ('START', '2025-02-09 08:00'), ('END', '2025-02-09 12:00'),   -- 4 h: 0.5
               ('START', '2025-02-10 08:00'), ('END', '2025-02-11 12:00'),   -- 28 h: not a session, open, 0
               ('START', '2025-02-15 20:00'), ('END', '2025-02-16 04:00')    -- 8 h, started on the last day: 1
       ) v(t, at);
set role app_owner;
select trimax_private.attendance_days('driver.dan', null, '2025-02-01', '2025-02-15') as dan \gset
select test.ok((:'dan'::jsonb ->> 'days')::numeric = 5.00 and (:'dan'::jsonb ->> 'hours')::numeric = 41.00
               and (:'dan'::jsonb ->> 'duty_days')::int = 8 and (:'dan'::jsonb ->> 'open_days')::int = 2
               and :'dan'::jsonb -> 'open_dates' = '["2025-02-08", "2025-02-10"]'::jsonb,
               'driver.dan, Feb 1-15: 1 + 0.75 + 0.75 + 1 + 0.5 + 1 = 5 days (41 h); Feb 8 and Feb 10 are open (no End duty)');
select test.ok((trimax_private.attendance_days('driver.dan', null, '2025-02-04', '2025-02-04') ->> 'days')::numeric = 0.75
               and (trimax_private.attendance_days('driver.dan', null, '2025-03-01', '2025-03-31') ->> 'days')::numeric = 0
               and (trimax_private.attendance_days('agent.ana', null, '2025-02-01', '2025-02-15') ->> 'duty_days')::int = 0,
               'one day (0.75), a month without duty (0) and someone else (0)');
select test.expect_error($$select trimax_private.attendance_days('driver.dan', 1, '2025-02-01', '2025-02-15')$$, 'TM422',
                         'an account and an employee without one together are refused');
select test.expect_error($$select trimax_private.attendance_days('driver.dan', null, '2025-02-15', '2025-02-01')$$, 'TM422',
                         'a period that ends before it starts is refused');

-- ================================================================== 3. Employees without an account: the Admin marks the day
select trimax_private.save_payroll_staff('marc.admin', null, 'Pedro Helper Att', 'Helper', null, true) as pedro \gset
select trimax_private.save_payroll_staff('marc.admin', null, 'Rosa Cashier Att', 'Cashier', null, true) as rosa \gset
select test.expect_error(format($$select trimax_private.save_staff_attendance('agent.ana', '2025-02-03', '[{"staff_id": %s, "status": "PRESENT"}]')$$, :pedro),
                         'TM403', 'only an Admin marks attendance');
select test.expect_error(format($$select trimax_private.save_staff_attendance('marc.admin', current_date + 3, '[{"staff_id": %s, "status": "PRESENT"}]')$$, :pedro),
                         'TM422', 'a day in the future is refused');
select test.expect_error(format($$select trimax_private.save_staff_attendance('marc.admin', '2025-02-03', '[{"staff_id": %s, "status": "LATE"}]')$$, :pedro),
                         'TM422', 'an unknown status is refused');
select test.expect_error($$select trimax_private.save_staff_attendance('marc.admin', '2025-02-03', '[{"staff_id": 999999, "status": "PRESENT"}]')$$,
                         'TM404', 'an employee that does not exist is refused');
select test.expect_error(format($$select trimax_private.save_staff_attendance('marc.admin', '2025-02-03', '[{"staff_id": %s, "status": "PRESENT"}, {"staff_id": %s, "status": "ABSENT"}]')$$, :pedro, :pedro),
                         'TM422', 'the same employee twice is refused');
select test.ok((select count(*) = 0 from staff_attendance), 'nothing refused was saved');

select trimax_private.save_staff_attendance('marc.admin', '2025-02-03', format('[{"staff_id": %s, "status": "PRESENT"}, {"staff_id": %s, "status": "HALF_DAY", "note": "Left at noon"}]', :pedro, :rosa)::jsonb) as n1 \gset
select trimax_private.save_staff_attendance('marc.admin', '2025-02-04', format('[{"staff_id": %s, "status": "HALF_DAY"}, {"staff_id": %s, "status": "ABSENT"}]', :pedro, :rosa)::jsonb) \g /dev/null
select trimax_private.save_staff_attendance('marc.admin', '2025-02-05', format('[{"staff_id": %s, "status": "PRESENT"}, {"staff_id": %s, "status": "PRESENT"}]', :pedro, :rosa)::jsonb) \g /dev/null
select test.ok(:n1 = 2 and (select note = 'Left at noon' and recorded_by = 'marc.admin' from staff_attendance where staff_id = :rosa and work_date = '2025-02-03')
               and exists (select 1 from audit_log where action = 'attendance.staff' and target_id = '2025-02-03'
                             and details = '2025-02-03: 1 present, 1 half day'),
               'Feb 3: Pedro present, Rosa half day (with a note); in the audit log');
select test.ok((trimax_private.attendance_days(null, :pedro, '2025-02-01', '2025-02-15') ->> 'days')::numeric = 2.5
               and (trimax_private.attendance_days(null, :rosa, '2025-02-01', '2025-02-15') ->> 'days')::numeric = 1.5
               and (trimax_private.attendance_days(null, :rosa, '2025-02-01', '2025-02-15') ->> 'absent')::int = 1,
               'Pedro 1 + 0.5 + 1 = 2.5 days; Rosa 0.5 + 0 + 1 = 1.5 days (1 absent)');
-- correct a day, then clear it
select trimax_private.save_staff_attendance('marc.admin', '2025-02-04', format('[{"staff_id": %s, "status": "PRESENT"}]', :pedro)::jsonb) \g /dev/null
select test.ok((trimax_private.attendance_days(null, :pedro, '2025-02-01', '2025-02-15') ->> 'days')::numeric = 3,
               'Pedro''s Feb 4 changed from half day to present: 3 days');
select trimax_private.save_staff_attendance('marc.admin', '2025-02-04', format('[{"staff_id": %s, "status": null}]', :pedro)::jsonb) as cleared \gset
select test.ok(:cleared = 1 and not exists (select 1 from staff_attendance where staff_id = :pedro and work_date = '2025-02-04')
               and (trimax_private.attendance_days(null, :pedro, '2025-02-01', '2025-02-15') ->> 'days')::numeric = 2,
               'clearing Pedro''s Feb 4 removes it: 2 days');
select trimax_private.save_staff_attendance('marc.admin', '2025-02-05', format('[{"staff_id": %s, "status": "PRESENT"}]', :pedro)::jsonb) as same \gset
select test.ok(:same = 0, 'saving the same day again changes nothing');

-- ================================================================== 4. Payroll: days x daily rate on the record
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), 'driver.dan', null, '2025-02-01', '2025-02-15',
       3000, 0, 0, 'UNPAID', null, null, null) as pr \gset
select trimax_private.set_payroll_attendance('marc.admin', :pr, 5, 600) \g /dev/null
select test.ok((select days_worked = 5 and daily_rate = 600 and basic_pay = 3000 and net_pay = 3000 from payroll_records where payroll_id = :pr)
               and exists (select 1 from audit_log where action = 'payroll.days' and target_id = :'pr'
                             and details like '%: 5 days x ₱600.00'),
               'driver.dan''s payroll: 5 days x ₱600 = ₱3,000 basic; days and rate saved and audited');
select count(*) as audits0 from audit_log where action = 'payroll.days' and target_id = :'pr' \gset
select trimax_private.set_payroll_attendance('marc.admin', :pr, 5, 600) \g /dev/null
select test.ok((select count(*) from audit_log where action = 'payroll.days' and target_id = :'pr') = :audits0,
               'saving the same days and rate writes nothing');
select trimax_private.set_payroll_attendance('marc.admin', :pr, 4.75, 600) \g /dev/null
select test.ok(exists (select 1 from audit_log where action = 'payroll.days' and target_id = :'pr'
                         and details like '%: 4.75 days x ₱600.00 (was 5 days x ₱600.00)'),
               'a correction says what it was');
select test.expect_error(format($$select trimax_private.set_payroll_attendance('agent.ana', %s, 5, 600)$$, :pr), 'TM403', 'only an Admin');
select test.expect_error(format($$select trimax_private.set_payroll_attendance('marc.admin', %s, 400, 600)$$, :pr), 'TM422', 'more than 366 days is refused');
select test.expect_error(format($$select trimax_private.set_payroll_attendance('marc.admin', %s, 1.333, 600)$$, :pr), 'TM422', 'days with 3 decimals are refused');
select test.expect_error(format($$select trimax_private.set_payroll_attendance('marc.admin', %s, 5, -1)$$, :pr), 'TM422', 'a negative rate is refused');
select trimax_private.void_payroll('marc.admin', :pr, 'Test') \g /dev/null
select test.expect_error(format($$select trimax_private.set_payroll_attendance('marc.admin', %s, 5, 600)$$, :pr), 'TM409', 'a voided record cannot be changed');
-- an older Desktop editing a record keeps its days and rate
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, null, '2025-02-01', '2025-02-15',
       1250, 0, 0, 'UNPAID', null, null, null, :pedro) as pp \gset
select trimax_private.set_payroll_attendance('marc.admin', :pp, 2.5, 500) \g /dev/null
select trimax_private.save_payroll('marc.admin', :pp, null, null, null, '2025-02-01', '2025-02-15',
       1250, 100, 0, 'UNPAID', null, null, 'allowance added', :pedro) \g /dev/null
select test.ok((select days_worked = 2.5 and daily_rate = 500 and net_pay = 1350 from payroll_records where payroll_id = :pp),
               'Pedro: 2.5 days x ₱500; editing the record (save_payroll) keeps the days and rate');
reset role;

\echo ALL PAYROLL ATTENDANCE SQL TESTS PASSED
