-- TEST ONLY. Never run against the real Supabase project.
-- The desktop repository has no schema scripts (sql/001..003 are referenced in code but were never
-- committed), so this reconstructs the tables from the SQL the desktop actually runs
-- (BookingService, BookingsPanel, AuthService, AccountService, ClientsPanel, InventoryPanel,
-- ManageVehiclesPanel, AuditService, GpsService). Column types are best guesses where the code does
-- not pin them down. Run as app_owner after 00_supabase_emulation.sql.

create table roles (
    role_id serial primary key,
    role_name text not null unique
);

create table users (
    username text primary key,
    password text not null,
    name text,
    email text,
    contact_num text,
    role_id int references roles(role_id),
    account_status text not null default 'Pending',
    failed_attempts int not null default 0,
    locked_until timestamptz
);

create table login_sessions (
    token_hash text primary key,
    username text not null references users(username),
    expires_at timestamptz not null
);

create table password_resets (
    reset_id serial primary key,
    username text not null references users(username),
    code_hash text not null,
    expires_at timestamptz not null,
    attempts int not null default 0,
    used boolean not null default false
);

create table clients (
    client_id serial primary key,
    client_name text not null,
    address text,
    contact_num text,
    latitude double precision,
    longitude double precision
);

create table agents (
    agent_id serial primary key,
    username text not null references users(username),
    client_id int not null references clients(client_id) on delete cascade,
    unique (username, client_id)
);

create table products (
    product_id serial primary key,
    product_name text not null,
    supplier text,
    price numeric(12, 2) not null check (price >= 0),
    stock int not null check (stock >= 0)
);

create table vehicles (
    vehicle_id text primary key,
    username text references users(username),
    vehicle_type text,
    status text not null default 'Available',
    last_location_at timestamptz
);

-- sql/001_booking_statuses.sql (referenced by BookingStatus.java) is the CHECK below.
create table bookings (
    booking_id serial primary key,
    client_id int references clients(client_id),
    driver_username text references users(username),
    vehicle_id text references vehicles(vehicle_id),
    transc_date timestamptz not null default now(),
    total_amount numeric(12, 2) not null default 0,
    status text not null default 'Pending'
        check (status in ('Pending', 'Confirmed', 'In Progress', 'Completed', 'Cancelled'))
);

create table booking_items (
    item_id serial primary key,
    booking_id int not null references bookings(booking_id) on delete cascade,
    product_id int not null references products(product_id),
    quantity int not null check (quantity > 0),
    unit_price numeric(12, 2) not null check (unit_price >= 0),
    subtotal numeric(12, 2) not null,
    check (subtotal = quantity * unit_price)
);

-- "bookings.total_amount is filled in by the trigger on booking_items" (BookingService)
create function booking_items_total() returns trigger language plpgsql as $$
begin
    update bookings b
       set total_amount = coalesce((select sum(subtotal) from booking_items i
                                     where i.booking_id = b.booking_id), 0)
     where b.booking_id = coalesce(new.booking_id, old.booking_id);
    return null;
end $$;
create trigger trg_booking_items_total after insert or update or delete on booking_items
for each row execute function booking_items_total();

-- sql/002_audit_log.sql: insert/select only; the table blocks updates and deletes.
create table audit_log (
    audit_id bigserial primary key,
    occurred_at timestamptz not null default now(),
    actor_username text,
    action text not null,
    target_type text,
    target_id text,
    result text,
    details text
);
create function audit_log_block() returns trigger language plpgsql as $$
begin raise exception 'audit_log is append-only'; end $$;
create trigger trg_audit_log_block before update or delete on audit_log
for each row execute function audit_log_block();

-- ---------------------------------------------------------------- seed data (fake people)
insert into roles (role_name) values ('Admin'), ('Agent'), ('Driver'), ('Rider');

insert into users (username, password, name, email, contact_num, role_id, account_status)
select v.username, 'x', v.name, v.email, v.contact, r.role_id, v.status
from (values
    ('marc.admin',  'Marc Admin',    'marc@example.test',    '09170000001', 'Admin',  'Active'),
    ('agent.ana',   'Ana Agent',     'ana@example.test',     '09170000002', 'Agent',  'Active'),
    ('agent.ben',   'Ben Agent',     'ben@example.test',     '09170000003', 'Agent',  'Active'),
    ('rider.rio',   'Rio Rider',     'rio@example.test',     '09170000004', 'Rider',  'Active'),
    ('driver.dan',  'Dan Driver',    'dan@example.test',     '09170000005', 'Driver', 'Active'),
    ('driver.dee',  'Dee Driver',    'dee@example.test',     '09170000006', 'Driver', 'Active'),
    ('driver.old',  'Old Driver',    'old@example.test',     '09170000007', 'Driver', 'Disabled'),
    ('driver.nomail','No Mail',      null,                   '09170000008', 'Driver', 'Active')
) as v(username, name, email, contact, role, status)
join roles r on r.role_name = v.role;

insert into clients (client_name, address, contact_num, latitude, longitude) values
    ('Sari-Sari Uno', 'Muzon, SJDM', '0917 111 2222', 14.80, 121.04),
    ('Store Dos',     'Tungko, SJDM', '+63 917 333 4444', 14.78, 121.06),
    ('Walk-in Tres',  'Graceville',   '09175556666', 14.79, 121.05);
-- Ana serves client 1, Ben serves client 2, client 3 has no agent.
insert into agents (username, client_id) values ('agent.ana', 1), ('agent.ben', 2);

insert into products (product_name, supplier, price, stock) values
    ('Coke Case (24 Bottles)',   'Coca-Cola', 180.00, 50),
    ('Sprite Case (24 Bottles)', 'Coca-Cola', 180.00, 3),
    ('Royal Case (24 Bottles)',  'Coca-Cola', 175.00, 0);

insert into vehicles (vehicle_id, username, vehicle_type, status) values
    ('TRK-001', 'driver.dan', 'Truck', 'Available'),
    ('TRK-002', 'driver.dee', 'Truck', 'Available'),
    ('VAN-003', 'driver.old', 'Van',   'Available');

-- One existing desktop booking, so the migration runs against non-empty tables.
insert into bookings (client_id, driver_username, vehicle_id) values (3, 'driver.dan', 'TRK-001');
insert into booking_items (booking_id, product_id, quantity, unit_price, subtotal) values (1, 1, 2, 180.00, 360.00);
update products set stock = stock - 2 where product_id = 1;
