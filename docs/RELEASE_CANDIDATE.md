# TRIMAX release candidate 1 (Stage H)

Status: **draft, not approved.** This file is the Stage H checklist from
`STAGES_C_H_IMPLEMENTATION_ROADMAP.md`. Nothing here merges branches, publishes an installer/APK,
creates accounts, sends staff emails or changes the live database. The same file is in both repositories.

| | Desktop (`GitBreakin/TrimaxSystem1`) | Android (`Andrizzzz/TRIMAX`) |
| --- | --- | --- |
| RC branch | `release/rc-1` | `release/rc-1` |
| Contains | Stages B, C, E, attendance, F, G, H fix, plus the earlier integration/SMTP work not yet on `master` | Lance's `644b597` UI work, Stages D, E, attendance, F, G |
| `master` merged in | yes (`518df3c`, no conflict) | yes (`1747cab`, conflict resolved, see P2-1) |
| Draft PR | `release/rc-1 → master` | `release/rc-1 → master` |

## Findings (most severe first)

| ID | Severity | Finding | Fix / owner | Status |
| --- | --- | --- | --- | --- |
| P0-1 | Privacy | `master` of the **public** Desktop repo still tracks `receipts/Receipt_7.pdf` and `Receipt_8.pdf` (customer name, address, contact number). Receipt_9 and older copies are in history. | Draft PR `hotfix/remove-tracked-receipts → master` removes them from the tree and ignores `receipts/`. History needs the repo made **private** (owner decision) or an approved history cleanup. | Waiting for approval |
| P0-2 | Security | The old database password is in the **public** Desktop history (`trimax.properties` and an old `DatabaseConnection.java`, Oct 17–22). | Confirm the Supabase database password was reset after Oct 22. If not, reset it now and update `trimax.properties` on each PC. Not tested here (the old password is never used). | Lance to confirm |
| P1-1 | Security | 7 live accounts (1 Admin, 2 Agents, 3 active Drivers, 1 inactive Driver) still store their password as plain text; each is only hashed when that person signs in. | RC adds **Settings → Password storage → Secure them now** (PBKDF2, same passwords, audited, skips accounts changed meanwhile). Run once after installing the RC. | Code ready; run needs approval |
| P1-2 | Security | Supabase Auth "leaked password protection" is off (advisor WARN). | Supabase dashboard → Authentication → password security. May need a paid plan. | Lance |
| P2-1 | Merge | Android `master` `1747cab` and the RC's `cf09946`/`ab62a61` set different liquid-glass opacity. A plain merge mixed them. | RC keeps the later "crystal clear" values (the RC tree equals `feature/stage-g-reports`). | Check on a phone |
| P2-3 | Usability | Agents & Drivers: the **Create Employee Account** button wrapped out of sight on common screen widths (found by Lance, 2026-10-26). | Fixed in the RC: the button has its own row, and toolbar rows now grow when their controls wrap (all 5 pages that use them; checked at 1280, 1536 and 1920 px and after resizing). | Fixed |
| P1-3 | Usability / security | An Agent or Driver created on the Desktop could not sign in on the phone until someone added the user in the Supabase Dashboard by hand and pressed "Link mobile sign-in". The provisioning queue meant for this had no caller, and its finish function audited as `provisioning`, which production's `audit_log` foreign key refuses, so it could never have worked. | Branch `feature/auto-mobile-sign-in` (Desktop) + `feature/setup-code-sign-in` (Android): Create Employee Account creates and links the Supabase sign-in through a one-time ticket and the Edge Function `trimax-accounts` (secret key only on the server); the person sets the phone password with the emailed code. `sql/20261026_01` (+ rollback `_94`). Applied + deployed 2026-10-26 (approval). Live: sign-ins are created and linked (agent_kenjie 13:53). Lance found the emailed code confusing (typed into the sign-in screen), so new accounts now get a temporary password (`Name-XXXX`, shown once + emailed, must be changed at the first sign-in); Edge Function v2 deployed 2026-10-26 15:31 (approval) |
| P2-4 | Usability | **Create Employee Account** stopped with "Email is not configured" on Lance's laptop (2026-10-26). The email settings were read only from Windows environment variables, which were never set; nothing was saved. | Fixed in the RC: the `smtp.*` keys can go in the private `trimax.properties` (environment variables still win). The message names the missing keys and the file. **System Settings → Email** shows the status and sends a test email. A failed send says why (for example, Gmail needs an App Password). Sending now always uses TLS 1.2+ and checks the server certificate. See `SMTP_EMAIL_SETUP.md`. | Fixed; each PC needs the Gmail App Password in `trimax.properties` |
| P2-2 | Exposure | Both repositories are public. The Android app's Supabase key is a publishable key (meant to be public); that is fine. | Owner decision on visibility. | Owner |
| P3-1 | Performance | Advisor INFO: 3 foreign keys without an index, 4 unused indexes. | Leave until there is real traffic. | Later |

