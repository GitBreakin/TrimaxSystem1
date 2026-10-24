# Android ⇄ desktop booking integration: setup, test and rollback

Branches `feature/mobile-booking-integration` and `feature/system-audit-fixes` (hub start, live GPS, duty
photos, automatic mobile invitations) in this repository and in `Andrizzzz/TRIMAX`.
Design and findings: [MOBILE_INTEGRATION_COMPATIBILITY.md](MOBILE_INTEGRATION_COMPATIBILITY.md).

## What it does

```
Agent (Android)                     Supabase Postgres (one bookings table)            Admin (desktop, JDBC)
---------------                     --------------------------------------            ---------------------
sign in (Supabase Auth email) ----> trimax_my_account(): role + Active from users
create booking ------------------> trimax_agent_create_booking(): Pending, no driver,
                                    server prices, stock deducted, idempotent --------> Bookings list ("New (mobile)")
                                                                                       Assign driver + Mark as Confirmed
Driver (Android)                                                                       (same BookingService as before)
----------------
Deliveries list <----------------- trimax_driver_list_deliveries(): only bookings
                                    where driver_username = the signed-in driver
Start (leaves the hub) / Arrived /
Delivered / Failed --------------> trimax_driver_update_delivery_status(): allowed
                                    transitions only, expected-status check ----------> "Delivery" column, details,
                                                                                       History (auto-refresh 30 s)
GPS while on duty ---------------> trimax_update_location(): user_locations + vehicle -> Live GPS / Agent Locations
Duty start/end + photo ----------> Storage "duty-photos" + trimax_record_duty_event() -> account window "Duty"
                                    provisioning queue <------------------------------ "Send mobile invite"
                                    Edge Function provision-mobile-accounts: creates the sign-in, emails the
                                    invitation, links it -> person sets a password from the email in the app
```

Drivers start every delivery at the TRIMAX hub (14.7826996, 121.0475865) and go straight to the customer's
pinned location: ASSIGNED -> ON_THE_WAY -> ARRIVED -> DELIVERED (or FAILED). There is no pickup step.

* Android bookings go into the existing `bookings` / `booking_items` / `clients` / `agents` tables, so
  every desktop list, report, receipt and KPI includes them without changes.
* The phone never writes tables. It calls `SECURITY DEFINER` functions that read the caller's identity
  from the Supabase JWT (`auth.uid()`), map it to a TRIMAX `users.username` through an admin-made link,
  re-check the role and `account_status = 'Active'` on every call, and only touch rows the caller owns.
* The desktop keeps connecting over JDBC exactly as before. It detects the migration and shows the
  extra information only when it is installed; on an un-migrated database it behaves as it does today.

## Files

| File | Purpose |
|---|---|
| `sql/20261023_00_preflight_readonly.sql` | Read-only checks to run first. Changes nothing. |
| `sql/20261023_01_mobile_booking_integration.sql` | The migration (one transaction, re-runnable, additive). |
| `sql/20261023_02_optional_rls_hardening.sql` | Optional: RLS on legacy tables + default-privilege cleanup. |
| `sql/20261023_99_rollback_mobile_booking_integration.sql` | Rollback (part A keeps data; part B commented). |
| `sql/20261024_01_hub_gps_duty_provisioning.sql` | Second migration: hub-start rules, live GPS, duty events + photo bucket, provisioning queue. |
| `sql/20261024_02_optional_provisioning_trigger.sql` | Optional: run the Edge Function automatically (pg_net + Vault). |
| `sql/20261024_99_rollback_hub_gps_duty_provisioning.sql` | Rollback of the second migration (keeps data). |
| `supabase/functions/provision-mobile-accounts/` | Edge Function + its README (deployment, secrets). |
| `sql/tests/` | Tests for a **disposable** local PostgreSQL only. |

## What the migration changes (review before running)

1. **Adds nullable columns** to `public.bookings` (`created_via`, `created_by_username`,
   `client_request_id`, `delivery_address`, `delivery_latitude`, `delivery_longitude`,
   `requested_schedule`, `special_instructions`, `delivery_status`, `delivery_status_at`, `assigned_at`,
   `last_changed_at`), with CHECK constraints, a FK to `users(username)` and indexes. Existing rows are
   not updated except by the new trigger on their next write (`last_changed_at`, `created_via = 'desktop'`).
2. **Two triggers on `bookings`.** Assigning or changing the driver sets `delivery_status = 'ASSIGNED'`
   and records an event. **Changing the driver after the driver has started is refused** (SQLSTATE
   `TM409`) — also for older desktop builds, which will show the raw database message.
3. **New schema `trimax_private`** (not exposed through the API): `mobile_accounts` (Supabase Auth user
   → TRIMAX username link), `delivery_events` (delivery history), helper and admin functions.
