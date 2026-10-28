-- =====================================================================================================
-- Half case (owner's request of 2026-10-28): an agent's booking, and a Walk-In / In-House sale on the
-- Desktop, can have a product as a HALF CASE.
--
-- Rules confirmed by the owner:
--   * A product can be sold by the half case when the Admin gives it a HALF CASE PRICE (Inventory). The
--     Admin sets that price. No half case price = whole cases only.
--   * A half case takes 0.5 off the stock (10 -> 9.5). Stock can therefore be a whole number or end in .5.
--   * A line is WHOLE (the product as before) or HALF: quantity = how many half cases, unit price = the half
--     case price, puhunan of the line = half the product's puhunan, stock = quantity x 0.5. One product can
--     be on an order twice: once whole, once half.
--   * Agent discounts per item work the same on a half case line: the most off a half case is
--     half case price - half the puhunan - half the minimum patong.
--
-- What this changes (every existing row stays as it is; every existing line becomes WHOLE):
--   1. products.stock and the stock history columns (quantity_change, stock_before, stock_after) become
--      numbers that can end in .5 (checked). Values are stored without trailing zeros (10, not 10.0).
--      + products.half_price (null = no half case).
--   2. booking_items and sale_items: + unit ('WHOLE' | 'HALF') and stock_qty (quantity x 0.5 for HALF),
--      the amount the line takes from the stock. The puhunan of a HALF line is half the product's puhunan.
--      booking_items: one line per product AND unit (was one per product), so whole + half fit in one booking.
--   3. Phone functions (same names and arguments): the product list gives half_price; a booking item and a
--      discount can say "unit": "HALF" (an app without it sends whole cases, as before); booking_json gives
--      each item's unit.
--   4. Desktop functions: create_sale and void_sale handle HALF lines; adjust_stock accepts halves; restock,
--      bad orders and the stock history return numbers with halves; trimax_private.set_product_half_price.
--   5. Sales & Inventory Intelligence counts a half case as 0.5 sold.
--
-- ORDER: after 20261028_06. Re-runnable. Rollback: 20261028_97_rollback_half_case.sql (only while no half
-- case was ever sold and no stock ends in .5; it refuses otherwise and changes nothing).
-- APPLIED to production 2026-10-29 00:05 (Asia/Manila) with the owner's approval (1 transaction). Install the new
-- Desktop and APK before setting any half case price (docs/HALF_CASE.md).
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid,numeric)') is null
       or not exists (select 1 from information_schema.columns
                       where table_schema = 'public' and table_name = 'booking_items' and column_name = 'discount_per_unit') then
        raise exception 'Apply 20261027_04 and 20261028_06 first.';
    end if;
end $$;

-- ===================================================================================== 1. STOCK IN HALVES
-- Functions whose result type changes are dropped first (created again below).
drop function if exists trimax_private.restock_product(text, integer, integer, numeric, text, boolean, uuid);
drop function if exists trimax_private.adjust_stock(text, integer, integer, text, uuid);
drop function if exists trimax_private.adjust_stock(text, integer, numeric, text, uuid);
drop function if exists trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid);
drop function if exists trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid, numeric);
drop function if exists trimax_private.void_bad_order(text, bigint, text);
drop function if exists trimax_private.product_insights(timestamptz, timestamptz);

-- The stock-history trigger names the column, so it is dropped while the type changes (created again below).
drop trigger if exists trimax_products_stock_moved on public.products;
do $$
begin
    if (select data_type from information_schema.columns
         where table_schema = 'public' and table_name = 'products' and column_name = 'stock') <> 'numeric' then
        alter table public.products alter column stock type numeric using stock::numeric;
    end if;
    if (select data_type from information_schema.columns
         where table_schema = 'public' and table_name = 'inventory_movements' and column_name = 'stock_after') <> 'numeric' then
        alter table public.inventory_movements
            alter column quantity_change type numeric using quantity_change::numeric,
            alter column stock_before type numeric using stock_before::numeric,
            alter column stock_after type numeric using stock_after::numeric;
    end if;
end $$;
alter table public.products drop constraint if exists products_stock_half_chk;
alter table public.products add constraint products_stock_half_chk
    check (stock * 2 = trunc(stock * 2) and stock <= 2000000000);
alter table public.inventory_movements drop constraint if exists inventory_movements_half_chk;
alter table public.inventory_movements add constraint inventory_movements_half_chk
    check (quantity_change * 2 = trunc(quantity_change * 2) and stock_after * 2 = trunc(stock_after * 2));

alter table public.products add column if not exists half_price numeric(12, 2);
alter table public.products drop constraint if exists products_half_price_chk;
alter table public.products add constraint products_half_price_chk check (half_price is null or half_price > 0);
comment on column public.products.half_price is 'Price of a half case. NULL: the product is sold by the whole case only.';

-- Keep stock without trailing zeros (10, not 10.0) and count a half case price change as a price change.
create or replace function trimax_private.products_before_write()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    new.stock := trim_scale(new.stock);
    if new.capital_cost is not null and new.markup_type is not null then
        new.price := trimax_private.selling_price(new.capital_cost, new.markup_type, new.markup_value);
    end if;
    if tg_op = 'INSERT'
       or new.price is distinct from old.price or new.capital_cost is distinct from old.capital_cost
       or new.markup_type is distinct from old.markup_type or new.markup_value is distinct from old.markup_value
       or new.min_markup is distinct from old.min_markup
       or new.agent_discount_allowed is distinct from old.agent_discount_allowed
       or new.half_price is distinct from old.half_price then
        new.pricing_updated_at := now();
    end if;
    return new;
end $function$;

create or replace function trimax_private.products_stock_moved()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_kind text := nullif(current_setting('trimax.stock_kind', true), '');
    v_actor text := nullif(current_setting('trimax.stock_actor', true), '');
    v_note text := nullif(current_setting('trimax.stock_note', true), '');
    v_request text := nullif(current_setting('trimax.stock_request_id', true), '');
    v_cost numeric;
    v_before numeric;
    v_change numeric;
begin
    if tg_op = 'INSERT' then
        if coalesce(new.stock, 0) = 0 then
            return null;
        end if;
        v_before := 0;
        v_change := new.stock;
        v_kind := coalesce(v_kind, 'INITIAL');
    else
        if new.stock is not distinct from old.stock then
            return null;
        end if;
        v_before := old.stock;
        v_change := trim_scale(new.stock - old.stock);
    end if;
    if v_kind is null then
        v_kind := 'ADJUSTMENT';
        v_note := coalesce(v_note, 'Stock changed without a TRIMAX reason (database user ' || session_user || ')');
    end if;
    begin
        v_cost := nullif(current_setting('trimax.stock_unit_cost', true), '')::numeric;
    exception when others then
        v_cost := null;
    end;
    v_cost := coalesce(v_cost, new.capital_cost);
    if v_actor is not null and not exists (select 1 from public.users u where u.username = v_actor) then
        v_note := coalesce(v_note || ' ', '') || '(by ' || v_actor || ')';
        v_actor := null;
    end if;
    insert into public.inventory_movements (product_id, product_name, kind, quantity_change, stock_before,
                                            stock_after, unit_cost, total_cost, reference_type, reference_id,
                                            actor_username, note, client_request_id)
    values (new.product_id, new.product_name, v_kind, v_change, v_before, new.stock, v_cost,
            case when v_cost is not null then round(v_cost * abs(v_change), 2) end,
            nullif(current_setting('trimax.stock_ref_type', true), ''),
            nullif(current_setting('trimax.stock_ref_id', true), ''),
            v_actor, left(v_note, 500), v_request::uuid);
    return null;
end $function$;

create trigger trimax_products_stock_moved after insert or update of stock on public.products
    for each row execute function trimax_private.products_stock_moved();

-- ===================================================================================== 2. HALF CASE LINES
alter table public.booking_items add column if not exists unit text not null default 'WHOLE';
alter table public.booking_items drop constraint if exists booking_items_unit_chk;
alter table public.booking_items add constraint booking_items_unit_chk check (unit in ('WHOLE', 'HALF'));
alter table public.booking_items add column if not exists stock_qty numeric
    generated always as (trim_scale(quantity * case when unit = 'HALF' then 0.5 else 1 end)) stored;
alter table public.sale_items add column if not exists unit text not null default 'WHOLE';
alter table public.sale_items drop constraint if exists sale_items_unit_chk;
alter table public.sale_items add constraint sale_items_unit_chk check (unit in ('WHOLE', 'HALF'));
alter table public.sale_items add column if not exists stock_qty numeric
    generated always as (trim_scale(quantity * case when unit = 'HALF' then 0.5 else 1 end)) stored;
