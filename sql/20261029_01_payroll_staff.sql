-- =====================================================================================================
-- Payroll for employees without an app account (owner's request of 2026-10-29): helpers, cashiers, guards...
-- "hindi lang driver at agent pinapasahod nila ... lagay ka don ng add employee ... para mabawas rin sa profit".
--
-- Payroll could already be saved for a typed name; this gives those employees a list of their own:
--   1. public.payroll_staff: name (unique, any case), position, contact number, active (owner only: RLS on, no grants).
--   2. trimax_private.save_payroll_staff(admin, staff_id, name, position, contact, active): Admin only, audited.
--   3. payroll_records.staff_id: which employee a record is for (an account OR an employee without one, never both).
--   4. save_payroll(..., p_staff_id bigint): 15 arguments (no default); the 14-argument version older Desktops call
--      becomes a wrapper and keeps an existing record's employee link.
-- Payroll already lowers net profit in Sales & Profit (by the end of its pay period); that is unchanged.
--
-- ORDER: after 20261027_01. Re-runnable. Rollback: 20261029_99_rollback_payroll_staff.sql.
-- NOT APPLIED to production. Needs the owner's approval first.
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.save_payroll(text,bigint,uuid,text,text,date,date,numeric,numeric,numeric,text,date,text,text)') is null then
        raise exception 'Apply 20261027_01 first.';
    end if;
end $$;

-- ===================================================================================== 1. EMPLOYEES WITHOUT AN ACCOUNT
create table if not exists public.payroll_staff (
    staff_id bigint generated always as identity primary key,
    name text not null,
    position text,
    contact_num text,
    active boolean not null default true,
    created_by text references public.users (username) on update cascade on delete set null,
    created_at timestamptz not null default now(),
    updated_by text references public.users (username) on update cascade on delete set null,
    updated_at timestamptz,
    constraint payroll_staff_name_chk check (char_length(btrim(name)) between 2 and 100),
    constraint payroll_staff_position_chk check (position is null or char_length(position) between 1 and 50),
    constraint payroll_staff_contact_chk check (contact_num is null or contact_num ~ '^09[0-9]{9}$')
);
create unique index if not exists payroll_staff_name_uq on public.payroll_staff (lower(btrim(name)));
create index if not exists payroll_staff_created_by_idx on public.payroll_staff (created_by);
create index if not exists payroll_staff_updated_by_idx on public.payroll_staff (updated_by);
comment on table public.payroll_staff is
    'Employees without an app account (helpers, cashiers...), for payroll. Desktop only (owner); not reachable from the API.';
alter table public.payroll_staff enable row level security;
revoke all on table public.payroll_staff from public, anon, authenticated, service_role;
revoke all on sequence public.payroll_staff_staff_id_seq from public, anon, authenticated, service_role;

alter table public.payroll_records add column if not exists staff_id bigint
    references public.payroll_staff (staff_id) on delete set null;
create index if not exists payroll_records_staff_idx on public.payroll_records (staff_id) where staff_id is not null;
alter table public.payroll_records drop constraint if exists payroll_records_one_employee_chk;
alter table public.payroll_records add constraint payroll_records_one_employee_chk
    check (staff_id is null or employee_username is null);
comment on column public.payroll_records.staff_id is 'The employee without an account this record is for (payroll_staff), or null.';

-- ===================================================================================== 2. ADD / EDIT AN EMPLOYEE
create or replace function trimax_private.save_payroll_staff(
    p_admin text, p_staff_id bigint, p_name text, p_position text, p_contact text, p_active boolean)
returns bigint
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_name text := regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g');
    v_pos text := nullif(regexp_replace(btrim(coalesce(p_position, '')), '\s+', ' ', 'g'), '');
    v_contact text := nullif(regexp_replace(coalesce(p_contact, ''), '[\s-]', '', 'g'), '');
    v_old public.payroll_staff;
    v_new public.payroll_staff;
    v_changes text := '';
begin
    perform trimax_private.require_admin(p_admin);
    if char_length(v_name) < 2 or char_length(v_name) > 100 then
        raise exception using errcode = 'TM422', message = 'Enter the employee''s full name (2 to 100 characters).';
    end if;
    if v_pos is not null and char_length(v_pos) > 50 then
        raise exception using errcode = 'TM422', message = 'The position is too long (up to 50 characters).';
    end if;
    if v_contact is not null and v_contact !~ '^09[0-9]{9}$' then
        raise exception using errcode = 'TM422',
            message = 'Contact number must be 11 digits and start with 09 (for example, 09123456789).';
    end if;
    begin
        if p_staff_id is null then
            insert into public.payroll_staff (name, position, contact_num, active, created_by)
            values (v_name, v_pos, v_contact, coalesce(p_active, true), p_admin)
            returning * into v_new;
            perform trimax_private.audit_as(p_admin, 'payroll.staff_add', 'payroll_staff', v_new.staff_id::text,
                v_name || coalesce(' (' || v_pos || ')', '') || ' added to the employees without an account');
            return v_new.staff_id;
        end if;
        select * into v_old from public.payroll_staff s where s.staff_id = p_staff_id for update;
        if not found then
            raise exception using errcode = 'TM404', message = 'That employee no longer exists. Refresh the employee list.';
        end if;
        update public.payroll_staff s
           set name = v_name, position = v_pos, contact_num = v_contact, active = coalesce(p_active, v_old.active),
               updated_by = p_admin, updated_at = now()
         where s.staff_id = p_staff_id
        returning * into v_new;
    exception when unique_violation then
        raise exception using errcode = 'TM409', message = 'There is already an employee named "' || v_name || '".';
    end;
    if v_old.name is distinct from v_new.name then v_changes := v_changes || ', name ' || v_old.name || ' -> ' || v_new.name; end if;
    if v_old.position is distinct from v_new.position then
        v_changes := v_changes || ', position ' || coalesce(v_old.position, '-') || ' -> ' || coalesce(v_new.position, '-');
    end if;
    if v_old.contact_num is distinct from v_new.contact_num then v_changes := v_changes || ', contact number changed'; end if;
    if v_old.active is distinct from v_new.active then
        v_changes := v_changes || case when v_new.active then ', working here again' else ', no longer working here' end;
    end if;
    if v_changes <> '' then
        perform trimax_private.audit_as(p_admin, 'payroll.staff_update', 'payroll_staff', p_staff_id::text,
            v_new.name || ':' || substr(v_changes, 2));
    end if;
    return v_new.staff_id;
end $function$;

-- ===================================================================================== 3. PAYROLL FOR THEM
create or replace function trimax_private.save_payroll(p_admin text, p_payroll_id bigint, p_request_id uuid, p_employee_username text, p_employee_name text, p_period_start date, p_period_end date, p_basic_pay numeric, p_allowances numeric, p_deductions numeric, p_status text, p_paid_at date, p_payment_method text, p_notes text, p_staff_id bigint)
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
    v_staff public.payroll_staff;
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
    if v_user is not null and p_staff_id is not null then
        raise exception using errcode = 'TM422', message = 'Choose one employee: an account or an employee without one.';
    end if;
    if p_staff_id is not null then
        select * into v_staff from public.payroll_staff s where s.staff_id = p_staff_id;
        if not found then
            raise exception using errcode = 'TM404', message = 'That employee no longer exists. Refresh the employee list.';
        end if;
        if not v_staff.active and (p_payroll_id is null
               or (select r.staff_id from public.payroll_records r where r.payroll_id = p_payroll_id) is distinct from p_staff_id) then
            raise exception using errcode = 'TM422',
                message = v_staff.name || ' is marked as no longer working here. Set them active again in Employees first.';
        end if;
        v_name := v_staff.name;
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
                                                created_by, client_request_id, staff_id)
            values (v_user, v_name, p_period_start, p_period_end, p_basic_pay, v_allow, v_ded, v_status,
                    case when v_status = 'PAID' then p_paid_at end, case when v_status = 'PAID' then v_method end,
                    v_notes, p_admin, p_request_id, p_staff_id)
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
               staff_id = p_staff_id, updated_by = p_admin, updated_at = now()
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

