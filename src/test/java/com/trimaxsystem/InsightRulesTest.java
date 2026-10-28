package com.trimaxsystem;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InsightRulesTest {

    private static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    @Test
    void labelsAreReadableAndInAFixedOrder() {
        assertEquals("Critical / Low Stock · Fast-Moving · Restock",
                InsightRules.labels(Arrays.asList("RESTOCK", "FAST", "CRITICAL")));
        assertEquals("Slow-Moving · Overstock", InsightRules.labels(Arrays.asList("OVERSTOCK", "SLOW")));
        assertEquals("Insufficient Data", InsightRules.labels(List.of("INSUFFICIENT_DATA")));
        assertEquals("Normal", InsightRules.labels(List.of("NORMAL")));
    }

    @Test
    void lowStockAndSlowMovingAreDifferentConditionsAndCanBeCombined() {
        // a fast seller can be low on stock; a slow seller can be overstocked
        assertEquals("CRITICAL", InsightRules.main(Arrays.asList("FAST", "CRITICAL")));
        assertEquals("SLOW", InsightRules.main(Arrays.asList("OVERSTOCK", "SLOW")));
        assertNotEquals(InsightRules.label("CRITICAL"), InsightRules.label("SLOW"));
        assertEquals("NORMAL", InsightRules.main(new ArrayList<>()));
    }

    @Test
    void trendHandlesZeroPreviousSales() {
        assertEquals("+20%", InsightRules.trend(d("12"), d("10"), d("20.0")));
        assertEquals("-75%", InsightRules.trend(d("5"), d("20"), d("-75.0")));
        assertEquals("0%", InsightRules.trend(d("5"), d("5"), d("0")));
        assertEquals("new", InsightRules.trend(d("3"), d("0"), null));
        assertEquals("", InsightRules.trend(d("0"), d("0"), null));
    }

    @Test
    void figuresWithoutASalesRateShowADash() {
        assertEquals("-", InsightRules.perDay(null));
        assertEquals("-", InsightRules.coverage(null));
        assertEquals("1.4", InsightRules.perDay(d("1.400")));
        assertEquals("0.33", InsightRules.perDay(d("0.333")));
        assertEquals("4 days", InsightRules.coverage(d("3.6")));
        assertEquals("42", InsightRules.qty(d("42.000")));
        assertEquals("2.5", InsightRules.qty(d("2.50")));
    }

    @Test
    void bestSellingSortsByQuantityOrByRevenue() {
        class P {
            final String n;
            final BigDecimal u, r;

            P(String n, String u, String r) {
                this.n = n;
                this.u = d(u);
                this.r = d(r);
            }
        }
        P water = new P("Water", "100", "1000"), rice = new P("Rice", "10", "5000"), soda = new P("Soda", "100", "2000");
        List<P> list = new ArrayList<>(Arrays.asList(water, rice, soda));
        list.sort(InsightRules.bestSelling(p -> p.u, p -> p.r, p -> p.n, false));
        assertEquals(Arrays.asList(soda, water, rice), list, "by quantity; equal quantity -> higher revenue first");
        list.sort(InsightRules.bestSelling(p -> p.u, p -> p.r, p -> p.n, true));
        assertEquals(Arrays.asList(rice, soda, water), list, "by revenue: high revenue is not high unit sales");
    }

    @Test
    void supplierNamesMatchIgnoringCapitalsAndSpaces() {
        List<String> names = Arrays.asList("Coca-Cola Company", "Pepsi Co.");
        assertEquals("Pepsi Co.", SupplierService.match(names, "  pepsi   co. "));
        assertNull(SupplierService.match(names, "Coca Cola"), "a different spelling is a different supplier");
        assertEquals("Bulacan Water Co.", SupplierService.tidy("  Bulacan   Water  Co. "));
        assertNotNull(SupplierService.check("X"));
        assertNull(SupplierService.check("RC"));
    }
}