-- One line per product AND unit, so a booking can have whole cases and half cases of the same product.
-- (Before: one line per product, booking_items_booking_id_product_id_key; the rollback puts it back.)
alter table public.booking_items drop constraint if exists booking_items_booking_id_product_id_key;
alter table public.booking_items drop constraint if exists booking_items_booking_product_unit_key;
alter table public.booking_items add constraint booking_items_booking_product_unit_key unique (booking_id, product_id, unit);
comment on column public.booking_items.unit is 'WHOLE (one case) or HALF (quantity = half cases, unit_price = half case price).';
comment on column public.booking_items.stock_qty is 'What the line takes from the stock: quantity, or quantity x 0.5 for HALF.';

-- 0.5 for a half case, 1 otherwise.
create or replace function trimax_private.unit_factor(p_unit text)
returns numeric
language sql
immutable
set search_path = public, pg_temp
as $$ select case when p_unit = 'HALF' then 0.5 else 1 end::numeric $$;

-- The puhunan of a line: half the product's puhunan for a half case.
create or replace function trimax_private.booking_items_capture_cost()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if tg_op = 'INSERT' and new.unit_cost is null
       or tg_op = 'UPDATE' and (new.product_id is distinct from old.product_id or new.unit is distinct from old.unit) then
        select round(p.capital_cost * trimax_private.unit_factor(new.unit), 2) into new.unit_cost
          from public.products p where p.product_id = new.product_id;
    end if;
    return new;
end $function$;
drop trigger if exists trimax_booking_items_capture_cost on public.booking_items;
create trigger trimax_booking_items_capture_cost before insert or update of product_id, unit on public.booking_items
    for each row execute function trimax_private.booking_items_capture_cost();

-- ===================================================================================== 3. ADMIN: HALF CASE PRICE
create or replace function trimax_private.set_product_half_price(p_admin text, p_product_id integer, p_half_price numeric)
returns numeric
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_old public.products;
begin
    perform trimax_private.require_admin(p_admin);
    if p_half_price is not null and (p_half_price <= 0 or p_half_price > 10000000 or p_half_price <> round(p_half_price, 2)) then
        raise exception using errcode = 'TM422', message = 'The half case price must be more than 0, with at most 2 decimals.';
    end if;
    select * into v_old from public.products p where p.product_id = p_product_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'That product no longer exists.';
    end if;
    if v_old.half_price is not distinct from p_half_price then
        return p_half_price;
    end if;
    update public.products p set half_price = p_half_price where p.product_id = p_product_id;
    perform trimax_private.audit_as(p_admin, 'product.half_price', 'product', p_product_id::text,
        v_old.product_name || ': half case '
        || case when p_half_price is null then 'turned off (was ' || trimax_private.peso(v_old.half_price) || ')'
                when v_old.half_price is null then 'turned on at ' || trimax_private.peso(p_half_price)
                else trimax_private.peso(v_old.half_price) || ' -> ' || trimax_private.peso(p_half_price) end);
    return p_half_price;
end $function$;

-- ===================================================================================== 4. PHONE FUNCTIONS
create or replace function public.trimax_list_products()
returns jsonb
language plpgsql
stable security definer
set search_path = public, pg_temp
as $function$
begin
    perform trimax_private.require_account('AGENT');
    return coalesce((
        select jsonb_agg(jsonb_build_object(
                   'product_id', p.product_id,
                   'product_name', p.product_name,
                   'price', p.price,
                   'stock', p.stock,
                   'image_path', p.image_path,
                   -- 20261027_04: whether the agent may take something off a piece, and the most (never the puhunan)
                   'discount_allowed', trimax_private.item_discount_max(p.price, p.capital_cost, p.min_markup,
                                                                        p.agent_discount_allowed) > 0,
                   'max_discount_per_unit', trimax_private.item_discount_max(p.price, p.capital_cost, p.min_markup,
                                                                             p.agent_discount_allowed),
                   -- 20261028_07: half case (null when the product is sold by the whole case only)
                   'half_price', p.half_price,
                   'half_max_discount_per_unit', case when p.half_price is not null then
                       trimax_private.item_discount_max(p.half_price, p.capital_cost * 0.5, p.min_markup * 0.5,
                                                        p.agent_discount_allowed) end)
                   order by lower(p.product_name), p.product_id)
        from public.products p), '[]'::jsonb);
end $function$;

create or replace function public.trimax_agent_discount_quote(p_items jsonb)
returns jsonb
language plpgsql
stable security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('AGENT');
    v_bad int;
    v_out jsonb;
    v_name text;
begin
    if p_items is null or jsonb_typeof(p_items) <> 'array'
       or jsonb_array_length(p_items) = 0 or jsonb_array_length(p_items) > 50 then
        raise exception using errcode = 'TM422', message = 'Add between 1 and 50 products.';
    end if;
    select count(*) into v_bad
      from jsonb_array_elements(p_items) e
     where jsonb_typeof(e) <> 'object'
        or coalesce(e ->> 'product_id', '') !~ '^[0-9]{1,9}$'
        or coalesce(e ->> 'quantity', '') !~ '^[0-9]{1,9}$'
        or coalesce(e ->> 'unit', 'WHOLE') not in ('WHOLE', 'HALF');
    if v_bad > 0 then
        raise exception using errcode = 'TM422', message = 'Each product needs a product id and a whole-number quantity.';
    end if;
    if exists (select 1 from jsonb_array_elements(p_items) e
                where (e ->> 'quantity')::bigint < 1 or (e ->> 'quantity')::bigint > 1000) then
        raise exception using errcode = 'TM422', message = 'Each quantity must be between 1 and 1000.';
    end if;
    if exists (select 1 from jsonb_array_elements(p_items) e
                where (e ->> 'product_id')::bigint > 2147483647
                   or not exists (select 1 from public.products p where p.product_id = (e ->> 'product_id')::bigint)) then
        raise exception using errcode = 'TM404', message = 'A product no longer exists. Refresh the product list.';
    end if;
    select p.product_name into v_name
      from jsonb_array_elements(p_items) e join public.products p on p.product_id = (e ->> 'product_id')::bigint
     where e ->> 'unit' = 'HALF' and p.half_price is null limit 1;
    if v_name is not null then
        raise exception using errcode = 'TM422', message = '"' || v_name || '" is not sold by the half case. Refresh the product list.';
    end if;
    with q as (
        select (e ->> 'product_id')::bigint as product_id, coalesce(e ->> 'unit', 'WHOLE') as unit,
               sum((e ->> 'quantity')::bigint) as quantity
          from jsonb_array_elements(p_items) e group by 1, 2
    ), l as (
        select p.product_id, p.product_name, q.unit, q.quantity,
               round(case when q.unit = 'HALF' then p.half_price else p.price end, 2) as unit_price,
               trimax_private.item_discount_max(case when q.unit = 'HALF' then p.half_price else p.price end,
                                                p.capital_cost * trimax_private.unit_factor(q.unit),
                                                p.min_markup * trimax_private.unit_factor(q.unit),
                                                p.agent_discount_allowed) as max_per_unit,
               case when not p.agent_discount_allowed then 'The Admin does not allow a discount on this item.'
                    when p.capital_cost is null then 'No discount yet: this item has no capital cost (puhunan).'
                    when trimax_private.item_discount_max(case when q.unit = 'HALF' then p.half_price else p.price end,
                                                          p.capital_cost * trimax_private.unit_factor(q.unit),
                                                          p.min_markup * trimax_private.unit_factor(q.unit), true) <= 0
                        then 'No discount is possible on this item at its current price.' end as reason
          from q join public.products p on p.product_id = q.product_id
    )
    select jsonb_build_object(
               'subtotal', coalesce(sum(l.unit_price * l.quantity), 0),
               'max_discount', coalesce(sum(l.max_per_unit * l.quantity), 0),
               'discount_available', coalesce(bool_or(l.max_per_unit > 0), false),
               'reason', case when not coalesce(bool_or(l.max_per_unit > 0), false)
                              then 'No item in this order can be discounted.' end,
               'items', coalesce(jsonb_agg(jsonb_build_object(
                            'product_id', l.product_id, 'product_name', l.product_name, 'unit', l.unit,
                            'quantity', l.quantity,
                            'unit_price', l.unit_price, 'max_discount_per_unit', l.max_per_unit,
                            'discount_allowed', l.max_per_unit > 0, 'reason', l.reason) order by l.product_id, l.unit desc), '[]'::jsonb))
      into v_out
      from l;
    return v_out;
