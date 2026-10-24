package com.trimaxsystem;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Driver progress on a booking (bookings.delivery_status), reported from the Android driver app.
 * It is separate from {@link BookingStatus}: the booking status stays the administrator's
 * Pending / Confirmed / In Progress / Completed / Cancelled. Drivers start at the TRIMAX hub and go
 * straight to the customer (no pickup step): ASSIGNED -> ON_THE_WAY -> ARRIVED -> DELIVERED, or FAILED.
 * Only two driver steps move the booking status, and the database does that itself: leaving the hub
 * (Confirmed to In Progress) and DELIVERED (In Progress to Completed). FAILED leaves the booking
 * In Progress so an administrator decides whether to cancel it, which returns the stock.
 * STARTED and PICKED_UP only appear on bookings saved by older app versions.
 *
 * The allowed steps are enforced in the database (trimax_private.delivery_transition_allowed in
 * sql/20261024_01_hub_gps_duty_provisioning.sql); {@link #canTransition} mirrors it for display.
 */
public final class DeliveryStatus {

    public static final String ASSIGNED = "ASSIGNED";
    /** Older app versions only. */
    public static final String STARTED = "STARTED";
    /** Older app versions only (the pickup step was removed: drivers start at the hub). */
    public static final String PICKED_UP = "PICKED_UP";
    public static final String ON_THE_WAY = "ON_THE_WAY";
    public static final String ARRIVED = "ARRIVED";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";

    /** Delivery column text for a booking from the phone that nobody has assigned yet. */
    public static final String NEW_FROM_MOBILE = "New (mobile)";

    /** In the order a delivery normally goes through them. */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            ASSIGNED, STARTED, PICKED_UP, ON_THE_WAY, ARRIVED, DELIVERED, FAILED));

    private DeliveryStatus() {
    }

    /** Words for the screen, e.g. "On the way". Null or blank gives "". Unknown codes are shown as they are. */
    public static String label(String code) {
        if (code == null || code.isBlank()) return "";
        switch (code) {
            case ASSIGNED:   return "Assigned";
            case STARTED:    return "Started (older app)";
            case PICKED_UP:  return "Picked up (older app)";
            case ON_THE_WAY: return "On the way";
            case ARRIVED:    return "Arrived";
            case DELIVERED:  return "Delivered";
            case FAILED:     return "Delivery failed";
            default:         return code;
        }
    }

    /** The driver has left with the goods and has not finished: the booking must not be reassigned. */
    public static boolean isUnderWay(String code) {
        return STARTED.equals(code) || PICKED_UP.equals(code) || ON_THE_WAY.equals(code) || ARRIVED.equals(code);
    }

    /** Same rule as the database function trimax_private.delivery_transition_allowed. */
    public static boolean canTransition(String from, String to) {
        if (from == null || to == null) return false;
        switch (from) {
            case ASSIGNED:   return ON_THE_WAY.equals(to);
            case STARTED:
            case PICKED_UP:  return ON_THE_WAY.equals(to) || ARRIVED.equals(to) || FAILED.equals(to);
            case ON_THE_WAY: return ARRIVED.equals(to) || FAILED.equals(to);
            case ARRIVED:    return DELIVERED.equals(to) || FAILED.equals(to);
            default:         return false;
        }
    }

    /**
     * What the bookings list shows in its Delivery column. A mobile booking that nobody has assigned yet is
     * flagged so the administrator notices it; desktop bookings without a driver show nothing.
     */
    public static String listLabel(String createdVia, String driverUsername, String deliveryStatus) {
        if (driverUsername == null) {
            return "mobile".equals(createdVia) ? NEW_FROM_MOBILE : "";
        }
        return label(deliveryStatus == null ? ASSIGNED : deliveryStatus);
    }

    private static final java.util.regex.Pattern CODE =
            java.util.regex.Pattern.compile("\\b(ASSIGNED|STARTED|PICKED_UP|ON_THE_WAY|ARRIVED|DELIVERED|FAILED)\\b");

    /** History text with the codes written as words, e.g. "STARTED -> ON_THE_WAY" becomes "Started -> On the way". */
    public static String humanize(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = CODE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(label(m.group(1))));
        m.appendTail(sb);
        return sb.toString();
    }
}
