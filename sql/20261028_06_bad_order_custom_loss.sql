-- =====================================================================================================
-- Bad orders: the Admin can set the loss in pesos (owner's request of 2026-10-28).
--
-- Why: the loss of a bad order is pieces x puhunan, but often only PART of one item is bad, for example one
-- bottle of "Sprite Mismo - 1 pack (6pcs)", worth about P10, while the puhunan is per pack.
--
-- Rules confirmed by the owner:
--   * "Other amount": the Admin types the loss in pesos (more than 0, at most 2 decimals). It is used
--     instead of pieces x puhunan and lowers net profit the same way (by the date found).
--   * With "Other amount" the pieces may be 0: only part of one item was bad, so the STOCK DOES NOT CHANGE
--     (no stock-history line); only the peso loss is recorded. With 1 or more pieces the stock goes down
--     as before.
--   * Without "Other amount" nothing changes: 1 or more pieces, loss = pieces x puhunan (none without
--     puhunan).
--   * Undo works for both; a bad order of 0 pieces puts no stock back.
--
-- What this changes (keeps every row; existing bad orders become loss_source 'PUHUNAN', as they are):
--   1. public.bad_orders: + loss_source ('PUHUNAN' | 'CUSTOM'); pieces may be 0 only with 'CUSTOM'; a
--      'CUSTOM' loss is more than 0 even without puhunan.
--   2. trimax_private.record_bad_order(..., p_custom_loss): the 9-argument version does the work. The
--      8-argument version stays (it calls the 9-argument one without an amount), so a Desktop built before
--      this update keeps finding and calling it exactly as before.
--   3. trimax_private.void_bad_order: a bad order of 0 pieces is undone without a stock change.
-- Reports (profit_summary / profit_trend) already add loss_amount: unchanged.
--
-- ORDER: after 20261028_04. Re-runnable. Rollback: 20261028_96_rollback_bad_order_custom_loss.sql.
-- APPLIED to production on 2026-10-28 (about 23:00 Asia/Manila) with the owner's approval, as migration
-- 20261028_06_bad_order_custom_loss (one transaction).
-- =====================================================================================================

begin;

do $$
begin
    if to_regclass('public.bad_orders') is null
       or to_regprocedure('trimax_private.void_bad_order(text,bigint,text)') is null then
        raise exception 'Apply 20261028_04_bad_orders.sql first.';
    end if;
end $$;

-- ----------------------------------------------------------------------------------------------- 1
alter table public.bad_orders add column if not exists loss_source text not null default 'PUHUNAN';
alter table public.bad_orders drop constraint if exists bad_orders_loss_source_check;
alter table public.bad_orders add constraint bad_orders_loss_source_check check (loss_source in ('PUHUNAN', 'CUSTOM'));
alter table public.bad_orders drop constraint if exists bad_orders_quantity_check;
alter table public.bad_orders add constraint bad_orders_quantity_check
    check (quantity between 0 and 1000000 and (quantity >= 1 or loss_source = 'CUSTOM'));
alter table public.bad_orders drop constraint if exists bad_orders_loss_chk;
alter table public.bad_orders add constraint bad_orders_loss_chk
    check (case when loss_source = 'CUSTOM' then loss_amount > 0
                else (unit_cost is null) = (loss_amount is null) end);
comment on column public.bad_orders.loss_source is
    'PUHUNAN: loss = quantity x unit_cost. CUSTOM: the Admin typed loss_amount (quantity may be 0: part of one item, stock unchanged).';

-- ----------------------------------------------------------------------------------------------- 2
-- No default on p_custom_loss: an 8-argument call must keep going to the 8-argument version (below).
drop function if exists trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid, numeric);
create function trimax_private.record_bad_order(
    p_admin text, p_product_id integer, p_quantity integer, p_reason text, p_disposition text,
    p_found_on date, p_note text, p_request_id uuid, p_custom_loss numeric)
returns table (bad_order_id bigint, stock_before integer, stock_after integer, loss_amount numeric, already_existed boolean)
language plpgsql
set search_path = public, pg_temp
as $function$
#variable_conflict use_column
declare
    v_reason text := upper(btrim(coalesce(p_reason, '')));
    v_disp text := upper(btrim(coalesce(p_disposition, '')));
    v_note text := nullif(btrim(p_note), '');
    v_today date := (now() at time zone 'Asia/Manila')::date;
    v_found date := coalesce(p_found_on, (now() at time zone 'Asia/Manila')::date);
    v_custom boolean := p_custom_loss is not null;
    v_loss numeric;
    v_product public.products;
    v_bo public.bad_orders;
    v_mov public.inventory_movements;
    v_before integer;
    v_after integer;