end $function$;

-- Discounts per item line: a line is a product + unit ("unit" missing = WHOLE, as older apps send).
create or replace function trimax_private.apply_item_discounts(p_booking_id integer, p_actor text, p_items jsonb)
returns numeric
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_line record;
    v_d numeric;
    v_max numeric;
    v_old numeric;
    v_total numeric;
    v_notes text := '';
begin
    if p_items is null or jsonb_typeof(p_items) <> 'array' then
        raise exception using errcode = 'TM422', message = 'Send the discounts as a list of items.';
    end if;
    if exists (select 1 from jsonb_array_elements(p_items) e
                where jsonb_typeof(e) <> 'object'
                   or coalesce(e ->> 'product_id', '') !~ '^[0-9]{1,9}$'
                   or coalesce(e ->> 'unit', 'WHOLE') not in ('WHOLE', 'HALF')
                   or coalesce(e ->> 'discount_per_unit', '') !~ '^[0-9]{1,7}(\.[0-9]{1,2})?$') then
        raise exception using errcode = 'TM422',
            message = 'Each discount needs a product id and a peso amount per piece (0 or more, at most 2 decimals).';
    end if;
    if exists (select 1 from jsonb_array_elements(p_items) e
                where not exists (select 1 from public.booking_items i
                                   where i.booking_id = p_booking_id and i.product_id = (e ->> 'product_id')::bigint
                                     and i.unit = coalesce(e ->> 'unit', 'WHOLE'))) then
        raise exception using errcode = 'TM422', message = 'A discounted product is not in this booking.';
    end if;
    select coalesce(sum(quantity * discount_per_unit), 0) into v_old from public.booking_items where booking_id = p_booking_id;

    for v_line in
        select i.item_id, i.quantity, i.unit, i.unit_price,
               coalesce(i.unit_cost, p.capital_cost * trimax_private.unit_factor(i.unit)) as cost,
               p.min_markup * trimax_private.unit_factor(i.unit) as min_markup, p.agent_discount_allowed,
               coalesce(p.product_name, 'product ' || i.product_id) || case when i.unit = 'HALF' then ' (half case)' else '' end as name,
               coalesce((select max((e ->> 'discount_per_unit')::numeric) from jsonb_array_elements(p_items) e
                          where (e ->> 'product_id')::bigint = i.product_id and coalesce(e ->> 'unit', 'WHOLE') = i.unit), 0) as wanted
          from public.booking_items i left join public.products p on p.product_id = i.product_id
         where i.booking_id = p_booking_id
         order by i.item_id
    loop
        v_d := v_line.wanted;
        if v_d > 0 then
            if not coalesce(v_line.agent_discount_allowed, false) then
                raise exception using errcode = 'TM422', detail = 'discount_not_allowed',
                    message = 'No discount is allowed on ' || v_line.name || '. Ask the Admin.';
            end if;
            if v_line.cost is null then
                raise exception using errcode = 'TM422', detail = 'discount_not_configured',
                    message = 'No discount is allowed on ' || v_line.name || ' yet: there is no capital cost (puhunan). '
                              || 'Ask the Admin to set it.';
            end if;
            v_max := trimax_private.item_discount_max(v_line.unit_price, v_line.cost, v_line.min_markup, true);
            if v_d > v_max then
                raise exception using errcode = 'TM422', detail = 'discount_too_big',
                    message = 'Too much off ' || v_line.name || '. The most you can take off is '
                              || trimax_private.peso(v_max) || ' per piece.';
            end if;
            v_notes := v_notes || ', ' || v_line.name || ' less ' || trimax_private.peso(v_d) || ' x ' || v_line.quantity;
        end if;
        update public.booking_items set discount_per_unit = v_d
         where item_id = v_line.item_id and discount_per_unit is distinct from v_d;
    end loop;

    -- Per item replaces the old whole-order discount; the trigger keeps the totals.
    update public.bookings set discount_type = null, discount_value = null where booking_id = p_booking_id
       and (discount_type is not null or discount_value is not null);
    perform public.refresh_booking_total_for(p_booking_id);
    select coalesce(sum(quantity * discount_per_unit), 0) into v_total from public.booking_items where booking_id = p_booking_id;
    if v_total <> v_old then
        update public.bookings set discount_by = p_actor, discount_at = now() where booking_id = p_booking_id;
        perform trimax_private.audit_as(p_actor, 'booking.discount', 'booking', p_booking_id::text,
            case when v_total = 0 then 'Item discounts removed'
                 else 'Item discounts ' || trimax_private.peso(v_total) || ':' || substr(v_notes, 2) end);
    end if;
    return v_total;
end $function$;

create or replace function trimax_private.discount_limits_for(p_booking_id integer, p_ids bigint[], p_qtys bigint[])
returns table (subtotal numeric, floor_total numeric, max_discount numeric, missing text)
language sql
stable
set search_path = public, pg_temp
as $function$
    with lines as (
        -- a line booked before costs were recorded uses the product's puhunan now (the stricter guess)
        select i.subtotal, i.quantity, coalesce(i.unit_cost, p.capital_cost * trimax_private.unit_factor(i.unit)) as cost,
               coalesce(p.min_markup, 0) * trimax_private.unit_factor(i.unit) as min_markup,
               coalesce(p.product_name, 'product ' || i.product_id) as name
          from public.booking_items i left join public.products p on p.product_id = i.product_id
         where p_booking_id is not null and i.booking_id = p_booking_id
        union all
        select round(p.price, 2) * l.quantity, l.quantity, p.capital_cost, p.min_markup, p.product_name
          from unnest(p_ids, p_qtys) as l(product_id, quantity)
          join public.products p on p.product_id = l.product_id
         where p_booking_id is null
    )
    select coalesce(sum(subtotal), 0),
           sum(quantity * (cost + min_markup)),
           case when bool_or(cost is null) then 0
                else greatest(coalesce(sum(subtotal), 0) - coalesce(sum(quantity * (cost + min_markup)), 0), 0) end,
           string_agg(distinct name, ', ') filter (where cost is null)
      from lines
$function$;

