-- =====================================================================================================
-- TRIMAX: shared bookings for the desktop admin app and the Android app
-- File: sql/20261023_01_mobile_booking_integration.sql
--
-- WHAT IT DOES (additive; no existing column, row, constraint or desktop statement is changed)
--   * Adds nullable delivery columns to public.bookings so an Android booking is an ordinary booking
--     row that the desktop list, detail window, assignment, dashboard and reports already handle.
--   * Adds schema trimax_private (NOT exposed by the Supabase API) with:
--       - mobile_accounts: admin-controlled link between auth.users and public.users(username)
--       - delivery_events: delivery history (created / assigned / driver status changes)
--       - helper and admin-only functions (link_mobile_account, unlink_mobile_account, ...)
--   * Adds public RPC functions for the phone. Each derives the user from auth.uid(), re-reads the
--     role and Active status from public.users on every call, and checks ownership itself.
--   * Removes the Supabase API roles' (anon, authenticated) direct access to the legacy tables.
--     The desktop connects through JDBC as the table owner and is not affected.
--
-- BEFORE RUNNING
--   1. Reset the database password (it is in git history) and back up the database.
--   2. Run sql/20261023_00_preflight_readonly.sql and review its output.
--   3. Run this script on a copy / test project first (sql/tests/run_tests.sh does this locally).
--   4. Run it in the Supabase SQL editor (or psql) as the "postgres" role, which owns the tables.
--      It runs in one transaction: if anything fails, nothing is applied. It is safe to re-run.
-- ROLLBACK: sql/20261023_99_rollback_mobile_booking_integration.sql
-- =====================================================================================================

begin;
set local lock_timeout = '10s';      -- fail instead of waiting behind a long desktop transaction
set local search_path = public;

-- -----------------------------------------------------------------------------------------------------
-- 0. Preconditions: stop with a clear message instead of half-applying to an unexpected database.
-- -----------------------------------------------------------------------------------------------------
do $$
declare
    v_missing text;
    v_type text;
begin
    if to_regclass('auth.users') is null or to_regprocedure('auth.uid()') is null then
        raise exception 'auth.users / auth.uid() not found: this script is for a Supabase database.';
    end if;

    select string_agg(t, ', ') into v_missing
    from unnest(array['users', 'roles', 'clients', 'agents', 'products', 'vehicles', 'bookings', 'booking_items']) t
    where to_regclass('public.' || t) is null;
    if v_missing is not null then
        raise exception 'Expected TRIMAX tables are missing: %', v_missing;
    end if;

    if not exists (
        select 1
        from pg_constraint con
        join pg_attribute att on att.attrelid = con.conrelid and att.attnum = con.conkey[1]
        where con.conrelid = 'public.users'::regclass
          and con.contype in ('p', 'u')
          and array_length(con.conkey, 1) = 1
          and att.attname = 'username') then
        raise exception 'public.users.username must be a primary key or unique; the mobile link table references it.';
    end if;

    -- If a column we add already exists, it must have the type we expect.
    for v_missing, v_type in
        select c.column_name, c.data_type
        from information_schema.columns c
        join (values ('created_via', 'text'), ('created_by_username', 'text'), ('client_request_id', 'uuid'),
                     ('delivery_address', 'text'), ('delivery_latitude', 'double precision'),
                     ('delivery_longitude', 'double precision'), ('requested_schedule', 'text'),
                     ('special_instructions', 'text'), ('delivery_status', 'text'),
                     ('delivery_status_at', 'timestamp with time zone'),
                     ('assigned_at', 'timestamp with time zone'),
                     ('last_changed_at', 'timestamp with time zone')) e(name, type)
          on e.name = c.column_name
        where c.table_schema = 'public' and c.table_name = 'bookings' and c.data_type <> e.type
    loop
        raise exception 'bookings.% already exists with type % (expected another type).', v_missing, v_type;
    end loop;
end $$;

