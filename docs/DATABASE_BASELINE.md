# TRIMAX database: live baseline, Stage B and Stage E migrations

Branches `feature/stage-b-db-baseline` (based on `feature/mobile-booking-integration`) and
`feature/stage-e-delivery-proofs` (on top of Stage C).

| Migration | Production status |
| --- | --- |
| `_01` live baseline | Describes production; nothing to apply |
| `_02`, `_03` | **Applied 2026-10-25**, with the owner's approval |
| `_04` delivery proofs | **Applied 2026-10-25**, with the owner's approval |
| `_05` admin photo tickets + Edge Function `trimax-admin-media` | **Not applied / not deployed.** Needs approval |

## Files (run in this order)

| File | What it does | Changes production? |
| --- | --- | --- |
| `sql/20261025_01_live_baseline.sql` | Everything production runs that was in no repo file: `user_locations`, `admin_notifications`, `trimax_private.duty_events`, `trimax_private.provisioning_requests`, GPS columns on `agents`/`vehicles`, 17 functions (GPS, duty with photo, mobile account provisioning, newer `trimax_driver_update_delivery_status` and `delivery_transition_allowed`), legacy booking-total and audit-log triggers, grants, the private `duty-photos` bucket and its 2 policies | No: on production every statement is a no-op |
| `sql/20261025_02_search_path_and_trigger_privileges.sql` | Pins `search_path` on the 4 functions the Supabase advisor flags; API roles can no longer call the 2 trigger functions | Settings only, no data |
| `sql/20261025_03_mobile_issue_reports.sql` | `public.trimax_report_issue(...)`: SOS and problem reports from the app become rows in `admin_notifications`, which the desktop Notifications screen already lists. Adds 2 nullable columns + 2 indexes | Additive, no data |
| `sql/20261025_04_delivery_proofs.sql` | Stage E. Private `delivery-proofs` bucket (JPEG, 5 MB, own-folder upload/read for phones), table `public.delivery_proofs` (RLS on, no phone access), `public.trimax_driver_attach_proof(...)`: the assigned driver attaches a drop-off photo to a started/delivered booking, the server checks the file is really in his folder, max 5 per booking, safe retries | Additive: 1 table, 1 bucket, 2 policies, 2 functions |
| `sql/20261025_05_admin_media_tickets.sql` | Desktop photo viewing without any storage key on the PC: `trimax_private.media_tickets`, `trimax_private.issue_media_ticket(admin, bucket, path)` (owner only: Active Admin, photo must exist, audited as `media.view`) and `public.trimax_service_redeem_media_ticket(ticket)` (service role only, one use, 2 minutes). Used with the Edge Function in `supabase/functions/trimax-admin-media` | Additive: 1 private table, 2 functions |
| `sql/20261025_97_rollback_media_tickets.sql` | Undoes `_05` (tickets are short-lived; audit rows stay) | — |
| `sql/20261025_98_rollback_stage_e.sql` | Undoes `_04` (part A keeps proofs and photos; part B, commented, drops the table) | — |
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
flood guard, privileges, and the desktop's own notification queries.

`sql/tests/40_stage_e_tests.sql` (with `39_storage_emulation.sql`, a small local stand-in for Supabase
Storage) covers the bucket's own-folder rules, and `trimax_driver_attach_proof`: not before Start, own
folder only, the file must really be uploaded (not empty, not older than 24 h), another driver's booking,
validation, retries by request id and by photo, the 5-photo limit, agents refused, desktop (owner) reads,
audit log; then rollback part A and re-apply. Result on 2026-10-25: **ALL DATABASE TESTS PASSED**.

## Applying to production (after approval)

1. Supabase dashboard -> SQL Editor.
2. Paste and run `20261025_02_...`, then `20261025_03_...`. Both are one transaction and re-runnable.
3. Check: Advisors -> Security no longer lists "Function Search Path Mutable".
4. The Android app needs a matching release that calls `trimax_report_issue`; until then nothing writes
   to the new function.

### `_04` (Stage E): applied 2026-10-25

Applied with the Supabase migration tool as `20261025_04_delivery_proofs`. Checked afterwards:
`trimax_driver_attach_proof` and `storage_object_uploaded` match this file (md5 of
`pg_get_functiondef`); bucket private, JPEG only, 5 MB; 2 own-folder policies; RLS on, phones cannot
read the table; only `authenticated` can call the function; no existing rows changed. The steps
below are kept for a fresh project.

1. SQL Editor: paste and run `20261025_04_delivery_proofs.sql` (one transaction, re-runnable).
2. Check: Storage lists a private `delivery-proofs` bucket; Advisors show nothing new.
3. Phones with the Stage E build keep drop-off photos queued until this is applied, then send them on
   their own. Completing deliveries never depends on it.
4. Viewing the photos in the Desktop is a later step: the Desktop has no Storage access yet, and no
   storage key goes into any client code.

### `_05` + Edge Function `trimax-admin-media` (after approval)

1. SQL Editor: run `20261025_05_admin_media_tickets.sql`.
2. Deploy `supabase/functions/trimax-admin-media` (`index.ts` + `handler.ts`) with **verify_jwt = false**:
   the Desktop has no Supabase sign-in; the one-time ticket is the authorization. The function reads the
   project's secret key from its own environment (`SUPABASE_SECRET_KEYS`, or the legacy
   `SUPABASE_SERVICE_ROLE_KEY`), never from a PC.
3. Desktop: nothing to configure. The project URL is worked out from `db.user`; `supabase.url` in
   `trimax.properties` can override it.

Tests: `sql/tests/41_media_tickets_tests.sql` (in `run_tests.sh`) and
`node --experimental-strip-types supabase/functions/trimax-admin-media/test_handler.mjs` (Node 22+, fake
Supabase on localhost; 13 checks).
