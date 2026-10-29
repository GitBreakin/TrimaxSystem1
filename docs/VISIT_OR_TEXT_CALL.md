# Visit or Text / Call on agent bookings (2026-10-29)

The owner asked for this: "sa agent app daw dapat kapag mag add booking, may option don na visit or via text/call, kapag visit need ng picture tas papasok sa admin".

Database update: `sql/20261029_02_booking_order_via.sql`, **applied to production 2026-10-29 ~12:50** with the owner's approval. Rollback: `sql/20261029_98_rollback_booking_order_via.sql`. The rollback refuses, and changes nothing, once any booking records Visit or Text / Call.

## Agent app (New Booking → summary)

- A new card asks **How did you take this order?** with two choices, **Visit** and **Text / Call**. One must be chosen before **Confirm booking**.
- **Visit** shows **Take visit photo**:
  - The photo is taken with the camera only (no gallery). It is full size, turned upright, and stamped with the date, the time and the GPS. Without a location fix the stamp reads "GPS: not available".
  - **Retake visit photo** replaces the photo.
  - The choice and the photo are kept if the screen rotates.
  - If the agent leaves without booking, the photo is deleted from the phone.
- **Text / Call** needs no photo.
- What happens on **Confirm booking**:
  1. The visit photo is uploaded to the agent's own folder in the private `customer-photos` bucket. The file keeps the same name on every retry, so it is never stored twice.
  2. `trimax_agent_create_booking_v2` saves the booking and the choice together.
  3. If the server finds a problem with the choice or the photo, the card shows what to fix.

## What the server checks (`trimax_agent_create_booking_v2`)

- Every rule of `trimax_agent_create_booking` applies; that function is called unchanged inside v2.
- Visit or Text / Call is required.
- A visit's photo must be:
  - in the calling agent's own folder (`<auth uid>/visit_….jpg`);
  - actually uploaded within the last day;
  - not already used for another booking.
- The location is optional, but it must have both coordinates (latitude and longitude) or neither, and must be within range.
- The photo time cannot be in the future.
- A Text / Call order sends no photo.
- A retry with the same request id returns the booking already saved.
- Audit entry: `booking.order_via`.

## Admin (Desktop)

- **Bookings** has a new last column, **Taken**, showing *Visit*, *Text / Call*, or blank. Blank means a Desktop booking or one made by an older agent app. The column can be sorted.
- The **booking window** has a new **Order taken** row:
  - Visit with a photo: *Visit: the agent went to the customer (photo below, with location)*.
  - Text / Call: *Text / Call: no visit, so no photo*.
  - Agent booking from an older app: *Not recorded (older agent app)*.
- For a visit, a **Visit photo** card sits next to *Proof of delivery*.
- Clicking the photo opens it large, with the agent, the customer, the time and **Open location in map**.
- The photo is private and loaded with a one-time ticket, the same way as delivery proofs. Each view writes a `media.view` audit row.

## Compatibility

- **Older agent apps** keep calling `trimax_agent_create_booking`. Their bookings have no Visit / Text-Call value.
- **The new agent app needs this database update.** Without it, Confirm booking says "The server is not set up for the mobile app yet". Apply the update first, then install the new APK.
- **The new Desktop** reads the new columns through `to_jsonb`, so it also works before the update. The new column and row stay empty until then.
- **No new bucket, storage policy or Edge Function is needed.** The phone can already upload to its own `customer-photos` folder, and the admin can already view that bucket.

## Checked

- **Database tests:** `sql/tests/56_booking_order_via_tests.sql`, plus the rollback, re-apply and refused-rollback steps in `run_tests.sh`. The whole suite passes with 881 checks.
- **Dry run** on the local production-like copy:
  - Applied as the owner.
  - `trimax_agent_create_booking` is unchanged (`647eec5b…`).
  - Every other function is unchanged.
  - Only `authenticated` may call v2.
- **Desktop end-to-end test** on a local database (E2EVisit, 9 checks), using the real database calls as the agent:
  - The **Taken** column shows *Visit* and *Text / Call*.
  - The booking window shows the row and the photo. The photo is fetched with a ticket that the test photo service redeems through the real database function.
  - The large view and the map button work.
  - A Text / Call booking shows no photo card.
  - All 7 Desktop end-to-end scripts pass, with 115 checks.
- **Unit tests:** Desktop 106. Android model tests: 48, including `OrderViaTest`.
- **Not checked here:** the agent app screen on a phone, including the camera, GPS and upload. It was not built here because there is no Android SDK.
