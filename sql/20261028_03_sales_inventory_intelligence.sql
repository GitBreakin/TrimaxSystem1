-- =====================================================================================================
-- Sales & Inventory Intelligence (panel recommendation, 2026-10-28): which products sell well, which are
-- slow or not selling, which have too much or too little stock, what to do about it, and Admin alerts.
--
-- QUALIFYING SALES (the same records as Sales & Profit):
--   * bookings with status 'Completed', dated by trimax_private.booking_sale_time (completed / delivered);
--     line amount = quantity x (unit price - item discount per piece);
--   * Walk-In / In-House sales with status 'COMPLETED' (paid or unpaid: the stock left either way), at
--     sold_at; line amount = line_total.
--   Not counted: Pending, Confirmed, In Progress and Cancelled bookings; VOIDED sales.
--   Each line is counted once (no join multiplies quantities).
--
-- WHAT IS KNOWN ABOUT A PRODUCT: products have no "date added", so "known since" = the earliest of its
-- stock history (inventory_movements, from 20261027_01), its booking lines and its sale lines. A product
-- known for less than min_history_days is "Insufficient Data" for slow / non-moving / overstock: nothing
-- is concluded from too little history. Nothing is estimated or filled in.
--
-- 1. trimax_private.inventory_insight_settings: one row of thresholds (change them without code).
-- 2. trimax_private.product_insights(from, to): one row per product with units sold, revenue, the previous
--    equivalent period, last sale, average per day, days of stock, flags, the reason and the suggested
--    action for each flag, and a restock quantity when the data supports it. Used by the Desktop's
--    Analytics ("Sales & Inventory Intelligence") and Inventory ("Insight" column), and by the alerts.
-- 3. trimax_private.inventory_alert_state + trimax_private.refresh_inventory_alerts(admin): adds an
--    admin_notifications row (category 'Inventory insight', related product) only when a condition NEWLY
--    applies (overstock, not selling, fast seller running low, restock recommended, sales dropped, out of
--    stock after recent sales), at most once per renotify_days for the same product and condition. A
--    lock makes two Desktops refreshing at the same time produce one alert. The Desktop runs it when it
--    starts, every 15 minutes while open, and when the Intelligence tab is opened (there is no scheduler).
-- Owner (Desktop) only: no grants to anon, authenticated or service_role.
--
-- ORDER: after 20261027_04 (and 20261028_01 for nothing; independent). Re-runnable.
-- Rollback: 20261028_93_rollback_sales_inventory_intelligence.sql (keeps settings, alert state, notifications).
-- NOT APPLIED to production. Needs the owner's approval first.
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.booking_sale_time(timestamptz,text,timestamptz,timestamptz)') is null
       or to_regclass('public.sale_items') is null or to_regclass('public.inventory_movements') is null
       or not exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'booking_items' and column_name = 'discount_per_unit') then
        raise exception 'Apply 20261027_01 and 20261027_04 first.';
    end if;
end $$;

-- ===================================================================================== 1. SETTINGS

