package com.trimaxsystem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The lines of a Walk-In / In-House sale before it is saved: one line per product, quantity and a peso
 * discount per line. Only for the screen; the database works the totals out again from its own prices
 * (trimax_private.create_sale) and refuses anything that does not add up.
 */
final class SaleCart {

    static final class Line {
        final int productId;
        final String name;
        final BigDecimal price;     // selling price per unit
        final BigDecimal cost;      // puhunan per unit, null when unknown
        final int stock;            // in stock when the product list was loaded
        int quantity;
        BigDecimal discount = BigDecimal.ZERO;

        Line(int productId, String name, BigDecimal price, BigDecimal cost, int stock) {
            this.productId = productId;
            this.name = name;
            this.price = price;
            this.cost = cost;
            this.stock = stock;
        }

        BigDecimal subtotal() {
            return price.multiply(BigDecimal.valueOf(quantity));
        }

        BigDecimal total() {
            return subtotal().subtract(discount);
        }

        /** True when this line sells below its puhunan (allowed for an Admin, but worth a warning). */
        boolean belowCost() {
            return cost != null && total().compareTo(cost.multiply(BigDecimal.valueOf(quantity))) < 0;
        }
    }

    private final List<Line> lines = new ArrayList<>();

    List<Line> lines() {
        return Collections.unmodifiableList(lines);
    }

    boolean isEmpty() {
        return lines.isEmpty();
    }

    void clear() {
        lines.clear();
    }

    /** Adds one more of the product (a new line, or +1 on its line). */
    Line add(int productId, String name, BigDecimal price, BigDecimal cost, int stock) {
        for (Line l : lines) {
            if (l.productId == productId) {
                l.quantity++;
                return l;
            }
        }
        Line l = new Line(productId, name, price, cost, stock);
        l.quantity = 1;
        lines.add(l);
        return l;
    }

    void remove(int index) {
        lines.remove(index);
    }

    /** Sets a quantity; 0 removes the line. */
    void setQuantity(int index, int quantity) {
        if (quantity < 0) throw new IllegalArgumentException("A quantity cannot be negative.");
        if (quantity == 0) {
            lines.remove(index);
            return;
        }
        lines.get(index).quantity = quantity;
    }

    void setDiscount(int index, BigDecimal discount) {
        if (discount == null || discount.signum() < 0) throw new IllegalArgumentException("A discount cannot be negative.");
        lines.get(index).discount = discount;
    }

    BigDecimal subtotal() {
        BigDecimal s = BigDecimal.ZERO;
        for (Line l : lines) s = s.add(l.subtotal());
        return s;
    }

    BigDecimal discount() {
        BigDecimal s = BigDecimal.ZERO;
        for (Line l : lines) s = s.add(l.discount);
        return s;
    }

    BigDecimal total() {
        return subtotal().subtract(discount());
    }

    /** The first problem that stops the sale, or null when it can be saved. */
    String problem() {
        if (lines.isEmpty()) return "Add at least one product.";
        if (lines.size() > 100) return "A sale can have at most 100 products.";
        for (Line l : lines) {
            if (l.quantity < 1) return "Enter a quantity for " + l.name + ".";
            if (l.quantity > l.stock) return "Only " + l.stock + " of " + l.name + " in stock.";
            if (l.discount.compareTo(l.subtotal()) > 0) return "The discount for " + l.name + " is more than its subtotal.";
        }
        return null;
    }

    /** Names of the lines sold below puhunan (empty when none). */
    List<String> belowCost() {
        List<String> out = new ArrayList<>();
        for (Line l : lines) if (l.belowCost()) out.add(l.name);
        return out;
    }

    /** [{"product_id":1,"quantity":2,"discount":5.00}, ...] for trimax_private.create_sale. */
    String itemsJson() {
        StringBuilder b = new StringBuilder("[");
        for (Line l : lines) {
            if (b.length() > 1) b.append(',');
            b.append("{\"product_id\":").append(l.productId)
                    .append(",\"quantity\":").append(l.quantity)
                    .append(",\"discount\":").append(l.discount.toPlainString()).append('}');
        }
        return b.append(']').toString();
    }
}
