-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261026_01_employee_mobile_sign_in.sql.
-- Desktop steps run as app_owner (the database owner), Edge Function steps as service_role with a
-- service JWT, phone steps as authenticated. The Edge Function's Auth Admin API calls are stood in for by
-- inserts into auth.users with the same app_metadata the function sends.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

-- ------------------------------------------------------------------ fixtures (superuser)
alter table auth.users add column if not exists raw_app_meta_data jsonb not null default '{}'::jsonb;
-- Production's audit_log only accepts TRIMAX users as actors (audit_log_actor_username_fkey); the
-- reconstructed test schema lacks it, so add it here for new rows.
do $$ begin
    if not exists (select 1 from pg_constraint where conname = 'audit_log_actor_username_fkey') then
        alter table public.audit_log add constraint audit_log_actor_username_fkey
            foreign key (actor_username) references public.users (username) not valid;
    end if;
end $$;
insert into users (username, password, name, email, contact_num, role_id, account_status)
select v.u, 'x', v.n, v.e, v.c, r.role_id, 'Active'
  from (values ('agent.new',     'New Agent',     'new@example.test',     '09170000101', 'Agent'),
               ('driver.retry',  'Retry Driver',  'retry@example.test',   '09170000102', 'Driver'),
               ('driver.manual', 'Manual Driver', 'manual@example.test',  '09170000103', 'Driver'),
               ('driver.stuck',  'Stuck Driver',  'stuck@example.test',   '09170000104', 'Driver'),
               ('agent.twin1',   'Twin One',      'twin@example.test',    '09170000105', 'Agent'),
               ('agent.twin2',   'Twin Two',      'TWIN@example.test ',   '09170000106', 'Agent')) v(u, n, e, c, role)
  join roles r on r.role_name = v.role;
delete from trimax_private.provisioning_tickets;

\set new_uid    '00000000-0000-0000-0000-0000000a0001'
\set retry_uid  '00000000-0000-0000-0000-0000000a0002'
\set manual_uid '00000000-0000-0000-0000-0000000a0003'
-- sha256 of the codes, as the Desktop stores them (lowercase hex)
\set code_hash `printf ABCD2345 | sha256sum | cut -d' ' -f1`
\set ana  '00000000-0000-0000-0000-00000000a0a0'

-- ------------------------------------------------------------------ privileges
select test.ok(has_function_privilege('service_role', 'public.trimax_service_claim_provisioning_ticket(uuid)', 'execute')
               and has_function_privilege('service_role', 'public.trimax_service_use_setup_code(text,text)', 'execute'),
               'the Edge Function (service role) can claim tickets and check setup codes');
select test.ok(not has_function_privilege('anon', 'public.trimax_service_claim_provisioning_ticket(uuid)', 'execute')
               and not has_function_privilege('authenticated', 'public.trimax_service_claim_provisioning_ticket(uuid)', 'execute')
               and not has_function_privilege('anon', 'public.trimax_service_use_setup_code(text,text)', 'execute')
               and not has_function_privilege('authenticated', 'public.trimax_service_use_setup_code(text,text)', 'execute'),
               'phones and anonymous callers can call neither service function');
select test.ok(not has_function_privilege('service_role', 'trimax_private.issue_provisioning_ticket(text,text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.issue_provisioning_ticket(text,text)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.claim_provisioning_request(bigint,interval)', 'execute'),
               'only the database owner (Desktop) issues tickets; the claim helper is private');
select test.ok(not has_table_privilege('service_role', 'trimax_private.provisioning_tickets', 'select')
               and not has_table_privilege('authenticated', 'trimax_private.provisioning_tickets', 'select'),
               'nobody but the owner reads the ticket table');

-- ------------------------------------------------------------------ Desktop (owner) asks for tickets
set role app_owner;
select test.expect_error($$select trimax_private.issue_provisioning_ticket('agent.ben', 'agent.new')$$,
                         'TM403', 'a non-admin cannot create mobile sign-ins');
