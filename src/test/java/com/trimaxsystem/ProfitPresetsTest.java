package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfitPresetsTest {

    @Test
    void presetRangesIncludeBothDates() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        Map<String, LocalDate[]> p = ProfitService.presets(today);
        assertArrayEquals(new LocalDate[]{today, today}, p.get("Today"));
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26)}, p.get("Yesterday"));
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 10, 21), today}, p.get("Last 7 days"));
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 10, 1), today}, p.get("This month"));
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 1, 1), today}, p.get("This year"));
    }

    @Test
    void longRangesAreShownPerMonth() {
        LocalDate a = LocalDate.of(2026, 1, 1);
        assertFalse(ProfitService.monthly(a, a.plusDays(92)));
        assertTrue(ProfitService.monthly(a, a.plusDays(93)));
    }

    @Test
    void stockMovementLabels() {
        assertEquals("Restock", InventoryService.kindLabel("RESTOCK"));
        assertEquals("Sale voided", InventoryService.kindLabel("SALE_VOIDED"));
        assertEquals("Correction", InventoryService.kindLabel("ADJUSTMENT"));
    }
}
