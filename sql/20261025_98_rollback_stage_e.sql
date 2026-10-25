-- =====================================================================================================
-- Rollback for 20261025_04_delivery_proofs.sql (Stage E).
--
-- PART A keeps every row and every photo: removes the phone's function and the upload policies, so
-- phones can no longer add proofs (the app keeps them queued on the phone and retries later).
-- PART B (commented out) also drops public.delivery_proofs, which deletes the record of which photo
-- belongs to which delivery. The photos themselves stay in the "delivery-proofs" bucket until they
-- are deleted from the Supabase dashboard. Run PART B only if you are sure.
-- =====================================================================================================

begin;

-- PART A ------------------------------------------------------------------------------------------
drop function if exists public.trimax_driver_attach_proof(integer, text, text, double precision, double precision,
                                                          timestamptz, uuid);
drop function if exists trimax_private.storage_object_uploaded(text, text, uuid, interval);

do $$
begin
    if to_regclass('storage.objects') is not null then
        drop policy if exists "trimax delivery proof upload own folder" on storage.objects;
        drop policy if exists "trimax delivery proof read own folder" on storage.objects;
    end if;
end $$;

-- PART B (data loss: uncomment deliberately) ------------------------------------------------------
-- drop table if exists public.delivery_proofs;

commit;