begin
    perform trimax_private.require_admin(p_admin);
    if p_request_id is null then
        raise exception using errcode = 'TM422', message = 'Missing request id.';
    end if;
    perform pg_advisory_xact_lock(hashtextextended('trimax-stock-request:' || p_request_id::text, 0));
    select * into v_bo from public.bad_orders b where b.client_request_id = p_request_id;
    if found then
        if v_bo.product_id is distinct from p_product_id or v_bo.quantity <> p_quantity then
            raise exception using errcode = 'TM409', message = 'This request id has already been used.';
        end if;
        if v_bo.movement_id is not null then
            select * into v_mov from public.inventory_movements m where m.movement_id = v_bo.movement_id;
            return query select v_bo.bad_order_id, v_mov.stock_before, v_mov.stock_after, v_bo.loss_amount, true;
        else
            return query select v_bo.bad_order_id, p.stock, p.stock, v_bo.loss_amount, true
                           from public.products p where p.product_id = v_bo.product_id;
        end if;
        return;
    end if;
    if v_custom then
        if p_custom_loss <= 0 or p_custom_loss > 10000000 or p_custom_loss <> round(p_custom_loss, 2) then
            raise exception using errcode = 'TM422', message = 'The amount to deduct must be more than 0, with at most 2 decimals.';
        end if;
        if p_quantity is null or p_quantity < 0 or p_quantity > 1000000 then
            raise exception using errcode = 'TM422', message = 'Enter how many pieces leave the stock (0 if only part of one item).';
        end if;
    elsif p_quantity is null or p_quantity < 1 or p_quantity > 1000000 then
        raise exception using errcode = 'TM422', message = 'Enter how many pieces are bad (1 or more).';
    end if;
    if v_reason not in ('BROKEN', 'DAMAGED', 'EXPIRED', 'DEFECTIVE', 'SPOILED', 'OTHER') then
        raise exception using errcode = 'TM422', message = 'Choose the reason (broken, damaged, expired, defective, spoiled or other).';
    end if;
    if v_disp not in ('DISPOSED', 'RETURNED_TO_SUPPLIER') then
        raise exception using errcode = 'TM422', message = 'Choose what happened to it (thrown away or returned to supplier).';
    end if;
    if v_reason = 'OTHER' and char_length(coalesce(v_note, '')) < 3 then
        raise exception using errcode = 'TM422', message = 'Describe the reason in the note (3 to 300 characters).';
    end if;
    if char_length(coalesce(v_note, '')) > 300 then
        raise exception using errcode = 'TM422', message = 'The note is too long (up to 300 characters).';
    end if;
    if v_found > v_today or v_found < v_today - 366 then
        raise exception using errcode = 'TM422', message = 'The date found must be today or within the last year.';
    end if;
    select * into v_product from public.products p where p.product_id = p_product_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'That product no longer exists.';
    end if;
    if p_quantity > v_product.stock then
        raise exception using errcode = 'TM409',
            message = 'Only ' || v_product.stock || ' in stock: a bad order cannot take out more than that.';
    end if;
    v_loss := case when v_custom then p_custom_loss else round(p_quantity * v_product.capital_cost, 2) end;

    insert into public.bad_orders (product_id, product_name, supplier, quantity, reason, disposition, found_on,
                                   unit_cost, loss_amount, loss_source, note, recorded_by, client_request_id)
    values (v_product.product_id, v_product.product_name, v_product.supplier, p_quantity, v_reason, v_disp, v_found,
            v_product.capital_cost, v_loss, case when v_custom then 'CUSTOM' else 'PUHUNAN' end, v_note, p_admin, p_request_id)
    returning * into v_bo;

    if p_quantity > 0 then
        perform trimax_private.set_stock_context('BAD_ORDER', 'bad_order', v_bo.bad_order_id::text, p_admin,
            'Bad order: ' || lower(trimax_private.bad_order_label(v_reason))
            || case when v_disp = 'RETURNED_TO_SUPPLIER' then ', returned to supplier' else ', thrown away' end
            || coalesce(' - ' || v_note, ''),
            v_product.capital_cost, p_request_id);
        update public.products p set stock = p.stock - p_quantity where p.product_id = p_product_id;
        perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
        select * into v_mov from public.inventory_movements m where m.client_request_id = p_request_id;
        update public.bad_orders b set movement_id = v_mov.movement_id where b.bad_order_id = v_bo.bad_order_id;
        v_before := v_mov.stock_before;
        v_after := v_mov.stock_after;
    else
        v_before := v_product.stock;
        v_after := v_product.stock;
    end if;

    perform trimax_private.audit_as(p_admin, 'inventory.bad_order', 'product', p_product_id::text,
        v_product.product_name || ': '
        || case when p_quantity = 0 then 'part of one item' else p_quantity::text end
        || ' ' || lower(trimax_private.bad_order_label(v_reason))
        || ' (found ' || to_char(v_found, 'Mon DD, YYYY') || ', '
        || case when v_disp = 'RETURNED_TO_SUPPLIER' then 'returned to supplier' else 'thrown away' end || '), '
        || case when p_quantity = 0 then 'stock unchanged (' || v_before || ')' else 'stock ' || v_before || ' -> ' || v_after end
        || case when v_custom then ', loss set by hand ' || trimax_private.peso(v_loss)
                else coalesce(', loss ' || trimax_private.peso(v_loss), ', no puhunan') end
        || coalesce(' - ' || v_note, ''));
    return query select v_bo.bad_order_id, v_before, v_after, v_loss, false;
