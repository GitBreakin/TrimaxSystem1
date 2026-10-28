-- =====================================================================================================
-- ROLLBACK of 20261028_06_bad_order_custom_loss.sql (only if the owner decides to undo it).
-- Puts back record_bad_order (8 arguments) and void_bad_order exactly as 20261028_04 made them, so new
-- bad orders are again pieces x puhunan with 1 or more pieces. KEEPS every bad order, including those with
-- a loss set by hand or 0 pieces, the loss_source column and the wider checks that allow them (tightening
-- them again would fail on those rows). Reports keep counting their loss. Changes no row.
-- =====================================================================================================

begin;

drop function if exists trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid, numeric);

-- ---- previous versions (sql/20261028_04_bad_orders.sql, section 3), unchanged. The 8-argument function
-- became an SQL wrapper in 20261028_06; it is dropped and created again as the full plpgsql version.
drop function if exists trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid);
create or replace function trimax_private.record_bad_order(
    p_admin text, p_product_id integer, p_quantity integer, p_reason text, p_disposition text,
    p_found_on date, p_note text, p_request_id uuid)
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
    v_product public.products;
    v_bo public.bad_orders;
    v_mov public.inventory_movements;
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
        select * into v_mov from public.inventory_movements m where m.movement_id = v_bo.movement_id;
        return query select v_bo.bad_order_id, v_mov.stock_before, v_mov.stock_after, v_bo.loss_amount, true;
        return;
    end if;
    if p_quantity is null or p_quantity < 1 or p_quantity > 1000000 then
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

    insert into public.bad_orders (product_id, product_name, supplier, quantity, reason, disposition, found_on,
                                   unit_cost, loss_amount, note, recorded_by, client_request_id)
    values (v_product.product_id, v_product.product_name, v_product.supplier, p_quantity, v_reason, v_disp, v_found,
            v_product.capital_cost, round(p_quantity * v_product.capital_cost, 2), v_note, p_admin, p_request_id)
    returning * into v_bo;

    perform trimax_private.set_stock_context('BAD_ORDER', 'bad_order', v_bo.bad_order_id::text, p_admin,
        'Bad order: ' || lower(trimax_private.bad_order_label(v_reason))
        || case when v_disp = 'RETURNED_TO_SUPPLIER' then ', returned to supplier' else ', thrown away' end
        || coalesce(' - ' || v_note, ''),
        v_product.capital_cost, p_request_id);
    update public.products p set stock = p.stock - p_quantity where p.product_id = p_product_id;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);

    select * into v_mov from public.inventory_movements m where m.client_request_id = p_request_id;
    update public.bad_orders b set movement_id = v_mov.movement_id where b.bad_order_id = v_bo.bad_order_id;

    perform trimax_private.audit_as(p_admin, 'inventory.bad_order', 'product', p_product_id::text,
        v_product.product_name || ': ' || p_quantity || ' ' || lower(trimax_private.bad_order_label(v_reason))
        || ' (found ' || to_char(v_found, 'Mon DD, YYYY') || ', '
        || case when v_disp = 'RETURNED_TO_SUPPLIER' then 'returned to supplier' else 'thrown away' end || '), stock '
        || v_mov.stock_before || ' -> ' || v_mov.stock_after
        || coalesce(', loss ' || trimax_private.peso(v_bo.loss_amount), ', no puhunan') || coalesce(' - ' || v_note, ''));
    return query select v_bo.bad_order_id, v_mov.stock_before, v_mov.stock_after, v_bo.loss_amount, false;
end $function$;

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
    if v_bo.product_id is null then
        raise exception using errcode = 'TM409', message = 'The product was deleted, so its stock cannot be put back.';
    end if;
    perform 1 from public.products p where p.product_id = v_bo.product_id for update;
    perform trimax_private.set_stock_context('BAD_ORDER_VOIDED', 'bad_order', v_bo.bad_order_id::text, p_admin,
        'Bad order undone: ' || v_reason, v_bo.unit_cost, null);
    update public.products p set stock = p.stock + v_bo.quantity where p.product_id = v_bo.product_id
    returning p.stock - v_bo.quantity, p.stock into v_before, v_after;
    perform trimax_private.set_stock_context(null, null, null, null, null, null, null);
    update public.bad_orders b set voided_at = now(), voided_by = p_admin, void_reason = v_reason
     where b.bad_order_id = p_bad_order_id;
    perform trimax_private.audit_as(p_admin, 'inventory.bad_order_void', 'product', v_bo.product_id::text,
        v_bo.product_name || ': bad order #' || v_bo.bad_order_id || ' of ' || v_bo.quantity || ' undone, stock '
        || v_before || ' -> ' || v_after || ' (' || v_reason || ')');
    return query select v_before, v_after;
end $function$;

revoke all on function trimax_private.record_bad_order(text, integer, integer, text, text, date, text, uuid) from public, anon, authenticated, service_role;
revoke all on function trimax_private.void_bad_order(text, bigint, text) from public, anon, authenticated, service_role;

commit;
