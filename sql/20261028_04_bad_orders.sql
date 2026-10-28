-- =====================================================================================================
-- Bad orders (BO): broken, damaged, expired, defective or spoiled stock (owner's request of 2026-10-28).
--
-- Rules confirmed by the owner:
--   * The Admin records a bad order in Inventory: product, quantity, reason (Broken, Damaged, Expired,
--     Defective, Spoiled, Other), what happened to it (Thrown away / Returned to supplier), the date it was
--     found, and a note (required for Other). The stock goes down by that quantity at once.
--   * The loss is the puhunan of those pieces (quantity x the product's puhunan at that moment). A product
--     without a puhunan is recorded with no peso loss (counted, and shown as "no puhunan").
--   * The loss is SUBTRACTED from net profit in Sales & Profit, by the date found:
--       net profit = gross profit - payroll - other expenses - bad orders (puhunan lost).
--   * A mistake is undone (VOIDED) with a reason: the stock comes back and the loss no longer counts.
--     Bad orders are never deleted. Everything is in Stock history and in the audit log.
--
-- What this changes (keeps every existing row; changes no existing value):
--   1. inventory_movements: two more kinds, BAD_ORDER and BAD_ORDER_VOIDED (the check constraint is
--      replaced by the same list plus these two).
--   2. public.bad_orders (RLS on, no API grants), guarded: no delete; only the movement link and the void
--      fields can be filled in, once.
--   3. Owner-only Desktop functions trimax_private.record_bad_order (Active Admin, same request id = same
--      bad order, no double stock-out) and void_bad_order.
--   4. trimax_private.profit_summary and profit_trend gain the bad-order columns AT THE END and subtract them
--      from net_profit (dropped and created again like in 20261027_02; the Desktop reads columns by name,
--      so an older Desktop keeps working and shows the new, lower net profit without the BO line).
--
-- ORDER: after 20261027_02 (expenses). Re-runnable. Rollback: 20261028_94_rollback_bad_orders.sql.
-- If 20261027_02 is ever run again after this file, run this file again too (02 re-creates the reports
-- without the bad-order columns).
-- NOT APPLIED to production. Needs the owner's approval first.
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.set_stock_context(text,text,text,text,text,numeric,uuid)') is null
       or to_regclass('public.expenses') is null
       or to_regprocedure('trimax_private.profit_summary(timestamptz,timestamptz)') is null then
        raise exception 'Apply 20261027_01 and 20261027_02 first.';
    end if;
end $$;

-- ----------------------------------------------------------------------------------------------- 1
alter table public.inventory_movements drop constraint if exists inventory_movements_kind_chk;
alter table public.inventory_movements add constraint inventory_movements_kind_chk check (kind in (
    'INITIAL', 'RESTOCK', 'ADJUSTMENT', 'BOOKING', 'BOOKING_CANCELLED', 'BOOKING_CHANGED', 'SALE', 'SALE_VOIDED',
    'BAD_ORDER', 'BAD_ORDER_VOIDED'));

-- ----------------------------------------------------------------------------------------------- 2
create table if not exists public.bad_orders (
    bad_order_id       bigint generated always as identity primary key,
    product_id         integer references public.products (product_id) on delete set null,
    product_name       text not null,                       -- as it was named when recorded
    supplier           text,
    quantity           integer not null check (quantity between 1 and 1000000),
    reason             text not null check (reason in ('BROKEN', 'DAMAGED', 'EXPIRED', 'DEFECTIVE', 'SPOILED', 'OTHER')),
    disposition        text not null check (disposition in ('DISPOSED', 'RETURNED_TO_SUPPLIER')),
    found_on           date not null,
    unit_cost          numeric(12, 2) check (unit_cost is null or unit_cost >= 0),       -- puhunan then
    loss_amount        numeric(14, 2) check (loss_amount is null or loss_amount >= 0),   -- quantity x unit_cost
    note               text check (note is null or char_length(note) <= 300),
    movement_id        bigint references public.inventory_movements (movement_id),
    recorded_by        text references public.users (username) on update cascade on delete set null,
    recorded_at        timestamptz not null default now(),
    client_request_id  uuid unique,
    voided_at          timestamptz,
    voided_by          text references public.users (username) on update cascade on delete set null,
    void_reason        text,
    constraint bad_orders_loss_chk check ((unit_cost is null) = (loss_amount is null)),
    constraint bad_orders_void_chk check ((voided_at is null) = (void_reason is null)),
    constraint bad_orders_other_chk check (reason <> 'OTHER' or char_length(btrim(coalesce(note, ''))) >= 3)
);
create index if not exists bad_orders_found_idx on public.bad_orders (found_on desc);
create index if not exists bad_orders_product_idx on public.bad_orders (product_id, found_on desc);
alter table public.bad_orders enable row level security;
revoke all on table public.bad_orders from public, anon, authenticated, service_role;
do $$
begin
    execute 'revoke all on sequence ' || pg_get_serial_sequence('public.bad_orders', 'bad_order_id')
            || ' from public, anon, authenticated, service_role';
end $$;
comment on table public.bad_orders is 'Bad orders (BO): broken, damaged, expired, defective or spoiled stock taken out of inventory.';

create or replace function trimax_private.bad_orders_guard()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if tg_op = 'DELETE' then
        raise exception using errcode = 'TM409', message = 'Bad orders cannot be deleted. Undo (void) it instead.';
    end if;
    -- Allowed: the movement link and the undo (void) fields filled in once; product_id emptied when the
    -- product is deleted; recorded_by / voided_by following a renamed or deleted user. Nothing else.
    if old.voided_at is not null
       and (new.voided_at is distinct from old.voided_at or new.void_reason is distinct from old.void_reason) then
        raise exception using errcode = 'TM409', message = 'This bad order was already undone.';
    end if;
    if (to_jsonb(new) - array['movement_id', 'voided_at', 'voided_by', 'void_reason', 'product_id', 'recorded_by'])
           is distinct from
       (to_jsonb(old) - array['movement_id', 'voided_at', 'voided_by', 'void_reason', 'product_id', 'recorded_by'])
       or (old.movement_id is not null and new.movement_id is distinct from old.movement_id)
       or (new.product_id is distinct from old.product_id and new.product_id is not null)
       or (old.voided_at is null and new.voided_at is null and new.void_reason is not null) then
        raise exception using errcode = 'TM409', message = 'A bad order cannot be changed. Undo it and record it again.';
    end if;
    return new;
end $function$;

drop trigger if exists trimax_bad_orders_guard on public.bad_orders;
create trigger trimax_bad_orders_guard before update or delete on public.bad_orders
    for each row execute function trimax_private.bad_orders_guard();

-- ----------------------------------------------------------------------------------------------- 3
create or replace function trimax_private.bad_order_label(p_reason text)
returns text
language sql
immutable
set search_path = public, pg_temp
as $function$
    select case p_reason when 'BROKEN' then 'Broken' when 'DAMAGED' then 'Damaged' when 'EXPIRED' then 'Expired'
                         when 'DEFECTIVE' then 'Defective' when 'SPOILED' then 'Spoiled' else 'Other' end
$function$;

create or replace function trimax_private.record_bad_order(
    p_admin text, p_product_id integer, p_quantity integer, p_reason text, p_disposition text,
    p_found_on date, p_note text, p_request_id uuid)
returns table (bad_order_id bigint, stock_before integer, stock_after integer, loss_amount numeric, already_existed boolean)
language plpgsql
set search_path = public, pg_temp
as $function$
#variable_conflict use_column
declare
    v_reason text := upper(btrim(coalesce(p_reason, '')));
    v_disp text := upper(btrim(coalesce(p_disposition, '')));
    v_note text := nullif(btrim(p_note), '');
    v_today date := (now() at time zone 'Asia/Manila')::date;
    v_found date := coalesce(p_found_on, (now() at time zone 'Asia/Manila')::date);
    v_product public.products;
    v_bo public.bad_orders;
    v_mov public.inventory_movements;
begin
    perform trimax_private.require_admin(p_admin);
    if p_request_id is null then
        raise exception using errcode = 'TM422', message = 'Missing request id.';
    end if;
    perform pg_advisory_xact_lock(hashtextextended('trimax-stock-request:' || p_request_id::text, 0));
    select * into v_bo from public.bad_orders b where b.client_request_id = p_request_id;
    if found then
        if v_bo.product_id is distinct from p_product_id or v_bo.quantity <> p_quantity then
            raise exception using errcode = 'TM409', message = 'This request id has already been used.';
        end if;
        select * into v_mov from public.inventory_movements m where m.movement_id = v_bo.movement_id;
        return query select v_bo.bad_order_id, v_mov.stock_before, v_mov.stock_after, v_bo.loss_amount, true;
        return;
    end if;
    if p_quantity is null or p_quantity < 1 or p_quantity > 1000000 then
        raise exception using errcode = 'TM422', message = 'Enter how many pieces are bad (1 or more).';
    end if;
    if v_reason not in ('BROKEN', 'DAMAGED', 'EXPIRED', 'DEFECTIVE', 'SPOILED', 'OTHER') then
        raise exception using errcode = 'TM422', message = 'Choose the reason (broken, damaged, expired, defective, spoiled or other).';
    end if;
    if v_disp not in ('DISPOSED', 'RETURNED_TO_SUPPLIER') then
        raise exception using errcode = 'TM422', message = 'Choose what happened to it (thrown away or returned to supplier).';
    end if;
    if v_reason = 'OTHER' and char_length(coalesce(v_note, '')) < 3 then
        raise exception using errcode = 'TM422', message = 'Describe the reason in the note (3 to 300 characters).';
    end if;
    if char_length(coalesce(v_note, '')) > 300 then
        raise exception using errcode = 'TM422', message = 'The note is too long (up to 300 characters).';
    end if;
    if v_found > v_today or v_found < v_today - 366 then
        raise exception using errcode = 'TM422', message = 'The date found must be today or within the last year.';
    end if;
    select * into v_product from public.products p where p.product_id = p_product_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'That product no longer exists.';
    end if;
    if p_quantity > v_product.stock then
        raise exception using errcode = 'TM409',
            message = 'Only ' || v_product.stock || ' in stock: a bad order cannot take out more than that.';
    end if;

    insert into public.bad_orders (product_id, product_name, supplier, quantity, reason, disposition, found_on,
                                   unit_cost, loss_amount, note, recorded_by, client_request_id)
    values (v_product.product_id, v_product.product_name, v_product.supplier, p_quantity, v_reason, v_disp, v_found,
            v_product.capital_cost, round(p_quantity * v_product.capital_cost, 2), v_note, p_admin, p_request_id)
    returning * into v_bo;

    perform trimax_private.set_stock_context('BAD_ORDER', 'bad_order', v_bo.bad_order_id::text, p_admin,
        'Bad order: ' || lower(trimax_private.bad_order_label(v_reason))
        || case when v_disp = 'RETURNED_TO_SUPPLIER' then ', returned to supplier' else ', thrown away' end
        || coalesce(' - ' || v_note, ''),
        v_product.capital_cost, p_request_id);
    update public.products p set stock = p.stock - p_quantity where p.product_id = p_product_id;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);

    select * into v_mov from public.inventory_movements m where m.client_request_id = p_request_id;
    update public.bad_orders b set movement_id = v_mov.movement_id where b.bad_order_id = v_bo.bad_order_id;

    perform trimax_private.audit_as(p_admin, 'inventory.bad_order', 'product', p_product_id::text,
        v_product.product_name || ': ' || p_quantity || ' ' || lower(trimax_private.bad_order_label(v_reason))
        || ' (found ' || to_char(v_found, 'Mon DD, YYYY') || ', '
        || case when v_disp = 'RETURNED_TO_SUPPLIER' then 'returned to supplier' else 'thrown away' end || '), stock '
        || v_mov.stock_before || ' -> ' || v_mov.stock_after
        || coalesce(', loss ' || trimax_private.peso(v_bo.loss_amount), ', no puhunan') || coalesce(' - ' || v_note, ''));
    return query select v_bo.bad_order_id, v_mov.stock_before, v_mov.stock_after, v_bo.loss_amount, false;
end $function$;

create or replace function trimax_private.void_bad_order(p_admin text, p_bad_order_id bigint, p_reason text)
returns table (stock_before integer, stock_after integer)
language plpgsql
set search_path = public, pg_temp
as $function$
#variable_conflict use_column
declare
    v_reason text := nullif(btrim(p_reason), '');
    v_bo public.bad_orders;
    v_before integer;
    v_after integer;
begin
    perform trimax_private.require_admin(p_admin);
    if v_reason is null or char_length(v_reason) < 3 or char_length(v_reason) > 300 then
        raise exception using errcode = 'TM422', message = 'Enter why it is undone (3 to 300 characters).';
    end if;
    select * into v_bo from public.bad_orders b where b.bad_order_id = p_bad_order_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'Bad order not found.';
    end if;
    if v_bo.voided_at is not null then
        raise exception using errcode = 'TM409', message = 'This bad order was already undone.';
    end if;
    if v_bo.product_id is null then
        raise exception using errcode = 'TM409', message = 'The product was deleted, so its stock cannot be put back.';
    end if;
    perform 1 from public.products p where p.product_id = v_bo.product_id for update;
    perform trimax_private.set_stock_context('BAD_ORDER_VOIDED', 'bad_order', v_bo.bad_order_id::text, p_admin,
        'Bad order undone: ' || v_reason, v_bo.unit_cost, null);
    update public.products p set stock = p.stock + v_bo.quantity where p.product_id = v_bo.product_id
    returning p.stock - v_bo.quantity, p.stock into v_before, v_after;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
    update public.bad_orders b set voided_at = now(), voided_by = p_admin, void_reason = v_reason
     where b.bad_order_id = p_bad_order_id;
    perform trimax_private.audit_as(p_admin, 'inventory.bad_order_void', 'product', v_bo.product_id::text,
        v_bo.product_name || ': bad order #' || v_bo.bad_order_id || ' of ' || v_bo.quantity || ' undone, stock '
        || v_before || ' -> ' || v_after || ' (' || v_reason || ')');
    return query select v_before, v_after;
end $function$;

-- ----------------------------------------------------------------------------------------------- 4
-- Reports: same as 20261027_02 plus bad orders (by the date found, voided ones never count); new columns
-- at the end. Ranges are [p_from, p_to): the Desktop passes midnights in Asia/Manila.

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
    other_expenses_count integer,
    bad_orders_loss numeric, bad_orders_count integer, bad_orders_no_cost integer)
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
    ), bo as (
        select coalesce(sum(o.loss_amount), 0) as loss, count(*)::int as n,
               count(*) filter (where o.loss_amount is null)::int as no_cost
          from public.bad_orders o
         where o.voided_at is null
           and o.found_on >= (p_from at time zone 'Asia/Manila')::date
           and o.found_on <= ((p_to - interval '1 microsecond') at time zone 'Asia/Manila')::date
    )
    select bt.n, bt.gross, bt.disc, bt.net, bc.cogs, bc.no_cost,
           st.n, st.gross, st.disc, st.net, sc.cogs, sc.no_cost,
           u.n, u.total,
           bt.gross + st.gross, bt.disc + st.disc, bt.net + st.net, bc.cogs + sc.cogs,
           bt.net + st.net - bc.cogs - sc.cogs,
           pr.paid, pr.unpaid, pr.n,
           bt.net + st.net - bc.cogs - sc.cogs - pr.paid - pr.unpaid - ex.paid - ex.unpaid - bo.loss,
           rs.cost, rs.n,
           ex.paid + ex.unpaid, ex.paid, ex.unpaid, ex.n,
           bo.loss, bo.n, bo.no_cost
      from bt, bc, st, sc, u, pr, rs, ex, bo
