-- TEST ONLY. Run by sql/tests/run_tests.sh on a disposable database, after 39_storage_emulation.sql and
-- 20261025_04_delivery_proofs.sql. Checks the delivery-proofs bucket rules and trimax_driver_attach_proof.
-- Every check prints "PASS ..." or stops with "FAIL ...".
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana  '00000000-0000-0000-0000-00000000a0a0'
\set dan  '00000000-0000-0000-0000-00000000d0d0'
\set dee  '00000000-0000-0000-0000-00000000e0e0'

-- ------------------------------------------------------------------ fixtures (superuser)
insert into trimax_private.mobile_accounts (auth_user_id, username) values
    (:'ana', 'agent.ana'), (:'dan', 'driver.dan'), (:'dee', 'driver.dee')
on conflict do nothing;

-- dan: one confirmed but not started, one started; dee: one started.
insert into public.bookings (client_id, driver_username, vehicle_id, status, delivery_status)
values (1, 'driver.dan', 'TRK-001', 'Confirmed', 'ASSIGNED') returning booking_id as b_waiting \gset
insert into public.bookings (client_id, driver_username, vehicle_id, status, delivery_status)
values (1, 'driver.dan', 'TRK-001', 'In Progress', 'STARTED') returning booking_id as b_started \gset
insert into public.bookings (client_id, driver_username, vehicle_id, status, delivery_status)
values (2, 'driver.dee', 'TRK-002', 'In Progress', 'STARTED') returning booking_id as b_dee \gset

-- ------------------------------------------------------------------ schema checks (superuser)
select test.ok(exists (select 1 from storage.buckets where id = 'delivery-proofs' and not public
                       and file_size_limit = 5242880 and allowed_mime_types = array['image/jpeg']),
               'private delivery-proofs bucket: JPEG only, 5 MB');
select test.ok((select count(*) from pg_policies where schemaname = 'storage' and tablename = 'objects'
                 and policyname like 'trimax delivery proof %') = 2, 'two own-folder storage policies');
select test.ok((select relrowsecurity from pg_class where oid = 'public.delivery_proofs'::regclass),
               'RLS is on for delivery_proofs');
select test.ok(not has_table_privilege('authenticated', 'public.delivery_proofs', 'select')
               and not has_table_privilege('anon', 'public.delivery_proofs', 'select'),
               'phones cannot read delivery_proofs directly');
select test.ok(not has_function_privilege('anon',
               'public.trimax_driver_attach_proof(integer,text,text,double precision,double precision,timestamptz,uuid)', 'execute'),
               'anon cannot call trimax_driver_attach_proof');
select test.ok(has_function_privilege('authenticated',
               'public.trimax_driver_attach_proof(integer,text,text,double precision,double precision,timestamptz,uuid)', 'execute'),
               'authenticated can call trimax_driver_attach_proof');
select test.ok(not has_function_privilege('authenticated',
               'trimax_private.storage_object_uploaded(text,text,uuid,interval)', 'execute'),
               'phones cannot call the private storage check');

-- ------------------------------------------------------------------ phone: driver.dan uploads
set role authenticated;
select test.jwt(:'dan');

insert into storage.objects (bucket_id, name, metadata)
select 'delivery-proofs', :'dan' || '/proof_' || g || '.jpg', '{"size": 2048}'::jsonb from generate_series(1, 7) g;
select test.ok(true, 'driver uploads into his own folder');
select test.expect_error(format($$insert into storage.objects (bucket_id, name, metadata) values ('delivery-proofs', %L, '{"size":1}')$$,
                                :'dee' || '/x.jpg'), '42501', 'driver cannot upload into another driver''s folder');
insert into storage.objects (bucket_id, name, metadata) values ('delivery-proofs', :'dan' || '/empty.jpg', '{"size": 0}');
reset role;
insert into storage.objects (bucket_id, name, owner, owner_id, metadata, created_at)
values ('delivery-proofs', :'dee' || '/dee_1.jpg', :'dee', :'dee', '{"size": 900}', now()),
       ('delivery-proofs', :'dan' || '/old.jpg', :'dan', :'dan', '{"size": 900}', now() - interval '2 days');
set role authenticated;
select test.jwt(:'dan');
select test.ok((select count(*) from storage.objects where bucket_id = 'delivery-proofs') = 9,
               'driver sees only his own photos (7 + empty + old), not another driver''s');

