-- =====================================================================================================
-- 1. The Admin changes a booking's order after it was booked (owner's request: "pwede sila mag update ng
--    order kahit na book na", e.g. the customer calls to remove the Sprite).
-- 2. The driver app's dashboard shows real numbers (deliveries, earnings) instead of sample figures.
--
-- This adds:
--   * trimax_private.booking_items_signature(booking): a fingerprint of the booking's status and lines, so a
--     change made by someone else after the Admin opened the booking is caught (TM409, nothing changes).
--   * trimax_private.update_booking_items(admin, booking, expected signature, items, note): Desktop only
--     (database owner, like the other trimax_private functions). Only an active Admin; only a Pending,
--     Confirmed or In Progress booking (a Completed one is already counted in Sales & Profit; a Cancelled
--     one gave its stock back). The new list replaces the old one, 1 to 50 lines of product + unit (WHOLE /
--     HALF) + quantity 1-1000:
--       - a line kept keeps its price and the agent's discount per piece (only the quantity changes);
--       - a new line is priced now (the product's price, or its half case price);
--       - a line left out is removed (to remove every line, cancel the booking instead).
--     Stock moves by the difference, in this transaction (stock history kind BOOKING_CHANGED, which the
--     stock history already names "Booking changed"); more than the stock left is refused. The totals are
--     recomputed by the existing trigger (discounts kept). Audit "booking.items_change" lists what changed
--     and the old and new total.
--   * public.trimax_driver_summary(): for the signed-in driver only. Delivered today and this month (count
--     and amount, Manila time), deliveries still to do, and pay from Payroll (this month's net pay of
--     records not voided, and the last 6 records).
--
-- Phones and older Desktops are not affected: no table, column or existing function changes.
-- ORDER: after 20261029_02. Re-runnable. Adds 3 functions. Changes no rows.
-- Rollback: 20261029_97 (drops the 3 functions; changes made with them stay).
-- =====================================================================================================

begin;

-- ----------------------------------------------------------------------------------------------- 1
create or replace function trimax_private.booking_items_signature(p_booking_id integer)
returns text
language sql
stable
set search_path = public, pg_temp
as $function$
    select md5(coalesce((select b.status from public.bookings b where b.booking_id = p_booking_id), '-') || '|'
               || coalesce(string_agg(i.product_id || ':' || i.unit || ':' || i.quantity || ':' || i.unit_price
                                      || ':' || i.discount_per_unit, ',' order by i.product_id, i.unit), ''))
      from public.booking_items i
     where i.booking_id = p_booking_id
$function$;

-- ----------------------------------------------------------------------------------------------- 2
create or replace function trimax_private.update_booking_items(p_admin text, p_booking_id integer,
                                                               p_expected text, p_items jsonb,
                                                               p_note text default null)
returns jsonb
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_status text;
    v_note text := nullif(btrim(coalesce(p_note, '')), '');
    v_bad int;
    v_ids int[];
    v_units text[];
    v_qtys int[];
    v_line record;
    v_old_total numeric;
    v_new_total numeric;
    v_changes text := '';
    v_move_ids int[];
    v_move_changes numeric[];
    v_moved int;
begin
    if not exists (select 1 from public.users u join public.roles r on r.role_id = u.role_id
                    where u.username = p_admin and r.role_name = 'Admin' and u.account_status = 'Active') then
        raise exception using errcode = 'TM403', message = 'Only an active Admin can change the order of a booking.';
    end if;

    select b.status, b.total_amount into v_status, v_old_total
      from public.bookings b where b.booking_id = p_booking_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'This booking no longer exists.';
    end if;
    if v_status not in ('Pending', 'Confirmed', 'In Progress') then
        raise exception using errcode = 'TM422',
            message = 'Only a Pending, Confirmed or In Progress booking can be changed. This booking is ' || v_status || '.';
    end if;
    if p_expected is null or p_expected is distinct from trimax_private.booking_items_signature(p_booking_id) then
        raise exception using errcode = 'TM409',
            message = 'This booking was changed by someone else after you opened it. It has been reloaded; check it and try again.';
    end if;
    if char_length(coalesce(v_note, '')) > 300 then
        raise exception using errcode = 'TM422', message = 'The note is too long (300 characters at most).';
    end if;

    -- ---- the new list
    if p_items is null or jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
        raise exception using errcode = 'TM422',
            message = 'Keep at least one item. To remove every item, cancel the booking instead.';
    end if;
    if jsonb_array_length(p_items) > 50 then
        raise exception using errcode = 'TM422', message = 'A booking can have at most 50 lines.';
    end if;
    select count(*) into v_bad
      from jsonb_array_elements(p_items) e
     where jsonb_typeof(e) <> 'object'
        or coalesce(e ->> 'product_id', '') !~ '^[0-9]{1,9}$'
        or coalesce(e ->> 'quantity', '') !~ '^[0-9]{1,4}$'
        or coalesce(e ->> 'unit', 'WHOLE') not in ('WHOLE', 'HALF');
    if v_bad > 0 then
        raise exception using errcode = 'TM422',
            message = 'Each line needs a product, a whole-number quantity and the unit (whole or half case).';
    end if;
    select array_agg(s.product_id order by s.product_id, s.unit desc), array_agg(s.unit order by s.product_id, s.unit desc),
           array_agg(s.quantity order by s.product_id, s.unit desc)
      into v_ids, v_units, v_qtys
      from (select (e ->> 'product_id')::bigint as product_id, coalesce(e ->> 'unit', 'WHOLE') as unit,
                   sum((e ->> 'quantity')::int) as quantity
              from jsonb_array_elements(p_items) e group by 1, 2) s;
    if exists (select 1 from unnest(v_qtys) q where q < 1 or q > 1000) then
        raise exception using errcode = 'TM422', message = 'Each quantity must be between 1 and 1000.';
    end if;
    if exists (select 1 from unnest(v_ids) i where i > 2147483647
                  or not exists (select 1 from public.products p where p.product_id = i)) then
        raise exception using errcode = 'TM404', message = 'A product in the list no longer exists. Reload the booking.';
    end if;
    -- a new half case line needs the product's half case price (a half case line already on the booking keeps its price)
    select p.product_name into v_line
      from unnest(v_ids, v_units) n(product_id, unit)
      join public.products p on p.product_id = n.product_id
     where n.unit = 'HALF' and p.half_price is null
       and not exists (select 1 from public.booking_items i
                        where i.booking_id = p_booking_id and i.product_id = n.product_id and i.unit = 'HALF')
     limit 1;
    if found then
        raise exception using errcode = 'TM422',
            message = '"' || v_line.product_name || '" is not sold by the half case (no half case price).';
    end if;

    -- ---- stock: lock every product involved (fixed order), then check what more is needed
    perform 1 from public.products p
      where p.product_id in (select unnest(v_ids) union select i.product_id from public.booking_items i where i.booking_id = p_booking_id)
      order by p.product_id for update;
    -- per product: (new need) - (what the booking already took); > 0 takes more stock, < 0 gives it back
    select coalesce(array_agg(m.product_id order by m.product_id), '{}'), coalesce(array_agg(m.change order by m.product_id), '{}')
      into v_move_ids, v_move_changes
      from (select x.product_id, sum(x.need) as change
              from (select n.product_id, n.quantity * trimax_private.unit_factor(n.unit) as need
                      from unnest(v_ids, v_units, v_qtys) n(product_id, unit, quantity)
                    union all
                    select i.product_id, -i.quantity * trimax_private.unit_factor(i.unit)
                      from public.booking_items i where i.booking_id = p_booking_id) x
             group by x.product_id
            having sum(x.need) <> 0) m;
    for v_line in
        select p.product_name, p.stock, m.change from unnest(v_move_ids, v_move_changes) m(product_id, change)
          join public.products p on p.product_id = m.product_id
         where m.change > 0 and p.stock < m.change order by p.product_id
    loop
        raise exception using errcode = 'TM409',
            message = 'Not enough stock for "' || v_line.product_name || '": ' || trim_scale(v_line.change)
                      || ' more needed, ' || trim_scale(v_line.stock) || ' left.';
    end loop;

    -- ---- what changes, for the audit log (before the lines change)
    select string_agg(t.label || ': ' || case when t.old_q = 0 then 'added ' || t.new_q
                                            when t.new_q = 0 then 'removed (was ' || t.old_q || ')'
                                            else t.old_q || ' -> ' || t.new_q end, '; ' order by t.label)
      into v_changes
      from (select coalesce(p.product_name, 'Product ' || k.product_id) || case when k.unit = 'HALF' then ' (half case)' else '' end as label,
                   coalesce(o.quantity, 0) as old_q, coalesce(n.quantity, 0) as new_q
              from (select i.product_id, i.unit from public.booking_items i where i.booking_id = p_booking_id
                    union select n.product_id, n.unit from unnest(v_ids, v_units) n(product_id, unit)) k
              left join public.booking_items o on o.booking_id = p_booking_id and o.product_id = k.product_id and o.unit = k.unit
              left join unnest(v_ids, v_units, v_qtys) n(product_id, unit, quantity) on n.product_id = k.product_id and n.unit = k.unit
              left join public.products p on p.product_id = k.product_id) t
     where t.old_q <> t.new_q;
    if v_changes is null then
        return jsonb_build_object('booking_id', p_booking_id, 'changed', false, 'total_amount', v_old_total,
                                  'signature', trimax_private.booking_items_signature(p_booking_id));
    end if;

    -- ---- the lines: remove, change the quantity (same price and discount), add (priced now)
    delete from public.booking_items i
     where i.booking_id = p_booking_id
       and not exists (select 1 from unnest(v_ids, v_units) n(product_id, unit)
                        where n.product_id = i.product_id and n.unit = i.unit);
    update public.booking_items i
       set quantity = n.quantity, subtotal = i.unit_price * n.quantity
      from unnest(v_ids, v_units, v_qtys) n(product_id, unit, quantity)
     where i.booking_id = p_booking_id and i.product_id = n.product_id and i.unit = n.unit
       and i.quantity <> n.quantity;
    insert into public.booking_items (booking_id, product_id, unit, quantity, unit_price, subtotal)
    select p_booking_id, n.product_id, n.unit, n.quantity,
           case when n.unit = 'HALF' then p.half_price else p.price end,
           case when n.unit = 'HALF' then p.half_price else p.price end * n.quantity
      from unnest(v_ids, v_units, v_qtys) with ordinality n(product_id, unit, quantity, ord)
      join public.products p on p.product_id = n.product_id
     where not exists (select 1 from public.booking_items i
                        where i.booking_id = p_booking_id and i.product_id = n.product_id and i.unit = n.unit)
     order by n.ord;
    perform public.refresh_booking_total_for(p_booking_id);

    -- ---- the stock moves by the difference (history: "Booking changed")
    perform trimax_private.set_stock_context('BOOKING_CHANGED', 'booking', p_booking_id::text, p_admin,
                                             left('Order changed by the admin' || coalesce(': ' || v_note, ''), 500), null, null);
    update public.products p
       set stock = p.stock - m.change
      from unnest(v_move_ids, v_move_changes) m(product_id, change)
     where p.product_id = m.product_id and p.stock - m.change >= 0;
    get diagnostics v_moved = row_count;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
    if v_moved <> cardinality(v_move_ids) then
        raise exception using errcode = 'TM409', message = 'The stock changed while saving. Please try again.';
    end if;

    select b.total_amount into v_new_total from public.bookings b where b.booking_id = p_booking_id;
    perform trimax_private.audit(p_admin, 'booking.items_change', p_booking_id::text,
        left(v_changes || '; total ' || trim_scale(v_old_total) || ' -> ' || trim_scale(v_new_total)
             || coalesce('; note: ' || v_note, ''), 500));

    return jsonb_build_object('booking_id', p_booking_id, 'changed', true,
                              'old_total', v_old_total, 'total_amount', v_new_total, 'changes', v_changes,
                              'signature', trimax_private.booking_items_signature(p_booking_id));
end $function$;

-- ----------------------------------------------------------------------------------------------- 3
create or replace function public.trimax_driver_summary()
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('DRIVER');
    v_today date := (now() at time zone 'Asia/Manila')::date;
    v_month date := date_trunc('month', now() at time zone 'Asia/Manila')::date;
    v_result jsonb;
begin
    with delivered as (
        select b.total_amount,
               (coalesce(case when b.delivery_status = 'DELIVERED' then b.delivery_status_at end,
                         b.completed_at, b.delivery_status_at, b.last_changed_at) at time zone 'Asia/Manila')::date as day
          from public.bookings b
         where b.driver_username = v_me
           and b.status <> 'Cancelled'
           and (b.status = 'Completed' or b.delivery_status = 'DELIVERED'))
    select jsonb_build_object(
        'today', v_today,
        'delivered_today', (select count(*) from delivered where day = v_today),
        'delivered_today_amount', (select coalesce(sum(total_amount), 0) from delivered where day = v_today),
        'delivered_month', (select count(*) from delivered where day >= v_month),
        'delivered_month_amount', (select coalesce(sum(total_amount), 0) from delivered where day >= v_month),
        'to_deliver', (select count(*) from public.bookings b
                        where b.driver_username = v_me and b.status in ('Pending', 'Confirmed', 'In Progress')
                          and coalesce(b.delivery_status, 'ASSIGNED') not in ('DELIVERED', 'FAILED')),
        'pay_month', (select coalesce(sum(r.net_pay), 0) from public.payroll_records r
                       where r.employee_username = v_me and r.status <> 'VOID' and r.period_end >= v_month
                         and r.period_end < (v_month + interval '1 month')::date),
        'pay_records', coalesce((select jsonb_agg(jsonb_build_object(
                                     'period_start', x.period_start, 'period_end', x.period_end,
                                     'net_pay', x.net_pay, 'status', x.status, 'paid_at', x.paid_at)
                                     order by x.period_end desc, x.payroll_id desc)
                                   from (select r.* from public.payroll_records r
                                          where r.employee_username = v_me and r.status <> 'VOID'
                                          order by r.period_end desc, r.payroll_id desc limit 6) x), '[]'::jsonb))
      into v_result;
    return v_result;
end $function$;

revoke all on function trimax_private.booking_items_signature(integer) from public, anon, authenticated, service_role;
revoke all on function trimax_private.update_booking_items(text, integer, text, jsonb, text) from public, anon, authenticated, service_role;
revoke all on function public.trimax_driver_summary() from public, anon, service_role;
grant execute on function public.trimax_driver_summary() to authenticated;

commit;
