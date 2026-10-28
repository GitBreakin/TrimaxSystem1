-- =====================================================================================================
-- ROLLBACK of 20261028_04_bad_orders.sql (only if the owner decides to undo it).
--
-- Puts back profit_summary and profit_trend exactly as 20261027_02 made them (net profit = gross profit
-- - payroll - other expenses, no bad-order columns) and removes record_bad_order / void_bad_order.
-- KEEPS all data: public.bad_orders with every row and its protection (cannot be deleted; only undone),
-- every BAD_ORDER / BAD_ORDER_VOIDED line in the stock history, and the wider kind check that allows them.
-- Stock already taken out by a bad order stays out (a rollback never changes stock).
-- Re-applying 20261028_04 afterwards works and continues from the same rows.
-- After a rollback the Desktop's "Bad order..." button and Bad orders tab say the database update is
-- needed, and Sales & Profit shows net profit without bad orders.
-- =====================================================================================================

begin;

drop function if exists trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid);
drop function if exists trimax_private.void_bad_order(text, bigint, text);
drop function if exists trimax_private.bad_order_label(text);

-- ---- previous reports (sql/20261027_02_expenses.sql, section 3), unchanged
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

revoke all on function trimax_private.profit_summary(timestamptz, timestamptz) from public;
revoke all on function trimax_private.profit_trend(timestamptz, timestamptz, text) from public;

commit;
