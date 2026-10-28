package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SaleCartTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void addingTheSameProductAddsToItsLine() {
        SaleCart c = new SaleCart();
        c.add(1, "Cola", d("57.00"), d("45.00"), 10);
        c.add(1, "Cola", d("57.00"), d("45.00"), 10);
        c.add(2, "Candy", d("5.50"), null, 100);
        assertEquals(2, c.lines().size());
        assertEquals(2, c.lines().get(0).quantity);
        assertEquals(d("119.50"), c.subtotal());
        assertNull(c.problem());
    }

    @Test
    void totalsWithLineDiscounts() {
        SaleCart c = new SaleCart();
        c.add(1, "Cola", d("57.00"), d("45.00"), 10);
        c.setQuantity(0, 3);
        c.setDiscount(0, d("5.00"));
        c.add(2, "Candy", d("5.50"), null, 100);
        c.setQuantity(1, 10);
        assertEquals(d("226.00"), c.subtotal());
        assertEquals(d("5.00"), c.discount());
        assertEquals(d("221.00"), c.total());   // the same sale as the database test (3 x 57 - 5 + 10 x 5.50)
        assertEquals("[{\"product_id\":1,\"quantity\":3,\"discount\":5.00},{\"product_id\":2,\"quantity\":10,\"discount\":0}]",
                c.itemsJson());
    }

    @Test
    void problemsStopTheSale() {
        SaleCart c = new SaleCart();
        assertEquals("Add at least one product.", c.problem());
        c.add(1, "Cola", d("57.00"), d("45.00"), 2);
        c.setQuantity(0, 3);
        assertTrue(c.problem().contains("Only 2"));
        c.setQuantity(0, 1);
        c.setDiscount(0, d("58.00"));
        assertTrue(c.problem().contains("more than its subtotal"));
        assertThrows(IllegalArgumentException.class, () -> c.setDiscount(0, d("-1")));
        c.setQuantity(0, 0);
        assertTrue(c.isEmpty());
    }

    @Test
    void warnsWhenALineSellsBelowPuhunan() {
        SaleCart c = new SaleCart();
        c.add(1, "Cola", d("57.00"), d("45.00"), 10);
        c.setDiscount(0, d("12.00"));
        assertTrue(c.belowCost().isEmpty());          // 45.00 = puhunan: not below
        c.setDiscount(0, d("12.01"));
        assertEquals(1, c.belowCost().size());
        c.add(2, "Candy", d("5.50"), null, 100);       // no puhunan: never flagged
        c.setDiscount(1, d("5.50"));
        assertEquals(1, c.belowCost().size());
    }

    // ---- half cases (sql/20261028_07)

    @Test
    void halfCaseLinesAreSeparateAndUseTheHalfPrice() {
        SaleCart c = new SaleCart();
        c.add(1, "Cola", d("250.00"), d("200.00"), d("10"), false);
        c.add(1, "Cola", d("125.00"), d("200.00"), d("10"), true);
        c.add(1, "Cola", d("125.00"), d("200.00"), d("10"), true);
        assertEquals(2, c.lines().size(), "one whole line and one half line");
        SaleCart.Line half = c.lines().get(1);
        assertTrue(half.half);
        assertEquals("Cola (half case)", half.label());
        assertEquals(2, half.quantity);
        assertEquals(d("100.00"), half.cost, "a half case's puhunan is half the case's");
        assertEquals(d("2"), c.casesOf(1).stripTrailingZeros(), "1 whole + 2 halves = 2 cases");
        assertEquals(d("500.00"), c.subtotal());
        assertNull(c.problem());
        assertEquals("[{\"product_id\":1,\"quantity\":1,\"discount\":0},"
                + "{\"product_id\":1,\"quantity\":2,\"discount\":0,\"unit\":\"HALF\"}]", c.itemsJson());
    }

    @Test
    void halfCasesCountAgainstTheStockTogether() {
        SaleCart c = new SaleCart();
        assertNull(c.addProblem(1, "Cola", d("1.5"), false));
        c.add(1, "Cola", d("250.00"), d("200.00"), d("1.5"), false);
        assertNull(c.addProblem(1, "Cola", d("1.5"), true), "0.5 is left");
        c.add(1, "Cola", d("125.00"), d("200.00"), d("1.5"), true);
        assertNotNull(c.addProblem(1, "Cola", d("1.5"), true), "nothing left");
        assertNotNull(c.addProblem(1, "Cola", d("1.5"), false));
        assertNotNull(c.quantityProblem(1, 2), "2 halves + 1 whole = 2 > 1.5");
        assertNull(c.quantityProblem(1, 1));
        assertNotNull(c.addProblem(2, "Candy", d("0"), true), "out of stock");
        assertNull(c.addProblem(2, "Candy", d("0.5"), true), "a half case can be sold from 0.5");
        assertNotNull(c.addProblem(2, "Candy", d("0.5"), false), "a whole case cannot");
        c.setQuantity(1, 2);
        assertTrue(c.problem().contains("Only 1.5"), c.problem());
    }

    @Test
    void halfCaseBelowPuhunanIsFlagged() {
        SaleCart c = new SaleCart();
        c.add(1, "Cola", d("125.00"), d("200.00"), d("10"), true);
        c.setDiscount(0, d("25.00"));
        assertTrue(c.belowCost().isEmpty(), "100.00 = half the puhunan: not below");
        c.setDiscount(0, d("25.01"));
        assertEquals(java.util.List.of("Cola (half case)"), c.belowCost());
        assertNull(SaleCart.halfCost(null));
        assertEquals(d("22.53"), SaleCart.halfCost(d("45.05")), "rounded to the centavo like the database");
    }
}
