-- Schema fingerprints, read-only. One line per table part (columns, constraints, indexes, RLS + API grants)
-- and per object kind (functions with their grants, triggers, trimax storage policies, buckets).
-- No data, no secrets. Run it on production (Supabase SQL editor) and on a database rebuilt from the
-- repository (sql/tests/rebuild_check.sh): equal lines mean the same schema. Line endings inside function
-- bodies are ignored (production's older bodies were saved with Windows line endings).
with c as (
    select table_schema || '.' || table_name as t, 'col' as k,
           column_name || ' ' || data_type || ' ' || is_nullable || ' ' || coalesce(column_default, '-') || ' '
           || coalesce(character_maximum_length::text, '') || ' ' || coalesce(numeric_precision::text, '') || ','
           || coalesce(numeric_scale::text, '') || ' ' || is_identity as line
      from information_schema.columns where table_schema in ('public', 'trimax_private')
), k as (
    select n.nspname || '.' || t.relname, 'con', co.conname || ' ' || pg_get_constraintdef(co.oid)
      from pg_constraint co join pg_class t on t.oid = co.conrelid join pg_namespace n on n.oid = t.relnamespace
     where n.nspname in ('public', 'trimax_private')
), i as (
    select schemaname || '.' || tablename, 'idx', indexname || ' ' || regexp_replace(indexdef, '^.* USING ', '')
      from pg_indexes where schemaname in ('public', 'trimax_private')
), r as (
    select n.nspname || '.' || c.relname, 'rls', c.relrowsecurity || ' ' || has_table_privilege('anon', c.oid, 'select')
           || has_table_privilege('authenticated', c.oid, 'select,insert,update,delete')
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname in ('public', 'trimax_private') and c.relkind = 'r'
), f as (
    select 'functions', 'fn', n.nspname || '.' || p.proname || '(' || pg_get_function_arguments(p.oid) || ') '
           || md5(replace(p.prosrc, E'\r', '') || '|' || p.prosecdef || '|' || coalesce(array_to_string(p.proconfig, ';'), '')
                  || '|' || pg_get_function_result(p.oid)) || ' '
           || has_function_privilege('anon', p.oid, 'execute') || has_function_privilege('authenticated', p.oid, 'execute')
           || has_function_privilege('service_role', p.oid, 'execute')
      from pg_proc p join pg_namespace n on n.oid = p.pronamespace
     where n.nspname in ('public', 'trimax_private') and p.prokind = 'f'
), tg as (
    select 'triggers', 'trg', c.relname || '.' || tg.tgname || ' ' || md5(pg_get_triggerdef(tg.oid))
      from pg_trigger tg join pg_class c on c.oid = tg.tgrelid join pg_namespace n on n.oid = c.relnamespace
     where not tg.tgisinternal and n.nspname in ('public', 'trimax_private')
), pol as (
    select 'storage', 'pol', policyname || ' ' || md5(coalesce(qual, '') || '|' || coalesce(with_check, '') || '|' || cmd
           || '|' || array_to_string(roles, ','))
      from pg_policies where policyname like 'trimax%'
), b as (
    select 'storage', 'bucket', id || ' ' || public || ' ' || coalesce(file_size_limit::text, '-') || ' '
           || coalesce(array_to_string(allowed_mime_types, ','), '-')
      from storage.buckets
), a as (
    select * from c union all select * from k union all select * from i union all select * from r
    union all select * from f union all select * from tg union all select * from pol union all select * from b
)
select t || ' ' || k || ' ' || count(*) || ' ' || md5(string_agg(line, E'\n' order by line collate "C")) as fingerprint
  from a group by t, k order by t collate "C", k;
