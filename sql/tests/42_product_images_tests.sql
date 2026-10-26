-- TEST ONLY. Run by sql/tests/run_tests.sh after 39_storage_emulation.sql, _05 and _06.
-- Product pictures: who may change them, upload/replace/remove/delete rules, and what the phones see.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set ana  '00000000-0000-0000-0000-00000000a0a0'

-- ------------------------------------------------------------------ schema and privileges (superuser)
select test.ok(exists (select 1 from storage.buckets where id = 'product-images' and public
                       and file_size_limit = 2097152 and allowed_mime_types = array['image/jpeg']),
               'public product-images bucket: JPEG only, 2 MB');
select test.ok(not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects'
                           and coalesce(qual, '') || coalesce(with_check, '') like '%product-images%'),
               'no storage policy lets a phone or PC write product pictures');
select test.ok(not has_function_privilege('authenticated', 'trimax_private.issue_product_image_ticket(text,integer,text,text)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.issue_product_image_ticket(text,integer,text,text)', 'execute')
               and not has_function_privilege('authenticated', 'trimax_private.set_product_image(text,integer,text)', 'execute'),
               'only the database owner (Desktop) can issue picture tickets or set pictures');
select test.expect_error($$update public.products set image_path = 'products/1/../x.jpg' where product_id = 1$$,
                         '23514', 'products.image_path only takes a product picture path');
select test.expect_error($$insert into trimax_private.media_tickets (bucket, object_path, issued_by, action)
                           values ('product-images', 'products/1/a.jpg', 'x', 'view')$$,
                         '23514', 'a product-images ticket must be an upload or a delete');

-- ------------------------------------------------------------------ Desktop (owner)
set role app_owner;
select test.expect_error($$select trimax_private.issue_product_image_ticket('driver.dan', 1, 'upload')$$,
                         'TM403', 'a non-admin cannot get a picture ticket');
select test.expect_error($$select trimax_private.issue_product_image_ticket('marc.admin', 999999, 'upload')$$,
                         'TM404', 'no upload ticket for a product that does not exist');
select test.expect_error($$select trimax_private.issue_product_image_ticket('marc.admin', 1, 'rename')$$,
                         'TM422', 'unknown picture action refused');

select trimax_private.issue_product_image_ticket('marc.admin', 1, 'upload') ->> 'path' as p1 \gset
select test.ok(:'p1' ~ '^products/1/[0-9a-f]{32}\.jpg$', 'upload ticket gets a fresh random path in the product''s folder');
select test.expect_error(format($$select trimax_private.set_product_image('marc.admin', 1, %L)$$, :'p1'),
                         'TM422', 'a picture that was not uploaded cannot be set');
reset role;
insert into storage.objects (bucket_id, name, metadata) values ('product-images', :'p1', '{"size": 50000}');
set role app_owner;
select test.ok(trimax_private.set_product_image('marc.admin', 1, :'p1') is null, 'first picture set (no previous picture)');
select test.ok((select image_path from public.products where product_id = 1) = :'p1', 'product now points at its picture');
select test.ok((select count(*) from public.audit_log where action = 'product.image_set' and target_id = '1') = 1,
               'setting a picture is audited');
select test.expect_error(format($$select trimax_private.issue_product_image_ticket('marc.admin', 1, 'delete', %L)$$, :'p1'),
                         'TM409', 'a picture still in use cannot be deleted');
select test.expect_error(format($$select trimax_private.set_product_image('marc.admin', 2, %L)$$, :'p1'),
                         'TM422', 'a product cannot take another product''s picture');

-- Replace: upload p2, set it, the old one comes back for deletion.
select trimax_private.issue_product_image_ticket('marc.admin', 1, 'upload') ->> 'path' as p2 \gset
reset role;
insert into storage.objects (bucket_id, name, metadata) values ('product-images', :'p2', '{"size": 60000}');
set role app_owner;
select test.ok(trimax_private.set_product_image('marc.admin', 1, :'p2') = :'p1', 'replace returns the previous picture to delete');
select (trimax_private.issue_product_image_ticket('marc.admin', 1, 'delete', :'p1') ->> 'ticket') as del1 \gset
select test.ok(:'del1' is not null, 'the replaced picture can now be deleted');
select test.expect_error(format($$select trimax_private.issue_product_image_ticket('marc.admin', 2, 'delete', %L)$$, :'p1'),
                         'TM422', 'no delete ticket through another product''s folder');
select test.ok(trimax_private.set_product_image('marc.admin', 1, :'p2') is null, 'setting the same picture again changes nothing');
reset role;

-- ------------------------------------------------------------------ Edge Function (service role)
select ticket as up_ticket from trimax_private.media_tickets where action = 'upload' order by issued_at desc limit 1 \gset
set role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', false);
select test.ok((public.trimax_service_redeem_media_ticket(:'del1') ->> 'action') = 'delete'
               and (public.trimax_service_redeem_media_ticket(:'up_ticket') ->> 'action') = 'upload',
               'redeem tells the function what the ticket is for');
reset role;

-- ------------------------------------------------------------------ the phone (agent) sees the picture
set role authenticated;
select test.jwt(:'ana');
select test.ok((select bool_and(p ? 'image_path') from jsonb_array_elements(public.trimax_list_products()) p),
               'every product in the agent''s list carries image_path');
select test.ok((select p ->> 'image_path' from jsonb_array_elements(public.trimax_list_products()) p
                 where (p ->> 'product_id')::int = 1) = :'p2', 'the agent gets the current picture of product 1');
select test.ok((select count(*) from jsonb_array_elements(public.trimax_list_products()) p
                 where p -> 'image_path' = 'null'::jsonb) > 0, 'products without a picture still list (image_path null)');
reset role;

-- ------------------------------------------------------------------ remove
set role app_owner;
select test.ok(trimax_private.set_product_image('marc.admin', 1, null) = :'p2', 'remove returns the picture to delete');
select test.ok((select image_path from public.products where product_id = 1) is null, 'product has no picture after remove');
select test.ok((select count(*) from public.audit_log where action = 'product.image_removed' and target_id = '1') = 1,
               'removing a picture is audited');
reset role;

select 'ALL PRODUCT IMAGE SQL TESTS PASSED';
