-- =====================================================================================================
-- Sales, inventory, payroll, agent discounts, customer photos and profit reports.
--
-- Client requirements of 2026-10-26 ("TRIMAX System Enhancement"). Business rules confirmed by the owner
-- on 2026-10-26:
--   * Walk-In  = a customer buys at the store, pays and takes the goods; no customer record is needed.
--     In-House = an Admin records a sale for a customer who is in the Customers list.
--     Both are immediate sales (not deliveries), so they are kept in public.sales / public.sale_items and
--     not in bookings (bookings need a customer and are delivered by a driver).
--   * Selling price = puhunan (capital cost) + patong (markup, a peso amount or a percent).
--   * An agent's booking discount may not take the booking below SUM(quantity x (puhunan + minimum
--     patong)). The minimum patong is set by the Admin per product (0 allowed). A product without puhunan
--     cannot be discounted. The phone is told the largest discount allowed for the order, never the cost.
--   * Profit: Net sales = sales - discounts; Gross profit = net sales - cost of goods sold (the puhunan
--     recorded on each sold line at the time of the sale); Net profit = gross profit - payroll.
--     A booking counts as a sale when it is Completed, dated when it was completed. A Walk-In / In-House
--     sale counts when it is Completed and Paid, dated when it was sold. Payroll counts by the end of its
--     pay period (paid and unpaid shown separately; voided records never count).
--   * Restocking is a stock purchase, never revenue.
--
-- What this adds (keeps every existing row; changes no existing value):
--   1. helpers: trimax_private.require_admin, audit_as, peso
--   2. products: capital_cost, markup_type, markup_value, min_markup (+ price kept = cost + markup)
--   3. public.inventory_movements: every stock change, written by a trigger on products.stock, with the
--      reason the caller gives through set_config('trimax.stock_*') (or "ADJUSTMENT" when none)
--   4. Desktop functions: save_product, restock_product, adjust_stock
--   5. bookings: completed_at, discount columns, booking_items.unit_cost (cost at the time of the sale)
--   6. phone (agent) functions: trimax_agent_discount_quote, trimax_agent_set_discount,
--      trimax_agent_save_customer, trimax_agent_list_customers; trimax_agent_create_booking gains a
--      discount and no longer creates customers without a photo
--   7. public.sales, public.sale_items + Desktop functions create_sale, void_sale, set_sale_paid
--   8. public.payroll_records + Desktop functions save_payroll, void_payroll
--   9. Desktop reports: profit_summary, profit_trend
--  10. customer photos: clients.photo_path, private bucket "customer-photos", Admin view tickets
--  11. privileges: new tables are invisible to the API roles; Desktop functions are owner-only
--
-- ORDER: after 20261026_01. Re-runnable. Rollback: 20261027_93_rollback_sales_inventory_payroll.sql.
-- APPLIED to production on 2026-10-27 (about 02:45 Asia/Manila) with the owner's approval, in three
-- transactions (sections 1-5, 6 and 7-11, each with the privileges of what it creates). Checked afterwards:
-- production's schema equals a database built from this file (100/100 fingerprints, including function
-- bodies and privileges), and no existing product, booking, booking line, client or agent value changed.
-- Edge Function trimax-admin-media v3 deployed the same night.
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.require_account(text)') is null
       or to_regprocedure('trimax_private.booking_json(integer,boolean)') is null
       or to_regprocedure('trimax_private.storage_object_uploaded(text,text,uuid,interval)') is null
       or to_regprocedure('trimax_private.issue_media_ticket(text,text,text)') is null
       or to_regprocedure('trimax_private.contact_key(text)') is null then
        raise exception 'Apply the earlier TRIMAX migrations first (20261023_01 to 20261026_01).';
    end if;
end $$;

-- ----------------------------------------------------------------------------------------------- 1
-- Helpers

create or replace function trimax_private.require_admin(p_username text)
returns text
language plpgsql
stable
set search_path = public, pg_temp
as $function$
begin
    if p_username is null or not exists (
        select 1 from public.users u join public.roles r on r.role_id = u.role_id
         where u.username = p_username and r.role_name = 'Admin' and u.account_status = 'Active') then
        raise exception using errcode = 'TM403', message = 'Only an active Admin can do this.';
    end if;
    return p_username;
end $function$;

create or replace function trimax_private.audit_as(p_actor text, p_action text, p_target_type text,
                                                   p_target_id text, p_details text)
returns void
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if to_regclass('public.audit_log') is not null then
        insert into public.audit_log (actor_username, action, target_type, target_id, result, details)
        values (p_actor, p_action, p_target_type, p_target_id, 'success', left(p_details, 1000));
    end if;
end $function$;

-- "₱1,234.50" for messages.
create or replace function trimax_private.peso(p numeric)
returns text
language sql
immutable
set search_path = public, pg_temp
as $function$
    select '₱' || to_char(coalesce(p, 0), 'FM999,999,999,990.00')
$function$;

-- ----------------------------------------------------------------------------------------------- 2
-- Product pricing: puhunan (capital_cost) + patong (markup) = selling price (price).
-- price stays the column every screen and the phone already read. When a product has a puhunan and a
-- patong the trigger keeps price = puhunan + patong; a product without puhunan keeps the price typed in.

alter table public.products add column if not exists capital_cost numeric(12, 2);
alter table public.products add column if not exists markup_type text;
alter table public.products add column if not exists markup_value numeric(12, 2);
alter table public.products add column if not exists min_markup numeric(12, 2) not null default 0;
alter table public.products add column if not exists pricing_updated_at timestamptz;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'products_capital_cost_chk') then
        alter table public.products add constraint products_capital_cost_chk
            check (capital_cost is null or capital_cost >= 0);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'products_markup_chk') then
        alter table public.products add constraint products_markup_chk
            check ((markup_type is null and markup_value is null)
                   or (markup_type in ('AMOUNT', 'PERCENT') and markup_value is not null and markup_value >= 0
                       and capital_cost is not null and (markup_type = 'AMOUNT' or markup_value <= 1000)));
    end if;
    if not exists (select 1 from pg_constraint where conname = 'products_min_markup_chk') then
        alter table public.products add constraint products_min_markup_chk check (min_markup >= 0);
    end if;
end $$;

create or replace function trimax_private.selling_price(p_cost numeric, p_type text, p_value numeric)
returns numeric
language sql
immutable
set search_path = public, pg_temp
as $function$
    select case when p_cost is null or p_type is null or p_value is null then null
                when p_type = 'AMOUNT' then round(p_cost + p_value, 2)
                when p_type = 'PERCENT' then round(p_cost * (1 + p_value / 100), 2) end
$function$;

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
       or new.min_markup is distinct from old.min_markup then
        new.pricing_updated_at := now();
    end if;
    return new;
end $function$;

drop trigger if exists trimax_products_before_write on public.products;
create trigger trimax_products_before_write before insert or update on public.products
    for each row execute function trimax_private.products_before_write();

-- ----------------------------------------------------------------------------------------------- 3
-- Inventory movements: one row per change of products.stock, written by a trigger so no code path
-- (Desktop, phone, a hand-made SQL change) can change stock without a trace.
-- The caller says why with transaction-local settings before it changes stock:
--   select set_config('trimax.stock_kind', 'RESTOCK', true);   -- see the kind list below
--   select set_config('trimax.stock_ref_type', 'booking', true), set_config('trimax.stock_ref_id', '42', true);
--   select set_config('trimax.stock_actor', '<admin username>', true);
--   select set_config('trimax.stock_note', '...', true), set_config('trimax.stock_unit_cost', '12.50', true);
-- Without a kind the change is recorded as ADJUSTMENT "without a TRIMAX reason".

