-- =====================================================================================================
-- TRIMAX live baseline (snapshot of the Supabase project "Trimax" on 2026-10-25)
--
-- WHY: these objects run in production but were created in the SQL editor and are in no repository
--      file. Without this file the database cannot be rebuilt or reviewed. Everything here was read
--      from the live database (pg_get_functiondef, pg_constraint, pg_policies, storage.buckets);
--      nothing was invented.
--
-- ORDER: legacy desktop schema -> 20261023_01_mobile_booking_integration.sql -> THIS FILE.
--
-- SAFETY: re-runnable and additive. On the live database every statement is a no-op (same columns,
--         same constraints, byte-identical function bodies, same grants). It changes no data.
--         Do not run against production without approval anyway: it is a baseline for review/rebuild.
--
-- CONTENTS
--   1. GPS columns on agents and vehicles
--   2. Tables: public.user_locations, public.admin_notifications,
--              trimax_private.duty_events, trimax_private.provisioning_requests
--   3. Private helper functions (incl. the live version of delivery_transition_allowed, which differs
--      from 20261023_01: it no longer allows the intermediate PICKED_UP / ON_THE_WAY / ARRIVED steps
--      for new deliveries)
--   4. Public functions: live GPS, duty with photo, mobile account provisioning, and the live version
--      of trimax_driver_update_delivery_status (adds safe retry of an already-recorded step)
--   5. Legacy desktop trigger functions (booking totals, append-only audit log)
--   6. Privileges
--   7. Storage: private bucket "duty-photos" and its two policies (Supabase only)
-- =====================================================================================================

begin;

-- ----------------------------------------------------------------------------------------------- 1
alter table public.agents   add column if not exists longitude numeric;
alter table public.agents   add column if not exists latitude numeric;
alter table public.agents   add column if not exists last_location_at timestamptz;
alter table public.vehicles add column if not exists latitude numeric;
alter table public.vehicles add column if not exists longitude numeric;
alter table public.vehicles add column if not exists last_location_at timestamptz;

do $$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.vehicles'::regclass and conname = 'vehicles_latitude_check') then
        alter table public.vehicles add constraint vehicles_latitude_check
            check (latitude >= ('-90'::integer)::numeric and latitude <= (90)::numeric);
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.vehicles'::regclass and conname = 'vehicles_longitude_check') then
        alter table public.vehicles add constraint vehicles_longitude_check
            check (longitude >= ('-180'::integer)::numeric and longitude <= (180)::numeric);
    end if;
end $$;

-- ----------------------------------------------------------------------------------------------- 2
create table if not exists public.user_locations (
    username     text             not null,
    latitude     double precision not null,
    longitude    double precision not null,
    accuracy_m   real,
    recorded_at  timestamptz      not null default now(),
    speed_mps    double precision,
    bearing_deg  double precision,
    constraint user_locations_pkey primary key (username),
    constraint user_locations_username_fkey foreign key (username) references public.users (username) on delete cascade,
    constraint user_locations_latitude_check check (latitude >= ('-90'::integer)::double precision and latitude <= (90)::double precision),
    constraint user_locations_longitude_check check (longitude >= ('-180'::integer)::double precision and longitude <= (180)::double precision),
    constraint user_locations_accuracy_m_check check (accuracy_m is null or accuracy_m >= (0)::double precision)
);
alter table public.user_locations add column if not exists speed_mps double precision;
alter table public.user_locations add column if not exists bearing_deg double precision;

create table if not exists public.admin_notifications (
    notification_id  bigserial    not null,
    created_at       timestamptz  not null default now(),
    kind             text         not null,
    category         text         not null,
    title            text         not null,
    body             text,
    related_type     text,
    related_id       text,
    read_at          timestamptz,
    read_by          text,
    constraint admin_notifications_pkey primary key (notification_id),
    constraint admin_notifications_kind_check check (kind = any (array['Alert'::text, 'Announcement'::text]))
);
create index if not exists admin_notifications_unread_idx
    on public.admin_notifications using btree (created_at desc) where (read_at is null);

