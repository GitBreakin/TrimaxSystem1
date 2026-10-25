package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DeliveryStatusTest {

    /** Exactly the pairs in trimax_private.delivery_transition_allowed (sql/20261025_01_...). */
    private static final Set<String> DATABASE_RULE = new HashSet<>(Arrays.asList(
            "ASSIGNED>STARTED", "STARTED>DELIVERED", "STARTED>FAILED",
            "PICKED_UP>DELIVERED", "PICKED_UP>FAILED", "ON_THE_WAY>DELIVERED", "ON_THE_WAY>FAILED",
            "ARRIVED>DELIVERED", "ARRIVED>FAILED"));

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
        assertEquals("Started", DeliveryStatus.listLabel("mobile", "driver.dan", DeliveryStatus.STARTED));
    }

    @Test
    void historyTextUsesWords() {
        assertEquals("Assigned -> Started", DeliveryStatus.humanize("ASSIGNED -> STARTED"));
        assertEquals("Started -> Delivered (Received by Aling Nena | ok)",
                DeliveryStatus.humanize("STARTED -> DELIVERED (Received by Aling Nena | ok)"));
        assertEquals("On the way (older app) -> Delivered", DeliveryStatus.humanize("ON_THE_WAY -> DELIVERED"));
        assertEquals("ON_THE_WAYS stays, $1 too", DeliveryStatus.humanize("ON_THE_WAYS stays, $1 too"));
        assertNull(DeliveryStatus.humanize(null));
    }
}
