-- =====================================================================================================
-- Agent discounts per item (owner's request of 2026-10-27), replacing the one discount per booking.
--
-- Rules confirmed by the owner:
--   * The Admin decides, per product, whether agents may discount it (Inventory: "Agents may discount").
--   * The agent takes a peso amount off each piece of an allowed item (e.g. Sprite 25.00 -> less 2.00 = 23.00).
--   * The limit stays: a piece may not go below puhunan + minimum patong. A product without puhunan, or not
--     allowed by the Admin, cannot be discounted. The phone is told the most it may take off each piece,
--     never the puhunan.
--   * The discount for the whole booking is replaced: the server refuses it with detail
--     'discount_per_item_only' (an older app shows "Update the TRIMAX app").
--
-- What this changes (keeps every existing row; existing bookings keep their totals: every line gets
-- discount_per_unit = 0, and the total keeps any old whole-order discount):
--   1. products.agent_discount_allowed (default false); booking_items.discount_per_unit (default 0)
--   2. trimax_private.item_discount_max, apply_item_discounts
--   3. refresh_booking_total: discount = item discounts + any old whole-order discount
--   4. booking_json: each item has discount_per_unit and line_total; discount.type 'PER_ITEM'
--   5. phone: trimax_agent_discount_quote (per item), trimax_agent_create_booking (discount per item, same
--      arguments), trimax_agent_set_item_discounts (new), trimax_agent_set_discount (only clears now)
--   6. Desktop: save_product gains p_agent_discount (default null = keep), so an older Desktop still works
--
-- ORDER: after 20261027_01 (and 02, 03). Re-runnable. Rollback: 20261027_90_rollback_per_item_discounts.sql.
-- NOT applied to production yet.
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.discount_amount_for(numeric,text,numeric)') is null
       or to_regprocedure('trimax_private.booking_json(integer,boolean)') is null
       or to_regclass('public.payroll_records') is null then
        raise exception 'Apply 20261027_01_sales_inventory_payroll.sql first.';
    end if;
end $$;

-- ----------------------------------------------------------------------------------------------- 1
alter table public.products add column if not exists agent_discount_allowed boolean not null default false;
alter table public.booking_items add column if not exists discount_per_unit numeric(12, 2) not null default 0;
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'booking_items_discount_chk') then
        alter table public.booking_items add constraint booking_items_discount_chk
            check (discount_per_unit >= 0 and discount_per_unit <= coalesce(unit_price, 0));
    end if;
end $$;

create or replace function trimax_private.products_before_write()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if new.capital_cost is not null and new.markup_type is not null then
        new.price := trimax_private.selling_price(new.capital_cost, new.markup_type, new.markup_value);
    end if;
    if tg_op = 'INSERT'
       or new.price is distinct from old.price or new.capital_cost is distinct from old.capital_cost
       or new.markup_type is distinct from old.markup_type or new.markup_value is distinct from old.markup_value
       or new.min_markup is distinct from old.min_markup
       or new.agent_discount_allowed is distinct from old.agent_discount_allowed then
        new.pricing_updated_at := now();
    end if;
    return new;
end $function$;

-- ----------------------------------------------------------------------------------------------- 2
-- The most an agent may take off one piece: price - (puhunan + minimum patong), only when the Admin allows
-- discounts on the product and it has a puhunan; 0 otherwise.
create or replace function trimax_private.item_discount_max(p_price numeric, p_cost numeric, p_min numeric,
                                                            p_allowed boolean)
returns numeric
language sql
immutable
set search_path = public, pg_temp
as $function$
    select case when not coalesce(p_allowed, false) or p_cost is null or p_price is null then 0
                else greatest(round(p_price - p_cost - coalesce(p_min, 0), 2), 0) end
$function$;

-- Sets the discount per piece of every line of a booking (lines not listed: 0) after checking each one.
-- p_items: [{"product_id": 12, "discount_per_unit": 2.00}, ...]. Returns the total discount.
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
                   or coalesce(e ->> 'discount_per_unit', '') !~ '^[0-9]{1,7}(\.[0-9]{1,2})?$') then
        raise exception using errcode = 'TM422',
            message = 'Each discount needs a product id and a peso amount per piece (0 or more, at most 2 decimals).';
    end if;
    if exists (select 1 from jsonb_array_elements(p_items) e
                where not exists (select 1 from public.booking_items i
                                   where i.booking_id = p_booking_id and i.product_id = (e ->> 'product_id')::bigint)) then
        raise exception using errcode = 'TM422', message = 'A discounted product is not in this booking.';
    end if;
    select coalesce(sum(quantity * discount_per_unit), 0) into v_old from public.booking_items where booking_id = p_booking_id;

    for v_line in
        select i.item_id, i.quantity, i.unit_price, coalesce(i.unit_cost, p.capital_cost) as cost,
               p.min_markup, p.agent_discount_allowed, coalesce(p.product_name, 'product ' || i.product_id) as name,
               coalesce((select max((e ->> 'discount_per_unit')::numeric) from jsonb_array_elements(p_items) e
                          where (e ->> 'product_id')::bigint = i.product_id), 0) as wanted
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

