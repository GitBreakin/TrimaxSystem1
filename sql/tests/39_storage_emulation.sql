-- TEST ONLY. A small stand-in for Supabase Storage on the disposable test database, so the bucket and
-- policies in 20261025_04_delivery_proofs.sql are created and their own-folder rules can be tested.
-- Only the parts the migrations and tests touch: storage.buckets, storage.objects (RLS on, owner from
-- the JWT like Supabase), storage.foldername(). Run as superuser, before 20261025_04.
\set ON_ERROR_STOP on
create schema if not exists storage authorization app_owner;
grant usage on schema storage to anon, authenticated, service_role;

create table if not exists storage.buckets (
    id text primary key,
    name text not null,
    public boolean default false,
    file_size_limit bigint,
    allowed_mime_types text[]
);
create table if not exists storage.objects (
    id uuid primary key default gen_random_uuid(),
    bucket_id text references storage.buckets (id),
    name text not null,
    owner uuid default auth.uid(),
    owner_id text default (auth.uid())::text,
    created_at timestamptz default now(),
    metadata jsonb,
    unique (bucket_id, name)
);
alter table storage.buckets owner to app_owner;
alter table storage.objects owner to app_owner;
alter table storage.objects enable row level security;
grant select, insert on storage.objects to authenticated;
grant select on storage.buckets to authenticated;

create or replace function storage.foldername(name text) returns text[]
language sql immutable as $$ select (string_to_array(name, '/'))[1:array_length(string_to_array(name, '/'), 1) - 1] $$;
alter function storage.foldername(text) owner to app_owner;
grant execute on function storage.foldername(text) to public;