create table if not exists public.inventory_movements (
    movement_id bigint generated always as identity primary key,
    product_id integer references public.products (product_id) on delete set null,
    product_name text not null,
    occurred_at timestamptz not null default now(),
    kind text not null,
    quantity_change integer not null,
    stock_before integer not null,
    stock_after integer not null,
    unit_cost numeric(12, 2),
    total_cost numeric(14, 2),
    reference_type text,
    reference_id text,
    actor_username text references public.users (username) on update cascade on delete set null,
    note text,
    client_request_id uuid,
    constraint inventory_movements_kind_chk check (kind in ('INITIAL', 'RESTOCK', 'ADJUSTMENT', 'BOOKING',
        'BOOKING_CANCELLED', 'BOOKING_CHANGED', 'SALE', 'SALE_VOIDED')),
    constraint inventory_movements_change_chk check (quantity_change <> 0
        and stock_after = stock_before + quantity_change and stock_after >= 0),
    constraint inventory_movements_cost_chk check ((unit_cost is null or unit_cost >= 0)
        and (total_cost is null or total_cost >= 0)),
    constraint inventory_movements_restock_chk check (kind <> 'RESTOCK'
        or (quantity_change > 0 and unit_cost is not null and total_cost is not null))
);
create unique index if not exists inventory_movements_request_uq
    on public.inventory_movements (client_request_id) where client_request_id is not null;
create index if not exists inventory_movements_product_idx on public.inventory_movements (product_id, occurred_at desc);
create index if not exists inventory_movements_time_idx on public.inventory_movements (occurred_at desc);

-- History is append-only. The only change allowed is the product_id being cleared when a product is
-- deleted (the row keeps the product's name).
create or replace function trimax_private.inventory_movements_append_only()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if tg_op = 'UPDATE' and new.product_id is null and old.product_id is not null
       and (to_jsonb(new) - 'product_id') = (to_jsonb(old) - 'product_id') then
        return new;
    end if;
    raise exception using errcode = 'TM409', message = 'Inventory history cannot be changed or deleted.';
end $function$;

drop trigger if exists trimax_inventory_movements_append_only on public.inventory_movements;
create trigger trimax_inventory_movements_append_only before update or delete on public.inventory_movements
    for each row execute function trimax_private.inventory_movements_append_only();

create or replace function trimax_private.set_stock_context(p_kind text, p_ref_type text, p_ref_id text,
                                                            p_actor text, p_note text,
                                                            p_unit_cost numeric default null,
                                                            p_request_id uuid default null)
returns void
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    perform set_config('trimax.stock_kind', coalesce(p_kind, ''), true);
    perform set_config('trimax.stock_ref_type', coalesce(p_ref_type, ''), true);
    perform set_config('trimax.stock_ref_id', coalesce(p_ref_id, ''), true);
    perform set_config('trimax.stock_actor', coalesce(p_actor, ''), true);
    perform set_config('trimax.stock_note', coalesce(p_note, ''), true);
    perform set_config('trimax.stock_unit_cost', coalesce(p_unit_cost::text, ''), true);
    perform set_config('trimax.stock_request_id', coalesce(p_request_id::text, ''), true);
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
    v_before integer;
    v_change integer;
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
        v_change := new.stock - old.stock;
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

drop trigger if exists trimax_products_stock_moved on public.products;
create trigger trimax_products_stock_moved after insert or update of stock on public.products
    for each row execute function trimax_private.products_stock_moved();

-- ----------------------------------------------------------------------------------------------- 4
-- Desktop product functions (database owner over JDBC; the Admin's username is passed and checked).

-- Add (p_product_id null) or edit a product. Editing never changes stock: stock only moves through a
-- restock, an adjustment with a reason, a booking or a sale. With a puhunan the patong is required and
-- the selling price is calculated; without one the typed price is kept (older products).
create or replace function trimax_private.save_product(
    p_admin text, p_product_id integer, p_name text, p_supplier text,
    p_capital_cost numeric, p_markup_type text, p_markup_value numeric, p_min_markup numeric,
    p_price numeric, p_initial_stock integer)
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
            insert into public.products (product_name, supplier, price, stock, capital_cost, markup_type, markup_value, min_markup)
            values (v_name, v_supplier, coalesce(trimax_private.selling_price(p_capital_cost, v_type, p_markup_value), p_price),
                    p_initial_stock, p_capital_cost,
                    case when p_capital_cost is not null then v_type end,
                    case when p_capital_cost is not null then p_markup_value end, v_min)
            returning * into v_new;
            perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
            perform trimax_private.audit_as(p_admin, 'product.create', 'product', v_new.product_id::text,
                v_new.product_name || ': price ' || trimax_private.peso(v_new.price)
                || coalesce(', puhunan ' || trimax_private.peso(v_new.capital_cost), ', no puhunan')
                || ', stock ' || v_new.stock);
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
    if v_new.price is distinct from v_old.price then
        v_changes := v_changes || '; price ' || trimax_private.peso(v_old.price) || ' -> ' || trimax_private.peso(v_new.price);
    end if;
    if v_changes <> '' then
        perform trimax_private.audit_as(p_admin, 'product.update', 'product', v_id::text,
                                        v_new.product_name || substr(v_changes, 1));
    end if;
    return v_id;
end $function$;

-- Restock: adds stock and records the purchase (quantity, cost per unit, total). Never revenue.
-- p_update_cost = true also makes this cost the product's puhunan (the price follows if it has a patong).
-- A retry with the same request id returns the first result instead of adding the stock twice.
create or replace function trimax_private.restock_product(
    p_admin text, p_product_id integer, p_quantity integer, p_unit_cost numeric, p_note text,
    p_update_cost boolean, p_request_id uuid)
returns table (movement_id bigint, stock_before integer, stock_after integer, total_cost numeric,
               price numeric, already_existed boolean)
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

-- Stock correction with a reason (count difference, damaged, expired...). p_change is added (+) or
-- removed (-); stock never goes below 0.
create or replace function trimax_private.adjust_stock(
    p_admin text, p_product_id integer, p_change integer, p_reason text, p_request_id uuid)
returns table (movement_id bigint, stock_before integer, stock_after integer, already_existed boolean)
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
    if p_change is null or p_change = 0 or abs(p_change) > 1000000 then
        raise exception using errcode = 'TM422', message = 'Enter how many to add (+) or remove (-), not 0.';
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
        v_product.product_name || ': ' || case when p_change > 0 then '+' else '' end || p_change
        || ', stock ' || v_mov.stock_before || ' -> ' || v_mov.stock_after || ' (' || v_reason || ')');
    return query select v_mov.movement_id, v_mov.stock_before, v_mov.stock_after, false;
end $function$;

-- ----------------------------------------------------------------------------------------------- 5
-- Bookings: when they were completed, the cost of every line at the time of the sale, and discounts.

alter table public.bookings add column if not exists completed_at timestamptz;
alter table public.bookings add column if not exists subtotal_amount numeric(12, 2);
alter table public.bookings add column if not exists discount_type text;
alter table public.bookings add column if not exists discount_value numeric(12, 2);
alter table public.bookings add column if not exists discount_amount numeric(12, 2) not null default 0;
alter table public.bookings add column if not exists discount_by text;
alter table public.bookings add column if not exists discount_at timestamptz;
alter table public.booking_items add column if not exists unit_cost numeric(12, 2);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'bookings_discount_chk') then
        alter table public.bookings add constraint bookings_discount_chk
            check (discount_amount >= 0
                   and (discount_type is null or discount_type in ('AMOUNT', 'PERCENT'))
                   and ((discount_type is null) = (discount_value is null))
                   and (discount_value is null or discount_value >= 0)
                   and (discount_type is distinct from 'PERCENT' or discount_value <= 100));
    end if;
    if not exists (select 1 from pg_constraint where conname = 'bookings_discount_by_fkey') then
        alter table public.bookings add constraint bookings_discount_by_fkey foreign key (discount_by)
            references public.users (username) on update cascade on delete set null;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'booking_items_unit_cost_chk') then
        alter table public.booking_items add constraint booking_items_unit_cost_chk check (unit_cost is null or unit_cost >= 0);
    end if;