-- ----------------------------------------------------------------------------------------------- 3
-- Totals: lines - item discounts - any old whole-order discount (on what is left after the items).
create or replace function public.refresh_booking_total()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    b int := coalesce(new.booking_id, old.booking_id);
    v_sum numeric;
    v_items numeric;
    v_order numeric;
begin
    select coalesce(sum(subtotal), 0), coalesce(sum(quantity * discount_per_unit), 0) into v_sum, v_items
      from public.booking_items where booking_id = b;
    select trimax_private.discount_amount_for(v_sum - v_items, discount_type, discount_value) into v_order
      from public.bookings where booking_id = b;
    v_order := coalesce(v_order, 0);
    update public.bookings
       set subtotal_amount = v_sum,
           discount_amount = v_items + v_order,
           total_amount = v_sum - v_items - v_order
     where booking_id = b
       and (subtotal_amount is distinct from v_sum
            or discount_amount is distinct from v_items + v_order
            or total_amount is distinct from v_sum - v_items - v_order);
    return null;
end $function$;

-- The same total, for one booking, without changing a line (used after a discount change).
create or replace function public.refresh_booking_total_for(p_booking_id integer)
returns void
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_sum numeric;
    v_items numeric;
    v_order numeric;
begin
    select coalesce(sum(subtotal), 0), coalesce(sum(quantity * discount_per_unit), 0) into v_sum, v_items
      from public.booking_items where booking_id = p_booking_id;
    select trimax_private.discount_amount_for(v_sum - v_items, discount_type, discount_value) into v_order
      from public.bookings where booking_id = p_booking_id;
    v_order := coalesce(v_order, 0);
    update public.bookings
       set subtotal_amount = v_sum, discount_amount = v_items + v_order, total_amount = v_sum - v_items - v_order
     where booking_id = p_booking_id
       and (subtotal_amount is distinct from v_sum or discount_amount is distinct from v_items + v_order
            or total_amount is distinct from v_sum - v_items - v_order);
end $function$;

-- ----------------------------------------------------------------------------------------------- 4
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

-- ----------------------------------------------------------------------------------------------- 5
-- Before booking: for each product, whether the agent may discount it and the most per piece.
create or replace function public.trimax_agent_discount_quote(p_items jsonb)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('AGENT');
    v_bad int;
    v_out jsonb;
begin
    if p_items is null or jsonb_typeof(p_items) <> 'array'
       or jsonb_array_length(p_items) = 0 or jsonb_array_length(p_items) > 50 then
        raise exception using errcode = 'TM422', message = 'Add between 1 and 50 products.';
    end if;
    select count(*) into v_bad
      from jsonb_array_elements(p_items) e
     where jsonb_typeof(e) <> 'object'
        or coalesce(e ->> 'product_id', '') !~ '^[0-9]{1,9}$'
        or coalesce(e ->> 'quantity', '') !~ '^[0-9]{1,9}$';
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
    with q as (
        select (e ->> 'product_id')::bigint as product_id, sum((e ->> 'quantity')::bigint) as quantity
          from jsonb_array_elements(p_items) e group by 1
    ), l as (
        select p.product_id, p.product_name, q.quantity, round(p.price, 2) as unit_price,
               trimax_private.item_discount_max(p.price, p.capital_cost, p.min_markup, p.agent_discount_allowed) as max_per_unit,
               case when not p.agent_discount_allowed then 'The Admin does not allow a discount on this item.'
                    when p.capital_cost is null then 'No discount yet: this item has no capital cost (puhunan).'
                    when trimax_private.item_discount_max(p.price, p.capital_cost, p.min_markup, true) <= 0
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
                            'product_id', l.product_id, 'product_name', l.product_name, 'quantity', l.quantity,
                            'unit_price', l.unit_price, 'max_discount_per_unit', l.max_per_unit,
                            'discount_allowed', l.max_per_unit > 0, 'reason', l.reason) order by l.product_id), '[]'::jsonb))
      into v_out
      from l;
    return v_out;
end $function$;