-- -----------------------------------------------------------------------------------------------------
-- 1. Private schema (not in Supabase's exposed API schemas, so tables here have no REST endpoint)
-- -----------------------------------------------------------------------------------------------------
create schema if not exists trimax_private;
revoke all on schema trimax_private from public;
do $$
begin
    -- These roles exist on Supabase; revoke defensively in case usage was ever granted.
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on schema trimax_private from anon, authenticated';
    end if;
end $$;

-- Link between a Supabase Auth user and a desktop user. One row per person; written only by an
-- administrator through trimax_private.link_mobile_account. Role and Active status are NOT copied
-- here: they are always read from public.users/public.roles, so disabling an account on the desktop
-- revokes mobile access immediately.
create table if not exists trimax_private.mobile_accounts (
    auth_user_id uuid primary key references auth.users(id) on delete cascade,
    username text not null unique references public.users(username) on update cascade on delete cascade,
    linked_at timestamptz not null default now(),
    linked_by text not null default current_user
);
alter table trimax_private.mobile_accounts enable row level security;

-- -----------------------------------------------------------------------------------------------------
-- 2. Additive, nullable columns on public.bookings
-- -----------------------------------------------------------------------------------------------------
alter table public.bookings
    add column if not exists created_via text,              -- 'desktop' | 'mobile'; NULL = created before this script
    add column if not exists created_by_username text,      -- the mobile agent who submitted it
    add column if not exists client_request_id uuid,        -- idempotency key sent by the phone
    add column if not exists delivery_address text,         -- per-booking drop-off (falls back to clients.address)
    add column if not exists delivery_latitude double precision,
    add column if not exists delivery_longitude double precision,
    add column if not exists requested_schedule text,       -- as entered by the agent, e.g. 'ASAP' or '2026-10-25 • 10:00 AM'
    add column if not exists special_instructions text,
    add column if not exists delivery_status text,          -- driver progress, see trimax_private.delivery_transition_allowed
    add column if not exists delivery_status_at timestamptz,
    add column if not exists assigned_at timestamptz,
    add column if not exists last_changed_at timestamptz;   -- set on every insert/update, for refresh/polling

do $$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.bookings'::regclass and conname = 'bookings_created_via_chk') then
        alter table public.bookings add constraint bookings_created_via_chk
            check (created_via is null or created_via in ('desktop', 'mobile'));
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.bookings'::regclass and conname = 'bookings_delivery_status_chk') then
        alter table public.bookings add constraint bookings_delivery_status_chk
            check (delivery_status is null
                   or delivery_status in ('ASSIGNED', 'STARTED', 'PICKED_UP', 'ON_THE_WAY', 'ARRIVED', 'DELIVERED', 'FAILED'));
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.bookings'::regclass and conname = 'bookings_delivery_point_chk') then
        alter table public.bookings add constraint bookings_delivery_point_chk
            check ((delivery_latitude is null and delivery_longitude is null)
                   or (delivery_latitude between -90 and 90 and delivery_longitude between -180 and 180
                       and not (delivery_latitude = 0 and delivery_longitude = 0)));
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.bookings'::regclass and conname = 'bookings_mobile_text_len_chk') then
        alter table public.bookings add constraint bookings_mobile_text_len_chk
            check (coalesce(char_length(delivery_address), 0) <= 500
                   and coalesce(char_length(requested_schedule), 0) <= 100
                   and coalesce(char_length(special_instructions), 0) <= 1000);
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.bookings'::regclass and conname = 'bookings_mobile_required_chk') then
        alter table public.bookings add constraint bookings_mobile_required_chk
            check (created_via is distinct from 'mobile'
                   or (client_request_id is not null and delivery_address is not null
                       and delivery_latitude is not null and client_id is not null));
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.bookings'::regclass and conname = 'bookings_created_by_fk') then
        alter table public.bookings add constraint bookings_created_by_fk
            foreign key (created_by_username) references public.users(username) on update cascade on delete set null;
    end if;
end $$;

create unique index if not exists bookings_client_request_id_key on public.bookings (client_request_id);
create index if not exists bookings_driver_status_idx on public.bookings (driver_username, status);
create index if not exists bookings_created_by_idx on public.bookings (created_by_username, transc_date desc);

-- -----------------------------------------------------------------------------------------------------
-- 3. Delivery history
-- -----------------------------------------------------------------------------------------------------
create table if not exists trimax_private.delivery_events (
    event_id bigserial primary key,
    booking_id int not null references public.bookings(booking_id) on delete cascade,
    occurred_at timestamptz not null default now(),
    actor_username text,                     -- NULL when the desktop changed the driver (desktop writes audit_log)
    source text not null check (source in ('desktop', 'mobile')),
    event_type text not null check (event_type in ('created', 'assigned', 'unassigned', 'delivery_status')),
    from_status text,
    to_status text,
    booking_status text,
    latitude double precision check (latitude between -90 and 90),
    longitude double precision check (longitude between -180 and 180),
    note text check (char_length(note) <= 600)
);
create index if not exists delivery_events_booking_idx on trimax_private.delivery_events (booking_id, occurred_at desc);
alter table trimax_private.delivery_events enable row level security;

-- -----------------------------------------------------------------------------------------------------
-- 4. Helpers (private; never granted to API roles)
-- -----------------------------------------------------------------------------------------------------

-- Comparable form of a phone number: digits only, last 10 digits (so 0917…, +63 917… and 917… match).
create or replace function trimax_private.contact_key(p_contact text)
returns text
language sql immutable strict
set search_path = public, pg_temp
as $$
    select case when char_length(d) >= 10 then right(d, 10)
                when char_length(d) >= 7 then d
                else null end
    from (select regexp_replace(p_contact, '[^0-9]', '', 'g') as d) s
$$;

create index if not exists clients_contact_key_idx on public.clients (trimax_private.contact_key(contact_num));

-- Which driver progress steps may follow which. PICKED_UP is optional (hub dispatch goes straight to
-- ON_THE_WAY). Only STARTED and DELIVERED also move bookings.status; see the update RPC.
create or replace function trimax_private.delivery_transition_allowed(p_from text, p_to text)
returns boolean
language sql immutable
set search_path = public, pg_temp
as $$
    select (p_from, p_to) in (
        ('ASSIGNED',   'STARTED'),
        ('STARTED',    'PICKED_UP'),
        ('STARTED',    'ON_THE_WAY'),
        ('PICKED_UP',  'ON_THE_WAY'),
        ('ON_THE_WAY', 'ARRIVED'),
        ('ARRIVED',    'DELIVERED'),
        ('STARTED',    'FAILED'),
        ('PICKED_UP',  'FAILED'),
        ('ON_THE_WAY', 'FAILED'),
        ('ARRIVED',    'FAILED'))
$$;

-- The signed-in mobile user's username. Raises TM401 when the Auth user is not linked or the TRIMAX
-- account is not Active, TM403 when the role does not match. p_role: 'AGENT', 'DRIVER' or NULL (either).
create or replace function trimax_private.require_account(p_role text)
returns text
language plpgsql stable
set search_path = public, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_username text;
    v_role text;
begin
    if v_uid is null then
        raise exception using errcode = 'TM401', message = 'You are not signed in.';
    end if;

    select u.username,
           case r.role_name when 'Agent' then 'AGENT' when 'Rider' then 'AGENT'
                            when 'Driver' then 'DRIVER' when 'Admin' then 'ADMIN' else 'OTHER' end
      into v_username, v_role
      from trimax_private.mobile_accounts m
      join public.users u on u.username = m.username
      left join public.roles r on r.role_id = u.role_id
     where m.auth_user_id = v_uid
       and u.account_status = 'Active';

    if v_username is null then
        raise exception using errcode = 'TM401',
            message = 'This sign-in is not linked to an active TRIMAX account. Ask an administrator.';
    end if;
    if v_role not in ('AGENT', 'DRIVER') then
        raise exception using errcode = 'TM403', message = 'The mobile app is for agents and drivers only.';
    end if;
    if p_role is not null and v_role <> p_role then
        raise exception using errcode = 'TM403',
            message = case p_role when 'AGENT' then 'Only agents can do this.' else 'Only drivers can do this.' end;
    end if;
    return v_username;
end $$;

-- Writes one audit_log row in the caller's transaction, if the audit table exists (same rule as the
-- desktop AuditService: a missing table is skipped, any other failure rolls the whole change back).
create or replace function trimax_private.audit(p_actor text, p_action text, p_target_id text, p_details text)
returns void
language plpgsql
set search_path = public, pg_temp
as $$
begin
    if to_regclass('public.audit_log') is not null then
        execute 'insert into public.audit_log (actor_username, action, target_type, target_id, result, details) '
             || 'values ($1, $2, ''booking'', $3, ''success'', $4)'
          using p_actor, p_action, p_target_id, p_details;
    end if;
