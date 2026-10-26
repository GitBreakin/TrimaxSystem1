-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261027_02_expenses.sql.
-- Desktop steps run as app_owner (the database owner, like the Desktop over JDBC), phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana '00000000-0000-0000-0000-00000000a0a0'
select (date_trunc('day', now() at time zone 'Asia/Manila')) at time zone 'Asia/Manila' as d0 \gset
select (date_trunc('day', now() at time zone 'Asia/Manila') + interval '1 day') at time zone 'Asia/Manila' as d1 \gset
select (now() at time zone 'Asia/Manila')::date as today \gset
-- Earlier test files left sales, bookings and payroll dated today: the report checks compare differences.
create table test.exp_before as select * from trimax_private.profit_summary(:'d0', :'d1');

-- ================================================================== 1. Who can see and call what
select test.ok(not has_table_privilege('authenticated', 'public.expenses', 'select')
               and not has_table_privilege('anon', 'public.expenses', 'select')
               and not has_table_privilege('service_role', 'public.expenses', 'select')
               and not has_table_privilege('authenticated', 'public.expense_history', 'select')
               and not has_table_privilege('service_role', 'public.expense_history', 'select')
               and (select relrowsecurity from pg_class where oid = 'public.expenses'::regclass)
               and (select relrowsecurity from pg_class where oid = 'public.expense_history'::regclass)
               and not exists (select 1 from pg_policies where tablename in ('expenses', 'expense_history')),
               'expenses and their history: RLS on, no policies, invisible to phones, anonymous callers and the service role');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.save_expense(text,bigint,uuid,date,text,text,numeric,text,date,text,text)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.save_expense(text,bigint,uuid,date,text,text,numeric,text,date,text,text)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.void_expense(text,bigint,text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.void_expense(text,bigint,text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.profit_summary(timestamptz,timestamptz)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.profit_trend(timestamptz,timestamptz,text)', 'execute'),
               'expense and report functions are for the database owner only (also after DROP + CREATE)');
set role authenticated;
select test.jwt(:'ana');
select test.expect_error('select * from public.expenses', '42501', 'a phone cannot read expenses');
select test.expect_error('select * from public.expense_history', '42501', 'a phone cannot read the expense history');
reset role;

-- ================================================================== 2. The Desktop keeps working
-- The first 25 report columns keep their names, types and order; the new ones are only added at the end.
select test.ok(pg_get_function_result('trimax_private.profit_summary(timestamptz,timestamptz)'::regprocedure) like
               'TABLE(bookings_count integer, bookings_gross numeric, bookings_discount numeric, bookings_net numeric, '
               || 'bookings_cogs numeric, bookings_lines_no_cost integer, sales_count integer, sales_gross numeric, '
               || 'sales_discount numeric, sales_net numeric, sales_cogs numeric, sales_lines_no_cost integer, '
               || 'unpaid_sales_count integer, unpaid_sales_total numeric, revenue_gross numeric, discounts numeric, '
               || 'revenue_net numeric, cogs numeric, gross_profit numeric, payroll_paid numeric, payroll_unpaid numeric, '
               || 'payroll_count integer, net_profit numeric, restock_cost numeric, restock_count integer, '
               || 'other_expenses numeric, other_expenses_paid numeric, other_expenses_unpaid numeric, other_expenses_count integer)',
               'profit_summary: the 25 existing columns unchanged, 4 expense columns added at the end');
select test.ok(pg_get_function_result('trimax_private.profit_trend(timestamptz,timestamptz,text)'::regprocedure) =
               'TABLE(bucket_start date, revenue numeric, cogs numeric, gross_profit numeric, payroll numeric, net_profit numeric, other_expenses numeric)',
               'profit_trend: the 6 existing columns unchanged, other_expenses added at the end');

-- ================================================================== 3. Add, retry, refuse
set role app_owner;
select gen_random_uuid() as e1req \gset
select trimax_private.save_expense('marc.admin', null, :'e1req', :'today', 'electricity', ' Meralco October ', 1500.50,
                                   'paid', :'today', 'cash', null) as e1 \gset
select test.ok((select category = 'ELECTRICITY' and description = 'Meralco October' and amount = 1500.50
                       and payment_status = 'PAID' and paid_at = :'today'::date and payment_method = 'CASH'
                       and created_by = 'marc.admin' and voided_at is null
                  from expenses where expense_id = :e1),
               'a paid expense is saved (category and status in capitals, description trimmed)');
select test.ok(trimax_private.save_expense('marc.admin', null, :'e1req', :'today', 'ELECTRICITY', 'Meralco October', 1500.50,
                                           'PAID', :'today', 'CASH', null) = :e1
               and (select count(*) = 1 from expenses where client_request_id = :'e1req'),
               'a double-clicked or retried expense is saved once');
select test.ok((select count(*) = 1 and bool_and(action = 'CREATE' and changed_by = 'marc.admin' and before_row is null
                                                 and (after_row ->> 'amount')::numeric = 1500.50)
                  from expense_history where expense_id = :e1)
               and (select count(*) = 1 from audit_log where action = 'expense.create' and target_id = :'e1'
                       and actor_username = 'marc.admin' and details like '%₱1,500.50%'),
               'adding an expense writes one history line and one audit line');

select test.expect_error(format($$select trimax_private.save_expense('agent.ana', null, gen_random_uuid(), %L, 'RENT', 'x', 1,
    'UNPAID', null, null, null)$$, :'today'), 'TM403', 'only an Admin records expenses');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', 0,
    'UNPAID', null, null, null)$$, :'today'), 'TM422', 'an amount of 0 is refused');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', -5,
    'UNPAID', null, null, null)$$, :'today'), 'TM422', 'a negative amount is refused');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', 10.555,
    'UNPAID', null, null, null)$$, :'today'), 'TM422', 'more than 2 decimals is refused');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', 10000000.01,
    'UNPAID', null, null, null)$$, :'today'), 'TM422', 'an amount above ₱10,000,000 is refused');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'PARTY', 'x', 1,
    'UNPAID', null, null, null)$$, :'today'), 'TM422', 'an unknown category is refused');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', '   ', 1,
    'UNPAID', null, null, null)$$, :'today'), 'TM422', 'an empty description is refused');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', repeat('x', 201), 1,
    'UNPAID', null, null, null)$$, :'today'), 'TM422', 'a description over 200 characters is refused');
