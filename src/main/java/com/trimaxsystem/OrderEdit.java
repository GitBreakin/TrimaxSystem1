package com.trimaxsystem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The order of one booking while the Admin changes it (Edit order, sql/20261029_03). No database here: it
 * keeps the lines, works out the new total the way the server will, what changes and what the stock must
 * still give, and builds the list the server function takes. The server checks everything again.
 */
final class OrderEdit {

    static final String WHOLE = "WHOLE", HALF = "HALF";
    static final int MAX_QTY = 1000, MAX_LINES = 50;

    /** One line: a product and unit, how many, and its price. */
    static final class Line {
        final int productId;
        final String unit;
        final String name;              // product name, without "(half case)"
        final BigDecimal unitPrice;     // the booking's price for a line already on it; the product's price for a new one
        final BigDecimal discountPerUnit;
        final int originalQty;          // 0 for a new line
        int qty;

        Line(int productId, String unit, String name, BigDecimal unitPrice, BigDecimal discountPerUnit, int originalQty, int qty) {
            this.productId = productId;
            this.unit = HALF.equals(unit) ? HALF : WHOLE;
            this.name = name == null ? "Product " + productId : name;
            this.unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
            this.discountPerUnit = discountPerUnit == null ? BigDecimal.ZERO : discountPerUnit;
            this.originalQty = originalQty;
            this.qty = qty;
        }

        boolean half() {
            return HALF.equals(unit);
        }

        String label() {
            return name + (half() ? " (half case)" : "");
        }

        /** What the line costs after the agent's discount per piece. */
        BigDecimal total() {
            return unitPrice.subtract(discountPerUnit).multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        }

        /** Cases taken from the stock (a half case is 0.5). */
        BigDecimal stockQty(int q) {
            return BigDecimal.valueOf(q).multiply(half() ? new BigDecimal("0.5") : BigDecimal.ONE);
        }

        boolean isNew() {
            return originalQty == 0;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private final List<Line> removed = new ArrayList<>();

    /** Starts from the booking as it was loaded. Lines without a product row cannot be edited (null). */
    static OrderEdit of(List<BookingService.Line> booking) {
        OrderEdit e = new OrderEdit();
        for (BookingService.Line l : booking) {
            if (l.productId == null) return null;
            e.lines.add(new Line(l.productId, l.half ? HALF : WHOLE, l.productName, l.unitPrice, l.discountPerUnit,
                    l.quantity, l.quantity));
        }
        return e;
    }

    List<Line> lines() {
        return lines;
    }

    /** Sets a line's quantity (1-1000). */
    void setQty(int index, int qty) {
        if (qty < 1 || qty > MAX_QTY) throw new IllegalArgumentException("The quantity must be between 1 and " + MAX_QTY + ".");
        lines.get(index).qty = qty;
    }

    /** Removes a line (the last one cannot be removed: cancel the booking instead). */
    void remove(int index) {
        if (lines.size() == 1) {
            throw new IllegalStateException("Keep at least one item. To remove every item, cancel the booking instead.");
        }
        Line l = lines.remove(index);
        if (!l.isNew()) removed.add(l);
    }

    /**
     * Adds a product, or more of it when the booking already has that product and unit. A product removed
     * in this edit and added again comes back with its old price and discount.
     */
    void add(int productId, String unit, String name, BigDecimal price, int qty) {
        if (qty < 1 || qty > MAX_QTY) throw new IllegalArgumentException("The quantity must be between 1 and " + MAX_QTY + ".");
        String u = HALF.equals(unit) ? HALF : WHOLE;
        for (Line l : lines) {
            if (l.productId == productId && l.unit.equals(u)) {
                if (l.qty + qty > MAX_QTY) throw new IllegalArgumentException("The quantity must be between 1 and " + MAX_QTY + ".");
                l.qty += qty;
                return;
            }
        }
        if (lines.size() >= MAX_LINES) throw new IllegalStateException("A booking can have at most " + MAX_LINES + " lines.");
        for (int i = 0; i < removed.size(); i++) {
            Line r = removed.get(i);
            if (r.productId == productId && r.unit.equals(u)) {
                removed.remove(i);
                r.qty = qty;
                lines.add(r);
                return;
            }
        }
        if (price == null) {
            throw new IllegalArgumentException(HALF.equals(u) ? name + " is not sold by the half case." : name + " has no price.");
        }
        lines.add(new Line(productId, u, name, price, BigDecimal.ZERO, 0, qty));
    }

    /** The items total after the change (the server recomputes it the same way). */
    BigDecimal total() {
        BigDecimal t = BigDecimal.ZERO;
        for (Line l : lines) t = t.add(l.total());
        return t.setScale(2, RoundingMode.HALF_UP);
    }

    /** True when something differs from the booking as loaded. */
    boolean changed() {
        if (!removed.isEmpty()) return true;
        for (Line l : lines) if (l.qty != l.originalQty) return true;
        return false;
    }

    /** "Sprite Case: removed (was 3)", "Coke Case: 3 -> 5", "Royal Case (half case): added 2". */
    List<String> changes() {
        List<String> out = new ArrayList<>();
        for (Line l : lines) {
            if (l.isNew()) out.add(l.label() + ": added " + l.qty);
            else if (l.qty != l.originalQty) out.add(l.label() + ": " + l.originalQty + " -> " + l.qty);
        }
        for (Line r : removed) out.add(r.label() + ": removed (was " + r.originalQty + ")");
        return out;
    }

    /**
     * Cases each product still needs from the stock (only products that need more; a reduction gives stock
     * back), by product id.
     */
    Map<Integer, BigDecimal> extraStockNeeded() {
        Map<Integer, BigDecimal> change = new LinkedHashMap<>();
        for (Line l : lines) change.merge(l.productId, l.stockQty(l.qty).subtract(l.stockQty(l.originalQty)), BigDecimal::add);
        for (Line r : removed) change.merge(r.productId, r.stockQty(r.originalQty).negate(), BigDecimal::add);
        change.values().removeIf(v -> v.signum() <= 0);
        return change;
    }

    /** The first stock problem against the stock the products had when the editor opened, or null. */
    String stockProblem(Map<Integer, BigDecimal> stockNow, Map<Integer, String> names) {
        for (Map.Entry<Integer, BigDecimal> e : extraStockNeeded().entrySet()) {
            BigDecimal left = stockNow.get(e.getKey());
            if (left != null && left.compareTo(e.getValue()) < 0) {
                return "Not enough stock for " + Objects.requireNonNullElse(names.get(e.getKey()), "product " + e.getKey())
                        + ": " + Money.qty(e.getValue()) + " more needed, " + Money.qty(left) + " left.";
            }
        }
        return null;
    }

    /** The list for trimax_private.update_booking_items: [{"product_id":1,"unit":"WHOLE","quantity":3}, ...]. */
    String toJson() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (i > 0) b.append(',');
            b.append("{\"product_id\":").append(l.productId).append(",\"unit\":\"").append(l.unit)
             .append("\",\"quantity\":").append(l.qty).append('}');
        }
        return b.append(']').toString();
    }
}
