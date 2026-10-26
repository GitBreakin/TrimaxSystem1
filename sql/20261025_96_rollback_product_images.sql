-- =====================================================================================================
-- Rollback for 20261025_06_product_images.sql.
--
-- PART A keeps every picture and every products.image_path: removes the Desktop functions, puts the
-- redeem and list-products functions back as in _05 / production, and restores the ticket checks.
-- Phones then simply stop receiving image_path. The bucket and its files stay.
-- PART B (commented out) also drops products.image_path / image_updated_at. Run it only if you are sure.
-- =====================================================================================================

begin;

-- PART A ------------------------------------------------------------------------------------------
drop function if exists trimax_private.issue_product_image_ticket(text, integer, text, text);
drop function if exists trimax_private.set_product_image(text, integer, text);

delete from trimax_private.media_tickets where bucket = 'product-images';
alter table trimax_private.media_tickets drop constraint if exists media_tickets_action_check;
alter table trimax_private.media_tickets drop constraint if exists media_tickets_bucket_check;
alter table trimax_private.media_tickets drop constraint if exists media_tickets_object_path_check;
alter table trimax_private.media_tickets add constraint media_tickets_bucket_check
    check (bucket in ('duty-photos', 'delivery-proofs'));
alter table trimax_private.media_tickets add constraint media_tickets_object_path_check
    check (object_path ~ '^[A-Za-z0-9-]{1,64}/[A-Za-z0-9_.-]{1,120}\.jpg$');
alter table trimax_private.media_tickets drop column if exists action;

create or replace function public.trimax_service_redeem_media_ticket(p_ticket uuid)
 returns jsonb
 language plpgsql
 security definer
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_bucket text;
    v_path text;
begin
    if not (trimax_private.is_service_request() or current_setting('role', true) = 'service_role') then
        raise exception using errcode = 'TM403', message = 'Not allowed.';
    end if;
    update trimax_private.media_tickets t
       set used_at = now()
     where t.ticket = p_ticket and t.used_at is null and t.expires_at > now()
    returning t.bucket, t.object_path into v_bucket, v_path;
    if v_bucket is null then
        raise exception using errcode = 'TM403', message = 'This photo link has expired. Open the photo again.';
    end if;
    return jsonb_build_object('bucket', v_bucket, 'path', v_path);
end $function$;
revoke all on function public.trimax_service_redeem_media_ticket(uuid) from public, anon, authenticated;
grant execute on function public.trimax_service_redeem_media_ticket(uuid) to service_role;

create or replace function public.trimax_list_products()
 returns jsonb
 language plpgsql
 stable security definer
 set search_path to 'public', 'pg_temp'
as $function$
begin
    perform trimax_private.require_account('AGENT');
    return coalesce((
        select jsonb_agg(jsonb_build_object(
                   'product_id', p.product_id,
                   'product_name', p.product_name,
                   'price', p.price,
                   'stock', p.stock) order by lower(p.product_name), p.product_id)
        from public.products p), '[]'::jsonb);
end $function$;

-- PART B (data loss: uncomment deliberately) ------------------------------------------------------
-- alter table public.products drop constraint if exists products_image_path_chk;
-- alter table public.products drop column if exists image_updated_at;
-- alter table public.products drop column if exists image_path;

commit;
