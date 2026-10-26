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
 * Payroll records (Admin only; the tables are invisible to the phone): saving and voiding through
 * trimax_private.save_payroll / void_payroll (checked and audited there), and the list with totals.
 */
final class PayrollService {

    private PayrollService() {
    }

    static final class Employee {
        String username, name, role, status;

        @Override
        public String toString() {
            return name + "  (" + role + (status != null && !"Active".equals(status) ? ", " + status : "") + ")";
        }
    }

    static List<Employee> employees() throws Db.Failure {
        List<Employee> out = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select u.username, u.name, coalesce(r.role_name, ''), u.account_status "
                     + "from users u left join roles r on r.role_id = u.role_id order by lower(u.name), u.username");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Employee e = new Employee();
                e.username = rs.getString(1);
                e.name = rs.getString(2);
                e.role = rs.getString(3);
                e.status = rs.getString(4);
                out.add(e);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the employees");
        }
        return out;
    }

    static final class Record {
        long id;
        String username, name, status, method, notes, createdBy, updatedBy, voidedBy, voidReason;
        LocalDate start, end, paidAt;
        BigDecimal basic, allowances, deductions, net;
        Timestamp createdAt, updatedAt, voidedAt;

        String period() {
            return start + " to " + end;
        }
    }

    static String statusLabel(String s) {
        return "PAID".equals(s) ? "Paid" : "UNPAID".equals(s) ? "Unpaid" : "VOID".equals(s) ? "Voided" : s;
    }

    /** Net pay = basic + allowances - deductions (the same as the database's generated column). */
    static BigDecimal net(BigDecimal basic, BigDecimal allowances, BigDecimal deductions) {
        BigDecimal b = basic == null ? BigDecimal.ZERO : basic;
        BigDecimal a = allowances == null ? BigDecimal.ZERO : allowances;
        BigDecimal d = deductions == null ? BigDecimal.ZERO : deductions;
        return b.add(a).subtract(d);
    }

    static long save(Long id, UUID requestId, String username, String name, LocalDate start, LocalDate end,
                     BigDecimal basic, BigDecimal allowances, BigDecimal deductions, boolean paid, LocalDate paidAt,
                     String method, String notes) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select trimax_private.save_payroll(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, admin);
            if (id == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, id);
            if (id == null) ps.setObject(3, requestId); else ps.setNull(3, Types.OTHER);
            ps.setString(4, username);
            ps.setString(5, name);
            ps.setDate(6, Date.valueOf(start));
            ps.setDate(7, Date.valueOf(end));
            ps.setBigDecimal(8, basic);
            ps.setBigDecimal(9, allowances);
            ps.setBigDecimal(10, deductions);
            ps.setString(11, paid ? "PAID" : "UNPAID");
            if (paid && paidAt != null) ps.setDate(12, Date.valueOf(paidAt)); else ps.setNull(12, Types.DATE);
            ps.setString(13, paid ? method : null);
            ps.setString(14, notes);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "The payroll record was not saved");
        }
    }

    static void voidRecord(long id, String reason) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select trimax_private.void_payroll(?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setLong(2, id);
            ps.setString(3, reason);
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw Db.failure(e, "The payroll record was not voided");
        }
    }

    static final class Page {
        final List<Record> rows = new ArrayList<>();
        int count;
        BigDecimal total = BigDecimal.ZERO, paid = BigDecimal.ZERO, unpaid = BigDecimal.ZERO;
    }

    /**
     * Records whose pay period ENDS in [from, to] (that is when payroll counts in the profit reports).
     * username null = everyone. status: null = unpaid and paid, "ALL" = also voided, or one status.
     */
    static Page list(String username, String status, LocalDate from, LocalDate to) throws Db.Failure {
        Page page = new Page();
        StringBuilder where = new StringBuilder("where r.period_end between ? and ? ");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to));
        if (username != null) {
            where.append("and r.employee_username = ? ");
            args.add(username);
        }
        if (status == null) where.append("and r.status <> 'VOID' ");
        else if (!"ALL".equals(status)) {
            where.append("and r.status = ? ");
            args.add(status);
        }
        String sql = "select payroll_id, employee_username, employee_name, period_start, period_end, basic_pay, allowances, "
                + "deductions, net_pay, status, paid_at, payment_method, notes, created_by, created_at, updated_by, updated_at, "
                + "voided_by, voided_at, void_reason from payroll_records r " + where
                + "order by r.period_end desc, lower(r.employee_name), r.payroll_id desc";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Record r = new Record();
                    r.id = rs.getLong(1);
                    r.username = rs.getString(2);
                    r.name = rs.getString(3);
                    r.start = rs.getDate(4).toLocalDate();
                    r.end = rs.getDate(5).toLocalDate();
                    r.basic = rs.getBigDecimal(6);
                    r.allowances = rs.getBigDecimal(7);
                    r.deductions = rs.getBigDecimal(8);
                    r.net = rs.getBigDecimal(9);
                    r.status = rs.getString(10);
                    Date paid = rs.getDate(11);
                    r.paidAt = paid == null ? null : paid.toLocalDate();
                    r.method = rs.getString(12);
                    r.notes = rs.getString(13);
                    r.createdBy = rs.getString(14);
                    r.createdAt = rs.getTimestamp(15);
                    r.updatedBy = rs.getString(16);
                    r.updatedAt = rs.getTimestamp(17);
                    r.voidedBy = rs.getString(18);
                    r.voidedAt = rs.getTimestamp(19);
                    r.voidReason = rs.getString(20);
                    page.rows.add(r);
                    if (!"VOID".equals(r.status)) {
                        page.count++;
                        page.total = page.total.add(r.net);
                        if ("PAID".equals(r.status)) page.paid = page.paid.add(r.net);
                        else page.unpaid = page.unpaid.add(r.net);
                    }
                }
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the payroll");
        }
        return page;
    }
}
