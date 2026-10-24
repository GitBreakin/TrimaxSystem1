-- TEST ONLY. Run by sql/tests/run_tests.sh after 10_mobile_integration_tests.sql and
-- sql/20261024_01_hub_gps_duty_provisioning.sql, on the same disposable database.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana  '00000000-0000-0000-0000-00000000a0a0'
\set ben  '00000000-0000-0000-0000-00000000b0b0'
\set dan  '00000000-0000-0000-0000-00000000d0d0'
\set dee  '00000000-0000-0000-0000-00000000e0e0'

create function test.service_claims() returns void
language sql as $$ select set_config('request.jwt.claims', '{"role":"service_role"}', false) $$;
grant execute on function test.service_claims() to public;

-- ================================================================== 1. Hub-start delivery flow
set role app_owner;
insert into bookings (client_id, driver_username, vehicle_id) values (2, 'driver.dan', 'TRK-001') returning booking_id as h1 \gset
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'ON_THE_WAY')$$, :h1), 'TM409', 'hub: cannot leave before the administrator confirms');
reset role;
set role app_owner;
update bookings set status = 'Confirmed' where booking_id = :h1 and status = 'Pending';
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'STARTED')$$, :h1), 'TM422', 'hub: the old STARTED step is no longer accepted');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ASSIGNED', 'FAILED', null, null, 'x')$$, :h1), 'TM422', 'hub: cannot fail a delivery that has not left the hub');
select public.trimax_driver_update_delivery_status(:h1, 'ASSIGNED', 'ON_THE_WAY', 14.7827, 121.0476) as h1_on \gset
select test.ok((:'h1_on'::jsonb ->> 'delivery_status') = 'ON_THE_WAY' and (:'h1_on'::jsonb ->> 'status') = 'In Progress',
    'hub: ASSIGNED -> ON_THE_WAY in one step, booking becomes In Progress');
select test.expect_error(format($$select public.trimax_driver_update_delivery_status(%s, 'ON_THE_WAY', 'PICKED_UP')$$, :h1), 'TM422', 'hub: nothing can move into PICKED_UP');
select test.ok((public.trimax_driver_update_delivery_status(:h1, 'ON_THE_WAY', 'ARRIVED', 14.80, 121.04) ->> 'delivery_status') = 'ARRIVED', 'hub: ON_THE_WAY -> ARRIVED');
select test.ok((public.trimax_driver_update_delivery_status(:h1, 'ARRIVED', 'DELIVERED', 14.80, 121.04, null, 'Maria') ->> 'status') = 'Completed', 'hub: DELIVERED completes the booking');
reset role;
set role app_owner;
select test.ok((select count(*) from audit_log where target_id = :'h1' and action = 'booking.status_change' and details like '%driver on the way%') = 1,
    'hub: the In Progress move is audited as "driver on the way"');
-- Rows left by older app versions can still finish.
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dan', 'TRK-001', 'In Progress') returning booking_id as h2 \gset
update bookings set delivery_status = 'PICKED_UP' where booking_id = :h2;
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dan', 'TRK-001', 'In Progress') returning booking_id as h3 \gset
update bookings set delivery_status = 'STARTED' where booking_id = :h3;
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.ok((public.trimax_driver_update_delivery_status(:h2, 'PICKED_UP', 'ARRIVED') ->> 'delivery_status') = 'ARRIVED', 'legacy PICKED_UP row can go to ARRIVED');
select test.ok((public.trimax_driver_update_delivery_status(:h3, 'STARTED', 'ON_THE_WAY') ->> 'delivery_status') = 'ON_THE_WAY', 'legacy STARTED row can go to ON_THE_WAY');
select test.ok((public.trimax_driver_update_delivery_status(:h2, 'ARRIVED', 'DELIVERED', null, null, null, 'Ana') ->> 'status') = 'Completed', 'legacy row finishes');
select test.ok((public.trimax_driver_update_delivery_status(:h3, 'ON_THE_WAY', 'FAILED', null, null, 'Gate closed') ->> 'delivery_status') = 'FAILED', 'legacy row can fail');
reset role;

