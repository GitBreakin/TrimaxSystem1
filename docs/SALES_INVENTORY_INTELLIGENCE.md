# Sales & Inventory Intelligence and the supplier list (2026-10-28)

Panel recommendation: the system should not only show stock and sales, but say which products sell well,
which are slow or not selling, which have too much or too little stock, what to do, and alert the Admin.

Where: **Analytics & Reports → Sales & Inventory Intelligence**, the **Insight** column in **Inventory**, and
**Notifications** (category *Inventory insight*). Plus the **supplier drop-down** in Inventory.

## Which sales count

The same records as Sales & Profit, each line counted once:

| Counted | Not counted |
|---|---|
| Bookings with status **Completed**, dated when they were completed / delivered (`booking_sale_time`) | Pending, Confirmed, In Progress, Cancelled bookings |
| Walk-In / In-House sales with status **COMPLETED**, paid **or unpaid** (the stock left either way) | VOIDED sales |

- Booking line amount = quantity × (unit price − item discount per piece).
- Sale line amount = line total.
- Note: Sales & Profit counts an *unpaid* walk-in sale only once it is paid. Here, units and revenue include it, because the goods were sold.

## Rules

All thresholds are in `trimax_private.inventory_insight_settings`, one row, and can be changed without code. The values below are the defaults.

