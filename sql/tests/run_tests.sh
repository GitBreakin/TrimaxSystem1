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
echo "ALL DATABASE TESTS PASSED"
