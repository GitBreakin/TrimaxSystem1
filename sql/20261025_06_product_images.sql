-- =====================================================================================================
-- Stage F: product (item) images, uploaded by the Desktop admin and shown in the Android app.
--
-- Access model
--   * Product pictures are not private: they go in a PUBLIC bucket "product-images" (read by anyone who
--     knows the URL, through Supabase's CDN), so the phones can show them without signing anything.
--   * Nobody can write to that bucket from a phone or a PC: there is no insert/update/delete policy.
--     Only the Edge Function "trimax-admin-media" writes, with the project's secret key (server-side),
--     and only for a one-time ticket the Desktop got from the database as an Active Admin.
--   * products.image_path holds the object path ("products/<id>/<random>.jpg"); never image bytes.
--     Every upload gets a new random name, so a replaced picture is never served from an old cache.
--
-- Flow (Desktop)
--   upload:  trimax_private.issue_product_image_ticket(admin, product, 'upload')  -> {ticket, path}
--            POST the JPEG to the Edge Function with that ticket
--            trimax_private.set_product_image(admin, product, path)               -> old path (or null)
--            delete the old picture: issue_product_image_ticket(admin, product, 'delete', old path)
--   remove:  set_product_image(admin, product, null), then delete the old picture the same way.
--   A picture still used by a product can never be deleted.
--
-- Also: public.trimax_list_products() now includes image_path (an extra field; older apps ignore it).
--
-- ORDER: after 20261025_05. Re-runnable. Adds 2 nullable columns to products, 1 bucket, 2 functions,
-- widens the ticket table checks, replaces 2 functions (redeem, list products). Changes no rows.
-- APPLIED to production on 2026-10-26 with the owner's approval (migration 20261025_06_product_images);
-- Edge Function trimax-admin-media v2 deployed the same day (version 2, verify_jwt = false).
-- =====================================================================================================

begin;

-- ----------------------------------------------------------------------------------------------- 1
alter table public.products add column if not exists image_path text;
alter table public.products add column if not exists image_updated_at timestamptz;
do $$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.products'::regclass
                   and conname = 'products_image_path_chk') then
        alter table public.products add constraint products_image_path_chk
            check (image_path is null or image_path ~ '^products/[0-9]{1,9}/[A-Za-z0-9_-]{1,80}\.jpg$');
    end if;
end $$;

-- ----------------------------------------------------------------------------------------------- 2
-- Tickets now also cover product pictures, and say what they are for.
alter table trimax_private.media_tickets add column if not exists action text not null default 'view';
alter table trimax_private.media_tickets drop constraint if exists media_tickets_bucket_check;
alter table trimax_private.media_tickets drop constraint if exists media_tickets_object_path_check;
alter table trimax_private.media_tickets drop constraint if exists media_tickets_action_check;
alter table trimax_private.media_tickets add constraint media_tickets_bucket_check
    check (bucket in ('duty-photos', 'delivery-proofs', 'product-images'));
alter table trimax_private.media_tickets add constraint media_tickets_object_path_check
    check (object_path ~ '^([A-Za-z0-9-]{1,64}|products/[0-9]{1,9})/[A-Za-z0-9_.-]{1,120}\.jpg$');
alter table trimax_private.media_tickets add constraint media_tickets_action_check
    check ((action = 'view' and bucket in ('duty-photos', 'delivery-proofs'))
           or (action in ('upload', 'delete') and bucket = 'product-images'));

-- ----------------------------------------------------------------------------------------------- 3
-- Desktop only (database owner). One ticket for one product picture: 'upload' gets a fresh path,
-- 'delete' only for a picture of that product that no product uses any more.
create or replace function trimax_private.issue_product_image_ticket(p_admin_username text, p_product_id integer,
                                                                     p_action text, p_path text default null)
 returns jsonb
 language plpgsql
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_ticket uuid;
    v_path text;
begin
    if not exists (select 1 from public.users u join public.roles r on r.role_id = u.role_id
                    where u.username = p_admin_username and r.role_name = 'Admin' and u.account_status = 'Active') then
        raise exception using errcode = 'TM403', message = 'Only an active Admin can change product pictures.';
    end if;
    if p_product_id is null or p_product_id <= 0 then
        raise exception using errcode = 'TM422', message = 'Choose a product first.';
    end if;

    if p_action = 'upload' then
        if not exists (select 1 from public.products p where p.product_id = p_product_id) then
            raise exception using errcode = 'TM404', message = 'That product no longer exists.';
        end if;
        v_path := 'products/' || p_product_id || '/' || replace(gen_random_uuid()::text, '-', '') || '.jpg';
    elsif p_action = 'delete' then
        v_path := p_path;
        if v_path is null or v_path !~ ('^products/' || p_product_id || '/[A-Za-z0-9_-]{1,80}\.jpg$') then
            raise exception using errcode = 'TM422', message = 'Not a picture of this product.';
        end if;
        if exists (select 1 from public.products p where p.image_path = v_path) then
            raise exception using errcode = 'TM409', message = 'That picture is still used by a product.';
        end if;
    else
        raise exception using errcode = 'TM422', message = 'Unknown picture action.';
    end if;

    delete from trimax_private.media_tickets where issued_at < now() - interval '1 day';
    insert into trimax_private.media_tickets (bucket, object_path, issued_by, action)
    values ('product-images', v_path, p_admin_username, p_action)
    returning ticket into v_ticket;
    return jsonb_build_object('ticket', v_ticket, 'path', v_path);