4. **Eight `public.trimax_*` functions**, executable only by the `authenticated` role.
5. **Revokes API access to the legacy tables.** `REVOKE ALL` on `users`, `roles`, `clients`, `agents`,
   `products`, `vehicles`, `bookings`, `booking_items`, `audit_log`, `login_sessions`,
   `password_resets`, `admin_notifications`, `user_locations` (and their sequences) **from `anon` and
   `authenticated`**, and `REVOKE CREATE ON SCHEMA public` from them. The desktop (database owner over
   JDBC) is not affected. **Anything else that reads those tables through the Supabase REST API with the
   publishable/anon key will stop working** — run the preflight and confirm nothing does. If the preflight
   shows those grants today, `users` (password hashes) is readable by anyone who has the publishable key,
   which ships in every APK, so this step is strongly recommended.

It does not delete, rename or rewrite any existing data, and does not touch PR #8's `profiles` /
`delivery_bookings` objects (if they exist, leave them unused; do not apply PR #8's migration).

### Second migration (`sql/20261024_01_hub_gps_duty_provisioning.sql`)

1. **Delivery rules:** ASSIGNED -> ON_THE_WAY (needs Confirmed, moves it to In Progress) -> ARRIVED ->
   DELIVERED; FAILED once under way. Nothing can move into PICKED_UP any more; rows left in STARTED /
   PICKED_UP by older app builds can still finish. **Phones running the older app build cannot start new
   deliveries after this** (they send STARTED): install the new APK at the same time.
2. **GPS:** adds `latitude`, `longitude`, `last_location_at` to `vehicles` if missing, creates
   `user_locations` if missing (and removes API access to it), and `trimax_update_location(...)`: the
   signed-in agent/driver's latest position, server timestamp, only while on duty, at most every 2 s.
3. **Duty:** `trimax_private.duty_events`, a private Storage bucket `duty-photos` (JPEG, 5 MB, each user may
   write only `<their auth id>/...`), `trimax_record_duty_event(...)` (refuses a duty change without an
   uploaded photo, double start/end, reused photos, and ending duty during a delivery) and
   `trimax_my_duty_status()`.
4. **Provisioning queue:** `trimax_private.provisioning_requests`, `trimax_private.request_mobile_access(...)`
   (desktop only) and two functions executable **only by the service role** for the Edge Function. A
   Supabase sign-in is linked automatically only if TRIMAX created it for exactly that account
   (app_metadata, which only the service role can set) and its email matches.

## Setup (in this order)

1. **Rotate the database password first.** It is in this repository's history (commit `13ad4c7`).
   Supabase dashboard → Project Settings → Database → Reset database password. Put the new one in each
   admin PC's `trimax.properties` (never commit that file).
2. **Back up.** Dashboard → Database → Backups, or `pg_dump` with the new password from a trusted PC.
3. **Preflight.** Open the SQL Editor, paste `sql/20261023_00_preflight_readonly.sql`, run. Every row
   must be `OK` or `WARN`. Fix any `STOP` first. Read every `WARN` (users without email cannot use the
   app; duplicate emails cannot be linked; API grants on legacy tables are listed here).
4. **(Recommended) Rehearse** on a disposable database: `PGHOST=127.0.0.1 PGPORT=5432
   PGSUPERUSER=postgres sql/tests/run_tests.sh` against a local PostgreSQL 14+ (never Supabase).
5. **Run the migration.** SQL Editor → paste `sql/20261023_01_mobile_booking_integration.sql` → run. It
   runs as `postgres`, stops with a clear error if a precondition fails, and is all-or-nothing.
6. (Optional, after reading it) `sql/20261023_02_optional_rls_hardening.sql`.
7. **Run the second migration** `sql/20261024_01_hub_gps_duty_provisioning.sql` the same way (it requires
   the first). Roll out the new APK at the same time (see the note on older builds above).
8. **Supabase Auth settings.** Turn off public sign-ups ("Allow new users to sign up"); keep "Confirm email"
   on; add `trimax://auth-callback` to Authentication → URL Configuration → Redirect URLs. Configure a custom
   SMTP sender (Authentication → Emails): Supabase's built-in sender is rate limited and meant for testing.
9. **Deploy the provisioning Edge Function** and (optionally) the automatic trigger: follow
   `supabase/functions/provision-mobile-accounts/README.md`. The service-role key stays in Supabase.
10. **Give people mobile access.** New Agent/Driver accounts created on the desktop queue an invitation
    automatically. For existing accounts (e.g. created before this change): Accounts → open the account →
    **Send mobile invite**. The "Mobile invitation" row shows queued / sent / failed (with the reason).
    The person opens the email on their phone, chooses a password in the app and is signed in.
    The manual path still works for a sign-in you created yourself in Supabase: **Link mobile sign-in**
    (only a confirmed sign-in with exactly the account's email). **Remove mobile sign-in** revokes access;
    setting the account Inactive also blocks the app on its next call.
11. **Build the app** from the Android branch (see the Android repo's `docs/MOBILE_INTEGRATION.md`).

## End-to-end test (real project, after setup)

Use test accounts: one Agent (or Rider), two Drivers each with an `Available` vehicle, one Admin.

1. Agent signs in on the phone with their email → lands on the agent dashboard.
2. New booking: customer, address on the map, products (stock comes from the server), schedule, notes →
   Confirm. The app shows *Booking #N* (the real `booking_id`).
3. Desktop → Bookings → Refresh (or wait ≤ 30 s): #N is *Pending*, Delivery = **New (mobile)**. Details
   show "from the mobile app (agent username)", Deliver to, Requested, Instructions; product stock dropped.
4. Turn the phone's network off, confirm another booking, get the "Not sure it was saved" dialog, turn it
   back on, tap Confirm again → exactly one new booking on the desktop.
5. Desktop: **Assign driver** → Driver A. Delivery = *Assigned*. Driver A's phone → Deliveries shows #N as
   "waiting for admin confirmation"; Driver B's phone does **not** show it.
6. Desktop: **Mark as Confirmed**. Driver A: refresh → START NAVIGATION / START DELIVERY becomes available.
7. Driver A (on duty, from the hub): START DELIVERY → the app shows On the way and navigation opens to the
   customer's **pinned** point (not the hub, not the phone's position) → Arrived → drop-off photo +
   recipient name → Confirm. No pickup step appears anywhere. The desktop shows the progress in the
   Delivery column and History after each step, then *Completed*.
8. Once the driver has started, the booking is *In Progress* and the desktop no longer offers **Change
   driver**; the database also refuses a driver change after the start (a desktop window opened before
   the start gets an error message and nothing is changed).
9. Restart the phone app and the desktop: the agent sees #N *Completed*; Driver A sees it in History, not
   in Deliveries; the desktop shows *Completed* with the recipient in History.
10. Failure path: another booking → assign → confirm → driver Start → report problem → *Mark delivery
    failed* with a reason → desktop shows **Delivery failed** in red; admin decides (Cancel returns stock).
11. Desktop: set Driver A Inactive (or Remove mobile sign-in) → the phone is sent back to sign-in on its
    next server call.
12. Security spot checks (from any PC, with the publishable key): `GET <project>/rest/v1/users` and
    `/rest/v1/bookings` must return an error, not rows; `POST /rest/v1/rpc/trimax_driver_list_deliveries`
    without a user token must be refused; `POST /rest/v1/rpc/trimax_service_claim_provisioning` with a
    user token must be refused.
13. Duty and GPS: Driver B taps START DUTY → photo → "Duty started and saved to the server"; the desktop
    account window shows "On duty since ..."; Live GPS shows the vehicle with a current timestamp within a
    minute; the dashboard line says "Position shared with the office at ...". Deny location permission →
    the line says the position is not shared. END DUTY during a delivery is refused.
14. Invitation: create a new Driver on the desktop → the confirmation says an invitation was queued → the
    email arrives → open it on the phone → set a password → the driver dashboard opens. Opening the same
    email link again says it has expired. "Forgot password" on the login screen sends a reset email that
    works the same way.

## Rollback

Second migration first: `sql/20261024_99_rollback_hub_gps_duty_provisioning.sql` (keeps duty history,
provisioning requests, positions and links; re-run `sql/20261023_01_...` afterwards to restore the
previous delivery rules). Then, if needed, `sql/20261023_99_rollback_mobile_booking_integration.sql`, part A: drops the functions, triggers and
`trimax_private` (links and delivery events); **keeps** the bookings the app created and the new columns,
so nothing in `bookings` is lost. Part B (commented) drops the columns too. Rollback does **not** restore
the revoked API grants (restore only what the preflight showed you had, if anything needed it). The
desktop works before, during and after either part.

## Known limits

* Proof-of-delivery photos, deposits, damage charges, problem/SOS reports, phone notifications and profile
  edits are still stored only on the phone; the app says so. OTP proof of delivery is not implemented; the
  recipient's name is required instead. (Duty photos and live GPS now reach the server.)
* GPS is shared while the app is open (dashboard or the in-app navigation map). Android limits location
  updates for apps in the background (for example while Google Maps navigation is in front); a foreground
  location service would be needed for continuous background tracking. The desktop shows such gaps as
  Stale, never as live.
* Invitation links use the custom scheme `trimax://auth-callback`. Another app installed on the same phone
  could register the same scheme; Android App Links (an https domain with assetlinks.json) would close that
  gap if TRIMAX gets a web domain.
* *Delivered* completes the booking. A failed delivery stays *In Progress* for the admin to resolve.
* An agent can reuse only customers linked to them; a customer linked to another agent (or to none) is
  refused rather than silently claimed.
* The desktop polls every 30 s (no push). The app refreshes when a screen is opened or retried.
* The desktop still connects as the database owner with a password in `trimax.properties` on each admin
  PC. That is a separate risk: a dedicated least-privilege database role, or moving admin writes behind a
  server, is recommended before handing the desktop to more people.