-- ================================================================== 2. Live GPS
set role authenticated;
select test.jwt(:'dan');
select public.trimax_update_location(14.7901, 121.0512, 8.5, 3.2, 90) as loc \gset
select test.ok((:'loc'::jsonb ->> 'stored')::boolean and (:'loc'::jsonb ->> 'vehicles_updated')::int = 1, 'GPS: driver position stored and his vehicle updated');
select test.ok((public.trimax_update_location(14.7902, 121.0513, 8.5) ->> 'throttled')::boolean, 'GPS: an update within 2 seconds is accepted but not written');
select test.expect_error($$select public.trimax_update_location(0, 0, 5)$$, 'TM422', 'GPS: 0,0 refused');
select test.expect_error($$select public.trimax_update_location(95, 121, 5)$$, 'TM422', 'GPS: latitude out of range refused');
select test.expect_error($$select public.trimax_update_location('NaN'::float8, 121, 5)$$, 'TM422', 'GPS: NaN refused');
select test.expect_error($$select public.trimax_update_location(14.79, 121.05, 20000)$$, 'TM422', 'GPS: a 20 km accuracy fix refused');
select test.expect_error($$select public.trimax_update_location(14.79, 121.05, 5, null, null, false)$$, 'TM422', 'GPS: nothing is stored while off duty');
select test.expect_error($$select * from public.user_locations$$, '42501', 'GPS: phones cannot read the location table directly');
select test.expect_error($$update public.vehicles set latitude = 1 where vehicle_id = 'TRK-001'$$, '42501', 'GPS: phones cannot write vehicles directly');
select test.jwt(:'dee');
select test.ok((public.trimax_update_location(14.70, 121.00, 12) ->> 'stored')::boolean, 'GPS: another driver stores his own position');
select test.jwt(:'ana');
select test.ok((public.trimax_update_location(14.75, 121.02, 20) ->> 'vehicles_updated')::int = 0, 'GPS: agent position stored (agents have no vehicle)');
select test.jwt(:'ben');
select test.expect_error($$select public.trimax_update_location(14.75, 121.02, 20)$$, 'TM401', 'GPS: an unlinked sign-in cannot store a position');
reset role;
set role anon;
select test.expect_error($$select public.trimax_update_location(14.75, 121.02, 20)$$, '42501', 'GPS: anonymous callers cannot call the function');
reset role;
set role app_owner;
-- GpsService.fetch (Live GPS page)
select test.ok((select v.latitude = 14.7901 and v.longitude = 121.0512 and extract(epoch from (now() - v.last_location_at)) < 60
                from vehicles v left join users u on u.username = v.username where v.vehicle_id = 'TRK-001'),
    'GPS: desktop Live GPS query sees driver.dan''s position and a fresh timestamp');
select test.ok((select v.latitude = 14.70 from vehicles v where v.vehicle_id = 'TRK-002'), 'GPS: each driver only moved his own vehicle');
-- GpsService.fetchAgents (Agent Locations page)
select test.ok((select count(*) from user_locations l join users u on u.username = l.username join roles r on r.role_id = u.role_id
                where r.role_name in ('Agent', 'Rider') and l.username = 'agent.ana' and l.accuracy_m = 20) = 1,
    'GPS: desktop Agent Locations query sees agent.ana');
reset role;

-- ================================================================== 3. Duty events with photos
set role authenticated;
select test.jwt(:'dan');
select test.expect_error(format($$select public.trimax_record_duty_event('START', '%s/duty-start-1.jpg')$$, :'dan'), 'TM422', 'duty: refused when the photo was not uploaded');
-- Storage upload (what POST /storage/v1/object/duty-photos/<uid>/<file> does, with RLS)
insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
values ('duty-photos', :'dan' || '/duty-start-1.jpg', :'dan', :'dan', '{"size": 34567, "mimetype": "image/jpeg"}');
select test.expect_error(format($$insert into storage.objects (bucket_id, name, owner, owner_id, metadata) values ('duty-photos', '%s/x.jpg', '%s', '%s', '{"size":1}')$$, :'dee', :'dan', :'dan'),
    '42501', 'duty: a user cannot upload into someone else''s folder');