end $$;

-- One booking as JSON, in the shape both mobile roles read. Callers check access first.
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

-- -----------------------------------------------------------------------------------------------------
-- 5. Triggers on public.bookings: keep delivery_status in step with the assigned driver, whichever
--    client changes it (including desktop builds that know nothing about delivery_status).
-- -----------------------------------------------------------------------------------------------------
create or replace function trimax_private.bookings_before_write()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $$
begin
    new.last_changed_at := now();
    if tg_op = 'INSERT' then
        new.created_via := coalesce(new.created_via, 'desktop');
        if new.driver_username is not null then
            new.delivery_status := coalesce(new.delivery_status, 'ASSIGNED');
            new.assigned_at := coalesce(new.assigned_at, now());
            new.delivery_status_at := coalesce(new.delivery_status_at, now());
        end if;
    elsif new.driver_username is distinct from old.driver_username then
        if coalesce(old.delivery_status, 'ASSIGNED') <> 'ASSIGNED' then
            raise exception using errcode = 'TM409',
                message = 'The driver has already started this delivery (' || old.delivery_status
                          || '), so it cannot be reassigned.';
        end if;
        new.delivery_status := case when new.driver_username is null then null else 'ASSIGNED' end;
        new.delivery_status_at := now();
        new.assigned_at := case when new.driver_username is null then null else now() end;
    end if;
    return new;
