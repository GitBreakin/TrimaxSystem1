package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AgentFreshnessTest {

    @Test
    void agentPositionOlderThanLimitIsStale() {
        // The same rule as vehicles: an old position must never be presented as current.
        assertEquals(GpsService.Freshness.STALE, GpsService.classify(true, 301, 300));
        assertEquals(GpsService.Freshness.CURRENT, GpsService.classify(true, 299, 300));
    }
}
