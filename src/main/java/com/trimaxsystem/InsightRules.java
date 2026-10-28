package com.trimaxsystem;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Labels, colours, ordering and wording of Sales &amp; Inventory Intelligence. The classification itself is
 * done ONCE, in the database (trimax_private.product_insights, sql/20261028_03), so Analytics, Inventory and
 * the alerts always agree; this class only presents it. No Swing, no database: it is unit-tested.
 */
public final class InsightRules {

    /** Database flag -> label shown to the Admin, in the order the labels are listed. */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("CRITICAL", "Critical / Low Stock");
        LABELS.put("FAST", "Fast-Moving");
        LABELS.put("RESTOCK", "Restock");
        LABELS.put("SLOW", "Slow-Moving");
        LABELS.put("NON_MOVING", "Non-Moving");
        LABELS.put("OVERSTOCK", "Overstock");
        LABELS.put("DECLINE", "Sales Dropped");
        LABELS.put("INSUFFICIENT_DATA", "Insufficient Data");
        LABELS.put("NORMAL", "Normal");
    }

    private InsightRules() {
    }

    public static String label(String flag) {
        return LABELS.getOrDefault(flag, flag);
    }

    /** "Fast-Moving · Critical / Low Stock" in a fixed order, whatever order the flags came in. */
    public static String labels(List<String> flags) {
        List<String> out = new ArrayList<>();
        for (String f : LABELS.keySet()) if (flags.contains(f)) out.add(LABELS.get(f));
        for (String f : flags) if (!LABELS.containsKey(f)) out.add(f);
        return String.join(" · ", out);
    }

    public static Color color(String flag) {
        switch (flag) {
            case "CRITICAL":
            case "RESTOCK":
                return Theme.DANGER;
            case "FAST":
                return Theme.OK;
            case "SLOW":
            case "OVERSTOCK":
            case "DECLINE":
                return Theme.WARN;
            case "NON_MOVING":
                return Color.decode("#B45309");
            case "INSUFFICIENT_DATA":
                return Theme.MUTED;
            default:
                return Theme.BLUE;
        }
    }

    /** The most important flag (for one colour in a list): the first in {@link #LABELS} order. */
    public static String main(List<String> flags) {
        for (String f : LABELS.keySet()) if (flags.contains(f)) return f;
        return flags.isEmpty() ? "NORMAL" : flags.get(0);
    }

    /** "+20%", "-75%", "new" (sold now, none before), or "" (nothing either time). */
    public static String trend(BigDecimal units, BigDecimal prevUnits, BigDecimal trendPct) {
        if (trendPct != null) {
            BigDecimal t = trendPct.setScale(0, RoundingMode.HALF_UP);
            return (t.signum() > 0 ? "+" : "") + t.toPlainString() + "%";
        }
        boolean now = units != null && units.signum() > 0;
        boolean before = prevUnits != null && prevUnits.signum() > 0;
        if (now && !before) return "new";
        return "";
    }

    /** Units without trailing zeros: 42, 1.5. */
    public static String qty(BigDecimal q) {
        if (q == null) return "";
        BigDecimal s = q.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }

    /** Per-day figure with up to 2 decimals, or "-" when it cannot be calculated. */
    public static String perDay(BigDecimal avg) {
        if (avg == null) return "-";
        return qty(avg.setScale(2, RoundingMode.HALF_UP));
    }

    /** Days of stock, or "-" when the product has no sales rate. */
    public static String coverage(BigDecimal days) {
        if (days == null) return "-";
        return days.setScale(0, RoundingMode.HALF_UP).toPlainString() + " days";
    }

    /** Best-selling order: by quantity (default) or by revenue; ties by the other, then by name. */
    public static <T> Comparator<T> bestSelling(java.util.function.Function<T, BigDecimal> units,
                                                java.util.function.Function<T, BigDecimal> revenue,
                                                java.util.function.Function<T, String> name, boolean byRevenue) {
        Comparator<T> u = Comparator.comparing(units, Comparator.nullsFirst(Comparator.naturalOrder()));
        Comparator<T> r = Comparator.comparing(revenue, Comparator.nullsFirst(Comparator.naturalOrder()));
        Comparator<T> first = byRevenue ? r.reversed().thenComparing(u.reversed()) : u.reversed().thenComparing(r.reversed());
        return first.thenComparing(name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }
}