select test.expect_error($$select public.trimax_record_duty_event('START', 'someone/duty-start-1.jpg')$$, 'TM422', 'duty: photo outside the user''s own folder refused');
select public.trimax_record_duty_event('START', :'dan' || '/duty-start-1.jpg', 14.7827, 121.0476, '22222222-2222-2222-2222-222222222222') as d1 \gset
select test.ok((:'d1'::jsonb ->> 'on_duty')::boolean and (:'d1'::jsonb ->> 'last_event') = 'START', 'duty: START recorded with photo');
select test.ok((public.trimax_record_duty_event('START', :'dan' || '/duty-start-1.jpg', 14.7827, 121.0476, '22222222-2222-2222-2222-222222222222') ->> 'already_recorded')::boolean,
    'duty: a retry with the same request id returns the saved event');
select test.ok((public.trimax_my_duty_status() ->> 'on_duty')::boolean, 'duty: status says on duty');
insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
values ('duty-photos', :'dan' || '/duty-start-2.jpg', :'dan', :'dan', '{"size": 30000}');
select test.expect_error(format($$select public.trimax_record_duty_event('START', '%s/duty-start-2.jpg')$$, :'dan'), 'TM409', 'duty: cannot start twice');
select test.expect_error(format($$select public.trimax_record_duty_event('END', '%s/duty-start-1.jpg')$$, :'dan'), 'TM409', 'duty: a photo cannot be used twice');
reset role;
-- A delivery under way blocks ending duty.
set role app_owner;
insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dan', 'TRK-001', 'Confirmed') returning booking_id as h4 \gset
reset role;
set role authenticated;
select test.jwt(:'dan');
select test.ok((public.trimax_driver_update_delivery_status(:h4, 'ASSIGNED', 'ON_THE_WAY') ->> 'delivery_status') = 'ON_THE_WAY', 'duty: driver leaves the hub');
insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
values ('duty-photos', :'dan' || '/duty-end-1.jpg', :'dan', :'dan', '{"size": 31000}');
select test.expect_error(format($$select public.trimax_record_duty_event('END', '%s/duty-end-1.jpg')$$, :'dan'), 'TM409', 'duty: cannot end duty with a delivery under way');
select public.trimax_driver_update_delivery_status(:h4, 'ON_THE_WAY', 'FAILED', null, null, 'Customer moved') is not null as f \gset
select test.ok((public.trimax_record_duty_event('END', :'dan' || '/duty-end-1.jpg') ->> 'on_duty') = 'false', 'duty: END recorded once the delivery is resolved');
insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
values ('duty-photos', :'dan' || '/duty-end-2.jpg', :'dan', :'dan', '{"size": 31000}');
select test.expect_error(format($$select public.trimax_record_duty_event('END', '%s/duty-end-2.jpg')$$, :'dan'), 'TM409', 'duty: cannot end twice');
select test.expect_error(format($$select public.trimax_record_duty_event('PAUSE', '%s/duty-end-2.jpg')$$, :'dan'), 'TM422', 'duty: unknown event refused');
select test.jwt(:'ben');
select test.expect_error($$select public.trimax_my_duty_status()$$, 'TM401', 'duty: unlinked sign-in refused');
select test.expect_error($$select * from trimax_private.duty_events$$, '42501', 'duty: phones cannot read duty history directly');
reset role;
set role app_owner;
select test.ok((select count(*) from trimax_private.duty_events where username = 'driver.dan') = 2, 'duty: exactly one START and one END stored');
select test.ok((select count(*) from audit_log where target_id = 'driver.dan' and action in ('duty.start', 'duty.end')) = 2, 'duty: both events audited');
reset role;