create table if not exists trimax_private.duty_events (
    event_id         bigserial        not null,
    username         text             not null,
    event_type       text             not null,
    photo_path       text             not null,
    latitude         double precision,
    longitude        double precision,
    client_event_id  uuid,
    recorded_at      timestamptz      not null default now(),
    constraint duty_events_pkey primary key (event_id),
    constraint duty_events_username_fkey foreign key (username) references public.users (username) on update cascade on delete cascade,
    constraint duty_events_event_type_check check (event_type = any (array['START'::text, 'END'::text])),
    constraint duty_events_photo_path_check check (char_length(photo_path) >= 10 and char_length(photo_path) <= 300),
    constraint duty_events_photo_path_key unique (photo_path),
    constraint duty_events_client_event_id_key unique (client_event_id),
    constraint duty_events_point_chk check ((latitude is null) = (longitude is null))
);
create index if not exists duty_events_user_idx
    on trimax_private.duty_events using btree (username, recorded_at desc, event_id desc);

create table if not exists trimax_private.provisioning_requests (
    request_id    bigserial    not null,
    username      text         not null,
    email         text         not null,
    status        text         not null default 'pending'::text,
    attempts      integer      not null default 0,
    last_error    text,
    auth_user_id  uuid,
    requested_by  text         not null,
    requested_at  timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    constraint provisioning_requests_pkey primary key (request_id),
    constraint provisioning_requests_username_fkey foreign key (username) references public.users (username) on update cascade on delete cascade,
    constraint provisioning_requests_status_check check (status = any (array['pending'::text, 'processing'::text, 'invited'::text, 'linked'::text, 'failed'::text])),
    constraint provisioning_requests_last_error_check check (char_length(last_error) <= 300)
);
create unique index if not exists provisioning_one_open_request_per_user
    on trimax_private.provisioning_requests using btree (username)
    where (status = any (array['pending'::text, 'processing'::text]));

alter table public.user_locations                 enable row level security;
alter table public.admin_notifications            enable row level security;
alter table trimax_private.duty_events            enable row level security;
alter table trimax_private.provisioning_requests  enable row level security;

-- ----------------------------------------------------------------------------------------------- 3
CREATE OR REPLACE FUNCTION trimax_private.delivery_transition_allowed(p_from text, p_to text)
 RETURNS boolean
 LANGUAGE sql
 IMMUTABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
    select (p_from, p_to) in (
        ('ASSIGNED',   'STARTED'),                              -- Start Delivery
        ('STARTED',    'DELIVERED'), ('STARTED',    'FAILED'),  -- Drop Off / Complete, or report a problem
        -- Only for rows saved by older app builds that still had intermediate steps:
        ('PICKED_UP',  'DELIVERED'), ('PICKED_UP',  'FAILED'),
        ('ON_THE_WAY', 'DELIVERED'), ('ON_THE_WAY', 'FAILED'),
        ('ARRIVED',    'DELIVERED'), ('ARRIVED',    'FAILED'))
$function$;

CREATE OR REPLACE FUNCTION trimax_private.role_code(p_username text)
 RETURNS text
 LANGUAGE sql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
    select case r.role_name when 'Agent' then 'AGENT' when 'Rider' then 'AGENT'
                            when 'Driver' then 'DRIVER' when 'Admin' then 'ADMIN' else 'OTHER' end
      from public.users u left join public.roles r on r.role_id = u.role_id
     where u.username = p_username
$function$;

CREATE OR REPLACE FUNCTION trimax_private.audit_user(p_actor text, p_action text, p_target_username text, p_details text)
 RETURNS void
 LANGUAGE plpgsql
 SET search_path TO 'public', 'pg_temp'
AS $function$
begin
    if to_regclass('public.audit_log') is not null then
        execute 'insert into public.audit_log (actor_username, action, target_type, target_id, result, details) '
             || 'values ($1, $2, ''user'', $3, ''success'', $4)'
          using p_actor, p_action, p_target_username, left(p_details, 500);
    end if;
end $function$;

-- Live version has no fixed search_path (advisor WARN); 20261025_02 fixes that.
CREATE OR REPLACE FUNCTION trimax_private.hub_location()
 RETURNS jsonb
 LANGUAGE sql
 IMMUTABLE
AS $function$ select jsonb_build_object('name', 'TRIMAX Hub', 'latitude', 14.7826996, 'longitude', 121.0475865) $function$;

-- Live version has no fixed search_path (advisor WARN); 20261025_02 fixes that.
CREATE OR REPLACE FUNCTION trimax_private.is_service_request()
 RETURNS boolean
 LANGUAGE sql
 STABLE
AS $function$
    select coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role', '') = 'service_role'