end $$;

-- When a booking counts as a sale: when it was completed; older bookings (before completed_at existed)
-- fall back to the delivery time, then to the booking date.
create or replace function trimax_private.booking_sale_time(p_completed_at timestamptz, p_delivery_status text,
                                                            p_delivery_status_at timestamptz, p_transc_date timestamptz)
returns timestamptz
language sql
immutable
set search_path = public, pg_temp
as $function$
    select coalesce(p_completed_at, case when p_delivery_status = 'DELIVERED' then p_delivery_status_at end, p_transc_date)
$function$;

create or replace function trimax_private.bookings_completed_at()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if new.status = 'Completed' then
        if tg_op = 'INSERT' or old.status is distinct from 'Completed' then
            new.completed_at := now();
        end if;
    else
        new.completed_at := null;
    end if;
    return new;
end $function$;

drop trigger if exists trimax_bookings_completed_at on public.bookings;
create trigger trimax_bookings_completed_at before insert or update of status on public.bookings
    for each row execute function trimax_private.bookings_completed_at();

-- The puhunan of each booking line when it was booked, so reports stay right when costs change later.
create or replace function trimax_private.booking_items_capture_cost()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if tg_op = 'INSERT' and new.unit_cost is null
       or tg_op = 'UPDATE' and new.product_id is distinct from old.product_id then
        select p.capital_cost into new.unit_cost from public.products p where p.product_id = new.product_id;
    end if;
    return new;
end $function$;

drop trigger if exists trimax_booking_items_capture_cost on public.booking_items;
create trigger trimax_booking_items_capture_cost before insert or update of product_id on public.booking_items
    for each row execute function trimax_private.booking_items_capture_cost();

create or replace function trimax_private.discount_amount_for(p_subtotal numeric, p_type text, p_value numeric)
returns numeric
language sql
immutable
set search_path = public, pg_temp
as $function$
    select case p_type when 'AMOUNT' then least(round(p_value, 2), coalesce(p_subtotal, 0))
                       when 'PERCENT' then round(coalesce(p_subtotal, 0) * p_value / 100, 2)
                       else 0 end
$function$;

-- Same trigger as before (total = sum of the lines), now also keeping the subtotal and the discount.
create or replace function public.refresh_booking_total()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    b int := coalesce(new.booking_id, old.booking_id);
    v_sum numeric;
begin
    select coalesce(sum(subtotal), 0) into v_sum from public.booking_items where booking_id = b;
    update public.bookings
       set subtotal_amount = v_sum,
           discount_amount = trimax_private.discount_amount_for(v_sum, discount_type, discount_value),
           total_amount = v_sum - trimax_private.discount_amount_for(v_sum, discount_type, discount_value)
     where booking_id = b
       and (subtotal_amount is distinct from v_sum
            or total_amount is distinct from v_sum - trimax_private.discount_amount_for(v_sum, discount_type, discount_value));
    return null;
end $function$;

drop trigger if exists trg_booking_items_total on public.booking_items;
create trigger trg_booking_items_total after insert or delete or update on public.booking_items
    for each row execute function public.refresh_booking_total();
revoke execute on function public.refresh_booking_total() from public, anon, authenticated;

-- Discount limits for a set of lines: the sum, the floor (puhunan + minimum patong, per unit) and the
-- products that have no puhunan (no discount is allowed then).
create or replace function trimax_private.discount_limits_for(p_booking_id integer, p_ids bigint[], p_qtys bigint[])
returns table (subtotal numeric, floor_total numeric, max_discount numeric, missing text)
language sql
stable
set search_path = public, pg_temp
as $function$
    with lines as (
        -- a line booked before costs were recorded uses the product's puhunan now (the stricter guess)
        select i.subtotal, i.quantity, coalesce(i.unit_cost, p.capital_cost) as cost, coalesce(p.min_markup, 0) as min_markup,
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

-- Sets (or clears, with no type or a 0 value) the discount of a booking after checking the limits.
create or replace function trimax_private.apply_booking_discount(p_booking_id integer, p_actor text,
                                                                 p_type text, p_value numeric)
returns numeric
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_type text := nullif(upper(btrim(coalesce(p_type, ''))), '');
    v_lim record;
    v_amount numeric;
begin
    select * into v_lim from trimax_private.discount_limits_for(p_booking_id, null, null);
    if coalesce(p_value, 0) = 0 then
        update public.bookings
           set discount_type = null, discount_value = null, discount_amount = 0,
               subtotal_amount = v_lim.subtotal, total_amount = v_lim.subtotal,
               discount_by = case when discount_amount > 0 then p_actor else discount_by end,
               discount_at = case when discount_amount > 0 then now() else discount_at end
         where booking_id = p_booking_id;
        return 0;
    end if;
    if v_type is null or v_type not in ('AMOUNT', 'PERCENT') then
        raise exception using errcode = 'TM422', message = 'Choose a peso amount or a percent discount.';
    end if;
    if p_value < 0 or p_value <> round(p_value, 2) then
        raise exception using errcode = 'TM422', message = 'Enter a discount of 0 or more (at most 2 decimals).';
    end if;
    if v_type = 'PERCENT' and p_value > 100 then
        raise exception using errcode = 'TM422', message = 'A percent discount must be from 0 to 100.';
    end if;
    v_amount := trimax_private.discount_amount_for(v_lim.subtotal, v_type, p_value);
    if v_type = 'AMOUNT' and p_value > v_lim.subtotal then
        raise exception using errcode = 'TM422',
            message = 'The discount is more than the order total (' || trimax_private.peso(v_lim.subtotal) || ').';
    end if;
    if v_lim.missing is not null then
        raise exception using errcode = 'TM422', detail = 'discount_not_configured',
            message = 'No discount is allowed for this order yet: there is no capital cost (puhunan) for '
                      || v_lim.missing || '. Ask the Admin to set it.';
    end if;
    if v_lim.subtotal - v_amount < v_lim.floor_total then
        raise exception using errcode = 'TM422', detail = 'discount_too_big',
            message = 'This discount is too big. The most you can give for this order is '
                      || trimax_private.peso(v_lim.max_discount) || '.';
    end if;
    update public.bookings
       set discount_type = v_type, discount_value = p_value, discount_amount = v_amount,
           subtotal_amount = v_lim.subtotal, total_amount = v_lim.subtotal - v_amount,
           discount_by = p_actor, discount_at = now()
     where booking_id = p_booking_id;
    perform trimax_private.audit_as(p_actor, 'booking.discount', 'booking', p_booking_id::text,
        'Original ' || trimax_private.peso(v_lim.subtotal) || ', discount ' || trimax_private.peso(v_amount)
        || case when v_type = 'PERCENT' then ' (' || p_value || '%)' else '' end
        || ', final ' || trimax_private.peso(v_lim.subtotal - v_amount));
    return v_amount;
end $function$;

-- booking_json (phone): adds the subtotal and the discount; never the cost.
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
            'type', b.discount_type,
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

-- ----------------------------------------------------------------------------------------------- 6
-- Customer photos (private bucket; the agent's phone uploads to its own folder) and agent functions.

alter table public.clients add column if not exists photo_path text;
alter table public.clients add column if not exists photo_updated_at timestamptz;
alter table public.clients add column if not exists photo_by text;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'clients_photo_path_chk') then
        alter table public.clients add constraint clients_photo_path_chk check (photo_path is null
            or photo_path ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/customer_[A-Za-z0-9_-]{1,80}\.jpg$');
    end if;
    if to_regclass('storage.buckets') is null then
        raise notice 'storage schema not present (not Supabase): skipping the customer-photos bucket';
        return;
    end if;
    insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
    values ('customer-photos', 'customer-photos', false, 5242880, array['image/jpeg'])
    on conflict (id) do nothing;
    if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects'
                   and policyname = 'trimax customer photo upload own folder') then
        create policy "trimax customer photo upload own folder" on storage.objects
            for insert to authenticated
            with check (bucket_id = 'customer-photos' and (storage.foldername(name))[1] = (select (auth.uid())::text));
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects'
                   and policyname = 'trimax customer photo read own folder') then
        create policy "trimax customer photo read own folder" on storage.objects
            for select to authenticated
            using (bucket_id = 'customer-photos' and (storage.foldername(name))[1] = (select (auth.uid())::text));
    end if;
