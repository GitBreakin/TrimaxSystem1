-- =====================================================================================================
-- ROLLBACK of 20261028_05_delivery_order_nearest_hub.sql (only if the owner decides to undo it).
-- Puts back trimax_driver_list_deliveries exactly as 20261023_01 made it (In Progress, then Confirmed,
-- then Pending, each by when it was assigned; no distance_from_hub_m) with the same grants, and drops
-- trimax_private.delivery_sequence and hub_distance_m. Changes no row. The Desktop's Bookings page then
-- shows no delivery order or distance (it checks for the functions).
-- =====================================================================================================

begin;

-- ---- previous version (sql/20261023_01_mobile_booking_integration.sql), unchanged
-- The signed-in driver's deliveries. 'active': not finished yet, in working order (In Progress first);
-- 'history': delivered, failed, completed or cancelled (latest 100).
create or replace function public.trimax_driver_list_deliveries(p_scope text default 'active')
returns jsonb
language plpgsql stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_me text := trimax_private.require_account('DRIVER');
begin
    if p_scope = 'active' then
        return coalesce((
            select jsonb_agg(trimax_private.booking_json(x.booking_id, false) || jsonb_build_object('sequence', x.seq) order by x.seq)
            from (select b.booking_id,
                         row_number() over (order by case b.status when 'In Progress' then 0 when 'Confirmed' then 1 else 2 end,
                                                     coalesce(b.assigned_at, b.transc_date), b.booking_id) as seq
                    from public.bookings b
                   where b.driver_username = v_me
                     and b.status in ('Pending', 'Confirmed', 'In Progress')
                     and coalesce(b.delivery_status, 'ASSIGNED') not in ('DELIVERED', 'FAILED')) x), '[]'::jsonb);
    elsif p_scope = 'history' then
        return coalesce((
            select jsonb_agg(trimax_private.booking_json(x.booking_id, false) order by x.changed desc nulls last, x.booking_id desc)
            from (select b.booking_id, coalesce(b.delivery_status_at, b.last_changed_at, b.transc_date) as changed
                    from public.bookings b
                   where b.driver_username = v_me
                     and (b.status in ('Completed', 'Cancelled') or b.delivery_status in ('DELIVERED', 'FAILED'))
                   order by 2 desc nulls last, b.booking_id desc
                   limit 100) x), '[]'::jsonb);
    end if;
    raise exception using errcode = 'TM422', message = 'Unknown list: ' || coalesce(p_scope, 'null');
end $$;

revoke all on function public.trimax_driver_list_deliveries(text) from public, anon, service_role;
grant execute on function public.trimax_driver_list_deliveries(text) to authenticated;

drop function if exists trimax_private.delivery_sequence(text);
drop function if exists trimax_private.hub_distance_m(double precision, double precision);

commit;
