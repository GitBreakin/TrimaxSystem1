-- =====================================================================================================
-- How the agent took the order: VISIT (went to the customer: a photo is required) or TEXT_CALL.
--
-- The agent app asks on New Booking: "Visit" or "Text / Call". A visit needs a photo taken there; the
-- admin sees the choice and the photo in the Desktop booking window.
--
-- This adds:
--   * public.bookings.order_via ('VISIT' / 'TEXT_CALL'; null = not recorded: made on the Desktop, or by an
--     older app), visit_photo_path, visit_latitude / visit_longitude (where the photo was taken, if the
--     phone had a location), visit_taken_at. A check keeps them consistent (a VISIT always has its photo,
--     a TEXT_CALL never has one) and a photo belongs to one booking only.
--   * The photo goes into the existing private bucket "customer-photos", in the agent's own folder
--     ("<auth uid>/visit_....jpg"); the phone can already upload there and the admin can already view
--     that bucket (trimax_private.issue_media_ticket + the trimax-admin-media Edge Function). No new
--     bucket, no storage policy and no Edge Function change.
--   * public.trimax_agent_create_booking_v2(...): the same booking as trimax_agent_create_booking (it is
--     called inside, unchanged) plus p_order_via and the visit photo, in one transaction. The server
--     checks: VISIT or TEXT_CALL chosen; for a visit the photo is in the caller's own folder, really
--     uploaded (within 1 day), not used for another booking, location both-or-neither and in range,
--     taken-at not in the future; TEXT_CALL has no photo. A retry with the same request id returns the
--     booking already saved. Writes audit "booking.order_via".
--     A new name (not an overload): the older app keeps calling trimax_agent_create_booking with the
--     same arguments, and PostgREST would not know which of two same-named functions to use.
--
-- Older apps: unchanged. Their bookings have order_via = null ("not recorded" on the Desktop).
-- Desktop: an older Desktop never reads the new columns; the new one reads them through to_jsonb, so it
-- also works before this update.
--
-- ORDER: after 20261028_07 (the create booking it calls). Re-runnable. Adds 5 nullable columns, 1 check,
-- 1 unique index, 1 function. Changes no rows. Rollback: 20261029_98 (refused once bookings use it).
-- =====================================================================================================

begin;

-- ----------------------------------------------------------------------------------------------- 1
alter table public.bookings add column if not exists order_via text;
alter table public.bookings add column if not exists visit_photo_path text;
alter table public.bookings add column if not exists visit_latitude double precision;
alter table public.bookings add column if not exists visit_longitude double precision;
alter table public.bookings add column if not exists visit_taken_at timestamptz;

