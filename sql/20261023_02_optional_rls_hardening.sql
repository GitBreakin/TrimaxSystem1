-- =====================================================================================================
-- OPTIONAL second layer, after sql/20261023_01_mobile_booking_integration.sql.
--
-- 01 already revokes anon/authenticated access to the legacy tables. This script adds:
--   1. Row Level Security ON (no policies) for the legacy tables, so even an accidental future GRANT
--      exposes no rows through the API. Table owners are not subject to RLS, so the desktop keeps
--      working AS LONG AS IT CONNECTS AS THE OWNER (today: postgres.<project>). If you later move the
--      desktop to a separate, less privileged login role, that role needs BYPASSRLS or policies.
--   2. Stops Supabase from automatically granting NEW tables/functions created by this role in
--      "public" to anon/authenticated. New tables then need an explicit grant to be reachable from the
--      API, which is the safe default for this project (the phone uses functions, not tables).
--
-- Review the preflight output first. Reverse with the statements at the bottom.
-- =====================================================================================================
begin;
set local lock_timeout = '10s';

do $$
declare
    v_t text;
    v_not_owned text;
begin
    select string_agg(c.relname || ' (owner ' || pg_get_userbyid(c.relowner) || ')', ', ')
      into v_not_owned
      from pg_class c
     where c.relnamespace = 'public'::regnamespace and c.relkind = 'r'
       and c.relname in ('users', 'roles', 'clients', 'agents', 'products', 'vehicles', 'bookings', 'booking_items',
                         'audit_log', 'login_sessions', 'password_resets', 'admin_notifications', 'user_locations')
       and c.relowner <> (select oid from pg_roles where rolname = current_user);
    if v_not_owned is not null then
        raise exception 'Run this as the owner of the TRIMAX tables. Not owned by %: %', current_user, v_not_owned;
    end if;

    foreach v_t in array array['users', 'roles', 'clients', 'agents', 'products', 'vehicles', 'bookings',
                               'booking_items', 'audit_log', 'login_sessions', 'password_resets',
                               'admin_notifications', 'user_locations']
    loop
        if to_regclass('public.' || v_t) is not null then
            execute format('alter table public.%I enable row level security', v_t);
        end if;
    end loop;
end $$;

alter default privileges in schema public revoke all on tables from anon, authenticated;
alter default privileges in schema public revoke all on sequences from anon, authenticated;
alter default privileges in schema public revoke execute on functions from anon, authenticated;

commit;

-- To reverse:
--   alter table public.<table> disable row level security;      -- for each table above
--   alter default privileges in schema public grant all on tables to anon, authenticated;
--   alter default privileges in schema public grant all on sequences to anon, authenticated;
--   alter default privileges in schema public grant execute on functions to anon, authenticated;
