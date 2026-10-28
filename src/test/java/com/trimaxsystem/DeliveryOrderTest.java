package com.trimaxsystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryOrderTest {

    @Test
    void distancesAreShortAndReadable() {
        assertEquals("0 m", DeliveryOrder.distance(0.4));
        assertEquals("810 m", DeliveryOrder.distance(812.0));
        assertEquals("1.4 km", DeliveryOrder.distance(1366.0));
        assertEquals("9.9 km", DeliveryOrder.distance(9940.0));
        assertEquals("12 km", DeliveryOrder.distance(12_400.0));
        assertEquals("", DeliveryOrder.distance(null));
    }

    @Test
    void listCellShowsTheNumberOnlyForDeliveriesInADriversOrder() {
        assertEquals("#1 · 810 m", DeliveryOrder.cell(1, 812.0));
        assertEquals("#3 · no pin", DeliveryOrder.cell(3, null));
        assertEquals("2.4 km", DeliveryOrder.cell(null, 2400.0));
        assertEquals("no pin", DeliveryOrder.cell(null, null));
    }

    @Test
    void bookingWindowLineSaysWhoseOrderAndThatItIsAStraightLine() {
        assertEquals("#2 of 4 for Juan (juan01)  ·  2.4 km from TRIMAX Hub (straight line)",
                DeliveryOrder.detail(2, 4, "Juan (juan01)", 2400.0));
        assertEquals("2.4 km from TRIMAX Hub (straight line)", DeliveryOrder.detail(null, null, null, 2400.0));
        assertTrue(DeliveryOrder.detail(null, null, null, null).startsWith("no pin"));
    }

    @Test
    void sortKeyPutsTheDriversOrderFirstThenNearestThenNoPin() {
        // one ORDER BY expression: the Bookings list appends only ASC / DESC to it
        assertTrue(DeliveryOrder.SORT_SQL.startsWith("COALESCE(ds.seq::double precision, 1000000 + COALESCE("));
        assertTrue(DeliveryOrder.SORT_SQL.contains(DeliveryOrder.DISTANCE_SQL));
        assertFalse(DeliveryOrder.SORT_SQL.contains(";"));
    }
}