Intended, checked and not a finding:

- **RLS with no policies (19 tables, advisor INFO).** Phones read and write only through the `trimax_*` functions, and the Desktop connects as the owner.
- **13 SECURITY DEFINER functions callable by signed-in users (advisor WARN).** These are the mobile API. Checks:
  - Every function has a fixed `search_path`.
  - `anon` can execute none of them.
  - `authenticated` can execute only those 13.
  - The 3 `trimax_service_*` functions are `service_role` only.
  - Every `trimax_private` function is owner only.
- **No table is readable or writable by `anon` or `authenticated`.**

## Lance's unpushed commit `ed5b7d5` (`fix/driver-started-delivered`, pushed 2026-10-26)

This commit was found in the hygiene check on the laptop (built on an older base, `e75c8c6`).

**Already in the RC:** its delivery rules (ASSIGNED -> STARTED -> DELIVERED, FAILED once started) and the proof screen sending the status the server reported.

**Ported into the Android RC:**
- Hero card main button follows the server's status:
  - A booking waiting for the administrator now opens the details. Before, it tried to start and the server refused.
  - A second tap or a refresh cannot send Start twice.
  - Navigation opens with the saved booking.
- The details screen reloads after a refused or timed-out step.
- Tests with real server replies (start, repeated start, drop-off) and the request sent to Supabase.
- `docs/MOBILE_INTEGRATION.md` brought up to date.

**Not ported (Lance decides):**
- Its button wording ("DROP OFF / COMPLETE DELIVERY", hero card "START DELIVERY"). The RC keeps Lance's later labels: "START NAVIGATION" and "DROP-OFF PHOTO & PROOF OF DELIVERY".
- A "next:" hint on the deliveries list.
- A message about outdated server rules. It no longer applies: the live server has the new rules.

## Database: live compared with the reviewed files (read-only, 2026-10-26)

A database was built locally from the repository files in order:

- `20261023_01`, then `20261025_01` baseline, `_02`, `_03`, `_04`, `_05`.
- Plus `20261023_02` for RLS on the legacy tables.

It was then compared object by object with production:

