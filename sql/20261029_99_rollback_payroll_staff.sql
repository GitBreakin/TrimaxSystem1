-- =====================================================================================================
-- ROLLBACK of 20261029_01_payroll_staff.sql (only if the owner decides to undo it).
-- Puts save_payroll back exactly as it was (14 arguments) and removes the employees-without-an-account list.
-- Payroll records of those employees STAY (with their names); only their link to the list goes.
-- The list itself (names, positions, contact numbers) is deleted: copy it first if it is needed.
-- =====================================================================================================

begin;

drop function if exists trimax_private.save_payroll(text, bigint, uuid, text, text, date, date, numeric, numeric, numeric, text, date, text, text, bigint);
drop function if exists trimax_private.save_payroll_staff(text, bigint, text, text, text, boolean);
alter table public.payroll_records drop constraint if exists payroll_records_one_employee_chk;
drop index if exists public.payroll_records_staff_idx;
alter table public.payroll_records drop column if exists staff_id;
drop table if exists public.payroll_staff;

-- ---- save_payroll as it was before 20261029_01 (from production, 2026-10-29)
CREATE OR REPLACE FUNCTION trimax_private.save_payroll(p_admin text, p_payroll_id bigint, p_request_id uuid, p_employee_username text, p_employee_name text, p_period_start date, p_period_end date, p_basic_pay numeric, p_allowances numeric, p_deductions numeric, p_status text, p_paid_at date, p_payment_method text, p_notes text)
 RETURNS bigint
 LANGUAGE plpgsql
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_user text := nullif(btrim(p_employee_username), '');
    v_name text := nullif(btrim(p_employee_name), '');
    v_status text := upper(btrim(coalesce(p_status, '')));
    v_method text := nullif(upper(btrim(coalesce(p_payment_method, ''))), '');
    v_notes text := nullif(btrim(p_notes), '');
    v_allow numeric := coalesce(p_allowances, 0);
    v_ded numeric := coalesce(p_deductions, 0);
    v_old public.payroll_records;
    v_new public.payroll_records;
    v_other bigint;
