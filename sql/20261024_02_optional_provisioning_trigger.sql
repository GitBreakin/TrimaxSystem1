-- =====================================================================================================
-- OPTIONAL: call the provisioning Edge Function automatically.
-- File: sql/20261024_02_optional_provisioning_trigger.sql
-- Requires: sql/20261024_01_hub_gps_duty_provisioning.sql, the Edge Function deployed (see
-- supabase/functions/provision-mobile-accounts/README.md), and the pg_net extension
-- (Supabase dashboard > Database > Extensions > pg_net).
--
-- Reads two Vault secrets that YOU create (Supabase dashboard > Project Settings > Vault, or
-- select vault.create_secret('<value>', '<name>')). Nothing secret is stored in this file or in git:
--   trimax_provisioning_url     https://<project-ref>.supabase.co/functions/v1/provision-mobile-accounts
--   trimax_provisioning_secret  the same random value you set as the function's TRIMAX_PROVISIONING_SECRET
--
-- After every insert into the provisioning queue (the desktop's "Send mobile invite"), the database asks
-- the function to process the queue. pg_net sends the request after the transaction commits and never
-- blocks or fails the desktop's insert. If the secrets are missing, nothing is sent (requests stay
-- pending and can be processed by calling the function by hand; see the README).
-- Undo: drop trigger trimax_provisioning_requests_notify on trimax_private.provisioning_requests;
-- =====================================================================================================

begin;

do $$
begin
    if to_regclass('trimax_private.provisioning_requests') is null then
        raise exception 'Run sql/20261024_01_hub_gps_duty_provisioning.sql first.';
    end if;
    if to_regnamespace('net') is null then
        raise exception 'The pg_net extension is not enabled (Database > Extensions > pg_net).';
    end if;
    if to_regclass('vault.decrypted_secrets') is null then
        raise exception 'Supabase Vault (vault.decrypted_secrets) not found.';
    end if;
end $$;

create or replace function trimax_private.notify_provisioning_function()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_url text;
    v_secret text;
begin
    select decrypted_secret into v_url from vault.decrypted_secrets where name = 'trimax_provisioning_url';
    select decrypted_secret into v_secret from vault.decrypted_secrets where name = 'trimax_provisioning_secret';
    if v_url is null or v_secret is null or v_url !~ '^https://' then
        raise notice 'TRIMAX provisioning: Vault secrets not set; the request stays pending.';
        return null;
    end if;
    begin
        perform net.http_post(
            url := v_url,
            body := '{}'::jsonb,
            headers := jsonb_build_object('Content-Type', 'application/json',
                                          'x-trimax-provisioning-secret', v_secret),
            timeout_milliseconds := 10000);
    exception when others then
        -- Never fail the desktop's insert because the notification could not be queued.
        raise notice 'TRIMAX provisioning: could not queue the call (%).', sqlstate;
    end;
    return null;
end $$;

revoke all on function trimax_private.notify_provisioning_function() from public;

drop trigger if exists trimax_provisioning_requests_notify on trimax_private.provisioning_requests;
create trigger trimax_provisioning_requests_notify
    after insert on trimax_private.provisioning_requests
    for each statement execute function trimax_private.notify_provisioning_function();

commit;