end $$;

create or replace function trimax_private.bookings_after_write()
returns trigger
language plpgsql
security definer          -- writes trimax_private.delivery_events whichever role changed the booking
set search_path = public, pg_temp
as $$
begin
    if tg_op = 'INSERT' then
        if new.driver_username is not null then
            insert into trimax_private.delivery_events (booking_id, source, event_type, to_status, booking_status, note)
            values (new.booking_id, coalesce(new.created_via, 'desktop'), 'assigned', 'ASSIGNED', new.status,
                    'Assigned to ' || new.driver_username || coalesce(' (vehicle ' || new.vehicle_id || ')', ''));
        end if;
    elsif new.driver_username is distinct from old.driver_username then
        insert into trimax_private.delivery_events (booking_id, source, event_type, from_status, to_status, booking_status, note)
        values (new.booking_id, 'desktop',
                case when new.driver_username is null then 'unassigned' else 'assigned' end,
                old.delivery_status, new.delivery_status, new.status,
                coalesce('Assigned to ' || new.driver_username || coalesce(' (vehicle ' || new.vehicle_id || ')', ''),
                         'Driver removed'));
    end if;
    return null;
end $$;

drop trigger if exists trimax_bookings_before_write on public.bookings;
create trigger trimax_bookings_before_write
    before insert or update on public.bookings
    for each row execute function trimax_private.bookings_before_write();

drop trigger if exists trimax_bookings_after_write on public.bookings;
create trigger trimax_bookings_after_write
    after insert or update of driver_username on public.bookings
    for each row execute function trimax_private.bookings_after_write();

-- -----------------------------------------------------------------------------------------------------
-- 6. Administrator functions (private: run from the desktop app or the SQL editor, never from a phone)
-- -----------------------------------------------------------------------------------------------------

-- Links a Supabase Auth user to a TRIMAX Agent/Driver account. The Auth email must equal the email
-- stored on the TRIMAX account and must be confirmed, so a link can only be made to the person who
-- controls that mailbox (or whom the administrator created/confirmed). Returns the Auth user id.
create or replace function trimax_private.link_mobile_account(p_username text, p_auth_email text, p_linked_by text default null)
returns uuid
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_username text;
    v_email text;
    v_role text;
    v_status text;
    v_auth_id uuid;
    v_confirmed timestamptz;
    v_other text;
