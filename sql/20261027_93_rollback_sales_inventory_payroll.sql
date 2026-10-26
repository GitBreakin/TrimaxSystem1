-- =====================================================================================================
-- ROLLBACK of 20261027_01_sales_inventory_payroll.sql (only if the owner decides to undo it).
--
-- Puts back the previous versions of trimax_agent_create_booking (9 arguments: creates customers again,
-- no discount), booking_json, refresh_booking_total and issue_media_ticket, and removes every function,
-- trigger and storage policy the migration added.
-- KEEPS all data: the new tables (sales, sale_items, payroll_records, inventory_movements) with their
-- rows and their append-only / read-only protection, and the new columns of products, bookings,
-- booking_items and clients, and the customer-photos bucket with its files. Re-applying
-- 20261027_01 afterwards works and continues from that data.
-- After a rollback the Desktop's Sales, Payroll and Sales & Profit pages say "not set up on the server".
-- =====================================================================================================

begin;

drop trigger if exists trimax_products_before_write on public.products;
drop trigger if exists trimax_products_stock_moved on public.products;
drop trigger if exists trimax_bookings_completed_at on public.bookings;
drop trigger if exists trimax_booking_items_capture_cost on public.booking_items;

drop function if exists public.trimax_agent_save_customer(text, text, text, double precision, double precision, text);
drop function if exists public.trimax_agent_list_customers();
drop function if exists public.trimax_agent_discount_quote(jsonb);
drop function if exists public.trimax_agent_set_discount(integer, text, numeric);
drop function if exists public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision,
                                                           jsonb, text, text, text, numeric);

-- ---- previous public.trimax_agent_create_booking (sql/20261023_01_mobile_booking_integration.sql)
create or replace function public.trimax_agent_create_booking(
    p_request_id uuid,
    p_customer_name text,
    p_customer_contact text,
    p_delivery_address text,
    p_delivery_latitude double precision,
    p_delivery_longitude double precision,
    p_items jsonb,
    p_requested_schedule text default null,
    p_special_instructions text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
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
    v_matched_existing boolean := false;
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

    -- ---- customer: reuse this agent's client with the same number, never another agent's
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
        insert into public.clients (client_name, address, contact_num, latitude, longitude)
        values (v_name, v_address, v_contact, p_delivery_latitude, p_delivery_longitude)
        returning client_id into v_client_id;
        insert into public.agents (username, client_id) values (v_me, v_client_id);
    else
        v_matched_existing := true;
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

    -- ---- booking, items, stock: all in this transaction
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

    update public.products p
       set stock = p.stock - l.quantity
      from unnest(v_ids, v_qtys) as l(product_id, quantity)
     where p.product_id = l.product_id and p.stock >= l.quantity;
    get diagnostics v_lines = row_count;
    if v_lines <> cardinality(v_ids) then
        raise exception using errcode = 'TM409', message = 'Stock changed while saving. Please try again.';
    end if;

    -- The desktop relies on a trigger on booking_items for total_amount; set it here too in case it is absent.
    select coalesce(sum(subtotal), 0) into v_total from public.booking_items where booking_id = v_booking_id;
    update public.bookings set total_amount = v_total
     where booking_id = v_booking_id and total_amount is distinct from v_total;

    insert into trimax_private.delivery_events (booking_id, actor_username, source, event_type, to_status, booking_status, note)
    values (v_booking_id, v_me, 'mobile', 'created', null, 'Pending',
            cardinality(v_ids) || ' product line(s) submitted from the mobile app');
    perform trimax_private.audit(v_me, 'booking.create', v_booking_id::text,
        'Created from the mobile app, ' || cardinality(v_ids) || ' item line(s), total ' || v_total);

    return trimax_private.booking_json(v_booking_id, false)
           || jsonb_build_object('already_existed', false, 'customer_matched_existing', v_matched_existing);
end $$;
revoke all on function public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision,
                                                          jsonb, text, text) from public, anon;
grant execute on function public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision,
                                                             jsonb, text, text) to authenticated;

-- ---- previous trimax_private.booking_json (sql/20261023_01_mobile_booking_integration.sql)
create or replace function trimax_private.booking_json(p_booking_id int, p_with_events boolean)
returns jsonb
language sql stable
set search_path = public, pg_temp
as $$
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
$$;
revoke all on function trimax_private.booking_json(integer, boolean) from public;

