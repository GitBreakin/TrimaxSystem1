-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261028_05_delivery_order_nearest_hub.sql.
-- Desktop steps run as app_owner (the database owner, like the Desktop over JDBC), phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set seqd '00000000-0000-0000-0000-00000000500d'
\set ana  '00000000-0000-0000-0000-00000000a0a0'

-- ================================================================== 1. Access
select test.ok(not has_function_privilege('authenticated', 'trimax_private.delivery_sequence(text)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.delivery_sequence(text)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.delivery_sequence(text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.hub_distance_m(double precision,double precision)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.hub_distance_m(double precision,double precision)', 'execute'),
               'delivery order and hub distance are for the database owner only');
select test.ok(has_function_privilege('authenticated', 'public.trimax_driver_list_deliveries(text)', 'execute')
               and not has_function_privilege('anon', 'public.trimax_driver_list_deliveries(text)', 'execute')
               and (select prosecdef from pg_proc where oid = 'public.trimax_driver_list_deliveries(text)'::regprocedure),
               'the phone function keeps its access: signed-in users only, security definer');

-- ================================================================== 2. Distance from the hub
select test.ok(trimax_private.hub_distance_m(14.7826996, 121.0475865) < 0.001, 'the hub itself is 0 m from the hub');
select test.ok(trimax_private.hub_distance_m(14.7900, 121.0475865) between 805 and 820,
               'a pin 0.0073 degrees north is about 812 m away');
select test.ok(trimax_private.hub_distance_m(null, 121.0) is null and trimax_private.hub_distance_m(14.8, null) is null,
               'no pin: no distance (not 0)');

-- ================================================================== 3. A driver with mixed deliveries
insert into auth.users (id, email, email_confirmed_at) values (:'seqd', 'seq@example.test', now());
insert into users (username, password, name, email, contact_num, role_id, account_status)
select 'driver.seq', 'x', 'Seq Driver', 'seq@example.test', '09170000099', role_id, 'Active' from roles where role_name = 'Driver';
set role app_owner;
select trimax_private.link_mobile_account('driver.seq', 'seq@example.test', 'marc.admin') is not null as linked \gset
reset role;
select test.ok(:'linked'::boolean, 'driver.seq is linked to a phone account');

insert into clients (client_name, address, contact_num, latitude, longitude) values
    ('Seq Pinned Customer', 'Near the hub', '09170000100', 14.7950, 121.0475865),   -- about 1.37 km
    ('Seq No Pin Customer', 'Somewhere',    '09170000101', null, null);
select client_id as c_pin from clients where client_name = 'Seq Pinned Customer' \gset
select client_id as c_nopin from clients where client_name = 'Seq No Pin Customer' \gset

-- One booking assigned to driver.seq; returns its id.
create function pg_temp.bk(p_client int, p_status text, p_lat double precision, p_lon double precision,
                           p_delivery text, p_driver text default 'driver.seq', p_minutes_ago int default 0) returns int
language plpgsql as $f$
declare b int;
begin
    insert into bookings (client_id, status, driver_username, delivery_latitude, delivery_longitude, transc_date)
    values (p_client, p_status, p_driver, p_lat, p_lon, now() - make_interval(mins => p_minutes_ago))
    returning booking_id into b;
    update bookings set delivery_status = p_delivery, assigned_at = now() - make_interval(mins => p_minutes_ago)
     where booking_id = b;
    return b;
end $f$;

-- ~3.0 km, assigned first
select pg_temp.bk(:c_nopin, 'Confirmed',   14.8100, 121.0475865, 'ASSIGNED', 'driver.seq', 60) as b_far \gset
-- ~0.8 km
select pg_temp.bk(:c_nopin, 'Confirmed',   14.7900, 121.0475865, 'ASSIGNED', 'driver.seq', 50) as b_near \gset
-- ~0.5 km, not confirmed yet
select pg_temp.bk(:c_nopin, 'Pending',     14.7870, 121.0475865, 'ASSIGNED', 'driver.seq', 40) as b_pending \gset
-- ~6.4 km, under way
select pg_temp.bk(:c_nopin, 'In Progress', 14.8400, 121.0475865, 'ON_THE_WAY', 'driver.seq', 30) as b_started \gset
-- no pin at all
select pg_temp.bk(:c_nopin, 'Confirmed',   null, null,           'ASSIGNED', 'driver.seq', 70) as b_nopin \gset
-- customer's pin ~1.37 km
select pg_temp.bk(:c_pin,   'Confirmed',   null, null,           'ASSIGNED', 'driver.seq', 20) as b_custpin \gset
-- finished
select pg_temp.bk(:c_nopin, 'Completed',   14.7830, 121.0475865, 'DELIVERED', 'driver.seq', 10) as b_done \gset
-- failed: not active
select pg_temp.bk(:c_nopin, 'In Progress', 14.7840, 121.0475865, 'FAILED', 'driver.seq', 5) as b_failed \gset
-- another driver
select pg_temp.bk(:c_nopin, 'Confirmed',   14.7830, 121.0475865, 'ASSIGNED', 'driver.dee', 5) as b_other \gset