begin
    select u.username, u.email, r.role_name, u.account_status
      into v_username, v_email, v_role, v_status
      from public.users u
      left join public.roles r on r.role_id = u.role_id
     where u.username = btrim(p_username)
       for update of u;
    if v_username is null then
        raise exception using errcode = 'TM404', message = 'There is no TRIMAX account named "' || coalesce(p_username, '') || '".';
    end if;
    if v_role is null or v_role not in ('Agent', 'Rider', 'Driver') then
        raise exception using errcode = 'TM422', message = 'Only Agent and Driver accounts can use the mobile app.';
    end if;
    if v_email is null or btrim(v_email) = '' then
        raise exception using errcode = 'TM422', message = 'Add an email address to this TRIMAX account first.';
    end if;
    if lower(btrim(v_email)) <> lower(btrim(coalesce(p_auth_email, ''))) then
        raise exception using errcode = 'TM422',
            message = 'The sign-in email must be the same as the email on the TRIMAX account (' || v_email || ').';
    end if;

    select a.id, a.email_confirmed_at into v_auth_id, v_confirmed
      from auth.users a
     where lower(a.email) = lower(btrim(p_auth_email));
    if v_auth_id is null then
        raise exception using errcode = 'TM404',
            message = 'No Supabase sign-in exists for ' || btrim(p_auth_email)
                      || '. Create or invite it under Authentication > Users first.';
    end if;
    if v_confirmed is null then
        raise exception using errcode = 'TM422', message = 'That sign-in has not confirmed its email address yet.';
    end if;

    select m.username into v_other from trimax_private.mobile_accounts m
     where m.auth_user_id = v_auth_id and m.username <> v_username;
    if v_other is not null then
        raise exception using errcode = 'TM409', message = 'That sign-in is already linked to "' || v_other || '".';
    end if;

    insert into trimax_private.mobile_accounts (auth_user_id, username, linked_by)
    values (v_auth_id, v_username, coalesce(p_linked_by, current_user))
    on conflict (username) do update
        set auth_user_id = excluded.auth_user_id, linked_at = now(), linked_by = excluded.linked_by;

    if to_regclass('public.audit_log') is not null then
        execute 'insert into public.audit_log (actor_username, action, target_type, target_id, result, details) '
             || 'values ($1, ''account.link_mobile'', ''user'', $2, ''success'', $3)'
          using coalesce(p_linked_by, current_user), v_username, 'Linked to sign-in ' || lower(btrim(p_auth_email));
    end if;
    return v_auth_id;
end $$;

create or replace function trimax_private.unlink_mobile_account(p_username text, p_unlinked_by text default null)
returns boolean
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_found boolean;
begin
    delete from trimax_private.mobile_accounts where username = btrim(p_username);
    v_found := found;
    if v_found and to_regclass('public.audit_log') is not null then
        execute 'insert into public.audit_log (actor_username, action, target_type, target_id, result, details) '
             || 'values ($1, ''account.unlink_mobile'', ''user'', $2, ''success'', ''Mobile sign-in removed'')'
          using coalesce(p_unlinked_by, current_user), btrim(p_username);
    end if;
    return v_found;
end $$;

-- -----------------------------------------------------------------------------------------------------
-- 7. Mobile RPC functions (public schema, callable only by signed-in users)
--    Errors use SQLSTATE TM401 (sign in again), TM403 (wrong role), TM404 (not found / not yours),
--    TM409 (conflict: changed meanwhile, stock, duplicate), TM422 (invalid input or transition).
-- -----------------------------------------------------------------------------------------------------

create or replace function public.trimax_my_account()
returns jsonb
language plpgsql stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_username text := trimax_private.require_account(null);
begin
    return (
        select jsonb_build_object(
            'username', u.username,
            'full_name', u.name,
            'email', u.email,
            'contact_num', u.contact_num,
            'role', case r.role_name when 'Driver' then 'DRIVER' else 'AGENT' end)
        from public.users u
        left join public.roles r on r.role_id = u.role_id
        where u.username = v_username);
end $$;