| Status | Rule |
|---|---|
| **Fast-Moving** | At least 1.0 sold per day on average **and** at least 5 sold in the period |
| **Critical / Low Stock** | Out of stock, **or** 29 or fewer (the Inventory's *Critical* level), **or** under 7 days of stock at the current rate |
| **Restock** | Under lead time + safety days of stock (3 + 3, **assumed**). Quantity = per day × (lead time + 14 days) − stock |
| **Slow-Moving** | Sold, but under 0.15 per day, while in stock |
| **Non-Moving** | In stock, no sale in the last 30 days, and known for at least 30 days. "Never sold" is told apart from "last sold N days ago" |
| **Overstock** | At least 20 in stock **and** (more than 60 days of stock at the current rate, **or** non-moving) |
| **Sales Dropped** | At least 10 sold in the previous period and 50 % fewer now |
| **Insufficient Data** | Recorded for under 14 days and nothing else applies |
| **Normal** | Nothing applies |

How the figures are worked out:
- **Per day** = units sold ÷ the days the product was known in the period. It is not shown when under 1 day.
- **Days of stock** = stock ÷ per day. It is only shown when per day > 0, so there is never a division by zero.
- **Trend** = % change against the previous period of the same length. When nothing was sold before, the trend says "new" (or nothing), never a made-up percentage.

A product can have several statuses at once: a fast seller can be low on stock, and a slow seller can be overstocked.
- Every status has its **reason** (with the numbers) and a **suggested action**.
- The list always shows the stock status (High / Moderate / Low / Critical / Out) next to it, unchanged.
- The analysis **never changes stock**.

## Limits of the data (stated, not hidden)

- **"Known since":** products have no "date added". The analysis uses the earliest of the product's stock history (`inventory_movements`, which only exists since Oct 27, 2026), its booking lines and its sale lines. Until a product has 14 days of history it is **Insufficient Data** for slow, non-moving and overstock.
- **Production has very few completed sales right now** (the demo data was cleared on Oct 27). Most products will show Insufficient Data or Normal until real sales build up.
- **Missing data for restock:** there is no supplier lead time, minimum stock, safety stock or purchase order in the database. Restock quantities use the assumed settings and say so, and incoming orders are not counted.
- **Stock-outs:** periods when a product was out of stock are not taken out of "per day".

## Admin alerts

`trimax_private.refresh_inventory_alerts(admin)` adds an `admin_notifications` row (kind *Announcement*, category *Inventory insight*, related product) only when a condition **newly** applies. The conditions are:
- possible overstock
- not selling
- fast seller running low
- restock recommended
- sales dropped
- out of stock after recent sales

Each alert has the product, stock, units sold and per day, the period (last 30 days), the reason and the suggested action. Clicking the alert, or **Open product**, opens the product in Inventory and marks the alert read.

**No duplicates:**
- `trimax_private.inventory_alert_state` remembers each product + condition. While the condition lasts, there is no new alert.
- When it clears and comes back, there is a new alert, but not within 7 days of the previous one.
- An advisory lock makes two Desktops refreshing at the same time give one alert.

**When it runs:** there is no scheduler on the server, so the Desktop runs it:
- shortly after sign-in
- every 15 minutes while the app is open
- when the Intelligence tab is opened

If no Desktop is open, alerts wait until one is.

**Access:** only the database owner (the Desktop) can run the analysis or read the notifications and settings. Agents and drivers are refused (tested).

## Supplier list

- `public.suppliers`:
  - Names are unique ignoring capitals and extra spaces.
  - It is filled once from the names the products already use. Similar names, such as "Coca Cola" and "Coca-Cola Company", are left as they are; the Admin decides.
  - A trigger adds any new name a product is saved with.
- `trimax_private.save_supplier(admin, name)`:
  - Active Admin only.
  - Returns the existing supplier instead of creating a duplicate.
  - Audited.
- Inventory → Product details:
  - **Supplier** is a drop-down, with **+ Add** to add a new supplier.
  - Search has a **Supplier** filter.
- `products.supplier` stays text, so phones and older Desktops keep working. Before the update is installed, the list is built from the products and a new name is saved with the product.

Also in this change: the **Refresh** button above the product list (Lance's change, kept as he wrote it).

## Files

- **Database:**
  - `sql/20261028_02_suppliers.sql`, with rollback `sql/20261028_92_rollback_suppliers.sql`.
  - `sql/20261028_03_sales_inventory_intelligence.sql`, with rollback `sql/20261028_93_rollback_sales_inventory_intelligence.sql`.
  - Tests: `sql/tests/50_suppliers_and_intelligence_tests.sql`, plus `run_tests.sh`.
- **Desktop:**
  - New: `InsightService`, `InsightRules`, `InsightsPanel`, `SupplierService`.
  - Changed: `InventoryPanel` (supplier drop-down and filter, Insight column, Refresh, `selectProduct`), `MainFrame` (Analytics tabs, open product, 15-minute alert check), `NotificationService` and `NotificationsPanel` (Inventory alerts, Open product).
  - Tests: `InsightRulesTest`.

## Checks run (on a disposable local PostgreSQL 16 and a test build; nothing on production)

- **`sql/tests/run_tests.sh`: ALL DATABASE TESTS PASSED.** This includes 41 new checks and rollback + re-apply. The checks cover:
  - high sales with low stock
  - high stock with few sales
  - no sales (never vs stopped)
  - a new product
  - zero stock
  - zero per day
  - cancelled, pending and confirmed bookings and a voided sale not counted
  - several sales of one product
  - an empty period
  - 7-day vs previous 7 days
  - alert de-duplication, clearing and coming back
  - read / unread
  - Admin vs agent access
  - stock never changed
  - suppliers
- **Desktop unit tests: 82 passed.** They ran on a `javac` build with stand-ins for javax.mail, PDFBox and POI. `PdfExportTest` and `ReceiptLayoutTest` were not run; run `mvn test` on a PC.
- **End-to-end against a local database with demo data (11 checks):**
  - Units sold equal an independent count of completed bookings and sales.
  - Every product has a status and reasons.
  - Alerts are not duplicated, and each is linked to its product.
  - The supplier list, add, and add again in other capitals work.
  - The six Intelligence tabs show.
  - Opening a product from the analysis selects it in Inventory.
  - The supplier drop-down shows the product's supplier.
  - The Insight column is filled.
  - The supplier filter works.
- Not checked on production data or on the Android app (no change there).

## Needs the owner's approval

Apply `20261028_02_suppliers.sql` and `20261028_03_sales_inventory_intelligence.sql` to production. Until then:
- the Intelligence tab says "not set up yet";
- the Insight column is empty;
- the supplier drop-down lists the products' suppliers, and a new one is saved with the product.
