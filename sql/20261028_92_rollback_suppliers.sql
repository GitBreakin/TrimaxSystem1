-- =====================================================================================================
-- ROLLBACK of 20261028_02_suppliers.sql (only if the owner decides to undo it).
-- Drops the trigger and the two functions. KEEPS public.suppliers and its rows (products never depended
-- on it: products.supplier is still the text it always was). The Desktop then lists the suppliers from the
-- products again.
-- =====================================================================================================

begin;
drop trigger if exists trimax_products_add_supplier on public.products;
drop function if exists trimax_private.products_add_supplier();
drop function if exists trimax_private.save_supplier(text, text);
commit;
