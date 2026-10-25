#!/usr/bin/env bash
# TEST ONLY. Two taps / retries at the same moment on one delivery. Called by run_tests.sh after
# sql/20261025_01_driver_started_delivered.sql and 13_driver_started_delivered_tests.sql.
set -euo pipefail
DB="$1"; SU="$2"
DEE='00000000-0000-0000-0000-00000000e0e0'
as_user() {  # as_user <uid> <sql>: runs <sql> as the API role "authenticated" with that JWT subject
    psql -X -q -tA -v ON_ERROR_STOP=1 -U "$SU" -d "$DB" \
        -c "set role authenticated" \
        -c "select set_config('request.jwt.claims', json_build_object('sub', '$1')::text, false)" \
        -c "$2" 2>&1 | grep -v '^$'
}
q() { psql -X -q -tA -v ON_ERROR_STOP=1 -U "$SU" -d "$DB" -c "$1"; }
fail() { echo "FAIL $*"; exit 1; }

B1=$(q "insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dee', 'TRK-002', 'Confirmed') returning booking_id" | head -1)
B2=$(q "insert into bookings (client_id, driver_username, vehicle_id, status) values (2, 'driver.dee', 'TRK-002', 'Confirmed') returning booking_id" | head -1)

# 1. The same Start Delivery twice at once (double tap + retry): one change, both callers see STARTED.
as_user "$DEE" "begin; select public.trimax_driver_update_delivery_status($B1, 'ASSIGNED', 'STARTED') ->> 'delivery_status'; select pg_sleep(2); commit;" > /dev/null &
sleep 0.5
second=$(as_user "$DEE" "select (r ->> 'delivery_status') || '/' || coalesce(r ->> 'already_recorded', 'false') from public.trimax_driver_update_delivery_status($B1, 'ASSIGNED', 'STARTED') r" | tail -1)
wait
[ "$second" = "STARTED/true" ] || fail "second concurrent Start should get the saved STARTED booking, got: $second"
[ "$(q "select count(*) from trimax_private.delivery_events where booking_id = $B1 and event_type = 'delivery_status'")" = "1" ] || fail "Start written more than once"
[ "$(q "select delivery_status || '/' || status from bookings where booking_id = $B1")" = "STARTED/In Progress" ] || fail "row not STARTED / In Progress"
echo "PASS two Start Delivery taps at once: one change, both get STARTED"

# 2. Drop Off and a problem report at the same moment: the first wins, the second is refused.
q "select 1" >/dev/null
as_user "$DEE" "begin; select public.trimax_driver_update_delivery_status($B1, 'STARTED', 'DELIVERED', null, null, null, 'Rosa') ->> 'status'; select pg_sleep(2); commit;" > /dev/null &
sleep 0.5
loser=$(as_user "$DEE" "select public.trimax_driver_update_delivery_status($B1, 'STARTED', 'FAILED', null, null, 'No one home') ->> 'delivery_status'" || true)
wait
echo "$loser" | grep -q 'already Completed' || fail "the conflicting FAILED should be refused, got: $loser"
[ "$(q "select delivery_status || '/' || status from bookings where booking_id = $B1")" = "DELIVERED/Completed" ] || fail "row should be DELIVERED / Completed"
[ "$(q "select count(*) from trimax_private.delivery_events where booking_id = $B1 and to_status in ('DELIVERED', 'FAILED')")" = "1" ] || fail "more than one final event"
echo "PASS Drop Off and a problem report at once: exactly one wins, the row stays consistent"

# 3. Two Drop Off retries at once: one DELIVERED event, both callers see Completed.
as_user "$DEE" "select public.trimax_driver_update_delivery_status($B2, 'ASSIGNED', 'STARTED')" > /dev/null
as_user "$DEE" "begin; select public.trimax_driver_update_delivery_status($B2, 'STARTED', 'DELIVERED', null, null, null, 'Rosa') ->> 'status'; select pg_sleep(2); commit;" > /dev/null &
sleep 0.5
second=$(as_user "$DEE" "select (r ->> 'status') || '/' || coalesce(r ->> 'already_recorded', 'false') from public.trimax_driver_update_delivery_status($B2, 'STARTED', 'DELIVERED', null, null, null, 'Rosa') r" | tail -1)
wait
[ "$second" = "Completed/true" ] || fail "second concurrent Drop Off should get the completed booking, got: $second"
[ "$(q "select count(*) from trimax_private.delivery_events where booking_id = $B2 and to_status = 'DELIVERED'")" = "1" ] || fail "DELIVERED written more than once"
echo "PASS two Drop Off taps at once: one DELIVERED event, both get Completed"
