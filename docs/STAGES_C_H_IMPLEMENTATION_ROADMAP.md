# TRIMAX Stages C–H Implementation Roadmap

**Status:** Planning only — review required  
**Base:** `feature/stage-b-db-baseline`  
**Safety rule:** No merges to `master`, production deployments, live Supabase changes, account emails, or destructive cleanup without explicit approval from Lance.

This document turns the Stage A audit and Stage B database baseline into an ordered, reviewable implementation plan. It does not claim that Stages C–H have been implemented or tested.

## Operating rules

1. Work on a dedicated branch per stage, based on the approved prior-stage branch; open a draft PR for each stage.
2. Do not merge a PR, deploy a Desktop build, apply SQL to production, or create/send Auth accounts without explicit approval.
3. Never commit credentials, `trimax.properties`, SMTP passwords, access tokens, receipts, or local IDE state.
4. Do not rewrite public Git history. Rotate any exposed database credential separately and verify the new credential is not committed.
5. Before touching the live database, export and compare its current schema/functions/policies against the Stage B baseline. Keep a reviewed rollback script for every production migration.
6. Each PR must list files changed, behavior changed, tests run, tests not run, screenshots where relevant, rollback notes, and known limitations.
7. A successful source review is not a successful build. Validate Android on a physical device and Desktop on a supported Windows machine before approval.

## Dependency order

- **C — Desktop login redesign:** can begin from the reviewed Desktop integration tip, but do not merge the 26-commit Desktop feature chain as part of this stage.
- **D — Booking and customers:** depends on the reviewed shared-booking integration branch and Stage B database baseline.
- **E — Driver workflow:** depends on D's agreed booking/customer contract; Android duty/GPS work also needs physical-device verification.
- **F — Product images:** depends on an approved schema migration and storage bucket/policy design; migration remains a file until separately approved.
- **G — Reports and shared records:** depends on stable shared bookings/delivery records and the issue-report contract from Stage B.
- **H — Regression/security:** runs after C–G are reviewed and integrated into a test candidate; it does not itself authorize production rollout.

## Stage C — Desktop login redesign

**Goal:** Improve the login UX without weakening existing authentication.

### Scope
- Review `LoginPanel`, `AuthFrame`, `RegisterPanel`, and authentication/session code on the Desktop feature tip before editing.
- Add password show/hide control, clear loading/disabled states, keyboard navigation/focus, and useful non-secret error messages.
- Hide/remove public “Register Here” because it creates Pending Admin accounts; preserve the approved admin-created employee setup flow.
- Keep authentication, password hashing, lockout behavior, and session invalidation intact.
- Do not expose reset/setup codes in UI or logs. SMTP configuration must be validated without displaying credentials.

### Acceptance checks
- Valid/invalid login, locked account, inactive account, database unavailable, and SMTP-not-configured cases are handled.
- Password toggle changes only visibility; it does not clear or alter the password.
- Registration is not reachable from the public login screen.
- No secrets appear in source, logs, screenshots, or exception messages.
- Desktop build and manual login test pass on Windows.

### Deliverable
Draft PR for Stage C, screenshots of login states, test checklist, and a note identifying which authentication paths were preserved.

## Stage D — Booking and customers

**Goal:** Make Desktop and Android agree on booking/customer behavior and preserve manually pinned customer locations.

### Scope
- First map existing Desktop and Android booking models, RPC/function contracts, and the live Stage B SQL baseline; document field names and status transitions before changing them.
- Add server-backed saved customers/customer pins only after a migration and access-control review. Agents must access only the customer data permitted by the server contract.
- A manually selected map pin is authoritative for the booking; live GPS must not overwrite it.
- Keep the pickup hub fixed at **14.7826996, 121.0475865**. Do not ask the user to select a pickup hub in the customer form.
- Preserve Admin approval: new Agent bookings start as Pending; the mobile app must not approve/confirm them.
- Avoid duplicate clients/bookings and ensure refresh/reopen retains the saved destination coordinates.

### Acceptance checks
- Test current GPS and a deliberately different manually selected pin; saved coordinates remain the manual pin.
- Verify fixed hub coordinates, client matching, required fields, and Pending status.
- Verify Admin approve/reject/assign actions and mobile status refresh.
- Verify role restrictions using Agent and Admin test accounts; do not use production staff accounts for destructive tests.
- No schema changes are applied to production as part of the PR.

### Deliverable
Draft PR, API/data-contract note, migration and rollback files if required, plus a manual cross-platform test matrix.

## Stage E — Driver workflow and proof

**Goal:** Make duty state, GPS, and delivery proof reliable and consistent.

### Scope
- Test duty start/end photo upload, retry after network loss, and server-confirmed duty state.
- Ensure GPS tracking lifecycle survives activity navigation, theme changes, and app restart without leaking listeners or claiming “on duty” when the server says otherwise.
- Upload delivery proof photos to a reviewed storage path with ownership/role checks, progress/error state, retry, and no duplicate records.
- Keep driver navigation/header/bottom-navigation insets consistent across Deliveries, History, and Notifications.
- Confirm delivery transitions are enforced by server functions, not just by client-side buttons.

### Acceptance checks
- Duty start/end requires a photo and records the server result.
- Simulate denied permission, offline upload, timeout, retry, app restart, and sign-out.
- Verify a driver cannot read or overwrite another driver's photos or delivery proof.
- Test on a physical Android device; inspect logs for crashes and duplicate GPS listeners.
- Do not claim background GPS support unless a properly permissioned foreground service is implemented and tested.

