# Post-defense revisions (2026-10-28)

What the panel asked for, what changed, and what still needs the owner's approval.

## 1. Booking workflow: fewer clicks

| Before | Now |
|---|---|
| Double-click a booking (or select + View Details) | **One click** on a booking opens it (Enter also works) |
| Pending: *Mark as Confirmed* (confirm box) → *Assign driver* (pick) → *Print receipt* | Pending: **Confirm & assign driver** → pick the driver → done (one transaction), then **Print receipt** |
| *Print receipt* / *Save receipt PDF* always shown, even with no driver ("Driver: Unassigned" on the receipt) | Receipt buttons appear **only after a driver is saved**; before that the window says why |
| Assign driver was one grey button among many | A **Next step** box at the top shows the driver and the one highlighted action |

Rules kept (and where they are enforced):

- Agents still create **Pending** bookings; only an Admin confirms them. *Confirm & assign* is an Admin action
  (`AuthService.requireAdmin()` re-checks the Admin in the database). The database still refuses a driver's
  START while a booking is Pending (`trimax_driver_update_delivery_status`), so the approval cannot be skipped.
- The driver must be Active with an **Available** vehicle (checked with a row lock); the booking must still have
  the status and driver the Admin saw, otherwise nothing changes ("changed by someone else"). A second click on
  a stale window is refused the same way, so a booking is never assigned twice.
- Confirm and assign happen **in one transaction**: if anything fails, the booking stays Pending with no driver,
  and no Print button appears (the window reloads from the database after every change).
- Changing an existing driver still has its own explicit button ("Change driver"), and the database still
  refuses it after the driver started. Cancelling still asks for confirmation and returns the stock.
- Before printing or saving a receipt the window re-reads the booking and only continues if a driver is saved.
  (Receipts are made on the PC; there is no server step to enforce, so this is the check.) The receipt layout
  (`ReceiptPdfGenerator`) is unchanged.
- Both changes are audited as before: `booking.status_change` "Pending -> Confirmed (with driver assignment)"
  and `booking.assign_driver`.

Files: `BookingActions.java` (new: next step and receipt rules), `BookingService.java`
(`confirmAndAssignDriver`), `BookingDetailDialog.java`, `BookingsPanel.java`.

## 2. Location history (Admins only)

**Before:** only the last position was stored (`user_locations`, one row per person; `vehicles.latitude/longitude`
for drivers). Earlier movements were never recorded and **cannot be recovered**.

**Now** (database update `sql/20261028_01_location_history_and_booking_notifications.sql`):

- `trimax_private.location_history` (append-only): username, AGENT/DRIVER, latitude, longitude, accuracy,
  speed, bearing, `recorded_at` (database clock). Foreign key to `users` (a deleted user's history goes with
  them). Indexes on (username, recorded_at) and (recorded_at).
- Recorded by the call the phones **already make** (`trimax_update_location`, while the app is open and the
  person is on duty), so **no new APK is needed**. A sample is kept when it is the first one, or 30 s passed,
  or the person moved 50 m (and 5 s passed). Fixes worse than 100 m accuracy update the live marker but are not
  kept. Off duty, 0,0 and invalid positions are refused as before. Anything unexpected in the history step
  only skips the sample; the live position still updates.
- Settings in `trimax_private.location_history_settings` (interval, distance, accuracy, retention), changeable
  without code. Retention: **90 days**; `purge_location_history` removes older samples when an Admin opens
  Location History (once per session) and writes an audit line.
- Reading: `trimax_private.location_history_for(admin, person, from, to, max_points, gap_minutes)` re-checks
  that the caller is an active Admin, allows at most 31 days, returns samples oldest first, marks gaps (no
  sample for more than 5 minutes = a new segment), and above 2000 samples returns every n-th one (always the
  first and the last, and both ends of every gap) together with the real total. Every viewing is in the Audit
  Log (`location.history_view`).
- Phones (`anon`, `authenticated`) and the service role have **no privilege** on the table, the settings or
  the functions (tested: direct reads and calls are refused with 42501).