end $$;

-- Agent saves a customer. A NEW customer needs a photo the phone has just uploaded to its own folder of
-- "customer-photos". An existing customer of this agent (same number) is updated; a photo is optional
-- then. A number that belongs to another agent's customer is refused, as in booking.
create or replace function public.trimax_agent_save_customer(
    p_name text, p_contact text, p_address text, p_latitude double precision, p_longitude double precision,
    p_photo_path text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('AGENT');
    v_uid uuid := auth.uid();
    v_name text := nullif(btrim(p_name), '');
    v_contact text := nullif(btrim(p_contact), '');
    v_key text := trimax_private.contact_key(p_contact);
    v_address text := nullif(btrim(p_address), '');
    v_photo text := nullif(btrim(p_photo_path), '');
    v_client public.clients;
    v_client_id integer;
    v_mine boolean;
    v_created boolean := false;
begin
    if v_name is null or char_length(v_name) > 100 then
        raise exception using errcode = 'TM422', message = 'Enter the customer name (up to 100 characters).';
    end if;
    if v_contact is null or v_key is null or char_length(v_contact) > 30 then
        raise exception using errcode = 'TM422', message = 'Enter a valid customer contact number.';
    end if;
    if v_address is null or char_length(v_address) > 500 then
        raise exception using errcode = 'TM422', message = 'Choose the customer''s location.';
    end if;
    if p_latitude is null or p_longitude is null or not (p_latitude between -90 and 90)
       or not (p_longitude between -180 and 180) or (p_latitude = 0 and p_longitude = 0) then
        raise exception using errcode = 'TM422', message = 'Choose the customer''s location on the map.';
    end if;
    if v_photo is not null then
        if v_photo !~ ('^' || v_uid::text || '/customer_[A-Za-z0-9_-]{1,80}\.jpg$') then
            raise exception using errcode = 'TM422', message = 'Not a customer photo from this phone.';
        end if;
        if not trimax_private.storage_object_uploaded('customer-photos', v_photo, v_uid, interval '1 day') then
            raise exception using errcode = 'TM422', detail = 'customer_photo_missing',
                message = 'The customer photo has not finished uploading. Try again.';
        end if;
    end if;

    perform pg_advisory_xact_lock(hashtextextended('trimax-client-contact:' || v_key, 0));
    select c.client_id, exists (select 1 from public.agents a where a.client_id = c.client_id and a.username = v_me)
      into v_client_id, v_mine
      from public.clients c
     where trimax_private.contact_key(c.contact_num) = v_key
     order by 2 desc, c.client_id
     limit 1;
    if v_client_id is not null and not v_mine then
        raise exception using errcode = 'TM409',
            message = 'A customer with this contact number is already registered and is not assigned to you. '
                      || 'Ask an administrator to assign that customer to you.';
    end if;

    if v_client_id is null then
        if v_photo is null then
            raise exception using errcode = 'TM422', detail = 'customer_photo_required',
                message = 'Take or choose a photo of the customer before saving.';
        end if;
        insert into public.clients (client_name, address, contact_num, latitude, longitude,
                                    photo_path, photo_updated_at, photo_by)
        values (v_name, v_address, v_contact, p_latitude, p_longitude, v_photo, now(), v_me)
        returning * into v_client;
        insert into public.agents (username, client_id) values (v_me, v_client.client_id);
        v_created := true;
        perform trimax_private.audit_as(v_me, 'customer.create', 'client', v_client.client_id::text,
                                        v_name || ' (' || v_contact || '), with photo, from the mobile app');
    else
        update public.clients c
           set client_name = v_name, address = v_address, contact_num = v_contact,
               latitude = p_latitude, longitude = p_longitude,
               photo_path = coalesce(v_photo, c.photo_path),
               photo_updated_at = case when v_photo is not null then now() else c.photo_updated_at end,
               photo_by = case when v_photo is not null then v_me else c.photo_by end
         where c.client_id = v_client_id
        returning * into v_client;
        perform trimax_private.audit_as(v_me, 'customer.update', 'client', v_client.client_id::text,
            v_name || ' (' || v_contact || ')' || case when v_photo is not null then ', new photo' else '' end
            || ', from the mobile app');
    end if;

    return jsonb_build_object('client_id', v_client.client_id, 'name', v_client.client_name,
        'contact', v_client.contact_num, 'address', v_client.address, 'latitude', v_client.latitude,
        'longitude', v_client.longitude, 'photo_path', v_client.photo_path,
        'photo_updated_at', v_client.photo_updated_at, 'created', v_created);
end $function$;

create or replace function public.trimax_agent_list_customers()
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('AGENT');
begin
    return coalesce((
        select jsonb_agg(jsonb_build_object(
                   'client_id', c.client_id, 'name', c.client_name, 'contact', c.contact_num,
                   'address', c.address, 'latitude', c.latitude, 'longitude', c.longitude,
                   'photo_path', c.photo_path, 'photo_updated_at', c.photo_updated_at)
                   order by lower(c.client_name), c.client_id)
          from public.clients c
         where exists (select 1 from public.agents a where a.client_id = c.client_id and a.username = v_me)), '[]'::jsonb);
end $function$;

-- Before booking: the largest discount the agent may give for these products and quantities.
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
    v_ids bigint[];
    v_qtys bigint[];
    v_lim record;
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
    select array_agg(s.product_id order by s.product_id), array_agg(s.quantity order by s.product_id)
      into v_ids, v_qtys
      from (select (e ->> 'product_id')::bigint as product_id, sum((e ->> 'quantity')::bigint) as quantity
              from jsonb_array_elements(p_items) e group by 1) s;
    if exists (select 1 from unnest(v_qtys) q where q < 1 or q > 1000) then
        raise exception using errcode = 'TM422', message = 'Each quantity must be between 1 and 1000.';
    end if;
    if exists (select 1 from unnest(v_ids) i where i > 2147483647
               or not exists (select 1 from public.products p where p.product_id = i)) then
        raise exception using errcode = 'TM404', message = 'A product no longer exists. Refresh the product list.';
    end if;
    select * into v_lim from trimax_private.discount_limits_for(null, v_ids, v_qtys);
    return jsonb_build_object(
        'subtotal', v_lim.subtotal,
        'max_discount', v_lim.max_discount,
        'discount_available', v_lim.missing is null and v_lim.max_discount > 0,
        'reason', case when v_lim.missing is not null
                       then 'No discount is allowed for this order yet: there is no capital cost (puhunan) for '
                            || v_lim.missing || '. Ask the Admin to set it.'
                       when v_lim.max_discount <= 0 then 'No discount is possible for this order at its current prices.'
                  end);
end $function$;

-- Agent changes (or removes, with a 0 value) the discount of one of their own Pending bookings.
create or replace function public.trimax_agent_set_discount(p_booking_id integer, p_discount_type text,
                                                            p_discount_value numeric)
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
    perform trimax_private.apply_booking_discount(p_booking_id, v_me, p_discount_type, p_discount_value);
    return trimax_private.booking_json(p_booking_id, false);
end $function$;

-- Booking from the phone: as before, plus an optional discount (checked here, never trusted from the
-- phone), stock movements labelled BOOKING, and customers must be saved with a photo first
-- (trimax_agent_save_customer). The old 9-argument version is replaced so PostgREST sees one function.
drop function if exists public.trimax_agent_create_booking(uuid, text, text, text, double precision,
                                                           double precision, jsonb, text, text);
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
        or coalesce(e ->> 'quantity', '') !~ '^[0-9]{1,9}$';
    if v_bad > 0 then
        raise exception using errcode = 'TM422', message = 'Each product needs a product id and a whole-number quantity.';
    end if;

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

    if coalesce(p_discount_value, 0) <> 0 then
        v_discount := trimax_private.apply_booking_discount(v_booking_id, v_me, p_discount_type, p_discount_value);
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
        || case when v_discount > 0 then ' after a discount of ' || v_discount else '' end);

    return trimax_private.booking_json(v_booking_id, false)
           || jsonb_build_object('already_existed', false, 'customer_matched_existing', true);