-- ================================================================== 4. Provisioning queue
set role app_owner;
-- AccountService.createEmployee inserts like this (public.users only):
insert into users (username, password, name, email, contact_num, role_id, account_status)
select 'agent_newbie', 'x', 'New Agent', 'newbie@example.test', '09171112222', role_id, 'Active' from roles where role_name = 'Agent';
insert into users (username, password, name, email, contact_num, role_id, account_status)
select 'driver_victim', 'x', 'Victim Driver', 'victim@example.test', '09171113333', role_id, 'Active' from roles where role_name = 'Driver';
insert into users (username, password, name, email, contact_num, role_id, account_status)
select 'agent_twin', 'x', 'Twin', 'ana@example.test', '09171114444', role_id, 'Active' from roles where role_name = 'Agent';
select trimax_private.request_mobile_access('agent_newbie', 'marc.admin') as r1 \gset
select test.ok(trimax_private.request_mobile_access('agent_newbie', 'marc.admin') = :r1, 'provisioning: asking again returns the open request');
select test.expect_error($$select trimax_private.request_mobile_access('marc.admin', 'marc.admin')$$, 'TM422', 'provisioning: Admin accounts are refused');
select test.expect_error($$select trimax_private.request_mobile_access('agent.ana', 'marc.admin')$$, 'TM422', 'provisioning: an account that already has a mobile sign-in is refused');
select test.expect_error($$select trimax_private.request_mobile_access('agent_twin', 'marc.admin')$$, 'TM422', 'provisioning: an email shared by two TRIMAX accounts is refused');
select test.expect_error($$select trimax_private.request_mobile_access('driver.old', 'marc.admin')$$, 'TM422', 'provisioning: a Disabled account is refused');
select test.expect_error($$select public.trimax_service_claim_provisioning(10)$$, 'TM403', 'provisioning: claim refused without the service role JWT');
reset role;
set role authenticated;
select test.jwt(:'ana');
select test.expect_error($$select public.trimax_service_claim_provisioning(10)$$, '42501', 'provisioning: signed-in phones cannot call the service functions');
select test.expect_error($$select trimax_private.request_mobile_access('agent_newbie')$$, '42501', 'provisioning: phones cannot queue requests');
reset role;

set role service_role;
select test.service_claims();
select public.trimax_service_claim_provisioning(10) as claim1 \gset
select test.ok(jsonb_array_length(:'claim1'::jsonb) = 1 and (:'claim1'::jsonb -> 0 ->> 'username') = 'agent_newbie'
               and (:'claim1'::jsonb -> 0 ->> 'existing_auth_user_id') is null, 'provisioning: the function claims the request; no Auth user exists yet');
select test.ok(jsonb_array_length(public.trimax_service_claim_provisioning(10)) = 0, 'provisioning: a claimed request is not handed out twice');
reset role;
-- The Edge Function creates the Auth user with app_metadata (service role only) and sends the invite:
insert into auth.users (id, email, email_confirmed_at, raw_app_meta_data, invited_at)
values ('00000000-0000-0000-0000-00000000aaa1', 'newbie@example.test', null,
        '{"provider":"email","trimax_provisioned":true,"trimax_username":"agent_newbie"}', now());
set role service_role;
select test.service_claims();
select test.ok((public.trimax_service_finish_provisioning(:r1, 'invited', '00000000-0000-0000-0000-00000000aaa1') ->> 'status') = 'invited',
    'provisioning: invited and linked');
select test.expect_error(format($$select public.trimax_service_finish_provisioning(%s, 'linked', '00000000-0000-0000-0000-00000000aaa1')$$, :r1), 'TM409',
    'provisioning: a finished request cannot be reported again');
reset role;
set role authenticated;
select test.jwt('00000000-0000-0000-0000-00000000aaa1');
select test.ok((public.trimax_my_account() ->> 'username') = 'agent_newbie' and (public.trimax_my_account() ->> 'role') = 'AGENT',
    'provisioning: after accepting the invitation the person signs in as their own TRIMAX account');