create or replace function public.trimax_agent_create_booking(
    p_request_id uuid, p_customer_name text, p_customer_contact text, p_delivery_address text,
    p_delivery_latitude double precision, p_delivery_longitude double precision, p_items jsonb,
    p_requested_schedule text default null, p_special_instructions text default null,
    p_discount_type text default null, p_discount_value numeric default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('AGENT');
    v_name text := nullif(btrim(p_customer_name), '');
    v_contact text := nullif(btrim(p_customer_contact), '');
    v_contact_key text := trimax_private.contact_key(p_customer_contact);
    v_address text := nullif(btrim(p_delivery_address), '');
    v_schedule text := nullif(btrim(p_requested_schedule), '');
    v_notes text := nullif(btrim(p_special_instructions), '');
    v_existing_id int;
    v_existing_by text;
    v_client_id int;
    v_client_is_mine boolean;
    v_booking_id int;
    v_line record;
    v_lines int;
    v_products int;
    v_bad int;
    v_ids bigint[];
    v_units text[];
    v_qtys bigint[];
    v_total numeric;
    v_discount numeric := 0;
    v_discounts jsonb := '[]'::jsonb;
begin
    if p_request_id is null then
        raise exception using errcode = 'TM422', message = 'Missing request id.';
    end if;

    -- Serialise calls that carry the same request id, then return the first result to the retry.
    perform pg_advisory_xact_lock(hashtextextended('trimax-booking-request:' || p_request_id::text, 0));
    select b.booking_id, b.created_by_username into v_existing_id, v_existing_by
      from public.bookings b where b.client_request_id = p_request_id;
    if v_existing_id is not null then
        if v_existing_by is distinct from v_me then
            raise exception using errcode = 'TM409', message = 'This request id has already been used.';
        end if;
        return trimax_private.booking_json(v_existing_id, false) || jsonb_build_object('already_existed', true);
    end if;

    -- ---- validate input
    if v_name is null or char_length(v_name) > 100 then
        raise exception using errcode = 'TM422', message = 'Enter the customer name (up to 100 characters).';
    end if;
    if v_contact is null or v_contact_key is null or char_length(v_contact) > 30 then
        raise exception using errcode = 'TM422', message = 'Enter a valid customer contact number.';
    end if;
    if v_address is null or char_length(v_address) > 500 then
        raise exception using errcode = 'TM422', message = 'Choose a delivery address.';
    end if;
    if p_delivery_latitude is null or p_delivery_longitude is null
       or not (p_delivery_latitude between -90 and 90) or not (p_delivery_longitude between -180 and 180)
       or (p_delivery_latitude = 0 and p_delivery_longitude = 0) then
        raise exception using errcode = 'TM422', message = 'Choose the delivery location on the map.';
    end if;
    if char_length(coalesce(v_schedule, '')) > 100 or char_length(coalesce(v_notes, '')) > 1000 then
        raise exception using errcode = 'TM422', message = 'The schedule or the instructions are too long.';
    end if;
    if p_items is null or jsonb_typeof(p_items) <> 'array'
       or jsonb_array_length(p_items) = 0 or jsonb_array_length(p_items) > 50 then
        raise exception using errcode = 'TM422', message = 'Add between 1 and 50 products.';
    end if;
    select count(*) into v_bad
      from jsonb_array_elements(p_items) e
     where jsonb_typeof(e) <> 'object'
        or coalesce(e ->> 'product_id', '') !~ '^[0-9]{1,9}$'
        or coalesce(e ->> 'quantity', '') !~ '^[0-9]{1,9}$'
        or coalesce(e ->> 'unit', 'WHOLE') not in ('WHOLE', 'HALF')
        or (e ? 'discount_per_unit' and jsonb_typeof(e -> 'discount_per_unit') <> 'null'
            and coalesce(e ->> 'discount_per_unit', '') !~ '^[0-9]{1,7}(\.[0-9]{1,2})?$');
    if v_bad > 0 then
        raise exception using errcode = 'TM422', message = 'Each product needs a product id, a whole-number quantity '
            || 'and, if any, a peso discount per piece (0 or more, at most 2 decimals).';
    end if;
    -- Discounts are per item (20261027_04): the old whole-order discount is no longer accepted.
    if coalesce(p_discount_value, 0) <> 0 then
        raise exception using errcode = 'TM422', detail = 'discount_per_item_only',
            message = 'Discounts are now given per item. Update the TRIMAX app, then enter the discount on each item.';
    end if;
    -- One discount per product and unit (a line listed twice must carry the same discount).
    if exists (select 1 from jsonb_array_elements(p_items) e
                group by (e ->> 'product_id'), coalesce(e ->> 'unit', 'WHOLE')
               having count(distinct coalesce((e ->> 'discount_per_unit')::numeric, 0)) > 1) then
        raise exception using errcode = 'TM422', message = 'A product is listed twice with different discounts.';
    end if;
    select coalesce(jsonb_agg(jsonb_build_object('product_id', s.product_id, 'unit', s.unit, 'discount_per_unit', s.d)), '[]'::jsonb)
      into v_discounts
      from (select (e ->> 'product_id')::bigint as product_id, coalesce(e ->> 'unit', 'WHOLE') as unit,
                   max(coalesce((e ->> 'discount_per_unit')::numeric, 0)) as d
              from jsonb_array_elements(p_items) e group by 1, 2) s
     where s.d > 0;

    -- Merge repeated lines into one line per product and unit, ordered by product id (whole before half).
    select array_agg(s.product_id order by s.product_id, s.unit desc), array_agg(s.unit order by s.product_id, s.unit desc),
           array_agg(s.quantity order by s.product_id, s.unit desc)
      into v_ids, v_units, v_qtys
      from (select (e ->> 'product_id')::bigint as product_id, coalesce(e ->> 'unit', 'WHOLE') as unit,
                   sum((e ->> 'quantity')::bigint) as quantity
              from jsonb_array_elements(p_items) e
             group by 1, 2) s;
    if exists (select 1 from unnest(v_qtys) q where q < 1 or q > 1000) then
        raise exception using errcode = 'TM422', message = 'Each quantity must be between 1 and 1000.';
    end if;
    if exists (select 1 from unnest(v_ids) i where i > 2147483647) then
        raise exception using errcode = 'TM404', message = 'Unknown product. Refresh the product list.';
    end if;

    -- ---- customer: this agent's saved customer with the same number (saved with a photo first)
    perform pg_advisory_xact_lock(hashtextextended('trimax-client-contact:' || v_contact_key, 0));
    select c.client_id,
           exists (select 1 from public.agents a where a.client_id = c.client_id and a.username = v_me)
      into v_client_id, v_client_is_mine
      from public.clients c
     where trimax_private.contact_key(c.contact_num) = v_contact_key
     order by 2 desc, c.client_id
     limit 1;
    if v_client_id is not null and not v_client_is_mine then
        raise exception using errcode = 'TM409',
            message = 'A customer with this contact number is already registered and is not assigned to you. '
                      || 'Ask an administrator to assign that customer to you.';
    end if;
    if v_client_id is null then
        raise exception using errcode = 'TM422', detail = 'customer_photo_required',
            message = 'Save this customer with a photo first (Customers, then Add customer).';
    end if;

    -- ---- products: lock in a fixed order (no deadlock between two phones), then check stock per product
    perform 1 from public.products where product_id = any(v_ids) order by product_id for update;
    for v_line in
        select s.product_id, s.need, s.has_half, p.product_name, p.stock, p.half_price
          from (select l.product_id, sum(l.quantity * trimax_private.unit_factor(l.unit)) as need,
                       bool_or(l.unit = 'HALF') as has_half
                  from unnest(v_ids, v_units, v_qtys) as l(product_id, unit, quantity)
                 group by l.product_id) s
          left join public.products p on p.product_id = s.product_id
         order by s.product_id
    loop
        if v_line.product_name is null then
            raise exception using errcode = 'TM404', message = 'Product ' || v_line.product_id || ' no longer exists. Refresh the product list.';
        end if;
        if v_line.has_half and v_line.half_price is null then
            raise exception using errcode = 'TM422',
                message = '"' || v_line.product_name || '" is not sold by the half case. Refresh the product list.';
        end if;
        if v_line.stock < v_line.need then
            raise exception using errcode = 'TM409',
                message = 'Not enough stock for "' || v_line.product_name || '" (' || v_line.stock || ' left).';
        end if;
    end loop;

    -- ---- booking, items, discount, stock: all in this transaction
    insert into public.bookings (client_id, status, created_via, created_by_username, client_request_id,
                                 delivery_address, delivery_latitude, delivery_longitude,
                                 requested_schedule, special_instructions)
    values (v_client_id, 'Pending', 'mobile', v_me, p_request_id,
            v_address, p_delivery_latitude, p_delivery_longitude, v_schedule, v_notes)
    returning booking_id into v_booking_id;

    insert into public.booking_items (booking_id, product_id, unit, quantity, unit_price, subtotal)
    select v_booking_id, l.product_id, l.unit, l.quantity,
           case when l.unit = 'HALF' then p.half_price else p.price end,
           case when l.unit = 'HALF' then p.half_price else p.price end * l.quantity
      from unnest(v_ids, v_units, v_qtys) with ordinality as l(product_id, unit, quantity, ord)
      join public.products p on p.product_id = l.product_id
     order by l.ord;

    -- The trigger on booking_items keeps the totals; set them here too in case it is absent.
    select coalesce(sum(subtotal), 0) into v_total from public.booking_items where booking_id = v_booking_id;
    update public.bookings set total_amount = v_total, subtotal_amount = v_total
     where booking_id = v_booking_id
       and (total_amount is distinct from v_total or subtotal_amount is distinct from v_total);

    if jsonb_array_length(v_discounts) > 0 then
        v_discount := trimax_private.apply_item_discounts(v_booking_id, v_me, v_discounts);
    end if;

    perform trimax_private.set_stock_context('BOOKING', 'booking', v_booking_id::text, v_me, null, null, null);
    update public.products p
       set stock = p.stock - s.need
      from (select l.product_id, sum(l.quantity * trimax_private.unit_factor(l.unit)) as need
              from unnest(v_ids, v_units, v_qtys) as l(product_id, unit, quantity) group by l.product_id) s
     where p.product_id = s.product_id and p.stock >= s.need;
    get diagnostics v_lines = row_count;
    select count(distinct i) into v_products from unnest(v_ids) i;
    if v_lines <> v_products then
        raise exception using errcode = 'TM409', message = 'Stock changed while saving. Please try again.';
    end if;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);

    insert into trimax_private.delivery_events (booking_id, actor_username, source, event_type, to_status, booking_status, note)
    values (v_booking_id, v_me, 'mobile', 'created', null, 'Pending',
            cardinality(v_ids) || ' product line(s) submitted from the mobile app');
    perform trimax_private.audit(v_me, 'booking.create', v_booking_id::text,
        'Created from the mobile app, ' || cardinality(v_ids) || ' item line(s), total ' || (v_total - v_discount)
        || case when v_discount > 0 then ' after item discounts of ' || v_discount else '' end
        || case when 'HALF' = any(v_units) then ', with half case(s)' else '' end);

    return trimax_private.booking_json(v_booking_id, false)
           || jsonb_build_object('already_existed', false, 'customer_matched_existing', true);
