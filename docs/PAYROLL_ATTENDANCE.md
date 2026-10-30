# Payroll from attendance (2026-10-30)

Owner's request: "sa attendance sa payroll, dapat automatic na papasok kung ilan pinasok ng agent and driver dipende sa attendance nila, yung ibang employee, lagay ka attendance rin nila kahit si admin na lang mag lagay ng attendance".

Owner's choices:
- **Pay** = days worked × daily rate.
- **Agents and drivers:** 8 hours of duty = 1 full day.
- **Employees without an app account:** the Admin marks Present / Half day / Absent.

Database update: `sql/20261030_01_payroll_attendance.sql`. **Applied to production on 2026-10-30 (~11:20)** and checked afterwards (see docs/DATABASE_BASELINE.md).
- Rollback: `sql/20261030_99_rollback_payroll_attendance.sql`. It is refused once any attendance or days are saved.
- It adds 1 table, 2 columns, 2 checks and 3 functions. It changes no row and no existing function.
- The phones are not touched. No new APK is needed.
- Until it is applied, the Desktop works as before: no Days worked / Daily rate fields, and the attendance button explains that the update is needed.

## 1. Agents and drivers: days from Start / End duty

The app already records **Start duty** and **End duty** with a photo (Desktop → **Attendance**). Payroll now counts them:

- Each Start duty is paired with the next End duty, if it comes before the next Start duty and within 24 hours.
- The hours count on the day the duty started (Philippine time).
- Each day counts **hours ÷ 8, at most 1**:
  - 8 hours or more = 1 day;
  - 6 hours = 0.75 day;
  - 4 hours = 0.5 day.
- A Start duty with no End duty is **not counted**. The payroll window names that day so the Admin can check it with the driver or agent, and type the days by hand if needed.

## 2. Employees without an app account: the Admin marks the day

**Attendance → Employees without an app...** lists the employees from **Payroll → Employees...**.

- Choose the day (◀ ▶, **Today**, or type it and **Show**). Future days are refused.
- For each employee, choose **Present**, **Half day**, **Absent**, or **—** (not marked). A note is optional.
- **Mark everyone present** fills in the ones not marked yet.
- **Save attendance** saves only what changed. Closing with unsaved changes asks first.
- **Days this month** shows the days counted from the 1st of the month to the chosen day.
- Present = 1 day, Half day = 0.5, Absent = 0.
- Saving is in the history (audit log), for example *2026-10-30: 3 present, 1 half day*.

## 3. Payroll: Days worked × Daily rate

**Payroll → New payroll record**: choose the employee and the pay period.

- **Days worked** is filled in from attendance. The line under it shows how it was counted, for example:
  - *From attendance: 11.5 days (93 h on 12 duty days; 1 day without End duty, not counted: 2026-10-08; 8 h = 1 day)*
  - *From attendance: 12.5 days (12 present, 1 half day, 1 absent)*
- **Daily rate** is filled in with the rate of the employee's last record, if there is one. Otherwise type it: the pay for one full day, for example 600.
- **Basic pay** = Days worked × Daily rate, worked out as you type. The line under the attendance shows it, for example *Basic pay = 0.5 x ₱600.00 = ₱300.00*.
- Days, rate and basic pay can all still be changed by hand. Allowances and deductions work as before.
- **Use attendance** puts the counted days back in the field.
- **Opening a saved record never changes its pay.** It shows the attendance count, and only **Use attendance** puts it in.
- A typed name ("Other person") has no attendance. Choose the employee from the list (for example *arlyn (pahinante)*) so their attendance counts.
- The payroll list has a **Days x rate** column, for example *11.5 x ₱600.00*.
- Saving the days and rate is in the history (audit log), with the old values when they change.

### A half day pays half (added 2026-10-30, after the owner's test)

The owner marked a half day, but the basic pay stayed ₱600. The basic pay had been typed with no **Daily rate**, so nothing could halve it. Now the editor says so instead of saving it silently:

- **Days worked but no Daily rate:**
  - the line under the attendance says *Enter the daily rate. Basic pay = 0.5 day x daily rate.*;
  - **Save** asks first: **Enter the daily rate** (nothing is saved) or **Save as typed** (a fixed amount).
