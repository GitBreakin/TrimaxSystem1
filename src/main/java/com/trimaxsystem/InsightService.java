package com.trimaxsystem;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sales &amp; Inventory Intelligence from the database (sql/20261028_03_sales_inventory_intelligence.sql).
 * The database does the counting and the classification (trimax_private.product_insights); this class
 * only reads it, so Analytics, Inventory and the alerts use one set of rules. Admins only (the Desktop is
 * the database owner; phones have no access).
 */
public final class InsightService {

    private static final String FN = "trimax_private.product_insights(timestamptz,timestamptz)";

    private InsightService() {
    }

    /** One product's figures for the chosen period. */
    public static final class Item {
        public int productId;
        public String name, supplier;
        public int stock;
        public BigDecimal price, unitsSold, revenue, prevUnits, prevRevenue, trendPct, historyDays, observedDays,
                avgDaily, coverageDays;
        public int orders;
        public Integer daysSinceLastSale, recommendedQty;
        public Timestamp lastSaleAt, knownSince;
        public boolean neverSold;
        public List<String> flags = new ArrayList<>();
        /** Reason and suggested action for each flag, in the same order as {@link #flags}. */
        public List<String> reasons = new ArrayList<>(), actions = new ArrayList<>();

        public boolean has(String flag) {
            return flags.contains(flag);
        }

        public String reason(String flag) {
            int i = flags.indexOf(flag);
            return i < 0 ? "" : reasons.get(i);
        }

        public String action(String flag) {
            int i = flags.indexOf(flag);
            return i < 0 ? "" : actions.get(i);
        }

