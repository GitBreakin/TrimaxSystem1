# Bad orders (BO): broken, damaged and expired stock (2026-10-28)

Owner's request: record in Inventory when items are broken, damaged, expired or otherwise bad (a "bad order"),
so they leave the stock and the loss is counted.

Owner's decisions:
- The loss (the puhunan of the bad pieces) **is subtracted from net profit**.
- Record **what happened to them**: thrown away, or returned to the supplier.

## How it works for the Admin

**Inventory → Products → select a product → Bad order...** (next to Restock and Correct stock):

| Field | Notes |
|---|---|
| Bad pieces | 1 up to the stock on hand |
| Reason | Broken, Damaged, Expired, Defective, Spoiled, Other (Other needs a note) |
| What happened to them | Thrown away / Returned to supplier |
| Date found | Today by default; not in the future, at most a year back |
| Note | Optional, up to 300 characters |

The dialog shows the result before saving, for example `Stock 71 → 67 · Loss ₱720.00 (4 × ₱180.00 puhunan)`.

When it is saved:
- The stock goes down at once.
- The **Stock history** gets a *Bad order* line, linked as "Bad order #N".
- The audit log records who did it.

**Inventory → Bad orders** (new tab):
- **Filters:** product, reason, what happened, the date-found range, and *Show undone*.
- **Totals:** loss (puhunan), pieces, number of bad orders, how many have no puhunan, and a line *by reason*.
- **List:** each bad order with its status, *Counted* or *Undone*. Double-click a row for all its details (supplier, puhunan per piece, who undid it and why).
- **Record bad order...:** choose any product, then the same dialog as above.
- **Undo...:** for a mistake. A reason is required. The pieces go back into stock (*Bad order undone* in the Stock history) and the loss no longer counts. The bad order stays on record; nothing is ever deleted.
- **Export:** CSV, Excel or PDF, with totals by reason.

**Correct stock** keeps its reasons. When *Remove* with *Damaged* or *Expired* is chosen, it now points to **Bad order...**. A correction does not count a loss, so a bad order is the right way for those.

**Sales & Profit:**
- A new card, **Bad orders (puhunan lost)**, says how many bad orders have no puhunan.
- Net profit = gross profit − payroll − other expenses − **bad orders**.
- The trend line, the exports and the Dashboard wording say the same.

## Loss set by hand (2026-10-28, `sql/20261028_06_bad_order_custom_loss.sql`, applied to production ~23:00 with the owner's approval)

Owner's request: sometimes only part of one item is bad, for example one bottle of "Sprite Mismo - 1 pack (6pcs)", worth about ₱10. The puhunan, though, is per pack.

- In the Bad order dialog, **Loss to deduct** has two choices:
  - **Puhunan × pieces** (as before)
  - **Other amount: ₱ ___**, where the Admin types the peso loss
- With **Other amount**, the pieces may be **0**: only part of one item was bad. The stock stays the same and only the amount is deducted from net profit, by the date found.
- With 1 or more pieces, the stock goes down as usual, but the loss is the typed amount.
- In the Bad orders tab:
  - such a loss shows "by hand", and 0 pieces shows "part"
  - the row details and the export say how the loss was set
- Undo works for both. Undoing a "part" one puts no stock back (none was taken out).
- Allowed amounts: more than 0, at most 2 decimals, up to ₱10,000,000. A product without puhunan can also get an amount set by hand.
- **Database:**
  - `bad_orders.loss_source` is `PUHUNAN` or `CUSTOM`; older rows are `PUHUNAN`.
  - `record_bad_order(..., p_custom_loss)` takes 9 arguments. The 8-argument version stays and calls it without an amount, so a Desktop built before this update keeps working.
  - Rollback `20261028_96_rollback_bad_order_custom_loss.sql` keeps every row.
- **Checks (local only):**
  - `run_tests.sh`: ALL DATABASE TESTS PASSED, with 31 new checks
  - 92 Desktop unit tests passed
  - end-to-end, 26 checks passed, including "Other amount" with 0 pieces and ₱10 in the dialog: the stock stays the same, and the tab shows "part" and "10.00 by hand"

## Rules

- **Loss** = pieces × the product's puhunan at the moment it is recorded (kept on the bad order, so a later puhunan change does not change it).
- **No puhunan:** the stock still goes down, but no peso loss is counted. The screens say "no puhunan" so it is not hidden.
- **Which date counts:** bad orders count in Sales & Profit by the **date found** (Asia/Manila days).
- **Which bad orders count:** undone ones never count.
- **What does not change:** sales, cost of goods sold and gross profit. Bad orders are not sales.
- **Retries:** each dialog has one request id, so a double click or a retry after a lost connection records it only once.
- **Limits:** a bad order cannot take out more than the stock. A bad order of a deleted product cannot be undone (there is no stock to put back); its record and loss are kept.

