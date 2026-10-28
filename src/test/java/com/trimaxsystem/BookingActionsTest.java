package com.trimaxsystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookingActionsTest {

    @Test
    void pendingWithoutDriverConfirmsAndAssignsInOneStep() {
        assertEquals(BookingActions.Primary.CONFIRM_AND_ASSIGN, BookingActions.primary("Pending", null));
        assertEquals(BookingActions.Primary.CONFIRM_AND_ASSIGN, BookingActions.primary("Pending", "  "));
    }

    @Test
    void pendingWithDriverOnlyNeedsConfirming() {
        assertEquals(BookingActions.Primary.CONFIRM, BookingActions.primary("Pending", "driver.dan"));
    }

    @Test
    void confirmedWithoutDriverAssigns() {
        assertEquals(BookingActions.Primary.ASSIGN, BookingActions.primary("Confirmed", null));
    }

    @Test
    void printIsTheNextStepOnceADriverIsSaved() {
        assertEquals(BookingActions.Primary.PRINT, BookingActions.primary("Confirmed", "driver.dan"));
        assertEquals(BookingActions.Primary.PRINT, BookingActions.primary("In Progress", "driver.dan"));
        assertEquals(BookingActions.Primary.PRINT, BookingActions.primary("Completed", "driver.dan"));
    }

    @Test
    void noReceiptWithoutADriver() {
        for (String status : BookingStatus.ALL) {
            assertFalse(BookingActions.canPrint(null), status);
            assertNotNull(BookingActions.whyNoReceipt(status, null), status);
        }
        assertEquals("The receipt can be printed once a driver is assigned.", BookingActions.whyNoReceipt("Pending", null));
        assertEquals(BookingActions.Primary.NONE, BookingActions.primary("Completed", null));
        assertEquals(BookingActions.Primary.NONE, BookingActions.primary("Cancelled", null));
    }

    @Test
    void assignedBookingsCanBePrintedWithoutAssigningAgain() {
        assertTrue(BookingActions.canPrint("driver.dan"));
        assertNull(BookingActions.whyNoReceipt("Completed", "driver.dan"));
    }

    @Test
    void driverCanOnlyBeChangedBeforeTheDeliveryStarts() {
        assertTrue(BookingActions.canChangeDriver("Confirmed", "driver.dan", "ASSIGNED"));
        assertTrue(BookingActions.canChangeDriver("Pending", "driver.dan", null));
        assertFalse(BookingActions.canChangeDriver("Confirmed", "driver.dan", "STARTED"));
        assertFalse(BookingActions.canChangeDriver("In Progress", "driver.dan", "ON_THE_WAY"));
        assertFalse(BookingActions.canChangeDriver("Confirmed", null, null));
    }

    @Test
    void everyCaseHasANextStepSentence() {
        for (String status : BookingStatus.ALL) {
            assertNotNull(BookingActions.nextStepText(status, null), status);
            assertNotNull(BookingActions.nextStepText(status, "driver.dan"), status);
        }
    }
}
