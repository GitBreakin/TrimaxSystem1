package com.trimaxsystem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/**
 * The booking statuses stored in bookings.status, and which status may follow which.
 * The names must match the database CHECK constraint exactly (see sql/001_booking_statuses.sql).
 *
 * The transition rules below are the one place to change if the business process differs:
 *   Pending     -> Confirmed, Cancelled
 *   Confirmed   -> In Progress, Cancelled
 *   In Progress -> Completed, Cancelled   (Cancelled = delivery failed, or the customer did not accept the goods)
 *   Completed, Cancelled -> (final)
 */
public final class BookingStatus {

    public static final String PENDING = "Pending";
    public static final String CONFIRMED = "Confirmed";
    public static final String IN_PROGRESS = "In Progress";
    public static final String COMPLETED = "Completed";
    public static final String CANCELLED = "Cancelled";

    /** All statuses, in display order. */
    public static final String[] ALL = {PENDING, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED};

    private static final Map<String, List<String>> NEXT = new LinkedHashMap<>();

    static {
        NEXT.put(PENDING, Arrays.asList(CONFIRMED, CANCELLED));
        NEXT.put(CONFIRMED, Arrays.asList(IN_PROGRESS, CANCELLED));
        NEXT.put(IN_PROGRESS, Arrays.asList(COMPLETED, CANCELLED));
        NEXT.put(COMPLETED, Collections.emptyList());
        NEXT.put(CANCELLED, Collections.emptyList());
    }

    private BookingStatus() {
    }

    /** Statuses a booking may move to from the given one (empty for a final or unknown status). */
    public static List<String> next(String current) {
        List<String> n = NEXT.get(current);
        return n == null ? Collections.emptyList() : n;
    }

    public static boolean canTransition(String from, String to) {
        return next(from).contains(to);
    }
}
