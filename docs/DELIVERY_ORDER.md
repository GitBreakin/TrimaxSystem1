# Delivery order: nearest to the TRIMAX Hub first (2026-10-28)

Owner's request: number the booking orders so that the one nearest comes first, measured from the
TRIMAX Hub pin.

## The rule

The Desktop and the driver app number a driver's unfinished deliveries as #1, #2, #3 and so on. The driver app shows #1 as the current delivery on its dashboard. The order is:

1. **Already started** (In Progress, Started, Picked up, On the way, Arrived). These stay on top, so the delivery the driver is driving to never drops away from #1.
2. **Confirmed**, nearest to the hub first.
3. **Pending**, nearest to the hub first. The Admin still has to confirm these, and the driver cannot start a Pending booking yet.

Within a group:
- A delivery without a pin comes last.
- When two have the same distance, the one assigned first comes first.
- The numbers change by themselves when a delivery is finished, confirmed, started or reassigned.

**Distance** is the straight line from the hub pin to the booking's delivery pin, or to the customer's saved pin when the booking has none. It is not road distance or travel time.

**Hub pin:** `trimax_private.hub_location()`, TRIMAX Hub, 14.7826996, 121.0475865 (Quirino Highway, San Jose del Monte). It is the same pin the app uses as the pickup point. To use a different pin, that one function is changed.

## Where it shows

- **Driver app:** the deliveries list and dashboard come in this order with "Seq #N". No new APK is needed, because the app already shows the server's order. Each delivery also carries `distance_from_hub_m`, which the current app ignores.
- **Desktop → Bookings:**
  - New column **Order · km**: "#1 · 2.5 km" for a delivery in a driver's order, and the distance alone for other bookings ("no pin" when there is none).
  - Choosing one driver in the Driver filter sorts the list in that driver's order, followed by the driver's other bookings.
  - Clicking the column header sorts by it too.
- **Booking window:** a *Delivery order* line, for example "#1 of 4 for Maria Santos (driver_maria) · 2.5 km from TRIMAX Hub (straight line)", or *From hub* with the distance for other bookings.

## Database (`sql/20261028_05_delivery_order_nearest_hub.sql`, NOT applied to production)

- `trimax_private.hub_distance_m(lat, lon)`: metres from the hub (null without a pin).
- `trimax_private.delivery_sequence(driver default all)`: the order above, per driver. Owner only.
- `public.trimax_driver_list_deliveries`: the arguments, access and history list stay the same; the active list uses the new order.
- Changes no row.
- Rollback `20261028_95_rollback_delivery_order_nearest_hub.sql` puts back the previous order.

Without the update, the Desktop leaves the column empty and the driver app keeps the previous order.

## Checks (disposable local PostgreSQL 16 and a test build; nothing on production)

- **`sql/tests/run_tests.sh`: ALL DATABASE TESTS PASSED.** It has 24 new checks. They cover:
  - access
  - distances (the hub = 0 m; no pin = no distance)
  - the order across started, Confirmed nearest-first, no pin and Pending
  - the customer's pin used when the booking has none
  - finished, failed and other drivers' bookings left out
  - per-driver numbering
  - the phone's list, Seq and distance
  - the history list unchanged
  - an agent refused
  - the order changing as work is done
  - rollback and re-apply
- **Desktop unit tests: 91 passed** (4 new). The PDF tests were not run here.
- **End-to-end on a local demo database: 4 checks passed.** For driver_maria, the Desktop order (#40, #36, #10, #15) is the same as the order her phone receives from the server. The booking window shows "#1 of 4 ...".
- Not checked on a real phone or on production data.