end $function$;

revoke all on function trimax_private.issue_product_image_ticket(text, integer, text, text) from public;

-- ----------------------------------------------------------------------------------------------- 4
-- Desktop only. Points the product at its new picture (or at none) and returns the previous path,
-- which the Desktop then deletes. A new picture must really be in the bucket first.
create or replace function trimax_private.set_product_image(p_admin_username text, p_product_id integer, p_path text)
 returns text
 language plpgsql
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_old text;
    v_name text;
begin
    if not exists (select 1 from public.users u join public.roles r on r.role_id = u.role_id
                    where u.username = p_admin_username and r.role_name = 'Admin' and u.account_status = 'Active') then
        raise exception using errcode = 'TM403', message = 'Only an active Admin can change product pictures.';
    end if;
    select p.image_path, p.product_name into v_old, v_name from public.products p
     where p.product_id = p_product_id for update;
    if not found then
        raise exception using errcode = 'TM404', message = 'That product no longer exists.';
    end if;
    if p_path is not null then
        if p_path !~ ('^products/' || p_product_id || '/[A-Za-z0-9_-]{1,80}\.jpg$') then
            raise exception using errcode = 'TM422', message = 'Not a picture of this product.';
        end if;
        if to_regclass('storage.objects') is not null
           and not exists (select 1 from storage.objects o where o.bucket_id = 'product-images' and o.name = p_path) then
            raise exception using errcode = 'TM422', message = 'The picture has not been uploaded.';
        end if;
    end if;
    if p_path is not distinct from v_old then
        return null;   -- nothing changes; nothing to delete
    end if;

    update public.products set image_path = p_path, image_updated_at = now() where product_id = p_product_id;
    if to_regclass('public.audit_log') is not null then
        insert into public.audit_log (actor_username, action, target_type, target_id, result, details)
        values (p_admin_username, case when p_path is null then 'product.image_removed' else 'product.image_set' end,
                'product', p_product_id::text, 'success', left(coalesce(v_name, '') || ': ' || coalesce(p_path, 'no picture'), 500));
    end if;
    return v_old;
end $function$;

revoke all on function trimax_private.set_product_image(text, integer, text) from public;

-- ----------------------------------------------------------------------------------------------- 5
-- Same as 20261025_05, plus the ticket's action.
create or replace function public.trimax_service_redeem_media_ticket(p_ticket uuid)
 returns jsonb
 language plpgsql
 security definer
 set search_path to 'public', 'pg_temp'
as $function$
declare
    v_bucket text;
    v_path text;
    v_action text;
begin
    if not (trimax_private.is_service_request() or current_setting('role', true) = 'service_role') then
        raise exception using errcode = 'TM403', message = 'Not allowed.';
    end if;
    update trimax_private.media_tickets t
       set used_at = now()
     where t.ticket = p_ticket and t.used_at is null and t.expires_at > now()
    returning t.bucket, t.object_path, t.action into v_bucket, v_path, v_action;
    if v_bucket is null then
        raise exception using errcode = 'TM403', message = 'This photo link has expired. Open the photo again.';
    end if;
    return jsonb_build_object('bucket', v_bucket, 'path', v_path, 'action', v_action);
end $function$;

revoke all on function public.trimax_service_redeem_media_ticket(uuid) from public, anon, authenticated;
grant execute on function public.trimax_service_redeem_media_ticket(uuid) to service_role;

-- ----------------------------------------------------------------------------------------------- 6
-- Same as production, plus image_path (null when the product has no picture).
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
                   'stock', p.stock,
                   'image_path', p.image_path) order by lower(p.product_name), p.product_id)
        from public.products p), '[]'::jsonb);
end $function$;

-- ----------------------------------------------------------------------------------------------- 7
do $$
begin
    if to_regclass('storage.buckets') is null then
        raise notice 'storage schema not present (not Supabase): skipping the product-images bucket';
        return;
    end if;
    insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
    values ('product-images', 'product-images', true, 2097152, array['image/jpeg'])
    on conflict (id) do nothing;
    -- No storage.objects policies on purpose: only the service role (the Edge Function) writes here.
end $$;

commit;
