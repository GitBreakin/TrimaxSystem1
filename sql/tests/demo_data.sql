-- TEST ONLY. Made-up demo data for screenshots and manual UI checks on a DISPOSABLE local database built by
-- sql/tests/rebuild_check.sh-style steps (20261026_00 + the migrations). Never run it on Supabase.
-- Every name, phone number and address below is invented. Demo admin: owen.demo / DemoAdmin#2026.
\set ON_ERROR_STOP on
-- Guard: refuse to run anywhere except the local database named trimax_demo (Supabase's is "postgres").
do $$
begin
  if current_database() <> 'trimax_demo' then
    raise exception 'demo_data.sql only runs on a local database named trimax_demo, not on %', current_database();
  end if;
end $$;
select setseed(0.42);

insert into roles (role_name) values ('Admin'), ('Agent'), ('Driver'), ('Rider') on conflict do nothing;

insert into users (username, password, name, email, contact_num, role_id, account_status)
select v.u, v.p, v.n, v.e, v.c, r.role_id, v.s
  from (values
    ('owen.demo',     'pbkdf2$600000$KO1thBXPmpHqwp13OSVovg==$GuaPFY4hFIkKiLNDLk5UMk9iOiCiKYpZCbMUfzyC9/c=', 'Owen Demo',      'owen.demo@example.test',  '09170000100', 'Admin',  'Active'),
    ('agent_lea',     'x', 'Lea Navarro',     'lea@example.test',     '09170000003', 'Agent',  'Active'),
    ('agent_rico',    'x', 'Rico Valdez',     'rico@example.test',    '09170000002', 'Agent',  'Active'),
    ('agent_tess',    'x', 'Tess Manalo',     'tess@example.test',    '09170000004', 'Agent',  'Active'),
    ('agent_jun',     'x', 'Jun Esguerra',    'jun@example.test',     '09170000005', 'Agent',  'Pending'),
    ('driver_juan',   'x', 'Juan Dela Cruz',  'juan@example.test',    '09171234567', 'Driver', 'Active'),
    ('driver_maria',  'x', 'Maria Santos',    'maria@example.test',   '09181234567', 'Driver', 'Active'),
    ('driver_ken',    'x', 'Ken Bisaya',      'ken@example.test',     '09232242342', 'Driver', 'Active'),
    ('driver_pedro',  'x', 'Pedro Ramos',     'pedro@example.test',   '09191234567', 'Driver', 'Inactive')
  ) as v(u, p, n, e, c, role, s)
  join roles r on r.role_name = v.role
on conflict do nothing;

insert into vehicles (vehicle_id, username, vehicle_type, status, latitude, longitude, last_location_at) values
  ('TST-1001', 'driver_juan',  'Truck',      'On Delivery', 14.7942, 121.0581, now() - interval '2 minutes'),
  ('TST-2002', 'driver_maria', 'Van',        'Available',   14.7811, 121.0450, now() - interval '25 minutes'),
  ('MVC-2211', 'driver_ken',   'Motorcycle', 'Available',   14.8133, 121.0412, now() - interval '3 hours'),
  ('TST-3003', 'driver_pedro', 'Truck',      'Maintenance', null, null, null)
on conflict do nothing;

insert into clients (client_name, address, contact_num, latitude, longitude) values
  ('Aling Nena Sari-sari Store', 'Tungko, San Jose del Monte, Bulacan',  '09170001001', 14.7804, 121.0632),
  ('Bayanihan Bakery',           'Muzon, San Jose del Monte, Bulacan',   '09170001002', 14.8021, 121.0353),
  ('Casa Lucia Carinderia',      'Graceville, San Jose del Monte',       '09170001003', 14.7768, 121.0511),
  ('Dagat Seafood Trading',      'Sapang Palay, San Jose del Monte',     '09170001004', 14.8344, 121.0466),
  ('Esteban Hardware',           'Poblacion, Norzagaray, Bulacan',       '09170001005', 14.9112, 121.0459),
  ('Fiesta Party Needs',         'Kaypian, San Jose del Monte',          '09170001006', 14.7972, 121.0705),
  ('Golden Grains Rice Retail',  'Minuyan, San Jose del Monte',          '09170001007', 14.8216, 121.0601),
  ('Halo-Halo Haven',            'Gumaoc, San Jose del Monte',           '09170001008', 14.7881, 121.0399),
  ('Ilaw Electrical Supply',     'Santa Maria, Bulacan',                 '09170001009', 14.8186, 120.9614),
  ('Josefa''s Water Refilling',  'Assumption, San Jose del Monte',       '09170001010', 14.8089, 121.0518),
  ('Kusina ni Kuya',             'Citrus, San Jose del Monte',           '09170001011', 14.8165, 121.0252),
  ('Lakbay Travel & Tours',      'Fairview, Quezon City',                '09170001012', 14.7071, 121.0624)
on conflict do nothing;

insert into agents (username, client_id)
select a, c.client_id from (values ('agent_lea', 1), ('agent_lea', 2), ('agent_rico', 3), ('agent_rico', 4), ('agent_rico', 5),
                                   ('agent_tess', 6), ('agent_tess', 7), ('agent_tess', 8)) v(a, n)
join clients c on c.client_id = v.n
on conflict do nothing;

insert into products (product_name, supplier, price, stock) values
  ('Purified water 5 gal',     'AquaPure',        35.00, 240),
  ('Mineral water 500 ml x24', 'AquaPure',       180.00,  64),
  ('LPG 11 kg refill',         'Petron',         950.00,  18),
  ('LPG 2.7 kg refill',        'Petron',         310.00,   6),
  ('Rice 25 kg sack',          'Golden Harvest', 1250.00,  42),
  ('Cooking oil 1 L',          'Minola',          98.00,   0),
  ('Ice tube 5 kg',            'Polar Ice',       60.00, 120),
  ('Balikbayan box (large)',   'TRIMAX',         150.00,   9)
on conflict do nothing;

-- 40 bookings over the last 30 days (Asia/Manila), mixed statuses, channels and drivers.
with b as (
  select g,
         (now() - (random() * 29 || ' days')::interval - (random() * 10 || ' hours')::interval) as at,
         1 + floor(random() * 12)::int as client,
         case when g % 10 in (0, 1, 2, 3) then 'Completed' when g % 10 in (4, 5) then 'Confirmed'
              when g % 10 = 6 then 'In Progress' when g % 10 = 7 then 'Cancelled' else 'Pending' end as status,
         case when g % 3 = 0 then 'desktop' else 'mobile' end as via,
         (array['driver_juan', 'driver_maria', 'driver_ken'])[1 + g % 3] as driver,
         (array['TST-1001', 'TST-2002', 'MVC-2211'])[1 + g % 3] as vehicle,
         (array['agent_lea', 'agent_rico', 'agent_tess'])[1 + g % 3] as agent
    from generate_series(1, 40) g
)
insert into bookings (client_id, transc_date, status, driver_username, vehicle_id, created_via, created_by_username,
                      client_request_id, delivery_address, delivery_latitude, delivery_longitude, requested_schedule)
select c.client_id, b.at, b.status,
       case when b.status in ('Pending', 'Cancelled') and b.g % 2 = 0 then null else b.driver end,
       case when b.status in ('Pending', 'Cancelled') and b.g % 2 = 0 then null else b.vehicle end,
       b.via, case when b.via = 'mobile' then b.agent end,
       case when b.via = 'mobile' then gen_random_uuid() end,
       c.address, c.latitude::double precision, c.longitude::double precision,
       case when b.g % 4 = 0 then 'Morning, before 10 AM' when b.g % 4 = 1 then 'After lunch' end
  from b join clients c on c.client_id = b.client
 order by b.at;

insert into booking_items (booking_id, product_id, quantity, unit_price, subtotal)
select bk.booking_id, p.product_id, q, p.price, q * p.price
  from bookings bk
  cross join lateral (select product_id, price from products order by random() limit 1 + (bk.booking_id % 3)) p
  cross join lateral (select 1 + floor(random() * 6)::int as q) qq
on conflict do nothing;

-- Delivery progress that matches each status.
update bookings set delivery_status = 'DELIVERED', delivery_status_at = transc_date + interval '3 hours'
 where status = 'Completed' and driver_username is not null;
update bookings set delivery_status = 'STARTED', delivery_status_at = now() - interval '40 minutes'
 where status = 'In Progress';
update bookings set delivery_status = 'FAILED', delivery_status_at = transc_date + interval '2 hours'
 where booking_id in (select booking_id from bookings where status = 'Confirmed' order by booking_id limit 1);
insert into trimax_private.delivery_events (booking_id, occurred_at, actor_username, source, event_type, from_status, to_status, booking_status)
select booking_id, transc_date + interval '1 hour', driver_username, 'mobile', 'delivery_status', 'ASSIGNED', 'STARTED', 'In Progress'
  from bookings where delivery_status in ('STARTED', 'DELIVERED', 'FAILED');
insert into trimax_private.delivery_events (booking_id, occurred_at, actor_username, source, event_type, from_status, to_status, booking_status, note)
select booking_id, delivery_status_at, driver_username, 'mobile', 'delivery_status', 'STARTED', delivery_status,
       case when delivery_status = 'DELIVERED' then 'Completed' else status end,
       case when delivery_status = 'FAILED' then 'Gate locked, customer not answering' else 'Received by store owner' end
  from bookings where delivery_status in ('DELIVERED', 'FAILED');
insert into delivery_proofs (booking_id, driver_username, photo_path, recipient_name, latitude, longitude, taken_at)
select booking_id, driver_username, '00000000-0000-0000-0000-00000000d0d0/proof_' || booking_id || '.jpg', 'Store owner',
       delivery_latitude, delivery_longitude, delivery_status_at
  from bookings where delivery_status = 'DELIVERED' limit 6;

insert into user_locations (username, latitude, longitude, accuracy_m, recorded_at) values
  ('driver_juan', 14.7942, 121.0581, 8, now() - interval '2 minutes'),
  ('driver_maria', 14.7811, 121.0450, 12, now() - interval '25 minutes')
on conflict do nothing;

insert into trimax_private.duty_events (username, event_type, photo_path, latitude, longitude, recorded_at)
select u, e, '00000000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0') || '/' || lower(e) || '_' || n || '.jpg',
       14.78 + n / 1000.0, 121.05 - n / 1000.0, now() - (n || ' hours')::interval
  from (values ('driver_juan', 'START', 9), ('driver_maria', 'START', 8), ('agent_lea', 'START', 7),
               ('agent_lea', 'END', 2), ('driver_ken', 'START', 30), ('driver_ken', 'END', 22)) v(u, e, n);

