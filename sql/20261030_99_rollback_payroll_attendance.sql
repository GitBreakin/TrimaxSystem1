-- =====================================================================================================
-- Rollback of 20261030_01_payroll_attendance.sql.
--
-- Refused (and nothing changes) once any staff attendance or any payroll days / daily rate is recorded: those
-- are payroll records. Before that it drops the 3 functions, the 2 columns with their checks, and the table.
-- =====================================================================================================

begin;

do $$
begin
    if (to_regclass('public.staff_attendance') is not null
        and exists (select 1 from public.staff_attendance))
       or (exists (select 1 from information_schema.columns where table_schema = 'public'
                    and table_name = 'payroll_records' and column_name = 'days_worked')
           and exists (select 1 from public.payroll_records where days_worked is not null or daily_rate is not null)) then
        raise exception 'Not rolled back: attendance or payroll days are already recorded. Nothing was changed.';
    end if;
end $$;

drop function if exists trimax_private.set_payroll_attendance(text, bigint, numeric, numeric);
drop function if exists trimax_private.attendance_days(text, bigint, date, date);
drop function if exists trimax_private.save_staff_attendance(text, date, jsonb);
alter table public.payroll_records drop constraint if exists payroll_records_daily_rate_chk;
alter table public.payroll_records drop constraint if exists payroll_records_days_chk;
alter table public.payroll_records drop column if exists daily_rate;
alter table public.payroll_records drop column if exists days_worked;
drop table if exists public.staff_attendance;

commit;