reset role;

-- An Auth user that someone else created for the victim's email is never linked automatically.
insert into auth.users (id, email, email_confirmed_at, raw_app_meta_data)
values ('00000000-0000-0000-0000-00000000bad1', 'victim@example.test', now(), '{"provider":"email"}');
set role app_owner;
select trimax_private.request_mobile_access('driver_victim', 'marc.admin') as r2 \gset
reset role;
set role service_role;
select test.service_claims();
select public.trimax_service_claim_provisioning(10) as claim2 \gset
select test.ok((:'claim2'::jsonb -> 0 ->> 'existing_auth_user_id') = '00000000-0000-0000-0000-00000000bad1'
               and (:'claim2'::jsonb -> 0 ->> 'existing_auth_created_by_trimax') = 'false',
    'provisioning: the function is told the existing sign-in was not created by TRIMAX');
select test.ok((public.trimax_service_finish_provisioning(:r2, 'linked', '00000000-0000-0000-0000-00000000bad1') ->> 'status') = 'failed',
    'provisioning: linking a sign-in TRIMAX did not create is refused even if the function asks');
reset role;
set role app_owner;
select test.ok(not exists (select 1 from trimax_private.mobile_accounts where username = 'driver_victim'), 'provisioning: victim account not linked');
select test.ok((select last_error from trimax_private.provisioning_requests where request_id = :r2) like '%not created by TRIMAX%', 'provisioning: the reason is stored for the desktop');
-- Metadata made for another account does not count either.
reset role;
update auth.users set raw_app_meta_data = '{"trimax_provisioned":true,"trimax_username":"agent_newbie"}' where id = '00000000-0000-0000-0000-00000000bad1';
set role app_owner;
select trimax_private.request_mobile_access('driver_victim', 'marc.admin') as r3 \gset
select test.ok(:r3 <> :r2, 'provisioning: a failed request does not block a new one');
reset role;
set role service_role;
select test.service_claims();
select public.trimax_service_claim_provisioning(10) is not null as c3 \gset
select test.ok((public.trimax_service_finish_provisioning(:r3, 'linked', '00000000-0000-0000-0000-00000000bad1') ->> 'status') = 'failed',
    'provisioning: a sign-in made for a different TRIMAX account is refused');
reset role;
-- Email changed after the request; too many attempts.
set role app_owner;
select trimax_private.request_mobile_access('driver_victim', 'marc.admin') as r4 \gset
update users set email = 'victim2@example.test' where username = 'driver_victim';
reset role;
set role service_role;
select test.service_claims();
select test.ok(jsonb_array_length(public.trimax_service_claim_provisioning(10)) = 0, 'provisioning: a request whose email changed is not processed');
reset role;
set role app_owner;
select test.ok((select status = 'failed' and last_error like '%email address changed%' from trimax_private.provisioning_requests where request_id = :r4),
    'provisioning: ... and is failed with the reason');
select trimax_private.request_mobile_access('driver_victim', 'marc.admin') as r5 \gset
update trimax_private.provisioning_requests set attempts = 5 where request_id = :r5;
reset role;
set role service_role;
select test.service_claims();
select test.ok(jsonb_array_length(public.trimax_service_claim_provisioning(10)) = 0, 'provisioning: gives up after 5 attempts');
select set_config('request.jwt.claims', '', false);
reset role;
set role app_owner;
select test.ok((select count(*) from audit_log where target_id = 'agent_newbie' and action in ('account.mobile_invite_requested', 'account.mobile_invite_sent')) = 2,
    'provisioning: request and invitation are audited');
select test.ok((select count(*) from trimax_private.mobile_accounts where auth_user_id = '00000000-0000-0000-0000-00000000aaa1') = 1, 'provisioning: exactly one link');
reset role;

\echo 'ALL HUB/GPS/DUTY/PROVISIONING TESTS PASSED'