end $function$;

create or replace function trimax_private.booking_json(p_booking_id integer, p_with_events boolean)
returns jsonb
language sql
stable
set search_path = public, pg_temp
as $function$
    select jsonb_build_object(
        'booking_id', b.booking_id,
        'created_at', b.transc_date,
        'status', b.status,
        'delivery_status', case when b.driver_username is null then null
                                else coalesce(b.delivery_status, 'ASSIGNED') end,
        'delivery_status_at', b.delivery_status_at,
        'assigned_at', b.assigned_at,
        'last_changed_at', b.last_changed_at,
        'total_amount', b.total_amount,
        'subtotal_amount', coalesce(b.subtotal_amount, b.total_amount + b.discount_amount),
        'discount', case when b.discount_amount > 0 then jsonb_build_object(
            'type', coalesce(b.discount_type, 'PER_ITEM'),
            'value', b.discount_value,
            'amount', b.discount_amount,
            'by', b.discount_by,
            'at', b.discount_at) end,
        'created_via', coalesce(b.created_via, 'desktop'),
        'customer', jsonb_build_object(
            'client_id', c.client_id,
            'name', c.client_name,
            'contact', c.contact_num,
            'address', c.address),
        'delivery', jsonb_build_object(
            'address', coalesce(b.delivery_address, c.address),
            'latitude', case when b.delivery_latitude is not null then b.delivery_latitude else c.latitude end,
            'longitude', case when b.delivery_latitude is not null then b.delivery_longitude else c.longitude end,
            'schedule', b.requested_schedule,
            'instructions', b.special_instructions),
        'driver', case when b.driver_username is null then null else jsonb_build_object(
            'username', b.driver_username,
            'name', du.name,
            'vehicle_id', b.vehicle_id,
            'vehicle_type', v.vehicle_type) end,
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                       'product_id', i.product_id,
                       'product_name', p.product_name,
                       'unit', i.unit,
                       'quantity', i.quantity,
                       'unit_price', i.unit_price,
                       'discount_per_unit', i.discount_per_unit,
                       'line_total', i.subtotal - i.quantity * i.discount_per_unit,
                       'subtotal', i.subtotal) order by i.item_id)
            from public.booking_items i
            left join public.products p on p.product_id = i.product_id
            where i.booking_id = b.booking_id), '[]'::jsonb),
        -- Positions are kept for the administrator only; the phone gets who/what/when.
        'events', case when p_with_events then coalesce((
            select jsonb_agg(jsonb_build_object(
                       'at', e.occurred_at,
                       'type', e.event_type,
                       'from', e.from_status,
                       'to', e.to_status,
                       'booking_status', e.booking_status,
                       'note', e.note) order by e.occurred_at desc, e.event_id desc)
            from (select * from trimax_private.delivery_events x
                  where x.booking_id = b.booking_id
                  order by x.occurred_at desc, x.event_id desc limit 30) e), '[]'::jsonb) end)
    from public.bookings b
    left join public.clients c on c.client_id = b.client_id
    left join public.users du on du.username = b.driver_username
    left join public.vehicles v on v.vehicle_id = b.vehicle_id
    where b.booking_id = p_booking_id
$function$;

-- ===================================================================================== 5. DESKTOP SALES
create or replace function trimax_private.create_sale(
    p_admin text, p_request_id uuid, p_sale_type text, p_client_id integer, p_customer_name text, p_items jsonb,
    p_payment_status text, p_payment_method text, p_amount_tendered numeric, p_note text)
returns table (sale_id bigint, receipt_no text, total_amount numeric, already_existed boolean)
language plpgsql
set search_path = public, pg_temp
as $function$
#variable_conflict use_column
declare
    v_existing public.sales;
    v_type text := upper(btrim(coalesce(p_sale_type, '')));
    v_pay text := upper(btrim(coalesce(p_payment_status, '')));
    v_method text := nullif(upper(btrim(coalesce(p_payment_method, ''))), '');
    v_name text := nullif(btrim(p_customer_name), '');
    v_note text := nullif(btrim(p_note), '');
    v_client_name text;
    v_bad int;
    v_ids bigint[];
    v_units text[];
    v_qtys bigint[];
    v_discs numeric[];
    v_line record;
    v_sale_id bigint;
    v_receipt text;
    v_sub numeric;
    v_disc numeric;
    v_cost numeric;
    v_missing int;
    v_total numeric;
    v_lines int;
    v_products int;
