-- =====================================================================================================
-- Payroll made by attendance, by itself (owner's request of 2026-10-30, 11:50): "kapag mag add ng new employee
-- dapat i seset agad yung basic pay nya, then kada present, absent, halfday sa attendance, automatic na sya
-- papasok sa payroll, hindi na manual i aadd ... ang gagawin nalang ni admin present half day or absent tas
-- automatic na sya papasok sa payroll".
--
-- This adds:
--   1. A daily rate per employee (the pay for one full day): payroll_staff.daily_rate for the employees without
--      an app account, and public.payroll_account_rates for app accounts (agents, drivers). Owner only.
--   2. payroll_records.auto: the record is kept by attendance (days worked x daily rate = basic pay).
--   3. trimax_private.pay_period(day): the pay week of a day, Monday to Sunday (owner, 13:27: "pay out weekly";
--      the week runs Monday to Sunday).
--   4. trimax_private.sync_payroll(actor, username, staff_id, day): for an employee with a daily rate, makes or
--      updates the UNPAID record of the period of that day from attendance_days() (20261030_01). A PAID record is
--      never changed. An unpaid record of the same employee and period that was typed by hand (also a typed name
--      equal to the employee's name) is taken over once attendance is recorded in that period. Nothing recorded
--      in the period: no record is made and a typed record stays as typed. When another record of the employee
--      already covers part of the week (for example a 1st-15th one typed before), no weekly record is made, so
--      nothing is paid twice; the Desktop says which record it is.
--      A past period keeps the daily rate it was counted with; the current one uses the employee's rate.
--   5. trimax_private.set_employee_rate(admin, username, staff_id, rate): Admin only, audited; the current period
--      is counted again. No rate: the employee's unpaid automatic records become ordinary (typed) records.
--   6. trimax_private.sync_payroll_all(admin, day): counts again the period of that day for everyone with a
--      daily rate (the Desktop does this for the current and the previous period when Payroll opens).
--   7. save_staff_attendance (20261030_01) now counts the payroll of the day's period after saving.
--   8. A trigger on trimax_private.duty_events: an End duty from the phone counts that person's payroll. It can
--      never make the End duty fail (an error is only a warning in the log; Payroll counts again when it opens).
--   9. set_payroll_attendance (20261030_01): its history no longer says "x P0.00" when there is no rate.
--
-- Phones are unchanged (same functions; an End duty just also updates payroll). save_payroll, the reports and
-- the driver summary are unchanged: they read payroll_records as before.
-- ORDER: after 20261030_01. Re-runnable. Adds 1 table, 2 columns, 1 trigger, 5 functions; replaces 2.
-- Changes no rows. Rollback: 20261030_98 (refused once a daily rate or an automatic record exists).
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.attendance_days(text,bigint,date,date)') is null then
        raise exception 'Apply 20261030_01 (payroll from attendance) first.';
    end if;
end $$;

-- ===================================================================================== 1. DAILY RATES
alter table public.payroll_staff add column if not exists daily_rate numeric(12, 2);
do $$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.payroll_staff'::regclass
                   and conname = 'payroll_staff_daily_rate_chk') then
        alter table public.payroll_staff add constraint payroll_staff_daily_rate_chk
            check (daily_rate is null or daily_rate between 0 and 10000000);
    end if;
end $$;
comment on column public.payroll_staff.daily_rate is 'Pay for one full day; with it, attendance makes the payroll by itself.';

create table if not exists public.payroll_account_rates (
    username   text primary key references public.users (username) on update cascade on delete cascade,
    daily_rate numeric(12, 2) not null,
    updated_by text references public.users (username) on update cascade on delete set null,
    updated_at timestamptz not null default now(),
    constraint payroll_account_rates_rate_chk check (daily_rate between 0 and 10000000)
);
create index if not exists payroll_account_rates_updated_by_idx on public.payroll_account_rates (updated_by);
comment on table public.payroll_account_rates is
    'Daily rate of app accounts (agents, drivers) for the payroll made from Start / End duty. Desktop only (owner).';
alter table public.payroll_account_rates enable row level security;
revoke all on table public.payroll_account_rates from public, anon, authenticated, service_role;

-- ===================================================================================== 2. AUTOMATIC RECORDS
alter table public.payroll_records add column if not exists auto boolean not null default false;
comment on column public.payroll_records.auto is
    'Kept by attendance: days worked x daily rate = basic pay, counted again while the record is unpaid.';

-- ===================================================================================== 3. PAY PERIOD
create or replace function trimax_private.pay_period(p_day date, out period_start date, out period_end date)
language sql
immutable
set search_path = public, pg_temp
as $function$
    -- Monday to Sunday
    select p_day - (extract(isodow from p_day)::int - 1), p_day - (extract(isodow from p_day)::int - 1) + 6
$function$;

-- ===================================================================================== 4. COUNT ONE EMPLOYEE'S PERIOD
create or replace function trimax_private.sync_payroll(p_actor text, p_username text, p_staff_id bigint, p_day date)
returns text
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_rate numeric;
    v_name text;
    v_ps date;
    v_pe date;
    v_rec public.payroll_records;
    v_att jsonb;
    v_days numeric;
    v_any boolean;
    v_use_rate numeric;
    v_basic numeric;
    v_today date := (now() at time zone 'Asia/Manila')::date;
begin
    if (p_username is null) = (p_staff_id is null) or p_day is null then
        raise exception using errcode = 'TM422', message = 'Choose one employee and a day.';
    end if;
    if p_staff_id is not null then
        select s.daily_rate, s.name into v_rate, v_name from public.payroll_staff s where s.staff_id = p_staff_id;
        if v_name is null then
            return 'missing';
        end if;
    else
        select coalesce(nullif(btrim(u.name), ''), u.username) into v_name from public.users u where u.username = p_username;
        if v_name is null then
            return 'missing';
        end if;
        select r.daily_rate into v_rate from public.payroll_account_rates r where r.username = p_username;
    end if;
    if v_rate is null then
        return 'no_rate';
    end if;

    select p.period_start, p.period_end into v_ps, v_pe from trimax_private.pay_period(p_day) p;
    -- one count at a time per employee and period
    perform pg_advisory_xact_lock(hashtextextended('trimax-payroll-auto:' || coalesce(p_username, 'staff ' || p_staff_id)
                                                   || ':' || v_ps, 0));
    select * into v_rec from public.payroll_records r
     where r.status <> 'VOID' and r.period_start = v_ps and r.period_end = v_pe
       and case when p_staff_id is not null
                then r.staff_id = p_staff_id
                     or (r.staff_id is null and r.employee_username is null and lower(r.employee_name) = lower(v_name))
                else r.employee_username = p_username end
     order by (r.staff_id is not null) desc, r.payroll_id
     limit 1
     for update;
    if v_rec.payroll_id is not null and v_rec.status = 'PAID' then
        return 'paid';
    end if;

    v_att := trimax_private.attendance_days(p_username, p_staff_id, v_ps, v_pe);
    v_days := (v_att ->> 'days')::numeric;
    v_any := case when v_att ->> 'source' = 'duty' then (v_att ->> 'duty_days')::int > 0
                  else (v_att ->> 'present')::int + (v_att ->> 'half_days')::int + (v_att ->> 'absent')::int > 0 end;

    if v_rec.payroll_id is null then
        if not v_any then
            return 'nothing';
        end if;
        -- another record of this employee already covers part of the week: no second pay for the same days
        if exists (select 1 from public.payroll_records r
                    where r.status <> 'VOID' and r.period_start <= v_pe and r.period_end >= v_ps
                      and case when p_staff_id is not null
                               then r.staff_id = p_staff_id
                                    or (r.staff_id is null and r.employee_username is null and lower(r.employee_name) = lower(v_name))
                               else r.employee_username = p_username end) then
            return 'overlap';
        end if;
        v_basic := round(v_days * v_rate, 2);
        insert into public.payroll_records (employee_username, employee_name, period_start, period_end, basic_pay,
                                            allowances, deductions, status, created_by, client_request_id, staff_id,
                                            days_worked, daily_rate, auto)
        values (p_username, v_name, v_ps, v_pe, v_basic, 0, 0, 'UNPAID', p_actor, gen_random_uuid(), p_staff_id,
                v_days, v_rate, true)
        returning * into v_rec;
        perform trimax_private.audit_as(p_actor, 'payroll.auto', 'payroll', v_rec.payroll_id::text,
            v_name || ', ' || v_ps || ' to ' || v_pe || ': made from attendance, ' || trim_scale(v_days) || ' days x '
            || trimax_private.peso(v_rate) || ' = ' || trimax_private.peso(v_basic));
        return 'created';
    end if;

    if not v_rec.auto and not v_any then
        return 'nothing';   -- a record typed by hand with no attendance behind it stays as it was typed
    end if;
    -- a past period keeps the rate it was counted with; the current one uses the employee's rate
    v_use_rate := case when v_pe < v_today and v_rec.auto and v_rec.daily_rate is not null then v_rec.daily_rate
                       else v_rate end;
    v_basic := round(v_days * v_use_rate, 2);
    if v_rec.auto and v_rec.days_worked = v_days and v_rec.daily_rate = v_use_rate and v_rec.basic_pay = v_basic
       and v_rec.staff_id is not distinct from p_staff_id then
        return 'unchanged';
    end if;
    if v_basic + v_rec.allowances - v_rec.deductions < 0 then
        raise exception using errcode = 'TM422',
            message = v_name || ': the deductions (' || trimax_private.peso(v_rec.deductions) || ') would be more than the pay ('
                      || trimax_private.peso(v_basic + v_rec.allowances) || ') for ' || v_ps || ' to ' || v_pe
                      || '. Lower the deductions in Payroll first.';
    end if;
    update public.payroll_records r
       set days_worked = v_days, daily_rate = v_use_rate, basic_pay = v_basic, auto = true,
           staff_id = coalesce(r.staff_id, p_staff_id), updated_by = p_actor, updated_at = now()
     where r.payroll_id = v_rec.payroll_id;
    perform trimax_private.audit_as(p_actor, 'payroll.auto', 'payroll', v_rec.payroll_id::text,
        v_name || ', ' || v_ps || ' to ' || v_pe || ': ' || trim_scale(v_days) || ' days x ' || trimax_private.peso(v_use_rate)
        || ' = ' || trimax_private.peso(v_basic) || ' (was ' || coalesce(trim_scale(v_rec.days_worked)::text || ' days, ', '')
        || trimax_private.peso(v_rec.basic_pay) || case when v_rec.auto then '' else ', typed by hand' end || ')');
    return 'updated';
end $function$;

-- ===================================================================================== 5. SET A DAILY RATE
create or replace function trimax_private.set_employee_rate(p_admin text, p_username text, p_staff_id bigint, p_rate numeric)
returns integer
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_user text := nullif(btrim(coalesce(p_username, '')), '');
    v_old numeric;
    v_name text;
    v_count int := 0;
    v_res text;
    v_p record;
    v_today date := (now() at time zone 'Asia/Manila')::date;
begin
    perform trimax_private.require_admin(p_admin);
    if (v_user is null) = (p_staff_id is null) then
        raise exception using errcode = 'TM422', message = 'Choose one employee.';
    end if;
    if p_rate is not null and (p_rate < 0 or p_rate > 10000000 or p_rate <> round(p_rate, 2)) then
        raise exception using errcode = 'TM422', message = 'The daily rate must be 0 or more, with at most 2 decimals.';
    end if;
    if p_staff_id is not null then
        select s.daily_rate, s.name into v_old, v_name from public.payroll_staff s where s.staff_id = p_staff_id for update;
        if v_name is null then
            raise exception using errcode = 'TM404', message = 'That employee no longer exists. Refresh the list.';
        end if;
        if v_old is not distinct from p_rate then
            return 0;
        end if;
        update public.payroll_staff s set daily_rate = p_rate, updated_by = p_admin, updated_at = now()
         where s.staff_id = p_staff_id;
    else
        select coalesce(nullif(btrim(u.name), ''), u.username) into v_name from public.users u where u.username = v_user;
        if v_name is null then
            raise exception using errcode = 'TM404', message = 'That employee account no longer exists.';
        end if;
        select r.daily_rate into v_old from public.payroll_account_rates r where r.username = v_user for update;
        if v_old is not distinct from p_rate then
            return 0;
        end if;
        if p_rate is null then
            delete from public.payroll_account_rates r where r.username = v_user;
        else
            insert into public.payroll_account_rates (username, daily_rate, updated_by, updated_at)
            values (v_user, p_rate, p_admin, now())
            on conflict (username) do update set daily_rate = excluded.daily_rate, updated_by = excluded.updated_by,
                                                 updated_at = now();
        end if;
    end if;
    perform trimax_private.audit_as(p_admin, 'payroll.rate', 'employee', coalesce(v_user, 'staff ' || p_staff_id),
        v_name || ': daily rate '
        || case when p_rate is null then 'removed (payroll no longer made from attendance)' else trimax_private.peso(p_rate) end
        || case when v_old is null then '' else ' (was ' || trimax_private.peso(v_old) || ')' end);

    if p_rate is null then
        -- the unpaid automatic records stay as they are, as ordinary records
        update public.payroll_records r set auto = false, updated_by = p_admin, updated_at = now()
         where r.auto and r.status = 'UNPAID'
           and (case when p_staff_id is not null then r.staff_id = p_staff_id else r.employee_username = v_user end);
        get diagnostics v_count = row_count;
        return v_count;
    end if;
    -- the current period, and any later unpaid automatic one, with the new rate
    for v_p in
        select distinct d from (select v_today as d
                                union all
                                select r.period_start from public.payroll_records r
                                 where r.auto and r.status = 'UNPAID' and r.period_end >= v_today
                                   and (case when p_staff_id is not null then r.staff_id = p_staff_id
                                             else r.employee_username = v_user end)) x
    loop
        v_res := trimax_private.sync_payroll(p_admin, v_user, p_staff_id, v_p.d);
        if v_res in ('created', 'updated') then
            v_count := v_count + 1;
        end if;
    end loop;
    return v_count;
end $function$;

-- ===================================================================================== 6. COUNT EVERYONE'S PERIOD
create or replace function trimax_private.sync_payroll_all(p_admin text, p_day date)
returns jsonb
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_e record;
    v_res text;
    v_created int := 0;
    v_updated int := 0;
    v_paid int := 0;
    v_problems text[] := '{}';
    v_ps date;
    v_pe date;
    v_name text;
    v_other text;
begin
    perform trimax_private.require_admin(p_admin);
    select p.period_start, p.period_end into v_ps, v_pe from trimax_private.pay_period(p_day) p;
    if p_day is null or p_day > (now() at time zone 'Asia/Manila')::date + 31 then
        raise exception using errcode = 'TM422', message = 'Choose a day in the pay period.';
    end if;
    for v_e in
        select r.username as u, null::bigint as s from public.payroll_account_rates r
        union all
        select null, s.staff_id from public.payroll_staff s where s.daily_rate is not null
    loop
        begin
            v_res := trimax_private.sync_payroll(p_admin, v_e.u, v_e.s, p_day);
        exception when others then
            v_problems := v_problems || sqlerrm;
            continue;
        end;
        if v_res = 'created' then v_created := v_created + 1;
        elsif v_res = 'updated' then v_updated := v_updated + 1;
        elsif v_res = 'paid' then v_paid := v_paid + 1;
        elsif v_res = 'overlap' then
            if v_e.s is not null then
                select s.name into v_name from public.payroll_staff s where s.staff_id = v_e.s;
            else
                select coalesce(nullif(btrim(u.name), ''), u.username) into v_name from public.users u where u.username = v_e.u;
            end if;
            select '#' || r.payroll_id || ' (' || r.period_start || ' to ' || r.period_end || ', ' || lower(r.status) || ')'
              into v_other
              from public.payroll_records r
             where r.status <> 'VOID' and r.period_start <= v_pe and r.period_end >= v_ps
               and case when v_e.s is not null
                        then r.staff_id = v_e.s
                             or (r.staff_id is null and r.employee_username is null and lower(r.employee_name) = lower(v_name))
                        else r.employee_username = v_e.u end
             order by r.payroll_id limit 1;
            v_problems := v_problems || (v_name || ': no weekly payroll for ' || v_ps || ' to ' || v_pe || ', because record '
                                         || coalesce(v_other, '?') || ' already covers part of that week. Void it if it was typed '
                                         || 'by mistake, and attendance makes the week''s payroll.');
        end if;
    end loop;
    return jsonb_build_object('created', v_created, 'updated', v_updated, 'paid', v_paid,
                              'problems', to_jsonb(v_problems));
end $function$;

-- ===================================================================================== 7. SAVE A DAY, THEN COUNT THE PAYROLL
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
    v_staff bigint;
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

    -- the payroll of that day's period, for everyone in the list who has a daily rate (20261030_02)
    for v_staff in select distinct (e ->> 'staff_id')::bigint from jsonb_array_elements(p_entries) e
    loop
        perform trimax_private.sync_payroll(p_admin, null, v_staff, p_work_date);
    end loop;
    return v_saved + v_cleared;
end $function$;

-- ===================================================================================== 8. END DUTY FROM THE PHONE
create or replace function trimax_private.duty_events_payroll()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_start timestamptz;
    v_day date;
begin
    begin
        -- the session this End closes counts on the day of its Start
        select max(e.recorded_at) into v_start from trimax_private.duty_events e
         where e.username = new.username and e.event_type = 'START' and e.recorded_at <= new.recorded_at;
        v_day := (coalesce(v_start, new.recorded_at) at time zone 'Asia/Manila')::date;
        perform trimax_private.sync_payroll(new.username, new.username, null, v_day);
    exception when others then
        -- never make the End duty fail; Payroll counts again when it opens
        raise warning 'TRIMAX payroll not updated for % (%): %', new.username, v_day, sqlerrm;
    end;
    return null;
end $function$;

drop trigger if exists duty_events_payroll on trimax_private.duty_events;
create trigger duty_events_payroll
    after insert on trimax_private.duty_events
    for each row when (new.event_type = 'END')
    execute function trimax_private.duty_events_payroll();

-- ===================================================================================== 9. HISTORY TEXT FIX
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
        || case when p_rate is null then '' else ' x ' || trimax_private.peso(p_rate) end
        || case when v_old.days_worked is not null or v_old.daily_rate is not null
                then ' (was ' || coalesce(trim_scale(v_old.days_worked)::text || ' days', 'no days')
                     || case when v_old.daily_rate is null then '' else ' x ' || trimax_private.peso(v_old.daily_rate) end || ')'
                else '' end);
end $function$;

revoke all on function trimax_private.pay_period(date) from public, anon, authenticated, service_role;
revoke all on function trimax_private.sync_payroll(text, text, bigint, date) from public, anon, authenticated, service_role;
revoke all on function trimax_private.set_employee_rate(text, text, bigint, numeric) from public, anon, authenticated, service_role;
revoke all on function trimax_private.sync_payroll_all(text, date) from public, anon, authenticated, service_role;
revoke all on function trimax_private.save_staff_attendance(text, date, jsonb) from public, anon, authenticated, service_role;
revoke all on function trimax_private.duty_events_payroll() from public, anon, authenticated, service_role;
revoke all on function trimax_private.set_payroll_attendance(text, bigint, numeric, numeric) from public, anon, authenticated, service_role;

commit;