end $function$;

-- The 8-argument version (as in 20261028_04, used by Desktops built before this update): no amount.
create or replace function trimax_private.record_bad_order(
    p_admin text, p_product_id integer, p_quantity integer, p_reason text, p_disposition text,
    p_found_on date, p_note text, p_request_id uuid)
returns table (bad_order_id bigint, stock_before integer, stock_after integer, loss_amount numeric, already_existed boolean)
language sql
set search_path = public, pg_temp
as $function$
    select * from trimax_private.record_bad_order(p_admin, p_product_id, p_quantity, p_reason, p_disposition,
                                                   p_found_on, p_note, p_request_id, null::numeric)
$function$;

-- ----------------------------------------------------------------------------------------------- 3
create or replace function trimax_private.void_bad_order(p_admin text, p_bad_order_id bigint, p_reason text)
returns table (stock_before integer, stock_after integer)
language plpgsql
set search_path = public, pg_temp
as $function$
#variable_conflict use_column
declare
    v_reason text := nullif(btrim(p_reason), '');
    v_bo public.bad_orders;
    v_before integer;
    v_after integer;
begin
    perform trimax_private.require_admin(p_admin);
    if v_reason is null or char_length(v_reason) < 3 or char_length(v_reason) > 300 then
        raise exception using errcode = 'TM422', message = 'Enter why it is undone (3 to 300 characters).';
    end if;
    select * into v_bo from public.bad_orders b where b.bad_order_id = p_bad_order_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'Bad order not found.';
    end if;
    if v_bo.voided_at is not null then
        raise exception using errcode = 'TM409', message = 'This bad order was already undone.';
    end if;
    if v_bo.quantity > 0 then
        if v_bo.product_id is null then
            raise exception using errcode = 'TM409', message = 'The product was deleted, so its stock cannot be put back.';
        end if;
        perform 1 from public.products p where p.product_id = v_bo.product_id for update;
        perform trimax_private.set_stock_context('BAD_ORDER_VOIDED', 'bad_order', v_bo.bad_order_id::text, p_admin,
            'Bad order undone: ' || v_reason, v_bo.unit_cost, null);
        update public.products p set stock = p.stock + v_bo.quantity where p.product_id = v_bo.product_id
        returning p.stock - v_bo.quantity, p.stock into v_before, v_after;
        perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
    else
        -- part of one item: no stock was taken out, so none is put back
        select p.stock, p.stock into v_before, v_after from public.products p where p.product_id = v_bo.product_id;
    end if;
    update public.bad_orders b set voided_at = now(), voided_by = p_admin, void_reason = v_reason
     where b.bad_order_id = p_bad_order_id;
    perform trimax_private.audit_as(p_admin, 'inventory.bad_order_void', 'product', coalesce(v_bo.product_id::text, ''),
        v_bo.product_name || ': bad order #' || v_bo.bad_order_id || ' of '
        || case when v_bo.quantity = 0 then 'part of one item' else v_bo.quantity::text end || ' undone, '
        || case when v_bo.quantity = 0 then 'stock unchanged' else 'stock ' || v_before || ' -> ' || v_after end
        || ' (' || v_reason || ')');
    return query select v_before, v_after;
end $function$;

revoke all on function trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid, numeric) from public, anon, authenticated, service_role;
revoke all on function trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid) from public, anon, authenticated, service_role;
revoke all on function trimax_private.void_bad_order(text, bigint, text) from public, anon, authenticated, service_role;

commit;
