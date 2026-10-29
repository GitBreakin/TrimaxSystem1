-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261029_01_payroll_staff.sql.
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC). Every check prints PASS or stops.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

-- A pay period no other test uses (so the totals below only count these records).
\set p0 '2025-03-01'
\set p1 '2025-03-15'
select (date '2025-03-01')::timestamp at time zone 'Asia/Manila' as d0 \gset
select (date '2025-04-01')::timestamp at time zone 'Asia/Manila' as d1 \gset

-- ================================================================== 1. Shape and access
select test.ok(to_regclass('public.payroll_staff') is not null
               and (select relrowsecurity from pg_class where oid = 'public.payroll_staff'::regclass)
               and not has_table_privilege('anon', 'public.payroll_staff', 'select')
               and not has_table_privilege('authenticated', 'public.payroll_staff', 'select')
               and not has_table_privilege('service_role', 'public.payroll_staff', 'select')
               and exists (select 1 from information_schema.columns where table_name = 'payroll_records' and column_name = 'staff_id'),
               'employees without an account: own table (RLS on, no API access) and payroll_records.staff_id');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.save_payroll_staff(text,bigint,text,text,text,boolean)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.save_payroll_staff(text,bigint,text,text,text,boolean)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.save_payroll(text,bigint,uuid,text,text,date,date,numeric,numeric,numeric,text,date,text,text,bigint)', 'execute')
               and (select count(*) = 2 from pg_proc where proname = 'save_payroll')
               and to_regprocedure('trimax_private.save_payroll(text,bigint,uuid,text,text,date,date,numeric,numeric,numeric,text,date,text,text)') is not null,
               'Desktop functions for the owner only; the 14-argument save_payroll older Desktops call is still there');

-- ================================================================== 2. Add / edit an employee
set role app_owner;
select test.expect_error($$select trimax_private.save_payroll_staff('agent.ana', null, 'Juan Dela Cruz', 'Helper', null, true)$$,
                         'TM403', 'only an Admin adds employees');
select test.expect_error($$select trimax_private.save_payroll_staff('marc.admin', null, ' J ', 'Helper', null, true)$$,
                         'TM422', 'a name of 1 letter is refused');
select test.expect_error($$select trimax_private.save_payroll_staff('marc.admin', null, 'Juan Dela Cruz', 'Helper', '12345', true)$$,
                         'TM422', 'a wrong contact number is refused');
select trimax_private.save_payroll_staff('marc.admin', null, '  Juan   Dela Cruz ', 'Helper', '0917-123-4567', null) as juan \gset
select trimax_private.save_payroll_staff('marc.admin', null, 'Maria Santos', 'Cashier', null, true) as maria \gset
select test.ok((select name = 'Juan Dela Cruz' and position = 'Helper' and contact_num = '09171234567' and active
                  from payroll_staff where staff_id = :juan),
               'Juan Dela Cruz added (spaces and dashes cleaned up), active by default');
select test.expect_error($$select trimax_private.save_payroll_staff('marc.admin', null, 'juan dela cruz', 'Driver helper', null, true)$$,
                         'TM409', 'the same name again (any case) is refused');
select test.ok(exists (select 1 from audit_log where action = 'payroll.staff_add' and target_id = :'juan'),
               'adding an employee is in the audit log');

-- ================================================================== 3. Payroll for an employee without an account
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, 'typed name ignored', :'p0', :'p1',
       6000, 500, 200, 'PAID', :'p1', 'CASH', null, :juan) as pj \gset
select test.ok((select employee_username is null and staff_id = :juan and employee_name = 'Juan Dela Cruz' and net_pay = 6300
                  from payroll_records where payroll_id = :pj),
               'payroll for Juan: linked to him, his name on the record, net 6000 + 500 - 200 = 6300');
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, null, %L, %L, 1, 0, 0, 'UNPAID', null, null, null, %s)$$,
                                :'p0', :'p1', :juan), 'TM409', 'a second record for Juan in the same pay period is refused');
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), 'agent.ana', null, %L, %L, 1, 0, 0, 'UNPAID', null, null, null, %s)$$,
                                :'p0', :'p1', :maria), 'TM422', 'an account and an employee without one together are refused');
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, null, %L, %L, 1, 0, 0, 'UNPAID', null, null, null, 999999)$$,
                                :'p0', :'p1'), 'TM404', 'an employee that does not exist is refused');
select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, null, :'p0', :'p1',
       4000, 0, 0, 'UNPAID', null, null, null, :maria) as pm \gset

-- ================================================================== 4. Payroll lowers net profit
select test.ok((select payroll_paid = 6300 and payroll_unpaid = 4000 and payroll_count = 2
                  from trimax_private.profit_summary(:'d0', :'d1')),
               'Sales & Profit counts both: paid 6300, unpaid 4000 (net profit goes down by 10300)');

-- ================================================================== 5. No longer working here
select trimax_private.save_payroll_staff('marc.admin', :maria, 'Maria Santos', 'Cashier', null, false);
select test.expect_error(format($$select trimax_private.save_payroll('marc.admin', null, gen_random_uuid(), null, null, '2025-03-16', '2025-03-31', 1, 0, 0, 'UNPAID', null, null, null, %s)$$,
                                :maria), 'TM422', 'no new payroll for an employee marked as no longer working here');
select trimax_private.save_payroll('marc.admin', :pm, null, null, null, :'p0', :'p1', 4500, 0, 0, 'UNPAID', null, null, null, :maria);
select test.ok((select basic_pay = 4500 and staff_id = :maria from payroll_records where payroll_id = :pm),
               'her existing record can still be corrected (4000 -> 4500)');
select test.ok(exists (select 1 from audit_log where action = 'payroll.staff_update' and target_id = :'maria' and details like '%no longer working here%'),
               'marking her as no longer working here is in the audit log');

-- ================================================================== 6. Older Desktop (14 arguments) editing such a record
select trimax_private.save_payroll('marc.admin', :pj, null, null, 'Juan Dela Cruz', :'p0', :'p1', 6100, 500, 200, 'PAID', :'p1', 'CASH', 'raise');
select test.ok((select staff_id = :juan and basic_pay = 6100 from payroll_records where payroll_id = :pj),
               'an older Desktop editing Juan''s record keeps the link to Juan');

-- ================================================================== 7. Renaming keeps the history
select trimax_private.save_payroll_staff('marc.admin', :juan, 'Juan P. Dela Cruz', 'Helper', '09171234567', true);
select test.ok((select employee_name = 'Juan Dela Cruz' and staff_id = :juan from payroll_records where payroll_id = :pj),
               'renaming Juan keeps the name already on his past records (and the link)');
reset role;

-- clean up so the later checks of the suite see no extra payroll
delete from payroll_records where payroll_id in (:pj, :pm);
select 'ALL PAYROLL STAFF SQL TESTS PASSED' as result;
\echo ALL PAYROLL STAFF SQL TESTS PASSED
