#!/usr/bin/env bash
# Runs the mobile-integration SQL tests on a DISPOSABLE local PostgreSQL. Never point it at Supabase.
#
#   PGHOST=127.0.0.1 PGPORT=54329 PGSUPERUSER=postgres sql/tests/run_tests.sh
#
# Needs: a PostgreSQL >= 14 server you can reach as a superuser with trust/peer/password auth, psql.
# Creates (and drops) databases named trimax_it_*.
set -euo pipefail
cd "$(dirname "$0")/.."

SU="${PGSUPERUSER:-postgres}"
DB="trimax_it_$$"
export PGHOST="${PGHOST:-127.0.0.1}" PGPORT="${PGPORT:-5432}"
psql_su()    { psql -X -q -v ON_ERROR_STOP=1 -U "$SU" "$@"; }
psql_owner() { PGPASSWORD=test-only psql -X -q -v ON_ERROR_STOP=1 -U app_owner -d "$DB" "$@"; }

cleanup() { psql_su -d postgres -c "drop database if exists $DB" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# app_owner is cluster-wide; create it once per cluster.
psql_su -d postgres -c "drop database if exists $DB" -c "create database $DB"
if psql_su -d postgres -tAc "select 1 from pg_roles where rolname = 'app_owner'" | grep -q 1; then
    # Reuse the cluster roles from an earlier run: emulation without the role-creating statements.
    grep -vE '^create role (anon|authenticated|service_role|app_owner)' tests/00_supabase_emulation.sql | psql_su -d "$DB"
else
    psql_su -d "$DB" -f tests/00_supabase_emulation.sql
fi

echo "== legacy schema (reconstructed from desktop SQL)"
psql_owner -f tests/01_legacy_schema_reconstructed.sql
echo "== migration, twice (must be re-runnable)"
psql_owner -f 20261023_01_mobile_booking_integration.sql
psql_owner -f 20261023_01_mobile_booking_integration.sql
echo "== functional and security tests"
psql_su -d "$DB" -f tests/10_mobile_integration_tests.sql 2>&1 | sed -n 's/^.*NOTICE:  //p; /ALL SQL TESTS PASSED/p; /ERROR/p; /CONTEXT/p'

echo "== concurrency tests"
bash tests/20_concurrency_tests.sh "$DB" "$SU"

echo "== optional hardening, then the desktop's own statements must still work as the owner"
psql_owner -f 20261023_02_optional_rls_hardening.sql
psql_owner -c "select count(*) from bookings b left join clients c on c.client_id = b.client_id left join users u on u.username = b.driver_username" >/dev/null
psql_owner -c "insert into bookings (client_id, driver_username, vehicle_id) values (1, 'driver.dan', 'TRK-001')" >/dev/null
echo "PASS desktop queries and inserts work with RLS enabled (owner)"

echo "== rollback, then migrate again"
psql_owner -f 20261023_99_rollback_mobile_booking_integration.sql
test "$(psql_owner -tAc "select count(*) from pg_proc where proname like 'trimax%'")" = "0" && echo "PASS rollback removed every trimax function"
test "$(psql_owner -tAc "select count(*) from bookings where created_via = 'mobile'")" -gt 0 && echo "PASS rollback kept the mobile bookings"
psql_owner -c "update bookings set driver_username = 'driver.dee', vehicle_id = 'TRK-002' where booking_id = 1" >/dev/null && echo "PASS desktop assignment works after rollback"
psql_owner -f 20261023_01_mobile_booking_integration.sql
echo "PASS migration re-applies after rollback"

echo "== Stage B: live baseline, search_path fixes, SOS / problem reports (each twice: re-runnable)"
for f in 20261025_01_live_baseline.sql 20261025_02_search_path_and_trigger_privileges.sql 20261025_03_mobile_issue_reports.sql; do
    psql_owner -f "$f"
    psql_owner -f "$f"
done
psql_su -d "$DB" -f tests/30_stage_b_tests.sql 2>&1 | sed -n 's/^.*NOTICE:  //p; /ALL STAGE B SQL TESTS PASSED/p; /ERROR/p; /CONTEXT/p'
echo "== Stage B rollback (part A), then re-apply"
psql_owner -f 20261025_99_rollback_stage_b.sql
test "$(psql_owner -tAc "select count(*) from pg_proc where proname = 'trimax_report_issue'")" = "0" && echo "PASS stage B rollback removed trimax_report_issue"
test "$(psql_owner -tAc "select count(*) from admin_notifications")" -gt 0 && echo "PASS stage B rollback kept the alerts"
psql_owner -f 20261025_02_search_path_and_trigger_privileges.sql
psql_owner -f 20261025_03_mobile_issue_reports.sql
echo "PASS stage B re-applies after rollback"

echo "== Stage E: delivery proof photos (Storage stand-in first; migration twice: re-runnable)"
psql_su -d "$DB" -f tests/39_storage_emulation.sql >/dev/null
psql_owner -f 20261025_04_delivery_proofs.sql
psql_owner -f 20261025_04_delivery_proofs.sql
psql_su -d "$DB" -f tests/40_stage_e_tests.sql 2>&1 | sed -n 's/^.*NOTICE:  //p; /ALL STAGE E SQL TESTS PASSED/p; /ERROR/p; /CONTEXT/p'
echo "== Stage E rollback (part A), then re-apply"
psql_owner -f 20261025_98_rollback_stage_e.sql
test "$(psql_owner -tAc "select count(*) from pg_proc where proname = 'trimax_driver_attach_proof'")" = "0" && echo "PASS stage E rollback removed trimax_driver_attach_proof"
test "$(psql_owner -tAc "select count(*) from pg_policies where policyname like 'trimax delivery proof %'")" = "0" && echo "PASS stage E rollback removed the upload policies"
test "$(psql_owner -tAc "select count(*) from delivery_proofs")" -gt 0 && echo "PASS stage E rollback kept the proofs"
psql_owner -f 20261025_04_delivery_proofs.sql
echo "PASS stage E re-applies after rollback"

echo "== Admin photo tickets (twice: re-runnable)"
psql_owner -f 20261025_05_admin_media_tickets.sql
psql_owner -f 20261025_05_admin_media_tickets.sql
psql_su -d "$DB" -f tests/41_media_tickets_tests.sql 2>&1 | sed -n 's/^.*NOTICE:  //p; /ALL MEDIA TICKET SQL TESTS PASSED/p; /ERROR/p; /CONTEXT/p'
psql_owner -f 20261025_97_rollback_media_tickets.sql
test "$(psql_owner -tAc "select count(*) from pg_proc where proname in ('issue_media_ticket','trimax_service_redeem_media_ticket')")" = "0" && echo "PASS ticket rollback removed both functions"
psql_owner -f 20261025_05_admin_media_tickets.sql
echo "PASS tickets re-apply after rollback"

echo "== Stage F: product images (twice: re-runnable)"
psql_owner -f 20261025_06_product_images.sql
psql_owner -f 20261025_06_product_images.sql
psql_su -d "$DB" -f tests/42_product_images_tests.sql 2>&1 | sed -n 's/^.*NOTICE:  //p; /ALL PRODUCT IMAGE SQL TESTS PASSED/p; /ERROR/p; /CONTEXT/p'
echo "== Stage F rollback (part A), then re-apply"
psql_owner -f 20261025_96_rollback_product_images.sql
test "$(psql_owner -tAc "select count(*) from pg_proc where proname in ('issue_product_image_ticket','set_product_image')")" = "0" && echo "PASS stage F rollback removed the Desktop picture functions"
test "$(psql_owner -tAc "select count(*) from information_schema.columns where table_name = 'products' and column_name = 'image_path'")" = "1" && echo "PASS stage F rollback kept products.image_path"
psql_owner -c "select trimax_private.issue_media_ticket('marc.admin', 'duty-photos', '00000000-0000-0000-0000-00000000d0d0/start_1_aa.jpg')" >/dev/null && echo "PASS duty photo tickets still work after the rollback"
psql_owner -f 20261025_06_product_images.sql
echo "PASS stage F re-applies after rollback"
echo "== Stage G: acknowledge / resolve alerts (twice: re-runnable)"
psql_owner -f 20261025_07_alert_resolution.sql
psql_owner -f 20261025_07_alert_resolution.sql
psql_su -d "$DB" -f tests/43_alert_resolution_tests.sql 2>&1 | sed -n 's/^.*NOTICE:  //p; /ALL ALERT RESOLUTION SQL TESTS PASSED/p; /ERROR/p; /CONTEXT/p'
echo "== Stage G rollback (part A), then re-apply"
psql_owner -f 20261025_95_rollback_alert_resolution.sql
test "$(psql_owner -tAc "select count(*) from pg_proc where proname = 'resolve_alert'")" = "0" && echo "PASS stage G rollback removed resolve_alert"
test "$(psql_owner -tAc "select count(*) from admin_notifications where resolved_at is not null")" -gt 0 && echo "PASS stage G rollback kept the recorded resolutions"
psql_owner -f 20261025_07_alert_resolution.sql
echo "PASS stage G re-applies after rollback"
echo "== Employee mobile sign-in: provisioning tickets and setup codes (twice: re-runnable)"
psql_owner -f 20261026_01_employee_mobile_sign_in.sql
psql_owner -f 20261026_01_employee_mobile_sign_in.sql
psql_su -d "$DB" -f tests/44_employee_mobile_sign_in_tests.sql 2>&1 | sed -n 's/^.*NOTICE:  //p; /ALL EMPLOYEE SIGN-IN SQL TESTS PASSED/p; /ERROR/p; /CONTEXT/p'
echo "== Employee mobile sign-in rollback, then re-apply"
psql_owner -f 20261026_94_rollback_employee_mobile_sign_in.sql
test "$(psql_owner -tAc "select count(*) from pg_proc where proname in ('issue_provisioning_ticket','claim_provisioning_request','trimax_service_claim_provisioning_ticket','trimax_service_use_setup_code')")" = "0" && echo "PASS rollback removed the 4 functions"
test "$(psql_owner -tAc "select count(*) from trimax_private.mobile_accounts where username in ('agent.new','driver.retry')")" = "2" && echo "PASS rollback kept the sign-ins and their links"
test "$(psql_owner -tAc "select count(*) from pg_proc where proname = 'trimax_service_finish_provisioning' and prosrc like '%audit_user(''provisioning''%'")" = "1" && echo "PASS rollback put back the previous finish function"
psql_owner -f 20261026_01_employee_mobile_sign_in.sql
echo "PASS employee sign-in re-applies after rollback"
echo "== Sales, inventory, payroll, agent discounts, customer photos, profit reports (twice: re-runnable)"
psql_owner -f 20261027_01_sales_inventory_payroll.sql 2>/dev/null || psql_owner -f 20261027_01_sales_inventory_payroll.sql
psql_owner -f 20261027_01_sales_inventory_payroll.sql 2>/dev/null || psql_owner -f 20261027_01_sales_inventory_payroll.sql
psql_su -d "$DB" -f tests/45_sales_inventory_payroll_tests.sql 2>&1 | sed -n 's/^.*NOTICE:  //p; /ALL SALES/p; /ERROR/p; /CONTEXT/p'
test "$(psql_owner -tAc "select count(*) from pg_proc where proname = 'save_product'")" = "1"
echo "== Sales, inventory, payroll rollback, then re-apply"
psql_owner -f 20261027_93_rollback_sales_inventory_payroll.sql
test "$(psql_owner -tAc "select count(*) from pg_proc where proname in ('save_product','create_sale','save_payroll','profit_summary','trimax_agent_save_customer','trimax_agent_discount_quote')")" = "0" && echo "PASS rollback removed the new functions"
test "$(psql_owner -tAc "select pronargs from pg_proc where proname = 'trimax_agent_create_booking'")" = "9" && echo "PASS rollback put back the 9-argument trimax_agent_create_booking"
test "$(psql_owner -tAc "select (select count(*) from sales) > 0 and (select count(*) from payroll_records) > 0 and (select count(*) from inventory_movements) > 0")" = "t" && echo "PASS rollback kept sales, payroll and stock history"
before="$(psql_owner -tAc "select count(*) from inventory_movements")"
psql_owner -c "update products set stock = stock + 1 where product_id = (select min(product_id) from products)" >/dev/null
test "$(psql_owner -tAc "select count(*) from inventory_movements")" = "$before" && echo "PASS after rollback stock changes are no longer recorded (trigger removed)"
psql_owner -f 20261027_01_sales_inventory_payroll.sql 2>/dev/null || psql_owner -f 20261027_01_sales_inventory_payroll.sql
test "$(psql_owner -tAc "select pronargs from pg_proc where proname = 'trimax_agent_create_booking'")" = "11" && echo "PASS sales/inventory/payroll re-applies after rollback"
echo "== Other expenses in net profit (twice: re-runnable)"
# check "<sql returning t>" "<label>": stops the run with FAIL when the answer is not t.
check() { if [ "$(psql_owner -tAc "$1")" = "t" ]; then echo "PASS $2"; else echo "FAIL $2"; exit 1; fi; }
psql_owner -f 20261027_02_expenses.sql
psql_owner -f 20261027_02_expenses.sql
psql_su -d "$DB" -f tests/46_expenses_tests.sql > "/tmp/$DB.46.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL EXPENSES/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.46.log"
grep -q '^ALL EXPENSES SQL TESTS PASSED' "/tmp/$DB.46.log" || { echo "FAIL expense tests (see above)"; exit 1; }
check "select count(*) = 2 from pg_proc where proname in ('save_expense','void_expense')" "save_expense and void_expense exist"
echo "== Other expenses rollback, then re-apply"
exp_rows="$(psql_owner -tAc "select count(*) from expenses")"
hist_rows="$(psql_owner -tAc "select count(*) from expense_history")"
psql_owner -f 20261027_92_rollback_expenses.sql
check "select count(*) = 0 from pg_proc where proname in ('save_expense','void_expense')" "rollback removed save_expense and void_expense"
check "select pg_get_function_result('trimax_private.profit_summary(timestamptz,timestamptz)'::regprocedure) not like '%other_expenses%' and pg_get_function_result('trimax_private.profit_trend(timestamptz,timestamptz,text)'::regprocedure) not like '%other_expenses%'" "rollback put back the reports without expenses"
check "select bool_and(net_profit = gross_profit - payroll_paid - payroll_unpaid) from trimax_private.profit_summary(now() - interval '30 days', now() + interval '1 day')" "after rollback net profit = gross profit - payroll again"
check "select (select count(*) from expenses) = $exp_rows and (select count(*) from expense_history) = $hist_rows" "rollback kept every expense ($exp_rows) and history line ($hist_rows)"
if psql_owner -c "delete from expenses" >/dev/null 2>&1; then echo "FAIL expenses could be deleted after rollback"; exit 1; fi
echo "PASS after rollback expenses still cannot be deleted"
psql_owner -f 20261027_02_expenses.sql
check "select count(*) = 2 from pg_proc where proname in ('save_expense','void_expense')" "expenses re-apply after rollback"
check "select count(*) = $exp_rows from expenses" "re-apply continues from the same expenses"
check "select other_expenses > 0 and net_profit = gross_profit - payroll_paid - payroll_unpaid - other_expenses from trimax_private.profit_summary(now() - interval '30 days', now() + interval '1 day')" "after re-apply the report counts the expenses again"
rm -f "/tmp/$DB.46.log"
echo "== Driver app: own assigned vehicle (twice: re-runnable)"
psql_owner -f 20261027_03_driver_my_vehicle.sql
psql_owner -f 20261027_03_driver_my_vehicle.sql
psql_su -d "$DB" -f tests/47_driver_vehicle_tests.sql > "/tmp/$DB.47.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL DRIVER VEHICLE/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.47.log"
grep -q '^ALL DRIVER VEHICLE SQL TESTS PASSED' "/tmp/$DB.47.log" || { echo "FAIL driver vehicle tests (see above)"; exit 1; }
rm -f "/tmp/$DB.47.log"
echo "== Driver vehicle rollback, then re-apply"
psql_owner -f 20261027_91_rollback_driver_my_vehicle.sql
check "select count(*) = 0 from pg_proc where proname = 'trimax_driver_my_vehicle'" "rollback removed trimax_driver_my_vehicle"
check "select count(*) > 0 from vehicles" "rollback kept the vehicles"
psql_owner -f 20261027_03_driver_my_vehicle.sql
check "select count(*) = 1 from pg_proc where proname = 'trimax_driver_my_vehicle'" "driver vehicle re-applies after rollback"
echo "== Agent discounts per item (twice: re-runnable)"
psql_owner -f 20261027_04_per_item_discounts.sql
psql_owner -f 20261027_04_per_item_discounts.sql
psql_su -d "$DB" -f tests/48_per_item_discounts_tests.sql > "/tmp/$DB.48.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL PER-ITEM/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.48.log"
grep -q '^ALL PER-ITEM DISCOUNT SQL TESTS PASSED' "/tmp/$DB.48.log" || { echo "FAIL per-item discount tests (see above)"; exit 1; }
rm -f "/tmp/$DB.48.log"
echo "== Per-item discounts rollback, then re-apply"
disc_lines="$(psql_owner -tAc "select count(*) from booking_items where discount_per_unit > 0")"
psql_owner -f 20261027_90_rollback_per_item_discounts.sql
check "select count(*) = 0 from pg_proc where proname in ('trimax_agent_set_item_discounts','apply_item_discounts','item_discount_max','refresh_booking_total_for')" "rollback removed the per-item functions"
check "select pronargs = 10 from pg_proc where proname = 'save_product'" "rollback put back the 10-argument save_product"
check "select count(*) = $disc_lines from booking_items where discount_per_unit > 0" "rollback kept the item discounts ($disc_lines lines)"
psql_owner -f 20261027_04_per_item_discounts.sql
check "select pronargs = 11 from pg_proc where proname = 'save_product'" "per-item discounts re-apply after rollback"
check "select count(*) = 0 from bookings b where b.subtotal_amount is not null and b.total_amount <> b.subtotal_amount - b.discount_amount" "after re-apply every booking total = subtotal - discount"
echo "== Location history and new-booking notifications (twice: re-runnable)"
psql_owner -f 20261028_01_location_history_and_booking_notifications.sql
psql_owner -f 20261028_01_location_history_and_booking_notifications.sql
psql_su -d "$DB" -f tests/49_location_history_and_booking_notifications_tests.sql > "/tmp/$DB.49.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL LOCATION HISTORY/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.49.log"
grep -q '^ALL LOCATION HISTORY AND BOOKING NOTIFICATION SQL TESTS PASSED' "/tmp/$DB.49.log" || { echo "FAIL location history / booking notification tests (see above)"; exit 1; }
rm -f "/tmp/$DB.49.log"
echo "== Location history rollback, then re-apply"
hist_rows="$(psql_owner -tAc "select count(*) from trimax_private.location_history")"
nb_rows="$(psql_owner -tAc "select count(*) from admin_notifications where category = 'New booking'")"
psql_owner -f 20261028_90_rollback_location_history_and_booking_notifications.sql
check "select count(*) = 0 from pg_proc where proname in ('location_history_for', 'purge_location_history', 'bookings_notify_new', 'distance_m')" "rollback removed the history and notification functions"
check "select count(*) = 0 from pg_trigger where tgname = 'trimax_bookings_notify_new'" "rollback removed the new-booking trigger"
check "select prosrc not like '%location_history%' from pg_proc where proname = 'trimax_update_location'" "rollback put back the location call without history"
check "select count(*) = $hist_rows from trimax_private.location_history" "rollback kept the location history ($hist_rows samples)"
check "select count(*) = $nb_rows from admin_notifications where category = 'New booking'" "rollback kept the notifications ($nb_rows)"
psql_owner -f 20261028_01_location_history_and_booking_notifications.sql
check "select count(*) = 4 from pg_proc where proname in ('location_history_for', 'purge_location_history', 'bookings_notify_new', 'distance_m')" "location history re-applies after rollback"
check "select count(*) = $hist_rows from trimax_private.location_history" "re-apply continues from the same history"
echo "== Suppliers and Sales & Inventory Intelligence (each twice: re-runnable)"
psql_owner -f 20261028_02_suppliers.sql
psql_owner -f 20261028_02_suppliers.sql
psql_owner -f 20261028_03_sales_inventory_intelligence.sql
psql_owner -f 20261028_03_sales_inventory_intelligence.sql
psql_su -d "$DB" -f tests/50_suppliers_and_intelligence_tests.sql > "/tmp/$DB.50.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL SUPPLIER AND INTELLIGENCE/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.50.log"
grep -q '^ALL SUPPLIER AND INTELLIGENCE SQL TESTS PASSED' "/tmp/$DB.50.log" || { echo "FAIL supplier / intelligence tests (see above)"; exit 1; }
rm -f "/tmp/$DB.50.log"
echo "== Suppliers and intelligence rollback, then re-apply"
sup_rows="$(psql_owner -tAc "select count(*) from suppliers")"
alert_rows="$(psql_owner -tAc "select count(*) from admin_notifications where category = 'Inventory insight'")"
psql_owner -f 20261028_93_rollback_sales_inventory_intelligence.sql
psql_owner -f 20261028_92_rollback_suppliers.sql
check "select count(*) = 0 from pg_proc where proname in ('save_supplier', 'products_add_supplier', 'product_insights', 'refresh_inventory_alerts', 'sold_lines')" "rollback removed the supplier and intelligence functions"
check "select count(*) = $sup_rows from suppliers" "rollback kept the supplier list ($sup_rows)"
check "select count(*) = $alert_rows from admin_notifications where category = 'Inventory insight'" "rollback kept the inventory alerts ($alert_rows)"
psql_owner -c "update products set supplier = 'After Rollback Supplier' where product_id = (select min(product_id) from products)" >/dev/null && echo "PASS products save normally after the rollback"
psql_owner -f 20261028_02_suppliers.sql
psql_owner -f 20261028_03_sales_inventory_intelligence.sql
check "select count(*) = 5 from pg_proc where proname in ('save_supplier', 'products_add_supplier', 'product_insights', 'refresh_inventory_alerts', 'sold_lines')" "suppliers and intelligence re-apply after rollback"
check "select exists (select 1 from suppliers where name = 'After Rollback Supplier')" "re-apply adds suppliers used while it was rolled back"
echo "== Bad orders (twice: re-runnable)"
psql_owner -f 20261028_04_bad_orders.sql
psql_owner -f 20261028_04_bad_orders.sql
psql_su -d "$DB" -f tests/51_bad_orders_tests.sql > "/tmp/$DB.51.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL BAD ORDER/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.51.log"
grep -q '^ALL BAD ORDER SQL TESTS PASSED' "/tmp/$DB.51.log" || { echo "FAIL bad order tests (see above)"; exit 1; }
rm -f "/tmp/$DB.51.log"
echo "== Bad orders rollback, then re-apply"
bo_rows="$(psql_owner -tAc "select count(*) from bad_orders")"
bo_moves="$(psql_owner -tAc "select count(*) from inventory_movements where kind in ('BAD_ORDER', 'BAD_ORDER_VOIDED')")"
stock_sum="$(psql_owner -tAc "select sum(stock) from products")"
psql_owner -f 20261028_94_rollback_bad_orders.sql
check "select count(*) = 0 from pg_proc where proname in ('record_bad_order', 'void_bad_order', 'bad_order_label')" "rollback removed the bad-order functions"
check "select pg_get_function_result('trimax_private.profit_summary(timestamptz,timestamptz)'::regprocedure) not like '%bad_orders%' and pg_get_function_result('trimax_private.profit_trend(timestamptz,timestamptz,text)'::regprocedure) not like '%bad_orders%'" "rollback put back the reports without bad orders"
check "select bool_and(net_profit = gross_profit - payroll_paid - payroll_unpaid - other_expenses) from trimax_private.profit_summary(now() - interval '30 days', now() + interval '1 day')" "after rollback net profit = gross profit - payroll - other expenses again"
check "select (select count(*) from bad_orders) = $bo_rows and (select count(*) from inventory_movements where kind in ('BAD_ORDER', 'BAD_ORDER_VOIDED')) = $bo_moves and (select sum(stock) from products) = $stock_sum" "rollback kept every bad order ($bo_rows), its stock history ($bo_moves lines) and the stock"
if psql_owner -c "delete from bad_orders" >/dev/null 2>&1; then echo "FAIL bad orders could be deleted after rollback"; exit 1; fi
echo "PASS after rollback bad orders still cannot be deleted"
psql_owner -f 20261028_04_bad_orders.sql
check "select count(*) = 3 from pg_proc where proname in ('record_bad_order', 'void_bad_order', 'bad_order_label')" "bad orders re-apply after rollback"
check "select count(*) = $bo_rows from bad_orders" "re-apply continues from the same bad orders"
check "select bad_orders_count > 0 and net_profit = gross_profit - payroll_paid - payroll_unpaid - other_expenses - bad_orders_loss from trimax_private.profit_summary(now() - interval '30 days', now() + interval '1 day')" "after re-apply the report counts the bad orders again"
echo "== Delivery order: nearest to the TRIMAX Hub first (twice: re-runnable)"
psql_owner -f 20261028_05_delivery_order_nearest_hub.sql
psql_owner -f 20261028_05_delivery_order_nearest_hub.sql
psql_su -d "$DB" -f tests/52_delivery_order_tests.sql > "/tmp/$DB.52.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL DELIVERY ORDER/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.52.log"
grep -q '^ALL DELIVERY ORDER SQL TESTS PASSED' "/tmp/$DB.52.log" || { echo "FAIL delivery order tests (see above)"; exit 1; }
rm -f "/tmp/$DB.52.log"
echo "== Delivery order rollback, then re-apply"
psql_owner -f 20261028_95_rollback_delivery_order_nearest_hub.sql
check "select count(*) = 0 from pg_proc where proname in ('delivery_sequence', 'hub_distance_m')" "rollback removed delivery_sequence and hub_distance_m"
check "select prosrc not like '%delivery_sequence%' and prosrc like '%when ''In Progress'' then 0%' from pg_proc where proname = 'trimax_driver_list_deliveries'" "rollback put back the previous driver list order"
check "select has_function_privilege('authenticated', 'public.trimax_driver_list_deliveries(text)', 'execute') and not has_function_privilege('anon', 'public.trimax_driver_list_deliveries(text)', 'execute')" "after rollback the driver list keeps its access"
psql_owner -f 20261028_05_delivery_order_nearest_hub.sql
check "select count(*) = 2 from pg_proc where proname in ('delivery_sequence', 'hub_distance_m') and prosrc <> ''" "delivery order re-applies after rollback"
echo "== Bad orders: loss set by hand, 0 pieces for part of one item (twice: re-runnable)"
psql_owner -f 20261028_06_bad_order_custom_loss.sql
psql_owner -f 20261028_06_bad_order_custom_loss.sql
psql_su -d "$DB" -f tests/53_bad_order_custom_loss_tests.sql > "/tmp/$DB.53.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL BAD ORDER CUSTOM/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.53.log"
grep -q '^ALL BAD ORDER CUSTOM LOSS SQL TESTS PASSED' "/tmp/$DB.53.log" || { echo "FAIL bad order custom loss tests (see above)"; exit 1; }
rm -f "/tmp/$DB.53.log"
echo "== Loss set by hand: rollback, then re-apply"
bo_rows="$(psql_owner -tAc "select count(*) from bad_orders")"
psql_owner -f 20261028_96_rollback_bad_order_custom_loss.sql
check "select to_regprocedure('trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid,numeric)') is null and to_regprocedure('trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid)') is not null" "rollback put back the 8-argument record_bad_order"
check "select count(*) = $bo_rows and count(*) filter (where loss_source = 'CUSTOM') > 0 from bad_orders" "rollback kept every bad order ($bo_rows), also those set by hand"
check "select (select loss_amount from trimax_private.record_bad_order('marc.admin', (select min(product_id) from products where stock > 0), 1, 'BROKEN', 'DISPOSED', null, null, gen_random_uuid())) is not distinct from (select round(capital_cost, 2) from products where product_id = (select min(product_id) from products where stock > 0))" "after rollback a bad order is pieces x puhunan again"
psql_owner -f 20261028_06_bad_order_custom_loss.sql
check "select to_regprocedure('trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid,numeric)') is not null and (select count(*) = 2 from pg_proc where proname = 'record_bad_order')" "loss set by hand re-applies after rollback"
echo "== Half case: rollback while nothing is half yet, then apply (twice: re-runnable)"
# Production has one line per product in a booking (booking_items_booking_id_product_id_key); the
# reconstructed test schema does not, so it is added here to test against what production has.
psql_owner -c "alter table booking_items drop constraint if exists booking_items_booking_id_product_id_key" -c "alter table booking_items add constraint booking_items_booking_id_product_id_key unique (booking_id, product_id)" >/dev/null
fn_before="$(psql_owner -tAc "select md5(string_agg(p.oid::regprocedure::text || md5(p.prosrc), ',' order by p.oid::regprocedure::text)) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname in ('public', 'trimax_private')")"
psql_owner -f 20261028_07_half_case.sql
psql_owner -f 20261028_97_rollback_half_case.sql
check "select '$fn_before' = (select md5(string_agg(p.oid::regprocedure::text || md5(p.prosrc), ',' order by p.oid::regprocedure::text)) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname in ('public', 'trimax_private')) and (select data_type = 'integer' from information_schema.columns where table_name = 'products' and column_name = 'stock') and (select pg_get_constraintdef(oid) = 'UNIQUE (booking_id, product_id)' from pg_constraint where conname = 'booking_items_booking_id_product_id_key') and to_regclass('public.booking_items_booking_product_unit_key') is null" "rollback before any half case puts back every function exactly, whole-number stock and one line per product"
psql_owner -f 20261028_07_half_case.sql
psql_owner -f 20261028_07_half_case.sql
psql_su -d "$DB" -f tests/54_half_case_tests.sql > "/tmp/$DB.54.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL HALF CASE/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.54.log"
grep -q '^ALL HALF CASE SQL TESTS PASSED' "/tmp/$DB.54.log" || { echo "FAIL half case tests (see above)"; exit 1; }
rm -f "/tmp/$DB.54.log"
echo "== Half case: rollback after half cases were sold is refused and changes nothing"
if psql_owner -f 20261028_97_rollback_half_case.sql >/dev/null 2>&1; then echo "FAIL the rollback ran although half cases exist"; exit 1; fi
check "select (select data_type = 'numeric' from information_schema.columns where table_name = 'products' and column_name = 'stock') and to_regprocedure('trimax_private.set_product_half_price(text,integer,numeric)') is not null and exists (select 1 from booking_items where unit = 'HALF')" "refused rollback left the half cases, the stock and the functions as they were"
echo "== Payroll for employees without an account: rollback while unused puts everything back, then apply (twice)"
fn_before="$(psql_owner -tAc "select md5(string_agg(p.oid::regprocedure::text || md5(p.prosrc), ',' order by p.oid::regprocedure::text)) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname in ('public', 'trimax_private')")"
psql_owner -f 20261029_01_payroll_staff.sql
psql_owner -f 20261029_99_rollback_payroll_staff.sql
check "select '$fn_before' = (select md5(string_agg(p.oid::regprocedure::text || md5(p.prosrc), ',' order by p.oid::regprocedure::text)) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname in ('public', 'trimax_private')) and to_regclass('public.payroll_staff') is null and not exists (select 1 from information_schema.columns where table_name = 'payroll_records' and column_name = 'staff_id')" "rollback puts back every function exactly and removes the list and the link"
psql_owner -f 20261029_01_payroll_staff.sql
psql_owner -f 20261029_01_payroll_staff.sql
psql_su -d "$DB" -f tests/55_payroll_staff_tests.sql > "/tmp/$DB.55.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL PAYROLL STAFF/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.55.log"
grep -q '^ALL PAYROLL STAFF SQL TESTS PASSED' "/tmp/$DB.55.log" || { echo "FAIL payroll staff tests (see above)"; exit 1; }
rm -f "/tmp/$DB.55.log"
echo "== Visit or Text / Call: rollback while unused puts everything back, then apply (twice)"
fn_before="$(psql_owner -tAc "select md5(string_agg(p.oid::regprocedure::text || md5(p.prosrc), ',' order by p.oid::regprocedure::text)) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname in ('public', 'trimax_private')")"
psql_owner -f 20261029_02_booking_order_via.sql
psql_owner -f 20261029_98_rollback_booking_order_via.sql
check "select '$fn_before' = (select md5(string_agg(p.oid::regprocedure::text || md5(p.prosrc), ',' order by p.oid::regprocedure::text)) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname in ('public', 'trimax_private')) and not exists (select 1 from information_schema.columns where table_name = 'bookings' and column_name in ('order_via', 'visit_photo_path', 'visit_latitude', 'visit_longitude', 'visit_taken_at')) and to_regclass('public.bookings_visit_photo_key') is null" "rollback puts back every function exactly and removes the 5 columns and the index"
psql_owner -f 20261029_02_booking_order_via.sql
psql_owner -f 20261029_02_booking_order_via.sql
psql_su -d "$DB" -f tests/56_booking_order_via_tests.sql > "/tmp/$DB.56.log" 2>&1 || true
sed -n 's/^.*NOTICE:  //p; /ALL BOOKING ORDER VIA/p; /ERROR/p; /CONTEXT/p' "/tmp/$DB.56.log"
grep -q '^ALL BOOKING ORDER VIA SQL TESTS PASSED' "/tmp/$DB.56.log" || { echo "FAIL booking order via tests (see above)"; exit 1; }
rm -f "/tmp/$DB.56.log"
echo "== Visit or Text / Call: rollback after bookings use it is refused and changes nothing"
if psql_owner -f 20261029_98_rollback_booking_order_via.sql >/dev/null 2>&1; then echo "FAIL the rollback ran although bookings use it"; exit 1; fi
check "select to_regprocedure('public.trimax_agent_create_booking_v2(uuid,text,text,text,double precision,double precision,jsonb,text,text,text,text,double precision,double precision,timestamptz)') is not null and exists (select 1 from bookings where order_via = 'VISIT' and visit_photo_path is not null)" "refused rollback left the function and the recorded visits"
echo "ALL DATABASE TESTS PASSED"