create or replace function trimax_private.save_payroll(
    p_admin text, p_payroll_id bigint, p_request_id uuid, p_employee_username text, p_employee_name text,
    p_period_start date, p_period_end date, p_basic_pay numeric, p_allowances numeric, p_deductions numeric,
    p_status text, p_paid_at date, p_payment_method text, p_notes text)
returns bigint
language plpgsql
set search_path = public, pg_temp
as $function$
declare
    v_staff bigint;
begin
    -- The 14-argument version older Desktops call. Editing a record of an employee without an account keeps its
    -- link to that employee, as long as the name was not changed.
    if p_payroll_id is not null and nullif(btrim(p_employee_username), '') is null then
        select r.staff_id into v_staff from public.payroll_records r
         where r.payroll_id = p_payroll_id and lower(r.employee_name) = lower(btrim(coalesce(p_employee_name, '')));
    end if;
    return trimax_private.save_payroll(p_admin, p_payroll_id, p_request_id, p_employee_username, p_employee_name,
        p_period_start, p_period_end, p_basic_pay, p_allowances, p_deductions, p_status, p_paid_at,
        p_payment_method, p_notes, v_staff);
end $function$;

revoke all on function trimax_private.save_payroll_staff(text, bigint, text, text, text, boolean) from public, anon, authenticated, service_role;
revoke all on function trimax_private.save_payroll(text, bigint, uuid, text, text, date, date, numeric, numeric, numeric, text, date, text, text, bigint) from public, anon, authenticated, service_role;
revoke all on function trimax_private.save_payroll(text, bigint, uuid, text, text, date, date, numeric, numeric, numeric, text, date, text, text) from public, anon, authenticated, service_role;

commit;