Desktop: **Live GPS Monitoring → Location history**: person (agents and drivers), From/To in Manila time,
quick Today / Yesterday / Last 7 days. The map joins recorded points in time order with straight blue lines
and says they are not the roads; a gap is a thin dashed grey line and the list says "No GPS data for N min
before this point"; first and last points are marked; hover a point for its time, accuracy and speed; the list
and the map select each other. Loading, empty ("No GPS points were recorded … Earlier trips were never
stored"), error and "not set up yet" states are shown on the map.

Files: `LocationHistoryService.java`, `LocationHistoryRules.java`, `LocationHistoryPanel.java` (new),
`TileMapView.java` (route layer), `MainFrame.java` (tab), `LiveGpsPanel.java` (wording).

Limitation: the phone sends positions only while the TRIMAX app is open and on duty (there is no background
location service), so history has gaps when the app is closed or in the background. Those gaps are shown as
"no data", never filled in.

## 3. Admin notification when an Agent submits a booking

- Same update (`20261028_01`): an `AFTER INSERT` trigger on `bookings` adds **one** `admin_notifications` row,
  "New Booking Submitted" (category *New booking*, related *booking #id*, reported by the agent), with the
  customer, the agent and the time. Only for bookings created from the mobile app by an **Agent**; Desktop
  bookings do not notify.
- It runs in the booking's own transaction: a booking that fails to save leaves no notification. A unique index
  on (related_type, related_id) for this category makes a second one for the same booking impossible, and the
  phone's retry with the same request id returns the existing booking without inserting. Edits, driver
  assignment and status changes are updates and never notify.
- Notifications stay the existing shared Admin inbox (`read_at` / `read_by`); only the database owner (the
  Desktop) can read them.
- Desktop: the bell now re-checks the unread count every **30 seconds** (there is no realtime connection), and
  refreshes the Notifications page if it is open. Clicking a *New booking* notification (or **Open booking**)
  opens that booking and marks the notification read. The Kind column says "Booking".

Files: `NotificationService.java`, `NotificationsPanel.java`, `MainFrame.java`.

## 4. Navigation

The sidebar's **Notifications** entry went to the same page as the bell in the header, so it is no longer in
the sidebar (15 entries). The bell keeps the unread count and opens the Notifications page.

## Checks run (2026-10-28, on a disposable local PostgreSQL 16 and a test build)

- `sql/tests/run_tests.sh`: **ALL DATABASE TESTS PASSED**, including the new
  `sql/tests/49_location_history_and_booking_notifications_tests.sql` (46 checks: privileges, recording rules,
  append-only, retention, Admin-only reading, ordering, gaps, sampling, empty ranges, one notification per
  booking, no notification on failure, retry, edit, assignment or Desktop booking) and rollback + re-apply.
- Desktop unit tests (76, incl. the new `BookingActionsTest`, `LocationHistoryRulesTest`) on a `javac` build
  with stand-ins for javax.mail, PDFBox and POI (Maven Central is not reachable from the build machine). Not
  run there: `PdfExportTest`, `ReceiptLayoutTest` (need the real PDFBox). Run `mvn test` on a PC before merging.
- End-to-end against a local database built from all migrations plus `sql/tests/demo_data.sql`: an agent
  booking from the phone RPC → one notification; Pending shows *Confirm & assign driver* and no Print; an
  unavailable vehicle is refused and nothing changes; a successful *Confirm & assign* saves Confirmed + driver
  in one step with both audit lines; a second click is refused; Print / Save receipt / Change driver appear;
  the bell shows the count and opens Notifications; the sidebar has no Notifications entry; Location history
  draws the recorded points in order with gaps split.
- Not tested: the Android app on a device (no Android SDK here; the app needs no change), real printing.

## Needs the owner's approval

1. Apply `sql/20261028_01_location_history_and_booking_notifications.sql` to production (same method as
   before: fingerprints before/after, dry run on a copy of production's rows, data unchanged). Until then the
   Desktop's Location history says "not set up yet" and no booking notifications are created; the booking
   workflow changes work without it. Rollback: `sql/20261028_90_rollback_location_history_and_booking_notifications.sql`
   (keeps the recorded history and the notifications).
2. Merge the Desktop branch.
