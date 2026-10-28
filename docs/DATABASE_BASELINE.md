# TRIMAX database: live baseline, Stage B and Stage E migrations

Branches `feature/stage-b-db-baseline` (based on `feature/mobile-booking-integration`) and
`feature/stage-e-delivery-proofs` (on top of Stage C).

| Migration | Production status |
| --- | --- |
| `_01` live baseline | Describes production; nothing to apply |
| `_02`, `_03` | **Applied 2026-10-25**, with the owner's approval |
| `_04` delivery proofs | **Applied 2026-10-25**, with the owner's approval |
| `_05` admin photo tickets + Edge Function `trimax-admin-media` | **Applied and deployed 2026-10-25**, with the owner's approval |
| `_06` product images + Edge Function `trimax-admin-media` v2 (upload/delete) | **Applied and deployed 2026-10-26**, with the owner's approval |
| `_07` alert resolution (Stage G) | **Not applied.** Needs approval |
| `20261026_01` employee mobile sign-in + Edge Function `trimax-accounts` | **Applied and deployed 2026-10-26**, with the owner's approval (function v1 13:49; v2 15:31 adds the temporary first password, no database change) |
| `20261027_01` products/stock/sales/payroll/profit, customer photos, agent discounts + Edge Function `trimax-admin-media` redeploy | **Applied 2026-10-27** with approval (3 transactions); `trimax-admin-media` v3 deployed |
| `20261027_02` other expenses in net profit | **Applied 2026-10-27** with approval (1 transaction) |
| `20261027_03` Driver app: own assigned vehicle (`trimax_driver_my_vehicle`) | **Applied 2026-10-27** (about 04:05) with approval |
| `20261027_04` agent discounts per item | **Applied 2026-10-27 ~04:50** with approval. Dry run on a copy of production: no row or total changed. After: 108/108 fingerprints equal the rebuild (`fc6970f1…`). Install the new APK + Desktop |
| `20261028_01` location history (Admin only) + "New booking" Admin notification | **Applied 2026-10-28 18:33** (from another session; not by the run that applied `_02` to `_04`). Its 5 functions equal the repo version (checked). No new APK needed (recorded by the existing location call) |
| `20261028_02` supplier list (Inventory drop-down) | **Applied 2026-10-28 ~22:20** with the owner's approval (1 transaction). 8 suppliers taken from the 11 products; no product changed |
| `20261028_03` Sales & Inventory Intelligence (analysis, settings, inventory alerts) | **Applied 2026-10-28 ~22:20** with the owner's approval (1 transaction). See docs/SALES_INVENTORY_INTELLIGENCE.md |
| `20261028_04` bad orders (broken, damaged, expired... stock out as a loss in net profit) | **Applied 2026-10-28 ~22:20** with the owner's approval (1 transaction). See docs/BAD_ORDERS.md |
| `20261028_05` delivery order: nearest to the TRIMAX Hub first (driver app "Seq #", Desktop Bookings) | **Applied 2026-10-28 ~22:36** with the owner's approval (1 transaction), after a dry run on the local production-like copy. After: the 3 functions equal the dry run; the driver list keeps its access (signed-in only, security definer); the bookings, customers and audit log are unchanged. No new APK needed. See docs/DELIVERY_ORDER.md |
| `20261028_06` bad orders: loss set by hand, 0 pieces for part of one item | **Applied 2026-10-28 ~23:00** with the owner's approval (1 transaction), after a dry run on the local production-like copy. After: the 3 functions and 3 checks equal the dry run; the 8-argument `record_bad_order` older Desktops look for is still there; no API access; products, stock history and the audit log are unchanged. See docs/BAD_ORDERS.md |
| `20261028_07` half case (agents and Walk-In / In-House sell half a case; stock can be .5) | **Not applied.** Needs the owner's approval. Install the new Desktop and APK the same day. See docs/HALF_CASE.md |

`20261028_02` to `_04` were checked in this order:
- **Before:** the production functions they build on had the same fingerprints as a local rebuild through `20261028_01`.
- **Dry run:** a local copy with the 11 production products, applied twice. There were no errors and no product changed.
- **After:**
  - The 11 new or replaced functions had the same fingerprints as the dry run.
  - The API roles had no access to the new tables, sequence or functions.
  - Products (11, stock 698), stock history (104), bookings, sales, notifications and the audit log were unchanged.
  - The month's profit figures were identical, with the new bad-order columns at 0.
  - `record_bad_order` refused a non-Admin.

