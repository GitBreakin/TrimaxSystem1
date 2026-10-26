-- ROLLBACK of 20261027_03_driver_my_vehicle.sql (only if the owner decides to undo it).
-- Removes public.trimax_driver_my_vehicle(). No data is involved. The Driver app then shows
-- "Vehicle details are not available" on the profile instead of the vehicle.

begin;
drop function if exists public.trimax_driver_my_vehicle();
commit;
