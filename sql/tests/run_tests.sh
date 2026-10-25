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
echo "ALL DATABASE TESTS PASSED"