do $$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.bookings'::regclass
                   and conname = 'bookings_order_via_chk') then
        alter table public.bookings add constraint bookings_order_via_chk check (
            (order_via is null or order_via in ('VISIT', 'TEXT_CALL'))
            and (case when order_via = 'VISIT' then visit_photo_path is not null
                      else visit_photo_path is null and visit_latitude is null and visit_longitude is null
                           and visit_taken_at is null end)
            and ((visit_latitude is null) = (visit_longitude is null))
            and (visit_latitude is null or (visit_latitude between -90 and 90 and visit_longitude between -180 and 180))
            and (visit_photo_path is null or visit_photo_path
                 ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/visit_[A-Za-z0-9_-]{1,80}\.jpg$'));
    end if;
end $$;

create unique index if not exists bookings_visit_photo_key
    on public.bookings (visit_photo_path) where visit_photo_path is not null;

-- ----------------------------------------------------------------------------------------------- 2
create or replace function public.trimax_agent_create_booking_v2(
    p_request_id uuid, p_customer_name text, p_customer_contact text, p_delivery_address text,
    p_delivery_latitude double precision, p_delivery_longitude double precision, p_items jsonb,
    p_order_via text,
    p_requested_schedule text default null, p_special_instructions text default null,
    p_visit_photo_path text default null, p_visit_latitude double precision default null,
    p_visit_longitude double precision default null, p_visit_taken_at timestamptz default null)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    v_me text := trimax_private.require_account('AGENT');
    v_uid uuid := auth.uid();
    v_via text := upper(btrim(coalesce(p_order_via, '')));
    v_path text := nullif(btrim(p_visit_photo_path), '');
    v_result jsonb;
    v_booking_id int;
    v_used_by int;
begin
    -- ---- the choice and the photo's details (cheap checks, before anything is saved)
    if v_via not in ('VISIT', 'TEXT_CALL') then
        raise exception using errcode = 'TM422', detail = 'order_via_required',
            message = 'Choose how you took this order: Visit or Text / Call.';
    end if;
    if v_via = 'VISIT' then
        if v_path is null then
            raise exception using errcode = 'TM422', detail = 'visit_photo_required',
                message = 'Take a photo of the visit before confirming the booking.';
        end if;
        if v_uid is null or v_path !~ ('^' || v_uid::text || '/visit_[A-Za-z0-9_-]{1,80}\.jpg$') then
            raise exception using errcode = 'TM422', message = 'Invalid visit photo.';
        end if;
        if (p_visit_latitude is null) <> (p_visit_longitude is null)
           or (p_visit_latitude is not null and not (p_visit_latitude between -90 and 90
                                                     and p_visit_longitude between -180 and 180))
           or (p_visit_latitude = 0 and p_visit_longitude = 0) then
            raise exception using errcode = 'TM422', message = 'Invalid location for the visit photo.';
        end if;
        if p_visit_taken_at > now() + interval '10 minutes' then
            raise exception using errcode = 'TM422',
                message = 'The visit photo time is in the future. Check the phone clock.';
        end if;
    elsif v_path is not null or p_visit_latitude is not null or p_visit_longitude is not null
          or p_visit_taken_at is not null then
        raise exception using errcode = 'TM422', message = 'A visit photo is only sent with Visit.';
    end if;

    -- ---- the booking itself: every rule of trimax_agent_create_booking, unchanged (also its retry answer)
    v_result := public.trimax_agent_create_booking(p_request_id, p_customer_name, p_customer_contact,
        p_delivery_address, p_delivery_latitude, p_delivery_longitude, p_items, p_requested_schedule,
        p_special_instructions);
    v_booking_id := (v_result ->> 'booking_id')::int;
    if coalesce((v_result ->> 'already_existed')::boolean, false) then
        return v_result || jsonb_build_object('order_via',
            (select b.order_via from public.bookings b where b.booking_id = v_booking_id));
    end if;

    -- ---- the visit photo really is on the server and belongs to no other booking
    if v_via = 'VISIT' then
        select b.booking_id into v_used_by from public.bookings b where b.visit_photo_path = v_path;
        if v_used_by is not null then
            raise exception using errcode = 'TM409',
                message = 'This photo is already used for booking #' || v_used_by || '. Take a new photo.';
        end if;
        if not trimax_private.storage_object_uploaded('customer-photos', v_path, v_uid, interval '1 day') then
            raise exception using errcode = 'TM422', detail = 'visit_photo_missing',
                message = 'The visit photo has not reached the server yet. Try again.';
        end if;
    end if;

    update public.bookings
       set order_via = v_via,
           visit_photo_path = case when v_via = 'VISIT' then v_path end,
           visit_latitude = case when v_via = 'VISIT' then p_visit_latitude end,
           visit_longitude = case when v_via = 'VISIT' then p_visit_longitude end,
           visit_taken_at = case when v_via = 'VISIT' then coalesce(p_visit_taken_at, now()) end
     where booking_id = v_booking_id;

    perform trimax_private.audit(v_me, 'booking.order_via', v_booking_id::text,
        case when v_via = 'VISIT'
             then left('Visit, photo ' || v_path
                       || case when p_visit_latitude is null then ' (no location)' else '' end, 300)
             else 'Text or call' end);

    return v_result || jsonb_build_object('order_via', v_via);
end $function$;

revoke all on function public.trimax_agent_create_booking_v2(uuid, text, text, text, double precision,
    double precision, jsonb, text, text, text, text, double precision, double precision, timestamptz)
    from public, anon, service_role;
grant execute on function public.trimax_agent_create_booking_v2(uuid, text, text, text, double precision,
    double precision, jsonb, text, text, text, text, double precision, double precision, timestamptz)
    to authenticated;

commit;
