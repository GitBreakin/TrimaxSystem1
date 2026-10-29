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

    /**
     * Someone payroll can be for: an app account (username), or an employee without one (staffId, from the
     * Employees list, sql/20261029_01).
     */
    static final class Employee {
        String username, name, role, status;
        Long staffId;

        boolean isStaff() {
            return staffId != null;
        }

        /** Same person (account or employee without one). */
        boolean same(Employee o) {
            return o != null && (isStaff() ? staffId.equals(o.staffId) : username != null && username.equals(o.username));
        }

        @Override
        public String toString() {
            return name + "  (" + role + (status != null && !"Active".equals(status) ? ", " + status : "") + ")";
        }
    }

    /** App accounts, then (on a server with 20261029_01) the employees without an account. */
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
        if (Db.payrollStaffReady()) {
            for (Staff s : staff()) out.add(s.asEmployee());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ employees without an account

    /** An employee without an app account (helper, cashier...): sql/20261029_01. */
    static final class Staff {
        long id;
        String name, position, contact;
        boolean active;

        Employee asEmployee() {
            Employee e = new Employee();
            e.staffId = id;
            e.name = name;
            e.role = position == null || position.isBlank() ? "no app account" : position;
            e.status = active ? "Active" : "No longer working here";
            return e;
        }
    }

    static List<Staff> staff() throws Db.Failure {
        List<Staff> out = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select staff_id, name, position, contact_num, active from payroll_staff "
                     + "order by active desc, lower(name), staff_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Staff s = new Staff();
                s.id = rs.getLong(1);
                s.name = rs.getString(2);
                s.position = rs.getString(3);
                s.contact = rs.getString(4);
                s.active = rs.getBoolean(5);
                out.add(s);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the employees", Db.UPDATE_29_PAYROLL_STAFF);
        }
        return out;
    }

    /** Adds (id null) or edits an employee without an account; the database checks and audits it. */
    static long saveStaff(Long id, String name, String position, String contact, boolean active) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select trimax_private.save_payroll_staff(?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, admin);
            if (id == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, id);
            ps.setString(3, name);
            ps.setString(4, position);
            ps.setString(5, contact);
            ps.setBoolean(6, active);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "The employee was not saved", Db.UPDATE_29_PAYROLL_STAFF);
        }
    }

    /** The problem with an employee's details, or null (the database checks again). */
    static String staffProblem(String name, String position, String contact) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 100) return "Enter the employee's full name (2 to 100 characters).";
        if (position != null && position.trim().length() > 50) return "The position is too long (up to 50 characters).";
        String c = contact == null ? "" : contact.replaceAll("[\\s-]", "");
        if (!c.isEmpty() && !c.matches("09\\d{9}")) {
            return "Contact number must be 11 digits and start with 09 (for example, 09123456789).";
        }
        return null;
    }

    static final class Record {
        long id;
        /** The employee without an account this record is for (20261029_01), or null. */
        Long staffId;
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
        return save(id, requestId, username, null, name, start, end, basic, allowances, deductions, paid, paidAt, method, notes);
    }

    /** staffId: an employee without an account (needs 20261029_01); then username is null. */
    static long save(Long id, UUID requestId, String username, Long staffId, String name, LocalDate start, LocalDate end,
                     BigDecimal basic, BigDecimal allowances, BigDecimal deductions, boolean paid, LocalDate paidAt,
                     String method, String notes) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(staffId != null
                     ? "select trimax_private.save_payroll(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                     : "select trimax_private.save_payroll(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
            if (staffId != null) ps.setLong(15, staffId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "The payroll record was not saved", staffId != null ? Db.UPDATE_29_PAYROLL_STAFF : Db.UPDATE_27);
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
        return list(username, null, status, from, to);
    }

    /** As above; staffId: only that employee without an account (needs 20261029_01). */
    static Page list(String username, Long staffId, String status, LocalDate from, LocalDate to) throws Db.Failure {
        Page page = new Page();
        StringBuilder where = new StringBuilder("where r.period_end between ? and ? ");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to));
        if (username != null) {
            where.append("and r.employee_username = ? ");
            args.add(username);
        }
        if (staffId != null) {
            where.append("and r.staff_id = ? ");
            args.add(staffId);
        }
        if (status == null) where.append("and r.status <> 'VOID' ");
        else if (!"ALL".equals(status)) {
            where.append("and r.status = ? ");
            args.add(status);
        }
        String sql = "select payroll_id, employee_username, employee_name, period_start, period_end, basic_pay, allowances, "
                + "deductions, net_pay, status, paid_at, payment_method, notes, created_by, created_at, updated_by, updated_at, "
                // staff_id read through to_jsonb so the list also works on a server without 20261029_01
                + "voided_by, voided_at, void_reason, (to_jsonb(r) ->> 'staff_id')::bigint from payroll_records r " + where
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
                    long sid = rs.getLong(21);
                    r.staffId = rs.wasNull() ? null : sid;
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