$function$;

CREATE OR REPLACE FUNCTION trimax_private.duty_status_json(p_username text)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
    select jsonb_build_object(
        'on_duty', coalesce(last.event_type = 'START', false),
        'since', case when last.event_type = 'START' then last.recorded_at end,
        'last_event', last.event_type,
        'last_event_at', last.recorded_at)
    from (select 1) one
    left join lateral (
        select e.event_type, e.recorded_at from trimax_private.duty_events e
         where e.username = p_username
         order by e.recorded_at desc, e.event_id desc limit 1) last on true
$function$;

CREATE OR REPLACE FUNCTION trimax_private.duty_photo_uploaded(p_path text, p_auth_uid uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
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
    execute 'select exists (select 1 from storage.objects o where o.bucket_id = ''duty-photos'' and o.name = $1 '
         || 'and ' || v_owner_expr || ' = $2 and o.created_at > now() - interval ''1 hour'' '
         || 'and coalesce((o.metadata ->> ''size'')::bigint, 1) > 0)'
       into v_ok using p_path, p_auth_uid::text;
    return v_ok;
end $function$;

CREATE OR REPLACE FUNCTION trimax_private.provisioning_problem(p_username text, p_email text)
 RETURNS text
 LANGUAGE plpgsql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_email text;
    v_role text;
    v_status text;
begin
    select nullif(btrim(u.email), ''), r.role_name, u.account_status into v_email, v_role, v_status
      from public.users u left join public.roles r on r.role_id = u.role_id
     where u.username = p_username;
    if not found then
        return 'The TRIMAX account no longer exists.';
    end if;
    if v_role is null or v_role not in ('Agent', 'Rider', 'Driver') then
        return 'Only Agent and Driver accounts can use the mobile app.';
    end if;
    if v_status is distinct from 'Active' then
        return 'The account is not Active.';
    end if;
    if v_email is null or v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then
        return 'The account has no valid email address.';
    end if;
    if p_email is not null and lower(v_email) <> lower(btrim(p_email)) then
        return 'The account''s email address changed after the invitation was requested. Request it again.';
    end if;
    if exists (select 1 from public.users o where o.username <> p_username and lower(btrim(o.email)) = lower(v_email)) then
        return 'Another TRIMAX account uses the same email address. Give each account its own email first.';
    end if;
    if exists (select 1 from trimax_private.mobile_accounts m where m.username = p_username) then
        return 'This account already has a mobile sign-in.';
    end if;
    return null;
end $function$;

CREATE OR REPLACE FUNCTION trimax_private.request_mobile_access(p_username text, p_requested_by text DEFAULT NULL::text)
 RETURNS bigint
 LANGUAGE plpgsql
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_username text := btrim(coalesce(p_username, ''));
    v_problem text;
    v_email text;
    v_id bigint;
begin
    perform 1 from public.users u where u.username = v_username for update;
    v_problem := trimax_private.provisioning_problem(v_username, null);
    if v_problem is not null then
        raise exception using errcode = 'TM422', message = v_problem;
    end if;
    select r.request_id into v_id from trimax_private.provisioning_requests r
     where r.username = v_username and r.status in ('pending', 'processing');
    if v_id is not null then
        return v_id;
    end if;
    select lower(btrim(u.email)) into v_email from public.users u where u.username = v_username;
    insert into trimax_private.provisioning_requests (username, email, requested_by)
    values (v_username, v_email, coalesce(nullif(btrim(p_requested_by), ''), current_user))
    returning request_id into v_id;
    perform trimax_private.audit_user(coalesce(nullif(btrim(p_requested_by), ''), current_user),
                                      'account.mobile_invite_requested', v_username, 'Mobile sign-in requested for ' || v_email);
    return v_id;
end $function$;

-- ----------------------------------------------------------------------------------------------- 4
CREATE OR REPLACE FUNCTION public.trimax_driver_update_delivery_status(p_booking_id integer, p_expected_status text, p_new_status text, p_latitude double precision DEFAULT NULL::double precision, p_longitude double precision DEFAULT NULL::double precision, p_note text DEFAULT NULL::text, p_recipient_name text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_me text := trimax_private.require_account('DRIVER');
    v_status text;
    v_from text;
    v_to text := upper(btrim(coalesce(p_new_status, '')));
    v_expected text := upper(btrim(coalesce(p_expected_status, '')));
    v_note text := nullif(btrim(p_note), '');
    v_recipient text := nullif(btrim(p_recipient_name), '');
    v_new_booking_status text;
    v_event_note text;
    v_last record;
begin
    -- Only the driver the booking is assigned to; the row lock makes concurrent calls wait their turn.
    select b.status, coalesce(b.delivery_status, 'ASSIGNED')
      into v_status, v_from
      from public.bookings b
     where b.booking_id = p_booking_id and b.driver_username = v_me
       for update;
    if v_status is null then
        raise exception using errcode = 'TM404', message = 'This delivery is not assigned to you.';
    end if;

    -- A retry of a step that already happened (double tap, or the phone lost the reply): answer with the
    -- saved booking instead of an error, but only if this driver's latest step on it is exactly that one.
    if v_from = v_to and v_expected <> v_from then
        select e.from_status, e.to_status, e.actor_username
          into v_last
          from trimax_private.delivery_events e
         where e.booking_id = p_booking_id and e.event_type = 'delivery_status'
         order by e.occurred_at desc, e.event_id desc
         limit 1;
        if found and v_last.from_status = v_expected and v_last.to_status = v_to and v_last.actor_username = v_me then
            return trimax_private.booking_json(p_booking_id, true) || jsonb_build_object('already_recorded', true);
        end if;
    end if;

    if v_status in ('Completed', 'Cancelled') then
        raise exception using errcode = 'TM409', message = 'Booking #' || p_booking_id || ' is already ' || v_status || '.';
    end if;
    if v_expected <> v_from then
        raise exception using errcode = 'TM409', detail = v_from,
            message = 'This delivery changed since you opened it (it is now ' || v_from || '). Refresh and try again.';
    end if;
    if not trimax_private.delivery_transition_allowed(v_from, v_to) then
        raise exception using errcode = 'TM422', message = 'A delivery cannot go from ' || v_from || ' to ' || v_to || '.';
    end if;
    if (p_latitude is null) <> (p_longitude is null)
       or (p_latitude is not null and not (p_latitude between -90 and 90 and p_longitude between -180 and 180)) then
        raise exception using errcode = 'TM422', message = 'Invalid location.';
    end if;
    if char_length(coalesce(v_note, '')) > 500 or char_length(coalesce(v_recipient, '')) > 100 then
        raise exception using errcode = 'TM422', message = 'The note or the recipient name is too long.';
    end if;

    v_new_booking_status := v_status;
    if v_from = 'ASSIGNED' then
        -- Starting needs the administrator's approval.
        if v_status = 'Pending' then
            raise exception using errcode = 'TM409',
                message = 'Booking #' || p_booking_id || ' is waiting for the administrator to confirm it. You can start once it is Confirmed.';
        end if;
        if v_status = 'Confirmed' then
            v_new_booking_status := 'In Progress';
        end if;
    end if;
    if v_to = 'DELIVERED' then
        if v_recipient is null then
            raise exception using errcode = 'TM422', message = 'Enter the name of the person who received the delivery.';
        end if;
        v_new_booking_status := 'Completed';
    elsif v_to = 'FAILED' then
        if v_note is null then
            raise exception using errcode = 'TM422', message = 'Enter the reason the delivery failed.';
        end if;
    end if;

    update public.bookings
       set delivery_status = v_to,
           delivery_status_at = now(),
           status = v_new_booking_status
     where booking_id = p_booking_id;

    v_event_note := concat_ws(' | ', case when v_recipient is not null then 'Received by ' || v_recipient end, v_note);
    insert into trimax_private.delivery_events (booking_id, actor_username, source, event_type, from_status, to_status,
                                                booking_status, latitude, longitude, note)
    values (p_booking_id, v_me, 'mobile', 'delivery_status', v_from, v_to, v_new_booking_status,
            p_latitude, p_longitude, nullif(v_event_note, ''));

    perform trimax_private.audit(v_me, 'booking.delivery_status', p_booking_id::text,
        v_from || ' -> ' || v_to || coalesce(' (' || nullif(v_event_note, '') || ')', ''));
    if v_new_booking_status <> v_status then
        perform trimax_private.audit(v_me, 'booking.status_change', p_booking_id::text,
            v_status || ' -> ' || v_new_booking_status || ' (driver ' || lower(replace(v_to, '_', ' ')) || ')');
    end if;

    return trimax_private.booking_json(p_booking_id, true);
end $function$;

CREATE OR REPLACE FUNCTION public.trimax_update_location(p_latitude double precision, p_longitude double precision, p_accuracy double precision DEFAULT NULL::double precision, p_speed double precision DEFAULT NULL::double precision, p_bearing double precision DEFAULT NULL::double precision, p_is_on_duty boolean DEFAULT true)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_me text := trimax_private.require_account(null);
    v_role text;
    v_last timestamptz;
    v_accuracy double precision := p_accuracy;
    v_speed double precision := p_speed;
    v_bearing double precision := p_bearing;
    v_vehicles int := 0;
begin
    if p_is_on_duty is distinct from true then
        raise exception using errcode = 'TM422', message = 'Your location is only shared while you are on duty.';
    end if;
    -- "between" is false for NULL, NaN and infinity, so this also rejects those.
    if not (p_latitude between -90 and 90) or not (p_longitude between -180 and 180)
       or (p_latitude = 0 and p_longitude = 0) then
        raise exception using errcode = 'TM422', message = 'Invalid GPS position.';
    end if;
    if v_accuracy is not null and not (v_accuracy between 0 and 10000) then
        if v_accuracy > 10000 and v_accuracy < 'infinity'::float8 then
            raise exception using errcode = 'TM422', message = 'The GPS position is too inaccurate to share.';
        end if;
        v_accuracy := null;
    end if;
    if v_speed is not null and not (v_speed between 0 and 100) then v_speed := null; end if;
    if v_bearing is not null and not (v_bearing between 0 and 360) then v_bearing := null; end if;

    select max(l.recorded_at) into v_last from public.user_locations l where l.username = v_me;
    if v_last is not null and v_last > now() - interval '2 seconds' then
        return jsonb_build_object('stored', false, 'throttled', true, 'recorded_at', v_last);
    end if;

    update public.user_locations
       set latitude = p_latitude, longitude = p_longitude, accuracy_m = v_accuracy,
           speed_mps = v_speed, bearing_deg = v_bearing, recorded_at = now()
     where username = v_me;
    if not found then
        insert into public.user_locations (username, latitude, longitude, accuracy_m, speed_mps, bearing_deg, recorded_at)
        values (v_me, p_latitude, p_longitude, v_accuracy, v_speed, v_bearing, now());
    end if;

    v_role := trimax_private.role_code(v_me);
    if v_role = 'DRIVER' then
        update public.vehicles
           set latitude = p_latitude, longitude = p_longitude, last_location_at = now()
         where username = v_me;
        get diagnostics v_vehicles = row_count;
    end if;

    return jsonb_build_object('stored', true, 'throttled', false, 'recorded_at', now(), 'vehicles_updated', v_vehicles);
end $function$;

CREATE OR REPLACE FUNCTION public.trimax_my_duty_status()
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
begin
    return trimax_private.duty_status_json(trimax_private.require_account(null));
end $function$;

CREATE OR REPLACE FUNCTION public.trimax_record_duty_event(p_event text, p_photo_path text, p_latitude double precision DEFAULT NULL::double precision, p_longitude double precision DEFAULT NULL::double precision, p_client_event_id uuid DEFAULT NULL::uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_me text := trimax_private.require_account(null);
    v_uid uuid := auth.uid();
    v_event text := upper(btrim(coalesce(p_event, '')));
    v_path text := btrim(coalesce(p_photo_path, ''));
    v_last text;
    v_existing_user text;
    v_at timestamptz;
begin
    if v_event not in ('START', 'END') then
        raise exception using errcode = 'TM422', message = 'Unknown duty event.';
    end if;

    -- A retry of a request that was already saved returns the saved state.
    if p_client_event_id is not null then
        select e.username into v_existing_user from trimax_private.duty_events e where e.client_event_id = p_client_event_id;
        if v_existing_user is not null then
            if v_existing_user <> v_me then
                raise exception using errcode = 'TM409', message = 'This request id has already been used.';
            end if;
            return trimax_private.duty_status_json(v_me) || jsonb_build_object('already_recorded', true);
        end if;
    end if;

    if (p_latitude is null) <> (p_longitude is null)
       or (p_latitude is not null and not (p_latitude between -90 and 90 and p_longitude between -180 and 180)) then
        raise exception using errcode = 'TM422', message = 'Invalid location.';
    end if;
    if v_path !~ ('^' || v_uid::text || '/[A-Za-z0-9_.-]{1,120}\.jpg$') then
        raise exception using errcode = 'TM422', message = 'Invalid duty photo reference.';
    end if;
    if not trimax_private.duty_photo_uploaded(v_path, v_uid) then
        raise exception using errcode = 'TM422', message = 'The duty photo has not been uploaded. Take the photo again.';
    end if;
    if exists (select 1 from trimax_private.duty_events e where e.photo_path = v_path) then
        raise exception using errcode = 'TM409', message = 'This photo has already been used for a duty event. Take a new photo.';
    end if;

    -- One duty change at a time per person.
    perform pg_advisory_xact_lock(hashtextextended('trimax-duty:' || v_me, 0));
    select e.event_type into v_last from trimax_private.duty_events e
     where e.username = v_me order by e.recorded_at desc, e.event_id desc limit 1;
    if v_event = 'START' and v_last = 'START' then
        raise exception using errcode = 'TM409', message = 'You are already on duty.';
    end if;
    if v_event = 'END' and coalesce(v_last, 'END') = 'END' then
        raise exception using errcode = 'TM409', message = 'You are not on duty.';
    end if;
    if v_event = 'END' and exists (
            select 1 from public.bookings b
             where b.driver_username = v_me and b.status not in ('Completed', 'Cancelled')
               and b.delivery_status in ('STARTED', 'PICKED_UP', 'ON_THE_WAY', 'ARRIVED')) then
        raise exception using errcode = 'TM409',
            message = 'Finish your delivery (or mark it failed) before ending duty.';
    end if;

    insert into trimax_private.duty_events (username, event_type, photo_path, latitude, longitude, client_event_id)
    values (v_me, v_event, v_path, p_latitude, p_longitude, p_client_event_id)
    returning recorded_at into v_at;
    perform trimax_private.audit_user(v_me, case v_event when 'START' then 'duty.start' else 'duty.end' end, v_me,
                                      'Duty ' || lower(v_event) || ' with photo ' || v_path);

    return trimax_private.duty_status_json(v_me) || jsonb_build_object('already_recorded', false, 'recorded_at', v_at);
end $function$;

CREATE OR REPLACE FUNCTION public.trimax_service_claim_provisioning(p_limit integer DEFAULT 10)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_out jsonb := '[]'::jsonb;
    r record;
    v_problem text;
    v_auth_id uuid;
    v_confirmed boolean;
    v_meta jsonb;
    v_auth_count int;
    v_linked text;
begin
    if not trimax_private.is_service_request() then
        raise exception using errcode = 'TM403', message = 'Not allowed.';
    end if;
    for r in
        select pr.request_id, pr.username, pr.email
          from trimax_private.provisioning_requests pr
         where pr.status = 'pending'
            or (pr.status = 'processing' and pr.updated_at < now() - interval '10 minutes')
         order by pr.request_id
         limit greatest(1, least(coalesce(p_limit, 10), 25))
           for update skip locked
    loop
        v_problem := trimax_private.provisioning_problem(r.username, r.email);
        if v_problem is null and (select attempts from trimax_private.provisioning_requests where request_id = r.request_id) >= 5 then
            v_problem := 'Gave up after 5 attempts. Check the Edge Function logs, then request the invitation again.';
        end if;
        if v_problem is not null then
            update trimax_private.provisioning_requests
               set status = 'failed', last_error = v_problem, updated_at = now()
             where request_id = r.request_id;
            continue;
        end if;

        select count(*), min(a.id::text)::uuid into v_auth_count, v_auth_id
          from auth.users a where lower(a.email) = lower(r.email);
        if v_auth_count > 1 then
            update trimax_private.provisioning_requests
               set status = 'failed', updated_at = now(),
                   last_error = 'More than one Supabase sign-in uses this email. Remove the extra one in Supabase first.'
             where request_id = r.request_id;
            continue;
        end if;
        v_confirmed := null; v_meta := null; v_linked := null;
        if v_auth_id is not null then
            select a.email_confirmed_at is not null, coalesce(to_jsonb(a) -> 'raw_app_meta_data', '{}'::jsonb)
              into v_confirmed, v_meta from auth.users a where a.id = v_auth_id;
            select m.username into v_linked from trimax_private.mobile_accounts m where m.auth_user_id = v_auth_id;
        end if;

        update trimax_private.provisioning_requests
           set status = 'processing', attempts = attempts + 1, updated_at = now()
         where request_id = r.request_id;

        v_out := v_out || jsonb_build_array(jsonb_build_object(
            'request_id', r.request_id,
            'username', r.username,
            'email', lower(r.email),
            'existing_auth_user_id', v_auth_id,
            'existing_auth_confirmed', v_confirmed,
            'existing_auth_created_by_trimax', coalesce(v_meta ->> 'trimax_provisioned', '') = 'true'
                                               and coalesce(v_meta ->> 'trimax_username', '') = r.username,
            'existing_auth_linked_username', v_linked));
    end loop;
    return v_out;
end $function$;

CREATE OR REPLACE FUNCTION public.trimax_service_finish_provisioning(p_request_id bigint, p_outcome text, p_auth_user_id uuid DEFAULT NULL::uuid, p_message text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_outcome text := lower(btrim(coalesce(p_outcome, '')));
    v_username text;
    v_email text;
    v_req_status text;
    v_problem text;
    v_auth_email text;
    v_meta jsonb;
    v_other text;
begin
    if not trimax_private.is_service_request() then
        raise exception using errcode = 'TM403', message = 'Not allowed.';
    end if;
    if v_outcome not in ('invited', 'linked', 'failed') then
        raise exception using errcode = 'TM422', message = 'Unknown outcome.';
    end if;
    select pr.username, pr.email, pr.status into v_username, v_email, v_req_status
      from trimax_private.provisioning_requests pr where pr.request_id = p_request_id for update;
    if v_username is null then
        raise exception using errcode = 'TM404', message = 'Unknown request.';
    end if;
    if v_req_status <> 'processing' then
        raise exception using errcode = 'TM409', message = 'The request is not being processed (it is ' || v_req_status || ').';
    end if;

    if v_outcome = 'failed' then
        v_problem := left(coalesce(nullif(btrim(p_message), ''), 'Provisioning failed.'), 300);
    else
        v_problem := trimax_private.provisioning_problem(v_username, v_email);
        if v_problem is null then
            select a.email, coalesce(to_jsonb(a) -> 'raw_app_meta_data', '{}'::jsonb) into v_auth_email, v_meta
              from auth.users a where a.id = p_auth_user_id;
            if v_auth_email is null or lower(v_auth_email) <> lower(v_email) then
                v_problem := 'The Supabase sign-in does not have this account''s email address.';
            elsif coalesce(v_meta ->> 'trimax_provisioned', '') <> 'true' or coalesce(v_meta ->> 'trimax_username', '') <> v_username then
                v_problem := 'The Supabase sign-in was not created by TRIMAX for this account.';
            else
                select m.username into v_other from trimax_private.mobile_accounts m where m.auth_user_id = p_auth_user_id;
                if v_other is not null and v_other <> v_username then
                    v_problem := 'That Supabase sign-in is already linked to another TRIMAX account.';
                end if;
            end if;
        end if;
    end if;

    if v_problem is not null then
        update trimax_private.provisioning_requests
           set status = 'failed', last_error = v_problem, auth_user_id = coalesce(p_auth_user_id, auth_user_id), updated_at = now()
         where request_id = p_request_id;
        perform trimax_private.audit_user('provisioning', 'account.mobile_invite_failed', v_username, v_problem);
        return jsonb_build_object('request_id', p_request_id, 'status', 'failed', 'message', v_problem);
    end if;

    insert into trimax_private.mobile_accounts (auth_user_id, username, linked_by)
    values (p_auth_user_id, v_username, 'provisioning')
    on conflict do nothing;
    update trimax_private.provisioning_requests
       set status = v_outcome, last_error = null, auth_user_id = p_auth_user_id, updated_at = now()
     where request_id = p_request_id;
    perform trimax_private.audit_user('provisioning',
        case v_outcome when 'invited' then 'account.mobile_invite_sent' else 'account.link_mobile' end, v_username,
        case v_outcome when 'invited' then 'Invitation email sent to ' || v_email
                       else 'Linked to existing TRIMAX-created sign-in ' || v_email end);
    return jsonb_build_object('request_id', p_request_id, 'status', v_outcome);
end $function$;

-- ----------------------------------------------------------------------------------------------- 5
-- Legacy desktop triggers. Live names: trg_booking_items_total, audit_log_no_update. A database built
-- from older desktop scripts may already have equivalent triggers under other names, so a trigger is
-- only added when the table has no trigger calling the same kind of function.
CREATE OR REPLACE FUNCTION public.refresh_booking_total()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    b INT := COALESCE(NEW.booking_id, OLD.booking_id);
BEGIN
    UPDATE bookings
    SET total_amount = COALESCE(
            (SELECT SUM(subtotal) FROM booking_items WHERE booking_id = b), 0)
    WHERE booking_id = b;
    RETURN NULL;
END;
$function$;

CREATE OR REPLACE FUNCTION public.audit_log_block_changes()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$function$;

do $$
begin
    if not exists (select 1 from pg_trigger where tgrelid = 'public.booking_items'::regclass and not tgisinternal) then
        create trigger trg_booking_items_total after insert or delete or update on public.booking_items
            for each row execute function refresh_booking_total();
    end if;
    if to_regclass('public.audit_log') is not null
       and not exists (select 1 from pg_trigger where tgrelid = 'public.audit_log'::regclass and not tgisinternal) then
        create trigger audit_log_no_update before delete or update on public.audit_log
            for each row execute function audit_log_block_changes();
    end if;
end $$;

-- ----------------------------------------------------------------------------------------------- 6
-- Same privileges as live: the app calls only these functions; tables stay closed to API roles.
revoke all on function trimax_private.delivery_transition_allowed(text, text)          from public;
revoke all on function trimax_private.role_code(text)                                  from public;
revoke all on function trimax_private.audit_user(text, text, text, text)               from public;
revoke all on function trimax_private.hub_location()                                   from public;
revoke all on function trimax_private.is_service_request()                             from public;
revoke all on function trimax_private.duty_status_json(text)                           from public;
revoke all on function trimax_private.duty_photo_uploaded(text, uuid)                  from public;
revoke all on function trimax_private.provisioning_problem(text, text)                 from public;
revoke all on function trimax_private.request_mobile_access(text, text)                from public;

revoke all on function public.trimax_driver_update_delivery_status(integer, text, text, double precision, double precision, text, text) from public, anon, service_role;
revoke all on function public.trimax_update_location(double precision, double precision, double precision, double precision, double precision, boolean) from public, anon, service_role;
revoke all on function public.trimax_my_duty_status()                                  from public, anon, service_role;
revoke all on function public.trimax_record_duty_event(text, text, double precision, double precision, uuid) from public, anon, service_role;
revoke all on function public.trimax_service_claim_provisioning(integer)               from public, anon, authenticated;
revoke all on function public.trimax_service_finish_provisioning(bigint, text, uuid, text) from public, anon, authenticated;

grant execute on function public.trimax_driver_update_delivery_status(integer, text, text, double precision, double precision, text, text) to authenticated;
grant execute on function public.trimax_update_location(double precision, double precision, double precision, double precision, double precision, boolean) to authenticated;
grant execute on function public.trimax_my_duty_status()                               to authenticated;
grant execute on function public.trimax_record_duty_event(text, text, double precision, double precision, uuid) to authenticated;
grant execute on function public.trimax_service_claim_provisioning(integer)            to service_role;
grant execute on function public.trimax_service_finish_provisioning(bigint, text, uuid, text) to service_role;

revoke all on table public.user_locations, public.admin_notifications from anon, authenticated;
revoke all on table trimax_private.duty_events, trimax_private.provisioning_requests from public;

-- ----------------------------------------------------------------------------------------------- 7
do $$
begin
    if to_regclass('storage.buckets') is null then
        raise notice 'storage schema not present (not Supabase): skipping the duty-photos bucket';
        return;
    end if;
    insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
    values ('duty-photos', 'duty-photos', false, 5242880, array['image/jpeg'])
    on conflict (id) do nothing;

    if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects'
                   and policyname = 'trimax duty photo upload own folder') then
        create policy "trimax duty photo upload own folder" on storage.objects
            for insert to authenticated
            with check (bucket_id = 'duty-photos' and (storage.foldername(name))[1] = (select (auth.uid())::text));
    end if;
    if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects'
                   and policyname = 'trimax duty photo read own folder') then
        create policy "trimax duty photo read own folder" on storage.objects
            for select to authenticated
            using (bucket_id = 'duty-photos' and (storage.foldername(name))[1] = (select (auth.uid())::text));
    end if;
end $$;

commit;