## Files (run in this order)

| File | What it does | Changes production? |
| --- | --- | --- |
| `sql/20261026_00_legacy_tables_from_production.sql` | The 11 Desktop tables (`users`, `bookings`, `clients`, …) exactly as production has them, written from production's catalog on 2026-10-26. Run it **first** when building a database from scratch, before `20261023_01`. | No: on production every statement is a no-op |
| `sql/20261025_01_live_baseline.sql` | Everything production runs that was in no repo file: `user_locations`, `admin_notifications`, `trimax_private.duty_events`, `trimax_private.provisioning_requests`, GPS columns on `agents`/`vehicles`, 17 functions (GPS, duty with photo, mobile account provisioning, newer `trimax_driver_update_delivery_status` and `delivery_transition_allowed`), legacy booking-total and audit-log triggers, grants, the private `duty-photos` bucket and its 2 policies | No: on production every statement is a no-op |
| `sql/20261025_02_search_path_and_trigger_privileges.sql` | Pins `search_path` on the 4 functions the Supabase advisor flags; API roles can no longer call the 2 trigger functions | Settings only, no data |
| `sql/20261025_03_mobile_issue_reports.sql` | `public.trimax_report_issue(...)`: SOS and problem reports from the app become rows in `admin_notifications`, which the desktop Notifications screen already lists. Adds 2 nullable columns + 2 indexes | Additive, no data |
| `sql/20261025_04_delivery_proofs.sql` | Stage E. Private `delivery-proofs` bucket (JPEG, 5 MB, own-folder upload/read for phones), table `public.delivery_proofs` (RLS on, no phone access), `public.trimax_driver_attach_proof(...)`: the assigned driver attaches a drop-off photo to a started/delivered booking, the server checks the file is really in his folder, max 5 per booking, safe retries | Additive: 1 table, 1 bucket, 2 policies, 2 functions |
| `sql/20261025_05_admin_media_tickets.sql` | Desktop photo viewing without any storage key on the PC: `trimax_private.media_tickets`, `trimax_private.issue_media_ticket(admin, bucket, path)` (owner only: Active Admin, photo must exist, audited as `media.view`) and `public.trimax_service_redeem_media_ticket(ticket)` (service role only, one use, 2 minutes). Used with the Edge Function in `supabase/functions/trimax-admin-media` | Additive: 1 private table, 2 functions |
| `sql/20261025_06_product_images.sql` | Stage F. `products.image_path` (+ `image_updated_at`), PUBLIC bucket `product-images` (JPEG, 2 MB, no write policies: only the Edge Function writes), upload/delete tickets (`trimax_private.issue_product_image_ticket`), `trimax_private.set_product_image` (audited, returns the old picture to delete), redeem returns the ticket's action, `trimax_list_products` adds `image_path` | Additive: 2 nullable columns, 1 bucket, 2 new + 2 replaced functions |
| `sql/20261026_01_employee_mobile_sign_in.sql` | Agent/Driver Supabase sign-ins without the Dashboard. `trimax_private.provisioning_tickets`; `trimax_private.issue_provisioning_ticket(admin, username)` (owner only: Active Admin; queues with the existing `request_mobile_access`); `public.trimax_service_claim_provisioning_ticket(ticket)` and `public.trimax_service_use_setup_code(email, code)` (service role only); private helper `claim_provisioning_request`. Replaces `trimax_service_finish_provisioning` with the same logic but a valid audit actor (the old one audited as `provisioning`, which `audit_log_actor_username_fkey` refuses). Used with `supabase/functions/trimax-accounts` | Additive: 1 private table, 4 new functions, 1 fixed function, no data |
| `sql/20261027_01_sales_inventory_payroll.sql` | Products: puhunan, patong (amount or percent), minimum patong, price kept consistent by a trigger. `public.inventory_movements` (append-only stock history, written by a trigger on `products.stock` with the reason of each change). Bookings: completion time, cost snapshot per line (`booking_items.unit_cost`), agent discount columns and checks (minimum price = puhunan + minimum patong). `public.sales` / `sale_items` (Walk-In / In-House, receipt numbers, void with stock return). `public.payroll_records`. Owner-only Desktop functions (`save_product`, `restock_product`, `adjust_stock`, `create_sale`, `void_sale`, `set_sale_paid`, `save_payroll`, `void_payroll`, `profit_summary`, `profit_trend`). Phone: private `customer-photos` bucket (own-folder policies), `trimax_agent_save_customer`, `_list_customers`, `_discount_quote`, `_set_discount`, and `trimax_agent_create_booking` with an optional discount (a new customer must be saved with a photo first). `issue_media_ticket` accepts customer photos. RLS on every new table, no API grants | Additive: 4 tables, 1 sequence, 1 bucket, columns on `products`, `bookings`, `booking_items`, `clients`; replaces 4 functions; no data rewritten |
| `sql/20261027_93_rollback_sales_inventory_payroll.sql` | Undoes `20261027_01`'s behaviour: puts back the previous `trimax_agent_create_booking`, `booking_json`, booking-total trigger, `issue_media_ticket` and media-ticket checks; drops the new functions, triggers and policies; keeps every new table, column and row | — |
| `sql/20261027_02_expenses.sql` | `public.expenses` (date, category, description, amount, Paid/Unpaid with date and method, notes, void with reason, `client_request_id`) and append-only `public.expense_history` (written by a trigger; expenses cannot be deleted and a voided one cannot change). Owner-only `trimax_private.save_expense` / `void_expense` (Active Admin, audited). `profit_summary` / `profit_trend` dropped and created again with the expense columns **added at the end**; net profit = gross profit − payroll − other expenses | Adds 2 tables, 5 functions, 3 triggers; replaces the 2 report functions; changes no row |
| `sql/20261027_03_driver_my_vehicle.sql` | `public.trimax_driver_my_vehicle()`: the signed-in driver's own assigned vehicles (plate number = `vehicles.vehicle_id`, type, status). No parameter, drivers only (agents TM403), not callable by anon or service_role | Adds 1 function; changes no row |
| `sql/20261027_04_per_item_discounts.sql` | `products.agent_discount_allowed`, `booking_items.discount_per_unit`; per-item quote, booking, `trimax_agent_set_item_discounts`; the whole-order discount is refused (`discount_per_item_only`); `save_product` gains `p_agent_discount` (default keeps the setting) | Adds 2 columns (defaults: no change to existing rows), 4 functions; replaces 8 (incl. `trimax_list_products`) |
| `sql/20261028_01_location_history_and_booking_notifications.sql` | Append-only `trimax_private.location_history` (+ one-row `location_history_settings`: 30 s / 50 m / 100 m accuracy / 90 days), written by `trimax_update_location` (same arguments; the answer adds `history_stored`); owner-only `location_history_for` (Active Admin, at most 31 days, gaps and sampling, audited) and `purge_location_history`. `AFTER INSERT` trigger on `bookings`: one "New booking" `admin_notifications` row per Agent booking from the phone (unique index on the related booking). No grants to anon/authenticated/service_role. See docs/POST_DEFENSE_REVISIONS.md | Adds 2 tables, 1 index on `admin_notifications`, 5 functions, 2 triggers; replaces `trimax_update_location`; changes no row |
| `sql/20261028_90_rollback_location_history_and_booking_notifications.sql` | Puts back the `20261025_01` `trimax_update_location`; drops the notification trigger, its index and the history functions; keeps the history and every notification | — |
| `sql/20261028_02_suppliers.sql` | `public.suppliers` (unique ignoring case; filled from the products' supplier names), trigger adding new names saved with products, owner-only `trimax_private.save_supplier(admin, name)` (Active Admin, no duplicates, audited). `products.supplier` unchanged | Adds 1 table, 1 trigger, 2 functions; changes no product |
| `sql/20261028_92_rollback_suppliers.sql` | Drops the trigger and functions; keeps the list | — |
| `sql/20261028_03_sales_inventory_intelligence.sql` | `trimax_private.inventory_insight_settings` (thresholds, incl. ASSUMED lead/safety/target days), `sold_lines()` (completed bookings + completed sales), `product_insights(from, to)` (per product: units, revenue, previous period, last sale, per day, days of stock, flags with reasons and actions, restock quantity), `inventory_alert_state` + `refresh_inventory_alerts(admin)` (one "Inventory insight" notification per newly applying condition, 7-day repeat limit, advisory lock). Owner only | Adds 2 tables, 3 functions; changes no row except new notifications |
| `sql/20261028_93_rollback_sales_inventory_intelligence.sql` | Drops the 3 functions; keeps settings, alert state and notifications | — |
| `sql/20261028_04_bad_orders.sql` | `inventory_movements` kind check widened with `BAD_ORDER` / `BAD_ORDER_VOIDED`. `public.bad_orders` (product, name and supplier snapshot, pieces, reason, thrown away / returned to supplier, date found, puhunan and loss snapshot, note, link to its stock movement, who/when, undo fields; guarded: no delete, only undo). Owner-only `record_bad_order` (Active Admin, pieces <= stock, same request id = same bad order, audited) and `void_bad_order` (stock back, audited). `profit_summary` / `profit_trend` re-created with `bad_orders_loss`, `bad_orders_count`, `bad_orders_no_cost` / `bad_orders` at the end, subtracted from `net_profit`. Also: the suppliers id sequence is revoked from the API roles (in `20261028_02`) | Adds 1 table, 1 trigger, 4 functions; replaces 2 report functions; changes no row |
| `sql/20261028_94_rollback_bad_orders.sql` | Puts back the `20261027_02` reports; drops the bad-order functions; keeps every bad order, its stock history and the stock | — |
| `sql/20261028_05_delivery_order_nearest_hub.sql` | `trimax_private.hub_distance_m(lat, lon)` (straight line from `hub_location()`), owner-only `trimax_private.delivery_sequence(driver)` (per driver: started first, then Confirmed nearest first, then Pending nearest first; no pin last). `public.trimax_driver_list_deliveries` re-created: same arguments and grants, unchanged history; the active list uses that order and adds `distance_from_hub_m` | Adds 2 functions, replaces 1; changes no row |
| `sql/20261028_95_rollback_delivery_order_nearest_hub.sql` | Puts back the `20261023_01` driver list (same grants) and drops the 2 functions | — |
| `sql/20261028_06_bad_order_custom_loss.sql` | `bad_orders.loss_source` (`PUHUNAN` / `CUSTOM`, default `PUHUNAN`); checks: 0 pieces only with `CUSTOM`, a `CUSTOM` loss is more than 0. `record_bad_order(..., p_custom_loss numeric)` (9 arguments, no default) does the work; the 8-argument version stays as a wrapper without an amount. `void_bad_order`: 0 pieces = no stock change | Adds 1 column, replaces 2 checks, adds 1 function, replaces 2; changes no row |
| `sql/20261028_07_half_case.sql` | `products.stock` and the 3 stock-history number columns become `numeric` (whole or .5 only, stored without trailing zeros). New: `products.half_price`, `booking_items` / `sale_items` `unit` (`WHOLE` / `HALF`) and generated `stock_qty`. `booking_items`: one line per product and unit (replaces the one-per-product key). New `unit_factor` and `set_product_half_price` (Admin, audited). Phone list / quote / booking / item discounts and the Desktop sale, void, restock, correct stock, bad order and reports re-created for halves (same arguments; `adjust_stock` takes a numeric change; restock / correct / bad order / undo return numeric stock) | Adds 5 columns, 1 key, 2 functions; replaces the rest; existing lines become `WHOLE`, no value changes |
| `sql/20261028_97_rollback_half_case.sql` | Only while no half case was ever booked or sold (else refuses, changes nothing): every function back exactly as before `_07`, whole-number stock, the unit columns dropped, one line per product again; keeps `half_price` | — |
| `sql/20261028_96_rollback_bad_order_custom_loss.sql` | Puts back the `20261028_04` record and void functions; keeps the column, the wider checks and every row | — |
| `sql/20261027_90_rollback_per_item_discounts.sql` | Puts back the `20261027_01` functions exactly; keeps both columns and their values | — |
| `sql/20261027_91_rollback_driver_my_vehicle.sql` | Drops that function | — |
| `sql/20261027_92_rollback_expenses.sql` | Undoes `20261027_02`'s behaviour: puts back the 2 report functions exactly as `20261027_01` (same body hash) and drops `save_expense` / `void_expense`; keeps both tables, their rows and their protection | — |
| `sql/20261026_94_rollback_employee_mobile_sign_in.sql` | Undoes `20261026_01`: drops the tickets and the 4 functions and puts back the previous finish function; keeps every sign-in and link | — |
| `sql/20261025_96_rollback_product_images.sql` | Undoes `_06` (part A keeps pictures and paths; part B, commented, drops the columns) | — |
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

### `_05` + Edge Function `trimax-admin-media`: applied and deployed 2026-10-25

Checked afterwards: both functions match this file (md5); RLS on the ticket table, no API role can read
it; only `service_role` can redeem, only the owner can issue; function ACTIVE with verify_jwt off; no new
security advisor warnings. The live end-to-end check (a real photo through the function) is done from
the Desktop, because this workspace cannot reach supabase.co. Steps kept for a fresh project:

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

### `_06` + Edge Function v2 (Stage F): applied and deployed 2026-10-26

Checked afterwards on production:
- The 4 functions match this file (md5 of the bodies).
- Grants: only the owner can issue picture tickets or set pictures; `trimax_list_products` is `authenticated` only; redeem is `service_role` only.
- `product-images` is public, JPEG only, 2 MB, with no storage policies.
- No rows changed (7 products, none with a picture; 0 tickets).
- The Edge Function is version 2, ACTIVE, verify_jwt off; its `handler.ts` is byte-identical to this repo (sha256).
- No new security advisor findings.

The live upload check (a real picture through the function) is done from the Desktop, because this workspace cannot reach supabase.co. Steps kept for a fresh project:


1. SQL Editor: run `20261025_06_product_images.sql`.
2. Redeploy `supabase/functions/trimax-admin-media` (`index.ts` + `handler.ts`, verify_jwt = false). v2 adds
   upload (JPEG body + `x-trimax-ticket` header, max 2 MB, magic bytes checked, never overwrites) and
   delete; viewing works exactly as before. Either order is safe: v2 treats tickets without an action as
   "view", and v1 refuses product-images tickets.
3. Desktop: Inventory shows a Picture column and a picture box (choose / preview / remove), saved with
   Add product / Update product. Android: pictures in the Packages tab and in Booking > Items.

Tests: `sql/tests/42_product_images_tests.sql` (26 checks + rollback/re-apply) and the Edge Function tests
(21 checks, including upload and delete).

### `20261027_01` (sales, stock, payroll, customer photos, discounts): applied 2026-10-27

Applied with the owner's approval as `20261027_01_sales_inventory_payroll_part1..3` (sections 1-5, 6, 7-11;
each part carries the privileges of what it creates). Before: production's schema equalled the repository
rebuild (84/84) and a dry run on a copy of production's rows changed no value. After: 100/100 fingerprints
equal a database built from the file, and no existing value changed. Order that was followed: back up → apply `20261027_01` → redeploy `trimax-admin-media` (adds
`customer-photos` to the buckets the Admin may view) → install the Desktop build → give Agents the new
APK the same day (an older APK cannot add a new customer once the photo is required). Details, rules and
rollback: [SALES_PROFIT_PAYROLL.md](SALES_PROFIT_PAYROLL.md).