end $function$;

-- ----------------------------------------------------------------------------------------------- 7
-- Walk-In and In-House sales (Desktop). Prices and costs are taken from products here, never from the
-- screen; the sale, its lines and the stock change are saved together or not at all.

create sequence if not exists public.sales_receipt_seq;

create table if not exists public.sales (
    sale_id bigint generated always as identity primary key,
    receipt_no text not null unique,
    sale_type text not null,
    client_id integer references public.clients (client_id) on delete restrict,
    customer_name text,
    status text not null default 'COMPLETED',
    payment_status text not null,
    payment_method text,
    amount_tendered numeric(14, 2),
    change_due numeric(14, 2),
    paid_at timestamptz,
    subtotal_amount numeric(14, 2) not null,
    discount_amount numeric(14, 2) not null default 0,
    total_amount numeric(14, 2) not null,
    cost_amount numeric(14, 2) not null default 0,
    cost_missing_lines integer not null default 0,
    note text,
    sold_at timestamptz not null default now(),
    created_by text not null references public.users (username) on update cascade,
    voided_at timestamptz,
    voided_by text references public.users (username) on update cascade,
    void_reason text,
    client_request_id uuid not null unique,
    constraint sales_type_chk check (sale_type in ('WALK_IN', 'IN_HOUSE')),
    constraint sales_customer_chk check ((sale_type = 'IN_HOUSE' and client_id is not null)
                                         or (sale_type = 'WALK_IN' and client_id is null)),
    constraint sales_status_chk check (status in ('COMPLETED', 'VOIDED')
                                       and ((status = 'VOIDED') = (voided_at is not null))),
    constraint sales_payment_chk check (payment_status in ('PAID', 'UNPAID')
        and ((payment_status = 'PAID') = (paid_at is not null))
        and (payment_status = 'PAID' or (payment_method is null and amount_tendered is null))
        and (payment_method is null or payment_method in ('CASH', 'GCASH', 'BANK_TRANSFER', 'OTHER'))),
    constraint sales_amounts_chk check (subtotal_amount >= 0 and discount_amount >= 0
        and total_amount = subtotal_amount - discount_amount and total_amount >= 0 and cost_amount >= 0
        and (amount_tendered is null or (amount_tendered >= total_amount and change_due = amount_tendered - total_amount)))
);
create index if not exists sales_sold_at_idx on public.sales (sold_at desc);
create index if not exists sales_client_idx on public.sales (client_id);

create table if not exists public.sale_items (
    sale_item_id bigint generated always as identity primary key,
    sale_id bigint not null references public.sales (sale_id) on delete restrict,
    line_no integer not null,
    product_id integer not null references public.products (product_id) on delete restrict,
    product_name text not null,
    quantity integer not null,
    unit_price numeric(12, 2) not null,
    unit_cost numeric(12, 2),
    line_subtotal numeric(14, 2) not null,
    line_discount numeric(14, 2) not null default 0,
    line_total numeric(14, 2) not null,
    constraint sale_items_line_uq unique (sale_id, line_no),
    constraint sale_items_amounts_chk check (quantity between 1 and 100000 and unit_price >= 0
        and (unit_cost is null or unit_cost >= 0) and line_subtotal = quantity * unit_price
        and line_discount >= 0 and line_total = line_subtotal - line_discount and line_total >= 0)
);
create index if not exists sale_items_sale_idx on public.sale_items (sale_id);
create index if not exists sale_items_product_idx on public.sale_items (product_id);

-- Lines are never edited; a wrong sale is voided (stock goes back) and entered again.
create or replace function trimax_private.sale_items_read_only()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    raise exception using errcode = 'TM409', message = 'Sale lines cannot be changed. Void the sale and enter it again.';
end $function$;

drop trigger if exists trimax_sale_items_read_only on public.sale_items;
create trigger trimax_sale_items_read_only before update or delete on public.sale_items
    for each row execute function trimax_private.sale_items_read_only();

create or replace function trimax_private.create_sale(
    p_admin text, p_request_id uuid, p_sale_type text, p_client_id integer, p_customer_name text,
    p_items jsonb, p_payment_status text, p_payment_method text, p_amount_tendered numeric, p_note text)
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
        or coalesce(jsonb_typeof(e -> 'discount'), 'null') not in ('number', 'null');
    if v_bad > 0 then
        raise exception using errcode = 'TM422',
            message = 'Each product needs a product id, a whole-number quantity and a discount amount.';
    end if;
    select array_agg(s.product_id order by s.product_id), array_agg(s.quantity order by s.product_id),
           array_agg(s.discount order by s.product_id)
      into v_ids, v_qtys, v_discs
      from (select (e ->> 'product_id')::bigint as product_id, sum((e ->> 'quantity')::bigint) as quantity,
                   sum(coalesce((e ->> 'discount')::numeric, 0)) as discount
              from jsonb_array_elements(p_items) e group by 1) s;
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
        select l.product_id, l.quantity, l.discount, p.product_name, round(p.price, 2) as price, p.stock
          from unnest(v_ids, v_qtys, v_discs) as l(product_id, quantity, discount)
          left join public.products p on p.product_id = l.product_id
         order by l.product_id
    loop
        if v_line.product_name is null then
            raise exception using errcode = 'TM404',
                message = 'Product ' || v_line.product_id || ' no longer exists. Refresh the product list.';
        end if;
        if v_line.stock < v_line.quantity then
            raise exception using errcode = 'TM409',
                message = 'Not enough stock for "' || v_line.product_name || '" (' || v_line.stock || ' left).';
        end if;
        if v_line.discount > v_line.price * v_line.quantity then
            raise exception using errcode = 'TM422',
                message = 'The discount for "' || v_line.product_name || '" is more than its subtotal ('
                          || trimax_private.peso(v_line.price * v_line.quantity) || ').';
        end if;
    end loop;

    select sum(round(p.price, 2) * l.quantity), sum(l.discount),
           coalesce(sum(p.capital_cost * l.quantity) filter (where p.capital_cost is not null), 0),
           count(*) filter (where p.capital_cost is null)
      into v_sub, v_disc, v_cost, v_missing
      from unnest(v_ids, v_qtys, v_discs) as l(product_id, quantity, discount)
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

    insert into public.sale_items (sale_id, line_no, product_id, product_name, quantity, unit_price, unit_cost,
                                   line_subtotal, line_discount, line_total)
    select v_sale_id, row_number() over (order by l.product_id), l.product_id, p.product_name, l.quantity,
           round(p.price, 2), p.capital_cost, round(p.price, 2) * l.quantity, l.discount,
           round(p.price, 2) * l.quantity - l.discount
      from unnest(v_ids, v_qtys, v_discs) as l(product_id, quantity, discount)
      join public.products p on p.product_id = l.product_id
     order by l.product_id;

    perform trimax_private.set_stock_context('SALE', 'sale', v_receipt, p_admin, null, null, null);
    update public.products p
       set stock = p.stock - l.quantity
      from unnest(v_ids, v_qtys) as l(product_id, quantity)
     where p.product_id = l.product_id and p.stock >= l.quantity;
    get diagnostics v_lines = row_count;
    if v_lines <> cardinality(v_ids) then
        raise exception using errcode = 'TM409', message = 'Stock changed while saving. Please try again.';
    end if;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);

    perform trimax_private.audit_as(p_admin, 'sale.create', 'sale', v_receipt,
        case v_type when 'WALK_IN' then 'Walk-In' else 'In-House' end
        || coalesce(' for ' || v_name, '') || ', ' || cardinality(v_ids) || ' line(s), total '
        || trimax_private.peso(v_total)
        || case when v_disc > 0 then ' after ' || trimax_private.peso(v_disc) || ' discount' else '' end
        || ', ' || lower(v_pay) || coalesce(' by ' || lower(v_method), ''));
    return query select v_sale_id, v_receipt, v_total, false;
