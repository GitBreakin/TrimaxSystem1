-- =====================================================================================================
-- READ-ONLY preflight report for the mobile booking integration. Changes nothing.
-- Run it in the Supabase SQL editor before sql/20261023_01_mobile_booking_integration.sql and keep the
-- output with the change record. Each row is one check: OK, WARN (review) or STOP (do not migrate yet).
-- =====================================================================================================
with
legacy(t) as (
    values ('users'), ('roles'), ('clients'), ('agents'), ('products'), ('vehicles'), ('bookings'),
           ('booking_items'), ('audit_log'), ('login_sessions'), ('password_resets'),
           ('admin_notifications'), ('user_locations')
),
tbl as (
    select l.t, c.oid, c.relrowsecurity, pg_get_userbyid(c.relowner) as owner
    from legacy l
    left join pg_class c on c.relname = l.t and c.relnamespace = 'public'::regnamespace and c.relkind = 'r'
),
checks(check_name, result, detail) as (
    select 'Supabase auth schema',
           case when to_regclass('auth.users') is not null and to_regprocedure('auth.uid()') is not null then 'OK' else 'STOP' end,
           'auth.users and auth.uid() must exist'
    union all
    select 'Connected as', 'OK', current_user || ' (the migration should run as the owner of the tables below)'
    union all
    select 'Table ' || t,
           case when oid is null and t in ('audit_log', 'login_sessions', 'password_resets', 'admin_notifications', 'user_locations') then 'OK'
                when oid is null then 'STOP' else 'OK' end,
           case when oid is null then 'missing' else 'owner ' || owner || ', RLS ' || case when relrowsecurity then 'on' else 'off' end end
    from tbl
    union all
    select 'API access to ' || t,
           case when exists (select 1 from information_schema.role_table_grants g
                             where g.table_schema = 'public' and g.table_name = t
                               and g.grantee in ('anon', 'authenticated')) then 'WARN' else 'OK' end,
           coalesce((select string_agg(distinct g.grantee || ':' || g.privilege_type, ', ')
                     from information_schema.role_table_grants g
                     where g.table_schema = 'public' and g.table_name = t
                       and g.grantee in ('anon', 'authenticated')), 'none')
           || '  (WARN = readable/writable with the public API key; the migration revokes this)'
    from tbl where oid is not null
    union all
    select 'users.username unique',
           case when exists (select 1 from pg_constraint con
                             join pg_attribute a on a.attrelid = con.conrelid and a.attnum = con.conkey[1]
                             where con.conrelid = to_regclass('public.users') and con.contype in ('p', 'u')
                               and array_length(con.conkey, 1) = 1 and a.attname = 'username') then 'OK' else 'STOP' end,
           'required for the mobile link table'
    union all
    select 'bookings.status constraint', 'OK',
           coalesce((select string_agg(pg_get_constraintdef(oid), ' / ') from pg_constraint
                     where conrelid = to_regclass('public.bookings') and contype = 'c'
                       and pg_get_constraintdef(oid) ilike '%status%'), 'no CHECK on status found')
    union all
    select 'bookings columns', 'OK',
           (select string_agg(column_name || ' ' || data_type || case when is_nullable = 'NO' then ' not null' else '' end, ', ' order by ordinal_position)
            from information_schema.columns where table_schema = 'public' and table_name = 'bookings')
    union all
    select 'Triggers on bookings / booking_items', 'OK',
           coalesce((select string_agg(tgrelid::regclass || '.' || tgname, ', ') from pg_trigger
                     where tgrelid in (to_regclass('public.bookings'), to_regclass('public.booking_items')) and not tgisinternal), 'none')
    union all
    select 'Users without email (cannot be linked)',
           case when exists (select 1 from public.users u join public.roles r on r.role_id = u.role_id
                             where r.role_name in ('Agent', 'Rider', 'Driver') and coalesce(btrim(u.email), '') = '') then 'WARN' else 'OK' end,
           coalesce((select string_agg(u.username, ', ') from public.users u join public.roles r on r.role_id = u.role_id
                     where r.role_name in ('Agent', 'Rider', 'Driver') and coalesce(btrim(u.email), '') = ''), 'none')
    union all
    select 'Duplicate emails among users',
           case when exists (select lower(email) from public.users where coalesce(btrim(email), '') <> ''
                             group by lower(email) having count(*) > 1) then 'WARN' else 'OK' end,
           'each mobile sign-in is matched to exactly one TRIMAX account by email'
    union all
    select 'PR #8 objects (profiles, delivery_bookings)',
           case when to_regclass('public.delivery_bookings') is not null or to_regclass('public.profiles') is not null then 'WARN' else 'OK' end,
           'if present they are superseded; check they are empty before dropping them separately'
    union all
    select 'Already migrated', case when to_regnamespace('trimax_private') is not null then 'WARN' else 'OK' end,
           'trimax_private exists = the migration ran before (re-running is safe)'
)
select check_name, result, detail from checks;
