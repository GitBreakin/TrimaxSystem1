package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GpsFreshnessTest {

    @Test
    void noCoordinatesIsNoLocation() {
        assertEquals(GpsService.Freshness.NO_LOCATION, GpsService.classify(false, 10, 300));
    }

    @Test
    void recentUpdateIsCurrentAndOldIsStale() {
        assertEquals(GpsService.Freshness.CURRENT, GpsService.classify(true, 60, 300));
        assertEquals(GpsService.Freshness.STALE, GpsService.classify(true, 301, 300));
    }

    @Test
    void missingTimestampIsNeverShownAsCurrent() {
        assertEquals(GpsService.Freshness.STALE, GpsService.classify(true, Double.NaN, 300));
    }
}