end $function$;

-- Void: the stock comes back (SALE_VOIDED movements), the sale stays on record with who/when/why.
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
      from (select i.product_id, sum(i.quantity) as qty from public.sale_items i
             where i.sale_id = p_sale_id group by i.product_id) s
     where p.product_id = s.product_id;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
    update public.sales s set status = 'VOIDED', voided_at = now(), voided_by = p_admin, void_reason = v_reason
     where s.sale_id = p_sale_id;
    perform trimax_private.audit_as(p_admin, 'sale.void', 'sale', v_sale.receipt_no,
        'Voided ' || trimax_private.peso(v_sale.total_amount) || ', stock returned (' || v_reason || ')');
end $function$;

-- An unpaid sale is paid later.
create or replace function trimax_private.set_sale_paid(p_admin text, p_sale_id bigint, p_payment_method text)
returns void
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_method text := nullif(upper(btrim(coalesce(p_payment_method, ''))), '');
    v_sale public.sales;
begin
    perform trimax_private.require_admin(p_admin);
    if v_method is null or v_method not in ('CASH', 'GCASH', 'BANK_TRANSFER', 'OTHER') then
        raise exception using errcode = 'TM422', message = 'Choose how the customer paid.';
    end if;
    select * into v_sale from public.sales s where s.sale_id = p_sale_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'Sale not found.';
    end if;
    if v_sale.status = 'VOIDED' then
        raise exception using errcode = 'TM409', message = 'This sale is voided.';
    end if;
    if v_sale.payment_status = 'PAID' then
        raise exception using errcode = 'TM409', message = 'This sale is already paid.';
    end if;
    update public.sales s set payment_status = 'PAID', payment_method = v_method, paid_at = now()
     where s.sale_id = p_sale_id;
    perform trimax_private.audit_as(p_admin, 'sale.paid', 'sale', v_sale.receipt_no,
        trimax_private.peso(v_sale.total_amount) || ' received by ' || lower(v_method));
end $function$;

-- ----------------------------------------------------------------------------------------------- 8
-- Payroll (Admin only; never visible to the phone).

create table if not exists public.payroll_records (
    payroll_id bigint generated always as identity primary key,
    employee_username text references public.users (username) on update cascade on delete set null,
    employee_name text not null,
    period_start date not null,
    period_end date not null,
    basic_pay numeric(12, 2) not null,
    allowances numeric(12, 2) not null default 0,
    deductions numeric(12, 2) not null default 0,
    net_pay numeric(12, 2) generated always as (basic_pay + allowances - deductions) stored,
    status text not null default 'UNPAID',
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
    constraint payroll_period_chk check (period_end >= period_start and period_end - period_start <= 92),
    constraint payroll_amounts_chk check (basic_pay >= 0 and allowances >= 0 and deductions >= 0
                                          and basic_pay + allowances - deductions >= 0),
    constraint payroll_status_chk check (status in ('UNPAID', 'PAID', 'VOID')
        and (status <> 'PAID' or paid_at is not null) and (status <> 'UNPAID' or paid_at is null)
        and ((status = 'VOID') = (voided_at is not null))),
    constraint payroll_method_chk check (payment_method is null
        or payment_method in ('CASH', 'GCASH', 'BANK_TRANSFER', 'OTHER'))
);
-- One record per employee per pay period (voided ones excepted), so payroll is never counted twice.
create unique index if not exists payroll_records_user_period_uq
    on public.payroll_records (employee_username, period_start, period_end)
    where status <> 'VOID' and employee_username is not null;
create unique index if not exists payroll_records_name_period_uq
    on public.payroll_records (lower(employee_name), period_start, period_end)
    where status <> 'VOID' and employee_username is null;
create index if not exists payroll_records_period_idx on public.payroll_records (period_end);

-- Create (p_payroll_id null; a retry with the same request id returns the first record) or edit.
create or replace function trimax_private.save_payroll(
    p_admin text, p_payroll_id bigint, p_request_id uuid, p_employee_username text, p_employee_name text,
    p_period_start date, p_period_end date, p_basic_pay numeric, p_allowances numeric, p_deductions numeric,
    p_status text, p_paid_at date, p_payment_method text, p_notes text)
returns bigint
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_user text := nullif(btrim(p_employee_username), '');
    v_name text := nullif(btrim(p_employee_name), '');
    v_status text := upper(btrim(coalesce(p_status, '')));
    v_method text := nullif(upper(btrim(coalesce(p_payment_method, ''))), '');
    v_notes text := nullif(btrim(p_notes), '');
    v_allow numeric := coalesce(p_allowances, 0);
    v_ded numeric := coalesce(p_deductions, 0);
    v_old public.payroll_records;
    v_new public.payroll_records;
    v_other bigint;
