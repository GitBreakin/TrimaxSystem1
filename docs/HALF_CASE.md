# Half case (2026-10-28)

Owner's request: "sa booking ng agent dapat pwede half case ... dapat may option din sa item na half case".
An agent (phone) and the Admin (Walk-In / In-House on the Desktop) can sell **half a case** of an item.

Owner's decisions:
- **Stock:** a half case takes **0.5** off the stock (stock can be 9.5).
- **Price:** the **Admin sets a half case price** per product. A product without one is sold by the whole case only.
- **Where:** **both** the agent's booking (Android) and Walk-In / In-House sales (Desktop).

Database update: `sql/20261028_07_half_case.sql`. Rollback: `sql/20261028_97_rollback_half_case.sql`.
The rollback only works while no half case was ever booked or sold. If one was, it refuses and changes nothing.

## For the Admin (Desktop)

**Inventory → Products → select a product → Half case price (₱)**, then **Update product**:
- Blank means whole cases only.
- The pricing line shows the half case's puhunan (half the case's), its patong, and what agents may take off it.
- If the half case price is below half the puhunan, the line warns that every half case is a loss.
- Turning it on, changing it or turning it off is written to the audit log.

**Stock** can now end in **.5**: the product list, Correct stock, Restock, Bad order and the Stock history show 9.5,
never 9.50.
- **Correct stock** takes whole or half cases (0.5, 1.5), not 0.3.
- Restock and Bad order stay in whole units.

**Walk-In / In-House → New sale**:
- The product list has a **Half case** column (the half case price, blank when none).
- **Add half case** (next to *Add to sale*) adds a line *Half case · Coke Case (24)* at the half case price. It is off for products without a half case price.
- The whole and half lines of one product share its stock. With 1.5 in stock, 1 case + 1 half case fits; a second half does not.
- On the receipt and in the sale details the line reads `Coke Case (24) (half case)`.
- Voiding the sale gives the 0.5s back.

**Bookings** show agent half case lines as `... (half case)`. **Cancel booking** gives back 0.5 per half case.

**Reports** count a half case as 0.5 sold:
- Sales & Profit (revenue and cost of goods at the half case price and puhunan).
- Sales & Inventory Intelligence.
- Analytics (cases ordered).

## For the agent (Android app)

**New Booking → items**:
- A product with a half case price has a second card, **"<name> (half case)"**, at the half case price.
- The card says *Half case · In stock: 7.5*.
- Both cards share the stock; the + button stops when the next whole or half case no longer fits.
- **Less** on a half case card goes up to the half case's own limit. That limit is half the minimum patong, and never below half the puhunan.
- The review step, the booking details and the driver's screens show `... (half case) x2`.
- The price list (Items) shows *Half case ₱ 95.00* under a product that has one.

The server prices every line again; the phone never sends prices.

## Database (`20261028_07`)

| Change | Notes |
|---|---|
| `products.stock`, `inventory_movements.quantity_change / stock_before / stock_after` → `numeric` | Whole or .5 only (checks `products_stock_half_chk`, `inventory_movements_half_chk`). Values are stored as 10, not 10.0 |
| `products.half_price` | Null = whole cases only; more than 0 |
| `booking_items.unit`, `sale_items.unit` (`WHOLE` / `HALF`, default `WHOLE`) and generated `stock_qty` (quantity, or quantity × 0.5) | Existing lines are all `WHOLE`. A HALF line's `unit_price` is the half case price and its `unit_cost` is half the puhunan |
| `booking_items`: one line per product **and unit** | Replaces `booking_items_booking_id_product_id_key` (one line per product) with `booking_items_booking_product_unit_key`, so a booking can have 1 case and 2 half cases of the same product. The rollback puts the old key back |
| `trimax_private.set_product_half_price(admin, product, price)` | Admin only, audited |
| Phone functions | `trimax_list_products` adds `half_price` and `half_max_discount_per_unit`. Quote, create booking and item discounts accept `"unit": "HALF"` per item (missing = whole, so older apps work as before) |
| Desktop functions | `create_sale` accepts `"unit": "HALF"`. Void, restock, correct stock, bad order and undo work in halves. The restock, correct-stock, bad-order and undo functions return numbers that can end in .5 |
| Reports | `sold_lines` uses `stock_qty`. `product_insights` returns the stock as a number |

Tests:
- `sql/tests/54_half_case_tests.sql`: 35 checks. `run_tests.sh` also applies, rolls back (every function back exactly, one line per product again), applies twice, and checks that the rollback is refused once halves exist.
- Desktop end to end on a local copy: 29 checks (Inventory, Correct stock, phone bookings, booking window, cancel, Walk-In sale, receipt, bad order, stock history, Intelligence, Analytics).
- Android: the model classes were compiled and tested (41 tests), and the phone's JSON was checked against a local database (7 checks).

## Install together

Apply `20261028_07`, then install the new Desktop and APK the same day. Older versions keep working, with these limits:
- An **older Desktop** shows a stock of 9.5 as 9.
- An **older Desktop** that cancels a booking with half cases gives back 1 per half case instead of 0.5.
- An **older APK** rounds 7.5 to 8 in the stock it shows (the server still refuses more than the stock).
- An **older APK** shows a half case line without "(half case)".
- An **older APK** cannot order half cases.