select test.expect_error($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), null, 'RENT', 'x', 1,
    'UNPAID', null, null, null)$$, 'TM422', 'the date is required');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', 1,
    'PAID', null, 'CASH', null)$$, :'today'), 'TM422', 'paid needs the payment date');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', 1,
    'PAID', %L::date + 5, 'CASH', null)$$, :'today', :'today'), 'TM422', 'a payment date in the future is refused');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', 1,
    'UNPAID', null, 'CASH', null)$$, :'today'), 'TM422', 'unpaid cannot have a payment method');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', 1,
    'PAID', %L, 'BITCOIN', null)$$, :'today', :'today'), 'TM422', 'an unknown payment method is refused');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', 1,
    'MAYBE', null, null, null)$$, :'today'), 'TM422', 'the status must be Paid or Unpaid');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, null, %L, 'RENT', 'x', 1,
    'UNPAID', null, null, null)$$, :'today'), 'TM422', 'a new expense needs a request id');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), %L, 'RENT', 'x', 1,
    'UNPAID', null, null, repeat('n', 501))$$, :'today'), 'TM422', 'notes over 500 characters are refused');

-- ================================================================== 4. Edit (audited, history kept)
select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), :'today', 'RENT', 'Store rent', 5000,
                                   'UNPAID', null, null, 'Due on the 30th') as e2 \gset
select trimax_private.save_expense('marc.admin', :e2, null, :'today', 'RENT', 'Store rent (discounted)', 4500,
                                   'UNPAID', null, null, 'Due on the 30th');
select test.ok((select amount = 4500 and description = 'Store rent (discounted)' and updated_by = 'marc.admin'
                       and updated_at is not null from expenses where expense_id = :e2),
               'an expense can be edited');