begin
    perform trimax_private.require_admin(p_admin);
    if p_payroll_id is null then
        if p_request_id is null then
            raise exception using errcode = 'TM422', message = 'Missing request id.';
        end if;
        perform pg_advisory_xact_lock(hashtextextended('trimax-payroll-request:' || p_request_id::text, 0));
        select r.payroll_id into v_other from public.payroll_records r where r.client_request_id = p_request_id;
        if v_other is not null then
            return v_other;
        end if;
    end if;
    if v_user is not null then
        select u.name into v_name from public.users u where u.username = v_user;
        if not found then
            raise exception using errcode = 'TM404', message = 'That employee account no longer exists.';
        end if;
        v_name := coalesce(nullif(btrim(p_employee_name), ''), v_name, v_user);
    end if;
    if v_name is null or char_length(v_name) > 100 then
        raise exception using errcode = 'TM422', message = 'Choose the employee or enter their name (up to 100 characters).';
    end if;
    if p_period_start is null or p_period_end is null or p_period_end < p_period_start then
        raise exception using errcode = 'TM422', message = 'Enter the pay period (the end cannot be before the start).';
    end if;
    if p_period_end - p_period_start > 92 then
        raise exception using errcode = 'TM422', message = 'A pay period can be at most 3 months.';
    end if;
    if p_period_start < date '2000-01-01' or p_period_end > (now() at time zone 'Asia/Manila')::date + 366 then
        raise exception using errcode = 'TM422', message = 'Check the pay period dates.';
    end if;
    if p_basic_pay is null or p_basic_pay < 0 or v_allow < 0 or v_ded < 0
       or greatest(p_basic_pay, v_allow, v_ded) > 10000000
       or p_basic_pay <> round(p_basic_pay, 2) or v_allow <> round(v_allow, 2) or v_ded <> round(v_ded, 2) then
        raise exception using errcode = 'TM422', message = 'Amounts must be 0 or more, with at most 2 decimals.';
    end if;
    if p_basic_pay + v_allow - v_ded < 0 then
        raise exception using errcode = 'TM422', message = 'Deductions cannot be more than basic pay plus allowances.';
    end if;
    if v_status not in ('UNPAID', 'PAID') then
        raise exception using errcode = 'TM422', message = 'Choose Unpaid or Paid.';
    end if;
    if v_status = 'PAID' then
        if p_paid_at is null or p_paid_at > (now() at time zone 'Asia/Manila')::date + 1 then
            raise exception using errcode = 'TM422', message = 'Enter the date it was paid (not in the future).';
        end if;
        if v_method is not null and v_method not in ('CASH', 'GCASH', 'BANK_TRANSFER', 'OTHER') then
            raise exception using errcode = 'TM422', message = 'Choose how it was paid.';
        end if;
    elsif p_paid_at is not null or v_method is not null then
        raise exception using errcode = 'TM422', message = 'Payment date and method are only for paid records.';
    end if;
    if char_length(coalesce(v_notes, '')) > 500 then
        raise exception using errcode = 'TM422', message = 'The notes are too long (up to 500 characters).';
    end if;

    begin
        if p_payroll_id is null then
            insert into public.payroll_records (employee_username, employee_name, period_start, period_end, basic_pay,
                                                allowances, deductions, status, paid_at, payment_method, notes,
                                                created_by, client_request_id)
            values (v_user, v_name, p_period_start, p_period_end, p_basic_pay, v_allow, v_ded, v_status,
                    case when v_status = 'PAID' then p_paid_at end, case when v_status = 'PAID' then v_method end,
                    v_notes, p_admin, p_request_id)
            returning * into v_new;
            perform trimax_private.audit_as(p_admin, 'payroll.create', 'payroll', v_new.payroll_id::text,
                v_name || ', ' || p_period_start || ' to ' || p_period_end || ': basic ' || trimax_private.peso(p_basic_pay)
                || ', allowances ' || trimax_private.peso(v_allow) || ', deductions ' || trimax_private.peso(v_ded)
                || ', net ' || trimax_private.peso(v_new.net_pay) || ', ' || lower(v_status));
            return v_new.payroll_id;
        end if;

        select * into v_old from public.payroll_records r where r.payroll_id = p_payroll_id for update;
        if not found then
            raise exception using errcode = 'TM404', message = 'Payroll record not found.';
        end if;
        if v_old.status = 'VOID' then
            raise exception using errcode = 'TM409', message = 'A voided payroll record cannot be changed.';
        end if;
        update public.payroll_records r
           set employee_username = v_user, employee_name = v_name, period_start = p_period_start,
               period_end = p_period_end, basic_pay = p_basic_pay, allowances = v_allow, deductions = v_ded,
               status = v_status, paid_at = case when v_status = 'PAID' then p_paid_at end,
               payment_method = case when v_status = 'PAID' then v_method end, notes = v_notes,
               updated_by = p_admin, updated_at = now()
         where r.payroll_id = p_payroll_id
        returning * into v_new;
    exception when unique_violation then
        select r.payroll_id into v_other from public.payroll_records r
         where r.status <> 'VOID' and r.period_start = p_period_start and r.period_end = p_period_end
           and (r.employee_username = v_user or (v_user is null and r.employee_username is null
                                                 and lower(r.employee_name) = lower(v_name)))
         limit 1;
        raise exception using errcode = 'TM409',
            message = 'This employee already has a payroll record for this pay period'
                      || coalesce(' (#' || v_other || ')', '') || '. Edit that one instead.';
    end;
    perform trimax_private.audit_as(p_admin, 'payroll.update', 'payroll', p_payroll_id::text,
        v_new.employee_name || ', ' || v_new.period_start || ' to ' || v_new.period_end || ': net '
        || trimax_private.peso(v_old.net_pay) || ' -> ' || trimax_private.peso(v_new.net_pay)
        || ', ' || lower(v_old.status) || ' -> ' || lower(v_new.status)
        || case when v_old.basic_pay <> v_new.basic_pay or v_old.allowances <> v_new.allowances
                     or v_old.deductions <> v_new.deductions
                then ' (basic ' || trimax_private.peso(v_old.basic_pay) || ' -> ' || trimax_private.peso(v_new.basic_pay)
                     || ', allowances ' || trimax_private.peso(v_old.allowances) || ' -> ' || trimax_private.peso(v_new.allowances)
                     || ', deductions ' || trimax_private.peso(v_old.deductions) || ' -> ' || trimax_private.peso(v_new.deductions) || ')'
                else '' end);
    return v_new.payroll_id;
end $function$;

create or replace function trimax_private.void_payroll(p_admin text, p_payroll_id bigint, p_reason text)
returns void
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_reason text := nullif(btrim(p_reason), '');
    v_old public.payroll_records;
begin
    perform trimax_private.require_admin(p_admin);
    if v_reason is null or char_length(v_reason) < 3 or char_length(v_reason) > 300 then
        raise exception using errcode = 'TM422', message = 'Enter the reason for voiding (3 to 300 characters).';
    end if;
    select * into v_old from public.payroll_records r where r.payroll_id = p_payroll_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'Payroll record not found.';
    end if;
    if v_old.status = 'VOID' then
        raise exception using errcode = 'TM409', message = 'This payroll record is already voided.';
    end if;
    update public.payroll_records r
       set status = 'VOID', voided_by = p_admin, voided_at = now(), void_reason = v_reason,
           paid_at = case when v_old.status = 'PAID' then r.paid_at end
     where r.payroll_id = p_payroll_id;
    perform trimax_private.audit_as(p_admin, 'payroll.void', 'payroll', p_payroll_id::text,
        v_old.employee_name || ', ' || v_old.period_start || ' to ' || v_old.period_end || ', net '
        || trimax_private.peso(v_old.net_pay) || ' (was ' || lower(v_old.status) || '): ' || v_reason);
end $function$;

-- ----------------------------------------------------------------------------------------------- 9
-- Reports (Desktop). Ranges are [p_from, p_to): the Desktop passes midnights in Asia/Manila.

create or replace function trimax_private.profit_summary(p_from timestamptz, p_to timestamptz)
returns table (
    bookings_count integer, bookings_gross numeric, bookings_discount numeric, bookings_net numeric,
    bookings_cogs numeric, bookings_lines_no_cost integer,
    sales_count integer, sales_gross numeric, sales_discount numeric, sales_net numeric,
    sales_cogs numeric, sales_lines_no_cost integer,
    unpaid_sales_count integer, unpaid_sales_total numeric,
    revenue_gross numeric, discounts numeric, revenue_net numeric, cogs numeric, gross_profit numeric,
    payroll_paid numeric, payroll_unpaid numeric, payroll_count integer, net_profit numeric,
    restock_cost numeric, restock_count integer)
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
    )
    select bt.n, bt.gross, bt.disc, bt.net, bc.cogs, bc.no_cost,
           st.n, st.gross, st.disc, st.net, sc.cogs, sc.no_cost,
           u.n, u.total,
           bt.gross + st.gross, bt.disc + st.disc, bt.net + st.net, bc.cogs + sc.cogs,
           bt.net + st.net - bc.cogs - sc.cogs,
           pr.paid, pr.unpaid, pr.n,
           bt.net + st.net - bc.cogs - sc.cogs - pr.paid - pr.unpaid,
           rs.cost, rs.n
      from bt, bc, st, sc, u, pr, rs
$function$;