-- ================================================================== 4. The order
set role app_owner;
select test.ok((select array_agg(s.booking_id order by s.seq) from trimax_private.delivery_sequence('driver.seq') s)
               = array[:b_started, :b_near, :b_custpin, :b_far, :b_nopin, :b_pending]::int[],
               'order: started first; then Confirmed nearest to the hub (0.8, 1.4, 3.0 km, no pin last); then Pending');
select test.ok((select array_agg(s.seq order by s.seq) from trimax_private.delivery_sequence('driver.seq') s) = array[1, 2, 3, 4, 5, 6],
               'numbered 1 to 6 without gaps');
select test.ok((select s.distance_m between 1350 and 1400 from trimax_private.delivery_sequence('driver.seq') s where s.booking_id = :b_custpin),
               'a booking without its own pin uses the customer''s pin (about 1.37 km)');
select test.ok((select s.distance_m is null and s.stage = 1 from trimax_private.delivery_sequence('driver.seq') s where s.booking_id = :b_nopin),
               'no pin anywhere: no distance, last among the Confirmed ones');
select test.ok(not exists (select 1 from trimax_private.delivery_sequence('driver.seq') s where s.booking_id in (:b_done, :b_failed, :b_other)),
               'finished, failed and other drivers'' bookings are not in this driver''s order');
select test.ok((select array_agg(row(s.booking_id, s.seq)::text order by s.seq) from trimax_private.delivery_sequence() s where s.driver_username = 'driver.dee')
               = (select array_agg(row(s.booking_id, s.seq)::text order by s.seq) from trimax_private.delivery_sequence('driver.dee') s)
               and (select bool_and(n = mx) from (select count(*) as n, max(s.seq) as mx from trimax_private.delivery_sequence() s group by s.driver_username) x)
               and exists (select 1 from trimax_private.delivery_sequence() s where s.booking_id = :b_other),
               'without a driver the order is worked out per driver (each driver starts at #1)');
reset role;

-- ================================================================== 5. What the driver's phone gets
set role authenticated;
select test.jwt(:'seqd');
select public.trimax_driver_list_deliveries('active') as active \gset
select public.trimax_driver_list_deliveries('history') as history \gset
reset role;
select test.ok((select array_agg((e ->> 'booking_id')::int order by ord) from jsonb_array_elements(:'active'::jsonb) with ordinality t(e, ord))
               = array[:b_started, :b_near, :b_custpin, :b_far, :b_nopin, :b_pending]::int[],
               'the phone list comes in the same order (#1 is the dashboard''s current delivery)');
select test.ok((select array_agg((e ->> 'sequence')::int order by ord) from jsonb_array_elements(:'active'::jsonb) with ordinality t(e, ord))
               = array[1, 2, 3, 4, 5, 6],
               'each delivery carries its Seq number');
select test.ok((select (e ->> 'distance_from_hub_m')::int between 805 and 820 from jsonb_array_elements(:'active'::jsonb) e
                 where (e ->> 'booking_id')::int = :b_near)
               and (select e -> 'distance_from_hub_m' = 'null'::jsonb from jsonb_array_elements(:'active'::jsonb) e
                     where (e ->> 'booking_id')::int = :b_nopin),
               'each delivery carries its distance from the hub in metres (null without a pin)');
select test.ok((select bool_and(e ? 'booking_id' and e ? 'status' and e ? 'delivery_status' and (e -> 'delivery') ? 'latitude' and e ? 'items')
                  from jsonb_array_elements(:'active'::jsonb) e),
               'the rest of each delivery is unchanged (booking_json)');
select test.ok((select array_agg((e ->> 'booking_id')::int) from jsonb_array_elements(:'history'::jsonb) e) @> array[:b_done, :b_failed]::int[]
               and not (select array_agg((e ->> 'booking_id')::int) from jsonb_array_elements(:'history'::jsonb) e) && array[:b_near]::int[],
               'the history list is unchanged');
set role authenticated;
select test.jwt(:'ana');
select test.expect_error($$select public.trimax_driver_list_deliveries('active')$$, 'TM403',
                         'an agent cannot read a driver''s list');
reset role;

-- ================================================================== 6. The order follows the work
update bookings set delivery_status = 'DELIVERED', status = 'Completed' where booking_id = :b_started;
update bookings set status = 'Confirmed' where booking_id = :b_pending;
set role app_owner;
select test.ok((select array_agg(s.booking_id order by s.seq) from trimax_private.delivery_sequence('driver.seq') s)
               = array[:b_pending, :b_near, :b_custpin, :b_far, :b_nopin]::int[],
               'after the started one is delivered and the Pending one is confirmed: the nearest (0.5 km) is #1');
reset role;
update bookings set delivery_status = 'STARTED', status = 'In Progress' where booking_id = :b_far;
set role app_owner;
select test.ok((select s.booking_id = :b_far from trimax_private.delivery_sequence('driver.seq') s where s.seq = 1),
               'once the driver starts a farther one it stays #1 while it is under way');
reset role;

drop function pg_temp.bk(int, text, double precision, double precision, text, text, int);
select 'ALL DELIVERY ORDER SQL TESTS PASSED' as result;
\echo ALL DELIVERY ORDER SQL TESTS PASSED