-- Agent changes the discounts per item of one of their own Pending bookings ([] removes them all).
create or replace function public.trimax_agent_set_item_discounts(p_booking_id integer, p_items jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('AGENT');
    v_status text;
begin
    select b.status into v_status from public.bookings b
     where b.booking_id = p_booking_id and b.created_by_username = v_me for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'Booking not found.';
    end if;
    if v_status <> 'Pending' then
        raise exception using errcode = 'TM409',
            message = 'The discount can only be changed while the booking is Pending (it is ' || v_status || ').';
    end if;
    perform trimax_private.apply_item_discounts(p_booking_id, v_me, coalesce(p_items, '[]'::jsonb));
    return trimax_private.booking_json(p_booking_id, false);
end $function$;

-- The old whole-order setter (older apps): removing works; giving a new whole-order discount is refused.
create or replace function public.trimax_agent_set_discount(p_booking_id integer, p_discount_type text,
                                                            p_discount_value numeric)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('AGENT');
begin
    if coalesce(p_discount_value, 0) <> 0 then
        raise exception using errcode = 'TM422', detail = 'discount_per_item_only',
            message = 'Discounts are now given per item. Update the TRIMAX app, then enter the discount on each item.';
    end if;
    return public.trimax_agent_set_item_discounts(p_booking_id, '[]'::jsonb);
end $function$;

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
    v_bad int;
    v_ids bigint[];
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
    -- One discount per product (a product listed twice must carry the same discount).
    if exists (select 1 from jsonb_array_elements(p_items) e
                group by (e ->> 'product_id')
               having count(distinct coalesce((e ->> 'discount_per_unit')::numeric, 0)) > 1) then
        raise exception using errcode = 'TM422', message = 'A product is listed twice with different discounts.';
    end if;
    select coalesce(jsonb_agg(jsonb_build_object('product_id', s.product_id, 'discount_per_unit', s.d)), '[]'::jsonb)
      into v_discounts
      from (select (e ->> 'product_id')::bigint as product_id, max(coalesce((e ->> 'discount_per_unit')::numeric, 0)) as d
              from jsonb_array_elements(p_items) e group by 1) s
     where s.d > 0;

    -- Merge repeated products into one line each, ordered by product id.
    select array_agg(s.product_id order by s.product_id), array_agg(s.quantity order by s.product_id)
      into v_ids, v_qtys
      from (select (e ->> 'product_id')::bigint as product_id, sum((e ->> 'quantity')::bigint) as quantity
              from jsonb_array_elements(p_items) e
             group by 1) s;
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

    -- ---- products: lock in a fixed order (no deadlock between two phones), then check stock
    perform 1 from public.products where product_id = any(v_ids) order by product_id for update;
    for v_line in
        select l.product_id, l.quantity, p.product_name, p.price, p.stock
          from unnest(v_ids, v_qtys) as l(product_id, quantity)
          left join public.products p on p.product_id = l.product_id
         order by l.product_id
    loop
        if v_line.product_name is null then
            raise exception using errcode = 'TM404', message = 'Product ' || v_line.product_id || ' no longer exists. Refresh the product list.';
        end if;
        if v_line.stock < v_line.quantity then
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

    insert into public.booking_items (booking_id, product_id, quantity, unit_price, subtotal)
    select v_booking_id, l.product_id, l.quantity, p.price, p.price * l.quantity
      from unnest(v_ids, v_qtys) as l(product_id, quantity)
      join public.products p on p.product_id = l.product_id
     order by l.product_id;

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
       set stock = p.stock - l.quantity
      from unnest(v_ids, v_qtys) as l(product_id, quantity)
     where p.product_id = l.product_id and p.stock >= l.quantity;
    get diagnostics v_lines = row_count;
    if v_lines <> cardinality(v_ids) then
        raise exception using errcode = 'TM409', message = 'Stock changed while saving. Please try again.';
    end if;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);

    insert into trimax_private.delivery_events (booking_id, actor_username, source, event_type, to_status, booking_status, note)
    values (v_booking_id, v_me, 'mobile', 'created', null, 'Pending',
            cardinality(v_ids) || ' product line(s) submitted from the mobile app');
    perform trimax_private.audit(v_me, 'booking.create', v_booking_id::text,
        'Created from the mobile app, ' || cardinality(v_ids) || ' item line(s), total ' || (v_total - v_discount)
        || case when v_discount > 0 then ' after item discounts of ' || v_discount else '' end);

    return trimax_private.booking_json(v_booking_id, false)
           || jsonb_build_object('already_existed', false, 'customer_matched_existing', true);
end $function$;

