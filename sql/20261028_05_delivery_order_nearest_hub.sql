-- =====================================================================================================
-- Delivery order: nearest to the TRIMAX Hub first (owner's request of 2026-10-28).
--
-- The driver app numbers a driver's unfinished deliveries ("Seq #1, #2 ...") and shows #1 as the current
-- delivery on its dashboard. Until now that order was: In Progress, then Confirmed, then Pending, each by
-- when it was assigned. Now, inside each of those groups, the delivery NEAREST TO THE TRIMAX HUB comes first:
--   1. deliveries the driver has already started (In Progress / Started / Picked up / On the way / Arrived)
--      stay on top, so the one the driver is driving to never moves away from #1;
--   2. Confirmed deliveries (ready to go), nearest to the hub first;
--   3. Pending deliveries (the Admin still has to confirm them; the driver cannot start them yet),
--      nearest to the hub first.
--   A delivery without a pin comes last in its group. Same distance: the one assigned first.
-- Distance = straight-line distance from the hub pin (trimax_private.hub_location(): TRIMAX Hub,
-- 14.7826996, 121.0475865) to the delivery pin of the booking, or the customer's saved pin when the
-- booking has none (the same pin booking_json gives the phone). It is not road distance.
--
-- 1. trimax_private.hub_distance_m(lat, lon): metres from the hub (null without a pin).
-- 2. trimax_private.delivery_sequence(driver default all): the order above, per driver. Owner only (the
--    Desktop's Bookings page shows it); the driver function below uses it, so both always agree.
-- 3. public.trimax_driver_list_deliveries: same arguments, grants and 'history' list; the 'active' list
--    uses the new order, and each delivery also gets distance_from_hub_m (older apps ignore it). The
--    phone app needs no update: it already shows the server's order and "Seq #".
--
-- ORDER: after 20261028_01 (distance_m). Re-runnable. Changes no row.
-- Rollback: 20261028_95_rollback_delivery_order_nearest_hub.sql (puts back the previous order).
-- APPLIED to production on 2026-10-28 (about 22:36 Asia/Manila) with the owner's approval, as migration
-- 20261028_05_delivery_order_nearest_hub (one transaction).
-- =====================================================================================================

begin;

do $$
begin
    if to_regprocedure('trimax_private.hub_location()') is null
       or to_regprocedure('trimax_private.distance_m(double precision,double precision,double precision,double precision)') is null
       or to_regprocedure('trimax_private.booking_json(integer,boolean)') is null
       or to_regprocedure('trimax_private.require_account(text)') is null then
        raise exception 'Apply 20261023_01, 20261025_01 and 20261028_01 first.';
    end if;
end $$;

-- ----------------------------------------------------------------------------------------------- 1
create or replace function trimax_private.hub_distance_m(p_lat double precision, p_lon double precision)
returns double precision
language sql
immutable
set search_path = public, pg_temp
as $$
    select case when p_lat is null or p_lon is null then null
                else trimax_private.distance_m((trimax_private.hub_location() ->> 'latitude')::double precision,
                                               (trimax_private.hub_location() ->> 'longitude')::double precision,
                                               p_lat, p_lon) end
$$;

-- ----------------------------------------------------------------------------------------------- 2
-- stage: 0 started, 1 Confirmed, 2 Pending. seq: 1, 2, 3 ... per driver.
create or replace function trimax_private.delivery_sequence(p_driver text default null)
returns table (booking_id integer, driver_username text, seq integer, stage integer, distance_m double precision)
language sql
stable
set search_path = public, pg_temp
as $$
    select a.booking_id, a.driver_username,
           (row_number() over (partition by a.driver_username
                               order by a.stage, a.distance_m nulls last, a.since, a.booking_id))::integer,
           a.stage, a.distance_m
      from (select b.booking_id, b.driver_username,
                   case when b.status = 'In Progress'
                          or coalesce(b.delivery_status, 'ASSIGNED') in ('STARTED', 'PICKED_UP', 'ON_THE_WAY', 'ARRIVED') then 0
                        when b.status = 'Confirmed' then 1
                        else 2 end as stage,
                   trimax_private.hub_distance_m(
                       case when b.delivery_latitude is not null then b.delivery_latitude else c.latitude end,
                       case when b.delivery_latitude is not null then b.delivery_longitude else c.longitude end) as distance_m,
                   coalesce(b.assigned_at, b.transc_date) as since
              from public.bookings b
              left join public.clients c on c.client_id = b.client_id
             where b.driver_username is not null
               and (p_driver is null or b.driver_username = p_driver)
               and b.status in ('Pending', 'Confirmed', 'In Progress')
               and coalesce(b.delivery_status, 'ASSIGNED') not in ('DELIVERED', 'FAILED')) a
$$;

-- ----------------------------------------------------------------------------------------------- 3
-- The signed-in driver's deliveries. 'active': not finished yet, in delivery order (above);
-- 'history': delivered, failed, completed or cancelled (latest 100), unchanged.
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
            select jsonb_agg(trimax_private.booking_json(s.booking_id, false)
                             || jsonb_build_object('sequence', s.seq,
                                                   'distance_from_hub_m', round(s.distance_m::numeric)::bigint)
                             order by s.seq)
              from trimax_private.delivery_sequence(v_me) s), '[]'::jsonb);
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

revoke all on function trimax_private.hub_distance_m(double precision, double precision) from public, anon, authenticated, service_role;
revoke all on function trimax_private.delivery_sequence(text) from public, anon, authenticated, service_role;
-- unchanged access for the phone function: signed-in users only (the function itself checks the driver)
revoke all on function public.trimax_driver_list_deliveries(text) from public, anon, service_role;
grant execute on function public.trimax_driver_list_deliveries(text) to authenticated;

commit;
