# Products, stock, sales, profit and payroll (database update `20261027_01`)

Branch `feature/sales-profit-payroll` (Desktop) and `feature/customer-photo-discounts` (Android).
Everything below needs `sql/20261027_01_sales_inventory_payroll.sql` on the server. Until it is applied,
the Desktop keeps working as before: Inventory uses the old product form, and the new screens say
"needs the database update 20261027_01".

**Production status: applied on 2026-10-27 (about 02:45 Manila) with the owner's approval**, and the Edge
Function `trimax-admin-media` redeployed (v3) so the Admin can open customer photos. Checked afterwards:
production's schema equals a database built from the file (100/100 fingerprints); no existing product,
booking, line, customer or agent value changed. Next: install the new APK on every Agent's phone today.

## Who can do what

| | Admin (Desktop) | Agent (Android) | Driver |
| --- | --- | --- | --- |
| Products, puhunan, patong, minimum patong | yes | sees prices only | no |
| Restock, stock adjustments, stock history | yes | no | no |
| Walk-In / In-House sales, receipts, void | yes | no | no |
| Sales & Profit dashboard | yes | no | no |
| Payroll | yes | no | no |
| Other expenses (`20261027_02`) | yes | no | no |
| Discount on a booking (per item) | decides which products agents may discount; sees less per piece, original / discount / final and who gave it | gives it per item (Pending bookings), up to each item's maximum | no |
| Customer photo | views it on the customer | takes it (required for a new customer) | no |

The database checks every rule itself. The Desktop calls owner-only functions in `trimax_private`
that check the signed-in Admin. The phone calls `public.trimax_agent_*` functions that check the
signed-in Agent. Phones cannot read the new tables (RLS on, no grants).

## Products and pricing (Inventory → Products)

- **Puhunan** (capital cost), **patong** as a peso amount or a percent, and the **selling price**
  worked out from them: puhunan + patong, or puhunan × (1 + percent / 100), rounded to the centavo. The
  database recomputes the price on every save, so a price never disagrees with its puhunan and patong.
- **Minimum patong** (₱0 or more): the lowest patong an Agent's discount may leave. It sets the minimum
  price for discounts (below).
- **Editing a product never changes its stock.** Stock changes only through Restock, Adjust, bookings and
  sales, and each one leaves a stock movement. A new product can start with an initial stock (recorded as
  `INITIAL`).
- The same product name with the same supplier cannot be added twice.
- Products saved before this update have no puhunan. They sell as before, but they cannot be discounted
  and their cost is missing from profit until the Admin fills it in.

## Restock and stock history

- **Restock**: quantity, unit cost, note. The total cost is quantity × unit cost. Optionally the
  product's puhunan is updated to the new unit cost (the price follows its patong).
- **Adjust stock**: a + or − change with a required reason (count correction, damage, …). Stock can never
  go below 0.
- **Stock history** lists every movement: date, kind, quantity, stock before and after, unit cost,
  reference (booking, sale), who, note. Kinds: `INITIAL`, `RESTOCK`, `ADJUSTMENT`, `BOOKING`,
  `BOOKING_CANCELLED`, `BOOKING_CHANGED`, `SALE`, `SALE_VOIDED`. Movements cannot be edited or deleted.
- Each restock or adjustment dialog has its own request id, so a double click or a retry after a
  network error is saved once.
- **Restock is not revenue and not an expense in profit.** Profit uses the cost of the goods sold.
  Restock spending is shown separately on the dashboard.

## Walk-In and In-House sales (Sales → New sale)

- **Walk-In**: someone buys at the store, pays and takes the goods. No customer record (an optional name).
- **In-House**: an Admin sale for a customer in the Customers list.
- Cart: several products, quantity + / −, remove a line, a peso discount per line, live subtotal,
  discounts and total. Payment: Paid or Unpaid; method Cash, GCash, Bank transfer or Other; cash
  tendered and change.
- **Save** is one database transaction. It prices the lines from the database, takes the stock
  (refused if any line would go below 0), keeps each line's unit cost for profit, and numbers the
  receipt `TRX-YYYYMMDD-nnnnn`. The request id makes a retry return the same sale, never a second one.
- **Sales history**: search by receipt number or customer, and filter by type, status, payment and
  date range. Details show the lines, discounts, payment, and void information.
- **Void** (with a reason): the stock comes back (`SALE_VOIDED`), the sale stays in the history marked
  void, and the audit log records who and why. **Mark as paid** turns an Unpaid sale into a Paid one.

## Receipts

A4 landscape, two copies side by side (Cashier copy, Client copy), for bookings and sales. Long product
names wrap instead of being cut. The layout tightens to keep up to about 30 lines on one page; more lines
continue on further pages with the header repeated. Sale receipts show type, receipt number, payment
status and method, line discounts, and VOID when voided.

