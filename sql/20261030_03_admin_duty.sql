-- =====================================================================================================
-- Time in / Time out by the Admin for an agent or driver (owner's request of 2026-10-30, 13:27: "dapat pwede si
-- admin mag in and out kay driver"), e.g. the driver forgot to tap Start / End duty, or the phone is broken.
--
-- This adds:
--   1. trimax_private.duty_events.recorded_by (the Admin who recorded it; null = the person's own phone) and note.
--      photo_path may now be empty, but only for an Admin's record: a phone record still always has its photo.
--   2. trimax_private.admin_duty_event(admin, username, 'START' | 'END', at, note): Desktop only (owner). Only an
--      active Admin; only for an active Agent or Driver. The same order rules as the phone (no Start while on duty,
--      no End while off duty), at the time chosen: not in the future, at most 7 days back, after that person's last
--      record, and an End within 24 hours of the Start (a longer duty is never counted). Audited
--      (duty.admin_start / duty.admin_end). An End also updates their payroll through the 20261030_02 trigger.
--
-- The phone functions are unchanged; after an Admin's Time in the app shows the person on duty, and they can End
-- duty from the phone as usual.
-- ORDER: after 20261025_01 (duty_events). Re-runnable. Adds 2 columns, 2 checks, 1 index, 1 function.
-- Changes no rows. Rollback: 20261030_97 (refused once an Admin has recorded a Time in / Time out).
-- =====================================================================================================

begin;

alter table trimax_private.duty_events alter column photo_path drop not null;
alter table trimax_private.duty_events add column if not exists recorded_by text
    references public.users (username) on update cascade on delete set null;
alter table trimax_private.duty_events add column if not exists note text;
do $$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'trimax_private.duty_events'::regclass
                   and conname = 'duty_events_source_chk') then
        -- a phone record has its photo; only an Admin's record may have none
        alter table trimax_private.duty_events add constraint duty_events_source_chk
            check (photo_path is not null or recorded_by is not null);
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'trimax_private.duty_events'::regclass
                   and conname = 'duty_events_note_chk') then
        alter table trimax_private.duty_events add constraint duty_events_note_chk
            check (note is null or char_length(note) between 1 and 200);
    end if;
end $$;
create index if not exists duty_events_recorded_by_idx on trimax_private.duty_events (recorded_by)
    where recorded_by is not null;
comment on column trimax_private.duty_events.recorded_by is
    'The Admin who recorded this Time in / Time out on the Desktop; null = the person''s own phone (with photo).';

create or replace function trimax_private.admin_duty_event(p_admin text, p_username text, p_event text,
                                                           p_at timestamptz, p_note text default null)
returns jsonb
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_event text := upper(btrim(coalesce(p_event, '')));
    v_at timestamptz := coalesce(p_at, now());
    v_note text := nullif(btrim(coalesce(p_note, '')), '');
    v_name text;
    v_role text;
    v_status text;
    v_last text;
    v_last_at timestamptz;
    v_id bigint;
begin
    perform trimax_private.require_admin(p_admin);
    if v_event not in ('START', 'END') then
        raise exception using errcode = 'TM422', message = 'Choose Time in or Time out.';
    end if;
    select coalesce(nullif(btrim(u.name), ''), u.username), r.role_name, u.account_status into v_name, v_role, v_status
      from public.users u left join public.roles r on r.role_id = u.role_id
     where u.username = p_username;
    if v_name is null then
        raise exception using errcode = 'TM404', message = 'That account no longer exists.';
    end if;
    if coalesce(v_role, '') not in ('Agent', 'Driver') then
        raise exception using errcode = 'TM422', message = 'Time in / Time out is for agents and drivers.';
    end if;
    if v_status is distinct from 'Active' then
        raise exception using errcode = 'TM422', message = v_name || '''s account is not active.';
    end if;
    if v_at > now() + interval '2 minutes' then
        raise exception using errcode = 'TM422', message = 'The time cannot be in the future.';
    end if;
    if v_at < now() - interval '7 days' then
        raise exception using errcode = 'TM422', message = 'The time can be at most 7 days ago.';
    end if;
    if char_length(coalesce(v_note, '')) > 200 then
        raise exception using errcode = 'TM422', message = 'The note is too long (200 characters at most).';
    end if;

    -- one duty change at a time per person (the same lock as the phone)
    perform pg_advisory_xact_lock(hashtextextended('trimax-duty:' || p_username, 0));
    select e.event_type, e.recorded_at into v_last, v_last_at
      from trimax_private.duty_events e where e.username = p_username
     order by e.recorded_at desc, e.event_id desc limit 1;
    if v_event = 'START' and v_last = 'START' then
        raise exception using errcode = 'TM409',
            message = v_name || ' is already on duty since '
                      || to_char(v_last_at at time zone 'Asia/Manila', 'Mon DD, HH12:MI AM') || '. Record a Time out first.';
    end if;
    if v_event = 'END' and coalesce(v_last, 'END') = 'END' then
        raise exception using errcode = 'TM409', message = v_name || ' is not on duty. Record a Time in first.';
    end if;
    if v_last_at is not null and v_at <= v_last_at then
        raise exception using errcode = 'TM409',
            message = 'Choose a time after ' || v_name || '''s last record ('
                      || to_char(v_last_at at time zone 'Asia/Manila', 'Mon DD, HH12:MI AM') || ').';
    end if;
    if v_event = 'END' and v_at - v_last_at > interval '24 hours' then
        raise exception using errcode = 'TM422',
            message = 'That is more than 24 hours after the Time in ('
                      || to_char(v_last_at at time zone 'Asia/Manila', 'Mon DD, HH12:MI AM')
                      || '). A duty longer than 24 hours is not counted; choose the right time.';
    end if;

    insert into trimax_private.duty_events (username, event_type, photo_path, recorded_at, recorded_by, note)
    values (p_username, v_event, null, v_at, p_admin, v_note)
    returning event_id into v_id;
    perform trimax_private.audit_as(p_admin, case v_event when 'START' then 'duty.admin_start' else 'duty.admin_end' end,
        'duty', p_username,
        v_name || ': ' || case v_event when 'START' then 'Time in' else 'Time out' end || ' at '
        || to_char(v_at at time zone 'Asia/Manila', 'YYYY-MM-DD HH24:MI') || ', recorded by the admin'
        || coalesce('; note: ' || v_note, ''));
    return trimax_private.duty_status_json(p_username) || jsonb_build_object('event_id', v_id, 'recorded_at', v_at);
end $function$;

revoke all on function trimax_private.admin_duty_event(text, text, text, timestamptz, text) from public, anon, authenticated, service_role;

commit;
