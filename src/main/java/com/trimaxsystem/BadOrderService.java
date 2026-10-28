package com.trimaxsystem;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bad orders (BO): broken, damaged, expired, defective or spoiled stock taken out of inventory
 * (sql/20261028_04_bad_orders.sql). The database checks the Admin, the quantity against the stock and
 * every field again, records the stock change (BAD_ORDER) and the loss (quantity × puhunan), and keeps
 * a retry with the same request id from taking the stock out twice. The loss lowers net profit in
 * Sales & Profit by the date found. A mistake is undone (voided) with a reason; nothing is deleted.
 */
final class BadOrderService {

    private BadOrderService() {
    }

    /** Reason codes (as in the database) and the words on screen. */
    static final String[][] REASONS = {
            {"BROKEN", "Broken"}, {"DAMAGED", "Damaged"}, {"EXPIRED", "Expired"},
            {"DEFECTIVE", "Defective"}, {"SPOILED", "Spoiled"}, {"OTHER", "Other"}};
    /** What happened to the bad items. */
    static final String[][] DISPOSITIONS = {
            {"DISPOSED", "Thrown away"}, {"RETURNED_TO_SUPPLIER", "Returned to supplier"}};
    /** How far back a date found may go (the database allows the same). */
    static final int MAX_DAYS_BACK = 366;

    static String reasonLabel(String code) {
        return label(REASONS, code);
    }

    static String dispositionLabel(String code) {
        return label(DISPOSITIONS, code);
    }

    private static String label(String[][] table, String code) {
        if (code == null) return "";
        for (String[] r : table) if (r[0].equals(code)) return r[1];
        return code;
    }

