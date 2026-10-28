package com.trimaxsystem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * Peso amounts typed on the screens: parsing with clear messages, formatting, and the pricing formula
 * (the same one the database uses, sql/20261027_01 part 2: selling price = puhunan + patong).
 */
final class Money {

    static final BigDecimal MAX = new BigDecimal("10000000");
    private static final DecimalFormat PLAIN = new DecimalFormat("#,##0.00");

    private Money() {
    }

    /** "1,250.50" (no peso sign). Empty for null. */
    static String plain(BigDecimal v) {
        return v == null ? "" : PLAIN.format(v);
    }

    /** "₱ 1,250.50". Empty for null. */
    static String peso(BigDecimal v) {
        return Theme.money(v);
    }

    /**
     * Reads a peso amount: "1250", "1,250.50", "₱1,250.50". Returns null for an empty field when
     * {@code optional}; otherwise throws with a message naming the field.
     */
    static BigDecimal parse(String text, String label, boolean optional) {
        String t = text == null ? "" : text.trim().replace("₱", "").replace("PHP", "").replace(",", "").trim();
        if (t.isEmpty()) {
            if (optional) return null;
            throw new IllegalArgumentException("Enter the " + label + ".");
        }
        BigDecimal v;
        try {
            v = new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The " + label + " must be a number, for example 1250.50.");
        }
        if (v.signum() < 0) throw new IllegalArgumentException("The " + label + " cannot be negative.");
        if (v.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("The " + label + " can have at most 2 decimals.");
        }
        if (v.compareTo(MAX) > 0) throw new IllegalArgumentException("The " + label + " is too large.");
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** A whole number in [min, max], or an error naming the field. */
    static int parseInt(String text, String label, int min, int max) {
        String t = text == null ? "" : text.trim().replace(",", "");
        if (t.isEmpty()) throw new IllegalArgumentException("Enter the " + label + ".");
        long v;
        try {
            v = Long.parseLong(t.startsWith("+") ? t.substring(1) : t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The " + label + " must be a whole number.");
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException("The " + label + " must be from " + String.format("%,d", min)
                    + " to " + String.format("%,d", max) + ".");
        }
        return (int) v;
    }

    /**
     * A stock or case count as people write it: "10", "9.5", "0" (stock can end in .5 since half cases,
     * sql/20261028_07). Empty for null.
     */
    static String qty(BigDecimal v) {
        if (v == null) return "";
        if (v.signum() == 0) return "0";
        return v.stripTrailingZeros().toPlainString();
    }

    /**
     * A stock change in whole or half cases: "3", "0.5", "1.5" (no sign; see the caller for + / -).
     * Throws with a message naming the field unless it is more than 0, at most max, and a multiple of 0.5.
     */
    static BigDecimal parseHalves(String text, String label, int max) {
        String t = text == null ? "" : text.trim().replace(",", "");
        if (t.isEmpty()) throw new IllegalArgumentException("Enter the " + label + ".");
        BigDecimal v;
        try {
            v = new BigDecimal(t.startsWith("+") ? t.substring(1) : t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The " + label + " must be a number, for example 2 or 1.5.");
        }
        if (v.signum() <= 0 || v.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalArgumentException("The " + label + " must be more than 0 and at most " + String.format("%,d", max) + ".");
        }
        if (v.multiply(BigDecimal.valueOf(2)).stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("The " + label + " must be whole or half cases (for example 2 or 1.5).");
        }
        return new BigDecimal(v.stripTrailingZeros().toPlainString());
    }

    /** Selling price = puhunan + patong (a peso amount, or a percent of the puhunan), to the centavo. */
    static BigDecimal sellingPrice(BigDecimal cost, String markupType, BigDecimal markupValue) {
        if (cost == null || markupType == null || markupValue == null) return null;
        if ("PERCENT".equals(markupType)) {
            return cost.multiply(BigDecimal.ONE.add(markupValue.divide(new BigDecimal(100))))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return cost.add(markupValue).setScale(2, RoundingMode.HALF_UP);
    }

    /** Patong as a share of the selling price ("margin"), e.g. 12 on 57 = 21.1%. Null when unknown. */
    static BigDecimal marginPercent(BigDecimal cost, BigDecimal price) {
        if (cost == null || price == null || price.signum() <= 0) return null;
        return price.subtract(cost).multiply(new BigDecimal(100)).divide(price, 1, RoundingMode.HALF_UP);
    }
}
