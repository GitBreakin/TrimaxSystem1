-- =====================================================================================================
-- Payroll from attendance (owner's request of 2026-10-30): "sa attendance sa payroll, dapat automatic na papasok
-- kung ilan pinasok ng agent and driver dipende sa attendance nila, yung ibang employee, lagay ka attendance rin
-- nila kahit si admin na lang mag lagay ng attendance".
-- Owner's decisions: pay = days worked x daily rate; for agents and drivers 8 hours of duty = 1 full day;
-- employees without an app account: the Admin marks Present / Half day / Absent.
--
-- This adds:
--   1. public.staff_attendance (staff_id, work_date, PRESENT / HALF_DAY / ABSENT, note): the attendance of the
--      employees without an app account (payroll_staff), entered by the Admin. Owner only (RLS on, no grants).
--   2. trimax_private.save_staff_attendance(admin, day, entries): Admin only; saves one day for several employees
--      ([{"staff_id": 1, "status": "PRESENT"}, ...]; status null clears that day); not for a future day; audited.
--   3. trimax_private.attendance_days(username, staff_id, from, to): the days worked in a pay period.
--        - an agent or driver (username): their duty sessions from the app (trimax_private.duty_events): each
--          Start duty paired with the next End duty (before the next Start, within 24 hours), counted on the day
--          it started; a day counts min(hours that day, 8) / 8, so 8 hours = 1 day, 6 hours = 0.75. A Start
--          without an End duty is not counted (the day is reported as "open" so the Admin can check it);
--        - an employee without an account (staff_id): Present = 1, Half day = 0.5, Absent = 0.
--   4. payroll_records.days_worked and daily_rate (both optional, kept as entered) and
--      trimax_private.set_payroll_attendance(admin, payroll_id, days, rate): saved with the record by the Desktop
--      in the same transaction as save_payroll; audited. Basic pay stays what the Admin saves (the Desktop fills
--      it in as days x rate).
--
-- Nothing else changes: save_payroll, the reports and the phones are untouched. Older Desktops never read the
-- new columns.
-- ORDER: after 20261029_01 (payroll_staff). Re-runnable. Adds 1 table, 2 columns, 2 checks, 3 functions.
-- Changes no rows. Rollback: 20261030_99 (refused while attendance or days are recorded).
-- =====================================================================================================

begin;

do $$
begin
    if to_regclass('public.payroll_staff') is null or to_regclass('trimax_private.duty_events') is null then
        raise exception 'Apply 20261029_01 (payroll employees) first.';
    end if;
end $$;

-- ===================================================================================== 1. STAFF ATTENDANCE
create table if not exists public.staff_attendance (
    staff_id    bigint not null references public.payroll_staff (staff_id) on delete cascade,
    work_date   date not null,
    status      text not null,
    note        text,
    recorded_by text references public.users (username) on update cascade on delete set null,
    recorded_at timestamptz not null default now(),
    constraint staff_attendance_pkey primary key (staff_id, work_date),
    constraint staff_attendance_status_chk check (status in ('PRESENT', 'HALF_DAY', 'ABSENT')),
    constraint staff_attendance_note_chk check (note is null or char_length(note) between 1 and 200)
);
create index if not exists staff_attendance_day_idx on public.staff_attendance (work_date);
create index if not exists staff_attendance_recorded_by_idx on public.staff_attendance (recorded_by);
comment on table public.staff_attendance is
    'Attendance of employees without an app account, entered by the Admin. Desktop only (owner); not reachable from the API.';
alter table public.staff_attendance enable row level security;
revoke all on table public.staff_attendance from public, anon, authenticated, service_role;

-- ===================================================================================== 2. SAVE ONE DAY
create or replace function trimax_private.save_staff_attendance(p_admin text, p_work_date date, p_entries jsonb)
returns integer
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_bad int;
    v_saved int := 0;
    v_cleared int := 0;
    v_summary text;
begin
    perform trimax_private.require_admin(p_admin);
    if p_work_date is null or p_work_date < date '2000-01-01'
       or p_work_date > (now() at time zone 'Asia/Manila')::date then
        raise exception using errcode = 'TM422', message = 'Choose a day that is not in the future.';
    end if;
    if p_entries is null or jsonb_typeof(p_entries) <> 'array' or jsonb_array_length(p_entries) = 0
       or jsonb_array_length(p_entries) > 500 then
        raise exception using errcode = 'TM422', message = 'Nothing to save.';
    end if;
    select count(*) into v_bad
      from jsonb_array_elements(p_entries) e
     where jsonb_typeof(e) <> 'object'
        or coalesce(e ->> 'staff_id', '') !~ '^[0-9]{1,15}$'
        or (e ? 'status' and jsonb_typeof(e -> 'status') <> 'null'
            and coalesce(e ->> 'status', '') not in ('PRESENT', 'HALF_DAY', 'ABSENT'))
        or (e ? 'note' and jsonb_typeof(e -> 'note') <> 'null'
            and char_length(btrim(coalesce(e ->> 'note', ''))) > 200);
    if v_bad > 0 then
        raise exception using errcode = 'TM422', message = 'Each employee needs Present, Half day, Absent or nothing.';
    end if;
    if exists (select 1 from jsonb_array_elements(p_entries) e group by e ->> 'staff_id' having count(*) > 1) then
        raise exception using errcode = 'TM422', message = 'An employee is listed twice.';
    end if;
    if exists (select 1 from jsonb_array_elements(p_entries) e
                where not exists (select 1 from public.payroll_staff s where s.staff_id = (e ->> 'staff_id')::bigint)) then
        raise exception using errcode = 'TM404', message = 'An employee in the list no longer exists. Reload the list.';
    end if;

    delete from public.staff_attendance a
     using jsonb_array_elements(p_entries) e
     where a.work_date = p_work_date and a.staff_id = (e ->> 'staff_id')::bigint
       and nullif(e ->> 'status', '') is null;
    get diagnostics v_cleared = row_count;

    insert into public.staff_attendance (staff_id, work_date, status, note, recorded_by, recorded_at)
    select (e ->> 'staff_id')::bigint, p_work_date, e ->> 'status', nullif(btrim(coalesce(e ->> 'note', '')), ''), p_admin, now()
      from jsonb_array_elements(p_entries) e
     where nullif(e ->> 'status', '') is not null
    on conflict (staff_id, work_date) do update
       set status = excluded.status, note = excluded.note, recorded_by = excluded.recorded_by, recorded_at = now()
     where staff_attendance.status is distinct from excluded.status or staff_attendance.note is distinct from excluded.note;
    get diagnostics v_saved = row_count;

    if v_saved + v_cleared > 0 then
        select string_agg(x.n || ' ' || x.label, ', ' order by x.ord) into v_summary
          from (select count(*) as n, min(case e ->> 'status' when 'PRESENT' then 1 when 'HALF_DAY' then 2 else 3 end) as ord,
                       case e ->> 'status' when 'PRESENT' then 'present' when 'HALF_DAY' then 'half day' else 'absent' end as label
                  from jsonb_array_elements(p_entries) e where nullif(e ->> 'status', '') is not null group by 3) x;
        perform trimax_private.audit_as(p_admin, 'attendance.staff', 'attendance', p_work_date::text,
            p_work_date || ': ' || coalesce(v_summary, 'nothing marked') || case when v_cleared > 0 then ', ' || v_cleared || ' cleared' else '' end);
    end if;
    return v_saved + v_cleared;
end $function$;

-- ===================================================================================== 3. DAYS WORKED IN A PERIOD
create or replace function trimax_private.attendance_days(p_username text, p_staff_id bigint, p_from date, p_to date)
returns jsonb
language plpgsql
stable
set search_path = public, pg_temp
as $function$
declare
    v_result jsonb;
begin
    if (p_username is null) = (p_staff_id is null) then
        raise exception using errcode = 'TM422', message = 'Choose one employee.';
    end if;
    if p_from is null or p_to is null or p_to < p_from or p_to - p_from > 92 then
        raise exception using errcode = 'TM422', message = 'Enter the pay period (at most 3 months).';
    end if;

    if p_staff_id is not null then
        select jsonb_build_object(
                   'source', 'staff',
                   'days', coalesce(sum(case a.status when 'PRESENT' then 1 when 'HALF_DAY' then 0.5 else 0 end), 0),
                   'present', count(*) filter (where a.status = 'PRESENT'),
                   'half_days', count(*) filter (where a.status = 'HALF_DAY'),
                   'absent', count(*) filter (where a.status = 'ABSENT'))
          into v_result
          from public.staff_attendance a
         where a.staff_id = p_staff_id and a.work_date between p_from and p_to;
        return v_result;
    end if;

    with ev as (
        select e.event_type, e.recorded_at
          from trimax_private.duty_events e
         where e.username = p_username
           and e.recorded_at >= (p_from::timestamp at time zone 'Asia/Manila') - interval '1 day'
           and e.recorded_at < ((p_to + 1)::timestamp at time zone 'Asia/Manila') + interval '2 days'),
    sessions as (
        select s.recorded_at as started,
               (select min(x.recorded_at) from ev x
                 where x.event_type = 'END' and x.recorded_at > s.recorded_at
                   and x.recorded_at < coalesce((select min(y.recorded_at) from ev y
                                                  where y.event_type = 'START' and y.recorded_at > s.recorded_at), 'infinity')) as ended
          from ev s
         where s.event_type = 'START'),
    counted as (
        select (started at time zone 'Asia/Manila')::date as day,
               case when ended is not null and ended - started <= interval '24 hours'
                    then extract(epoch from ended - started) / 3600.0 end as hours
          from sessions),
    per_day as (
        select day, sum(hours) as hours, bool_or(hours is null) as open
          from counted
         where day between p_from and p_to
         group by day)
    select jsonb_build_object(
               'source', 'duty',
               'days', coalesce(sum(round(least(coalesce(hours, 0), 8) / 8, 2)), 0),
               'hours', round(coalesce(sum(hours), 0), 2),
               'duty_days', count(*),
               'open_days', count(*) filter (where open),
               'open_dates', coalesce(jsonb_agg(day order by day) filter (where open), '[]'::jsonb))
      into v_result
      from per_day;
    return v_result;
end $function$;

-- ===================================================================================== 4. DAYS AND RATE ON A PAYROLL RECORD
alter table public.payroll_records add column if not exists days_worked numeric(6, 2);
alter table public.payroll_records add column if not exists daily_rate numeric(12, 2);
do $$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.payroll_records'::regclass
                   and conname = 'payroll_records_days_chk') then
        alter table public.payroll_records add constraint payroll_records_days_chk
            check (days_worked is null or days_worked between 0 and 366);
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.payroll_records'::regclass
                   and conname = 'payroll_records_daily_rate_chk') then
        alter table public.payroll_records add constraint payroll_records_daily_rate_chk
            check (daily_rate is null or daily_rate between 0 and 10000000);
    end if;
