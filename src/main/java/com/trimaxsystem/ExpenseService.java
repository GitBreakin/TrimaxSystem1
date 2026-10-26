package com.trimaxsystem;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Other expenses (Admin only; the tables are invisible to the phone): saving and voiding through
 * trimax_private.save_expense / void_expense (checked and audited there, sql/20261027_02_expenses.sql),
 * the list with totals, and the change history of one expense. Non-voided expenses (paid and unpaid)
 * lower net profit in Sales & Profit, by the expense date.
 */
final class ExpenseService {

    private ExpenseService() {
    }

    /** { code, label } in the order the screens show them. The database accepts exactly these codes. */
    static final String[][] CATEGORIES = {
            {"ELECTRICITY", "Electricity"}, {"WATER", "Water"}, {"RENT", "Rent"}, {"FUEL", "Fuel"},
            {"REPAIRS", "Repairs & maintenance"}, {"SUPPLIES", "Supplies"}, {"INTERNET_PHONE", "Internet & phone"},
            {"TAXES_PERMITS", "Taxes & permits"}, {"OTHER", "Other"}};

    /** { code, label }: the payroll methods plus Check. */
    static final String[][] METHODS = {
            {"CASH", "Cash"}, {"GCASH", "GCash"}, {"BANK_TRANSFER", "Bank transfer"}, {"CHECK", "Check"}, {"OTHER", "Other"}};

    static String categoryLabel(String code) {
        for (String[] c : CATEGORIES) if (c[0].equals(code)) return c[1];
        return code == null ? "" : code;
    }

    static String methodLabel(String code) {
        if (code == null) return "";
        for (String[] m : METHODS) if (m[0].equals(code)) return m[1];
        return code;
    }

    static final class Record {
        long id;
        LocalDate date, paidAt;
        String category, description, paymentStatus, method, notes, createdBy, updatedBy, voidedBy, voidReason;
        BigDecimal amount;
        Timestamp createdAt, updatedAt, voidedAt;

        boolean voided() {
            return voidedAt != null;
        }

        /** "Paid", "Unpaid" or "Voided" (a voided expense keeps its payment status underneath). */
        String statusLabel() {
            if (voided()) return "Voided";
            return "PAID".equals(paymentStatus) ? "Paid" : "Unpaid";
        }
    }

    static final class Page {
        final List<Record> rows = new ArrayList<>();
        int count;
        BigDecimal total = BigDecimal.ZERO, paid = BigDecimal.ZERO, unpaid = BigDecimal.ZERO;

        /** Adds a row; only non-voided rows count in the totals (as in the profit report). */
        void add(Record r) {
            rows.add(r);
            if (r.voided()) return;
            count++;
            total = total.add(r.amount);
            if ("PAID".equals(r.paymentStatus)) paid = paid.add(r.amount);
            else unpaid = unpaid.add(r.amount);
        }
    }

