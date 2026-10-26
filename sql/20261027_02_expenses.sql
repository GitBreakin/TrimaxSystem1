-- =====================================================================================================
-- Other expenses (electricity, rent, fuel, ...) and net profit after them.
--
-- Owner's request of 2026-10-27. Business rules (on top of 20261027_01):
--   * An expense has a date, a category, a description, an amount, Paid or Unpaid (with how and when it
--     was paid), notes and who entered it. A wrong one is VOIDED with a reason, never deleted.
--   * Net profit = gross profit - payroll - other expenses.
--     Other expenses count Paid + Unpaid (like payroll), by the expense date (a calendar date, compared
--     with the report range in Asia/Manila). Voided expenses never count.
--   * Every create, edit and void is kept in public.expense_history (append-only) and in audit_log.
--
-- What this adds (keeps every existing row; changes no existing value):
--   1. public.expenses + public.expense_history (append-only, written by a trigger on expenses)
--   2. Desktop functions trimax_private.save_expense, void_expense (owner only, Admin only)
--   3. trimax_private.profit_summary and profit_trend gain the other-expense columns AT THE END and
--      subtract them from net_profit. The return columns change, so both are dropped and created again
--      (same names, same arguments). The Desktop reads columns by name, so an older Desktop keeps working
--      (it shows the new, lower net profit without the expense line).
--   4. privileges: new tables invisible to the API roles; new functions owner only
--
-- ORDER: after 20261027_01. Re-runnable. Rollback: 20261027_92_rollback_expenses.sql.
-- APPLIED to production on 2026-10-27 (about 03:36 Asia/Manila) with the owner's approval, as migration
-- 20261027_02_expenses (one transaction). Before: dry run on a copy of production's rows (passwords and
-- contact details masked) changed no value. After: production's schema equals a database built from the
-- repository through this file (108/108 fingerprints), all 24 existing tables have the same row checksums,
-- and the report columns of 20261027_01 give the same results.
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.require_admin(text)') is null
       or to_regprocedure('trimax_private.audit_as(text,text,text,text,text)') is null
       or to_regprocedure('trimax_private.peso(numeric)') is null
       or to_regprocedure('trimax_private.booking_sale_time(timestamptz,text,timestamptz,timestamptz)') is null
       or to_regclass('public.payroll_records') is null then
        raise exception 'Apply 20261027_01_sales_inventory_payroll.sql first.';
    end if;
end $$;

-- ----------------------------------------------------------------------------------------------- 1
-- Tables

create table if not exists public.expenses (
    expense_id bigint generated always as identity primary key,
    expense_date date not null,
    category text not null,
    description text not null,
    amount numeric(12, 2) not null,
    payment_status text not null default 'UNPAID',
    paid_at date,
    payment_method text,
    notes text,
    created_by text not null references public.users (username) on update cascade,
    created_at timestamptz not null default now(),
    updated_by text references public.users (username) on update cascade,
    updated_at timestamptz,
    voided_by text references public.users (username) on update cascade,
    voided_at timestamptz,
    void_reason text,
    client_request_id uuid not null unique,
    constraint expenses_category_chk check (category in ('ELECTRICITY', 'WATER', 'RENT', 'FUEL', 'REPAIRS',
        'SUPPLIES', 'INTERNET_PHONE', 'TAXES_PERMITS', 'OTHER')),
    constraint expenses_description_chk check (char_length(btrim(description)) between 1 and 200),
    constraint expenses_amount_chk check (amount > 0),
    constraint expenses_payment_chk check (payment_status in ('UNPAID', 'PAID')
        and (payment_status <> 'PAID' or paid_at is not null)
        and (payment_status <> 'UNPAID' or (paid_at is null and payment_method is null))),
    constraint expenses_method_chk check (payment_method is null
        or payment_method in ('CASH', 'GCASH', 'BANK_TRANSFER', 'CHECK', 'OTHER')),
    constraint expenses_notes_chk check (notes is null or char_length(notes) <= 500),
    constraint expenses_void_chk check ((voided_at is null) = (voided_by is null)
        and (voided_at is null) = (void_reason is null))
);
create index if not exists expenses_date_idx on public.expenses (expense_date) where voided_at is null;
create index if not exists expenses_all_date_idx on public.expenses (expense_date desc, expense_id desc);

