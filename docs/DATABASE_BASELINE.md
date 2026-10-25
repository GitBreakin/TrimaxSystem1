# TRIMAX database: live baseline and Stage B migrations

Branch `feature/stage-b-db-baseline` (based on `feature/mobile-booking-integration`).
**Nothing in this branch has been applied to the Supabase project.** Applying `_02` and `_03` needs approval.

## Files (run in this order)

| File | What it does | Changes production? |
| --- | --- | --- |
| `sql/20261025_01_live_baseline.sql` | Everything production runs that was in no repo file: `user_locations`, `admin_notifications`, `trimax_private.duty_events`, `trimax_private.provisioning_requests`, GPS columns on `agents`/`vehicles`, 17 functions (GPS, duty with photo, mobile account provisioning, newer `trimax_driver_update_delivery_status` and `delivery_transition_allowed`), legacy booking-total and audit-log triggers, grants, the private `duty-photos` bucket and its 2 policies | No: on production every statement is a no-op |
| `sql/20261025_02_search_path_and_trigger_privileges.sql` | Pins `search_path` on the 4 functions the Supabase advisor flags; API roles can no longer call the 2 trigger functions | Settings only, no data |
| `sql/20261025_03_mobile_issue_reports.sql` | `public.trimax_report_issue(...)`: SOS and problem reports from the app become rows in `admin_notifications`, which the desktop Notifications screen already lists. Adds 2 nullable columns + 2 indexes | Additive, no data |
| `sql/20261025_99_rollback_stage_b.sql` | Undoes `_02` and `_03` (part A keeps data; part B, commented, drops the new columns) | — |

## How the baseline was verified

Every function body was read from production with `pg_get_functiondef`. The baseline was then applied
to a disposable local PostgreSQL on top of the legacy schema and `20261023_01`, and each of the 32
functions in `public` / `trimax_private` was fingerprinted (md5 of `pg_get_functiondef`, Windows line
endings removed). All 32 match production exactly.

Two functions in `20261023_01` are older than production; the baseline carries the production versions:
`trimax_driver_update_delivery_status` (safe retry of a step already recorded) and
`delivery_transition_allowed` (new deliveries go ASSIGNED -> STARTED -> DELIVERED/FAILED only).

## Tests

```bash
# disposable local PostgreSQL only; never point this at Supabase
PGHOST=127.0.0.1 PGPORT=54329 PGSUPERUSER=postgres sql/tests/run_tests.sh
```

`sql/tests/30_stage_b_tests.sql` covers live GPS, duty start/end with photo path and retry, and the
report function: own-booking checks for agents and drivers, retries, validation, the 10-per-10-minutes
flood guard, privileges, and the desktop's own notification queries. Result on 2026-10-25:
**ALL DATABASE TESTS PASSED**.

## Applying to production (after approval)

1. Supabase dashboard -> SQL Editor.
2. Paste and run `20261025_02_...`, then `20261025_03_...`. Both are one transaction and re-runnable.
3. Check: Advisors -> Security no longer lists "Function Search Path Mutable".
4. The Android app needs a matching release that calls `trimax_report_issue`; until then nothing writes
   to the new function.