begin
    perform trimax_private.require_admin(p_admin);
    if p_request_id is null then
        raise exception using errcode = 'TM422', message = 'Missing request id.';
    end if;
    perform pg_advisory_xact_lock(hashtextextended('trimax-sale-request:' || p_request_id::text, 0));
    select * into v_existing from public.sales s where s.client_request_id = p_request_id;
    if found then
        return query select v_existing.sale_id, v_existing.receipt_no, v_existing.total_amount, true;
        return;
    end if;

    if v_type not in ('WALK_IN', 'IN_HOUSE') then
        raise exception using errcode = 'TM422', message = 'Choose Walk-In or In-House.';
    end if;
    if v_type = 'IN_HOUSE' then
        if p_client_id is null then
            raise exception using errcode = 'TM422', message = 'Choose the customer for an In-House sale.';
        end if;
        select c.client_name into v_client_name from public.clients c where c.client_id = p_client_id;
        if not found then
            raise exception using errcode = 'TM404', message = 'That customer no longer exists.';
        end if;
        v_name := v_client_name;
    else
        if p_client_id is not null then
            raise exception using errcode = 'TM422',
                message = 'A Walk-In sale has no customer record. Choose In-House to sell to a saved customer.';
        end if;
        if char_length(coalesce(v_name, '')) > 100 then
            raise exception using errcode = 'TM422', message = 'The customer name is too long (up to 100 characters).';
        end if;
    end if;
    if v_pay not in ('PAID', 'UNPAID') then
        raise exception using errcode = 'TM422', message = 'Choose Paid or Unpaid.';
    end if;
    if v_pay = 'PAID' then
        if v_method is null or v_method not in ('CASH', 'GCASH', 'BANK_TRANSFER', 'OTHER') then
            raise exception using errcode = 'TM422', message = 'Choose how the customer paid.';
        end if;
    elsif v_method is not null or p_amount_tendered is not null then
        raise exception using errcode = 'TM422', message = 'Payment details are only for paid sales.';
    end if;
    if char_length(coalesce(v_note, '')) > 500 then
        raise exception using errcode = 'TM422', message = 'The note is too long (up to 500 characters).';
    end if;

    if p_items is null or jsonb_typeof(p_items) <> 'array'
       or jsonb_array_length(p_items) = 0 or jsonb_array_length(p_items) > 100 then
        raise exception using errcode = 'TM422', message = 'Add between 1 and 100 products.';
    end if;
    select count(*) into v_bad
      from jsonb_array_elements(p_items) e
     where jsonb_typeof(e) <> 'object'
        or coalesce(e ->> 'product_id', '') !~ '^[0-9]{1,9}$'
        or coalesce(e ->> 'quantity', '') !~ '^[0-9]{1,9}$'
        or coalesce(e ->> 'unit', 'WHOLE') not in ('WHOLE', 'HALF')
        or coalesce(jsonb_typeof(e -> 'discount'), 'null') not in ('number', 'null');
    if v_bad > 0 then
        raise exception using errcode = 'TM422',
            message = 'Each product needs a product id, a whole-number quantity and a discount amount.';
    end if;
    -- one line per product and unit (whole before half)
    select array_agg(s.product_id order by s.product_id, s.unit desc), array_agg(s.unit order by s.product_id, s.unit desc),
           array_agg(s.quantity order by s.product_id, s.unit desc), array_agg(s.discount order by s.product_id, s.unit desc)
      into v_ids, v_units, v_qtys, v_discs
      from (select (e ->> 'product_id')::bigint as product_id, coalesce(e ->> 'unit', 'WHOLE') as unit,
                   sum((e ->> 'quantity')::bigint) as quantity, sum(coalesce((e ->> 'discount')::numeric, 0)) as discount
              from jsonb_array_elements(p_items) e group by 1, 2) s;
    if exists (select 1 from unnest(v_qtys) q where q < 1 or q > 100000) then
        raise exception using errcode = 'TM422', message = 'Each quantity must be between 1 and 100,000.';
    end if;
    if exists (select 1 from unnest(v_discs) d where d < 0 or d <> round(d, 2)) then
        raise exception using errcode = 'TM422', message = 'A discount must be 0 or more, with at most 2 decimals.';
    end if;
    if exists (select 1 from unnest(v_ids) i where i > 2147483647) then
        raise exception using errcode = 'TM404', message = 'Unknown product. Refresh the product list.';
    end if;

    perform 1 from public.products where product_id = any(v_ids) order by product_id for update;
    for v_line in
        select l.product_id, l.unit, l.quantity, l.discount, p.product_name, p.half_price,
               round(case when l.unit = 'HALF' then p.half_price else p.price end, 2) as price
          from unnest(v_ids, v_units, v_qtys, v_discs) as l(product_id, unit, quantity, discount)
          left join public.products p on p.product_id = l.product_id
         order by l.product_id, l.unit desc
    loop
        if v_line.product_name is null then
            raise exception using errcode = 'TM404',
                message = 'Product ' || v_line.product_id || ' no longer exists. Refresh the product list.';
        end if;
        if v_line.unit = 'HALF' and v_line.half_price is null then
            raise exception using errcode = 'TM422',
                message = '"' || v_line.product_name || '" is not sold by the half case. Set its half case price in Inventory.';
        end if;
        if v_line.discount > v_line.price * v_line.quantity then
            raise exception using errcode = 'TM422',
                message = 'The discount for "' || v_line.product_name || case when v_line.unit = 'HALF' then ' (half case)' else '' end
                          || '" is more than its subtotal (' || trimax_private.peso(v_line.price * v_line.quantity) || ').';
        end if;
    end loop;
    for v_line in
        select s.product_id, s.need, p.product_name, p.stock
          from (select l.product_id, sum(l.quantity * trimax_private.unit_factor(l.unit)) as need
                  from unnest(v_ids, v_units, v_qtys) as l(product_id, unit, quantity) group by l.product_id) s
          join public.products p on p.product_id = s.product_id
         order by s.product_id
    loop
        if v_line.stock < v_line.need then
            raise exception using errcode = 'TM409',
                message = 'Not enough stock for "' || v_line.product_name || '" (' || v_line.stock || ' left).';
        end if;
    end loop;

    select sum(round(case when l.unit = 'HALF' then p.half_price else p.price end, 2) * l.quantity), sum(l.discount),
           coalesce(sum(round(p.capital_cost * trimax_private.unit_factor(l.unit), 2) * l.quantity)
                    filter (where p.capital_cost is not null), 0),
           count(*) filter (where p.capital_cost is null)
      into v_sub, v_disc, v_cost, v_missing
      from unnest(v_ids, v_units, v_qtys, v_discs) as l(product_id, unit, quantity, discount)
      join public.products p on p.product_id = l.product_id;
    v_total := v_sub - v_disc;
    if v_pay = 'PAID' and p_amount_tendered is not null
       and (p_amount_tendered < v_total or p_amount_tendered > 100000000 or p_amount_tendered <> round(p_amount_tendered, 2)) then
        raise exception using errcode = 'TM422',
            message = 'The amount received is less than the total (' || trimax_private.peso(v_total) || ').';
    end if;

    v_receipt := 'TRX-' || to_char(now() at time zone 'Asia/Manila', 'YYYYMMDD') || '-'
                 || lpad(nextval('public.sales_receipt_seq')::text, 5, '0');
    insert into public.sales (receipt_no, sale_type, client_id, customer_name, status, payment_status,
                              payment_method, amount_tendered, change_due, paid_at, subtotal_amount,
                              discount_amount, total_amount, cost_amount, cost_missing_lines, note,
                              created_by, client_request_id)
    values (v_receipt, v_type, case when v_type = 'IN_HOUSE' then p_client_id end, v_name, 'COMPLETED', v_pay,
            v_method, p_amount_tendered, case when p_amount_tendered is not null then p_amount_tendered - v_total end,
            case when v_pay = 'PAID' then now() end, v_sub, v_disc, v_total, v_cost, v_missing, v_note,
            p_admin, p_request_id)
    returning sales.sale_id into v_sale_id;

    insert into public.sale_items (sale_id, line_no, product_id, product_name, unit, quantity, unit_price, unit_cost,
                                   line_subtotal, line_discount, line_total)
    select v_sale_id, l.ord, l.product_id,
           p.product_name || case when l.unit = 'HALF' then ' (half case)' else '' end, l.unit, l.quantity,
           round(case when l.unit = 'HALF' then p.half_price else p.price end, 2),
           round(p.capital_cost * trimax_private.unit_factor(l.unit), 2),
           round(case when l.unit = 'HALF' then p.half_price else p.price end, 2) * l.quantity, l.discount,
           round(case when l.unit = 'HALF' then p.half_price else p.price end, 2) * l.quantity - l.discount
      from unnest(v_ids, v_units, v_qtys, v_discs) with ordinality as l(product_id, unit, quantity, discount, ord)
      join public.products p on p.product_id = l.product_id
     order by l.ord;

    perform trimax_private.set_stock_context('SALE', 'sale', v_receipt, p_admin, null, null, null);
    update public.products p
       set stock = p.stock - s.need
      from (select l.product_id, sum(l.quantity * trimax_private.unit_factor(l.unit)) as need
              from unnest(v_ids, v_units, v_qtys) as l(product_id, unit, quantity) group by l.product_id) s
     where p.product_id = s.product_id and p.stock >= s.need;
    get diagnostics v_lines = row_count;
    select count(distinct i) into v_products from unnest(v_ids) i;
    if v_lines <> v_products then
        raise exception using errcode = 'TM409', message = 'Stock changed while saving. Please try again.';
    end if;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);

    perform trimax_private.audit_as(p_admin, 'sale.create', 'sale', v_receipt,
        case v_type when 'WALK_IN' then 'Walk-In' else 'In-House' end
        || coalesce(' for ' || v_name, '') || ', ' || cardinality(v_ids) || ' line(s), total '
        || trimax_private.peso(v_total)
        || case when v_disc > 0 then ' after ' || trimax_private.peso(v_disc) || ' discount' else '' end
        || ', ' || lower(v_pay) || coalesce(' by ' || lower(v_method), '')
        || case when 'HALF' = any(v_units) then ', with half case(s)' else '' end);
    return query select v_sale_id, v_receipt, v_total, false;
end $function$;

create or replace function trimax_private.void_sale(p_admin text, p_sale_id bigint, p_reason text)
returns void
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_reason text := nullif(btrim(p_reason), '');
    v_sale public.sales;
begin
    perform trimax_private.require_admin(p_admin);
    if v_reason is null or char_length(v_reason) < 3 or char_length(v_reason) > 300 then
        raise exception using errcode = 'TM422', message = 'Enter the reason for voiding (3 to 300 characters).';
    end if;
    select * into v_sale from public.sales s where s.sale_id = p_sale_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'Sale not found.';
    end if;
    if v_sale.status = 'VOIDED' then
        raise exception using errcode = 'TM409', message = 'This sale is already voided.';
    end if;
    perform 1 from public.products p
     where p.product_id in (select i.product_id from public.sale_items i where i.sale_id = p_sale_id)
     order by p.product_id for update;
    perform trimax_private.set_stock_context('SALE_VOIDED', 'sale', v_sale.receipt_no, p_admin, v_reason, null, null);
    update public.products p
       set stock = p.stock + s.qty
      from (select i.product_id, sum(i.stock_qty) as qty from public.sale_items i
             where i.sale_id = p_sale_id group by i.product_id) s
     where p.product_id = s.product_id;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
    update public.sales s set status = 'VOIDED', voided_at = now(), voided_by = p_admin, void_reason = v_reason
     where s.sale_id = p_sale_id;
    perform trimax_private.audit_as(p_admin, 'sale.void', 'sale', v_sale.receipt_no,
        'Voided ' || trimax_private.peso(v_sale.total_amount) || ', stock returned (' || v_reason || ')');
