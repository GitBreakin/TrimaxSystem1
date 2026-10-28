-- =====================================================================================================
-- Supplier list for the Inventory (owner's request of 2026-10-28): the Admin picks the supplier from a
-- drop-down and can add a new one, instead of typing the name for every product.
--
-- 1. public.suppliers: one row per supplier name. Names are unique ignoring upper/lower case and spaces
--    at the ends ("Pepsi Co." and " pepsi co. " are the same supplier). RLS on, no API grants: only the
--    Desktop (database owner) reads it.
-- 2. Filled once from the supplier names the products already have (no product is changed, and similar
--    names such as "Coca Cola" / "Coca-Cola Company" are kept as they are: the Admin decides).
-- 3. A product saved with a supplier that is not in the list (for example from an older Desktop) adds it to
--    the list (trigger), so the list never misses a supplier in use.
-- 4. trimax_private.save_supplier(admin, name): Active Admin only; returns the existing supplier when the
--    name is already there (no duplicates); audited (supplier.create).
-- products.supplier stays a text column as before, so phones and older Desktops keep working.
--
-- ORDER: after 20261027_04 (independent of 20261028_01). Re-runnable.
-- Rollback: 20261028_92_rollback_suppliers.sql (keeps the list).
-- NOT APPLIED to production. Needs the owner's approval first.
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.require_admin(text)') is null
       or to_regprocedure('trimax_private.audit_as(text,text,text,text,text)') is null then
        raise exception 'Apply 20261027_01_sales_inventory_payroll.sql first.';
    end if;
end $$;

create table if not exists public.suppliers (
    supplier_id  integer generated always as identity primary key,
    name         text not null check (char_length(btrim(name)) between 1 and 120 and name = btrim(name)),
    name_key     text generated always as (lower(name)) stored,
    created_at   timestamptz not null default now(),
    created_by   text references public.users (username) on update cascade on delete set null
);
create unique index if not exists suppliers_name_key on public.suppliers (name_key);
alter table public.suppliers enable row level security;
revoke all on table public.suppliers from public, anon, authenticated, service_role;
comment on table public.suppliers is 'Supplier names the Admin picks from in Inventory (Desktop only).';

insert into public.suppliers (name)
select distinct on (lower(btrim(p.supplier))) btrim(p.supplier)
  from public.products p
 where nullif(btrim(p.supplier), '') is not null and char_length(btrim(p.supplier)) <= 120
 order by lower(btrim(p.supplier)), btrim(p.supplier)
on conflict (name_key) do nothing;

-- Keeps the list complete when a product is saved with a new supplier name.
create or replace function trimax_private.products_add_supplier()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if nullif(btrim(new.supplier), '') is not null and char_length(btrim(new.supplier)) <= 120 then
        insert into public.suppliers (name) values (btrim(new.supplier)) on conflict (name_key) do nothing;
    end if;
    return null;
end $$;

drop trigger if exists trimax_products_add_supplier on public.products;
create trigger trimax_products_add_supplier
    after insert or update of supplier on public.products
    for each row execute function trimax_private.products_add_supplier();

-- Adds a supplier (or returns the one that already has this name). Desktop (database owner) only.
create or replace function trimax_private.save_supplier(p_admin text, p_name text)
returns table (supplier_id integer, name text, created boolean)
language plpgsql
set search_path = public, pg_temp
as $$
#variable_conflict use_column
declare
    v_name text := regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g');
    v_id integer;
begin
    perform trimax_private.require_admin(p_admin);
    if char_length(v_name) < 2 or char_length(v_name) > 120 then
        raise exception using errcode = 'TM422', message = 'Enter a supplier name of 2 to 120 characters.';
    end if;
    select s.supplier_id into v_id from public.suppliers s where s.name_key = lower(v_name);
    if found then
        return query select s.supplier_id, s.name, false from public.suppliers s where s.supplier_id = v_id;
        return;
    end if;
    insert into public.suppliers (name, created_by) values (v_name, p_admin)
    on conflict (name_key) do nothing
    returning suppliers.supplier_id into v_id;
    if v_id is null then   -- added by someone else a moment ago
        return query select s.supplier_id, s.name, false from public.suppliers s where s.name_key = lower(v_name);
        return;
    end if;
    perform trimax_private.audit_as(p_admin, 'supplier.create', 'supplier', v_id::text, 'Added supplier "' || v_name || '"');
    return query select v_id, v_name, true;
end $$;

revoke all on function trimax_private.products_add_supplier() from public;
revoke all on function trimax_private.save_supplier(text, text) from public;

commit;
