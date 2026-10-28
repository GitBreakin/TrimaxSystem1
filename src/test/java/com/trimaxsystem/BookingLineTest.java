package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** A booking line with an agent's discount per piece (20261027_04). */
class BookingLineTest {

    @Test
    void lineDiscountAndTotal() {
        BookingService.Line l = new BookingService.Line();
        l.quantity = 2;
        l.unitPrice = new BigDecimal("25.00");
        l.subtotal = new BigDecimal("50.00");
        assertEquals(0, BigDecimal.ZERO.compareTo(l.lineDiscount()), "no discount by default");
        assertEquals(0, new BigDecimal("50.00").compareTo(l.lineTotal()));
        l.discountPerUnit = new BigDecimal("2.00");
        assertEquals(0, new BigDecimal("4.00").compareTo(l.lineDiscount()));
        assertEquals(0, new BigDecimal("46.00").compareTo(l.lineTotal()));
    }

    @Test
    void halfCaseLine() {
        BookingService.Line l = new BookingService.Line();
        l.half = true;
        l.quantity = 3;
        l.unitPrice = new BigDecimal("125.00");
        l.subtotal = new BigDecimal("375.00");
        l.discountPerUnit = new BigDecimal("5.00");
        assertEquals(0, new BigDecimal("360.00").compareTo(l.lineTotal()), "the discount is per half case");
    }
}
