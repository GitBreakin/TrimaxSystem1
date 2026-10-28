-- =====================================================================================================
-- Post-defense revisions (2026-10-28): GPS location history (Admin only) and an Admin notification
-- when an Agent submits a booking.
--
-- 1. LOCATION HISTORY
--    Until now only the LAST position was kept (public.user_locations, one row per person, and the
--    driver's vehicles.latitude/longitude). Past movements were never stored and cannot be recovered.
--    From this update on, public.trimax_update_location (the call the phone already makes while the app
--    is open and the person is on duty) ALSO appends a sample to trimax_private.location_history when:
--      * the position is accurate enough (accuracy <= max_accuracy_m, default 100 m; unknown accuracy
--        is kept), and
--      * it is the person's first sample, or at least min_interval_s (default 30 s) passed since the last
--        stored sample, or the person moved at least min_distance_m (default 50 m) and at least 5 s passed.
--    The current-position behaviour (user_locations, vehicles) is unchanged and does not depend on it.
--    No phone update is needed: the phone sends the same call; the answer gains "history_stored".
--    Settings live in trimax_private.location_history_settings (one row) and can be changed without
--    code. Samples older than retention_days (default 90) are removed by
--    trimax_private.purge_location_history, which the Desktop runs when an Admin opens Location History.
--    The table is append-only: rows cannot be changed, and only expired rows (or the rows of a deleted
--    user) can be deleted.
--    Access: Admins only, through the Desktop (database owner) and trimax_private.location_history_for,
--    which re-checks that the caller is an active Admin and records the viewing in the audit log.
--    The phones (anon / authenticated) have no privilege on the table or the functions.
--
-- 2. NEW BOOKING NOTIFICATION
--    An AFTER INSERT trigger on public.bookings adds ONE admin_notifications row ("New Booking Submitted",
--    kind 'Announcement', category 'New booking', related booking <id>) when an Agent's booking from the
--    mobile app is saved. It runs inside the booking's own transaction, so a booking that fails to save
--    leaves no notification. A unique index on (related_type, related_id) for this category makes a
--    second notification for the same booking impossible; the phone's retry with the same request id
--    returns the existing booking without inserting, so it does not fire again either. Edits, driver
--    assignment and status changes are UPDATEs and never fire it. Bookings entered on the Desktop by an
--    Admin do not notify. Admin notifications stay one shared inbox for all Admins (existing model:
--    read_at / read_by); only the database owner (Desktop) can read them.
--
-- ORDER: after 20261027_04. Re-runnable. Rollback: 20261028_90_rollback_location_history_and_booking_notifications.sql
-- APPLIED to production on 2026-10-28 (about 18:33 Asia/Manila) as migration
-- 20261028_01_location_history_and_booking_notifications (from another session); its functions equal this file.
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('public.trimax_update_location(double precision,double precision,double precision,double precision,double precision,boolean)') is null
       or to_regprocedure('trimax_private.require_admin(text)') is null
       or to_regprocedure('trimax_private.audit_as(text,text,text,text,text)') is null
       or to_regclass('public.admin_notifications') is null
       or to_regprocedure('trimax_private.booking_json(integer,boolean)') is null then
        raise exception 'Apply the earlier updates first (through 20261027_04).';
    end if;
end $$;

-- ===================================================================================== 1. LOCATION HISTORY

create table if not exists trimax_private.location_history_settings (
    id              boolean primary key default true check (id),          -- exactly one row
    min_interval_s  integer not null default 30  check (min_interval_s between 5 and 3600),
    min_distance_m  integer not null default 50  check (min_distance_m between 5 and 5000),
    max_accuracy_m  integer not null default 100 check (max_accuracy_m between 5 and 10000),
    retention_days  integer not null default 90  check (retention_days between 1 and 3650)
);
insert into trimax_private.location_history_settings (id) values (true) on conflict (id) do nothing;

create table if not exists trimax_private.location_history (
    sample_id    bigint generated always as identity primary key,
    username     text not null references public.users (username) on update cascade on delete cascade,
    role_code    text not null check (role_code in ('AGENT', 'DRIVER')),
    latitude     double precision not null check (latitude between -90 and 90),
    longitude    double precision not null check (longitude between -180 and 180),
    accuracy_m   real check (accuracy_m is null or accuracy_m >= 0),
    speed_mps    double precision check (speed_mps is null or speed_mps between 0 and 100),
    bearing_deg  double precision check (bearing_deg is null or bearing_deg between 0 and 360),
    recorded_at  timestamptz not null default now(),                       -- database clock, never the phone's
    check (not (latitude = 0 and longitude = 0))
);
create index if not exists location_history_user_time_idx on trimax_private.location_history (username, recorded_at);
create index if not exists location_history_time_idx on trimax_private.location_history (recorded_at);

alter table trimax_private.location_history enable row level security;
alter table trimax_private.location_history_settings enable row level security;
revoke all on table trimax_private.location_history from public, anon, authenticated, service_role;
revoke all on table trimax_private.location_history_settings from public, anon, authenticated, service_role;

comment on table trimax_private.location_history is
    'GPS samples recorded by trimax_update_location (append-only; Admins only, through the Desktop). '
    'Starts when 20261028_01 was applied: earlier movements were never recorded.';

-- Append-only: no updates; a delete only for samples past retention or of a user who no longer exists.
create or replace function trimax_private.location_history_append_only()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $$
begin
    if tg_op = 'UPDATE' then
        -- ON UPDATE CASCADE of a renamed username is the only change allowed.
        if (to_jsonb(new) - 'username') = (to_jsonb(old) - 'username')
           and exists (select 1 from public.users u where u.username = new.username) then
            return new;
        end if;
        raise exception using errcode = 'TM409', message = 'Location history cannot be changed.';
    end if;
    if old.recorded_at < now() - make_interval(days => (select s.retention_days from trimax_private.location_history_settings s))
       or not exists (select 1 from public.users u where u.username = old.username) then
        return old;
    end if;
    raise exception using errcode = 'TM409', message = 'Location history cannot be deleted before it expires.';
end $$;

drop trigger if exists trimax_location_history_append_only on trimax_private.location_history;
create trigger trimax_location_history_append_only
    before update or delete on trimax_private.location_history
    for each row execute function trimax_private.location_history_append_only();

-- Distance in metres between two positions (haversine, spherical earth; good to well under 1 % here).
create or replace function trimax_private.distance_m(p_lat1 double precision, p_lon1 double precision,
                                                     p_lat2 double precision, p_lon2 double precision)
returns double precision
language sql
immutable
set search_path = public, pg_temp
as $$
    select 2 * 6371000 * asin(sqrt(
               power(sin(radians(p_lat2 - p_lat1) / 2), 2)
             + cos(radians(p_lat1)) * cos(radians(p_lat2)) * power(sin(radians(p_lon2 - p_lon1) / 2), 2)))
$$;

-- The phone's live location call: unchanged checks and current-position updates, plus the history sample.
create or replace function public.trimax_update_location(p_latitude double precision, p_longitude double precision,
                                                        p_accuracy double precision default null,
                                                        p_speed double precision default null,
                                                        p_bearing double precision default null,
                                                        p_is_on_duty boolean default true)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me text := trimax_private.require_account(null);
    v_role text;
    v_last timestamptz;
    v_accuracy double precision := p_accuracy;
    v_speed double precision := p_speed;
    v_bearing double precision := p_bearing;
    v_vehicles int := 0;
    v_set trimax_private.location_history_settings;
    v_prev record;
    v_history boolean := false;
    v_skip text := null;
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
        return jsonb_build_object('stored', false, 'throttled', true, 'recorded_at', v_last, 'history_stored', false);
    end if;

    -- The row lock taken here also makes two calls of the same person decide the history sample in turn.
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

    -- History sample (never fails the live update: anything unexpected here only skips the sample).
    if v_role in ('AGENT', 'DRIVER') then
        begin
            select * into v_set from trimax_private.location_history_settings limit 1;
            if v_accuracy is not null and v_accuracy > coalesce(v_set.max_accuracy_m, 100) then
                v_skip := 'inaccurate';
            else
                select h.latitude, h.longitude, h.recorded_at into v_prev
                  from trimax_private.location_history h
                 where h.username = v_me
                 order by h.recorded_at desc, h.sample_id desc
                 limit 1;
                if v_prev.recorded_at is null
                   or now() - v_prev.recorded_at >= make_interval(secs => coalesce(v_set.min_interval_s, 30))
                   or (now() - v_prev.recorded_at >= interval '5 seconds'
                       and trimax_private.distance_m(v_prev.latitude, v_prev.longitude, p_latitude, p_longitude)
                           >= coalesce(v_set.min_distance_m, 50)) then
                    insert into trimax_private.location_history (username, role_code, latitude, longitude, accuracy_m,
                                                                 speed_mps, bearing_deg)
                    values (v_me, v_role, p_latitude, p_longitude, v_accuracy, v_speed, v_bearing);
                    v_history := true;
                else
                    v_skip := 'too_soon';
                end if;
            end if;
        exception when others then
            v_history := false;
            v_skip := 'error';
        end;
    end if;

    return jsonb_build_object('stored', true, 'throttled', false, 'recorded_at', now(), 'vehicles_updated', v_vehicles,
                              'history_stored', v_history, 'history_skipped', v_skip);
end $$;

-- Removes samples older than the retention period. Desktop (database owner) only.
create or replace function trimax_private.purge_location_history(p_admin text)
returns integer
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_n integer;
    v_days integer;
begin
    perform trimax_private.require_admin(p_admin);
    select s.retention_days into v_days from trimax_private.location_history_settings s;
    delete from trimax_private.location_history h
     where h.recorded_at < now() - make_interval(days => coalesce(v_days, 90));
    get diagnostics v_n = row_count;
    if v_n > 0 then
        perform trimax_private.audit_as(p_admin, 'location.history_purge', 'location_history', 'expired',
            'Removed ' || v_n || ' GPS sample(s) older than ' || coalesce(v_days, 90) || ' days');
    end if;
    return v_n;
end $$;

-- One person's recorded samples in a time range, oldest first, for the Admin's Location History map.
--   * Only an active Admin may call it (re-checked here); every call is recorded in the audit log.
--   * Range: from < to, at most 31 days.
--   * More than p_max_points samples: every step-th sample is returned, always with the first and the
--     last one; total_points and step say so (the Desktop shows "showing 1 of every N recorded points").
--   * segment: a new number starts after a gap of more than p_gap_minutes without any sample (app closed,
--     no signal, off duty). The Desktop never draws a line across segments.
--   * gap_before_s: seconds since the previous RECORDED sample (null for the first).
create or replace function trimax_private.location_history_for(p_admin text, p_username text,
                                                               p_from timestamptz, p_to timestamptz,
                                                               p_max_points integer default 2000,
                                                               p_gap_minutes integer default 5)
returns table (sample_id bigint, recorded_at timestamptz, latitude double precision, longitude double precision,
               accuracy_m real, speed_mps double precision, gap_before_s integer, segment integer,
               total_points integer, step integer)
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_total integer;
    v_step integer;
    v_max integer := least(greatest(coalesce(p_max_points, 2000), 10), 5000);
    v_gap integer := least(greatest(coalesce(p_gap_minutes, 5), 1), 240);
begin
    perform trimax_private.require_admin(p_admin);
    if p_from is null or p_to is null or p_from >= p_to then
        raise exception using errcode = 'TM422', message = 'Choose a start that is before the end.';
    end if;
    if p_to - p_from > interval '31 days' then
        raise exception using errcode = 'TM422', message = 'Choose a period of at most 31 days.';
    end if;
    if coalesce(trimax_private.role_code(p_username), 'NONE') not in ('AGENT', 'DRIVER') then
        raise exception using errcode = 'TM404', message = 'Choose an agent or a driver.';
    end if;

    select count(*)::int into v_total
      from trimax_private.location_history h
     where h.username = p_username and h.recorded_at >= p_from and h.recorded_at < p_to;
    v_step := greatest(1, ceil(v_total::numeric / v_max)::int);

    perform trimax_private.audit_as(p_admin, 'location.history_view', 'user', p_username,
        'Viewed GPS history ' || to_char(p_from at time zone 'Asia/Manila', 'YYYY-MM-DD HH24:MI') || ' to '
        || to_char(p_to at time zone 'Asia/Manila', 'YYYY-MM-DD HH24:MI') || ' (Manila), ' || v_total || ' sample(s)');

    return query
    with s as (
        select h.sample_id, h.recorded_at, h.latitude, h.longitude, h.accuracy_m, h.speed_mps,
               extract(epoch from h.recorded_at - lag(h.recorded_at) over w)::int as gap_s,
               row_number() over w as rn
          from trimax_private.location_history h
         where h.username = p_username and h.recorded_at >= p_from and h.recorded_at < p_to
        window w as (order by h.recorded_at, h.sample_id)
    ), g as (
        select s.*, (sum(case when s.gap_s > v_gap * 60 then 1 else 0 end) over (order by s.rn))::int as seg,
               lead(s.gap_s) over (order by s.rn) as next_gap_s
          from s
    )
    select g.sample_id, g.recorded_at, g.latitude, g.longitude, g.accuracy_m, g.speed_mps, g.gap_s, g.seg,
           v_total, v_step
      from g
     where (g.rn - 1) % v_step = 0 or g.rn = v_total
            -- keep both ends of every gap, so a segment never starts or ends at a skipped sample
            or g.gap_s > v_gap * 60
            or g.next_gap_s > v_gap * 60
     order by g.rn;
end $$;

revoke all on function trimax_private.location_history_append_only() from public;
revoke all on function trimax_private.distance_m(double precision, double precision, double precision, double precision) from public;
revoke all on function trimax_private.purge_location_history(text) from public;
revoke all on function trimax_private.location_history_for(text, text, timestamptz, timestamptz, integer, integer) from public;
revoke all on function public.trimax_update_location(double precision, double precision, double precision, double precision, double precision, boolean) from public, anon, service_role;
grant execute on function public.trimax_update_location(double precision, double precision, double precision, double precision, double precision, boolean) to authenticated;

-- ===================================================================================== 2. NEW BOOKING NOTIFICATION

-- At most one "New booking" notification per booking.
create unique index if not exists admin_notifications_new_booking_key
    on public.admin_notifications (related_type, related_id) where category = 'New booking';

create or replace function trimax_private.bookings_notify_new()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_agent_name text;
    v_customer text;
begin
    if coalesce(new.created_via, '') <> 'mobile' or new.created_by_username is null
       or coalesce(trimax_private.role_code(new.created_by_username), 'NONE') <> 'AGENT' then
        return null;
    end if;
    select u.name into v_agent_name from public.users u where u.username = new.created_by_username;
    select c.client_name into v_customer from public.clients c where c.client_id = new.client_id;
    insert into public.admin_notifications (kind, category, title, body, related_type, related_id, reported_by)
    values ('Announcement', 'New booking', 'New Booking Submitted',
            'Booking #' || new.booking_id
            || coalesce(' for ' || nullif(btrim(v_customer), ''), '')
            || ' submitted by ' || coalesce(nullif(btrim(v_agent_name), '') || ' (' || new.created_by_username || ')',
                                            new.created_by_username)
            || ' on ' || to_char(coalesce(new.transc_date, now()) at time zone 'Asia/Manila', 'Mon DD, YYYY HH12:MI AM')
            || ' (Manila). Open the booking to confirm it and assign a driver.',
            'booking', new.booking_id::text, new.created_by_username)
    on conflict (related_type, related_id) where category = 'New booking' do nothing;
    return null;
end $$;

revoke all on function trimax_private.bookings_notify_new() from public;

drop trigger if exists trimax_bookings_notify_new on public.bookings;
create trigger trimax_bookings_notify_new
    after insert on public.bookings
    for each row execute function trimax_private.bookings_notify_new();

commit;