## Agent discounts on bookings (per item since `20261027_04`)

Owner's decision of 2026-10-27: the discount is **per item**, not one discount for the whole booking.

- The **Admin** decides per product: Inventory → "Agents may discount this item" (off by default).
- The **Agent** types a peso amount **off each piece** of an allowed item when creating a booking, or
  changes it while the booking is Pending (e.g. Sprite ₱25.00, less ₱2.00 = ₱23.00 a piece; Coke stays
  ₱25.00 when it is not allowed). The phone shows each item's maximum, the original price, the discount
  and the final price.
- **Limit per piece:** price − (puhunan + minimum patong). A product without puhunan, or not allowed by
  the Admin, cannot be discounted. The phone is told only the maximum per piece, never the puhunan.
- The server checks every line again when the booking is saved or changed (refusals: `discount_not_allowed`,
  `discount_not_configured`, `discount_too_big`; an older app's whole-order discount gets
  `discount_per_item_only` = "update the app"). Every change is in the audit log ("Sprite less ₱2.00 x 2").
- The Desktop booking details show "Less / piece" and "Line total" per item; the receipt prints the
  discount on each line; Sales & Profit counts the discounts as before (net sales = gross − discounts).
- Bookings from before keep their totals (their lines have no discount per piece).

## Sales & Profit dashboard

Periods: **Today, Yesterday, Last 7 days, This month, This year, Custom range**. Days are Philippine time
(Asia/Manila). A range from day A to day B includes both days.

| Metric | Counted |
| --- | --- |
| Revenue (gross) | Completed bookings (dated when completed) + Paid, not void sales (dated when sold) |
| Discounts | Booking discounts + sale line discounts |
| Net sales | Gross − discounts |
| Cost of goods sold | Quantity × unit cost **at the time of the sale/booking** (lines without a cost are counted and shown as a warning, not guessed) |
| Gross profit | Net sales − cost of goods sold |
| Payroll | Payroll records whose **pay period ends** inside the range; Paid and Unpaid shown separately; both are subtracted; void records never count |
| Other expenses (`20261027_02`) | Expenses **dated** inside the range (Electricity, Water, Rent, Fuel, Repairs, Supplies, Internet & phone, Taxes & permits, Other); Paid and Unpaid shown separately; both are subtracted; voided expenses never count |
| Net profit | Gross profit − payroll − other expenses |
| Shown, not subtracted | Unpaid sales (count and total), restock spending |

Pending, confirmed, in-progress, cancelled and failed bookings are not revenue. Until `20261027_02` is on
the server, the Other expenses card says "Not counted yet" and net profit is gross profit − payroll.
Charts show the trend by day, or by month for ranges over 92 days. Every period can be exported to CSV,
Excel or PDF (the export lists Other expenses paid / unpaid and has an Other expenses column in the
trend). Empty periods show an empty state.

## Payroll (Payroll)

- Employee, pay period (start–end), basic pay, allowances, deductions, **net pay = basic + allowances −
  deductions** (worked out by the database), status Unpaid / Paid with pay date and method, notes.
- One record per employee and period (void records excepted). Editing and voiding are audited; a void
  needs a reason and is kept in the history.
- History filters: employee, status, and the range in which the period ends. The summary shows totals
  for the filtered records.

### Employees without an app account (database update `20261029_01`, applied to production 2026-10-29)

Owner's request of 2026-10-29: helpers, cashiers, guards and others are paid too, not only agents and drivers.
Their pay must lower the profit as well.

- **Payroll → Employees...** opens the list of employees without an app account.
  - **Add employee...** asks for the full name, a position (type one, or pick Helper, Cashier, Warehouse staff, Security guard, Office staff or Mechanic) and an optional contact number (09XXXXXXXXX).
  - The same name twice is refused (any capitalization).
  - **Edit...** changes the name, position or contact number. Records already saved keep the name they had.
  - **No longer working here** hides them from new payroll records; their records stay. **Working here again** undoes it.
  - Adding and changing employees are audited.
- **New payroll record** lists them with the agents and drivers, for example *Juan Dela Cruz (Helper)*. The name is filled in from the list.
- The Employee filter above the list shows only their records.
- Their payroll lowers net profit like any payroll: **net profit = gross profit − payroll − other expenses − bad orders**.
- Without the update, "Other person (type the name)" still works, as before.

Database: `public.payroll_staff` (owner only, no API access). `payroll_records.staff_id` records which employee a
payroll record is for. A record is for an account or for an employee without one, never both.
`trimax_private.save_payroll_staff` adds and edits employees. `save_payroll` gets a 15-argument version with the
employee; the 14-argument version older Desktops call stays, and keeps a record's employee when it is edited.
Rollback: `sql/20261029_99_rollback_payroll_staff.sql`.

