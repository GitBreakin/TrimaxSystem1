-- =====================================================================================================
-- Rollback of 20261030_02_payroll_auto.sql.
--
-- Refused (and nothing changes) once a daily rate is set or an automatic payroll record exists: those are payroll
-- data. Before that it removes the End duty trigger and the 5 new functions, puts back save_staff_attendance and
-- set_payroll_attendance exactly as in 20261030_01, and drops payroll_records.auto, payroll_staff.daily_rate and
-- public.payroll_account_rates.
-- =====================================================================================================

begin;

do $$
begin
    if (to_regclass('public.payroll_account_rates') is not null and exists (select 1 from public.payroll_account_rates))
       or (exists (select 1 from information_schema.columns where table_schema = 'public'
                    and table_name = 'payroll_staff' and column_name = 'daily_rate')
           and exists (select 1 from public.payroll_staff where daily_rate is not null))
       or (exists (select 1 from information_schema.columns where table_schema = 'public'
                    and table_name = 'payroll_records' and column_name = 'auto')
           and exists (select 1 from public.payroll_records where auto)) then
        raise exception 'Not rolled back: daily rates or automatic payroll records already exist. Nothing was changed.';
    end if;
end $$;

drop trigger if exists duty_events_payroll on trimax_private.duty_events;
drop function if exists trimax_private.duty_events_payroll();
drop function if exists trimax_private.sync_payroll_all(text, date);
drop function if exists trimax_private.set_employee_rate(text, text, bigint, numeric);
drop function if exists trimax_private.sync_payroll(text, text, bigint, date);
drop function if exists trimax_private.pay_period(date);

-- exactly as in 20261030_01
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
revoke all on function trimax_private.set_payroll_attendance(text, bigint, numeric, numeric) from public, anon, authenticated, service_role;

alter table public.payroll_records drop column if exists auto;
alter table public.payroll_staff drop constraint if exists payroll_staff_daily_rate_chk;
alter table public.payroll_staff drop column if exists daily_rate;
drop table if exists public.payroll_account_rates;

commit;
