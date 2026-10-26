-- TEST ONLY. Run by sql/tests/run_tests.sh after 39_storage_emulation.sql, _04 and _05.
-- Checks the one-time photo tickets the Desktop uses to view duty photos and delivery proofs.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set dan  '00000000-0000-0000-0000-00000000d0d0'

-- ------------------------------------------------------------------ fixtures (superuser)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('duty-photos', 'duty-photos', false, 5242880, array['image/jpeg']) on conflict do nothing;
insert into storage.objects (bucket_id, name, owner, owner_id, metadata)
values ('duty-photos', :'dan' || '/start_1_aa.jpg', :'dan', :'dan', '{"size": 2048}')
on conflict do nothing;
delete from trimax_private.media_tickets;

-- ------------------------------------------------------------------ privileges
select test.ok(not has_function_privilege('authenticated', 'public.trimax_service_redeem_media_ticket(uuid)', 'execute')
               and not has_function_privilege('anon', 'public.trimax_service_redeem_media_ticket(uuid)', 'execute'),
               'phones cannot redeem photo tickets');
select test.ok(has_function_privilege('service_role', 'public.trimax_service_redeem_media_ticket(uuid)', 'execute'),
               'the Edge Function (service role) can redeem tickets');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.issue_media_ticket(text,text,text)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.issue_media_ticket(text,text,text)', 'execute'),
               'only the database owner (Desktop) can issue tickets');
select test.ok(not has_table_privilege('service_role', 'trimax_private.media_tickets', 'select')
               and not has_table_privilege('authenticated', 'trimax_private.media_tickets', 'select'),
               'nobody but the owner reads the ticket table');

-- ------------------------------------------------------------------ Desktop (owner) issues tickets
set role app_owner;
select trimax_private.issue_media_ticket('marc.admin', 'duty-photos', :'dan' || '/start_1_aa.jpg') as t1 \gset
select test.ok(:'t1' is not null, 'admin gets a ticket for an existing duty photo');
select test.expect_error(format($$select trimax_private.issue_media_ticket('driver.dan', 'duty-photos', %L)$$, :'dan' || '/start_1_aa.jpg'),
                         'TM403', 'a non-admin cannot get a ticket');
select test.expect_error(format($$select trimax_private.issue_media_ticket('marc.admin', 'duty-photos', %L)$$, :'dan' || '/missing.jpg'),
                         'TM404', 'no ticket for a photo that is not on the server');
select test.expect_error($$select trimax_private.issue_media_ticket('marc.admin', 'products', 'x/a.jpg')$$,
                         'TM422', 'no ticket for another bucket');
select test.expect_error(format($$select trimax_private.issue_media_ticket('marc.admin', 'duty-photos', %L)$$, :'dan' || '/../x.jpg'),
                         'TM422', 'no ticket for a path trick');
select test.ok((select count(*) from public.audit_log where action = 'media.view' and target_id = 'driver.dan') = 1,
               'viewing is audited against the photo''s owner (driver.dan)');
select trimax_private.issue_media_ticket('marc.admin', 'duty-photos', :'dan' || '/start_1_aa.jpg') as t_old \gset
reset role;
update trimax_private.media_tickets set expires_at = now() - interval '1 second' where ticket = :'t_old';

-- ------------------------------------------------------------------ Edge Function (service role) redeems
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select test.ok((public.trimax_service_redeem_media_ticket(:'t1') ->> 'path') = :'dan' || '/start_1_aa.jpg',
               'ticket redeemed: bucket and path of that one photo');
select test.expect_error(format($$select public.trimax_service_redeem_media_ticket(%L)$$, :'t1'), 'TM403', 'a ticket works only once');
select test.expect_error(format($$select public.trimax_service_redeem_media_ticket(%L)$$, :'t_old'), 'TM403', 'an expired ticket is refused');
select test.expect_error($$select public.trimax_service_redeem_media_ticket('3f2b8c1e-1a2b-4c3d-8e9f-0a1b2c3d4e5f')$$,
                         'TM403', 'an unknown ticket is refused');
reset role;

set role authenticated;
select test.jwt(:'dan');
select test.expect_error(format($$select public.trimax_service_redeem_media_ticket(%L)$$, :'t_old'), '42501',
                         'a phone cannot call the redeem function at all');
reset role;

select 'ALL MEDIA TICKET SQL TESTS PASSED';