create or replace function public.trimax_list_products()
returns jsonb
language plpgsql stable
security definer
set search_path = public, pg_temp
as $$
begin
    perform trimax_private.require_account('AGENT');
    return coalesce((
        select jsonb_agg(jsonb_build_object(
                   'product_id', p.product_id,
                   'product_name', p.product_name,
                   'price', p.price,
                   'stock', p.stock) order by lower(p.product_name), p.product_id)
        from public.products p), '[]'::jsonb);
end $$;

-- Creates a Pending, unassigned booking for the signed-in agent. Prices come from public.products,
-- stock is deducted in the same transaction (as the desktop does), and p_request_id makes retries
-- safe: the same id always returns the same booking instead of creating a second one.
-- p_items: [{"product_id": 1, "quantity": 2}, ...]
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

-- The signed-in agent's own bookings, newest first.
create or replace function public.trimax_agent_list_bookings(p_limit int default 50, p_offset int default 0)
returns jsonb
language plpgsql stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_me text := trimax_private.require_account('AGENT');
begin
    return coalesce((
        select jsonb_agg(trimax_private.booking_json(b.booking_id, false) order by b.transc_date desc, b.booking_id desc)
        from (select booking_id, transc_date from public.bookings
              where created_by_username = v_me
              order by transc_date desc, booking_id desc
              limit least(greatest(coalesce(p_limit, 50), 1), 200)
              offset greatest(coalesce(p_offset, 0), 0)) b), '[]'::jsonb);
end $$;

create or replace function public.trimax_agent_get_booking(p_booking_id int)
returns jsonb
language plpgsql stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_me text := trimax_private.require_account('AGENT');
begin
    if not exists (select 1 from public.bookings where booking_id = p_booking_id and created_by_username = v_me) then
        raise exception using errcode = 'TM404', message = 'Booking not found.';
    end if;
    return trimax_private.booking_json(p_booking_id, true);
end $$;

-- The signed-in driver's deliveries. 'active': not finished yet, in working order (In Progress first);
-- 'history': delivered, failed, completed or cancelled (latest 100).
create or replace function public.trimax_driver_list_deliveries(p_scope text default 'active')
returns jsonb
language plpgsql stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_me text := trimax_private.require_account('DRIVER');
begin
    if p_scope = 'active' then
        return coalesce((
            select jsonb_agg(trimax_private.booking_json(x.booking_id, false) || jsonb_build_object('sequence', x.seq) order by x.seq)
            from (select b.booking_id,
                         row_number() over (order by case b.status when 'In Progress' then 0 when 'Confirmed' then 1 else 2 end,
                                                     coalesce(b.assigned_at, b.transc_date), b.booking_id) as seq
                    from public.bookings b
                   where b.driver_username = v_me
                     and b.status in ('Pending', 'Confirmed', 'In Progress')
                     and coalesce(b.delivery_status, 'ASSIGNED') not in ('DELIVERED', 'FAILED')) x), '[]'::jsonb);
    elsif p_scope = 'history' then
        return coalesce((
            select jsonb_agg(trimax_private.booking_json(x.booking_id, false) order by x.changed desc nulls last, x.booking_id desc)
            from (select b.booking_id, coalesce(b.delivery_status_at, b.last_changed_at, b.transc_date) as changed
                    from public.bookings b
                   where b.driver_username = v_me
                     and (b.status in ('Completed', 'Cancelled') or b.delivery_status in ('DELIVERED', 'FAILED'))
                   order by 2 desc nulls last, b.booking_id desc
                   limit 100) x), '[]'::jsonb);
    end if;
    raise exception using errcode = 'TM422', message = 'Unknown list: ' || coalesce(p_scope, 'null');
end $$;

create or replace function public.trimax_driver_get_delivery(p_booking_id int)
returns jsonb
language plpgsql stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_me text := trimax_private.require_account('DRIVER');
begin
    if not exists (select 1 from public.bookings where booking_id = p_booking_id and driver_username = v_me) then
        raise exception using errcode = 'TM404', message = 'This delivery is not assigned to you.';
    end if;
    return trimax_private.booking_json(p_booking_id, true);
end $$;