create table if not exists trimax_private.inventory_insight_settings (
    id                       boolean primary key default true check (id),
    min_history_days         integer not null default 14  check (min_history_days between 1 and 365),
    non_moving_days          integer not null default 30  check (non_moving_days between 7 and 365),
    fast_daily_rate          numeric(10, 3) not null default 1.000 check (fast_daily_rate > 0),
    fast_min_units           integer not null default 5   check (fast_min_units >= 1),
    slow_daily_rate          numeric(10, 3) not null default 0.150 check (slow_daily_rate > 0),
    low_stock_units          integer not null default 29  check (low_stock_units >= 0),
    critical_coverage_days   integer not null default 7   check (critical_coverage_days between 1 and 365),
    overstock_coverage_days  integer not null default 60  check (overstock_coverage_days between 7 and 3650),
    overstock_min_stock      integer not null default 20  check (overstock_min_stock >= 1),
    lead_time_days           integer not null default 3   check (lead_time_days between 0 and 365),
    safety_days              integer not null default 3   check (safety_days between 0 and 365),
    target_cover_days        integer not null default 14  check (target_cover_days between 1 and 365),
    decline_pct              integer not null default 50  check (decline_pct between 10 and 100),
    decline_min_prev_units   integer not null default 10  check (decline_min_prev_units >= 1),
    alert_window_days        integer not null default 30  check (alert_window_days between 7 and 365),
    renotify_days            integer not null default 7   check (renotify_days between 1 and 365)
);
insert into trimax_private.inventory_insight_settings (id) values (true) on conflict (id) do nothing;
alter table trimax_private.inventory_insight_settings enable row level security;
revoke all on table trimax_private.inventory_insight_settings from public, anon, authenticated, service_role;
comment on table trimax_private.inventory_insight_settings is
    'Thresholds of Sales & Inventory Intelligence. lead_time_days, safety_days and target_cover_days are '
    'ASSUMPTIONS (the database has no supplier lead time, minimum stock or purchase orders).';

-- ===================================================================================== 2. PRODUCT INSIGHTS

-- Every qualifying sale line, once.
create or replace function trimax_private.sold_lines()
returns table (product_id integer, qty numeric, amount numeric, sold_at timestamptz, ref text)
language sql
stable
set search_path = public, pg_temp
as $$
    select i.product_id, i.quantity::numeric,
           (i.quantity * (i.unit_price - coalesce(i.discount_per_unit, 0)))::numeric,
           trimax_private.booking_sale_time(b.completed_at, b.delivery_status, b.delivery_status_at, b.transc_date),
           'booking ' || b.booking_id
      from public.booking_items i
      join public.bookings b on b.booking_id = i.booking_id
     where b.status = 'Completed'
    union all
    select si.product_id, si.quantity::numeric, si.line_total::numeric, x.sold_at, 'sale ' || x.receipt_no
      from public.sale_items si
      join public.sales x on x.sale_id = si.sale_id
     where x.status = 'COMPLETED'
$$;

create or replace function trimax_private.product_insights(p_from timestamptz, p_to timestamptz)
returns table (product_id integer, product_name text, supplier text, stock integer, price numeric,
               units_sold numeric, revenue numeric, orders integer,
               prev_units numeric, prev_revenue numeric, trend_pct numeric,
               last_sale_at timestamptz, days_since_last_sale integer, never_sold boolean,
               known_since timestamptz, history_days numeric, observed_days numeric,
               avg_daily numeric, coverage_days numeric, recommended_qty integer,
               flags text[], reasons jsonb, actions jsonb)
language plpgsql
stable
set search_path = public, pg_temp
as $$
#variable_conflict use_column
declare
    s trimax_private.inventory_insight_settings;
    r record;
    v_len interval;
    v_flags text[];
    v_reasons jsonb;
    v_actions jsonb;
    v_insufficient boolean;
    v_hist numeric;
    v_obs numeric;
    v_avg numeric;
    v_cov numeric;
    v_qty integer;
    v_trend numeric;
    v_days_since integer;
    v_nm_from timestamptz;
    v_fast boolean;
    v_critical boolean;
    v_non_moving boolean;
