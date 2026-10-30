package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The Admin's Time in / Time out (sql/20261030_03): the day and time typed are Philippine time. */
class AdminDutyTest {

    @Test
    void theTimeTypedIsPhilippineTime() {
        assertEquals(Instant.parse("2026-10-30T00:00:00Z"), AdminDutyDialog.parse("2026-10-30", "08:00"));
        assertEquals(Instant.parse("2026-10-29T16:05:00Z"), AdminDutyDialog.parse(" 2026-10-30 ", "0:05"));
        assertEquals(Instant.parse("2026-10-30T09:30:00Z"), AdminDutyDialog.parse("2026-10-30", "17:30"));
    }

    @Test
    void aWrongDayOrTimeIsNotGuessed() {
        assertNull(AdminDutyDialog.parse("30/10/2026", "08:00"));
        assertNull(AdminDutyDialog.parse("2026-10-30", "8am"));
        assertNull(AdminDutyDialog.parse("2026-10-30", "25:00"));
        assertNull(AdminDutyDialog.parse("", ""));
    }

    @Test
    void thePersonSaysOnOrOffDuty() {
        AttendanceService.Person p = new AttendanceService.Person();
        p.name = "Maria Santos";
        p.role = "Driver";
        assertFalse(p.onDuty());
        assertTrue(p.toString().endsWith("off duty"));
        p.lastType = "START";
        p.lastAt = java.sql.Timestamp.from(Instant.parse("2026-10-30T00:00:00Z"));
        assertTrue(p.onDuty());
        assertTrue(p.toString().contains("on duty since"));
    }
}
