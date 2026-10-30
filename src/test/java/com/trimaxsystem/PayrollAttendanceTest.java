package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Payroll from attendance (sql/20261030_01): days worked x daily rate. */
class PayrollAttendanceTest {

    @Test
    void daysAsTyped() {
        assertNull(PayrollService.parseDays("  "));
        assertEquals(0, new BigDecimal("11.5").compareTo(PayrollService.parseDays("11.5")));
        assertEquals(0, new BigDecimal("0").compareTo(PayrollService.parseDays("0")));
        assertThrows(IllegalArgumentException.class, () -> PayrollService.parseDays("eleven"));
        assertThrows(IllegalArgumentException.class, () -> PayrollService.parseDays("-1"));
        assertThrows(IllegalArgumentException.class, () -> PayrollService.parseDays("367"));
        assertThrows(IllegalArgumentException.class, () -> PayrollService.parseDays("1.333"));
    }

    @Test
    void basicPayIsDaysTimesRate() {
        assertEquals(new BigDecimal("6900.00"), PayrollService.basicFromDays(new BigDecimal("11.5"), new BigDecimal("600")));
        assertEquals(new BigDecimal("4687.50"), PayrollService.basicFromDays(new BigDecimal("7.5"), new BigDecimal("625")));
        assertNull(PayrollService.basicFromDays(null, new BigDecimal("600")));
        assertNull(PayrollService.basicFromDays(new BigDecimal("5"), null));
    }

    @Test
    void aHalfDayMustHalveTheBasicPay() {
        // owner, 2026-10-30: half day marked, basic pay typed as 600, no daily rate -> asked for the rate
        assertEquals("Enter the daily rate. Basic pay = 0.5 day x daily rate.",
                PayrollService.basicCheck(new BigDecimal("0.5"), null, new BigDecimal("600")));
        assertEquals("Basic pay " + Money.peso(new BigDecimal("600")) + ", but 0.5 x " + Money.peso(new BigDecimal("600"))
                        + " = " + Money.peso(new BigDecimal("300.00")) + ".",
                PayrollService.basicCheck(new BigDecimal("0.5"), new BigDecimal("600"), new BigDecimal("600")));
        assertNull(PayrollService.basicCheck(new BigDecimal("0.5"), new BigDecimal("600"), new BigDecimal("300")));
        assertNull(PayrollService.basicCheck(new BigDecimal("1.5"), new BigDecimal("500"), new BigDecimal("750.00")));
        // a fixed amount (Days worked empty) and no days with no pay are fine
        assertNull(PayrollService.basicCheck(null, null, new BigDecimal("600")));
        assertNull(PayrollService.basicCheck(BigDecimal.ZERO, null, BigDecimal.ZERO));
        assertNotNull(PayrollService.basicCheck(BigDecimal.ZERO, null, new BigDecimal("600")));
        assertNotNull(PayrollService.basicCheck(BigDecimal.ZERO, new BigDecimal("600"), new BigDecimal("600")));
        assertEquals("0.5 day", PayrollService.dayWord(new BigDecimal("0.5")));
        assertEquals("1 day", PayrollService.dayWord(BigDecimal.ONE));
        assertEquals("0 days", PayrollService.dayWord(BigDecimal.ZERO));
        assertEquals("11.5 days", PayrollService.dayWord(new BigDecimal("11.50")));
    }

    @Test
    void nothingRecordedIsNotZeroDays() {
        PayrollService.AttendanceDays none = new PayrollService.AttendanceDays();
        assertFalse(none.any());
        PayrollService.AttendanceDays absent = new PayrollService.AttendanceDays();
        absent.absent = 1;
        assertTrue(absent.any());
        PayrollService.AttendanceDays open = new PayrollService.AttendanceDays();
        open.fromDuty = true;
        open.dutyDays = 1;
        open.openDays = 1;
        assertTrue(open.any());
    }

    @Test
    void payPeriodsAreTheFifteenthAndTheEndOfTheMonth() {
        // the same as trimax_private.pay_period (sql/20261030_02)
        assertArrayEquals(new java.time.LocalDate[]{java.time.LocalDate.parse("2026-10-01"), java.time.LocalDate.parse("2026-10-15")},
                PayrollService.payPeriod(java.time.LocalDate.parse("2026-10-15")));
        assertArrayEquals(new java.time.LocalDate[]{java.time.LocalDate.parse("2026-10-16"), java.time.LocalDate.parse("2026-10-31")},
                PayrollService.payPeriod(java.time.LocalDate.parse("2026-10-30")));
        assertArrayEquals(new java.time.LocalDate[]{java.time.LocalDate.parse("2024-02-16"), java.time.LocalDate.parse("2024-02-29")},
                PayrollService.payPeriod(java.time.LocalDate.parse("2024-02-16")));
    }

    @Test
    void aNewEmployeeNeedsADailyRate() {
        assertEquals(0, new BigDecimal("600").compareTo(PayrollStaffDialog.parseRate("600")));
        assertThrows(IllegalArgumentException.class, () -> PayrollStaffDialog.parseRate(""));
        assertThrows(IllegalArgumentException.class, () -> PayrollStaffDialog.parseRate("0"));
        assertThrows(IllegalArgumentException.class, () -> PayrollStaffDialog.parseRate("abc"));
    }

    @Test
    void theAttendanceWindowShowsThePayOfThePeriod() {
        PayrollService.StaffDay d = new PayrollService.StaffDay();
        assertEquals("No daily rate", d.payrollText());
        d.dailyRate = new BigDecimal("600");
        assertEquals(Money.peso(BigDecimal.ZERO), d.payrollText());
        d.periodPay = new BigDecimal("900.00");
        d.periodDays = new BigDecimal("1.5");
        d.periodStatus = "UNPAID";
        assertEquals(Money.peso(new BigDecimal("900.00")) + " (1.5 days)", d.payrollText());
        d.periodStatus = "PAID";
        assertEquals(Money.peso(new BigDecimal("900.00")) + " paid", d.payrollText());
    }

    @Test
    void theListShowsDaysAndRate() {
        assertEquals("11.5 x " + Theme.money(new BigDecimal("600")), PayrollService.daysText(new BigDecimal("11.50"), new BigDecimal("600")));
        assertEquals("1 day", PayrollService.daysText(BigDecimal.ONE, null));
        assertEquals("", PayrollService.daysText(null, null));
    }

    @Test
    void whatTheEditorSaysAboutAttendance() {
        PayrollService.AttendanceDays duty = new PayrollService.AttendanceDays();
        duty.fromDuty = true;
        duty.days = new BigDecimal("5");
        duty.hours = new BigDecimal("41.00");
        duty.dutyDays = 8;
        duty.openDays = 2;
        duty.openDates = "2025-02-08, 2025-02-10";
        assertEquals("5 days (41 h on 8 duty days; 2 days without End duty, not counted: 2025-02-08, 2025-02-10; 8 h = 1 day)",
                duty.summary());
        PayrollService.AttendanceDays staff = new PayrollService.AttendanceDays();
        staff.days = new BigDecimal("2.5");
        staff.present = 2;
        staff.halfDays = 1;
        staff.absent = 1;
        assertEquals("2.5 days (2 present, 1 half day, 1 absent)", staff.summary());
        assertEquals("Half day", PayrollService.attendanceLabel(PayrollService.HALF_DAY));
    }
}