-- One line per create / edit / void: the row as it was before (null for a create) and after.
create table if not exists public.expense_history (
    history_id bigint generated always as identity primary key,
    expense_id bigint not null references public.expenses (expense_id),
    action text not null check (action in ('CREATE', 'UPDATE', 'VOID')),
    changed_by text not null,
    changed_at timestamptz not null default now(),
    before_row jsonb,
    after_row jsonb not null
);
create index if not exists expense_history_expense_idx on public.expense_history (expense_id, history_id);

-- Expenses are never deleted (void them); a voided expense is never changed again.
create or replace function trimax_private.expenses_guard()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if tg_op = 'DELETE' then
        raise exception using errcode = 'TM409', message = 'Expenses cannot be deleted. Void it instead.';
    end if;
    if old.voided_at is not null then
        raise exception using errcode = 'TM409', message = 'A voided expense cannot be changed.';
    end if;
    if new.expense_id <> old.expense_id or new.created_by <> old.created_by or new.created_at <> old.created_at
       or new.client_request_id <> old.client_request_id then
        raise exception using errcode = 'TM409', message = 'Who and when an expense was entered cannot be changed.';
    end if;
    return new;
end $function$;

drop trigger if exists trimax_expenses_guard on public.expenses;
create trigger trimax_expenses_guard before update or delete on public.expenses
    for each row execute function trimax_private.expenses_guard();

-- Every insert / update of an expense writes one history line (also changes made outside the functions).
create or replace function trimax_private.expenses_history()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if tg_op = 'INSERT' then
        insert into public.expense_history (expense_id, action, changed_by, before_row, after_row)
        values (new.expense_id, 'CREATE', new.created_by, null, to_jsonb(new));
    elsif to_jsonb(new) is distinct from to_jsonb(old) then
        insert into public.expense_history (expense_id, action, changed_by, before_row, after_row)
        values (new.expense_id,
                case when new.voided_at is not null then 'VOID' else 'UPDATE' end,
                coalesce(case when new.voided_at is not null then new.voided_by end, new.updated_by,
                         nullif(current_setting('trimax.actor', true), ''), current_user),
                to_jsonb(old), to_jsonb(new));
    end if;
    return null;
end $function$;

drop trigger if exists trimax_expenses_history on public.expenses;
create trigger trimax_expenses_history after insert or update on public.expenses
    for each row execute function trimax_private.expenses_history();

create or replace function trimax_private.expense_history_append_only()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    raise exception using errcode = 'TM409', message = 'Expense history cannot be changed or deleted.';
end $function$;

drop trigger if exists trimax_expense_history_append_only on public.expense_history;
create trigger trimax_expense_history_append_only before update or delete on public.expense_history
    for each row execute function trimax_private.expense_history_append_only();

-- ----------------------------------------------------------------------------------------------- 2
-- Desktop functions

-- Create (p_expense_id null; a retry with the same request id returns the first expense) or edit.
create or replace function trimax_private.save_expense(
    p_admin text, p_expense_id bigint, p_request_id uuid, p_expense_date date, p_category text,
    p_description text, p_amount numeric, p_payment_status text, p_paid_at date, p_payment_method text,
    p_notes text)
returns bigint
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_category text := upper(btrim(coalesce(p_category, '')));
    v_desc text := nullif(btrim(p_description), '');
    v_status text := upper(btrim(coalesce(p_payment_status, '')));
    v_method text := nullif(upper(btrim(coalesce(p_payment_method, ''))), '');
    v_notes text := nullif(btrim(p_notes), '');
    v_today date := (now() at time zone 'Asia/Manila')::date;
    v_old public.expenses;
    v_new public.expenses;
    v_other bigint;
