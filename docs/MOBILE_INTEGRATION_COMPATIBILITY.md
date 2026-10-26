# TRIMAX desktop ⇄ Android: architecture and compatibility report

Written 2026-10-23, before any integration code was changed. Based on reading the code on
`GitBreakin/TrimaxSystem1@feature/ui-improvements` (aeb457b, Marc's most recent line, 22 commits ahead
of `master`) and `Andrizzzz/TRIMAX@redesign/corporate-ui-v2` (341a1ed, includes the PR #9 navigation fix),
plus `feature/supabase-auth-migration` in both repositories (PR #8 on Android).

## Root cause of the sync gap

The two applications never talk to the same data.

| | Desktop admin | Android |
|---|---|---|
| Storage | Supabase PostgreSQL over JDBC (`users`, `roles`, `clients`, `agents`, `products`, `vehicles`, `bookings`, `booking_items`, `audit_log`, ...) | Local SQLite `trimax.db` on each phone (`users`, `deliveries`, `customers`, ...) |
| Login | `users.username` + PBKDF2 hash, Admin role only | Local SQLite users seeded on debug builds (`driver01`, `agent01`, ...) |
| Booking create | `BookingService.createBooking`: one transaction inserts `bookings` + `booking_items`, deducts `products.stock` | `NewBookingSummaryActivity` → `DatabaseHelper.addBooking` writes a local row only |
| Driver list | n/a | `DatabaseHelper.getAssignedDeliveries(username)` reads local rows |

`DatabaseHelper.addBooking` also falls back to `driver_id = "agent01"`, and the summary screen sets the
**agent's** username as `driver_id`, so even locally a pending booking is "assigned" to someone.
Nothing ever leaves the phone, so the desktop cannot see Android bookings and drivers can never see
desktop assignments.

## What the desktop already supports

- `bookings.driver_username` / `vehicle_id` can be `NULL`: the bookings list has an "Unassigned" filter
  and `BookingService.assignDriver` handles `expectedDriver == null`.
- Assignment is already safe: admin role re-checked in the database, vehicle row locked, optimistic
  check on status and current driver, audit row in the same transaction.
- Status model (`BookingStatus`, DB CHECK constraint): `Pending → Confirmed → In Progress → Completed`,
  `Cancelled` from any non-final status; cancelling returns stock.
- Every dashboard KPI, report and chart counts rows of `bookings`.

## Incompatibilities found

| Topic | Desktop | Android | PR #8 (`delivery_bookings`) |
|---|---|---|---|
| Booking table | `bookings` + `booking_items` | local `deliveries` | new parallel table, desktop never reads it |
| Booking ID | `int` serial | `BK-2026-00125` text, generated from a local row count | uuid + text code |
| Status values | `Pending`, `Confirmed`, `In Progress`, `Completed`, `Cancelled` | `PENDING`, `CONFIRMED`, `ASSIGNED`, `STARTED`, `PICKED UP`, `ON THE WAY`, `ARRIVED AT DESTINATION`, `DELIVERED`, `FAILED`, `CANCELLED` | `PENDING`…`FAILED` uppercase |
| What is sold | `products` (price, stock) | 5 hard-coded "packages" with hard-coded prices | free-text service + client-supplied `unit_price` |
| Customer | `clients`, linked to agents through `agents` | local `customers` | free-text name/phone |
| Identity | `users.username` (text PK), role via `roles` | local `users.id` / username | `profiles.id` = `auth.users.id`, role copied into `profiles.role` |
| Delivery location | `clients.address/latitude/longitude` | per-booking destination + coordinates | per-booking destination |

PR #8 also: lets an assigned driver `UPDATE` every column of the booking; grants table-wide `INSERT,
UPDATE` to `authenticated`; duplicates the role (so disabling a user on the desktop would not revoke
mobile access); does not persist or refresh tokens.

## Security findings (existing)

1. **Database owner password in git history.** Commit `13ad4c7` (2026-10-17), reachable from every
   desktop branch, contains `trimax.properties` with the password for `postgres.dfxjblrletoeukoarxlz`.
   Deleting the file later does not remove it from history. The password must be reset.
2. The desktop connects as the database owner (`postgres.<project>`), so it bypasses any RLS.
3. Legacy tables were created with plain SQL in `public`. With Supabase's default privileges they are
   very likely readable and writable by the `anon`/`authenticated` API roles, and RLS is probably off.
   The Android PR #8 branch ships the project URL and publishable key, so anyone could call
   `GET /rest/v1/users` (password hashes) if that is the case. This could not be verified here (no
   database access); `sql/20261023_00_preflight_readonly.sql` checks it.

## Decision

**Extend the existing `bookings` table; do not create a second booking table.**

- An Android agent's booking becomes a normal `bookings` row (`status = 'Pending'`, no driver) with
  `booking_items` for real `products`, priced by the database and with stock deducted in the same
  transaction, exactly like a desktop booking. The desktop list, detail window, assignment, status
  changes, receipts, dashboard and reports work on it unchanged and nothing is double counted.
- Delivery-specific data goes into new **nullable** columns on `bookings` (delivery address and
  coordinates, schedule text, instructions, `created_via`, `created_by_username`, an idempotency key,
  and a driver-progress `delivery_status`). Existing desktop `INSERT`/`SELECT` statements name their
  columns, so they are unaffected.
- `bookings.status` keeps its five values. Driver progress is a separate `delivery_status`
  (`ASSIGNED, STARTED, PICKED_UP, ON_THE_WAY, ARRIVED, DELIVERED, FAILED`) taken from the existing
  Android lifecycle. Two driver steps move the booking status: `STARTED` (Confirmed → In Progress) and
  `DELIVERED` (In Progress → Completed). `FAILED` leaves the booking In Progress for the admin to
  cancel, which returns stock through the existing desktop flow.
- Identity: Supabase Auth for the phones. A private mapping table links `auth.users.id` to
  `users.username`; it is written only by an administrator, and only when the Auth email equals the
  desktop user's email and is confirmed. Role and Active status are always read from `users`/`roles`,
  so disabling an account on the desktop revokes mobile access immediately.
- The phone never touches tables. It calls `SECURITY DEFINER` functions that derive the user from
  `auth.uid()` and enforce ownership, transitions and validation. `anon`/`authenticated` lose direct
  access to the legacy tables.
- The desktop keeps its JDBC connection (Marc's workflow is preserved). Its credentials are never used
  by the phone. Moving the desktop to a least-privilege role or a backend is documented as follow-up.
