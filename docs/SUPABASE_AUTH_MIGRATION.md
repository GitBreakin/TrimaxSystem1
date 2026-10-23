# TRIMAX desktop migration plan: Supabase Auth and shared delivery bookings

## Current state
The desktop client currently opens direct PostgreSQL JDBC connections using `TRIMAX_DB_URL`, `TRIMAX_DB_USER`, and `TRIMAX_DB_PASSWORD` (or a local properties file). `BookingService.createBooking` inserts into the legacy `bookings` and `booking_items` tables, requires a driver/vehicle at booking time, and deducts product stock. The Android delivery flow instead collects customer/address/coordinates/schedule and saves locally. These are not equivalent data models.

## Required target architecture
- Supabase Auth owns identities and sessions.
- `public.profiles.id` equals the matching `auth.users.id`; role values are assigned only by an administrator/trusted process.
- Android Agent submits a pending delivery with no driver assigned.
- Desktop Admin reads pending deliveries and assigns an active driver/vehicle.
- Android Driver reads only assigned deliveries and updates allowed delivery statuses.
- Both clients operate on the same `public.delivery_bookings` data (or a deliberately designed API contract backed by it).

## Desktop work that must happen before production
1. Replace client-distributed database-owner credentials with a trusted backend/API for privileged operations. Do not bundle database passwords, service-role keys, or secret keys in a desktop installer.
2. Add Supabase Auth sign-in and securely persist/refresh tokens. Do not treat local role values as authorization.
3. Add an admin delivery queue using `delivery_bookings`; keep the existing product/stock order flow separate until an explicit mapping is agreed.
4. Implement assignment as a privileged, validated operation: confirm driver is active, vehicle is available, booking is pending/unassigned, then commit assignment atomically and record an audit event.
5. Add status/history/notifications and safe concurrency handling.
6. Test least-privilege access with separate Admin, Agent, and Driver accounts, plus unauthenticated and cross-user access attempts.

## Data mapping
| Android delivery field | Shared model |
|---|---|
| Booking code | `delivery_bookings.booking_code` |
| Authenticated Agent | `submitted_by` (Auth UUID) |
| Customer name / phone / email | `customer_name`, `customer_phone`, `customer_email` |
| Service / quantity / price | `service_name`, `quantity`, `unit_price` |
| TRIMAX hub | `pickup_address`, `pickup_latitude`, `pickup_longitude` |
| Chosen destination | `destination_address`, `destination_latitude`, `destination_longitude` |
| Date/time / notes | `scheduled_at`, `special_instructions` |
| Admin assignment | `assigned_driver_id`, `assigned_driver_legacy_username`, `vehicle_id` |
| Workflow | `status`, `created_at`, `updated_at` |

## Migration safety
- Apply the additive SQL migration to a TEST Supabase project first, not production.
- Back up existing data before any schema or identity migration.
- Never migrate plaintext passwords or assume existing PBKDF2 hashes are compatible with Supabase Auth.
- Create/invite users only after verifying ownership of their email; map each profile UUID to the correct Auth user.
- Do not auto-grant roles from user-editable metadata.
- Review and tighten driver status transitions server-side before production.
- Preserve legacy tables during staged rollout; do not drop or rename them until parity and rollback are proven.

## Acceptance tests
- Agent A can create a pending booking and see it after restart/login.
- Agent A cannot see Agent B's bookings or assign a driver.
- Admin sees all delivery bookings and can assign a valid driver/vehicle once.
- Driver A sees only bookings assigned to Driver A and cannot reassign them or change protected fields.
- Driver status updates are visible to Agent and Admin after refresh.
- Concurrent admin assignment attempts cannot assign one booking twice.
- A desktop build contains no privileged database credentials or Supabase secret/service-role keys.
- Legacy product orders and stock accounting still work independently.