select test.ok((select count(*) = 1 from expense_history where expense_id = :e2 and action = 'UPDATE'
                   and (before_row ->> 'amount')::numeric = 5000 and (after_row ->> 'amount')::numeric = 4500
                   and changed_by = 'marc.admin')
               and (select count(*) = 1 from audit_log where action = 'expense.update' and target_id = :'e2'
                       and details like '%₱5,000.00 -> ₱4,500.00%'),
               'an edit keeps the amount before and after in the history and the audit log');
select test.expect_error($$select trimax_private.save_expense('marc.admin', 99999999, null, current_date, 'RENT', 'x', 1,
    'UNPAID', null, null, null)$$, 'TM404', 'editing an expense that does not exist');
-- Unpaid -> paid: the method and date are kept; paid -> unpaid clears them.
select trimax_private.save_expense('marc.admin', :e2, null, :'today', 'RENT', 'Store rent (discounted)', 4500,
                                   'PAID', :'today', 'BANK_TRANSFER', null);
select trimax_private.save_expense('marc.admin', :e2, null, :'today', 'RENT', 'Store rent (discounted)', 4500,
                                   'UNPAID', null, null, null);
select test.ok((select payment_status = 'UNPAID' and paid_at is null and payment_method is null and notes is null
                  from expenses where expense_id = :e2)
               and (select count(*) = 3 from expense_history where expense_id = :e2 and action = 'UPDATE'),
               'paid and back to unpaid: each change is one history line');

-- ================================================================== 5. Void (never deleted)
select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), :'today', 'FUEL', 'Diesel, truck 2', 300,
                                   'PAID', :'today', 'GCASH', null) as e3 \gset
select test.expect_error(format($$select trimax_private.void_expense('marc.admin', %s, 'no')$$, :e3),
                         'TM422', 'voiding needs a reason of at least 3 characters');
select test.expect_error(format($$select trimax_private.void_expense('agent.ana', %s, 'Entered twice')$$, :e3),
                         'TM403', 'only an Admin voids expenses');
select trimax_private.void_expense('marc.admin', :e3, 'Entered twice');
select test.ok((select voided_by = 'marc.admin' and voided_at is not null and void_reason = 'Entered twice'
                       and amount = 300 and payment_status = 'PAID' from expenses where expense_id = :e3),
               'a voided expense keeps its amount and payment, with who, when and why it was voided');
select test.ok((select count(*) = 1 from expense_history where expense_id = :e3 and action = 'VOID' and changed_by = 'marc.admin'
                   and after_row ->> 'void_reason' = 'Entered twice')
               and (select count(*) = 1 from audit_log where action = 'expense.void' and target_id = :'e3'
                       and details like '%Entered twice%'),
               'voiding is in the history and the audit log');
select test.expect_error(format($$select trimax_private.void_expense('marc.admin', %s, 'again')$$, :e3),
                         'TM409', 'an expense cannot be voided twice');
select test.expect_error(format($$select trimax_private.save_expense('marc.admin', %s, null, current_date, 'FUEL', 'x', 1,
    'UNPAID', null, null, null)$$, :e3), 'TM409', 'a voided expense cannot be edited');
select test.expect_error(format('update expenses set amount = 1 where expense_id = %s', :e3),
                         'TM409', 'a voided expense cannot be changed even directly by the owner');
select test.expect_error(format('delete from expenses where expense_id = %s', :e1),
                         'TM409', 'expenses cannot be deleted, even by the owner');
select test.expect_error(format('update expense_history set changed_by = %L where expense_id = %s', 'someone', :e1),
                         'TM409', 'the expense history cannot be changed');
select test.expect_error(format('delete from expense_history where expense_id = %s', :e1),
                         'TM409', 'the expense history cannot be deleted');
select test.expect_error(format('update expenses set created_by = %L where expense_id = %s', 'agent.ana', :e1),
                         'TM409', 'who entered an expense cannot be changed');
-- A change made outside the functions is still in the history.
update expenses set notes = 'Checked against the bill' where expense_id = :e1;
select test.ok((select count(*) = 1 from expense_history where expense_id = :e1 and action = 'UPDATE'
                   and after_row ->> 'notes' = 'Checked against the bill'),
               'a direct change by the owner is still written to the history');

