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
}