| Object | Result |
| --- | --- |
| 37 functions (body, SECURITY DEFINER, search_path, arguments with defaults, result, grants) | **identical** (live bodies use Windows line endings; ignored) |
| Columns, constraints and indexes of every table the migrations create (`admin_notifications`, `user_locations`, `delivery_proofs`, all `trimax_private` tables) | **identical** |
| Buckets `duty-photos`, `delivery-proofs` and the 4 storage policies | **identical** |
| RLS flags and table grants (19 tables) | **identical** |
| Legacy Desktop tables (`users`, `bookings`, `clients`, …) | now in `sql/20261026_00_legacy_tables_from_production.sql` (written from production's catalog). A database built only from the repository files is **identical** to production: 80/80 schema fingerprints (`sql/tests/rebuild_check.sh`). |
| Edge Function `trimax-admin-media` | v1 matched repo `5db009c`; **v2 deployed 2026-10-26** = repo `handler.ts` (sha256 identical) |
| Applied migrations on live | `_02`, `_03`, `_04`, `_05`, and `_06` (2026-10-26, with approval). `_07` is **not applied**. |

### Waiting for separate approval (in this order)

1. ~~`20261025_06_product_images.sql`, then deploy `trimax-admin-media` v2 (Stage F).~~ **Done 2026-10-26** with approval. Verified: function md5 and grants, bucket, no rows changed, handler sha256, advisors unchanged.
2. `20261025_07_alert_resolution.sql` (Stage G).
3. Running "Secure them now" once (P1-1). It changes 7 stored values.
4. ~~`20261026_01_employee_mobile_sign_in.sql`, then deploy the Edge Function `trimax-accounts` (verify_jwt = false) (P1-3).~~ **Done 2026-10-26 13:49** with approval. Verified: the 5 function checksums equal the tested files, grants (service role only), no row counts changed, advisors unchanged except the new ticket table (INFO).

Rollbacks: `_96` (Stage F, part A keeps pictures) and `_95` (Stage G, part A keeps resolutions).

## Tests run for this RC (cloud workspace)

| Area | Result |
| --- | --- |
| `sql/tests/run_tests.sh` on PostgreSQL 16 (all stages, rollbacks, re-apply) | ALL DATABASE TESTS PASSED (271 checks) |
| Edge Function handler (Node 22, fake Supabase) | ALL EDGE FUNCTION CHECKS PASSED (21) |
| Desktop: whole app compiled (real javax.mail 1.6.2 built from source; stand-ins for POI, PDFBox) + all 9 unit-test classes | 35/35 passed |
| Desktop: email with the real javax.mail 1.6.2 against local test SMTP servers, settings only in `trimax.properties`: plain, STARTTLS (default), port 465 SSL; wrong password, certificate for another name, nothing listening, unknown server, environment variable over the file | all sent or refused as expected, each with a plain-language reason |
| Desktop: Create Employee Account on a test database | email sent: account and hashed code saved, email has the username and code; wrong password or no settings: nothing saved |
| Desktop: report export equals screen, every tab and grouping | 111/111 |
| Desktop: password securing SQL on a test DB (compare-and-set, login still works, wrong password refused) | passed |
| Android: 12 of 13 unit-test classes (stand-ins for Android classes) | 46/46 passed |
| Desktop RC on Lance's laptop against production: an Attendance duty photo opened (2026-10-26 10:50; server shows the one-time ticket used and audit `media.view` success, so Desktop -> ticket -> Edge Function v2 -> Storage works end to end) | passed |
| Android on Lance's laptop (real SDK): `gradlew testDebugUnitTest` on `release/rc-1` `fa49ed1` (includes the `ed5b7d5` port), then `installDebug` on his phone (LAB-LX2) | BUILD SUCCESSFUL, installed (2026-10-26) |
| Android: `SupabaseClientTest` (needs OkHttp MockWebServer from Gradle) | **not run here** |
| Secret scan, all branches and history, both repos | P0-1 and P0-2 above; nothing else (only the publishable key and placeholders) |
| Branch ancestry and merge dry-run into `master` | both RCs contain `master` |

Not run here (no Android SDK, no Maven Central, no Windows, no phone): `gradlew test`, `installDebug`,
`mvn test`, real Excel/PDF files, live end-to-end tests.

## Gates for Lance (sign off each)

**Repository hygiene (both PCs, before anything is merged)**

- [x] Laptop `C:\Users\lance\AndroidStudioProjects\TRIMAX2` (2026-10-26: only `.idea/deploymentTargetSelector.xml` changed, 4 old stashes kept, nothing unpushed after `fix/driver-started-delivered` was pushed).
- [ ] Gigabyte `C:\Users\Gigabyte\IdeaProjects\TrimaxSystem1`:
  `git fetch origin`, `git status --short`, `git stash list`, `git log --branches --not --remotes --oneline`.
  Nothing unpushed. Stashes, `receipts/` and IDE files are kept as they are.

**Desktop (Windows, `release/rc-1`)**

- [ ] `mvn -q test` passes (or IntelliJ → Maven → Lifecycle → test).
- [ ] Sign in, wrong password message, lockout after 5 wrong tries, forgot password email (SMTP set).
- [ ] System Settings → Email says "Set up", and **Send test email** arrives (Gmail App Password in `trimax.properties`).
- [ ] Create employee account → setup email arrives; mobile link works.
- [ ] Booking review: confirm, assign driver and vehicle; a phone booking appears.
- [ ] Customers, Inventory (add/edit; pictures only after `_06` + v2), Drivers/Live GPS, Attendance photo opens.
- [ ] Analytics: each tab, group by week/month, export CSV, Excel and PDF and open them.
- [ ] Notifications: mark read; after `_07`, resolve an alert with a note.
- [ ] Settings → Check database; Password storage shows 7, then 0 after "Secure them now" (after approval).

**Android (physical phone, `release/rc-1`)**

- [x] `.\gradlew.bat testDebugUnitTest` passes and `.\gradlew.bat installDebug` installs on the phone (`fa49ed1`, 2026-10-26).
- [ ] Agent: sign in, create booking with a map pin, see it in Desktop; price list pictures (after `_06`).
- [ ] Driver: duty start/end with photo and GPS; Start delivery → Delivered with proof photo, or Mark failed with a note.
- [ ] SOS and problem report reach Desktop Notifications; offline report is sent later once.
- [ ] Reports screen: periods, "n/a" with no bookings, CSV opens in Excel without formulas.
- [ ] Theme (light/dark), bottom navigation glass looks right (P2-1), notifications, navigation.

**Cross-platform**

- [ ] Booking from Android → reviewed in Desktop → status visible on phone.
- [ ] Driver status changes match on both sides; proof photo opens in Desktop.

**Security**

- [ ] P0-1 decided (hotfix PR merged and/or repo private).
- [ ] P0-2 confirmed (password reset after Oct 22).
- [ ] P1-2 setting reviewed.

## Rollback plan

- **Code:** `master` is untouched until Lance merges. If a merged RC misbehaves, revert the merge commit on `master` (no force-push). The previous Desktop/APK builds stay usable against the same database.
- **Database:**
  - Only `_02` to `_05` are live. Their rollback files (`_99`, `_98`, `_97`) keep data.
  - `_06` and `_07` have their own rollbacks (`_96`, `_95`, part A keeps data).
- **Edge Function:** redeploy v1 from commit `5db009c` if v2 misbehaves.