-- An expense from 10 days ago: not in today's report, in a wider one.
select trimax_private.save_expense('marc.admin', null, gen_random_uuid(), :'today'::date - 10, 'WATER', 'Maynilad', 820,
                                   'UNPAID', null, null, null) as e4 \gset
reset role;

-- ================================================================== 6. Sales & Profit report
create table test.exp_after as select * from trimax_private.profit_summary(:'d0', :'d1');
select test.ok((select a.other_expenses - b.other_expenses = 6000.50
                       and a.other_expenses_paid - b.other_expenses_paid = 1500.50
                       and a.other_expenses_unpaid - b.other_expenses_unpaid = 4500
                       and a.other_expenses_count - b.other_expenses_count = 2
                  from test.exp_after a, test.exp_before b),
               'other expenses today: paid 1,500.50 + unpaid 4,500 = 6,000.50 (the voided fuel and the old water bill never)');
select test.ok((select a.gross_profit = b.gross_profit and a.payroll_paid = b.payroll_paid and a.payroll_unpaid = b.payroll_unpaid
                       and a.revenue_net = b.revenue_net and a.restock_cost = b.restock_cost
                       and a.net_profit - b.net_profit = -6000.50
                  from test.exp_after a, test.exp_before b),
               'net profit goes down by exactly the other expenses; sales, gross profit and payroll do not change');
select test.ok((select net_profit = gross_profit - payroll_paid - payroll_unpaid - other_expenses
                       and other_expenses = other_expenses_paid + other_expenses_unpaid
                  from test.exp_after),
               'net profit = gross profit - payroll - other expenses');
select test.ok((select a.other_expenses - b.other_expenses = 6820.50 and a.other_expenses_count - b.other_expenses_count = 3
                  from trimax_private.profit_summary(:'d0'::timestamptz - interval '15 days', :'d1') a,
                       (select 0::numeric as other_expenses, 0 as other_expenses_count) b),
               'a 16-day report includes the water bill from 10 days ago');
select test.ok((select other_expenses = 0 from trimax_private.profit_summary(:'d0'::timestamptz - interval '1 day', :'d0')),
               'the day before (Asia/Manila midnights) has no expenses: dates are compared in Manila time');
select test.ok((select sum(t.other_expenses) = s.other_expenses and sum(t.net_profit) = s.net_profit
                       and sum(t.payroll) = s.payroll_paid + s.payroll_unpaid and sum(t.revenue) = s.revenue_net
                  from trimax_private.profit_trend(:'d0'::timestamptz - interval '15 days', :'d1', 'day') t,
                       trimax_private.profit_summary(:'d0'::timestamptz - interval '15 days', :'d1') s
                 group by s.other_expenses, s.net_profit, s.payroll_paid, s.payroll_unpaid, s.revenue_net),
               'the daily trend adds up to the summary (expenses and net profit included)');
select test.ok((select sum(t.other_expenses) = s.other_expenses and sum(t.net_profit) = s.net_profit
                  from trimax_private.profit_trend(:'d0'::timestamptz - interval '40 days', :'d1', 'month') t,
                       trimax_private.profit_summary(:'d0'::timestamptz - interval '40 days', :'d1') s
                 group by s.other_expenses, s.net_profit),
               'the monthly trend adds up too');
select test.ok((select other_expenses = 820 from trimax_private.profit_trend(:'d0'::timestamptz - interval '15 days', :'d1', 'day')
                 where bucket_start = :'today'::date - 10),
               'the water bill is on its own day in the trend');
select test.ok((select other_expenses = 0 and other_expenses_count = 0 and net_profit = 0
                  from trimax_private.profit_summary(:'d1'::timestamptz + interval '10 days', :'d1'::timestamptz + interval '11 days')),
               'an empty period gives zeros, not errors');
drop table test.exp_before, test.exp_after;

select 'ALL EXPENSES SQL TESTS PASSED' as result;
\echo ALL EXPENSES SQL TESTS PASSED