end $function$;

-- ===================================================================================== 6. STOCK FUNCTIONS (halves)
create function trimax_private.restock_product(
    p_admin text, p_product_id integer, p_quantity integer, p_unit_cost numeric, p_note text, p_update_cost boolean,
    p_request_id uuid)
returns table (movement_id bigint, stock_before numeric, stock_after numeric, total_cost numeric, price numeric,
               already_existed boolean)
language plpgsql
set search_path = public, pg_temp
as $function$
#variable_conflict use_column
declare
    v_note text := nullif(btrim(p_note), '');
    v_product public.products;
    v_after public.products;
    v_mov public.inventory_movements;
begin
    perform trimax_private.require_admin(p_admin);
    if p_request_id is null then
        raise exception using errcode = 'TM422', message = 'Missing request id.';
    end if;
    perform pg_advisory_xact_lock(hashtextextended('trimax-stock-request:' || p_request_id::text, 0));
    select * into v_mov from public.inventory_movements m where m.client_request_id = p_request_id;
    if found then
        if v_mov.kind <> 'RESTOCK' or v_mov.product_id is distinct from p_product_id then
            raise exception using errcode = 'TM409', message = 'This request id has already been used.';
        end if;
        return query select v_mov.movement_id, v_mov.stock_before, v_mov.stock_after, v_mov.total_cost,
                            (select p.price from public.products p where p.product_id = p_product_id), true;
        return;
    end if;
    if p_quantity is null or p_quantity < 1 or p_quantity > 100000 then
        raise exception using errcode = 'TM422', message = 'Enter a quantity from 1 to 100,000.';
    end if;
    if p_unit_cost is null or p_unit_cost < 0 or p_unit_cost > 10000000 or p_unit_cost <> round(p_unit_cost, 2) then
        raise exception using errcode = 'TM422', message = 'Enter the cost per unit (0 or more, at most 2 decimals).';
    end if;
    if char_length(coalesce(v_note, '')) > 300 then
        raise exception using errcode = 'TM422', message = 'The note is too long (up to 300 characters).';
    end if;
    select * into v_product from public.products p where p.product_id = p_product_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'That product no longer exists.';
    end if;
    if v_product.stock + p_quantity > 2000000000 then
        raise exception using errcode = 'TM422', message = 'That is more stock than the system can hold.';
    end if;

    perform trimax_private.set_stock_context('RESTOCK', 'restock', null, p_admin, v_note, p_unit_cost, p_request_id);
    update public.products p
       set stock = p.stock + p_quantity,
           capital_cost = case when coalesce(p_update_cost, false) then p_unit_cost else p.capital_cost end
     where p.product_id = p_product_id
    returning * into v_after;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);

    select * into v_mov from public.inventory_movements m where m.client_request_id = p_request_id;
    perform trimax_private.audit_as(p_admin, 'inventory.restock', 'product', p_product_id::text,
        v_product.product_name || ': +' || p_quantity || ' at ' || trimax_private.peso(p_unit_cost) || ' each = '
        || trimax_private.peso(v_mov.total_cost) || ', stock ' || v_product.stock || ' -> ' || v_after.stock
        || case when v_after.capital_cost is distinct from v_product.capital_cost
                then ', puhunan ' || coalesce(trimax_private.peso(v_product.capital_cost), 'none') || ' -> '
                     || trimax_private.peso(v_after.capital_cost)
                     || case when v_after.price is distinct from v_product.price
                             then ', price ' || trimax_private.peso(v_product.price) || ' -> ' || trimax_private.peso(v_after.price)
                             else '' end
                else '' end
        || coalesce(' (' || v_note || ')', ''));
    return query select v_mov.movement_id, v_mov.stock_before, v_mov.stock_after, v_mov.total_cost, v_after.price, false;
end $function$;

-- Correct stock: + or -, in whole or half cases (0.5 steps).
create function trimax_private.adjust_stock(
    p_admin text, p_product_id integer, p_change numeric, p_reason text, p_request_id uuid)
returns table (movement_id bigint, stock_before numeric, stock_after numeric, already_existed boolean)
language plpgsql
set search_path = public, pg_temp
as $function$
#variable_conflict use_column
declare
    v_reason text := nullif(btrim(p_reason), '');
    v_product public.products;
    v_mov public.inventory_movements;
begin
    perform trimax_private.require_admin(p_admin);
    if p_request_id is null then
        raise exception using errcode = 'TM422', message = 'Missing request id.';
    end if;
    perform pg_advisory_xact_lock(hashtextextended('trimax-stock-request:' || p_request_id::text, 0));
    select * into v_mov from public.inventory_movements m where m.client_request_id = p_request_id;
    if found then
        if v_mov.kind <> 'ADJUSTMENT' or v_mov.product_id is distinct from p_product_id then
            raise exception using errcode = 'TM409', message = 'This request id has already been used.';
        end if;
        return query select v_mov.movement_id, v_mov.stock_before, v_mov.stock_after, true;
        return;
    end if;
    if p_change is null or p_change = 0 or abs(p_change) > 1000000 or p_change * 2 <> trunc(p_change * 2) then
        raise exception using errcode = 'TM422', message = 'Enter how many to add (+) or remove (-): whole or half cases (0.5), not 0.';
    end if;
    if v_reason is null or char_length(v_reason) < 3 or char_length(v_reason) > 300 then
        raise exception using errcode = 'TM422', message = 'Enter the reason for the correction (3 to 300 characters).';
    end if;
    select * into v_product from public.products p where p.product_id = p_product_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'That product no longer exists.';
    end if;
    if v_product.stock + p_change < 0 then
        raise exception using errcode = 'TM409',
            message = 'Stock cannot go below 0 (there are ' || v_product.stock || ' in stock).';
    end if;
    perform trimax_private.set_stock_context('ADJUSTMENT', 'adjustment', null, p_admin, v_reason, null, p_request_id);
    update public.products p set stock = p.stock + p_change where p.product_id = p_product_id;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
    select * into v_mov from public.inventory_movements m where m.client_request_id = p_request_id;
    perform trimax_private.audit_as(p_admin, 'inventory.adjust', 'product', p_product_id::text,
        v_product.product_name || ': ' || case when p_change > 0 then '+' else '' end || trim_scale(p_change)
        || ', stock ' || v_mov.stock_before || ' -> ' || v_mov.stock_after || ' (' || v_reason || ')');
    return query select v_mov.movement_id, v_mov.stock_before, v_mov.stock_after, false;
end $function$;

-- ===================================================================================== 7. BAD ORDERS (stock with halves)
create function trimax_private.record_bad_order(
    p_admin text, p_product_id integer, p_quantity integer, p_reason text, p_disposition text,
    p_found_on date, p_note text, p_request_id uuid, p_custom_loss numeric)