select test.expect_error($$select trimax_private.issue_provisioning_ticket('marc.admin', 'marc.admin')$$,
                         'TM422', 'no mobile sign-in for an Admin account');
select test.expect_error($$select trimax_private.issue_provisioning_ticket('marc.admin', 'driver.nomail')$$,
                         'TM422', 'no mobile sign-in without a valid email');
select test.expect_error($$select trimax_private.issue_provisioning_ticket('marc.admin', 'agent.ana')$$,
                         'TM422', 'no second sign-in for an account that already has one');
select test.expect_error($$select trimax_private.issue_provisioning_ticket('marc.admin', 'agent.twin1')$$,
                         'TM422', 'no sign-in while two accounts share an email');
select trimax_private.issue_provisioning_ticket('marc.admin', 'agent.new') as t1 \gset
select trimax_private.issue_provisioning_ticket('marc.admin', 'agent.new') as t2 \gset
select test.ok(:'t1' <> :'t2', 'each call gets its own ticket');
select test.ok((select count(*) from trimax_private.provisioning_requests where username = 'agent.new') = 1,
               'asking twice queues ONE request (no duplicate)');
select test.ok((select count(*) from audit_log where action = 'account.mobile_invite_requested' and target_id = 'agent.new'
                   and actor_username = 'marc.admin') = 1, 'the request is audited with the admin''s name');
reset role;

-- ------------------------------------------------------------------ Edge Function claims (service role)
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select public.trimax_service_claim_provisioning_ticket(:'t1') as c1 \gset
select test.ok((:'c1'::jsonb ->> 'status') = 'processing' and (:'c1'::jsonb ->> 'username') = 'agent.new'
               and (:'c1'::jsonb ->> 'email') = 'new@example.test' and (:'c1'::jsonb -> 'existing_auth_user_id') = 'null'::jsonb,
               'ticket claims the request: no sign-in with that email yet');
select test.expect_error(format($$select public.trimax_service_claim_provisioning_ticket(%L)$$, :'t1'), 'TM403',
                         'a ticket works only once');
select test.ok((public.trimax_service_claim_provisioning_ticket(:'t2') ->> 'status') = 'busy',
               'a second ticket while the first call is still working gets "busy" (no parallel creation)');
reset role;
-- what the Edge Function does next: Auth Admin API creates the user ...
insert into auth.users (id, email, email_confirmed_at, raw_app_meta_data)
values (:'new_uid', 'new@example.test', now(), '{"trimax_provisioned": true, "trimax_username": "agent.new"}');
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
-- ... and records it with the EXISTING finish function
select test.ok((public.trimax_service_finish_provisioning((:'c1'::jsonb ->> 'request_id')::bigint, 'invited', :'new_uid') ->> 'status') = 'invited',
               'finish records the new sign-in');
reset role;
select test.ok((select actor_username from audit_log where action = 'account.mobile_invite_sent' and target_id = 'agent.new') = 'marc.admin',
               'the new sign-in is audited as the admin who asked (audit actors must be TRIMAX users)');
select test.ok((select username from trimax_private.mobile_accounts where auth_user_id = :'new_uid') = 'agent.new',
               'the Supabase sign-in is linked to public.users agent.new');
select test.ok((select account_status from users where username = 'agent.new') = 'Active'
               and (select r.role_name from users u join roles r on r.role_id = u.role_id where u.username = 'agent.new') = 'Agent',
               'role and status are unchanged by provisioning');
set role app_owner;
select test.expect_error($$select trimax_private.issue_provisioning_ticket('marc.admin', 'agent.new')$$,
                         'TM422', 'asking again after success is refused (already has a sign-in)');
reset role;

-- ------------------------------------------------------------------ interrupted call, then retry
set role app_owner;
select trimax_private.issue_provisioning_ticket('marc.admin', 'driver.retry') as r1 \gset
reset role;
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select public.trimax_service_claim_provisioning_ticket(:'r1') as rc1 \gset
reset role;
-- the Auth user was created, then the call died before finish
insert into auth.users (id, email, email_confirmed_at, raw_app_meta_data)
values (:'retry_uid', 'retry@example.test', now(), '{"trimax_provisioned": true, "trimax_username": "driver.retry"}');
update trimax_private.provisioning_requests set updated_at = now() - interval '3 minutes'
 where request_id = (:'rc1'::jsonb ->> 'request_id')::bigint;