-- Per day or per month (p_bucket 'day' | 'month'), every bucket in the range listed (0 when empty).
create or replace function trimax_private.profit_trend(p_from timestamptz, p_to timestamptz, p_bucket text)
returns table (bucket_start date, revenue numeric, cogs numeric, gross_profit numeric, payroll numeric, net_profit numeric)
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
    ), agg as (
        select l.b, sum(l.revenue) as revenue, sum(l.cogs) as cogs from lines l group by l.b
    )
    select bk.b, coalesce(a.revenue, 0), coalesce(a.cogs, 0), coalesce(a.revenue, 0) - coalesce(a.cogs, 0),
           coalesce(p.amount, 0), coalesce(a.revenue, 0) - coalesce(a.cogs, 0) - coalesce(p.amount, 0)
      from buckets bk
      left join agg a on a.b = bk.b
      left join pay p on p.b = bk.b
     order by bk.b
$function$;

-- ----------------------------------------------------------------------------------------------- 10
-- Admin view tickets also for customer photos (Edge Function trimax-admin-media serves them).

alter table trimax_private.media_tickets drop constraint if exists media_tickets_bucket_check;
alter table trimax_private.media_tickets drop constraint if exists media_tickets_action_check;
alter table trimax_private.media_tickets add constraint media_tickets_bucket_check
    check (bucket in ('duty-photos', 'delivery-proofs', 'product-images', 'customer-photos'));
alter table trimax_private.media_tickets add constraint media_tickets_action_check
    check ((action = 'view' and bucket in ('duty-photos', 'delivery-proofs', 'customer-photos'))
           or (action in ('upload', 'delete') and bucket = 'product-images'));

create or replace function trimax_private.issue_media_ticket(p_admin_username text, p_bucket text, p_path text)
returns uuid
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_ticket uuid;
    v_owner text;
begin
    if not exists (select 1 from public.users u join public.roles r on r.role_id = u.role_id
                    where u.username = p_admin_username and r.role_name = 'Admin' and u.account_status = 'Active') then
        raise exception using errcode = 'TM403', message = 'Only an active Admin can view photos.';
    end if;
    if p_bucket not in ('duty-photos', 'delivery-proofs', 'customer-photos')
       or p_path is null or p_path !~ '^[A-Za-z0-9-]{1,64}/[A-Za-z0-9_.-]{1,120}\.jpg$' then
        raise exception using errcode = 'TM422', message = 'Not a TRIMAX photo.';
    end if;
    if to_regclass('storage.objects') is not null
       and not exists (select 1 from storage.objects o where o.bucket_id = p_bucket and o.name = p_path) then
        raise exception using errcode = 'TM404', message = 'That photo is not on the server.';
    end if;

    -- Housekeeping: tickets are only useful for 2 minutes; keep a day for troubleshooting.
    delete from trimax_private.media_tickets where issued_at < now() - interval '1 day';

    insert into trimax_private.media_tickets (bucket, object_path, issued_by)
    values (p_bucket, p_path, p_admin_username)
    returning ticket into v_ticket;

    -- Who looked at whose photo.
    select m.username into v_owner from trimax_private.mobile_accounts m
     where m.auth_user_id::text = split_part(p_path, '/', 1);
    perform trimax_private.audit_user(p_admin_username, 'media.view', coalesce(v_owner, split_part(p_path, '/', 1)),
                                      p_bucket || '/' || p_path);
    return v_ticket;
end $function$;

-- ----------------------------------------------------------------------------------------------- 11
-- Privileges. New tables: owner only (RLS on, no policies, no grants to the API roles), like every other
-- TRIMAX table. Desktop functions: owner only. Phone functions: signed-in users only.

alter table public.inventory_movements enable row level security;
alter table public.sales enable row level security;
alter table public.sale_items enable row level security;
alter table public.payroll_records enable row level security;
revoke all on table public.inventory_movements, public.sales, public.sale_items, public.payroll_records
    from public, anon, authenticated, service_role;
revoke all on sequence public.sales_receipt_seq from public, anon, authenticated, service_role;
do $$
declare
    v_seq text;
begin
    for v_seq in select pg_get_serial_sequence('public.' || t, c)
                   from (values ('inventory_movements', 'movement_id'), ('sales', 'sale_id'),
                                ('sale_items', 'sale_item_id'), ('payroll_records', 'payroll_id')) v(t, c) loop
        execute 'revoke all on sequence ' || v_seq || ' from public, anon, authenticated, service_role';
    end loop;
end $$;

revoke all on function trimax_private.require_admin(text) from public;
revoke all on function trimax_private.audit_as(text, text, text, text, text) from public;
revoke all on function trimax_private.peso(numeric) from public;
revoke all on function trimax_private.selling_price(numeric, text, numeric) from public;
revoke all on function trimax_private.products_before_write() from public;
revoke all on function trimax_private.inventory_movements_append_only() from public;
revoke all on function trimax_private.set_stock_context(text, text, text, text, text, numeric, uuid) from public;
revoke all on function trimax_private.products_stock_moved() from public;
revoke all on function trimax_private.save_product(text, integer, text, text, numeric, text, numeric, numeric, numeric, integer) from public;
revoke all on function trimax_private.restock_product(text, integer, integer, numeric, text, boolean, uuid) from public;
revoke all on function trimax_private.adjust_stock(text, integer, integer, text, uuid) from public;
revoke all on function trimax_private.booking_sale_time(timestamptz, text, timestamptz, timestamptz) from public;
revoke all on function trimax_private.bookings_completed_at() from public;
revoke all on function trimax_private.booking_items_capture_cost() from public;
revoke all on function trimax_private.discount_amount_for(numeric, text, numeric) from public;
revoke all on function trimax_private.discount_limits_for(integer, bigint[], bigint[]) from public;
revoke all on function trimax_private.apply_booking_discount(integer, text, text, numeric) from public;
revoke all on function trimax_private.booking_json(integer, boolean) from public;
revoke all on function trimax_private.sale_items_read_only() from public;
revoke all on function trimax_private.create_sale(text, uuid, text, integer, text, jsonb, text, text, numeric, text) from public;
revoke all on function trimax_private.void_sale(text, bigint, text) from public;
revoke all on function trimax_private.set_sale_paid(text, bigint, text) from public;
revoke all on function trimax_private.save_payroll(text, bigint, uuid, text, text, date, date, numeric, numeric, numeric, text, date, text, text) from public;
revoke all on function trimax_private.void_payroll(text, bigint, text) from public;
revoke all on function trimax_private.profit_summary(timestamptz, timestamptz) from public;
revoke all on function trimax_private.profit_trend(timestamptz, timestamptz, text) from public;
revoke all on function trimax_private.issue_media_ticket(text, text, text) from public;

revoke all on function public.trimax_agent_save_customer(text, text, text, double precision, double precision, text) from public, anon;
revoke all on function public.trimax_agent_list_customers() from public, anon;
revoke all on function public.trimax_agent_discount_quote(jsonb) from public, anon;
revoke all on function public.trimax_agent_set_discount(integer, text, numeric) from public, anon;
revoke all on function public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision,
                                                          jsonb, text, text, text, numeric) from public, anon;
grant execute on function public.trimax_agent_save_customer(text, text, text, double precision, double precision, text) to authenticated;
grant execute on function public.trimax_agent_list_customers() to authenticated;
grant execute on function public.trimax_agent_discount_quote(jsonb) to authenticated;
grant execute on function public.trimax_agent_set_discount(integer, text, numeric) to authenticated;
grant execute on function public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision,
                                                             jsonb, text, text, text, numeric) to authenticated;
do $$
begin
    -- The Edge Functions and the Supabase dashboard use service_role; it does not need the phone functions.
    execute 'revoke all on function public.trimax_agent_save_customer(text, text, text, double precision, double precision, text) from service_role';
    execute 'revoke all on function public.trimax_agent_list_customers() from service_role';
    execute 'revoke all on function public.trimax_agent_discount_quote(jsonb) from service_role';
    execute 'revoke all on function public.trimax_agent_set_discount(integer, text, numeric) from service_role';
end $$;

commit;
