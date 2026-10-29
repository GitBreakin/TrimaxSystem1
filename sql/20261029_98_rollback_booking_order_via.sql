-- =====================================================================================================
-- Rollback of 20261029_02_booking_order_via.sql.
--
-- Refused (and nothing changes) once a booking has an order_via: the choice and the visit photos are
-- business records. Before that it removes the function, the check, the index and the 5 columns.
-- The photos in "customer-photos" are not touched.
-- =====================================================================================================

begin;

do $$
begin
    if exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'bookings'
               and column_name = 'order_via')
       and exists (select 1 from public.bookings where order_via is not null) then
        raise exception 'Not rolled back: bookings already record Visit or Text / Call. Nothing was changed.';
    end if;
end $$;

drop function if exists public.trimax_agent_create_booking_v2(uuid, text, text, text, double precision,
    double precision, jsonb, text, text, text, text, double precision, double precision, timestamptz);
drop index if exists public.bookings_visit_photo_key;
alter table public.bookings drop constraint if exists bookings_order_via_chk;
alter table public.bookings drop column if exists visit_taken_at;
alter table public.bookings drop column if exists visit_longitude;
alter table public.bookings drop column if exists visit_latitude;
alter table public.bookings drop column if exists visit_photo_path;
alter table public.bookings drop column if exists order_via;

commit;
