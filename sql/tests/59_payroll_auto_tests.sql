-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261030_02_payroll_auto.sql (and the 58 tests, whose data it uses:
-- Pedro and Rosa's February 2025 marks, Pedro's typed February record, driver.dan's duty).
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;
\set dan '00000000-0000-0000-0000-00000000d0d0'

-- ================================================================== 1. Shape and access
select test.ok(to_regclass('public.payroll_account_rates') is not null
               and (select relrowsecurity from pg_class where oid = 'public.payroll_account_rates'::regclass)
               and not has_table_privilege('authenticated', 'public.payroll_account_rates', 'select')
               and not has_table_privilege('anon', 'public.payroll_account_rates', 'select')
               and not has_table_privilege('service_role', 'public.payroll_account_rates', 'select')
               and exists (select 1 from information_schema.columns where table_name = 'payroll_staff' and column_name = 'daily_rate')
               and exists (select 1 from information_schema.columns where table_name = 'payroll_records' and column_name = 'auto')
               and (select count(*) = 0 from payroll_records where auto)
               and (select count(*) = 0 from payroll_staff where daily_rate is not null),
               'rates table (RLS on, no API access), the 2 columns; no record is automatic and no rate is set yet');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.sync_payroll(text,text,bigint,date)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.set_employee_rate(text,text,bigint,numeric)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.sync_payroll_all(text,date)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.pay_period(date)', 'execute')
               and exists (select 1 from pg_trigger where tgname = 'duty_events_payroll' and not tgisinternal),
               'the new functions are Desktop-only; the End duty trigger is there');
select test.ok((select period_start = '2025-02-01' and period_end = '2025-02-15' from trimax_private.pay_period('2025-02-15'))
               and (select period_start = '2025-02-16' and period_end = '2025-02-28' from trimax_private.pay_period('2025-02-16'))
               and (select period_start = '2024-02-16' and period_end = '2024-02-29' from trimax_private.pay_period('2024-02-20'))
               and (select period_start = '2025-12-16' and period_end = '2025-12-31' from trimax_private.pay_period('2025-12-31')),
               'pay periods: 1st-15th and 16th-end (also February in a leap year and December)');

set role app_owner;
select staff_id as pedro from payroll_staff where name = 'Pedro Helper Att' \gset
select staff_id as rosa from payroll_staff where name = 'Rosa Cashier Att' \gset
select payroll_id as pp from payroll_records where staff_id = :pedro and period_start = '2025-02-01' and status <> 'VOID' \gset

-- ================================================================== 2. Setting a daily rate
select test.expect_error(format($$select trimax_private.set_employee_rate('agent.ana', null, %s, 600)$$, :pedro), 'TM403', 'only an Admin sets a daily rate');
select test.expect_error($$select trimax_private.set_employee_rate('marc.admin', null, null, 600)$$, 'TM422', 'an employee is needed');
select test.expect_error(format($$select trimax_private.set_employee_rate('marc.admin', null, %s, -1)$$, :pedro), 'TM422', 'a negative rate is refused');
select test.expect_error(format($$select trimax_private.set_employee_rate('marc.admin', null, %s, 600.555)$$, :pedro), 'TM422', '3 decimals are refused');
select test.expect_error($$select trimax_private.set_employee_rate('marc.admin', null, 999999, 600)$$, 'TM404', 'an unknown employee is refused');
select test.expect_error($$select trimax_private.set_employee_rate('marc.admin', 'nobody.here', null, 600)$$, 'TM404', 'an unknown account is refused');
select trimax_private.set_employee_rate('marc.admin', null, :pedro, 600) \g /dev/null
select test.ok((select daily_rate = 600 from payroll_staff where staff_id = :pedro)
               and exists (select 1 from audit_log where action = 'payroll.rate' and details = 'Pedro Helper Att: daily rate ₱600.00')
               and (select not auto and basic_pay = 1250 from payroll_records where payroll_id = :pp),
               'Pedro: ₱600 a day, in the history; his February record is not touched by setting the rate (past period)');