set role app_owner;
select trimax_private.issue_provisioning_ticket('marc.admin', 'driver.retry') as r2 \gset
reset role;
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select public.trimax_service_claim_provisioning_ticket(:'r2') as rc2 \gset
select test.ok((:'rc2'::jsonb ->> 'request_id') = (:'rc1'::jsonb ->> 'request_id')
               and (:'rc2'::jsonb ->> 'existing_auth_user_id') = :'retry_uid'
               and (:'rc2'::jsonb ->> 'existing_auth_created_by_trimax')::boolean
               and (:'rc2'::jsonb -> 'existing_auth_linked_username') = 'null'::jsonb,
               'retry finds the sign-in the interrupted call made (created by TRIMAX, not linked)');
select test.ok((public.trimax_service_finish_provisioning((:'rc2'::jsonb ->> 'request_id')::bigint, 'linked', :'retry_uid') ->> 'status') = 'linked',
               'retry links it instead of creating a second one');
reset role;
select test.ok((select count(*) from auth.users where lower(email) = 'retry@example.test') = 1
               and (select username from trimax_private.mobile_accounts where auth_user_id = :'retry_uid') = 'driver.retry',
               'one Supabase sign-in, linked, after the retry');

-- ------------------------------------------------------------------ a sign-in made by hand in the Dashboard
insert into auth.users (id, email, email_confirmed_at) values (:'manual_uid', 'manual@example.test', now());
set role app_owner;
select trimax_private.issue_provisioning_ticket('marc.admin', 'driver.manual') as m1 \gset
reset role;
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select public.trimax_service_claim_provisioning_ticket(:'m1') as mc \gset
select test.ok(not (:'mc'::jsonb ->> 'existing_auth_created_by_trimax')::boolean,
               'a hand-made sign-in is reported as not created by TRIMAX');
select test.ok((public.trimax_service_finish_provisioning((:'mc'::jsonb ->> 'request_id')::bigint, 'linked', :'manual_uid') ->> 'status') = 'failed',
               'finish refuses to link a sign-in TRIMAX did not create (the admin uses Link mobile sign-in)');
reset role;
select test.ok(not exists (select 1 from trimax_private.mobile_accounts where username = 'driver.manual'),
               'nothing linked for the hand-made sign-in');

-- ------------------------------------------------------------------ expired ticket, too many attempts
set role app_owner;
select trimax_private.issue_provisioning_ticket('marc.admin', 'driver.stuck') as s1 \gset
reset role;
update trimax_private.provisioning_tickets set expires_at = now() - interval '1 second' where ticket = :'s1';
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select test.expect_error(format($$select public.trimax_service_claim_provisioning_ticket(%L)$$, :'s1'), 'TM403', 'an expired ticket is refused');
reset role;
update trimax_private.provisioning_requests set attempts = 5 where username = 'driver.stuck' and status = 'pending';
set role app_owner;
select trimax_private.issue_provisioning_ticket('marc.admin', 'driver.stuck') as s2 \gset
reset role;
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select test.ok((public.trimax_service_claim_provisioning_ticket(:'s2') ->> 'status') = 'failed',
               'after 5 attempts the request fails with a message instead of looping');
reset role;
select test.ok((select count(*) from audit_log where action = 'account.mobile_invite_failed' and target_id = 'driver.stuck') = 1,
               'the failure is audited');
set role app_owner;
select test.ok(trimax_private.issue_provisioning_ticket('marc.admin', 'driver.stuck') is not null,
               'after a failure the admin can try again (a new request is queued)');
reset role;