returns table (bad_order_id bigint, stock_before numeric, stock_after numeric, loss_amount numeric, already_existed boolean)
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
    v_custom boolean := p_custom_loss is not null;
    v_loss numeric;
    v_product public.products;
    v_bo public.bad_orders;
    v_mov public.inventory_movements;
    v_before numeric;
    v_after numeric;
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
        if v_bo.movement_id is not null then
            select * into v_mov from public.inventory_movements m where m.movement_id = v_bo.movement_id;
            return query select v_bo.bad_order_id, v_mov.stock_before, v_mov.stock_after, v_bo.loss_amount, true;
        else
            return query select v_bo.bad_order_id, p.stock, p.stock, v_bo.loss_amount, true
                           from public.products p where p.product_id = v_bo.product_id;
        end if;
        return;
    end if;
    if v_custom then
        if p_custom_loss <= 0 or p_custom_loss > 10000000 or p_custom_loss <> round(p_custom_loss, 2) then
            raise exception using errcode = 'TM422', message = 'The amount to deduct must be more than 0, with at most 2 decimals.';
        end if;
        if p_quantity is null or p_quantity < 0 or p_quantity > 1000000 then
            raise exception using errcode = 'TM422', message = 'Enter how many pieces leave the stock (0 if only part of one item).';
        end if;
    elsif p_quantity is null or p_quantity < 1 or p_quantity > 1000000 then
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
    v_loss := case when v_custom then p_custom_loss else round(p_quantity * v_product.capital_cost, 2) end;

    insert into public.bad_orders (product_id, product_name, supplier, quantity, reason, disposition, found_on,
                                   unit_cost, loss_amount, loss_source, note, recorded_by, client_request_id)
    values (v_product.product_id, v_product.product_name, v_product.supplier, p_quantity, v_reason, v_disp, v_found,
            v_product.capital_cost, v_loss, case when v_custom then 'CUSTOM' else 'PUHUNAN' end, v_note, p_admin, p_request_id)
    returning * into v_bo;

    if p_quantity > 0 then
        perform trimax_private.set_stock_context('BAD_ORDER', 'bad_order', v_bo.bad_order_id::text, p_admin,
            'Bad order: ' || lower(trimax_private.bad_order_label(v_reason))
            || case when v_disp = 'RETURNED_TO_SUPPLIER' then ', returned to supplier' else ', thrown away' end
            || coalesce(' - ' || v_note, ''),
            v_product.capital_cost, p_request_id);
        update public.products p set stock = p.stock - p_quantity where p.product_id = p_product_id;
        perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
        select * into v_mov from public.inventory_movements m where m.client_request_id = p_request_id;
        update public.bad_orders b set movement_id = v_mov.movement_id where b.bad_order_id = v_bo.bad_order_id;
        v_before := v_mov.stock_before;
        v_after := v_mov.stock_after;
    else
        v_before := v_product.stock;
        v_after := v_product.stock;
    end if;

    perform trimax_private.audit_as(p_admin, 'inventory.bad_order', 'product', p_product_id::text,
        v_product.product_name || ': '
        || case when p_quantity = 0 then 'part of one item' else p_quantity::text end
        || ' ' || lower(trimax_private.bad_order_label(v_reason))
        || ' (found ' || to_char(v_found, 'Mon DD, YYYY') || ', '
        || case when v_disp = 'RETURNED_TO_SUPPLIER' then 'returned to supplier' else 'thrown away' end || '), '
        || case when p_quantity = 0 then 'stock unchanged (' || v_before || ')' else 'stock ' || v_before || ' -> ' || v_after end
        || case when v_custom then ', loss set by hand ' || trimax_private.peso(v_loss)
                else coalesce(', loss ' || trimax_private.peso(v_loss), ', no puhunan') end
        || coalesce(' - ' || v_note, ''));
    return query select v_bo.bad_order_id, v_before, v_after, v_loss, false;
end $function$;

-- The 8-argument version (as in 20261028_04, used by Desktops built before this update): no amount.
create function trimax_private.record_bad_order(
    p_admin text, p_product_id integer, p_quantity integer, p_reason text, p_disposition text,
    p_found_on date, p_note text, p_request_id uuid)
returns table (bad_order_id bigint, stock_before numeric, stock_after numeric, loss_amount numeric, already_existed boolean)
language sql
set search_path = public, pg_temp
as $function$
    select * from trimax_private.record_bad_order(p_admin, p_product_id, p_quantity, p_reason, p_disposition,
                                                   p_found_on, p_note, p_request_id, null::numeric)
$function$;

-- ----------------------------------------------------------------------------------------------- 3
create function trimax_private.void_bad_order(p_admin text, p_bad_order_id bigint, p_reason text)
returns table (stock_before numeric, stock_after numeric)
language plpgsql
set search_path = public, pg_temp
as $function$
#variable_conflict use_column
declare
    v_reason text := nullif(btrim(p_reason), '');
    v_bo public.bad_orders;
    v_before numeric;
    v_after numeric;
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
    if v_bo.quantity > 0 then
        if v_bo.product_id is null then
            raise exception using errcode = 'TM409', message = 'The product was deleted, so its stock cannot be put back.';
        end if;
        perform 1 from public.products p where p.product_id = v_bo.product_id for update;
        perform trimax_private.set_stock_context('BAD_ORDER_VOIDED', 'bad_order', v_bo.bad_order_id::text, p_admin,
            'Bad order undone: ' || v_reason, v_bo.unit_cost, null);
        update public.products p set stock = p.stock + v_bo.quantity where p.product_id = v_bo.product_id
        returning p.stock - v_bo.quantity, p.stock into v_before, v_after;
        perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
    else
        -- part of one item: no stock was taken out, so none is put back
        select p.stock, p.stock into v_before, v_after from public.products p where p.product_id = v_bo.product_id;
    end if;
    update public.bad_orders b set voided_at = now(), voided_by = p_admin, void_reason = v_reason
     where b.bad_order_id = p_bad_order_id;
    perform trimax_private.audit_as(p_admin, 'inventory.bad_order_void', 'product', coalesce(v_bo.product_id::text, ''),
        v_bo.product_name || ': bad order #' || v_bo.bad_order_id || ' of '
        || case when v_bo.quantity = 0 then 'part of one item' else v_bo.quantity::text end || ' undone, '
        || case when v_bo.quantity = 0 then 'stock unchanged' else 'stock ' || v_before || ' -> ' || v_after end
        || ' (' || v_reason || ')');
    return query select v_before, v_after;
end $function$;


-- ===================================================================================== 8. INTELLIGENCE: a half case counts as 0.5 sold
-- (sql/20261028_03 with the line's stock_qty instead of its quantity, and stock as a number with halves)
create or replace function trimax_private.sold_lines()
returns table (product_id integer, qty numeric, amount numeric, sold_at timestamptz, ref text)
language sql
stable
set search_path = public, pg_temp
as $$
    select i.product_id, i.stock_qty,
           (i.quantity * (i.unit_price - coalesce(i.discount_per_unit, 0)))::numeric,
           trimax_private.booking_sale_time(b.completed_at, b.delivery_status, b.delivery_status_at, b.transc_date),
           'booking ' || b.booking_id
      from public.booking_items i
      join public.bookings b on b.booking_id = i.booking_id
     where b.status = 'Completed'
    union all
    select si.product_id, si.stock_qty, si.line_total::numeric, x.sold_at, 'sale ' || x.receipt_no
      from public.sale_items si
      join public.sales x on x.sale_id = si.sale_id
     where x.status = 'COMPLETED'
$$;

create function trimax_private.product_insights(p_from timestamptz, p_to timestamptz)
returns table (product_id integer, product_name text, supplier text, stock numeric, price numeric,
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


revoke all on function trimax_private.unit_factor(text) from public, anon, authenticated, service_role;
revoke all on function trimax_private.set_product_half_price(text, integer, numeric) from public, anon, authenticated, service_role;
revoke all on function trimax_private.restock_product(text, integer, integer, numeric, text, boolean, uuid) from public, anon, authenticated, service_role;
revoke all on function trimax_private.adjust_stock(text, integer, numeric, text, uuid) from public, anon, authenticated, service_role;
revoke all on function trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid, numeric) from public, anon, authenticated, service_role;
revoke all on function trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid) from public, anon, authenticated, service_role;
revoke all on function trimax_private.void_bad_order(text, bigint, text) from public, anon, authenticated, service_role;
revoke all on function trimax_private.sold_lines() from public, anon, authenticated, service_role;
revoke all on function trimax_private.product_insights(timestamptz, timestamptz) from public, anon, authenticated, service_role;
revoke all on function trimax_private.create_sale(text, uuid, text, integer, text, jsonb, text, text, numeric, text) from public, anon, authenticated, service_role;
revoke all on function trimax_private.void_sale(text, bigint, text) from public, anon, authenticated, service_role;
revoke all on function trimax_private.apply_item_discounts(integer, text, jsonb) from public, anon, authenticated, service_role;
revoke all on function trimax_private.discount_limits_for(integer, bigint[], bigint[]) from public, anon, authenticated, service_role;
revoke all on function trimax_private.booking_json(integer, boolean) from public, anon, authenticated, service_role;
revoke all on function trimax_private.products_before_write() from public, anon, authenticated, service_role;
revoke all on function trimax_private.products_stock_moved() from public, anon, authenticated, service_role;
revoke all on function trimax_private.booking_items_capture_cost() from public, anon, authenticated, service_role;
-- phone functions: unchanged access (signed-in users only; each checks the role itself)
revoke all on function public.trimax_list_products() from public, anon, service_role;
grant execute on function public.trimax_list_products() to authenticated;
revoke all on function public.trimax_agent_discount_quote(jsonb) from public, anon, service_role;
grant execute on function public.trimax_agent_discount_quote(jsonb) to authenticated;
revoke all on function public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision, jsonb, text, text, text, numeric) from public, anon, service_role;
grant execute on function public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision, jsonb, text, text, text, numeric) to authenticated;

commit;