begin
    perform trimax_private.require_admin(p_admin);
    if p_expense_id is null then
        if p_request_id is null then
            raise exception using errcode = 'TM422', message = 'Missing request id.';
        end if;
        perform pg_advisory_xact_lock(hashtextextended('trimax-expense-request:' || p_request_id::text, 0));
        select e.expense_id into v_other from public.expenses e where e.client_request_id = p_request_id;
        if v_other is not null then
            return v_other;
        end if;
    end if;
    if p_expense_date is null or p_expense_date < date '2000-01-01' or p_expense_date > v_today + 366 then
        raise exception using errcode = 'TM422', message = 'Enter the date of the expense.';
    end if;
    if v_category not in ('ELECTRICITY', 'WATER', 'RENT', 'FUEL', 'REPAIRS', 'SUPPLIES', 'INTERNET_PHONE',
                          'TAXES_PERMITS', 'OTHER') then
        raise exception using errcode = 'TM422', message = 'Choose a category.';
    end if;
    if v_desc is null or char_length(v_desc) > 200 then
        raise exception using errcode = 'TM422', message = 'Enter what the expense was for (up to 200 characters).';
    end if;
    if p_amount is null or p_amount <= 0 or p_amount > 10000000 or p_amount <> round(p_amount, 2) then
        raise exception using errcode = 'TM422', message = 'The amount must be more than 0, with at most 2 decimals.';
    end if;
    if v_status not in ('UNPAID', 'PAID') then
        raise exception using errcode = 'TM422', message = 'Choose Unpaid or Paid.';
    end if;
    if v_status = 'PAID' then
        if p_paid_at is null or p_paid_at > v_today + 1 or p_paid_at < date '2000-01-01' then
            raise exception using errcode = 'TM422', message = 'Enter the date it was paid (not in the future).';
        end if;
        if v_method is not null and v_method not in ('CASH', 'GCASH', 'BANK_TRANSFER', 'CHECK', 'OTHER') then
            raise exception using errcode = 'TM422', message = 'Choose how it was paid.';
        end if;
    elsif p_paid_at is not null or v_method is not null then
        raise exception using errcode = 'TM422', message = 'Payment date and method are only for paid expenses.';
    end if;
    if char_length(coalesce(v_notes, '')) > 500 then
        raise exception using errcode = 'TM422', message = 'The notes are too long (up to 500 characters).';
    end if;

    if p_expense_id is null then
        insert into public.expenses (expense_date, category, description, amount, payment_status, paid_at,
                                     payment_method, notes, created_by, client_request_id)
        values (p_expense_date, v_category, v_desc, p_amount, v_status,
                case when v_status = 'PAID' then p_paid_at end, case when v_status = 'PAID' then v_method end,
                v_notes, p_admin, p_request_id)
        returning * into v_new;
        perform trimax_private.audit_as(p_admin, 'expense.create', 'expense', v_new.expense_id::text,
            p_expense_date || ' ' || lower(v_category) || ' "' || v_desc || '": '
            || trimax_private.peso(p_amount) || ', ' || lower(v_status));
        return v_new.expense_id;
    end if;

    select * into v_old from public.expenses e where e.expense_id = p_expense_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'Expense not found.';
    end if;
    if v_old.voided_at is not null then
        raise exception using errcode = 'TM409', message = 'A voided expense cannot be changed.';
    end if;
    update public.expenses e
       set expense_date = p_expense_date, category = v_category, description = v_desc, amount = p_amount,
           payment_status = v_status, paid_at = case when v_status = 'PAID' then p_paid_at end,
           payment_method = case when v_status = 'PAID' then v_method end, notes = v_notes,
           updated_by = p_admin, updated_at = now()
     where e.expense_id = p_expense_id
    returning * into v_new;
    perform trimax_private.audit_as(p_admin, 'expense.update', 'expense', p_expense_id::text,
        v_new.expense_date || ' ' || lower(v_new.category) || ' "' || v_new.description || '": '
        || trimax_private.peso(v_old.amount) || ' -> ' || trimax_private.peso(v_new.amount)
        || ', ' || lower(v_old.payment_status) || ' -> ' || lower(v_new.payment_status)
        || case when v_old.expense_date <> v_new.expense_date
                then ' (date ' || v_old.expense_date || ' -> ' || v_new.expense_date || ')' else '' end
        || case when v_old.category <> v_new.category
                then ' (category ' || lower(v_old.category) || ' -> ' || lower(v_new.category) || ')' else '' end);
    return v_new.expense_id;
