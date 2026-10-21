package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RoleLabelTest {

    @Test
    void legacyRiderBecomesAgentOnly() {
        assertEquals("Agent", AccountService.roleLabel("Rider"));
        assertEquals("Agent", AccountService.roleLabel("rider"));
        assertEquals("Agent", AccountService.roleLabel("Agent"));
        assertEquals("Driver", AccountService.roleLabel("Driver"));
    }
}
