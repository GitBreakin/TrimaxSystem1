package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Edit order in the booking window (sql/20261029_03): the order while the Admin changes it. */
class OrderEditTest {

    private static BookingService.Line line(int id, String name, int qty, String price, String less, boolean half) {
        BookingService.Line l = new BookingService.Line();
        l.productId = id;
        l.productName = name;
        l.product = name + (half ? " (half case)" : "");
        l.quantity = qty;
        l.unitPrice = new BigDecimal(price);
        l.subtotal = l.unitPrice.multiply(BigDecimal.valueOf(qty));
        l.discountPerUnit = new BigDecimal(less);
        l.half = half;
        return l;
    }

    /** Aling Nene: 3 Coke (120, 5 off each) + 3 Sprite (100) = 645. */
    private static OrderEdit nene() {
        return OrderEdit.of(List.of(line(1, "Coke Case", 3, "120", "5", false), line(2, "Sprite Case", 3, "100", "0", false)));
    }

    @Test
    void removingTheSpriteKeepsTheCokePriceAndDiscount() {
        OrderEdit e = nene();
        assertEquals(new BigDecimal("645.00"), e.total());
        assertFalse(e.changed());
        e.remove(1);
        assertTrue(e.changed());
        assertEquals(new BigDecimal("345.00"), e.total(), "3 x (120 - 5)");
        assertEquals(List.of("Sprite Case: removed (was 3)"), e.changes());
        assertEquals("[{\"product_id\":1,\"unit\":\"WHOLE\",\"quantity\":3}]", e.toJson());
        assertTrue(e.extraStockNeeded().isEmpty(), "removing only gives stock back");
    }

    @Test
    void quantitiesAndNewProducts() {
        OrderEdit e = nene();
        e.setQty(0, 5);
        e.add(3, OrderEdit.HALF, "Royal Case", new BigDecimal("110"), 2);
        assertEquals(new BigDecimal("1095.00"), e.total(), "5 x 115 + 3 x 100 + 2 x 110");
        assertEquals(List.of("Coke Case: 3 -> 5", "Royal Case (half case): added 2"), e.changes());
        Map<Integer, BigDecimal> more = e.extraStockNeeded();
        assertEquals(0, new BigDecimal("2").compareTo(more.get(1)));
        assertEquals(0, new BigDecimal("1").compareTo(more.get(3)), "2 half cases take 1 case");
        assertFalse(more.containsKey(2));
        e.add(1, OrderEdit.WHOLE, "Coke Case", new BigDecimal("999"), 1);
        assertEquals(6, e.lines().get(0).qty, "adding a product already on the order adds to its line, at its price");
        assertEquals(new BigDecimal("120"), e.lines().get(0).unitPrice);
    }

    @Test
    void aRemovedLineAddedAgainKeepsItsOldPrice() {
        OrderEdit e = nene();
        e.remove(0);
        e.add(1, OrderEdit.WHOLE, "Coke Case", new BigDecimal("130"), 3);
        assertFalse(e.changed(), "back to the same order");
        assertEquals(new BigDecimal("645.00"), e.total());
    }

    @Test
    void stockAndLimits() {
        OrderEdit e = nene();
        e.setQty(1, 10);   // 7 more Sprite
        assertEquals("Not enough stock for Sprite Case: 7 more needed, 4 left.",
                e.stockProblem(Map.of(1, new BigDecimal("50"), 2, new BigDecimal("4")), Map.of(1, "Coke Case", 2, "Sprite Case")));
        assertNull(e.stockProblem(Map.of(2, new BigDecimal("7")), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> e.setQty(0, 0));
        assertThrows(IllegalArgumentException.class, () -> e.setQty(0, 1001));
        e.remove(1);
        assertThrows(IllegalStateException.class, () -> e.remove(0), "the last item stays: cancel the booking instead");
        assertThrows(IllegalArgumentException.class, () -> e.add(9, OrderEdit.HALF, "No Half", null, 1),
                "a half case without a half case price");
    }

    @Test
    void aLineWhoseProductWasDeletedCannotBeEdited() {
        BookingService.Line gone = line(1, "Old", 1, "10", "0", false);
        gone.productId = null;
        assertNull(OrderEdit.of(List.of(gone)));
    }

    @Test
    void theBookingsListTotals() {
        assertEquals("This list: 43 bookings  ·  ₱ 50,000.00 (cancelled not counted)     |     "
                        + "Delivered by Maria Santos: 12 bookings  ·  ₱ 24,500.00",
                BookingsPanel.totalsText(43, new BigDecimal("50000"), 12, new BigDecimal("24500"), "Maria Santos", false, false)
                        .replace("₱ ", "₱ "));
        assertEquals("Delivered: 1 booking  ·  " + Theme.money(new BigDecimal("120")),
                BookingsPanel.totalsText(1, new BigDecimal("120"), 1, new BigDecimal("120"), null, false, true));
        assertTrue(BookingsPanel.totalsText(0, BigDecimal.ZERO, 0, BigDecimal.ZERO, null, false, false).startsWith("This list: 0 bookings  ·  "));
    }
}