-- ------------------------------------------------------------------ phone: attach proofs
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_waiting, :'dan' || '/proof_1.jpg'),
                         'TM409', 'proof refused before the delivery is started');
select (public.trimax_driver_attach_proof(:b_started, :'dan' || '/proof_1.jpg', ' Maria Santos ', 14.79, 121.06,
        now() - interval '5 minutes', 'cccccccc-0000-0000-0000-000000000001') ->> 'proof_id')::bigint as proof1 \gset
select test.ok(:proof1 > 0, 'proof attached to a started delivery');
select test.ok((public.trimax_driver_attach_proof(:b_started, :'dan' || '/proof_1.jpg', 'Maria Santos', 14.79, 121.06,
        null, 'cccccccc-0000-0000-0000-000000000001') ->> 'already_recorded')::boolean, 'retry with the same request id returns the first proof');
select test.ok((public.trimax_driver_attach_proof(:b_started, :'dan' || '/proof_1.jpg', null, null, null, null,
        'cccccccc-0000-0000-0000-000000000099') ->> 'proof_id')::bigint = :proof1, 'same photo under a new request id is not added twice');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L, null, null, null, null, 'cccccccc-0000-0000-0000-000000000001')$$,
                                :b_waiting, :'dan' || '/proof_2.jpg'), 'TM409', 'a request id cannot be reused for another booking');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_started, :'dee' || '/dee_1.jpg'),
                         'TM422', 'photo outside the driver''s own folder refused');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_started, :'dan' || '/../x.jpg'),
                         'TM422', 'path tricks refused');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_started, :'dan' || '/missing.jpg'),
                         'TM422', 'photo that was never uploaded refused');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_started, :'dan' || '/empty.jpg'),
                         'TM422', 'empty upload refused');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_started, :'dan' || '/old.jpg'),
                         'TM422', 'upload older than 24 hours refused');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_dee, :'dan' || '/proof_2.jpg'),
                         'TM404', 'proof refused on another driver''s delivery');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L, %L)$$, :b_started, :'dan' || '/proof_2.jpg', repeat('x', 101)),
                         'TM422', 'recipient over 100 characters refused');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L, null, 14.7, null)$$, :b_started, :'dan' || '/proof_2.jpg'),
                         'TM422', 'half a location refused');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L, null, null, null, now() + interval '1 day')$$,
                                :b_started, :'dan' || '/proof_2.jpg'), 'TM422', 'photo time in the future refused');
select test.expect_error($$select * from public.delivery_proofs$$, '42501', 'driver cannot read delivery_proofs directly');

-- Delivered with the existing function, then more photos still attach (the phone uploads after DELIVERED).
select test.ok((public.trimax_driver_update_delivery_status(:b_started, 'STARTED', 'DELIVERED', 14.79, 121.06, null, 'Maria Santos')
                ->> 'status') = 'Completed', 'delivery completed (STARTED -> DELIVERED)');
select test.ok((public.trimax_driver_attach_proof(:b_started, :'dan' || '/proof_2.jpg') ->> 'proof_id') is not null,
               'proof attached after the delivery was completed');
select public.trimax_driver_attach_proof(:b_started, :'dan' || '/proof_' || g || '.jpg') from generate_series(3, 5) g;
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_started, :'dan' || '/proof_6.jpg'),
                         'TM409', 'sixth proof photo on one delivery refused');

-- ------------------------------------------------------------------ other roles
select test.jwt(:'ana');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_started, :'ana' || '/a.jpg'),
                         'TM403', 'agents cannot attach delivery proofs');
select test.jwt('00000000-0000-0000-0000-000000002222');
select test.expect_error(format($$select public.trimax_driver_attach_proof(%s, %L)$$, :b_started, 'x/a.jpg'),
                         'TM401', 'a sign-in without a TRIMAX account is refused');

-- ------------------------------------------------------------------ desktop (owner) and audit
reset role;
set role app_owner;
select test.ok((select count(*) from public.delivery_proofs where booking_id = :b_started) = 5,
               'desktop (owner) reads the 5 proofs of the delivery');
select test.ok((select recipient_name from public.delivery_proofs where proof_id = :proof1) = 'Maria Santos',
               'recipient name stored trimmed');
select test.ok((select count(*) from public.audit_log where action = 'booking.proof_photo' and target_id = :b_started::text) = 5,
               'each proof is in the audit log');
reset role;

select 'ALL STAGE E SQL TESTS PASSED';
