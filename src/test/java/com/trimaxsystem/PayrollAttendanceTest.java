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