    /** Quantity × puhunan, rounded to centavos; null when the product has no puhunan. */
    static BigDecimal loss(int quantity, BigDecimal unitCost) {
        if (unitCost == null) return null;
        return unitCost.multiply(BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * The problem with the input, or null when it can be sent (the database checks again).
     * quantity null = not a whole number.
     */
    static String check(Integer quantity, int stock, String reason, String note, LocalDate found, LocalDate today) {
        if (quantity == null || quantity < 1) return "Enter how many pieces are bad (1 or more).";
        if (quantity > stock) return "Only " + stock + " in stock: a bad order cannot take out more than that.";
        if (reason == null) return "Choose the reason.";
        String n = note == null ? "" : note.trim();
        if ("OTHER".equals(reason) && n.length() < 3) return "Describe the reason in the note.";
        if (n.length() > 300) return "The note is too long (up to 300 characters).";
        if (found == null) return "Write the date found as YYYY-MM-DD, for example " + today + ".";
        if (found.isAfter(today)) return "The date found cannot be in the future.";
        if (found.isBefore(today.minusDays(MAX_DAYS_BACK))) return "The date found must be within the last year.";
        return null;
    }

    /** "Stock 50 → 45 · Loss ₱100.00 (5 × ₱20.00 puhunan)" for the dialog. */
    static String preview(int stock, int quantity, BigDecimal unitCost) {
        String s = "Stock " + stock + " → " + (stock - quantity);
        BigDecimal loss = loss(quantity, unitCost);
        if (loss == null) return s + "  ·  no puhunan: the loss in pesos is not counted";
        return s + "  ·  Loss " + Money.peso(loss) + " (" + quantity + " × " + Money.peso(unitCost) + " puhunan)";
    }

    // ------------------------------------------------------------------------------------ record / undo

    static final class Result {
        long id;
        int stockBefore, stockAfter;
        BigDecimal loss;
        boolean alreadySaved;
    }

    static Result record(int productId, int quantity, String reason, String disposition, LocalDate found,
                         String note, UUID requestId) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select * from trimax_private.record_bad_order(?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setInt(2, productId);
            ps.setInt(3, quantity);
            ps.setString(4, reason);
            ps.setString(5, disposition);
            ps.setDate(6, found == null ? null : Date.valueOf(found));
            ps.setString(7, note);
            ps.setObject(8, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Result r = new Result();
                r.id = rs.getLong("bad_order_id");
                r.stockBefore = rs.getInt("stock_before");
                r.stockAfter = rs.getInt("stock_after");
                r.loss = rs.getBigDecimal("loss_amount");
                r.alreadySaved = rs.getBoolean("already_existed");
                return r;
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not record the bad order", Db.UPDATE_28_BAD_ORDERS);
        }
    }

    /** Puts the pieces back in stock; the loss no longer counts. Returns {before, after}. */
    static int[] undo(long badOrderId, String reason) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select stock_before, stock_after from trimax_private.void_bad_order(?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setLong(2, badOrderId);
            ps.setString(3, reason);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new int[]{rs.getInt(1), rs.getInt(2)};
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not undo the bad order", Db.UPDATE_28_BAD_ORDERS);
        }
    }

    // ------------------------------------------------------------------------------------ list

    static final class Record {
        long id;
        Integer productId;
        String productName, supplier, reason, disposition, note, recordedBy, voidedBy, voidReason;
        int quantity;
        LocalDate foundOn;
        BigDecimal unitCost, loss;
        Timestamp recordedAt, voidedAt;

        boolean undone() {
            return voidedAt != null;
        }
    }

    /** Totals of the bad orders that count (not undone) in the list. */
    static final class Totals {
        int count, units, noCost;
        BigDecimal loss = BigDecimal.ZERO;
    }

    static final class Page {
        final List<Record> rows = new ArrayList<>();
        final Totals all = new Totals();
        /** By reason code, in the order of {@link #REASONS}; only reasons that occur. */
        final Map<String, Totals> byReason = new LinkedHashMap<>();
        int undoneCount;
        boolean truncated;
    }

    /**
     * Bad orders found from..to (both included), newest first. reason / disposition: a code or null for all.
     * withUndone: also list the undone ones (they never count in the totals).
     */
    static Page list(LocalDate from, LocalDate to, String reason, String disposition, Integer productId,
                     boolean withUndone, int limit) throws Db.Failure {
        Page p = new Page();
        String sql = "select bad_order_id, product_id, product_name, supplier, quantity, reason, disposition, found_on, "
                + "unit_cost, loss_amount, note, recorded_by, recorded_at, voided_at, voided_by, void_reason "
                + "from bad_orders where found_on between ? and ? "
                + (reason != null ? "and reason = ? " : "")
                + (disposition != null ? "and disposition = ? " : "")
                + (productId != null ? "and product_id = ? " : "")
                + "order by found_on desc, bad_order_id desc";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setDate(i++, Date.valueOf(from));
            ps.setDate(i++, Date.valueOf(to));
            if (reason != null) ps.setString(i++, reason);
            if (disposition != null) ps.setString(i++, disposition);
            if (productId != null) ps.setInt(i, productId);
            List<Record> all = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Record r = new Record();
                    r.id = rs.getLong(1);
                    int pid = rs.getInt(2);
                    r.productId = rs.wasNull() ? null : pid;
                    r.productName = rs.getString(3);
                    r.supplier = rs.getString(4);
                    r.quantity = rs.getInt(5);
                    r.reason = rs.getString(6);
                    r.disposition = rs.getString(7);
                    r.foundOn = rs.getDate(8).toLocalDate();
                    r.unitCost = rs.getBigDecimal(9);
                    r.loss = rs.getBigDecimal(10);
                    r.note = rs.getString(11);
                    r.recordedBy = rs.getString(12);
                    r.recordedAt = rs.getTimestamp(13);
                    r.voidedAt = rs.getTimestamp(14);
                    r.voidedBy = rs.getString(15);
                    r.voidReason = rs.getString(16);
                    all.add(r);
                }
            }
            summarize(p, all, withUndone, limit);
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the bad orders", Db.UPDATE_28_BAD_ORDERS);
        }
        return p;
    }

    /** Totals over every counted bad order; the list keeps at most limit rows. Package-private for tests. */
    static void summarize(Page p, List<Record> all, boolean withUndone, int limit) {
        for (String[] r : REASONS) {
            for (Record x : all) {
                if (r[0].equals(x.reason) && !x.undone()) {
                    p.byReason.put(r[0], new Totals());
                    break;
                }
            }
        }
        for (Record r : all) {
            if (r.undone()) {
                p.undoneCount++;
            } else {
                add(p.all, r);
                Totals t = p.byReason.get(r.reason);
                if (t != null) add(t, r);
            }
            if (!r.undone() || withUndone) {
                if (p.rows.size() < limit) p.rows.add(r);
                else p.truncated = true;
            }
        }
    }

    private static void add(Totals t, Record r) {
        t.count++;
        t.units += r.quantity;
        if (r.loss == null) t.noCost++;
        else t.loss = t.loss.add(r.loss);
    }

    /** "Broken 2 (₱100.00) · Expired 1 (₱60.00)" ... for the summary line. */
    static String byReasonText(Page p) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Totals> e : p.byReason.entrySet()) {
            if (b.length() > 0) b.append("   ·   ");
            Totals t = e.getValue();
            b.append(reasonLabel(e.getKey())).append(": ").append(t.units).append(t.units == 1 ? " pc" : " pcs").append(" (");
            if (t.noCost == t.count) b.append("no puhunan");
            else b.append(Money.peso(t.loss)).append(t.noCost == 0 ? "" : ", " + t.noCost + " without puhunan");
            b.append(")");
        }
        return b.toString();
    }
}
