-- TEST ONLY. Never run against the real Supabase project.
-- Emulates the parts of Supabase that the mobile integration relies on, on a plain PostgreSQL
-- cluster: the API roles, auth.users, auth.uid(), and Supabase's default privileges in "public".
-- Run as a superuser on an empty, disposable database.

-- Supabase API roles (PostgREST switches to these per request; they cannot log in).
create role anon nologin noinherit;
create role authenticated nologin noinherit;
create role service_role nologin noinherit bypassrls;

-- Stand-in for Supabase's "postgres" role: owns the application tables and is what the desktop
-- connects as. Deliberately NOT superuser and NOT bypassrls, the strictest assumption.
create role app_owner login nosuperuser nobypassrls nocreaterole password 'test-only';
grant create, usage on schema public to app_owner;
do $$ begin execute format('grant create on database %I to app_owner', current_database()); end $$;

-- auth schema
create schema auth;
create table auth.users (
    id uuid primary key default gen_random_uuid(),
    email text unique,
    email_confirmed_at timestamptz,
    raw_app_meta_data jsonb default '{}'::jsonb,   -- app_metadata: only the service role can set it
    invited_at timestamptz,
    created_at timestamptz not null default now()
);

-- Same definition as Supabase: the subject of the request's JWT.
create function auth.uid() returns uuid
language sql stable
as $$
    select nullif(coalesce(
        nullif(current_setting('request.jwt.claim.sub', true), ''),
        (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')
    ), '')::uuid
$$;

grant usage on schema auth to anon, authenticated, service_role, app_owner;
grant execute on function auth.uid() to public;
grant select, references on auth.users to app_owner;

-- Supabase's default privileges: everything app_owner creates in public is granted to the API roles.
grant usage on schema public to anon, authenticated, service_role;
alter default privileges for role app_owner in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges for role app_owner in schema public grant all on sequences to anon, authenticated, service_role;
alter default privileges for role app_owner in schema public grant all on functions to anon, authenticated, service_role;

-- Supabase Storage: the tables and helper the duty-photo policies use. Supabase lets "postgres" create
-- policies on storage.objects; here app_owner (the "postgres" stand-in) owns the tables for that reason.
create schema storage;
create table storage.buckets (
    id text primary key,
    name text not null,
    public boolean default false,
    file_size_limit bigint,
    allowed_mime_types text[],
    created_at timestamptz default now()
);
create table storage.objects (
    id uuid primary key default gen_random_uuid(),
    bucket_id text references storage.buckets(id),
    name text not null,
    owner uuid,
    owner_id text,
    metadata jsonb,
    created_at timestamptz not null default now(),
    unique (bucket_id, name)
);
create function storage.foldername(name text) returns text[]
language plpgsql immutable as $$
declare _parts text[];
begin
    select string_to_array(name, '/') into _parts;
    return _parts[1:array_length(_parts, 1) - 1];
end $$;
alter table storage.objects enable row level security;
grant usage on schema storage to anon, authenticated, service_role, app_owner;
grant all on storage.objects, storage.buckets to anon, authenticated, service_role;
grant execute on function storage.foldername(text) to public;
alter table storage.objects owner to app_owner;
alter table storage.buckets owner to app_owner;