end $function$;

create or replace function trimax_private.void_expense(p_admin text, p_expense_id bigint, p_reason text)
returns void
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_reason text := nullif(btrim(p_reason), '');
    v_old public.expenses;
begin
    perform trimax_private.require_admin(p_admin);
    if v_reason is null or char_length(v_reason) < 3 or char_length(v_reason) > 300 then
        raise exception using errcode = 'TM422', message = 'Enter the reason for voiding (3 to 300 characters).';
    end if;
    select * into v_old from public.expenses e where e.expense_id = p_expense_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'Expense not found.';
    end if;
    if v_old.voided_at is not null then
        raise exception using errcode = 'TM409', message = 'This expense is already voided.';
    end if;
    update public.expenses e
       set voided_by = p_admin, voided_at = now(), void_reason = v_reason
     where e.expense_id = p_expense_id;
    perform trimax_private.audit_as(p_admin, 'expense.void', 'expense', p_expense_id::text,
        v_old.expense_date || ' ' || lower(v_old.category) || ' "' || v_old.description || '", '
        || trimax_private.peso(v_old.amount) || ' (was ' || lower(v_old.payment_status) || '): ' || v_reason);
end $function$;

-- ----------------------------------------------------------------------------------------------- 3
-- Reports (Desktop). Same as 20261027_01 plus other expenses; new columns at the end.
-- Ranges are [p_from, p_to): the Desktop passes midnights in Asia/Manila.

drop function if exists trimax_private.profit_summary(timestamptz, timestamptz);
create function trimax_private.profit_summary(p_from timestamptz, p_to timestamptz)
returns table (
    bookings_count integer, bookings_gross numeric, bookings_discount numeric, bookings_net numeric,
    bookings_cogs numeric, bookings_lines_no_cost integer,
    sales_count integer, sales_gross numeric, sales_discount numeric, sales_net numeric,
    sales_cogs numeric, sales_lines_no_cost integer,
    unpaid_sales_count integer, unpaid_sales_total numeric,
    revenue_gross numeric, discounts numeric, revenue_net numeric, cogs numeric, gross_profit numeric,
    payroll_paid numeric, payroll_unpaid numeric, payroll_count integer, net_profit numeric,
    restock_cost numeric, restock_count integer,
    other_expenses numeric, other_expenses_paid numeric, other_expenses_unpaid numeric,
    other_expenses_count integer)
