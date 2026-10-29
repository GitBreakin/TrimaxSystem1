package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** How the agent took the order, as the admin sees it (sql/20261029_02). */
class OrderViaTest {

    @Test
    void theBookingsListShowsAShortLabel() {
        assertEquals("Visit", OrderVia.listLabel("VISIT"));
        assertEquals("Text / Call", OrderVia.listLabel("TEXT_CALL"));
        assertEquals("", OrderVia.listLabel(null), "Desktop bookings and older apps: empty");
        assertEquals("", OrderVia.listLabel("SOMETHING_ELSE"));
    }

    @Test
    void theBookingWindowExplainsIt() {
        assertEquals("Visit: the agent went to the customer (photo below, with location)",
                OrderVia.detail("VISIT", true, true, true));
        assertEquals("Visit: the agent went to the customer (photo below, no location)",
                OrderVia.detail("VISIT", true, true, false));
        assertEquals("Text / Call: no visit, so no photo", OrderVia.detail("TEXT_CALL", true, false, false));
        assertEquals("Not recorded (older agent app)", OrderVia.detail(null, true, false, false));
        assertNull(OrderVia.detail(null, false, false, false), "no row for a booking made on the Desktop");
    }

    @Test
    void aVisitPhotoNeedsTheVisitAndThePath() {
        BookingService.Detail d = new BookingService.Detail();
        assertFalse(d.hasVisitPhoto());
        d.orderVia = OrderVia.VISIT;
        assertFalse(d.hasVisitPhoto());
        d.visitPhotoPath = "0f8fad5b-d9cb-469f-a165-70867728950e/visit_1.jpg";
        assertTrue(d.hasVisitPhoto());
        assertFalse(d.visitHasGps());
        d.visitLatitude = 14.8;
        d.visitLongitude = 121.0;
        assertTrue(d.visitHasGps());
        d.orderVia = OrderVia.TEXT_CALL;
        assertFalse(d.hasVisitPhoto(), "only a visit shows a photo");
    }
}