-- ----------------------------------------------------------------------------------------------- 6
drop function if exists trimax_private.save_product(text, integer, text, text, numeric, text, numeric, numeric, numeric, integer);
create or replace function trimax_private.save_product(
    p_admin text, p_product_id integer, p_name text, p_supplier text,
    p_capital_cost numeric, p_markup_type text, p_markup_value numeric, p_min_markup numeric,
    p_price numeric, p_initial_stock integer, p_agent_discount boolean default null)
returns integer
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_name text := nullif(btrim(p_name), '');
    v_supplier text := nullif(btrim(p_supplier), '');
    v_type text := nullif(upper(btrim(coalesce(p_markup_type, ''))), '');
    v_min numeric := coalesce(p_min_markup, 0);
    v_old public.products;
    v_new public.products;
    v_id integer;
    v_changes text := '';
begin
    perform trimax_private.require_admin(p_admin);
    if v_name is null or char_length(v_name) > 120 then
        raise exception using errcode = 'TM422', message = 'Enter the product name (up to 120 characters).';
    end if;
    if char_length(coalesce(v_supplier, '')) > 120 then
        raise exception using errcode = 'TM422', message = 'The supplier name is too long (up to 120 characters).';
    end if;
    if p_capital_cost is not null and (p_capital_cost < 0 or p_capital_cost > 10000000 or p_capital_cost <> round(p_capital_cost, 2)) then
        raise exception using errcode = 'TM422', message = 'Puhunan must be 0 or more, with at most 2 decimals.';
    end if;
    if v_type is not null and v_type not in ('AMOUNT', 'PERCENT') then
        raise exception using errcode = 'TM422', message = 'Patong must be a peso amount or a percent.';
    end if;
    if p_capital_cost is not null then
        if v_type is null or p_markup_value is null then
            raise exception using errcode = 'TM422', message = 'Enter the patong (markup) for this puhunan.';
        end if;
        if p_markup_value < 0 or p_markup_value <> round(p_markup_value, 2)
           or (v_type = 'AMOUNT' and p_markup_value > 10000000) or (v_type = 'PERCENT' and p_markup_value > 1000) then
            raise exception using errcode = 'TM422', message = 'Patong must be 0 or more (a percent at most 1000), with at most 2 decimals.';
        end if;
    else
        if v_type is not null or p_markup_value is not null then
            raise exception using errcode = 'TM422', message = 'Enter the puhunan before the patong.';
        end if;
        if p_price is null or p_price < 0 or p_price > 10000000 or p_price <> round(p_price, 2) then
            raise exception using errcode = 'TM422', message = 'Enter the selling price (0 or more, at most 2 decimals).';
        end if;
    end if;
    if v_min < 0 or v_min > 10000000 or v_min <> round(v_min, 2) then
        raise exception using errcode = 'TM422', message = 'Minimum patong must be 0 or more, with at most 2 decimals.';
    end if;
    -- Same rule as production's unique (product_name, supplier), also where that constraint is missing.
    if exists (select 1 from public.products p where p.product_name = v_name and p.supplier = v_supplier
                  and p.product_id is distinct from p_product_id) then
        raise exception using errcode = 'TM409', message = 'A product with this name and supplier already exists.';
    end if;

    begin
        if p_product_id is null then
            if p_initial_stock is null or p_initial_stock < 0 or p_initial_stock > 1000000 then
                raise exception using errcode = 'TM422', message = 'Enter the starting stock (0 to 1,000,000).';
            end if;
            perform trimax_private.set_stock_context('INITIAL', 'product', null, p_admin, 'Starting stock', null, null);
            insert into public.products (product_name, supplier, price, stock, capital_cost, markup_type, markup_value, min_markup,
                                         agent_discount_allowed)
            values (v_name, v_supplier, coalesce(trimax_private.selling_price(p_capital_cost, v_type, p_markup_value), p_price),
                    p_initial_stock, p_capital_cost,
                    case when p_capital_cost is not null then v_type end,
                    case when p_capital_cost is not null then p_markup_value end, v_min,
                    coalesce(p_agent_discount, false))
            returning * into v_new;
            perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
            perform trimax_private.audit_as(p_admin, 'product.create', 'product', v_new.product_id::text,
                v_new.product_name || ': price ' || trimax_private.peso(v_new.price)
                || coalesce(', puhunan ' || trimax_private.peso(v_new.capital_cost), ', no puhunan')
                || ', stock ' || v_new.stock
                || case when v_new.agent_discount_allowed then ', agents may discount' else '' end);
            return v_new.product_id;
        end if;

        select * into v_old from public.products p where p.product_id = p_product_id for update;
        if not found then
            raise exception using errcode = 'TM404', message = 'That product no longer exists.';
        end if;
        update public.products p
           set product_name = v_name, supplier = v_supplier,
               capital_cost = p_capital_cost,
               markup_type = case when p_capital_cost is not null then v_type end,
               markup_value = case when p_capital_cost is not null then p_markup_value end,
               min_markup = v_min,
               agent_discount_allowed = coalesce(p_agent_discount, p.agent_discount_allowed),
               price = coalesce(trimax_private.selling_price(p_capital_cost, v_type, p_markup_value), p_price)
         where p.product_id = p_product_id
        returning * into v_new;
    exception when unique_violation then
        raise exception using errcode = 'TM409', message = 'A product with this name and supplier already exists.';
    end;

    v_id := v_new.product_id;
    if v_new.product_name is distinct from v_old.product_name then
        v_changes := v_changes || '; name ' || v_old.product_name || ' -> ' || v_new.product_name;
    end if;
    if v_new.supplier is distinct from v_old.supplier then
        v_changes := v_changes || '; supplier ' || coalesce(v_old.supplier, '-') || ' -> ' || coalesce(v_new.supplier, '-');
    end if;
    if v_new.capital_cost is distinct from v_old.capital_cost then
        v_changes := v_changes || '; puhunan ' || coalesce(trimax_private.peso(v_old.capital_cost), 'none')
                     || ' -> ' || coalesce(trimax_private.peso(v_new.capital_cost), 'none');
    end if;
    if v_new.markup_type is distinct from v_old.markup_type or v_new.markup_value is distinct from v_old.markup_value then
        v_changes := v_changes || '; patong ' || coalesce(v_old.markup_value::text || ' ' || v_old.markup_type, 'none')
                     || ' -> ' || coalesce(v_new.markup_value::text || ' ' || v_new.markup_type, 'none');
    end if;
    if v_new.min_markup is distinct from v_old.min_markup then
        v_changes := v_changes || '; minimum patong ' || trimax_private.peso(v_old.min_markup) || ' -> ' || trimax_private.peso(v_new.min_markup);
    end if;
    if v_new.agent_discount_allowed is distinct from v_old.agent_discount_allowed then
        v_changes := v_changes || case when v_new.agent_discount_allowed then '; agents may now discount'
                                       else '; agents may no longer discount' end;
    end if;
    if v_new.price is distinct from v_old.price then
        v_changes := v_changes || '; price ' || trimax_private.peso(v_old.price) || ' -> ' || trimax_private.peso(v_new.price);
    end if;
    if v_changes <> '' then
        perform trimax_private.audit_as(p_admin, 'product.update', 'product', v_id::text,
                                        v_new.product_name || substr(v_changes, 1));
    end if;
    return v_id;