language sql
stable
set search_path = public, pg_temp
as $function$
    with b as (
        select bk.booking_id, coalesce(bk.subtotal_amount, bk.total_amount + bk.discount_amount) as gross,
               bk.discount_amount as disc, bk.total_amount as net
          from public.bookings bk
         where bk.status = 'Completed'
           and trimax_private.booking_sale_time(bk.completed_at, bk.delivery_status, bk.delivery_status_at, bk.transc_date) >= p_from
           and trimax_private.booking_sale_time(bk.completed_at, bk.delivery_status, bk.delivery_status_at, bk.transc_date) < p_to
    ), bt as (
        select count(*)::int as n, coalesce(sum(gross), 0) as gross, coalesce(sum(disc), 0) as disc,
               coalesce(sum(net), 0) as net from b
    ), bc as (
        select coalesce(sum(i.quantity * i.unit_cost) filter (where i.unit_cost is not null), 0) as cogs,
               count(*) filter (where i.unit_cost is null)::int as no_cost
          from public.booking_items i join b on b.booking_id = i.booking_id
    ), s as (
        select x.sale_id, x.subtotal_amount, x.discount_amount, x.total_amount
          from public.sales x
         where x.status = 'COMPLETED' and x.payment_status = 'PAID' and x.sold_at >= p_from and x.sold_at < p_to
    ), st as (
        select count(*)::int as n, coalesce(sum(subtotal_amount), 0) as gross, coalesce(sum(discount_amount), 0) as disc,
               coalesce(sum(total_amount), 0) as net from s
    ), sc as (
        select coalesce(sum(i.quantity * i.unit_cost) filter (where i.unit_cost is not null), 0) as cogs,
               count(*) filter (where i.unit_cost is null)::int as no_cost
          from public.sale_items i join s on s.sale_id = i.sale_id
    ), u as (
        select count(*)::int as n, coalesce(sum(x.total_amount), 0) as total
          from public.sales x
         where x.status = 'COMPLETED' and x.payment_status = 'UNPAID' and x.sold_at >= p_from and x.sold_at < p_to
    ), pr as (
        select coalesce(sum(r.net_pay) filter (where r.status = 'PAID'), 0) as paid,
               coalesce(sum(r.net_pay) filter (where r.status = 'UNPAID'), 0) as unpaid, count(*)::int as n
          from public.payroll_records r
         where r.status <> 'VOID'
           and r.period_end >= (p_from at time zone 'Asia/Manila')::date
           and r.period_end <= ((p_to - interval '1 microsecond') at time zone 'Asia/Manila')::date
    ), rs as (
        select coalesce(sum(m.total_cost), 0) as cost, count(*)::int as n
          from public.inventory_movements m
         where m.kind = 'RESTOCK' and m.occurred_at >= p_from and m.occurred_at < p_to
    ), ex as (
        select coalesce(sum(e.amount) filter (where e.payment_status = 'PAID'), 0) as paid,
               coalesce(sum(e.amount) filter (where e.payment_status = 'UNPAID'), 0) as unpaid, count(*)::int as n
          from public.expenses e
         where e.voided_at is null
           and e.expense_date >= (p_from at time zone 'Asia/Manila')::date
           and e.expense_date <= ((p_to - interval '1 microsecond') at time zone 'Asia/Manila')::date
    )
    select bt.n, bt.gross, bt.disc, bt.net, bc.cogs, bc.no_cost,
           st.n, st.gross, st.disc, st.net, sc.cogs, sc.no_cost,
           u.n, u.total,
           bt.gross + st.gross, bt.disc + st.disc, bt.net + st.net, bc.cogs + sc.cogs,
           bt.net + st.net - bc.cogs - sc.cogs,
           pr.paid, pr.unpaid, pr.n,
           bt.net + st.net - bc.cogs - sc.cogs - pr.paid - pr.unpaid - ex.paid - ex.unpaid,
           rs.cost, rs.n,
           ex.paid + ex.unpaid, ex.paid, ex.unpaid, ex.n
      from bt, bc, st, sc, u, pr, rs, ex
$function$;

-- Per day or per month (p_bucket 'day' | 'month'), every bucket in the range listed (0 when empty).
drop function if exists trimax_private.profit_trend(timestamptz, timestamptz, text);
create function trimax_private.profit_trend(p_from timestamptz, p_to timestamptz, p_bucket text)
returns table (bucket_start date, revenue numeric, cogs numeric, gross_profit numeric, payroll numeric,
               net_profit numeric, other_expenses numeric)
