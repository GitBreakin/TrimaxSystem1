package com.trimaxsystem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The lines of a Walk-In / In-House sale before it is saved: one line per product and unit (whole case or
 * half case, sql/20261028_07), quantity and a peso discount per line. A half case is sold at the product's
 * half case price, its puhunan is half the case's, and it takes 0.5 off the stock. Only for the screen; the
 * database works the totals out again from its own prices (trimax_private.create_sale) and refuses anything
 * that does not add up.
 */
final class SaleCart {

    static final BigDecimal HALF = new BigDecimal("0.5");

    static final class Line {
        final int productId;
        final String name;
        final boolean half;         // a half case line (the price and puhunan are the half case's)
        final BigDecimal price;     // selling price per unit (per half case on a half line)
        final BigDecimal cost;      // puhunan per unit, null when unknown
        final BigDecimal stock;     // cases in stock when the product list was loaded
        int quantity;
        BigDecimal discount = BigDecimal.ZERO;

        Line(int productId, String name, BigDecimal price, BigDecimal cost, BigDecimal stock, boolean half) {
            this.productId = productId;
            this.name = name;
            this.half = half;
            this.price = price;
            this.cost = cost;
            this.stock = stock;
        }

        /** "Cola" or "Cola (half case)". */
        String label() {
            return half ? name + " (half case)" : name;
        }

        /** The name in the sale table: "Half case · Cola" (the unit first, so a narrow column still shows it). */
        String cartName() {
            return half ? "Half case \u00B7 " + name : name;
        }

        /** What the line takes off the stock: the quantity, or half of it for half cases. */
        BigDecimal cases() {
            BigDecimal q = BigDecimal.valueOf(quantity);
            return half ? q.multiply(HALF) : q;
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

    /** Adds one more whole case of the product (a new line, or +1 on its line). */
    Line add(int productId, String name, BigDecimal price, BigDecimal cost, int stock) {
        return add(productId, name, price, cost, BigDecimal.valueOf(stock), false);
    }

    /**
     * Adds one more of the product as a whole case, or as a half case (price = the half case price; cost = the
     * product's puhunan per case, halved here the way the database does it).
     */
    Line add(int productId, String name, BigDecimal price, BigDecimal cost, BigDecimal stock, boolean half) {
        for (Line l : lines) {
            if (l.productId == productId && l.half == half) {
                l.quantity++;
                return l;
            }
        }
        Line l = new Line(productId, name, price, half ? halfCost(cost) : cost, stock, half);
        l.quantity = 1;
        lines.add(l);
        return l;
    }

    /** The puhunan of a half case: half the case's, to the centavo (as trimax_private.create_sale). */
    static BigDecimal halfCost(BigDecimal caseCost) {
        return caseCost == null ? null : caseCost.multiply(HALF).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Cases of the product in this sale (whole and half lines together). */
    BigDecimal casesOf(int productId) {
        BigDecimal s = BigDecimal.ZERO;
        for (Line l : lines) if (l.productId == productId) s = s.add(l.cases());
        return s;
    }

    /**
     * Null when the line at index can have the quantity (the product's other line counted too), or the
     * message to show.
     */
    String quantityProblem(int index, int quantity) {
        Line l = lines.get(index);
        BigDecimal other = casesOf(l.productId).subtract(l.cases());
        BigDecimal want = BigDecimal.valueOf(quantity).multiply(l.half ? HALF : BigDecimal.ONE);
        return other.add(want).compareTo(l.stock) > 0 ? stockMessage(l) : null;
    }

    /** Null when one more (whole or half) of the product fits its stock, or the message to show. */
    String addProblem(int productId, String name, BigDecimal stock, boolean half) {
        BigDecimal need = casesOf(productId).add(half ? HALF : BigDecimal.ONE);
        if (stock == null || stock.signum() <= 0) return name + " is out of stock.";
        return need.compareTo(stock) > 0 ? "Only " + Money.qty(stock) + " of " + name + " in stock"
                + (casesOf(productId).signum() > 0 ? " (" + Money.qty(casesOf(productId)) + " already in this sale)." : ".") : null;
    }

    private static String stockMessage(Line l) {
        return "Only " + Money.qty(l.stock) + " of " + l.name + " in stock"
                + (l.half ? " (a half case takes 0.5)." : ".");
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
            if (l.quantity < 1) return "Enter a quantity for " + l.label() + ".";
            if (casesOf(l.productId).compareTo(l.stock) > 0) {
                return "Only " + Money.qty(l.stock) + " of " + l.name + " in stock (this sale takes "
                        + Money.qty(casesOf(l.productId)) + ").";
            }
            if (l.discount.compareTo(l.subtotal()) > 0) return "The discount for " + l.label() + " is more than its subtotal.";
        }
        return null;
    }

    /** Names of the lines sold below puhunan (empty when none). */
    List<String> belowCost() {
        List<String> out = new ArrayList<>();
        for (Line l : lines) if (l.belowCost()) out.add(l.label());
        return out;
    }

    /**
     * [{"product_id":1,"quantity":2,"discount":5.00}, {"product_id":1,"quantity":1,"discount":0,"unit":"HALF"}, ...]
     * for trimax_private.create_sale. "unit" only on half case lines, so a whole-case sale is the same as before.
     */
    String itemsJson() {
        StringBuilder b = new StringBuilder("[");
        for (Line l : lines) {
            if (b.length() > 1) b.append(',');
            b.append("{\"product_id\":").append(l.productId)
                    .append(",\"quantity\":").append(l.quantity)
                    .append(",\"discount\":").append(l.discount.toPlainString());
            if (l.half) b.append(",\"unit\":\"HALF\"");
            b.append('}');
        }
        return b.append(']').toString();
    }
}