- **Basic pay different from days × rate,** for example typed by hand: **Save** asks **Use ₱300.00**, **Keep ₱600.00** or **Cancel**.
- **Attendance changed after the record was saved,** for example a half day corrected to present: opening the record says *Attendance now counts 1 day; this record has 0.5 day. Click Use attendance to update it.* The saved pay does not change by itself.
- **Nothing recorded in the period** (no marks, no duty): **Days worked** is left empty instead of 0. The editor says no attendance is recorded, and a fixed basic pay saves without a question.
- **Leave Days worked empty** to pay a fixed amount.

## 4. Payroll made by attendance, by itself (database update `20261030_02`, added 2026-10-30)

Owner's request, 11:50: "kapag mag add ng new employee dapat i seset agad yung basic pay nya, then kada present, absent, halfday sa attendance, automatic na sya papasok sa payroll, hindi na manual i aadd ... ang gagawin nalang ni admin present half day or absent tas automatic na sya papasok sa payroll".

Database update: `sql/20261030_02_payroll_auto.sql`.
- Rollback: `sql/20261030_98_rollback_payroll_auto.sql`. It is refused once a daily rate or an automatic record exists.
- It adds 1 table, 2 columns, 1 trigger and 5 functions, and replaces 2 functions. It changes no row.
- The phones are not changed. No new APK is needed.

**Daily rate**
- **Payroll → Employees...** is now **Employees and daily rates**. It lists the employees without an app account and the agents and drivers, with their daily rate (the pay for one full day).
- **Add employee...** asks for the daily rate; an employee cannot be added without one.
- For an agent or driver: select them → **Edit / daily rate...** → type the rate. Leave it empty to stop their automatic payroll.
- A daily rate is in the history (audit log), with the old rate when it changes.

**The Admin only marks the day**
- **Attendance → Employees without an app...** → Present / Half day / Absent → **Save attendance**.
- The payroll record of that day's pay period is made or updated at once: days worked × daily rate = basic pay.
- The window has a **Payroll this period** column, for example *₱900.00 (1.5 days)*. After saving it says, for example: *Saved. Payroll 2026-10-16 to 2026-10-31: jairus ₱300.00 (0.5 day).*
- Agents and drivers: each **End duty** from the app updates their payroll the same way (8 hours = 1 day). A problem with the payroll never stops the End duty.

**Pay periods:** 1st–15th and 16th–end of the month (kinsenas).

**What changes and what never does**
- A **paid** record is never changed. If the attendance changes later, the record says so when opened, and the attendance window says *Already paid for this period, not changed*.
- An unpaid record typed by hand for the same employee and period is taken over once attendance is recorded. A typed name equal to the employee's name is taken over too. With no attendance in the period, a typed record stays as it was typed.
- A new daily rate is used for the current period. A past period keeps the rate it was counted with.
- A record made by attendance is marked **auto** in the list (*0.5 x ₱600.00 · auto*). In its editor, the employee, period, days, rate and basic pay are locked. **Allowances, deductions, notes and Paid** can still be set.
- If deductions would become more than the pay, the attendance is refused with a message, and nothing is saved.
- Opening **Payroll** counts again the current and the previous pay period for everyone with a daily rate. This catches an End duty whose count failed.
- **New payroll record** still works, for example for someone paid a fixed amount without a daily rate.

**Database**
- `payroll_staff.daily_rate` and `public.payroll_account_rates`: owner only, RLS on, no API access.
- `payroll_records.auto`.
- `trimax_private.pay_period(day)`, `sync_payroll(actor, username, staff_id, day)`, `set_employee_rate(admin, username, staff_id, rate)`, `sync_payroll_all(admin, day)`: owner only.
- The trigger `duty_events_payroll` (End duty).
- `save_staff_attendance` now counts the payroll after saving.
- `set_payroll_attendance` no longer writes "x ₱0.00" in the history when there is no rate.

**Checked**
- **Database tests:** `sql/tests/59_payroll_auto_tests.sql` covers:
  - access and pay periods;
  - rate checks;
  - taking over a typed record, also by name;
  - half day;
  - counting everyone;
  - a past period keeps its rate;
  - a paid record is never changed;
  - deductions;
  - End duty: a night shift counts on its start day, and a payroll error never stops the End duty;
  - the phone's own End duty;
  - removing a rate;
  - the rollback refused once rates exist.

  The whole suite passes (1004 checks), including the rollback and re-apply.