end $$;
comment on column public.payroll_records.days_worked is 'Days worked in the pay period (from attendance, as saved by the Admin).';
comment on column public.payroll_records.daily_rate is 'Pay per day used for the basic pay (days worked x daily rate), as saved by the Admin.';

create or replace function trimax_private.set_payroll_attendance(p_admin text, p_payroll_id bigint,
                                                                  p_days numeric, p_rate numeric)
returns void
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_old public.payroll_records;
begin
    perform trimax_private.require_admin(p_admin);
    if p_days is not null and (p_days < 0 or p_days > 366 or p_days <> round(p_days, 2)) then
        raise exception using errcode = 'TM422', message = 'Days worked must be from 0 to 366, with at most 2 decimals.';
    end if;
    if p_rate is not null and (p_rate < 0 or p_rate > 10000000 or p_rate <> round(p_rate, 2)) then
        raise exception using errcode = 'TM422', message = 'The daily rate must be 0 or more, with at most 2 decimals.';
    end if;
    select * into v_old from public.payroll_records r where r.payroll_id = p_payroll_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'Payroll record not found.';
    end if;
    if v_old.status = 'VOID' then
        raise exception using errcode = 'TM409', message = 'A voided payroll record cannot be changed.';
    end if;
    if v_old.days_worked is not distinct from p_days and v_old.daily_rate is not distinct from p_rate then
        return;
    end if;
    update public.payroll_records r set days_worked = p_days, daily_rate = p_rate where r.payroll_id = p_payroll_id;
    perform trimax_private.audit_as(p_admin, 'payroll.days', 'payroll', p_payroll_id::text,
        v_old.employee_name || ', ' || v_old.period_start || ' to ' || v_old.period_end || ': '
        || coalesce(trim_scale(p_days)::text || ' days', 'days not set')
        || coalesce(' x ' || trimax_private.peso(p_rate), '')
        || case when v_old.days_worked is not null or v_old.daily_rate is not null
                then ' (was ' || coalesce(trim_scale(v_old.days_worked)::text || ' days', 'no days')
                     || coalesce(' x ' || trimax_private.peso(v_old.daily_rate), '') || ')'
                else '' end);
end $function$;

revoke all on function trimax_private.save_staff_attendance(text, date, jsonb) from public, anon, authenticated, service_role;
revoke all on function trimax_private.attendance_days(text, bigint, date, date) from public, anon, authenticated, service_role;
revoke all on function trimax_private.set_payroll_attendance(text, bigint, numeric, numeric) from public, anon, authenticated, service_role;

commit;
