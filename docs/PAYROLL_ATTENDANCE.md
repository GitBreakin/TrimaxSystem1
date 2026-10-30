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