begin
    if p_from is null or p_to is null or p_from >= p_to then
        raise exception using errcode = 'TM422', message = 'Choose a start that is before the end.';
    end if;
    if p_to - p_from > interval '366 days' then
        raise exception using errcode = 'TM422', message = 'Choose a period of at most one year.';
    end if;
    select * into s from trimax_private.inventory_insight_settings limit 1;
    v_len := p_to - p_from;
    v_nm_from := p_to - make_interval(days => s.non_moving_days);

    for r in
        with l as (select * from trimax_private.sold_lines()),
        cur as (select l.product_id, sum(l.qty) as units, sum(l.amount) as revenue, count(distinct l.ref)::int as orders
                  from l where l.sold_at >= p_from and l.sold_at < p_to group by 1),
        prev as (select l.product_id, sum(l.qty) as units, sum(l.amount) as revenue
                   from l where l.sold_at >= p_from - v_len and l.sold_at < p_from group by 1),
        last as (select l.product_id, max(l.sold_at) as last_at from l where l.sold_at < p_to group by 1),
        nm as (select l.product_id, sum(l.qty) as units from l where l.sold_at >= v_nm_from and l.sold_at < p_to group by 1),
        seen as (select z.product_id, min(z.at) as first_at from (
                     select m.product_id, m.occurred_at as at from public.inventory_movements m where m.product_id is not null
                     union all select i.product_id, b.transc_date from public.booking_items i join public.bookings b on b.booking_id = i.booking_id
                     union all select si.product_id, x.sold_at from public.sale_items si join public.sales x on x.sale_id = si.sale_id
                 ) z group by 1)
        select p.product_id as pid, p.product_name as pname, p.supplier as psupplier, p.stock as pstock, p.price as pprice,
               coalesce(c.units, 0) as units, coalesce(c.revenue, 0) as revenue, coalesce(c.orders, 0) as orders,
               coalesce(pv.units, 0) as prev_units, coalesce(pv.revenue, 0) as prev_revenue,
               la.last_at, coalesce(n.units, 0) as nm_units, sn.first_at
          from public.products p
          left join cur c on c.product_id = p.product_id
          left join prev pv on pv.product_id = p.product_id
          left join last la on la.product_id = p.product_id
          left join nm n on n.product_id = p.product_id
          left join seen sn on sn.product_id = p.product_id
         order by p.product_id
    loop
        v_flags := '{}';
        v_reasons := '{}'::jsonb;
        v_actions := '{}'::jsonb;
        v_hist := case when r.first_at is null then null
                       else round((extract(epoch from (p_to - r.first_at)) / 86400)::numeric, 1) end;
        v_insufficient := v_hist is null or v_hist < s.min_history_days;
        v_obs := case when r.first_at is null then null
                      else round((extract(epoch from (p_to - greatest(p_from, r.first_at))) / 86400)::numeric, 1) end;
        v_avg := case when v_obs is not null and v_obs >= 1 then round(r.units / v_obs, 3) end;
        v_cov := case when v_avg > 0 then round(greatest(r.pstock, 0) / v_avg, 1) end;
        v_trend := case when r.prev_units > 0 then round((r.units - r.prev_units) * 100.0 / r.prev_units, 1) end;
        v_days_since := case when r.last_at is null then null
                             else floor(extract(epoch from (p_to - r.last_at)) / 86400)::int end;
        v_qty := null;

        -- Critical / low stock
        v_critical := r.pstock <= 0 or r.pstock <= s.low_stock_units
                      or (v_cov is not null and v_cov < s.critical_coverage_days);
        if v_critical then
            v_flags := array_append(v_flags, 'CRITICAL');
            v_reasons := v_reasons || jsonb_build_object('CRITICAL',
                case when r.pstock <= 0 then 'Out of stock.'
                     when r.pstock <= s.low_stock_units then 'Only ' || r.pstock || ' left (low stock is ' || s.low_stock_units || ' or fewer).'
                     else 'About ' || v_cov || ' days of stock left at the current sales rate (less than ' || s.critical_coverage_days || ' days).' end);
            v_actions := v_actions || jsonb_build_object('CRITICAL',
                case when r.units > 0 then 'Restock soon: it is selling.'
                     else 'Check whether it is still needed before restocking.' end);
        end if;

        -- Fast-moving
        v_fast := r.units >= s.fast_min_units and v_avg is not null and v_avg >= s.fast_daily_rate;
        if v_fast then
            v_flags := array_append(v_flags, 'FAST');
            v_reasons := v_reasons || jsonb_build_object('FAST',
                'Sold ' || trim_scale(r.units) || ' in the period, ' || trim_scale(round(v_avg, 2)) || ' per day on average (fast is '
                || trim_scale(s.fast_daily_rate) || ' per day or more).');
            v_actions := v_actions || jsonb_build_object('FAST', 'Keep enough stock and reorder earlier than usual.');
        end if;

        -- Restock recommendation (only when the sales rate is known)
        if v_avg is not null and v_avg > 0 and v_cov <= s.lead_time_days + s.safety_days then
            v_qty := greatest(1, ceil(v_avg * (s.lead_time_days + s.target_cover_days))::int - greatest(r.pstock, 0));
            v_flags := array_append(v_flags, 'RESTOCK');
            v_reasons := v_reasons || jsonb_build_object('RESTOCK',
                case when r.pstock <= 0 then 'Out of stock while selling ' || trim_scale(round(v_avg, 2)) || ' per day'
                     else 'About ' || v_cov || ' days of stock left at ' || trim_scale(round(v_avg, 2)) || ' per day' end
                || '; a restock is assumed to take ' || s.lead_time_days || ' days, plus ' || s.safety_days || ' days of safety stock.');
            v_actions := v_actions || jsonb_build_object('RESTOCK',
                'Order about ' || v_qty || ' (enough for ' || s.target_cover_days || ' days after delivery). Assumed lead time '
                || s.lead_time_days || ' days; incoming orders are not tracked.');
        end if;

        -- Slow-moving
        if not v_insufficient and r.units > 0 and v_avg is not null and v_avg < s.slow_daily_rate and r.pstock > 0 then
            v_flags := array_append(v_flags, 'SLOW');
            v_reasons := v_reasons || jsonb_build_object('SLOW',
                'Only ' || trim_scale(r.units) || ' sold in ' || v_obs || ' days (' || trim_scale(round(v_avg, 2)) || ' per day; slow is below '
                || trim_scale(s.slow_daily_rate) || ' per day) with ' || r.pstock || ' in stock.');
            v_actions := v_actions || jsonb_build_object('SLOW',
                'Review the price, consider a promotion, check demand, or buy less next time.');
        end if;

        -- Non-moving (in stock, no sale in the last non_moving_days, and known for at least that long)
        v_non_moving := not v_insufficient and r.pstock > 0 and r.nm_units = 0 and v_hist >= s.non_moving_days;
        if v_non_moving then
            v_flags := array_append(v_flags, 'NON_MOVING');
            v_reasons := v_reasons || jsonb_build_object('NON_MOVING',
                case when r.last_at is null
                     then 'Never sold since it was first recorded ' || floor(v_hist) || ' days ago; ' || r.pstock || ' in stock.'
                     else 'Last sold ' || v_days_since || ' days ago (' || to_char(r.last_at at time zone 'Asia/Manila', 'Mon DD, YYYY')
                          || '); no sale in the last ' || s.non_moving_days || ' days; ' || r.pstock || ' in stock.' end);
            v_actions := v_actions || jsonb_build_object('NON_MOVING',
                'Consider a promotion or bundle, ask customers if they still want it, and stop reordering for now.');
        end if;

        -- Overstock
        if not v_insufficient and r.pstock >= s.overstock_min_stock
           and ((v_cov is not null and v_cov > s.overstock_coverage_days) or v_non_moving) then
            v_flags := array_append(v_flags, 'OVERSTOCK');
            v_reasons := v_reasons || jsonb_build_object('OVERSTOCK',
                case when v_cov is not null
                     then r.pstock || ' in stock lasts about ' || v_cov || ' days at ' || trim_scale(round(v_avg, 2)) || ' per day (more than '
                          || s.overstock_coverage_days || ' days).'
                     else r.pstock || ' in stock and no sale in the last ' || s.non_moving_days || ' days.' end);
            v_actions := v_actions || jsonb_build_object('OVERSTOCK',
                'Hold off on buying more, consider a promotion, and check for seasonal demand.');
        end if;

        -- Sales decline compared with the previous equivalent period
        if r.prev_units >= s.decline_min_prev_units and r.units <= r.prev_units * (1 - s.decline_pct / 100.0) then
            v_flags := array_append(v_flags, 'DECLINE');
            v_reasons := v_reasons || jsonb_build_object('DECLINE',
                'Sold ' || trim_scale(r.units) || ' compared with ' || trim_scale(r.prev_units) || ' in the previous period ('
                || coalesce(v_trend::text, '-100') || '%).');
            v_actions := v_actions || jsonb_build_object('DECLINE',
                'Check the price, stock-outs and what customers are buying instead.');
        end if;

        if cardinality(v_flags) = 0 then
            if v_insufficient then
                v_flags := array['INSUFFICIENT_DATA'];
                v_reasons := jsonb_build_object('INSUFFICIENT_DATA',
                    case when r.first_at is null then 'No stock history, booking or sale is recorded for this product yet.'
                         else 'Recorded for only ' || v_hist || ' days (at least ' || s.min_history_days || ' days are needed).' end);
                v_actions := jsonb_build_object('INSUFFICIENT_DATA', 'Wait for more sales history before deciding.');
            else
                v_flags := array['NORMAL'];
                v_reasons := jsonb_build_object('NORMAL', 'Sales and stock are within the set limits.');
                v_actions := jsonb_build_object('NORMAL', 'No action needed.');
            end if;
        end if;

        product_id := r.pid; product_name := r.pname; supplier := r.psupplier; stock := r.pstock; price := r.pprice;
        units_sold := r.units; revenue := r.revenue; orders := r.orders;
        prev_units := r.prev_units; prev_revenue := r.prev_revenue; trend_pct := v_trend;
        last_sale_at := r.last_at; days_since_last_sale := v_days_since; never_sold := r.last_at is null;
        known_since := r.first_at; history_days := v_hist; observed_days := v_obs;
        avg_daily := v_avg; coverage_days := v_cov; recommended_qty := v_qty;
        flags := v_flags; reasons := v_reasons; actions := v_actions;
        return next;
    end loop;