Tests: `sql/tests/45_sales_inventory_payroll_tests.sql` in `run_tests.sh` (full suite: 472 checks, ALL
DATABASE TESTS PASSED on 2026-10-26, including rollback and re-apply) and the Edge Function tests (22
checks, with a customer-photo case).

### `20261027_02` (other expenses in net profit): applied 2026-10-27

Applied with the owner's approval (about 03:36 Manila, one transaction). Before: production equalled the
repository rebuild through `20261027_01` (100/100 fingerprints); a dry run on a copy of production's rows
(passwords and contact details masked) changed no row and gave the same report results. After: 108/108
fingerprints equal the rebuild through `20261027_02`; the row checksums of all 24 existing tables and the
`20261027_01` report columns (4 periods + 2 trends) are unchanged. Security advisors: no new warning (the
two new tables show the same "RLS on, no policy" INFO as every TRIMAX table). No full data dump was taken
(the update changes no row); undo with `20261027_92`. Details: [SALES_PROFIT_PAYROLL.md](SALES_PROFIT_PAYROLL.md).

Tests: `sql/tests/46_expenses_tests.sql` in `run_tests.sh` (50 checks + rollback and re-apply; ALL DATABASE
TESTS PASSED 2026-10-27). Also checked: after `20261027_02` + `20261027_92` the two report functions have
the same body hash and grants as after `20261027_01` alone.

## Rebuilding a database from this repository (checked 2026-10-26)

`sql/tests/rebuild_check.sh` builds a disposable database from the SQL files only, in this order:
1. `20261026_00`
2. `20261023_01`, `20261023_02`
3. `20261025_01` to `_06`

It then compares the result with production's schema fingerprints (`sql/tests/fingerprints.sql`, saved in `sql/tests/production_fingerprints_2026-10-26.txt`). The 80 fingerprints match. They cover:
- every column, constraint, index, RLS flag and API grant of the 19 tables;
- all 39 functions with their grants;
- the 4 triggers, the 4 storage policies and the 3 buckets.

To check production again later, run `sql/tests/fingerprints.sql` in the Supabase SQL editor (read-only), save the output and pass that file to the script.