-- ================================================================== 3. Marking a day makes the payroll
-- Pedro's typed record (2.5 days x 500 + 100 allowance, unpaid) is taken over: Feb 3, 5 present + Feb 6 present = 3 days
select trimax_private.save_staff_attendance('marc.admin', '2025-02-06', format('[{"staff_id": %s, "status": "PRESENT"}, {"staff_id": %s, "status": "PRESENT"}]', :pedro, :rosa)::jsonb) \g /dev/null
select test.ok((select auto and days_worked = 3 and daily_rate = 600 and basic_pay = 1800 and allowances = 100 and net_pay = 1900
                  and updated_by = 'marc.admin' from payroll_records where payroll_id = :pp)
               and (select count(*) = 1 from payroll_records where staff_id = :pedro and period_start = '2025-02-01' and status <> 'VOID')
               and exists (select 1 from audit_log where action = 'payroll.auto' and target_id = :'pp'
                             and details = 'Pedro Helper Att, 2025-02-01 to 2025-02-15: 3 days x ₱600.00 = ₱1,800.00 (was 2.5 days, ₱1,250.00, typed by hand)'),
               'marking Feb 6 present: Pedro''s record becomes 3 days x ₱600 = ₱1,800 (+ ₱100 allowance kept), automatic, in the history');
select test.ok(not exists (select 1 from payroll_records where staff_id = :rosa),
               'Rosa has no daily rate: no payroll is made for her');
select trimax_private.set_employee_rate('marc.admin', null, :rosa, 500) \g /dev/null
select trimax_private.sync_payroll_all('marc.admin', '2025-02-10') as all1 \gset
select test.ok((:'all1'::jsonb ->> 'created')::int = 1 and (:'all1'::jsonb ->> 'updated')::int = 0
               and (select auto and status = 'UNPAID' and days_worked = 2.5 and daily_rate = 500 and basic_pay = 1250
                      and created_by = 'marc.admin' and employee_name = 'Rosa Cashier Att'
                      from payroll_records where staff_id = :rosa and period_start = '2025-02-01' and period_end = '2025-02-15'),
               'counting everyone for Feb 1-15 makes Rosa''s record: 0.5 + 0 + 1 + 1 = 2.5 days x ₱500 = ₱1,250 (Pedro unchanged)');
select trimax_private.save_staff_attendance('marc.admin', '2025-02-06', format('[{"staff_id": %s, "status": "HALF_DAY"}]', :pedro)::jsonb) \g /dev/null
select test.ok((select days_worked = 2.5 and basic_pay = 1500 and net_pay = 1600 from payroll_records where payroll_id = :pp),
               'Feb 6 changed to half day: 2.5 days x ₱600 = ₱1,500');
select trimax_private.sync_payroll_all('marc.admin', '2025-02-10') as all2 \gset
select test.ok((:'all2'::jsonb ->> 'created')::int = 0 and (:'all2'::jsonb ->> 'updated')::int = 0,
               'counting again with nothing new changes nothing');
-- a later rate is for the current period: February (past) keeps ₱600
select trimax_private.set_employee_rate('marc.admin', null, :pedro, 700) \g /dev/null
select trimax_private.save_staff_attendance('marc.admin', '2025-02-07', format('[{"staff_id": %s, "status": "ABSENT"}]', :pedro)::jsonb) \g /dev/null
select test.ok((select days_worked = 2.5 and daily_rate = 600 and basic_pay = 1500 from payroll_records where payroll_id = :pp)
               and exists (select 1 from audit_log where action = 'payroll.rate' and details = 'Pedro Helper Att: daily rate ₱700.00 (was ₱600.00)'),
               'rate ₱700 from now on: the past February record keeps ₱600 (Feb 7 absent adds nothing)');

-- ================================================================== 4. A paid record is never changed
select trimax_private.save_payroll('marc.admin', :pp, null, null, null, '2025-02-01', '2025-02-15', 1500, 100, 0,
       'PAID', '2025-02-16', 'CASH', null, :pedro) \g /dev/null
select trimax_private.save_staff_attendance('marc.admin', '2025-02-06', format('[{"staff_id": %s, "status": "PRESENT"}]', :pedro)::jsonb) \g /dev/null
select test.ok((select status = 'PAID' and days_worked = 2.5 and basic_pay = 1500 from payroll_records where payroll_id = :pp)
               and trimax_private.sync_payroll('marc.admin', null, :pedro, '2025-02-06') = 'paid',
               'paid: Feb 6 back to present does not change it (attendance now 3 days, paid 2.5)');

-- ================================================================== 5. A typed name equal to the employee is taken over
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, 'lito typed', '2025-02-01', '2025-02-15',
       999, 0, 0, 'UNPAID', null, null, null) as typed \gset
