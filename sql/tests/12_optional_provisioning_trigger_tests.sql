-- TEST ONLY. Stand-ins for pg_net and Vault, then sql/20261024_02_optional_provisioning_trigger.sql
-- (applied by run_tests.sh between the two parts of this file).
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\if :{?part2}
\else
create schema if not exists net;
create table net.calls (id bigserial primary key, url text, headers jsonb, body jsonb);
create function net.http_post(url text, body jsonb default '{}'::jsonb, params jsonb default '{}'::jsonb,
                              headers jsonb default '{}'::jsonb, timeout_milliseconds int default 5000)
returns bigint language sql as $$ insert into net.calls (url, headers, body) values (url, headers, body) returning id $$;
create schema if not exists vault;
create table vault.decrypted_secrets (name text primary key, decrypted_secret text);
grant usage on schema net, vault to app_owner;
grant select on vault.decrypted_secrets to app_owner;
grant insert, select on net.calls to app_owner;
grant usage on sequence net.calls_id_seq to app_owner;
grant execute on function net.http_post(text, jsonb, jsonb, jsonb, int) to app_owner;
\quit
\endif

set role app_owner;
insert into users (username, password, name, email, contact_num, role_id, account_status)
select 'driver_trig', 'x', 'Trigger Test', 'trig@example.test', '09171115555', role_id, 'Active' from roles where role_name = 'Driver';
insert into users (username, password, name, email, contact_num, role_id, account_status)
select 'driver_trig2', 'x', 'Trigger Test 2', 'trig2@example.test', '09171116666', role_id, 'Active' from roles where role_name = 'Driver';
select trimax_private.request_mobile_access('driver_trig', 'marc.admin') is not null as ok \gset
select test.ok((select count(*) from net.calls) = 0, 'trigger: without the Vault secrets nothing is sent and the request is still queued');
reset role;
insert into vault.decrypted_secrets values
    ('trimax_provisioning_url', 'https://example-ref.supabase.co/functions/v1/provision-mobile-accounts'),
    ('trimax_provisioning_secret', 'test-secret-value');
set role app_owner;
select trimax_private.request_mobile_access('driver_trig2', 'marc.admin') is not null as ok \gset
select test.ok((select count(*) from net.calls) = 1, 'trigger: a new request asks the Edge Function to run');
select test.ok((select headers ->> 'x-trimax-provisioning-secret' = 'test-secret-value' and url like 'https://%/provision-mobile-accounts'
                from net.calls order by id desc limit 1), 'trigger: the call carries the secret from Vault in a header');
reset role;
drop function net.http_post(text, jsonb, jsonb, jsonb, int);
set role app_owner;
update users set email = 'trig3@example.test' where username = 'driver_trig';
update trimax_private.provisioning_requests set status = 'failed' where username = 'driver_trig';
select test.ok(trimax_private.request_mobile_access('driver_trig', 'marc.admin') is not null, 'trigger: a broken pg_net never blocks the desktop''s request');
reset role;
\echo 'ALL OPTIONAL TRIGGER TESTS PASSED'
