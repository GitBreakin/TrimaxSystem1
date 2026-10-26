#!/usr/bin/env bash
# Builds a database ONLY from this repository's SQL files (no test data) on a DISPOSABLE local PostgreSQL
# and prints its schema fingerprints, then compares them with production's (saved file, or a fresh copy
# you pasted from the Supabase SQL editor). Never point it at Supabase.
#
#   PGHOST=127.0.0.1 PGPORT=54329 PGSUPERUSER=postgres sql/tests/rebuild_check.sh [production_fingerprints.txt]
set -euo pipefail
cd "$(dirname "$0")/.."
SU="${PGSUPERUSER:-postgres}"
DB="trimax_rebuild_$$"
EXPECTED="${1:-tests/production_fingerprints_2026-10-26.txt}"
export PGHOST="${PGHOST:-127.0.0.1}" PGPORT="${PGPORT:-5432}"
psql_su()    { psql -X -q -v ON_ERROR_STOP=1 -U "$SU" "$@"; }
psql_owner() { PGPASSWORD=test-only psql -X -q -v ON_ERROR_STOP=1 -U app_owner -d "$DB" "$@"; }
cleanup() { psql_su -d postgres -c "drop database if exists $DB" >/dev/null 2>&1 || true; }
trap cleanup EXIT

psql_su -d postgres -c "create database $DB" >/dev/null
if psql_su -d postgres -tAc "select 1 from pg_roles where rolname = 'app_owner'" | grep -q 1; then
    grep -vE '^create role (anon|authenticated|service_role|app_owner)' tests/00_supabase_emulation.sql | psql_su -d "$DB" >/dev/null
else
    psql_su -d "$DB" -f tests/00_supabase_emulation.sql >/dev/null
fi
psql_su -d "$DB" -f tests/39_storage_emulation.sql >/dev/null   # stand-in for Supabase Storage

for f in 20261026_00_legacy_tables_from_production.sql \
         20261023_01_mobile_booking_integration.sql 20261023_02_optional_rls_hardening.sql \
         20261025_01_live_baseline.sql 20261025_02_search_path_and_trigger_privileges.sql \
         20261025_03_mobile_issue_reports.sql 20261025_04_delivery_proofs.sql \
         20261025_05_admin_media_tickets.sql 20261025_06_product_images.sql; do
    echo "== $f"
    psql_owner -f "$f" >/dev/null 2>&1 || { psql_owner -f "$f"; exit 1; }
done

psql_su -d "$DB" -At -f tests/fingerprints.sql | LC_ALL=C sort > "/tmp/$DB.txt"
if diff <(grep -v '^#' "$EXPECTED" | LC_ALL=C sort) "/tmp/$DB.txt"; then
    echo "PASS the rebuilt database matches $EXPECTED ($(wc -l < "/tmp/$DB.txt") fingerprints)"
else
    echo "DIFFERENCES above: < expected (production), > rebuilt from this repository"
    exit 1
fi