select trimax_private.save_payroll_staff('marc.admin', null, 'Lito Typed', 'Helper', null, true) as lito \gset
select trimax_private.set_employee_rate('marc.admin', null, :lito, 400) \g /dev/null
select trimax_private.save_staff_attendance('marc.admin', '2025-02-07', format('[{"staff_id": %s, "status": "PRESENT"}]', :lito)::jsonb) \g /dev/null
select test.ok((select staff_id = :lito and auto and days_worked = 1 and basic_pay = 400 from payroll_records where payroll_id = :typed)
               and (select count(*) = 1 from payroll_records where lower(employee_name) = 'lito typed' and period_start = '2025-02-01' and status <> 'VOID'),
               'a typed "lito typed" record of Feb 1-15 becomes Lito Typed''s: 1 day x ₱400 (no second record)');

-- a typed record of an employee with a rate but no attendance in that period stays as typed
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, null, '2025-01-01', '2025-01-15',
       5000, 0, 0, 'UNPAID', null, null, null, :lito) as jan \gset
select test.ok(trimax_private.sync_payroll('marc.admin', null, :lito, '2025-01-10') = 'nothing'
               and (select not auto and basic_pay = 5000 and days_worked is null from payroll_records where payroll_id = :jan),
               'no attendance in Jan 1-15: Lito''s typed ₱5,000 record is not touched');

-- ================================================================== 6. Deductions more than the new pay: refused, nothing saved
select payroll_id as rr from payroll_records where staff_id = :rosa and period_start = '2025-02-01' \gset
select trimax_private.save_payroll('marc.admin', :rr, null, null, null, '2025-02-01', '2025-02-15', 1250, 0, 1000,
       'UNPAID', null, null, 'cash advance', :rosa) \g /dev/null
select test.expect_error(format($$select trimax_private.save_staff_attendance('marc.admin', '2025-02-05', '[{"staff_id": %s, "status": "ABSENT"}]')$$, :rosa),
                         'TM422', 'Rosa absent on Feb 5 would pay ₱750 against ₱1,000 deductions: refused');
select test.ok((select status = 'PRESENT' from staff_attendance where staff_id = :rosa and work_date = '2025-02-05')
               and (select basic_pay = 1250 from payroll_records where payroll_id = :rr),
               'the refused day was not saved and the payroll is unchanged');
-- the same found by "count everyone" is reported, not an error
reset role;
update staff_attendance set status = 'ABSENT' where staff_id = :rosa and work_date = '2025-02-05';
set role app_owner;
select trimax_private.sync_payroll_all('marc.admin', '2025-02-10') as all3 \gset
select test.ok(jsonb_array_length(:'all3'::jsonb -> 'problems') = 1 and :'all3'::jsonb ->> 'problems' like '%Rosa Cashier Att: the deductions%',
               'count everyone: Rosa''s problem is listed, the others are still counted');
reset role;
update staff_attendance set status = 'PRESENT' where staff_id = :rosa and work_date = '2025-02-05';
set role app_owner;

-- ================================================================== 7. Agents and drivers: an End duty makes the payroll
reset role;
insert into trimax_private.duty_events (username, event_type, photo_path, recorded_at)
select 'driver.dan', t, 'test/auto/' || md5(t || at), (at::timestamp at time zone 'Asia/Manila')
  from (values ('START', '2025-03-03 08:00'), ('END', '2025-03-03 14:00')) v(t, at);
select test.ok(not exists (select 1 from payroll_records where employee_username = 'driver.dan' and period_start = '2025-03-01'),
               'no daily rate yet: an End duty makes no payroll');
set role app_owner;
select trimax_private.set_employee_rate('marc.admin', 'driver.dan', null, 800) \g /dev/null
reset role;
insert into trimax_private.duty_events (username, event_type, photo_path, recorded_at)
select 'driver.dan', t, 'test/auto/' || md5(t || at), (at::timestamp at time zone 'Asia/Manila')
  from (values ('START', '2025-03-04 20:00'), ('END', '2025-03-05 04:00')) v(t, at);
select test.ok((select auto and days_worked = 1.75 and daily_rate = 800 and basic_pay = 1400 and created_by = 'driver.dan'
                  and employee_name is not null from payroll_records
                 where employee_username = 'driver.dan' and period_start = '2025-03-01' and period_end = '2025-03-15')
               and exists (select 1 from audit_log where action = 'payroll.auto' and actor_username = 'driver.dan'
                             and details like '%2025-03-01 to 2025-03-15: made from attendance, 1.75 days x ₱800.00 = ₱1,400.00'),
               'driver.dan ₱800 a day: the End duty of a night shift makes Mar 1-15: 6 h (0.75) + 8 h (1) = 1.75 days = ₱1,400');
