package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DeliveryStatusTest {

    /** Exactly the pairs in trimax_private.delivery_transition_allowed (sql/20261023_01_...). */
    private static final Set<String> DATABASE_RULE = new HashSet<>(Arrays.asList(
            "ASSIGNED>STARTED",
            "STARTED>PICKED_UP", "STARTED>ON_THE_WAY", "PICKED_UP>ON_THE_WAY",
            "ON_THE_WAY>ARRIVED", "ARRIVED>DELIVERED",
            "STARTED>FAILED", "PICKED_UP>FAILED", "ON_THE_WAY>FAILED", "ARRIVED>FAILED"));

    @Test
    void transitionsMatchTheDatabaseRuleExactly() {
        for (String from : DeliveryStatus.ALL) {
            for (String to : DeliveryStatus.ALL) {
                assertEquals(DATABASE_RULE.contains(from + ">" + to), DeliveryStatus.canTransition(from, to), from + " -> " + to);
            }
        }
        assertFalse(DeliveryStatus.canTransition(null, DeliveryStatus.STARTED));
        assertFalse(DeliveryStatus.canTransition(DeliveryStatus.ASSIGNED, null));
    }

    @Test
    void finishedDeliveriesCannotMove() {
        for (String to : DeliveryStatus.ALL) {
            assertFalse(DeliveryStatus.canTransition(DeliveryStatus.DELIVERED, to));
            assertFalse(DeliveryStatus.canTransition(DeliveryStatus.FAILED, to));
        }
    }

    @Test
    void underWayMeansStartedButNotFinished() {
        assertFalse(DeliveryStatus.isUnderWay(null));
        assertFalse(DeliveryStatus.isUnderWay(DeliveryStatus.ASSIGNED));
        assertTrue(DeliveryStatus.isUnderWay(DeliveryStatus.STARTED));
        assertTrue(DeliveryStatus.isUnderWay(DeliveryStatus.ARRIVED));
        assertFalse(DeliveryStatus.isUnderWay(DeliveryStatus.DELIVERED));
        assertFalse(DeliveryStatus.isUnderWay(DeliveryStatus.FAILED));
    }

    @Test
    void labelsAreReadableAndNeverNull() {
        for (String s : DeliveryStatus.ALL) {
            assertFalse(DeliveryStatus.label(s).isEmpty(), s);
            assertFalse(DeliveryStatus.label(s).contains("_"), s);
        }
        assertEquals("", DeliveryStatus.label(null));
        assertEquals("SOMETHING_NEW", DeliveryStatus.label("SOMETHING_NEW"));
    }

    @Test
    void listLabelFlagsUnassignedMobileBookingsOnly() {
        assertEquals(DeliveryStatus.NEW_FROM_MOBILE, DeliveryStatus.listLabel("mobile", null, null));
        assertEquals("", DeliveryStatus.listLabel("desktop", null, null));
        assertEquals("", DeliveryStatus.listLabel(null, null, null));
        // A booking with a driver but no recorded progress (made before the migration) counts as assigned.
        assertEquals(DeliveryStatus.label(DeliveryStatus.ASSIGNED), DeliveryStatus.listLabel(null, "driver.dan", null));
        assertEquals("On the way", DeliveryStatus.listLabel("mobile", "driver.dan", DeliveryStatus.ON_THE_WAY));
    }

    @Test
    void historyTextUsesWords() {
        assertEquals("Started -> On the way", DeliveryStatus.humanize("STARTED -> ON_THE_WAY"));
        assertEquals("Arrived -> Delivered (Received by Aling Nena | ok)",
                DeliveryStatus.humanize("ARRIVED -> DELIVERED (Received by Aling Nena | ok)"));
        assertEquals("ON_THE_WAYS stays, $1 too", DeliveryStatus.humanize("ON_THE_WAYS stays, $1 too"));
        assertNull(DeliveryStatus.humanize(null));
    }
}