-- ---- previous public.refresh_booking_total (sql/20261025_01_live_baseline.sql + 20261025_02)
CREATE OR REPLACE FUNCTION public.refresh_booking_total()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    b INT := COALESCE(NEW.booking_id, OLD.booking_id);
BEGIN
    UPDATE bookings
    SET total_amount = COALESCE(
            (SELECT SUM(subtotal) FROM booking_items WHERE booking_id = b), 0)
    WHERE booking_id = b;
    RETURN NULL;
END;
$function$;
alter function public.refresh_booking_total() set search_path = public, pg_temp;
revoke execute on function public.refresh_booking_total() from public, anon, authenticated;
drop trigger if exists trg_booking_items_total on public.booking_items;
create trigger trg_booking_items_total after insert or delete or update on public.booking_items
    for each row execute function public.refresh_booking_total();

-- ---- previous trimax_private.issue_media_ticket (sql/20261025_05_admin_media_tickets.sql)
create or replace function trimax_private.issue_media_ticket(p_admin_username text, p_bucket text, p_path text)
 returns uuid
 language plpgsql
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_ticket uuid;
    v_owner text;
begin
    if not exists (select 1 from public.users u join public.roles r on r.role_id = u.role_id
                    where u.username = p_admin_username and r.role_name = 'Admin' and u.account_status = 'Active') then
        raise exception using errcode = 'TM403', message = 'Only an active Admin can view photos.';
    end if;
    if p_bucket not in ('duty-photos', 'delivery-proofs')
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
revoke all on function trimax_private.issue_media_ticket(text, text, text) from public;
-- old customer-photo tickets (2 minutes long) are removed so the previous constraints fit again
delete from trimax_private.media_tickets where bucket = 'customer-photos';
alter table trimax_private.media_tickets drop constraint if exists media_tickets_bucket_check;
alter table trimax_private.media_tickets drop constraint if exists media_tickets_action_check;
alter table trimax_private.media_tickets add constraint media_tickets_bucket_check
    check (bucket in ('duty-photos', 'delivery-proofs', 'product-images'));
alter table trimax_private.media_tickets add constraint media_tickets_action_check
    check ((action = 'view' and bucket in ('duty-photos', 'delivery-proofs'))
           or (action in ('upload', 'delete') and bucket = 'product-images'));

-- ---- Desktop functions and helpers added by 20261027_01
drop function if exists trimax_private.save_product(text, integer, text, text, numeric, text, numeric, numeric, numeric, integer);
drop function if exists trimax_private.restock_product(text, integer, integer, numeric, text, boolean, uuid);
drop function if exists trimax_private.adjust_stock(text, integer, integer, text, uuid);
drop function if exists trimax_private.create_sale(text, uuid, text, integer, text, jsonb, text, text, numeric, text);
drop function if exists trimax_private.void_sale(text, bigint, text);
drop function if exists trimax_private.set_sale_paid(text, bigint, text);
drop function if exists trimax_private.save_payroll(text, bigint, uuid, text, text, date, date, numeric, numeric, numeric, text, date, text, text);
drop function if exists trimax_private.void_payroll(text, bigint, text);
drop function if exists trimax_private.profit_summary(timestamptz, timestamptz);
drop function if exists trimax_private.profit_trend(timestamptz, timestamptz, text);
drop function if exists trimax_private.apply_booking_discount(integer, text, text, numeric);
drop function if exists trimax_private.discount_limits_for(integer, bigint[], bigint[]);
drop function if exists trimax_private.discount_amount_for(numeric, text, numeric);
drop function if exists trimax_private.booking_sale_time(timestamptz, text, timestamptz, timestamptz);
drop function if exists trimax_private.bookings_completed_at();
drop function if exists trimax_private.booking_items_capture_cost();
drop function if exists trimax_private.products_before_write();
drop function if exists trimax_private.products_stock_moved();
drop function if exists trimax_private.set_stock_context(text, text, text, text, text, numeric, uuid);
drop function if exists trimax_private.selling_price(numeric, text, numeric);
drop function if exists trimax_private.require_admin(text);
drop function if exists trimax_private.audit_as(text, text, text, text, text);
drop function if exists trimax_private.peso(numeric);

-- ---- customer photo upload/read policies (the bucket and its files stay)
do $$
begin
    if to_regclass('storage.objects') is not null then
        drop policy if exists "trimax customer photo upload own folder" on storage.objects;
        drop policy if exists "trimax customer photo read own folder" on storage.objects;
    end if;
end $$;

commit;