$function$;

drop function if exists trimax_private.profit_trend(timestamptz, timestamptz, text);
create function trimax_private.profit_trend(p_from timestamptz, p_to timestamptz, p_bucket text)
returns table (bucket_start date, revenue numeric, cogs numeric, gross_profit numeric, payroll numeric,
               net_profit numeric, other_expenses numeric, bad_orders numeric)
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
    ), bx as (
        select date_trunc(params.unit, o.found_on::timestamp)::date as b, sum(coalesce(o.loss_amount, 0)) as amount
          from public.bad_orders o, params
         where o.voided_at is null and o.found_on between params.d0 and params.d1
         group by 1
    ), agg as (
        select l.b, sum(l.revenue) as revenue, sum(l.cogs) as cogs from lines l group by l.b
    )
    select bk.b, coalesce(a.revenue, 0), coalesce(a.cogs, 0), coalesce(a.revenue, 0) - coalesce(a.cogs, 0),
           coalesce(p.amount, 0),
           coalesce(a.revenue, 0) - coalesce(a.cogs, 0) - coalesce(p.amount, 0) - coalesce(x.amount, 0) - coalesce(o.amount, 0),
           coalesce(x.amount, 0),
           coalesce(o.amount, 0)
      from buckets bk
      left join agg a on a.b = bk.b
      left join pay p on p.b = bk.b
      left join ox x on x.b = bk.b
      left join bx o on o.b = bk.b
     order by bk.b
$function$;

revoke all on function trimax_private.bad_orders_guard() from public;
revoke all on function trimax_private.bad_order_label(text) from public;
revoke all on function trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid) from public;
revoke all on function trimax_private.void_bad_order(text, bigint, text) from public;
revoke all on function trimax_private.profit_summary(timestamptz, timestamptz) from public;
revoke all on function trimax_private.profit_trend(timestamptz, timestamptz, text) from public;

commit;
