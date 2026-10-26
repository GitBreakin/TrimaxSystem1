package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class MobileIntegrationTest {

    @Test
    void showsTheDatabaseRuleWithoutServerNoise() {
        SQLException e = new SQLException(
                "ERROR: The driver has already started this delivery (STARTED), so it cannot be reassigned.\n"
                        + "  Where: PL/pgSQL function trimax_private.bookings_before_write() line 13 at RAISE", "TM409");
        assertEquals("The driver has already started this delivery (STARTED), so it cannot be reassigned.",
                MobileIntegration.friendlyMessage(e));
    }

    @Test
    void findsTheRuleInsideAWrapper() {
        SQLException e = new SQLException("ERROR: That sign-in has not confirmed its email address yet.", "TM422");
        assertEquals("That sign-in has not confirmed its email address yet.",
                MobileIntegration.friendlyMessage(new RuntimeException(e)));
    }

    @Test
    void ignoresOrdinaryDatabaseErrors() {
        assertNull(MobileIntegration.friendlyMessage(new SQLException("ERROR: connection refused", "08001")));
        assertNull(MobileIntegration.friendlyMessage(new SQLException("no state")));
        assertNull(MobileIntegration.friendlyMessage(new IllegalStateException("x")));
        assertNull(MobileIntegration.friendlyMessage(null));
    }
}