        /** Every reason, one per line ("Fast-Moving: ..."), for tooltips and details. */
        public String allReasons() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < flags.size(); i++) {
                if (b.length() > 0) b.append('\n');
                b.append(InsightRules.label(flags.get(i))).append(": ").append(reasons.get(i));
            }
            return b.toString();
        }

        public String allActions() {
            List<String> seen = new ArrayList<>();
            for (String a : actions) if (a != null && !a.isBlank() && !seen.contains(a)) seen.add(a);
            return String.join(" ", seen);
        }
    }

    /** The thresholds in use (shown on screen so every flag can be checked). */
    public static final class Settings {
        public int minHistoryDays, nonMovingDays, fastMinUnits, lowStockUnits, criticalCoverageDays,
                overstockCoverageDays, overstockMinStock, leadTimeDays, safetyDays, targetCoverDays, declinePct,
                declineMinPrevUnits, alertWindowDays;
        public BigDecimal fastDailyRate, slowDailyRate;
    }

    public static final class Result {
        public final List<Item> items = new ArrayList<>();
        public Settings settings;
        public Instant from, to;
    }

    /** Thrown when the database update is not installed yet. */
    public static final class NotInstalledException extends Exception {
        NotInstalledException() {
            super("Sales & Inventory Intelligence is not set up on the server yet (database update 20261028_03).");
        }
    }

    public static boolean installed(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT to_regprocedure('" + FN + "') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /** Every product for [from, to). Admins only. */
    public static Result load(Instant from, Instant to)
            throws SQLException, AuthService.AuthException, NotInstalledException {
        AuthService.requireAdmin();
        try (Connection conn = DatabaseConnection.getConnection()) {
            if (!installed(conn)) throw new NotInstalledException();
            Result r = new Result();
            r.from = from;
            r.to = to;
            r.settings = settings(conn);
            r.items.addAll(query(conn, from, to));
            return r;
        }
    }

    /**
     * Flags of the last {@code days} days by product, for the Inventory list. Empty when the update is not
     * installed (the Inventory then just shows no insight).
     */
    public static Map<Integer, Item> latest(int days) throws SQLException {
        Map<Integer, Item> m = new HashMap<>();
        try (Connection conn = DatabaseConnection.getConnection()) {
            if (!installed(conn)) return m;
            Instant to = Instant.now();
            for (Item i : query(conn, to.minus(days, ChronoUnit.DAYS), to)) m.put(i.productId, i);
        }
        return m;
    }

    /**
     * Adds Admin notifications for conditions that newly apply (see the SQL file for the rules); returns
     * how many. Does nothing (returns 0) when the update is not installed.
     */
    public static int refreshAlerts() throws SQLException, AuthService.AuthException {
        AuthService.Session me = AuthService.requireAdmin();
        try (Connection conn = DatabaseConnection.getConnection()) {
            if (!installed(conn)) return 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT trimax_private.refresh_inventory_alerts(?)")) {
                ps.setString(1, me.username);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    private static List<Item> query(Connection conn, Instant from, Instant to) throws SQLException {
        List<Item> out = new ArrayList<>();
        String sql = "SELECT i.*, "
                + "ARRAY(SELECT coalesce(i.reasons ->> f, '') FROM unnest(i.flags) WITH ORDINALITY AS u(f, n) ORDER BY n) AS reason_list, "
                + "ARRAY(SELECT coalesce(i.actions ->> f, '') FROM unnest(i.flags) WITH ORDINALITY AS u(f, n) ORDER BY n) AS action_list "
                + "FROM trimax_private.product_insights(?, ?) i ORDER BY i.product_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(from));
            ps.setTimestamp(2, Timestamp.from(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Item i = new Item();
                    i.productId = rs.getInt("product_id");
                    i.name = rs.getString("product_name");
                    i.supplier = rs.getString("supplier");
                    i.stock = rs.getInt("stock");
                    i.price = rs.getBigDecimal("price");
                    i.unitsSold = rs.getBigDecimal("units_sold");
                    i.revenue = rs.getBigDecimal("revenue");
                    i.orders = rs.getInt("orders");
                    i.prevUnits = rs.getBigDecimal("prev_units");
                    i.prevRevenue = rs.getBigDecimal("prev_revenue");
                    i.trendPct = rs.getBigDecimal("trend_pct");
                    i.lastSaleAt = rs.getTimestamp("last_sale_at");
                    int d = rs.getInt("days_since_last_sale");
                    i.daysSinceLastSale = rs.wasNull() ? null : d;
                    i.neverSold = rs.getBoolean("never_sold");
                    i.knownSince = rs.getTimestamp("known_since");
                    i.historyDays = rs.getBigDecimal("history_days");
                    i.observedDays = rs.getBigDecimal("observed_days");
                    i.avgDaily = rs.getBigDecimal("avg_daily");
                    i.coverageDays = rs.getBigDecimal("coverage_days");
                    int q = rs.getInt("recommended_qty");
                    i.recommendedQty = rs.wasNull() ? null : q;
                    i.flags = strings(rs.getArray("flags"));
                    i.reasons = strings(rs.getArray("reason_list"));
                    i.actions = strings(rs.getArray("action_list"));
                    out.add(i);
                }
            }
        }
        return out;
    }

    private static Settings settings(Connection conn) throws SQLException {
        Settings s = new Settings();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM trimax_private.inventory_insight_settings LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                s.minHistoryDays = rs.getInt("min_history_days");
                s.nonMovingDays = rs.getInt("non_moving_days");
                s.fastDailyRate = rs.getBigDecimal("fast_daily_rate");
                s.fastMinUnits = rs.getInt("fast_min_units");
                s.slowDailyRate = rs.getBigDecimal("slow_daily_rate");
                s.lowStockUnits = rs.getInt("low_stock_units");
                s.criticalCoverageDays = rs.getInt("critical_coverage_days");
                s.overstockCoverageDays = rs.getInt("overstock_coverage_days");
                s.overstockMinStock = rs.getInt("overstock_min_stock");
                s.leadTimeDays = rs.getInt("lead_time_days");
                s.safetyDays = rs.getInt("safety_days");
                s.targetCoverDays = rs.getInt("target_cover_days");
                s.declinePct = rs.getInt("decline_pct");
                s.declineMinPrevUnits = rs.getInt("decline_min_prev_units");
                s.alertWindowDays = rs.getInt("alert_window_days");
            }
        }
        return s;
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) return new ArrayList<>();
        Object[] v = (Object[]) a.getArray();
        List<String> out = new ArrayList<>();
        for (Object o : v) out.add(o == null ? "" : o.toString());
        return out;
    }

    /** Plain-language summary of the rules, with the thresholds in use. */
    public static String rulesText(Settings s) {
        return "Units sold = completed bookings (on the day they were completed) + completed Walk-In / In-House sales "
                + "(paid or unpaid); cancelled, pending and voided ones are not counted. "
                + "Fast-moving: " + InsightRules.qty(s.fastDailyRate) + "+ per day and at least " + s.fastMinUnits + " sold. "
                + "Slow-moving: below " + InsightRules.qty(s.slowDailyRate) + " per day. "
                + "Non-moving: in stock, no sale in " + s.nonMovingDays + " days. "
                + "Overstock: " + s.overstockMinStock + "+ in stock lasting over " + s.overstockCoverageDays + " days. "
                + "Low stock: " + s.lowStockUnits + " or fewer, or under " + s.criticalCoverageDays + " days left. "
                + "Restock assumes " + s.leadTimeDays + " days lead time + " + s.safetyDays + " days safety, ordering for "
                + s.targetCoverDays + " days (no supplier lead times or purchase orders are recorded). "
                + "Products recorded for under " + s.minHistoryDays + " days: Insufficient Data.";
    }

    static List<String> list(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }
}