### Days worked from attendance (database update `20261030_01`, applied to production 2026-10-30)

Basic pay can be worked out as **days worked × daily rate**:
- The days of agents and drivers come from their Start / End duty (8 hours = 1 day).
- The days of employees without an app account come from **Attendance → Employees without an app...**, where the Admin marks Present / Half day / Absent.

See docs/PAYROLL_ATTENDANCE.md.

## Other expenses (Expenses, database update `20261027_02`)

Owner's request of 2026-10-27: business costs that are not payroll and not stock lower net profit.

- Date, category, what it was for, amount, **Unpaid / Paid** (with the date paid and Cash, GCash, Bank
  transfer, Check or Other), notes. The database records who entered it and when.
- **Net profit = gross profit − payroll − other expenses.** An expense counts by its **date** (Asia/Manila
  days), paid and unpaid alike, like payroll. Restocks are still not expenses: stock cost counts when the
  goods are sold (cost of goods sold).
- A wrong expense is **voided with a reason**, never deleted; a voided one cannot be changed. Every
  create, edit and void is kept in `public.expense_history` (the row before and after; append-only) and in
  the audit log. History... on the Expenses page shows it in words.
- Filters: category, status (unpaid and paid / unpaid / paid / voided / all), date range, This month /
  Last month, search in the description and notes. The cards total the filtered, non-voided expenses.
- A double click on Save saves once (`client_request_id`).
- Phones cannot see expenses (RLS on, no grants; the functions are for the database owner only).
- Without `20261027_02` on the server the Expenses page says it needs that database update and New
  expense is disabled. An older Desktop on a server that has it keeps working; its Sales & Profit shows the
  lower net profit but no expense line, so install the new Desktop the same day.

**Production status: applied on 2026-10-27 (about 03:36 Manila) with the owner's approval** (108/108
fingerprints after; no existing row or report value changed). Install the new Desktop build the same day.

Rollout that was followed: compare production with the repository rebuild (fingerprints) → dry run on a copy
of production's rows → back up → apply `sql/20261027_02_expenses.sql` (one transaction, re-runnable) →
install the Desktop build. No Edge Function and no APK change.

Rollback: `sql/20261027_92_rollback_expenses.sql` puts back `profit_summary` / `profit_trend` exactly as
`20261027_01` made them and removes `save_expense` / `void_expense`. It keeps every expense and history
row and their protection; re-applying `20261027_02` continues from the same data.

## Customer photos (Agents)

- A **new customer needs a photo** (camera or gallery). The phone uploads a JPEG to the private bucket
  `customer-photos`, into the Agent's own folder, then saves the customer. The server checks that the
  file is really there.
- A booking for a customer who is not saved yet is refused with "Save this customer with a photo first";
  the app opens Add customer, prefilled.
- Customers saved before this update keep a placeholder until someone adds a photo.
- The Admin opens the photo from the customer on the Desktop (one-time ticket through the Edge Function
  `trimax-admin-media`; no storage key on the PC).

## Rollout order (after approval)

1. Back up the database.
2. Apply `sql/20261027_01_sales_inventory_payroll.sql` (one transaction, re-runnable).
3. Redeploy `supabase/functions/trimax-admin-media` (adds the `customer-photos` bucket to what the Admin
   may view).
4. Install the Desktop build, then give Agents the new APK **the same day**. An older APK cannot add a
   new customer once step 2 is live (the server asks for a photo that the old app cannot send).
   Bookings for existing customers keep working on the old APK.
5. Fill in puhunan (and minimum patong where discounts are allowed) for the products.

Rollback: `sql/20261027_93_rollback_sales_inventory_payroll.sql` puts back the previous booking, booking
JSON and media-ticket functions and removes the new functions, triggers and policies. It keeps every
new table, column and row (sales, payroll, stock movements, photos).

## Tests

- Database: `sql/tests/45_sales_inventory_payroll_tests.sql` in `sql/tests/run_tests.sh` (disposable
  local PostgreSQL only); the full suite passed with 472 checks, including rollback and re-apply.
- Other expenses: `sql/tests/46_expenses_tests.sql` in `run_tests.sh` (50 checks, then rollback and
  re-apply; ALL DATABASE TESTS PASSED on 2026-10-27 on PostgreSQL 16.15).
- Desktop: `mvn test` (MoneyTest, SaleCartTest, ReceiptLayoutTest, ProfitPresetsTest, ExpenseServiceTest,
  PdfExportTest, and the earlier tests).
- Android: `./gradlew testDebugUnitTest` (DiscountCalcTest, DiscountQuoteTest, ServerCustomerTest,
  CustomerPhotosTest, CustomerPhotoDownloadTest, and additions to BookingRecordTest,
  CreateBookingRequestTest, CustomerDirectoryTest).