## Database (`sql/20261028_04_bad_orders.sql`, applied to production 2026-10-28 with the owner's approval)

- **Stock history:** `inventory_movements` gains kinds `BAD_ORDER` and `BAD_ORDER_VOIDED` (the check constraint is replaced by the same list plus these two).
- **`public.bad_orders`:**
  - RLS on, no policies, no grants to anon / authenticated / service_role; the same for its id sequence.
  - A trigger refuses deletes and any change except linking the stock movement and the undo fields (once).
  - A product being deleted only empties `product_id`; the name is kept.
- **`trimax_private.record_bad_order(admin, product, pieces, reason, disposition, found_on, note, request_id)`:**
  - Active Admin only.
  - Locks the product and checks every field.
  - Same request id = the same bad order.
  - Audited as `inventory.bad_order`.
- **`trimax_private.void_bad_order(admin, id, reason)`:** Active Admin only; stock back; audited as `inventory.bad_order_void`.
- **Reports:**
  - `profit_summary` gains `bad_orders_loss`, `bad_orders_count` and `bad_orders_no_cost` at the end.
  - `profit_trend` gains `bad_orders` at the end.
  - Both subtract the loss from `net_profit`.
  - The Desktop reads columns by name, so an older Desktop keeps working (it shows the lower net profit without the bad-order line).
- **Rollback:** `sql/20261028_94_rollback_bad_orders.sql` puts back the reports of `20261027_02` and drops the functions. It keeps every bad order, its stock history and the stock.
- **Also in this change:** `20261028_02_suppliers.sql` now also revokes the suppliers id sequence from the API roles (same rule as the table).

Before the update is applied, the Desktop says so:
- **Bad order...:** "needs the database update 20261028_04_bad_orders".
- **Bad orders tab:** the same message.
- **Sales & Profit:** "Not counted yet".

## Files

- **Database:** `sql/20261028_04_bad_orders.sql`, `sql/20261028_94_rollback_bad_orders.sql`, `sql/tests/51_bad_orders_tests.sql`, `sql/tests/run_tests.sh`.
- **Desktop, new:** `BadOrderService`, `BadOrdersPanel`, test `BadOrderTest`.
- **Desktop, changed:**
  - `StockDialogs`: the Bad order dialog, and the hint in Correct stock.
  - `InventoryPanel`: the button and the tab.
  - `InventoryService` and `StockHistoryPanel`: kinds, the filter, the reference and totals.
  - `ProfitService`, `ProfitPanel` and `ProfitWidgets`: the bad-orders card, net profit wording, exports and trend.
  - `Db`: the readiness check.
  - `MainFrame`: page subtitles.

## Checks run (disposable local PostgreSQL 16 and a test build; nothing on production)

- **`sql/tests/run_tests.sh`: ALL DATABASE TESTS PASSED.** This includes 71 new checks: 63 in `51_bad_orders_tests.sql` and 8 for rollback and re-apply. They cover:
  - access: phones, anonymous callers and the service role are refused
  - the report columns stay the same and the new ones are only added at the end
  - every refusal: not an Admin, no request id, 0 or negative, more than the stock, unknown reason or disposition, Other without a note, a future or too-old date, a missing product
  - stock and its history line, the audit entry
  - retry safety
  - no puhunan
  - the loss in net profit by the date found, with the daily and monthly trend adding up to the summary
  - no delete or change
  - undo and its effect on profit, and no double undo
  - a deleted product
  - Correct stock and outside changes still recorded as before
- **Desktop unit tests: 87 passed** (5 new). `PdfExportTest` and `ReceiptLayoutTest` were not run here; run `mvn test` on a PC.
- **End-to-end against a local database with demo data: 23 checks passed, with screenshots.** They cover:
  - restock, then a bad order, and the retry
  - the effect on profit
  - the button, the dialog preview, too many pieces refused, and saving
  - the Correct stock hint
  - the Bad orders tab, totals, Undo from the tab, and Show undone
  - the Stock history filter
  - the Sales & Profit card and net profit
- Not checked on production data or on the Android app (no change there).

## Production

Applied on 2026-10-28 (about 22:20 Manila) with the owner's approval, after `20261028_02` and `20261028_03`. The checks before and after are in docs/DATABASE_BASELINE.md.