-- Moves the signed-in driver's delivery one step forward. p_expected_status is the status the driver
-- saw; if it changed meanwhile nothing is written (TM409). STARTED moves a Confirmed booking to
-- In Progress (a Pending booking must be confirmed by the administrator first); DELIVERED moves it to
-- Completed and needs the recipient's name; FAILED needs a reason and leaves the booking In Progress
-- so the administrator decides whether to cancel it (which returns the stock).
create or replace function public.trimax_driver_update_delivery_status(
    p_booking_id int,
    p_expected_status text,
    p_new_status text,
    p_latitude double precision default null,
    p_longitude double precision default null,
    p_note text default null,
    p_recipient_name text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me text := trimax_private.require_account('DRIVER');
    v_status text;
    v_from text;
    v_to text := upper(btrim(coalesce(p_new_status, '')));
    v_expected text := upper(btrim(coalesce(p_expected_status, '')));
    v_note text := nullif(btrim(p_note), '');
    v_recipient text := nullif(btrim(p_recipient_name), '');
    v_new_booking_status text;
    v_event_note text;
begin
    select b.status, coalesce(b.delivery_status, 'ASSIGNED')
      into v_status, v_from
      from public.bookings b
     where b.booking_id = p_booking_id and b.driver_username = v_me
       for update;
    if v_status is null then
        raise exception using errcode = 'TM404', message = 'This delivery is not assigned to you.';
    end if;
    if v_status in ('Completed', 'Cancelled') then
        raise exception using errcode = 'TM409', message = 'Booking #' || p_booking_id || ' is already ' || v_status || '.';
    end if;
    if v_expected <> v_from then
        raise exception using errcode = 'TM409', detail = v_from,
            message = 'This delivery changed since you opened it (it is now ' || v_from || '). Refresh and try again.';
    end if;
    if not trimax_private.delivery_transition_allowed(v_from, v_to) then
        raise exception using errcode = 'TM422', message = 'A delivery cannot go from ' || v_from || ' to ' || v_to || '.';
    end if;
    if (p_latitude is null) <> (p_longitude is null)
       or (p_latitude is not null and not (p_latitude between -90 and 90 and p_longitude between -180 and 180)) then
        raise exception using errcode = 'TM422', message = 'Invalid location.';
    end if;
    if char_length(coalesce(v_note, '')) > 500 or char_length(coalesce(v_recipient, '')) > 100 then
        raise exception using errcode = 'TM422', message = 'The note or the recipient name is too long.';
    end if;

    v_new_booking_status := v_status;
    if v_to = 'STARTED' then
        if v_status = 'Pending' then
            raise exception using errcode = 'TM409',
                message = 'Booking #' || p_booking_id || ' is waiting for the administrator to confirm it. You can start once it is Confirmed.';
        end if;
        if v_status = 'Confirmed' then
            v_new_booking_status := 'In Progress';
        end if;
    elsif v_to = 'DELIVERED' then
        if v_recipient is null then
            raise exception using errcode = 'TM422', message = 'Enter the name of the person who received the delivery.';
        end if;
        v_new_booking_status := 'Completed';
    elsif v_to = 'FAILED' then
        if v_note is null then
            raise exception using errcode = 'TM422', message = 'Enter the reason the delivery failed.';
        end if;
    end if;

    update public.bookings
       set delivery_status = v_to,
           delivery_status_at = now(),
           status = v_new_booking_status
     where booking_id = p_booking_id;

    v_event_note := concat_ws(' | ', case when v_recipient is not null then 'Received by ' || v_recipient end, v_note);
    insert into trimax_private.delivery_events (booking_id, actor_username, source, event_type, from_status, to_status,
                                                booking_status, latitude, longitude, note)
    values (p_booking_id, v_me, 'mobile', 'delivery_status', v_from, v_to, v_new_booking_status,
            p_latitude, p_longitude, nullif(v_event_note, ''));

    perform trimax_private.audit(v_me, 'booking.delivery_status', p_booking_id::text,
        v_from || ' -> ' || v_to || coalesce(' (' || nullif(v_event_note, '') || ')', ''));
    if v_new_booking_status <> v_status then
        perform trimax_private.audit(v_me, 'booking.status_change', p_booking_id::text,
            v_status || ' -> ' || v_new_booking_status || ' (driver ' || lower(v_to) || ')');
    end if;

    return trimax_private.booking_json(p_booking_id, true);
end $$;

-- -----------------------------------------------------------------------------------------------------
-- 8. Privileges
-- -----------------------------------------------------------------------------------------------------
-- The functions above run with search_path = public, pg_temp (not ''), because the existing desktop
-- trigger on booking_items refers to "bookings" without a schema and must still resolve it when a
-- function inserts items. That is only safe if no untrusted role can create objects in "public",
-- so make sure the API roles cannot (Supabase does not grant this by default; this is a no-op then).
revoke create on schema public from public;
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke create on schema public from anon, authenticated';
    end if;
end $$;

-- Private objects: owner only.
revoke all on all tables in schema trimax_private from public;
revoke all on all sequences in schema trimax_private from public;
revoke execute on all functions in schema trimax_private from public;

do $$
declare
    v_fn text;
    v_api_roles text := 'anon, authenticated';
    v_has_service boolean := exists (select 1 from pg_roles where rolname = 'service_role');
    v_t text;
begin
    if not exists (select 1 from pg_roles where rolname = 'authenticated') then
        raise exception 'Role "authenticated" not found: this script is for a Supabase database.';
    end if;
    if v_has_service then
        v_api_roles := v_api_roles || ', service_role';
    end if;

    execute 'revoke all on all tables in schema trimax_private from ' || v_api_roles;
    execute 'revoke all on all sequences in schema trimax_private from ' || v_api_roles;
    execute 'revoke execute on all functions in schema trimax_private from ' || v_api_roles;

    -- Mobile RPCs: signed-in users only (Supabase's default privileges would otherwise grant anon).
    foreach v_fn in array array[
        'public.trimax_my_account()',
        'public.trimax_list_products()',
        'public.trimax_agent_create_booking(uuid, text, text, text, double precision, double precision, jsonb, text, text)',
        'public.trimax_agent_list_bookings(int, int)',
        'public.trimax_agent_get_booking(int)',
        'public.trimax_driver_list_deliveries(text)',
        'public.trimax_driver_get_delivery(int)',
        'public.trimax_driver_update_delivery_status(int, text, text, double precision, double precision, text, text)']
    loop
        execute 'revoke all on function ' || v_fn || ' from public, ' || v_api_roles;
        execute 'grant execute on function ' || v_fn || ' to authenticated';
    end loop;

    -- Legacy tables: no direct API access at all. The phone uses the functions above; the desktop
    -- connects as the owner. (Restoring these grants would expose password hashes and customer data.)
    foreach v_t in array array['users', 'roles', 'clients', 'agents', 'products', 'vehicles', 'bookings',
                               'booking_items', 'audit_log', 'login_sessions', 'password_resets',
                               'admin_notifications', 'user_locations']
    loop
        if to_regclass('public.' || v_t) is not null then
            execute format('revoke all on table public.%I from anon, authenticated', v_t);
        end if;
    end loop;
    -- Their sequences too.
    for v_t in
        select format('%I.%I', sn.nspname, s.relname)
        from pg_class s
        join pg_namespace sn on sn.oid = s.relnamespace
        join pg_depend d on d.objid = s.oid and d.deptype in ('a', 'i')
        join pg_class t on t.oid = d.refobjid
        where s.relkind = 'S' and sn.nspname = 'public'
          and t.relname in ('users', 'roles', 'clients', 'agents', 'products', 'vehicles', 'bookings',
                            'booking_items', 'audit_log', 'login_sessions', 'password_resets',
                            'admin_notifications', 'user_locations')
    loop
        execute 'revoke all on sequence ' || v_t || ' from anon, authenticated';
    end loop;
end $$;

comment on schema trimax_private is 'TRIMAX mobile integration internals. Not exposed through the Supabase API.';
comment on column public.bookings.delivery_status is
    'Driver progress: ASSIGNED, STARTED, PICKED_UP, ON_THE_WAY, ARRIVED, DELIVERED, FAILED. Maintained by trimax_private triggers and RPCs.';

commit;

-- Ask PostgREST to pick up the new functions (harmless if it is not listening).
notify pgrst, 'reload schema';
