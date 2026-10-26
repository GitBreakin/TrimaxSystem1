-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261027_03_driver_my_vehicle.sql.
-- Desktop steps as app_owner (the database owner, like the Desktop over JDBC); phone steps as
-- authenticated with a JWT subject (like PostgREST). Every check prints PASS or stops with FAIL.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana '00000000-0000-0000-0000-00000000a0a0'
\set dan '00000000-0000-0000-0000-00000000d0d0'
\set dee '00000000-0000-0000-0000-00000000e0e0'
-- agent.new, linked by test 44
\set other '00000000-0000-0000-0000-0000000a0001'

-- ================================================================== 1. Who can call it
select test.ok(has_function_privilege('authenticated', 'public.trimax_driver_my_vehicle()', 'execute')
               and not has_function_privilege('anon', 'public.trimax_driver_my_vehicle()', 'execute')
               and not has_function_privilege('service_role', 'public.trimax_driver_my_vehicle()', 'execute'),
               'signed-in phones can call trimax_driver_my_vehicle; anonymous callers and the service role cannot');
select test.ok((select prosecdef and pronargs = 0 from pg_proc where proname = 'trimax_driver_my_vehicle'),
               'it takes no parameter (the driver comes from the sign-in, never from the request)');
set role anon;
select test.expect_error('select public.trimax_driver_my_vehicle()', '42501', 'an anonymous caller is refused');
reset role;

-- ================================================================== 2. Each driver sees only their own vehicle
-- Earlier test files may have moved vehicles around: set a known state as the Desktop would.
set role app_owner;
update vehicles set username = null where username in ('driver.dan', 'driver.dee') and vehicle_id not in ('TRK-001', 'TRK-002');
update vehicles set username = 'driver.dan', vehicle_type = 'Truck' where vehicle_id = 'TRK-001';
update vehicles set username = 'driver.dee', vehicle_type = 'Truck' where vehicle_id = 'TRK-002';
reset role;

set role authenticated;
select test.jwt(:'dan');
select public.trimax_driver_my_vehicle() as dan_v \gset
select test.ok(jsonb_array_length(:'dan_v'::jsonb -> 'vehicles') = 1
               and :'dan_v'::jsonb -> 'vehicles' -> 0 ->> 'plate_number' = 'TRK-001'
               and :'dan_v'::jsonb -> 'vehicles' -> 0 ->> 'vehicle_type' = 'Truck'
               and :'dan_v'::jsonb -> 'vehicles' -> 0 ->> 'status' is not null
               and :'dan_v'::jsonb ? 'checked_at',
               'driver.dan gets his own vehicle: TRK-001, Truck, with its status');
select test.ok(:'dan_v'::text !~ 'TRK-002', 'driver.dan does not get driver.dee''s vehicle');
select test.jwt(:'dee');
select test.ok((select string_agg(e ->> 'plate_number', ',') = 'TRK-002'
                  from jsonb_array_elements(public.trimax_driver_my_vehicle() -> 'vehicles') e),
               'driver.dee gets only TRK-002');
select test.jwt(:'ana');
select test.expect_error('select public.trimax_driver_my_vehicle()', 'TM403', 'an agent cannot use the driver function');
reset role;

-- ================================================================== 3. Admin edits on the Desktop show up
set role app_owner;
insert into vehicles (vehicle_id, username, vehicle_type, status) values ('TST-900', 'driver.dan', 'Van', 'Available');
-- the same statement as ManageVehiclesPanel (vehicle_id is the plate number)
update vehicles set vehicle_id = 'NBC-1234', username = 'driver.dan', vehicle_type = 'Motorcycle' where vehicle_id = 'TST-900';
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.ok((select count(*) = 1 from jsonb_array_elements(public.trimax_driver_my_vehicle() -> 'vehicles') e
                 where e ->> 'plate_number' = 'NBC-1234' and e ->> 'vehicle_type' = 'Motorcycle')
               and (select count(*) = 0 from jsonb_array_elements(public.trimax_driver_my_vehicle() -> 'vehicles') e
                     where e ->> 'plate_number' = 'TST-900'),
               'after the Admin changes the plate and type, the driver gets NBC-1234, Motorcycle (not the old plate)');
select test.ok(jsonb_array_length(public.trimax_driver_my_vehicle() -> 'vehicles') = 2,
               'a driver with two assigned vehicles gets both');
reset role;
set role app_owner;
update vehicles set username = null where username = 'driver.dan';
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.ok(jsonb_array_length(public.trimax_driver_my_vehicle() -> 'vehicles') = 0,
               'a driver without an assigned vehicle gets an empty list (the app shows "No vehicle assigned")');
reset role;
set role app_owner;
delete from vehicles where vehicle_id = 'NBC-1234';
update vehicles set username = 'driver.dan' where vehicle_id = 'TRK-001';
reset role;
-- Production's bookings.vehicle_id follows a plate change (ON UPDATE CASCADE, checked on production
-- 2026-10-27 and in 20261026_00_legacy_tables_from_production.sql); this test schema is older and has no rule.

-- ================================================================== 4. Customer isolation, one more direct path
-- (tests 10 and 45 already check lists, saving and opening bookings across agents)
set role app_owner;
select min(product_id) as anyprod from products where stock > 0 \gset
select c.client_id as anas, c.contact_num as anas_contact from clients c
  join agents a on a.client_id = c.client_id and a.username = 'agent.ana' order by c.client_id limit 1 \gset
reset role;
set role authenticated;
select test.jwt(:'other');
select test.expect_error(format($$select public.trimax_agent_create_booking(gen_random_uuid(), 'Not mine', %L, 'X', 14.8, 121.0,
                                   '[{"product_id": %s, "quantity": 1}]')$$, :'anas_contact', :anyprod),
                         'TM409', 'another agent cannot create a booking for agent.ana''s customer by sending her contact number');
select test.ok(not exists (select 1 from jsonb_array_elements(public.trimax_agent_list_customers()) e
                            where (e ->> 'client_id')::int = :anas),
               'and still does not see that customer in the list');
reset role;

select 'ALL DRIVER VEHICLE SQL TESTS PASSED' as result;
\echo ALL DRIVER VEHICLE SQL TESTS PASSED