end $$;

-- ===================================================================================== 3. ALERTS

create table if not exists trimax_private.inventory_alert_state (
    product_id         integer not null references public.products (product_id) on delete cascade,
    condition          text not null check (condition in ('OVERSTOCK', 'NON_MOVING', 'FAST_LOW', 'RESTOCK', 'DECLINE', 'OUT_OF_STOCK')),
    active             boolean not null,
    since              timestamptz not null default now(),
    last_notified_at   timestamptz,
    notification_id    bigint,
    primary key (product_id, condition)
);
alter table trimax_private.inventory_alert_state enable row level security;
revoke all on table trimax_private.inventory_alert_state from public, anon, authenticated, service_role;

create or replace function trimax_private.refresh_inventory_alerts(p_admin text)
returns integer
language plpgsql
set search_path = public, pg_temp
as $$
declare
    s trimax_private.inventory_insight_settings;
    i record;
    c text;
    v_conditions text[];
    v_state trimax_private.inventory_alert_state;
    v_created integer := 0;
    v_from timestamptz;
    v_to timestamptz := now();
    v_title text;
    v_reason text;
    v_action text;
    v_body text;
    v_id bigint;
begin
    perform trimax_private.require_admin(p_admin);
    -- One refresh at a time (two Desktops at once give one alert, not two).
    perform pg_advisory_xact_lock(hashtext('trimax.refresh_inventory_alerts'));
    select * into s from trimax_private.inventory_insight_settings limit 1;
    v_from := v_to - make_interval(days => s.alert_window_days);

    for i in select * from trimax_private.product_insights(v_from, v_to) loop
        v_conditions := '{}';
        if 'OVERSTOCK' = any(i.flags) then v_conditions := array_append(v_conditions, 'OVERSTOCK'); end if;
        if 'NON_MOVING' = any(i.flags) then v_conditions := array_append(v_conditions, 'NON_MOVING'); end if;
        if 'FAST' = any(i.flags) and 'CRITICAL' = any(i.flags) then v_conditions := array_append(v_conditions, 'FAST_LOW'); end if;
        if 'RESTOCK' = any(i.flags) then v_conditions := array_append(v_conditions, 'RESTOCK'); end if;
        if 'DECLINE' = any(i.flags) then v_conditions := array_append(v_conditions, 'DECLINE'); end if;
        if i.stock <= 0 and i.units_sold > 0 then v_conditions := array_append(v_conditions, 'OUT_OF_STOCK'); end if;

        -- conditions that no longer apply: remember that, so a later return is a new event
        update trimax_private.inventory_alert_state st
           set active = false, since = now()
         where st.product_id = i.product_id and st.active and not (st.condition = any(v_conditions));

        foreach c in array v_conditions loop
            select * into v_state from trimax_private.inventory_alert_state st
             where st.product_id = i.product_id and st.condition = c;
            if found and v_state.active then
                continue;                                   -- already known: no new alert
            end if;
            if found and v_state.last_notified_at is not null
               and v_state.last_notified_at > now() - make_interval(days => s.renotify_days) then
                update trimax_private.inventory_alert_state st set active = true, since = now()
                 where st.product_id = i.product_id and st.condition = c;
                continue;                                   -- came back too soon: no repeat alert
            end if;

            v_title := case c
                when 'OVERSTOCK' then 'Possible overstock: '
                when 'NON_MOVING' then 'Not selling: '
                when 'FAST_LOW' then 'Fast seller running low: '
                when 'RESTOCK' then 'Restock recommended: '
                when 'DECLINE' then 'Sales dropped: '
                else 'Out of stock: ' end || i.product_name;
            v_reason := case c
                when 'FAST_LOW' then concat_ws(' ', i.reasons ->> 'FAST', i.reasons ->> 'CRITICAL')
                when 'OUT_OF_STOCK' then 'Out of stock after selling ' || trim_scale(i.units_sold) || ' in the last '
                                         || s.alert_window_days || ' days.'
                else i.reasons ->> c end;
            v_action := case c
                when 'FAST_LOW' then coalesce(i.actions ->> 'RESTOCK', i.actions ->> 'CRITICAL')
                when 'OUT_OF_STOCK' then coalesce(i.actions ->> 'RESTOCK', 'Restock if customers still order it.')
                else i.actions ->> c end;
            v_body := i.product_name || coalesce(' (' || nullif(btrim(i.supplier), '') || ')', '') || '. '
                || 'Stock now: ' || i.stock || '. Sold in the last ' || s.alert_window_days || ' days ('
                || to_char(v_from at time zone 'Asia/Manila', 'Mon DD') || ' to ' || to_char(v_to at time zone 'Asia/Manila', 'Mon DD, YYYY')
                || '): ' || trim_scale(i.units_sold)
                || coalesce(', ' || trim_scale(round(i.avg_daily, 2)) || ' per day', '') || '. '
                || coalesce('Reason: ' || v_reason || ' ', '')
                || coalesce('Suggested action: ' || v_action, '');

            insert into public.admin_notifications (kind, category, title, body, related_type, related_id)
            values ('Announcement', 'Inventory insight', left(v_title, 200), left(v_body, 2000), 'product', i.product_id::text)
            returning notification_id into v_id;
            v_created := v_created + 1;

            insert into trimax_private.inventory_alert_state (product_id, condition, active, since, last_notified_at, notification_id)
            values (i.product_id, c, true, now(), now(), v_id)
            on conflict (product_id, condition) do update
               set active = true, since = now(), last_notified_at = now(), notification_id = excluded.notification_id;
        end loop;
    end loop;
    return v_created;
end $$;

revoke all on function trimax_private.sold_lines() from public;
revoke all on function trimax_private.product_insights(timestamptz, timestamptz) from public;
revoke all on function trimax_private.refresh_inventory_alerts(text) from public;

commit;
