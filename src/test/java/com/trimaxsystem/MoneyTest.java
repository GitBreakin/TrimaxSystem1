package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void parsesPesoAmountsAsTyped() {
        assertEquals(d("1250.50"), Money.parse("1,250.50", "price", false));
        assertEquals(d("35.00"), Money.parse("₱35", "price", false));
        assertEquals(d("0.00"), Money.parse("0", "price", false));
        assertNull(Money.parse("  ", "discount", true));
    }

    @Test
    void refusesNegativeTooPreciseOrMissingAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.parse("-1", "puhunan", false));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("10.555", "puhunan", false));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("abc", "puhunan", false));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("", "puhunan", false));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("10000001", "puhunan", false));
    }

    @Test
    void sellingPriceIsPuhunanPlusPatong() {
        // the same results as trimax_private.selling_price in the database
        assertEquals(d("57.00"), Money.sellingPrice(d("45.00"), "AMOUNT", d("12.00")));
        assertEquals(d("100.00"), Money.sellingPrice(d("80.00"), "PERCENT", d("25")));
        assertEquals(d("306.00"), Money.sellingPrice(d("255.00"), "PERCENT", d("20")));
        assertEquals(d("37.50"), Money.sellingPrice(d("33.33"), "PERCENT", d("12.5")));   // 37.49625 rounds half up
        assertNull(Money.sellingPrice(null, "AMOUNT", d("1")));
        assertNull(Money.sellingPrice(d("1"), null, null));
    }

    @Test
    void marginIsPatongShareOfThePrice() {
        assertEquals(d("21.1"), Money.marginPercent(d("45"), d("57")));
        assertNull(Money.marginPercent(null, d("57")));
        assertNull(Money.marginPercent(d("1"), BigDecimal.ZERO));
    }

    @Test
    void wholeNumbersWithinLimits() {
        assertEquals(24, Money.parseInt("24", "quantity", 1, 100));
        assertEquals(-5, Money.parseInt("-5", "change", -10, 10));
        assertThrows(IllegalArgumentException.class, () -> Money.parseInt("0", "quantity", 1, 100));
        assertThrows(IllegalArgumentException.class, () -> Money.parseInt("1.5", "quantity", 1, 100));
    }

    @Test
    void payrollNetPay() {
        assertEquals(d("8200.00"), PayrollService.net(d("8000.00"), d("500.00"), d("300.00")));
        assertEquals(d("600.00"), PayrollService.net(d("600.00"), null, null));
    }
}