    static long save(Long id, UUID requestId, LocalDate date, String category, String description, BigDecimal amount,
                     boolean paid, LocalDate paidAt, String method, String notes) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select trimax_private.save_expense(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, admin);
            if (id == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, id);
            if (id == null) ps.setObject(3, requestId); else ps.setNull(3, Types.OTHER);
            ps.setDate(4, Date.valueOf(date));
            ps.setString(5, category);
            ps.setString(6, description);
            ps.setBigDecimal(7, amount);
            ps.setString(8, paid ? "PAID" : "UNPAID");
            if (paid && paidAt != null) ps.setDate(9, Date.valueOf(paidAt)); else ps.setNull(9, Types.DATE);
            ps.setString(10, paid ? method : null);
            ps.setString(11, notes);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "The expense was not saved", Db.UPDATE_27_EXPENSES);
        }
    }

    static void voidExpense(long id, String reason) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select trimax_private.void_expense(?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setLong(2, id);
            ps.setString(3, reason);
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw Db.failure(e, "The expense was not voided", Db.UPDATE_27_EXPENSES);
        }
    }

    /**
     * Expenses dated in [from, to]. category null = all. status: null = unpaid and paid, "ALL" = also
     * voided, "VOID" = only voided, or "PAID" / "UNPAID". search: words in the description or notes.
     */
    static Page list(String category, String status, LocalDate from, LocalDate to, String search) throws Db.Failure {
        Page page = new Page();
        StringBuilder where = new StringBuilder("where e.expense_date between ? and ? ");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to));
        if (category != null) {
            where.append("and e.category = ? ");
            args.add(category);
        }
        if (status == null) where.append("and e.voided_at is null ");
        else if ("VOID".equals(status)) where.append("and e.voided_at is not null ");
        else if (!"ALL".equals(status)) {
            where.append("and e.voided_at is null and e.payment_status = ? ");
            args.add(status);
        }
        String q = search == null ? "" : search.trim();
        if (!q.isEmpty()) {
            where.append("and (e.description ilike ? or coalesce(e.notes, '') ilike ?) ");
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        String sql = "select expense_id, expense_date, category, description, amount, payment_status, paid_at, payment_method, "
                + "notes, created_by, created_at, updated_by, updated_at, voided_by, voided_at, void_reason from expenses e "
                + where + "order by e.expense_date desc, e.expense_id desc";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Record r = new Record();
                    r.id = rs.getLong(1);
                    r.date = rs.getDate(2).toLocalDate();
                    r.category = rs.getString(3);
                    r.description = rs.getString(4);
                    r.amount = rs.getBigDecimal(5);
                    r.paymentStatus = rs.getString(6);
                    Date paid = rs.getDate(7);
                    r.paidAt = paid == null ? null : paid.toLocalDate();
                    r.method = rs.getString(8);
                    r.notes = rs.getString(9);
                    r.createdBy = rs.getString(10);
                    r.createdAt = rs.getTimestamp(11);
                    r.updatedBy = rs.getString(12);
                    r.updatedAt = rs.getTimestamp(13);
                    r.voidedBy = rs.getString(14);
                    r.voidedAt = rs.getTimestamp(15);
                    r.voidReason = rs.getString(16);
                    page.add(r);
                }
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the expenses", Db.UPDATE_27_EXPENSES);
        }
        return page;
    }

    static final class Change {
        String action, by, summary;
        Timestamp at;
    }

    /** The history of one expense, oldest first, each line in plain words. */
    static List<Change> history(long id) throws Db.Failure {
        List<Change> out = new ArrayList<>();
        String sql = "select action, changed_by, changed_at, "
                + "after_row ->> 'expense_date', after_row ->> 'category', after_row ->> 'description', "
                + "(after_row ->> 'amount')::numeric, after_row ->> 'payment_status', "
                + "before_row ->> 'expense_date', before_row ->> 'category', before_row ->> 'description', "
                + "(before_row ->> 'amount')::numeric, before_row ->> 'payment_status', after_row ->> 'void_reason', "
                + "before_row ->> 'notes', after_row ->> 'notes' "
                + "from expense_history where expense_id = ? order by history_id";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Change c = new Change();
                    c.action = rs.getString(1);
                    c.by = rs.getString(2);
                    c.at = rs.getTimestamp(3);
                    c.summary = describe(c.action, rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
                            rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getBigDecimal(12),
                            rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16));
                    out.add(c);
                }
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the expense history", Db.UPDATE_27_EXPENSES);
        }
        return out;
    }

    /** One history line in words; only what changed is listed for an edit. */
    static String describe(String action, String date, String category, String description, BigDecimal amount,
                           String status, String oldDate, String oldCategory, String oldDescription, BigDecimal oldAmount,
                           String oldStatus, String voidReason, String oldNotes, String notes) {
        if ("CREATE".equals(action)) {
            return "Added: " + date + ", " + categoryLabel(category) + ", \"" + description + "\", " + Money.peso(amount)
                    + ", " + ("PAID".equals(status) ? "paid" : "unpaid");
        }
        if ("VOID".equals(action)) {
            return "Voided: " + (voidReason == null ? "" : voidReason);
        }
        List<String> parts = new ArrayList<>();
        if (!eq(oldDate, date)) parts.add("date " + oldDate + " → " + date);
        if (!eq(oldCategory, category)) parts.add("category " + categoryLabel(oldCategory) + " → " + categoryLabel(category));
        if (!eq(oldDescription, description)) parts.add("description \"" + oldDescription + "\" → \"" + description + "\"");
        if (oldAmount != null && amount != null && oldAmount.compareTo(amount) != 0) {
            parts.add("amount " + Money.peso(oldAmount) + " → " + Money.peso(amount));
        }
        if (!eq(oldStatus, status)) {
            parts.add(("PAID".equals(oldStatus) ? "paid" : "unpaid") + " → " + ("PAID".equals(status) ? "paid" : "unpaid"));
        }
        if (!eq(oldNotes, notes)) parts.add("notes changed");
        return parts.isEmpty() ? "Edited (payment details)" : "Edited: " + String.join(", ", parts);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