begin
    perform trimax_private.require_admin(p_admin);
    if p_payroll_id is null then
        if p_request_id is null then
            raise exception using errcode = 'TM422', message = 'Missing request id.';
        end if;
        perform pg_advisory_xact_lock(hashtextextended('trimax-payroll-request:' || p_request_id::text, 0));
        select r.payroll_id into v_other from public.payroll_records r where r.client_request_id = p_request_id;
        if v_other is not null then
            return v_other;
        end if;
    end if;
    if v_user is not null then
        select u.name into v_name from public.users u where u.username = v_user;
        if not found then
            raise exception using errcode = 'TM404', message = 'That employee account no longer exists.';
        end if;
        v_name := coalesce(nullif(btrim(p_employee_name), ''), v_name, v_user);
    end if;
    if v_name is null or char_length(v_name) > 100 then
        raise exception using errcode = 'TM422', message = 'Choose the employee or enter their name (up to 100 characters).';
    end if;
    if p_period_start is null or p_period_end is null or p_period_end < p_period_start then
        raise exception using errcode = 'TM422', message = 'Enter the pay period (the end cannot be before the start).';
    end if;
    if p_period_end - p_period_start > 92 then
        raise exception using errcode = 'TM422', message = 'A pay period can be at most 3 months.';
    end if;
    if p_period_start < date '2000-01-01' or p_period_end > (now() at time zone 'Asia/Manila')::date + 366 then
        raise exception using errcode = 'TM422', message = 'Check the pay period dates.';
    end if;
    if p_basic_pay is null or p_basic_pay < 0 or v_allow < 0 or v_ded < 0
       or greatest(p_basic_pay, v_allow, v_ded) > 10000000
       or p_basic_pay <> round(p_basic_pay, 2) or v_allow <> round(v_allow, 2) or v_ded <> round(v_ded, 2) then
        raise exception using errcode = 'TM422', message = 'Amounts must be 0 or more, with at most 2 decimals.';
    end if;
    if p_basic_pay + v_allow - v_ded < 0 then
        raise exception using errcode = 'TM422', message = 'Deductions cannot be more than basic pay plus allowances.';
    end if;
    if v_status not in ('UNPAID', 'PAID') then
        raise exception using errcode = 'TM422', message = 'Choose Unpaid or Paid.';
    end if;
    if v_status = 'PAID' then
        if p_paid_at is null or p_paid_at > (now() at time zone 'Asia/Manila')::date + 1 then
            raise exception using errcode = 'TM422', message = 'Enter the date it was paid (not in the future).';
        end if;
        if v_method is not null and v_method not in ('CASH', 'GCASH', 'BANK_TRANSFER', 'OTHER') then
            raise exception using errcode = 'TM422', message = 'Choose how it was paid.';
        end if;
    elsif p_paid_at is not null or v_method is not null then
        raise exception using errcode = 'TM422', message = 'Payment date and method are only for paid records.';
    end if;
    if char_length(coalesce(v_notes, '')) > 500 then
        raise exception using errcode = 'TM422', message = 'The notes are too long (up to 500 characters).';
    end if;

    begin
        if p_payroll_id is null then
            insert into public.payroll_records (employee_username, employee_name, period_start, period_end, basic_pay,
                                                allowances, deductions, status, paid_at, payment_method, notes,
                                                created_by, client_request_id)
            values (v_user, v_name, p_period_start, p_period_end, p_basic_pay, v_allow, v_ded, v_status,
                    case when v_status = 'PAID' then p_paid_at end, case when v_status = 'PAID' then v_method end,
                    v_notes, p_admin, p_request_id)
            returning * into v_new;
            perform trimax_private.audit_as(p_admin, 'payroll.create', 'payroll', v_new.payroll_id::text,
                v_name || ', ' || p_period_start || ' to ' || p_period_end || ': basic ' || trimax_private.peso(p_basic_pay)
                || ', allowances ' || trimax_private.peso(v_allow) || ', deductions ' || trimax_private.peso(v_ded)
                || ', net ' || trimax_private.peso(v_new.net_pay) || ', ' || lower(v_status));
            return v_new.payroll_id;
        end if;

        select * into v_old from public.payroll_records r where r.payroll_id = p_payroll_id for update;
        if not found then
            raise exception using errcode = 'TM404', message = 'Payroll record not found.';
        end if;
        if v_old.status = 'VOID' then
            raise exception using errcode = 'TM409', message = 'A voided payroll record cannot be changed.';
        end if;
        update public.payroll_records r
           set employee_username = v_user, employee_name = v_name, period_start = p_period_start,
               period_end = p_period_end, basic_pay = p_basic_pay, allowances = v_allow, deductions = v_ded,
               status = v_status, paid_at = case when v_status = 'PAID' then p_paid_at end,
               payment_method = case when v_status = 'PAID' then v_method end, notes = v_notes,
               updated_by = p_admin, updated_at = now()
         where r.payroll_id = p_payroll_id
        returning * into v_new;
    exception when unique_violation then
        select r.payroll_id into v_other from public.payroll_records r
         where r.status <> 'VOID' and r.period_start = p_period_start and r.period_end = p_period_end
           and (r.employee_username = v_user or (v_user is null and r.employee_username is null
                                                 and lower(r.employee_name) = lower(v_name)))
         limit 1;
        raise exception using errcode = 'TM409',
            message = 'This employee already has a payroll record for this pay period'
                      || coalesce(' (#' || v_other || ')', '') || '. Edit that one instead.';
    end;
    perform trimax_private.audit_as(p_admin, 'payroll.update', 'payroll', p_payroll_id::text,
        v_new.employee_name || ', ' || v_new.period_start || ' to ' || v_new.period_end || ': net '
        || trimax_private.peso(v_old.net_pay) || ' -> ' || trimax_private.peso(v_new.net_pay)
        || ', ' || lower(v_old.status) || ' -> ' || lower(v_new.status)
        || case when v_old.basic_pay <> v_new.basic_pay or v_old.allowances <> v_new.allowances
                     or v_old.deductions <> v_new.deductions
                then ' (basic ' || trimax_private.peso(v_old.basic_pay) || ' -> ' || trimax_private.peso(v_new.basic_pay)
                     || ', allowances ' || trimax_private.peso(v_old.allowances) || ' -> ' || trimax_private.peso(v_new.allowances)
                     || ', deductions ' || trimax_private.peso(v_old.deductions) || ' -> ' || trimax_private.peso(v_new.deductions) || ')'
                else '' end);
    return v_new.payroll_id;
end $function$;

revoke all on function trimax_private.save_payroll(text, bigint, uuid, text, text, date, date, numeric, numeric, numeric, text, date, text, text) from public, anon, authenticated, service_role;

commit;