- **Dry run** on the local production-like copy, applied twice:
  - the 5 new functions are `duty_events_payroll` `eb01c419…`, `pay_period` `a40907b0…`, `set_employee_rate` `32a171fd…`, `sync_payroll` `cbf22752…`, `sync_payroll_all` `c3d9d294…`;
  - the 2 replaced functions are `save_staff_attendance` `4acbd671…` and `set_payroll_attendance` `decdb625…`;
  - every other function is unchanged, and the API roles have no access.
- **Production read-only pre-check (2026-10-30):** the 110 functions besides `resolve_alert` are identical to the copy before the update. There is no rates table and no trigger yet.
- **Desktop unit tests:** 121 pass.
- **Desktop end-to-end:** E2EPayAuto (15 checks) covers:
  - adding an employee needs a rate;
  - a driver's rate;
  - Present, then Half day, makes ₱600, then ₱300, by itself;
  - an employee with no rate;
  - Maria's End duty makes 0.75 × ₱700 = ₱525;
  - the list marks them auto;
  - the locked editor with an allowance;
  - after Paid, the attendance no longer changes it.

  All 10 end-to-end scripts pass (174 checks).

## Database

- `public.staff_attendance`: one row per employee and day: PRESENT / HALF_DAY / ABSENT, note, who saved it and when. Owner only: RLS on, no API access.
- `trimax_private.save_staff_attendance(admin, day, entries)`: Admin only; saves one day for several employees.
- `trimax_private.attendance_days(username, staff_id, from, to)`: the days in a pay period of at most 3 months, from duty (agents and drivers) or from the Admin's marks (employees without an account).
- `payroll_records.days_worked` (0–366, 2 decimals) and `daily_rate` (0–10,000,000), both optional.
- `trimax_private.set_payroll_attendance(admin, record, days, rate)`: called by the Desktop in the same transaction as `save_payroll`. A voided record cannot be changed.
- `save_payroll`, the reports, net profit and the phone functions are unchanged. Basic pay is still what the Admin saves.

## Checked

- **Database tests:** `sql/tests/58_payroll_attendance_tests.sql` covers:
  - Admin only; future days; bad, duplicate and unknown entries; clearing a day;
  - 8 h = 1 day, 4 h = 0.5, more than 8 h = 1, Start without End not counted, two sessions on one day, a night duty counted on its start day;
  - days and rate saved, audited, refused on a voided record; an older Desktop editing the record keeps them;
  - the rollback while nothing is saved puts every function back exactly; once attendance is saved, the rollback is refused and changes nothing.

  The whole suite passes (969 checks).
- **Dry run** on the local production-like copy, applied twice: the 3 new functions were added (`attendance_days` `f93c59fa…`, `save_staff_attendance` `7489144e…`, `set_payroll_attendance` `f318d8ba…`) and every other function stayed the same. No row changed, the table has RLS on, and only the owner can use the table and functions.
- **Production read-only pre-check (2026-10-30):** the 6 functions this update builds on (`require_admin`, `audit_as`, `peso`, both `save_payroll`, `save_payroll_staff`) have the same fingerprints as the dry-run copy; `staff_attendance` and the 2 columns are not there yet.
- **Desktop unit tests:** 118 pass, including `PayrollAttendanceTest`.
- **Desktop end-to-end tests:** E2EAttendance (28 checks) covers:
  - marking a helper Present and then Half day;
  - Days this month = 1.5;
  - a driver with duty of 8 h, 4 h and a Start without End → 1.5 days;
  - the helper → 1.5 days;
  - basic pay = days × rate, saved with the record, in the audit log;
  - the list column;
  - a half day with the basic pay typed and no rate: Save asks, and nothing is saved until the rate is entered (0.5 × 600 = 300);
  - a basic pay typed by hand: Save asks, and **Use** puts back days × rate;
  - attendance changed after saving: the record says so, and **Use attendance** gives 1 × 600;
  - nothing marked: the days are left empty, and a fixed pay saves without a question.

  All 9 end-to-end scripts pass (159 checks), including E2EStaff with the new fields.
