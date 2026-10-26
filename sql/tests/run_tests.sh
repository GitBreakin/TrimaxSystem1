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
echo "ALL DATABASE TESTS PASSED"