insert into trimax_private.duty_events (username, event_type, photo_path, recorded_at)
select 'driver.dan', t, 'test/auto/' || md5(t || at), (at::timestamp at time zone 'Asia/Manila')
  from (values ('START', '2025-03-15 22:00'), ('END', '2025-03-16 06:00')) v(t, at);
select test.ok((select days_worked = 2.75 and basic_pay = 2200 from payroll_records
                 where employee_username = 'driver.dan' and period_start = '2025-03-01')
               and not exists (select 1 from payroll_records where employee_username = 'driver.dan' and period_start = '2025-03-16'),
               'a duty from Mar 15 22:00 to Mar 16 06:00 counts on the 15th: Mar 1-15 = 2.75 days, nothing for Mar 16-31');
-- a payroll problem never stops the End duty
alter table public.payroll_records add constraint test_block_dan check (employee_username is distinct from 'driver.dan' or period_start <> '2025-03-16');
insert into trimax_private.duty_events (username, event_type, photo_path, recorded_at)
select 'driver.dan', t, 'test/auto/' || md5(t || at), (at::timestamp at time zone 'Asia/Manila')
  from (values ('START', '2025-03-17 08:00'), ('END', '2025-03-17 16:00')) v(t, at);
select test.ok(exists (select 1 from trimax_private.duty_events where username = 'driver.dan' and event_type = 'END'
                         and recorded_at = '2025-03-17 16:00'::timestamp at time zone 'Asia/Manila')
               and not exists (select 1 from payroll_records where employee_username = 'driver.dan' and period_start = '2025-03-16'),
               'when the payroll cannot be saved, the End duty is still recorded (only a warning)');
alter table public.payroll_records drop constraint test_block_dan;
set role app_owner;
select trimax_private.sync_payroll_all('marc.admin', '2025-03-17') \g /dev/null
select test.ok((select days_worked = 1 and basic_pay = 800 from payroll_records where employee_username = 'driver.dan' and period_start = '2025-03-16'),
               'Payroll counting everyone afterwards makes it: Mar 16-31 = 1 day = ₱800');
reset role;

-- the phone's own End duty (signed in as driver.dan) also makes the payroll of today's period
insert into storage.objects (bucket_id, name, owner, owner_id, metadata) values
    ('duty-photos', :'dan' || '/auto_start.jpg', :'dan', :'dan', '{"size": 1}'),
    ('duty-photos', :'dan' || '/auto_end.jpg', :'dan', :'dan', '{"size": 1}');
select period_start as today_ps from trimax_private.pay_period((now() at time zone 'Asia/Manila')::date) \gset
set role authenticated;
select test.jwt(:'dan');
select test.ok((public.trimax_record_duty_event('START', :'dan' || '/auto_start.jpg') ->> 'on_duty')::boolean, 'phone: Start duty');
select test.ok(not (public.trimax_record_duty_event('END', :'dan' || '/auto_end.jpg') ->> 'on_duty')::boolean, 'phone: End duty still works');
reset role;
select test.ok((select auto and status = 'UNPAID' and daily_rate = 800 from payroll_records
                 where employee_username = 'driver.dan' and period_start = :'today_ps'),
               'the phone''s End duty made driver.dan''s record for the current period');

-- ================================================================== 8. Removing a rate
set role app_owner;
select trimax_private.set_employee_rate('marc.admin', 'driver.dan', null, null) as removed \gset
select test.ok(:removed = 3 and not exists (select 1 from payroll_account_rates where username = 'driver.dan')
               and not exists (select 1 from payroll_records where employee_username = 'driver.dan' and auto and status = 'UNPAID')
               and (select basic_pay = 2200 from payroll_records where employee_username = 'driver.dan' and period_start = '2025-03-01')
               and exists (select 1 from audit_log where action = 'payroll.rate'
                             and details like '%: daily rate removed (payroll no longer made from attendance) (was ₱800.00)'),
               'rate removed: driver.dan''s 3 unpaid records stay as they are, as ordinary records');

-- ================================================================== 9. History text without a rate
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, 'No Rate Person', '2025-04-01', '2025-04-15',
       500, 0, 0, 'UNPAID', null, null, null) as nr \gset
select trimax_private.set_payroll_attendance('marc.admin', :nr, 2, null) \g /dev/null
select test.ok(exists (select 1 from audit_log where action = 'payroll.days' and target_id = :'nr'
                         and details = 'No Rate Person, 2025-04-01 to 2025-04-15: 2 days'),
               'days without a rate: the history no longer says "x ₱0.00"');
reset role;

\echo ALL PAYROLL AUTO SQL TESTS PASSED
