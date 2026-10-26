# Android ⇄ desktop booking integration: setup, test and rollback

Branch `feature/mobile-booking-integration` in this repository and in `Andrizzzz/TRIMAX`.
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
Start / Picked up / On the way /
Arrived / Delivered / Failed ----> trimax_driver_update_delivery_status(): allowed
                                    transitions only, expected-status check ----------> "Delivery" column, details,
                                                                                       History (auto-refresh 30 s)
```

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
7. **Supabase Auth settings.** Turn off public sign-ups ("Allow new users to sign up") so only accounts
   you create exist. Linking never trusts sign-up metadata, but closed sign-up keeps the user list clean.
8. **Sign-ins for agents and drivers (no Dashboard step).** After `sql/20261026_01_employee_mobile_sign_in.sql`
   and the Edge Function `supabase/functions/trimax-accounts` are live (see the section below),
   **Agents & Drivers → Create Employee Account** also creates the person's Supabase sign-in and links it.
   For an account made earlier: open it → **Create mobile sign-in**. The person chooses the phone
   password in the app (**Set up or reset password**) with the code from the TRIMAX email; a new code:
   open the account → **Send new setup code**. Roles are never set in Supabase.
9. **Sign-ins made by hand before this** (Supabase → Authentication → Users): open the account →
   **Link mobile sign-in**. The link is made only if a confirmed Supabase user with exactly that email
   exists. **Remove mobile sign-in** revokes it; setting the account Inactive also blocks the app on its
   next call.
10. **Build the app** from the Android branch (see the Android repo's `docs/MOBILE_INTEGRATION.md`).

## Employee sign-ins without the Supabase Dashboard (`20261026_01` + `trimax-accounts`)

```
Desktop (admin, JDBC)                     Supabase (server)                                   Phone
---------------------                     -----------------                                   -----
Create Employee Account
  users row + setup code (hashed) + email
  issue_provisioning_ticket(admin, user) -> queue (provisioning_requests), one-time ticket
  POST trimax-accounts {provision, ticket} -> claim ticket -> Auth Admin API: create user
                                              (email confirmed, random password, app_metadata
                                              trimax_provisioned/trimax_username)
                                           -> trimax_service_finish_provisioning: link
                                                                                     Set up or reset password:
                                           <- POST trimax-accounts {set_password, email, code, new password}
                                              trimax_service_use_setup_code (1 use, 5 tries, 24 h)
                                              -> Auth Admin API: set that user's password
                                                                                     sign in as usual
```

* The secret key exists only in the Edge Function's environment (Supabase sets it). The Desktop holds
  no Supabase key; the phone only the publishable key.
* Only an Active Admin can get a ticket (checked in the database, not by the Desktop screen). A ticket
  works once, for 2 minutes, for one account.
* Retrying is safe: a second press while one is running answers "busy"; after a cut-off call the retry
  finds the Auth user the first call made and links it instead of creating another one.
* A sign-in someone made by hand with the same email is not taken over: the admin gets "use Link mobile
  sign-in".
* The same migration fixes the audit actor of the existing `trimax_service_finish_provisioning` (it wrote
  `provisioning`, which `audit_log`'s foreign key refuses, so it could never have succeeded).

Install (each step needs the owner's approval): run `sql/20261026_01_employee_mobile_sign_in.sql`, then
deploy `supabase/functions/trimax-accounts` (`index.ts` + `handler.ts`) with **verify_jwt = false** (the
ticket or the code is the authorization). No secret has to be added. Rollback:
`sql/20261026_94_rollback_employee_mobile_sign_in.sql` (keeps every sign-in and link), and delete the
function.

Local checks: `sql/tests/run_tests.sh` (part "Employee mobile sign-in"),
`node --experimental-strip-types supabase/functions/trimax-accounts/test_handler.mjs`, and
`supabase/functions/trimax-accounts/local_supabase.mjs` to try the Desktop and the phone against a
disposable local database.

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
6. Desktop: **Mark as Confirmed**. Driver A: refresh → START DELIVERY becomes available.
7. Driver A: Start → Picked up & on the way → Arrived → drop-off photo + recipient name → Confirm. The
   desktop shows the progress in the Delivery column and History after each step, then *Completed*.
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
    without a user token must be refused.

## Rollback

`sql/20261023_99_rollback_mobile_booking_integration.sql`, part A: drops the functions, triggers and
`trimax_private` (links and delivery events); **keeps** the bookings the app created and the new columns,
so nothing in `bookings` is lost. Part B (commented) drops the columns too. Rollback does **not** restore
the revoked API grants (restore only what the preflight showed you had, if anything needed it). The
desktop works before, during and after either part.

## Known limits

* Driver location, proof photos, deposits, damage charges, problem/SOS reports, attendance, phone
  notifications and profile edits are still stored only on the phone; the app now says so. OTP proof of
  delivery is not implemented; the recipient's name is required instead.
* *Delivered* completes the booking. A failed delivery stays *In Progress* for the admin to resolve.
* An agent can reuse only customers linked to them; a customer linked to another agent (or to none) is
  refused rather than silently claimed.
* The desktop polls every 30 s (no push). The app refreshes when a screen is opened or retried.
* The desktop still connects as the database owner with a password in `trimax.properties` on each admin
  PC. That is a separate risk: a dedicated least-privilege database role, or moving admin writes behind a
  server, is recommended before handing the desktop to more people.
