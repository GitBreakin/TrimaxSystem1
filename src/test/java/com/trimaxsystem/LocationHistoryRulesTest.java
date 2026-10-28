package com.trimaxsystem;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocationHistoryRulesTest {

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    @Test
    void rangeMustRunForwardAndAtMostThirtyOneDays() {
        Instant a = Instant.parse("2026-10-28T00:00:00Z");
        assertNull(LocationHistoryRules.checkRange(a, a.plusSeconds(3600)));
        assertEquals("The start must be before the end.", LocationHistoryRules.checkRange(a, a));
        assertEquals("The start must be before the end.", LocationHistoryRules.checkRange(a.plusSeconds(1), a));
        assertNull(LocationHistoryRules.checkRange(a, a.plusSeconds(31L * 86400)));
        assertNotNull(LocationHistoryRules.checkRange(a, a.plusSeconds(31L * 86400 + 1)));
        assertNotNull(LocationHistoryRules.checkRange(null, a));
    }

    @Test
    void presetsAreManilaDays() {
        Map<String, Instant[]> p = LocationHistoryRules.presets(LocalDate.of(2026, 10, 28), MANILA);
        // Manila midnight is 16:00 UTC of the day before
        assertArrayEquals(new Instant[]{Instant.parse("2026-10-27T16:00:00Z"), Instant.parse("2026-10-28T16:00:00Z")}, p.get("Today"));
        assertArrayEquals(new Instant[]{Instant.parse("2026-10-26T16:00:00Z"), Instant.parse("2026-10-27T16:00:00Z")}, p.get("Yesterday"));
        assertArrayEquals(new Instant[]{Instant.parse("2026-10-21T16:00:00Z"), Instant.parse("2026-10-28T16:00:00Z")}, p.get("Last 7 days"));
    }

    @Test
    void summarySaysWhenOnlySomePointsAreDrawn() {
        assertEquals("No GPS points were recorded in this period.", LocationHistoryRules.summary(0, 0, 1));
        assertEquals("1 recorded GPS point.", LocationHistoryRules.summary(1, 1, 1));
        assertEquals("120 recorded GPS points.", LocationHistoryRules.summary(120, 120, 1));
        assertEquals("5000 recorded GPS points; the map shows 2001 of them (about 1 of every 3, always with the first and the last one).",
                LocationHistoryRules.summary(5000, 2001, 3));
    }

    @Test
    void gapsAreLongerThanFiveMinutes() {
        assertFalse(LocationHistoryRules.isGap(null));
        assertFalse(LocationHistoryRules.isGap(300));
        assertTrue(LocationHistoryRules.isGap(301));
        assertEquals("20 min", LocationHistoryRules.gapText(1200));
        assertEquals("1 h 20 min", LocationHistoryRules.gapText(4800));
        assertEquals("2 h", LocationHistoryRules.gapText(7200));
    }

    @Test
    void emptyTextNeverClaimsDataCanBeRecovered() {
        String t = LocationHistoryRules.emptyText("Ken (driver_ken)");
        assertTrue(t.startsWith("No GPS points were recorded for Ken (driver_ken)"));
        assertTrue(t.contains("never stored"));
        assertFalse(t.toLowerCase().contains("estimated"));
    }

    @Test
    void timesAreShownInManila() {
        Instant at = Instant.parse("2026-10-28T06:15:03Z");
        assertEquals("Oct 28, 2:15:03 PM", LocationHistoryRules.pointTime(at, MANILA));
        assertEquals("Oct 28, 2026 8:00 AM to Oct 28, 2026 6:00 PM (Manila)",
                LocationHistoryRules.rangeText(Instant.parse("2026-10-28T00:00:00Z"), Instant.parse("2026-10-28T10:00:00Z"), MANILA));
        assertEquals(Long.valueOf(36), LocationHistoryRules.kmh(10.0));
        assertNull(LocationHistoryRules.kmh(null));
    }
}
