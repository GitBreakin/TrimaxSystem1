package com.trimaxsystem;

/**
 * How the agent took a booking (sql/20261029_02): went to the customer (a photo taken there is required)
 * or by text / call. Null for bookings made on the Desktop and for bookings from an older agent app.
 */
final class OrderVia {

    static final String VISIT = "VISIT";
    static final String TEXT_CALL = "TEXT_CALL";

    private OrderVia() {
    }

    /** The bookings list ("Taken" column): short, empty when not recorded. */
    static String listLabel(String orderVia) {
        if (VISIT.equals(orderVia)) return "Visit";
        if (TEXT_CALL.equals(orderVia)) return "Text / Call";
        return "";
    }

    /** The booking window's "Order taken" row; null when the row should not be shown (Desktop booking). */
    static String detail(String orderVia, boolean fromMobile, boolean hasPhoto, boolean hasGps) {
        if (VISIT.equals(orderVia)) {
            return "Visit: the agent went to the customer"
                    + (hasPhoto ? " (photo below" + (hasGps ? ", with location)" : ", no location)") : "");
        }
        if (TEXT_CALL.equals(orderVia)) return "Text / Call: no visit, so no photo";
        return fromMobile ? "Not recorded (older agent app)" : null;
    }
}
