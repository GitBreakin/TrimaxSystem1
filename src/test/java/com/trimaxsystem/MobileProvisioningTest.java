package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MobileProvisioningTest {

    @Test
    void readsStatusAndMessageFromTheFunctionAnswer() {
        String body = "{\"status\":\"failed\",\"message\":\"A Supabase sign-in with this email already exists "
                + "(made outside TRIMAX). Open the account and use \\\"Link mobile sign-in\\\".\"}";
        assertEquals("failed", MobileProvisioning.jsonString(body, "status"));
        assertEquals("A Supabase sign-in with this email already exists (made outside TRIMAX). Open the account and use "
                + "\"Link mobile sign-in\".", MobileProvisioning.jsonString(body, "message"));
    }

    @Test
    void nullsMissingAndOddValuesAreNull() {
        assertNull(MobileProvisioning.jsonString("{\"status\":\"invited\",\"message\":null}", "message"));
        assertNull(MobileProvisioning.jsonString("{\"status\":\"invited\"}", "error"));
        assertNull(MobileProvisioning.jsonString("{\"count\":3}", "count"));
        assertNull(MobileProvisioning.jsonString(null, "status"));
        assertNull(MobileProvisioning.jsonString("{\"status\":\"unterminated", "status"));
        assertEquals("Ñ\né", MobileProvisioning.jsonString("{\"error\" : \"\\u00d1\\n\\u00e9\"}", "error"));
    }

    @Test
    void onlyInvitedOrLinkedMeansReady() {
        assertTrue(new MobileProvisioning.Result("invited", null).ready());
        assertTrue(new MobileProvisioning.Result("linked", null).ready());
        for (String s : new String[]{"failed", "busy", "error", "not_installed"}) {
            assertFalse(new MobileProvisioning.Result(s, "x").ready(), s);
        }
        assertTrue(new MobileProvisioning.Result("invited", null).describe().contains("Set up or reset password"));
        assertEquals("Try again in a minute.", new MobileProvisioning.Result("busy", "Try again in a minute.").describe());
    }
}