end $function$;

-- ----------------------------------------------------------------------------------------------- 7
-- Privileges
revoke all on function trimax_private.products_before_write() from public;
revoke all on function trimax_private.item_discount_max(numeric, numeric, numeric, boolean) from public;
revoke all on function trimax_private.apply_item_discounts(integer, text, jsonb) from public;
revoke all on function public.refresh_booking_total() from public, anon, authenticated;
revoke all on function public.refresh_booking_total_for(integer) from public, anon, authenticated;
revoke all on function trimax_private.booking_json(integer, boolean) from public;
revoke all on function trimax_private.save_product(text, integer, text, text, numeric, text, numeric, numeric, numeric, integer, boolean) from public;
revoke all on function public.trimax_agent_discount_quote(jsonb) from public, anon;
revoke all on function public.trimax_agent_set_item_discounts(integer, jsonb) from public, anon;
revoke all on function public.trimax_agent_set_discount(integer, text, numeric) from public, anon;
revoke all on function public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision,
                                                          jsonb, text, text, text, numeric) from public, anon;
grant execute on function public.trimax_agent_discount_quote(jsonb) to authenticated;
grant execute on function public.trimax_agent_set_item_discounts(integer, jsonb) to authenticated;
grant execute on function public.trimax_agent_set_discount(integer, text, numeric) to authenticated;
grant execute on function public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision,
                                                             jsonb, text, text, text, numeric) to authenticated;
do $$
begin
    execute 'revoke all on function public.refresh_booking_total_for(integer) from service_role';
    execute 'revoke all on function public.trimax_agent_discount_quote(jsonb) from service_role';
    execute 'revoke all on function public.trimax_agent_set_item_discounts(integer, jsonb) from service_role';
    execute 'revoke all on function public.trimax_agent_set_discount(integer, text, numeric) from service_role';
end $$;

commit;
