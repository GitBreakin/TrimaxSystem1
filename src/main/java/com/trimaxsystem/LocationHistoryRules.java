package com.trimaxsystem;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Plain rules and wording for the Location History view (no Swing, no database), so they can be tested.
 * All dates and times are Manila time (Asia/Manila), like the rest of the app.
 */
public final class LocationHistoryRules {

    private static final DateTimeFormatter RANGE =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter POINT =
            DateTimeFormatter.ofPattern("MMM d, h:mm:ss a", Locale.ENGLISH);

    private LocationHistoryRules() {
    }

    /** Why a range cannot be shown, or null when it is fine. */
    public static String checkRange(Instant from, Instant to) {
        if (from == null || to == null) return "Choose a start and an end.";
        if (!from.isBefore(to)) return "The start must be before the end.";
        if (Duration.between(from, to).compareTo(Duration.ofDays(LocationHistoryService.MAX_DAYS)) > 0) {
            return "Choose a period of at most " + LocationHistoryService.MAX_DAYS + " days.";
        }
        return null;
    }

    /**
     * Quick ranges, each [start, end) in the given zone: Today (until the end of today), Yesterday,
     * Last 7 days (today and the 6 days before).
     */
    public static Map<String, Instant[]> presets(LocalDate today, ZoneId zone) {
        Map<String, Instant[]> m = new LinkedHashMap<>();
        m.put("Today", new Instant[]{start(today, zone), start(today.plusDays(1), zone)});
        m.put("Yesterday", new Instant[]{start(today.minusDays(1), zone), start(today, zone)});
        m.put("Last 7 days", new Instant[]{start(today.minusDays(6), zone), start(today.plusDays(1), zone)});
        return m;
    }

    private static Instant start(LocalDate d, ZoneId zone) {
        return d.atStartOfDay(zone).toInstant();
    }

    /** "Oct 28, 2026 8:00 AM to Oct 28, 2026 6:00 PM (Manila)". */
    public static String rangeText(Instant from, Instant to, ZoneId zone) {
        return RANGE.format(from.atZone(zone)) + " to " + RANGE.format(to.atZone(zone)) + " (Manila)";
    }

    public static String pointTime(Instant at, ZoneId zone) {
        return POINT.format(at.atZone(zone));
    }

    /**
     * What the map shows, said plainly: how many samples were recorded, and if only every n-th one is drawn.
     */
    public static String summary(int total, int shown, int step) {
        if (total <= 0) return "No GPS points were recorded in this period.";
        String rec = total + (total == 1 ? " recorded GPS point" : " recorded GPS points");
        if (step <= 1 || shown >= total) return rec + ".";
        return rec + "; the map shows " + shown + " of them (about 1 of every " + step
                + ", always with the first and the last one).";
    }

    /** "5 min", "1 h 20 min": the length of a gap without data. */
    public static String gapText(int seconds) {
        int min = Math.max(1, Math.round(seconds / 60f));
        if (min < 60) return min + " min";
        int h = min / 60, rest = min % 60;
        return h + " h" + (rest == 0 ? "" : " " + rest + " min");
    }

    /** True when the time since the previous recorded point means data is missing in between. */
    public static boolean isGap(Integer gapBeforeSeconds) {
        return gapBeforeSeconds != null && gapBeforeSeconds > LocationHistoryService.GAP_MINUTES * 60;
    }

    /** Shown when a person has no samples, without suggesting that anything was lost or can be recovered. */
    public static String emptyText(String who) {
        return "No GPS points were recorded for " + who + " in this period.\n"
                + "Points are recorded only while the TRIMAX app is open and the person is on duty,\n"
                + "and only since the location history was turned on. Earlier trips were never stored.";
    }

    /** km/h from metres per second, or null. */
    public static Long kmh(Double metresPerSecond) {
        return metresPerSecond == null ? null : Math.round(metresPerSecond * 3.6);
    }
}
