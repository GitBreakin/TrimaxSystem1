#!/usr/bin/env bash
# TEST ONLY. Two phones at the same moment. Called by run_tests.sh after 10_mobile_integration_tests.sql.
set -euo pipefail
DB="$1"; SU="$2"
ANA='00000000-0000-0000-0000-00000000a0a0'
RIO='00000000-0000-0000-0000-00000000c0c0'
as_user() {  # as_user <uid> <sql>: runs <sql> as the API role "authenticated" with that JWT subject
    psql -X -q -tA -v ON_ERROR_STOP=1 -U "$SU" -d "$DB" \
        -c "set role authenticated" \
        -c "select set_config('request.jwt.claims', json_build_object('sub', '$1')::text, false)" \
        -c "$2" 2>&1 | grep -v '^$'
}
q() { psql -X -q -tA -v ON_ERROR_STOP=1 -U "$SU" -d "$DB" -c "$1"; }
fail() { echo "FAIL $*"; exit 1; }

ITEMS='[{"product_id": 1, "quantity": 1}]'
REQ='c0ffee00-0000-0000-0000-000000000001'

# 1. The same submission twice at once (double tap + network retry): one booking, one stock deduction.
stock_before=$(q "select stock from products where product_id = 1")
as_user "$ANA" "begin; select public.trimax_agent_create_booking('$REQ', 'Race Customer', '0921 000 0001', 'Somewhere', 14.7, 121.0, '$ITEMS') ->> 'booking_id'; select pg_sleep(2); commit;" > /dev/null &
sleep 0.5
second=$(as_user "$ANA" "select (public.trimax_agent_create_booking('$REQ', 'Race Customer', '0921 000 0001', 'Somewhere', 14.7, 121.0, '$ITEMS') ->> 'already_existed')" | tail -1)
wait
[ "$(q "select count(*) from bookings where client_request_id = '$REQ'")" = "1" ] || fail "duplicate booking for one request id"
[ "$second" = "true" ] || fail "second concurrent call should return the existing booking, got: $second"
[ "$(q "select stock from products where product_id = 1")" = "$((stock_before - 1))" ] || fail "stock deducted more than once"
echo "PASS concurrent identical submissions create exactly one booking and deduct stock once"

# 2. Two agents race for the last units of a product: one wins, the other gets a stock error.
q "update products set stock = 2 where product_id = 2" >/dev/null
SPRITE='[{"product_id": 2, "quantity": 2}]'
as_user "$ANA" "begin; select public.trimax_agent_create_booking('c0ffee00-0000-0000-0000-000000000002', 'Race Two', '0921 000 0002', 'Somewhere', 14.7, 121.0, '$SPRITE') ->> 'booking_id'; select pg_sleep(2); commit;" > /dev/null &
sleep 0.5
loser=$(as_user "$RIO" "select public.trimax_agent_create_booking('c0ffee00-0000-0000-0000-000000000003', 'Race Three', '0921 000 0003', 'Somewhere', 14.7, 121.0, '$SPRITE') ->> 'booking_id'" || true)
wait
echo "$loser" | grep -q 'Not enough stock' || fail "second agent should get a stock error, got: $loser"
[ "$(q "select stock from products where product_id = 2")" = "0" ] || fail "stock should be 0"
[ "$(q "select count(*) from bookings where client_request_id in ('c0ffee00-0000-0000-0000-000000000002', 'c0ffee00-0000-0000-0000-000000000003')")" = "1" ] || fail "exactly one booking should exist"
echo "PASS two agents ordering the last units: one booking, stock never negative"
