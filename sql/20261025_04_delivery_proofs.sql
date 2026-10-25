-- =====================================================================================================
-- Delivery proof photos from the Android driver app reach the server.
--
-- Today the drop-off photo stays on the driver's phone. This adds:
--
--   * a private Storage bucket "delivery-proofs" (JPEG only, 5 MB max). A signed-in phone may upload
--     and read only inside its own folder "<auth uid>/..." (same rule as "duty-photos");
--   * public.delivery_proofs: one row per proof photo (booking, driver, storage path, recipient,
--     location, when it was taken). RLS on with no policies: phones never read it directly; the
--     Desktop reads it as the database owner;
--   * public.trimax_driver_attach_proof(...): the only way a phone records a proof. The server checks:
--       - signed-in, linked, Active DRIVER; the booking is assigned to that driver and has been started
--         (STARTED or an older in-between step), delivered or failed - not a Pending/Confirmed one;
--       - the photo path is in the caller's own folder and the file really is in the bucket (uploaded
--         by this user in the last 24 hours, not empty);
--       - recipient <= 100 characters, location both-or-neither and in range, taken-at not in the future;
--       - at most 5 proof photos per booking;
--       - a retry with the same p_client_request_id (or the same photo) returns the first result.
--
-- The phone sends the photo AFTER the delivery is marked DELIVERED (the photo never blocks completing
-- a delivery) and keeps it queued until this function accepts it, so this can be applied at any time.
--
-- Desktop: nothing breaks. Showing the photos in the Desktop needs a way to read the private bucket
-- (decided in a later stage: no storage key is put in any client code).
--
-- ORDER: after 20261025_01, _02, _03. Re-runnable. Creates 1 table, 1 bucket, 2 storage policies,
-- 2 functions. Changes no existing rows.
-- APPLIED to production on 2026-10-25 with the owner's approval (migration 20261025_04_delivery_proofs).
-- Verified after applying: function fingerprints match this file; bucket private, JPEG, 5 MB; 2 policies.
-- =====================================================================================================

begin;

-- ----------------------------------------------------------------------------------------------- 1
create table if not exists public.delivery_proofs (
    proof_id          bigint generated always as identity primary key,
    booking_id        integer not null references public.bookings (booking_id) on delete cascade,
    driver_username   text references public.users (username) on update cascade on delete set null,
    photo_path        text not null unique,
    recipient_name    text check (char_length(recipient_name) <= 100),
    latitude          double precision check (latitude between -90 and 90),
    longitude         double precision check (longitude between -180 and 180),
    taken_at          timestamptz,
    created_at        timestamptz not null default now(),
    client_request_id uuid unique,
    constraint delivery_proofs_point_chk check ((latitude is null) = (longitude is null))
);
create index if not exists delivery_proofs_booking_idx on public.delivery_proofs (booking_id, created_at desc);

alter table public.delivery_proofs enable row level security;
revoke all on table public.delivery_proofs from public, anon, authenticated;

-- ----------------------------------------------------------------------------------------------- 2
-- True when p_path is in p_bucket, owned by p_auth_uid, not empty and recent. Outside Supabase (no
-- storage schema, e.g. the local test database) there is nothing to check, so it answers true.
create or replace function trimax_private.storage_object_uploaded(p_bucket text, p_path text, p_auth_uid uuid,
                                                                  p_max_age interval)
 returns boolean
 language plpgsql
 stable
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_owner_expr text;
    v_ok boolean;
begin
    if to_regclass('storage.objects') is null then
        return true;
    end if;
    select case when bool_or(column_name = 'owner_id') then 'coalesce(o.owner_id, o.owner::text)' else 'o.owner::text' end
      into v_owner_expr
      from information_schema.columns
     where table_schema = 'storage' and table_name = 'objects' and column_name in ('owner', 'owner_id');
    execute 'select exists (select 1 from storage.objects o where o.bucket_id = $1 and o.name = $2 '
         || 'and ' || v_owner_expr || ' = $3 and o.created_at > now() - $4 '
         || 'and coalesce((o.metadata ->> ''size'')::bigint, 1) > 0)'
       into v_ok using p_bucket, p_path, p_auth_uid::text, p_max_age;
    return coalesce(v_ok, false);
end $function$;

revoke all on function trimax_private.storage_object_uploaded(text, text, uuid, interval) from public;

-- ----------------------------------------------------------------------------------------------- 3
create or replace function public.trimax_driver_attach_proof(
    p_booking_id integer,
    p_photo_path text,
    p_recipient_name text default null,
    p_latitude double precision default null,
    p_longitude double precision default null,
    p_taken_at timestamptz default null,
    p_client_request_id uuid default null)
 returns jsonb
 language plpgsql
 security definer
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_me text := trimax_private.require_account('DRIVER');
    v_uid uuid := auth.uid();
    v_path text := btrim(coalesce(p_photo_path, ''));
    v_recipient text := nullif(btrim(p_recipient_name), '');
    v_existing record;
    v_status text;
    v_delivery text;
    v_count int;
    v_id bigint;