insert into admin_notifications (kind, category, title, body, related_type, related_id, reported_by, created_at, read_at, read_by) values
  ('Alert', 'SOS', 'SOS from Juan Dela Cruz', 'Engine overheating near Bocaue toll' || chr(10) || 'Location: 14.800000, 120.950000',
   'user', 'driver_juan', 'driver_juan', now() - interval '35 minutes', null, null),
  ('Alert', 'Delivery problem', 'Problem reported by Maria Santos', 'Gate locked, customer not answering',
   'booking', '7', 'driver_maria', now() - interval '3 hours', now() - interval '2 hours', 'owen.demo'),
  ('Announcement', 'General', 'Office closed on Nov 1', 'All Saints'' Day', null, null, null, now() - interval '1 day', now() - interval '20 hours', 'owen.demo');

insert into audit_log (actor_username, action, target_type, target_id, result, details, occurred_at)
select 'owen.demo', a, t, i, 'success', d, now() - (n || ' minutes')::interval
  from (values ('auth.login', 'user', 'owen.demo', 'Signed in', 5), ('booking.confirm', 'booking', '12', 'Pending -> Confirmed', 18),
               ('booking.assign', 'booking', '12', 'driver_juan / TST-1001', 19), ('product.update', 'product', '3', 'Stock 20 -> 18', 44),
               ('account.create', 'user', 'agent_jun', 'Agent account created; setup email sent', 95),
               ('notification.mark_read', 'notification', '2', '1 changed', 120)) v(a, t, i, d, n);
