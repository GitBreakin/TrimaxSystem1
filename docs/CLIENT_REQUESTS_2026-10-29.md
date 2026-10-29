# Client requests (2026-10-29 evening)

Database update: `sql/20261029_03_booking_edit_and_driver_summary.sql`.
- Rollback: `sql/20261029_97_rollback_booking_edit_and_driver_summary.sql`.
- It adds 3 functions and changes no table, column, existing function or row.
- The Desktop and the phone apps work without it. Until it is applied, **Edit order** is not shown and the driver app shows "–" for its numbers.

## 1. "Pag click ng booking, dapat lalabas kung ano inorder"

The booking window now opens with **Items ordered (N)**: every line, its quantity, price and line total, then the total.

Before, the list of items was the last thing in the window. On a laptop screen, with the photo cards on top of it, it was squeezed out of view.

The window now scrolls, so the order, the details, the photos and the history are never squeezed.

## 2. Change the order after booking ("tanggalin yung Sprite")

Booking window → **Edit order**. It is shown while the booking is **Pending, Confirmed or In Progress**.

What the Admin can do:
- Change a quantity: double-click it.
- **Remove item**.
- Add a product: whole or half case, with a quantity.
- Write a reason, which is saved in the booking history.

Nothing changes until **Save changes**. A confirmation lists every change and the old and new total.

What the server does, all at once or not at all (`trimax_private.update_booking_items`):
- A line that stays keeps its price and the agent's discount per piece. Only its quantity changes.
- A new line gets today's price, or the half case price.
- The stock moves by the difference:
  - removing gives stock back;
  - adding takes stock, and more than what is left is refused.
  - The stock history shows these moves as "Booking changed".
- The totals are recomputed.
- The history shows *Order changed: Sprite Case: removed (was 3); total 645 -> 345; note: ...*.
- If someone else changed the booking after the window was opened, nothing is saved and the window reloads.
- The last item cannot be removed. Cancel the booking instead.
- A **Completed** booking cannot be changed, because it is already in Sales & Profit. A **Cancelled** one cannot be changed either, because its stock was already given back.

Cancelling after a change gives back exactly what the booking holds at that time.

## 3. Receipt name: JAIMILA

Printed and saved receipts, for bookings and for Walk-In / In-House sales, now start with **JAIMILA Consumer Goods Trading** instead of TRI-MAX.

The name is set in one place: `ReceiptPdfGenerator.BUSINESS_NAME`. The app itself is still called TRI-MAX.

## 4. Agent and driver apps: one greeting and real numbers

**Driver dashboard**
- The greeting: before, the screen showed a fixed "Good Morning," with "Good evening, Name" under it. Now it shows one greeting from the phone's clock, e.g. "Good evening," with the name below.
- The stats row: before, it showed made-up numbers (12 / ₱1,200 / 98% On Time). It now shows **Delivered today**, **Earnings (month)** and **To deliver**, from the server.

**Agent dashboard**
- Before, it said "Hello," above "Hi, Agent Name! 👋". It now uses the same single greeting.

**Agent Reports**
- The lines under the counts were sample percentages. They are now computed.

**Other fixes**
- **About** shows the installed version.
- After a booking is saved, **View booking** no longer opens a sample booking number.

## 5. Driver earnings

The server function `public.trimax_driver_summary()` works for the signed-in driver only. It gives:
- **Earnings (month)**: this month's net pay from **Payroll** (records not voided).
- What the driver delivered today and this month: count and amount.
- What is still to deliver.

Tapping **Earnings** on the driver dashboard lists:
- this month's pay;
- the amounts delivered;
- the last 6 payroll records, marked paid or not yet paid.

Earnings come from what the Admin enters in **Payroll**. A driver with no payroll record sees ₱0.

## 6. Bookings: how much a driver delivered

The Bookings page has a totals line under the filters. It counts every page of the filtered list:
- *This list: 41 bookings · ₱107,914.00 (cancelled not counted) | Delivered: 16 bookings · ₱52,896.00*
- With one driver chosen, it reads *Delivered by Maria Santos: …*.

There is also a new status filter, **Delivered (by the driver)**. It matches bookings that are Completed or that the driver marked delivered on the phone. With this filter, **From / To** mean the day the booking was delivered.

To see what one driver delivered on a day: choose the driver, choose **Delivered (by the driver)**, set From / To to that day, then **Apply filters**.

## Checked

- **Database tests:** `sql/tests/57_booking_edit_tests.sql` (26 checks: refusals, removal, change while In Progress, half case, stock, history, cancel after a change, driver summary). The rollback / re-apply steps were run too. The whole suite passes, with 940 checks.
- **Dry run** on the local production-like copy: the 3 new functions were added and every other function stayed the same. Only the Desktop (the owner) can change an order, and only signed-in phones can call the driver summary.
- **Desktop unit tests:** 112 pass, including `OrderEditTest`.
- **Desktop end-to-end tests:** all 8 scripts pass, with 131 checks. The new E2EEdit (15) covers:
  - the window at 640 px high shows both lines;
  - Edit order, Remove item, the confirmation, saving, the stock, the history;
  - adding back;
  - the totals for Maria with the Delivered filter.

  E2EVisit also checks that one item still shows at full height next to a photo card.
- **Android model tests:** 55 pass, including `DriverSummaryTest` and `ReportTextTest`.
- **Not checked here:**
  - The phone screens (no Android SDK).
  - A printed or saved receipt PDF (PDFBox is not available here). `mvn test` runs the receipt tests.