begin
    -- Retry of a proof that was already recorded: answer with it.
    if p_client_request_id is not null then
        select p.proof_id, p.booking_id, p.driver_username into v_existing
          from public.delivery_proofs p where p.client_request_id = p_client_request_id;
        if found then
            if v_existing.driver_username is distinct from v_me or v_existing.booking_id <> p_booking_id then
                raise exception using errcode = 'TM409', message = 'This request id has already been used.';
            end if;
            return jsonb_build_object('proof_id', v_existing.proof_id, 'booking_id', v_existing.booking_id,
                                      'already_recorded', true);
        end if;
    end if;

    -- Only the driver the booking is assigned to; the row lock serialises proofs for one booking.
    select b.status, coalesce(b.delivery_status, 'ASSIGNED') into v_status, v_delivery
      from public.bookings b
     where b.booking_id = p_booking_id and b.driver_username = v_me
       for update;
    if v_status is null then
        raise exception using errcode = 'TM404', message = 'This delivery is not assigned to you.';
    end if;
    if v_delivery not in ('STARTED', 'PICKED_UP', 'ON_THE_WAY', 'ARRIVED', 'DELIVERED', 'FAILED') then
        raise exception using errcode = 'TM409', message = 'Start the delivery before adding a proof photo.';
    end if;

    if v_uid is null or v_path !~ ('^' || v_uid::text || '/[A-Za-z0-9_.-]{1,120}\.jpg$') then
        raise exception using errcode = 'TM422', message = 'Invalid photo.';
    end if;
    if char_length(coalesce(v_recipient, '')) > 100 then
        raise exception using errcode = 'TM422', message = 'The recipient name is too long.';
    end if;
    if (p_latitude is null) <> (p_longitude is null)
       or (p_latitude is not null and not (p_latitude between -90 and 90 and p_longitude between -180 and 180))
       or (p_latitude = 0 and p_longitude = 0) then
        raise exception using errcode = 'TM422', message = 'Invalid location.';
    end if;
    if p_taken_at > now() + interval '10 minutes' then
        raise exception using errcode = 'TM422', message = 'The photo time is in the future. Check the phone clock.';
    end if;

    -- Same photo sent again under another request id: answer with the first row.
    select p.proof_id, p.booking_id into v_existing from public.delivery_proofs p where p.photo_path = v_path;
    if found then
        if v_existing.booking_id <> p_booking_id then
            raise exception using errcode = 'TM409', message = 'This photo is already attached to another booking.';
        end if;
        return jsonb_build_object('proof_id', v_existing.proof_id, 'booking_id', p_booking_id, 'already_recorded', true);
    end if;

    if not trimax_private.storage_object_uploaded('delivery-proofs', v_path, v_uid, interval '24 hours') then
        raise exception using errcode = 'TM422', message = 'The photo has not been uploaded yet.';
    end if;

    select count(*) into v_count from public.delivery_proofs p where p.booking_id = p_booking_id;
    if v_count >= 5 then
        raise exception using errcode = 'TM409', message = 'This delivery already has 5 proof photos.';
    end if;

    insert into public.delivery_proofs (booking_id, driver_username, photo_path, recipient_name,
                                        latitude, longitude, taken_at, client_request_id)
    values (p_booking_id, v_me, v_path, v_recipient, p_latitude, p_longitude, p_taken_at, p_client_request_id)
    returning proof_id into v_id;

    perform trimax_private.audit(v_me, 'booking.proof_photo', p_booking_id::text,
                                 left(v_path || coalesce(' (received by ' || v_recipient || ')', ''), 300));

    return jsonb_build_object('proof_id', v_id, 'booking_id', p_booking_id, 'already_recorded', false);
end $function$;

revoke all on function public.trimax_driver_attach_proof(integer, text, text, double precision, double precision,
                                                         timestamptz, uuid) from public, anon, service_role;
grant execute on function public.trimax_driver_attach_proof(integer, text, text, double precision, double precision,
                                                            timestamptz, uuid) to authenticated;

-- ----------------------------------------------------------------------------------------------- 4
do $$
begin
    if to_regclass('storage.buckets') is null then
        raise notice 'storage schema not present (not Supabase): skipping the delivery-proofs bucket';
        return;
    end if;
    insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
    values ('delivery-proofs', 'delivery-proofs', false, 5242880, array['image/jpeg'])
    on conflict (id) do nothing;

    if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects'
                   and policyname = 'trimax delivery proof upload own folder') then
        create policy "trimax delivery proof upload own folder" on storage.objects
            for insert to authenticated
            with check (bucket_id = 'delivery-proofs' and (storage.foldername(name))[1] = (select (auth.uid())::text));
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects'
                   and policyname = 'trimax delivery proof read own folder') then
        create policy "trimax delivery proof read own folder" on storage.objects
            for select to authenticated
            using (bucket_id = 'delivery-proofs' and (storage.foldername(name))[1] = (select (auth.uid())::text));
    end if;
end $$;

commit;