-- ------------------------------------------------------------------ phones and strangers
set role authenticated;
select test.jwt(:'ana');
select test.expect_error(format($$select public.trimax_service_claim_provisioning_ticket(%L)$$, :'t2'), '42501', 'a phone cannot claim tickets');
select test.expect_error($$select public.trimax_service_use_setup_code('new@example.test', 'ABCD2345')$$, '42501', 'a phone cannot check codes directly');
select test.expect_error($$select trimax_private.issue_provisioning_ticket('marc.admin', 'agent.new')$$, '42501', 'a phone cannot issue tickets');
reset role;
select set_config('request.jwt.claims', '', false);

-- ------------------------------------------------------------------ setup code from the email (service role)
insert into password_resets (username, code_hash, expires_at) values
    ('agent.new',     :'code_hash', now() + interval '1 day'),
    ('driver.manual', :'code_hash', now() + interval '1 day'),
    ('driver.old',    :'code_hash', now() + interval '1 day'),
    ('marc.admin',    :'code_hash', now() + interval '1 day'),
    ('agent.twin1',   :'code_hash', now() + interval '1 day');
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select test.ok(not (public.trimax_service_use_setup_code('new@example.test', 'WRONG234') ->> 'ok')::boolean,
               'a wrong code is refused');
select test.ok((select attempts from password_resets where username = 'agent.new' and used = false) = 1,
               'the wrong try is counted (no exception rolls it back)');
select test.ok((public.trimax_service_use_setup_code('nobody@example.test', 'ABCD2345') ->> 'message')
               = (public.trimax_service_use_setup_code('new@example.test', 'WRONG234') ->> 'message'),
               'an unknown email gets the same answer as a wrong code (no account discovery)');
select public.trimax_service_use_setup_code(' NEW@example.test ', ' abcd2345 ') as ok1 \gset
select test.ok((:'ok1'::jsonb ->> 'ok')::boolean and (:'ok1'::jsonb ->> 'auth_user_id') = :'new_uid',
               'the right code (any case, spaces) gives the linked sign-in');
select test.ok(not (public.trimax_service_use_setup_code('new@example.test', 'ABCD2345') ->> 'ok')::boolean,
               'a code works only once');
select test.ok(position('not ready' in public.trimax_service_use_setup_code('manual@example.test', 'ABCD2345') ->> 'message') > 0,
               'a right code for an account without a TRIMAX sign-in says it is not ready');
select test.ok((select used from password_resets where username = 'driver.manual') = false,
               '... and keeps the code for after the admin fixes it');
select test.ok(position('not active' in public.trimax_service_use_setup_code('old@example.test', 'ABCD2345') ->> 'message') > 0,
               'an inactive account cannot set a phone password');
select test.ok(position('does not use the mobile app' in public.trimax_service_use_setup_code('marc@example.test', 'ABCD2345') ->> 'message') > 0,
               'an Admin code does nothing on the phone');
select test.ok(not (public.trimax_service_use_setup_code('twin@example.test', 'ABCD2345') ->> 'ok')::boolean,
               'an email shared by two accounts is refused');
reset role;
insert into password_resets (username, code_hash, expires_at) values ('agent.new', :'code_hash', now() - interval '1 second');
insert into password_resets (username, code_hash, expires_at) values ('driver.retry', :'code_hash', now() + interval '1 day');
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select test.ok(not (public.trimax_service_use_setup_code('new@example.test', 'ABCD2345') ->> 'ok')::boolean,
               'an expired code is refused');
select test.ok(count(*) = 5, 'five wrong tries ...')
  from generate_series(1, 5) g where not (public.trimax_service_use_setup_code('retry@example.test', 'WRONG234') ->> 'ok')::boolean;
select test.ok(position('Too many' in public.trimax_service_use_setup_code('retry@example.test', 'ABCD2345') ->> 'message') > 0,
               '... then even the right code is refused and the code is burned');
reset role;
select test.ok((select count(*) from audit_log where action = 'account.mobile_password_set' and target_id = 'agent.new') = 1,
               'setting the phone password is audited');

select 'ALL EMPLOYEE SIGN-IN SQL TESTS PASSED' as result \gset
\echo :result
