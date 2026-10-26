package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BookingStatusTest {

    @Test
    void allowsTheWorkflowInOrder() {
        assertTrue(BookingStatus.canTransition("Pending", "Confirmed"));
        assertTrue(BookingStatus.canTransition("Confirmed", "In Progress"));
        assertTrue(BookingStatus.canTransition("In Progress", "Completed"));
    }

    @Test
    void cancellationAllowedFromAnyOpenStatus() {
        for (String s : new String[]{"Pending", "Confirmed", "In Progress"}) {
            assertTrue(BookingStatus.canTransition(s, "Cancelled"), s);
        }
    }

    @Test
    void blocksSkippingBackwardsAndFinalStatuses() {
        assertFalse(BookingStatus.canTransition("Pending", "Completed"));
        assertFalse(BookingStatus.canTransition("Confirmed", "Pending"));
        assertTrue(BookingStatus.next("Completed").isEmpty());
        assertTrue(BookingStatus.next("Cancelled").isEmpty());
        assertTrue(BookingStatus.next("Rider-made-up").isEmpty());
        assertTrue(BookingStatus.next(null).isEmpty());
    }
}
