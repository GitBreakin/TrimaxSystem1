package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Delivery order: a driver's unfinished deliveries numbered #1, #2 ... with the one NEAREST TO THE TRIMAX
 * HUB first (sql/20261028_05_delivery_order_nearest_hub.sql). Deliveries already under way stay first;
 * Confirmed ones come before Pending ones (the driver cannot start a Pending one yet); a delivery without
 * a pin is last in its group. The database works the order out (trimax_private.delivery_sequence), so the
 * Desktop and the driver app always show the same numbers.
 * Distance = straight line from the hub pin to the delivery pin (or the customer's saved pin), not road
 * distance.
 */
final class DeliveryOrder {

    static final String HUB_NAME = "TRIMAX Hub";
    private static final long CACHE_MILLIS = 60_000;
    private static volatile Boolean installed;
    private static volatile long checkedAt;

    /** Distance from the hub in metres for a booking row (aliases b = bookings, c = clients). */
    static final String DISTANCE_SQL = "trimax_private.hub_distance_m("
            + "CASE WHEN b.delivery_latitude IS NOT NULL THEN b.delivery_latitude ELSE c.latitude END, "
            + "CASE WHEN b.delivery_latitude IS NOT NULL THEN b.delivery_longitude ELSE c.longitude END)";
    /** The delivery order of every driver, to LEFT JOIN as ds on ds.booking_id. */
    static final String SEQUENCE_SQL = "trimax_private.delivery_sequence()";
    /**
     * One sort key: deliveries in a driver's order first (by their number), then the rest nearest to the
     * hub first, bookings without a pin last.
     */
    static final String SORT_SQL = "COALESCE(ds.seq::double precision, 1000000 + COALESCE(" + DISTANCE_SQL + ", 1000000000))";

    private DeliveryOrder() {
    }

    /** True when the server has 20261028_05 (checked at most once a minute). */
    static boolean isInstalled(Connection conn) throws SQLException {
        Boolean known = installed;
        if (known != null && System.currentTimeMillis() - checkedAt < CACHE_MILLIS) return known;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT to_regprocedure('trimax_private.delivery_sequence(text)') IS NOT NULL "
                        + "AND to_regprocedure('trimax_private.hub_distance_m(double precision,double precision)') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            installed = rs.getBoolean(1);
            checkedAt = System.currentTimeMillis();
            return installed;
        }
    }

    /** "850 m", "2.4 km", "12 km"; "" for null. */
    static String distance(Double meters) {
        if (meters == null || meters.isNaN()) return "";
        double m = Math.max(0, meters);
        if (m < 1000) return Math.round(m / 10.0) * 10 + " m";
        if (m < 10_000) return String.format(Locale.ROOT, "%.1f km", m / 1000.0);
        return Math.round(m / 1000.0) + " km";
    }

    /** The Bookings list cell: "#1 · 2.4 km", "#3 · no pin", "2.4 km", "no pin". */
    static String cell(Integer seq, Double meters) {
        String d = meters == null ? "no pin" : distance(meters);
        return seq == null ? d : "#" + seq + " · " + d;
    }

    /** The booking window line, e.g. "#2 of 4 for Juan (juan01) · 2.4 km from TRIMAX Hub". */
    static String detail(Integer seq, Integer count, String driver, Double meters) {
        String where = meters == null ? "no pin, so the distance from " + HUB_NAME + " is not known"
                : distance(meters) + " from " + HUB_NAME + " (straight line)";
        if (seq == null) return where;
        return "#" + seq + (count == null ? "" : " of " + count) + (driver == null ? "" : " for " + driver)
                + "  ·  " + where;
    }
}