### Deliverable
Draft PR, Android device test results, upload/storage policy review, and screenshots for the driver workflow.

## Stage F — Product images

**Goal:** Add controlled product image upload in Desktop and efficient display in Android.

### Scope
- Propose a migration for `products.image_path` and a product-image storage bucket; choose object naming, file-size/type limits, and access rules before implementation.
- Desktop inventory UI: upload, preview, replace, remove, upload progress, and recoverable errors.
- Android: load image by the approved path with placeholder, caching, and graceful handling of missing/invalid images.
- Avoid storing large image binaries in the products table; store a path/reference and keep secrets out of the client.
- Keep the migration and bucket/policy changes as reviewable files only until production approval.

### Acceptance checks
- Upload valid image; reject unsupported file types and oversized files.
- Replace/remove image and confirm UI consistency after reload.
- Check missing image, expired/unavailable URL, slow network, and offline states.
- Verify authorization: only approved roles can change product images; ordinary mobile users cannot upload or replace them.
- Check image loading performance and avoid fetching the same image repeatedly.

### Deliverable
Draft PR with migration, rollback/cleanup notes, storage policy tests, and Desktop/Android screenshots.

## Stage G — Reports and shared records

**Goal:** Give Admins useful operational visibility without inventing metrics or losing field reports.

### Scope
- Extend reports from shared source records: booking totals/statuses by date, delivery completion/failure, driver performance, product/item summaries, and CSV/PDF/XLSX export where supported.
- Add a server-side issue-report contract for SOS/problem reports that writes to `admin_notifications`; confirm severity, actor, booking/delivery reference, timestamps, and status fields.
- Ensure Android can submit an issue safely and the Desktop Admin can list, inspect, acknowledge, and resolve it.
- Use explicit date ranges, consistent timezone rules, empty/loading/error states, and server-backed data. Avoid deriving operational claims from incomplete or phone-only records.
- Restrict access by role and avoid including passwords, auth tokens, private customer details, or unnecessary location history in exports/notifications.

### Acceptance checks
- Compare report aggregates against a known test dataset and the source booking/delivery records.
- Test date boundaries, no-data range, failed query, export content, and CSV escaping.
- Submit an issue from Android test account and confirm it appears for Admin; verify an unauthorized role cannot view or resolve another user's private data.
- Validate notification read/acknowledge/resolve behavior and avoid duplicate alerts on refresh.
- Keep Stage B SQL changes separate and unapplied until separately approved.

### Deliverable
Draft PR, sample reports generated from test data, reconciliation results, and issue-report access-control tests.

## Stage H — Regression, security, and release candidate

**Goal:** Verify the combined candidate before any merge or rollout decision.

### Required gates
- **Repository hygiene:** inspect both local PCs with `git status --short`, `git stash list`, `git log --oneline -3`, and `git log --oneline origin/HEAD..HEAD`; preserve receipts, IDE state, stashes, and unpushed commits.
- **Android:** run `.\gradlew.bat test` and `.\gradlew.bat installDebug` where supported; manually test login, booking, map pin, duty photos, GPS, notifications, navigation, theme, and reports on a physical device.
- **Desktop:** build/test on Windows with dependencies available; manually test login, employee setup email flow, booking review, customers, inventory, driver/location panels, notifications, and exports.
- **Cross-platform:** verify a booking created on Android is reviewed in Desktop, status changes propagate back, driver delivery status is consistent, and issue reports reach Admin.
- **Security:** inspect RLS/function grants, fixed `search_path`, storage policies, role/active checks, secret scanning, logs, password-reset flow, SMTP setup, and leaked-password protection setting.
- **Database:** compare current production definitions to reviewed migrations; run migrations and rollback tests only on disposable/staging PostgreSQL first. Obtain separate explicit approval before applying any production SQL.
- **Release evidence:** capture before/after screenshots, commit SHAs, test logs, known limitations, rollback plan, and the exact changes awaiting approval.

### Acceptance checks
- No known P0/P1 defects; all failed tests are explained and tracked.
- Both applications build on their intended machines.
- No credentials or customer receipts are tracked.
- Database migration dry-run and rollback are demonstrated outside production.
- A release checklist is signed off by Lance before merge/deploy.

### Deliverable
Draft release-candidate PR/checklist. This stage does not merge branches, publish installers/APKs, create Auth users, send staff emails, or deploy database changes.

## Approval gates (must remain separate)

| Action | Required approval |
|---|---|
| Merge any stage PR to `master` | Explicit approval from Lance after review |
| Apply SQL, create/alter production bucket or policies | Explicit production-change approval |
| Create Auth users or send setup emails to staff | Explicit approval and verified recipient list |
| Publish/install a release build for staff | Explicit release approval |
| Close superseded PRs or delete branches | Explicit repository-housekeeping approval |

## Suggested implementation sequence

1. Review this roadmap and the Stage B baseline PR.
2. Stage C in isolation; build and test on Windows.
3. Stage D against a disposable/staging database and test Android ↔ Desktop booking behavior.
4. Stage E device tests for duty/GPS/proof.
5. Stage F storage/migration review, then upload and display tests.
6. Stage G issue-report and report reconciliation tests.
7. Stage H full regression and security review.
8. Present each PR and evidence separately; wait for approval before merge or production action.

**Current state:** This is a plan only. No Stages C–H code, migrations, deployments, live database writes, account creation, or merges are authorized by this document.