language sql
stable
set search_path = public, pg_temp
as $function$
    with params as (
        select case when p_bucket = 'month' then 'month' else 'day' end as unit,
               (p_from at time zone 'Asia/Manila')::date as d0,
               ((p_to - interval '1 microsecond') at time zone 'Asia/Manila')::date as d1
    ), buckets as (
        select g::date as b
          from params, generate_series(date_trunc(params.unit, params.d0::timestamp),
                                       date_trunc(params.unit, params.d1::timestamp),
                                       ('1 ' || params.unit)::interval) g
    ), lines as (
        select date_trunc(params.unit, (trimax_private.booking_sale_time(bk.completed_at, bk.delivery_status,
                          bk.delivery_status_at, bk.transc_date) at time zone 'Asia/Manila'))::date as b,
               bk.total_amount as revenue,
               (select coalesce(sum(i.quantity * i.unit_cost), 0) from public.booking_items i
                 where i.booking_id = bk.booking_id and i.unit_cost is not null) as cogs
          from public.bookings bk, params
         where bk.status = 'Completed'
           and trimax_private.booking_sale_time(bk.completed_at, bk.delivery_status, bk.delivery_status_at, bk.transc_date) >= p_from
           and trimax_private.booking_sale_time(bk.completed_at, bk.delivery_status, bk.delivery_status_at, bk.transc_date) < p_to
        union all
        select date_trunc(params.unit, (x.sold_at at time zone 'Asia/Manila'))::date, x.total_amount,
               (select coalesce(sum(i.quantity * i.unit_cost), 0) from public.sale_items i
                 where i.sale_id = x.sale_id and i.unit_cost is not null)
          from public.sales x, params
         where x.status = 'COMPLETED' and x.payment_status = 'PAID' and x.sold_at >= p_from and x.sold_at < p_to
    ), pay as (
        select date_trunc(params.unit, r.period_end::timestamp)::date as b, sum(r.net_pay) as amount
          from public.payroll_records r, params
         where r.status <> 'VOID' and r.period_end between params.d0 and params.d1
         group by 1
    ), ox as (
        select date_trunc(params.unit, e.expense_date::timestamp)::date as b, sum(e.amount) as amount
          from public.expenses e, params
         where e.voided_at is null and e.expense_date between params.d0 and params.d1
         group by 1
    ), agg as (
        select l.b, sum(l.revenue) as revenue, sum(l.cogs) as cogs from lines l group by l.b
    )
    select bk.b, coalesce(a.revenue, 0), coalesce(a.cogs, 0), coalesce(a.revenue, 0) - coalesce(a.cogs, 0),
           coalesce(p.amount, 0),
           coalesce(a.revenue, 0) - coalesce(a.cogs, 0) - coalesce(p.amount, 0) - coalesce(x.amount, 0),
           coalesce(x.amount, 0)
      from buckets bk
      left join agg a on a.b = bk.b
      left join pay p on p.b = bk.b
      left join ox x on x.b = bk.b
     order by bk.b
$function$;

-- ----------------------------------------------------------------------------------------------- 4
-- Privileges: owner only (RLS on, no policies, no grants to the API roles), like every other TRIMAX table.

alter table public.expenses enable row level security;
alter table public.expense_history enable row level security;
revoke all on table public.expenses, public.expense_history from public, anon, authenticated, service_role;
do $$
declare
    v_seq text;
begin
    for v_seq in select pg_get_serial_sequence('public.' || t, c)
                   from (values ('expenses', 'expense_id'), ('expense_history', 'history_id')) v(t, c) loop
        execute 'revoke all on sequence ' || v_seq || ' from public, anon, authenticated, service_role';
    end loop;
end $$;

revoke all on function trimax_private.expenses_guard() from public;
revoke all on function trimax_private.expenses_history() from public;
revoke all on function trimax_private.expense_history_append_only() from public;
revoke all on function trimax_private.save_expense(text, bigint, uuid, date, text, text, numeric, text, date, text, text) from public;
revoke all on function trimax_private.void_expense(text, bigint, text) from public;
revoke all on function trimax_private.profit_summary(timestamptz, timestamptz) from public;
revoke all on function trimax_private.profit_trend(timestamptz, timestamptz, text) from public;

commit;
