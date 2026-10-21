package com.trimaxsystem;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Operational report over bookings created in a date range.
 *
 * Definitions (shown to the admin on the Analytics page and written into every export):
 *  - A booking belongs to the day it was CREATED (transc_date), in Asia/Manila time. Both dates are inclusive.
 *  - Completion rate   = Completed / all bookings created in the range.
 *  - Cancellation rate = Cancelled / all bookings created in the range.
 *    Bookings still open (Pending, Confirmed, In Progress) are in the denominator, so a recent range
 *    shows lower completion until its bookings finish. With no bookings the rate is undefined (null), never 0%.
 *  - Value = sum of total_amount of bookings that are not Cancelled.
 *  - Driver activity is plain counts of bookings per assigned driver. It is not a score or a ranking.
 */
public final class ReportService {

    public static final String TZ = "Asia/Manila";
    public static final int MAX_DAYS = 366;
    private static final int MAX_DRIVER_ROWS = 500;

    private ReportService() {
    }

    public static final class DayRow {
        public LocalDate day;
        public int total, completed, cancelled;
        public BigDecimal value = BigDecimal.ZERO;
    }

    public static final class DriverRow {
        public String username, name;      // both null = bookings with no driver
        public int total, completed, cancelled, open;
    }

    public static final class Report {
        public LocalDate from, to;
        public int total, completed, cancelled, open;
        public BigDecimal value = BigDecimal.ZERO;
        public final List<DayRow> days = new ArrayList<>();
        public final List<DriverRow> drivers = new ArrayList<>();
        public boolean driversTruncated;

        public Double completionRate() {
            return rate(completed, total);
        }

        public Double cancellationRate() {
            return rate(cancelled, total);
        }
    }

    /** Percentage 0-100, or null when the denominator is 0 (undefined, not zero). */
    public static Double rate(int part, int whole) {
        return whole <= 0 ? null : part * 100.0 / whole;
    }

    public static String validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) return "Enter both dates as YYYY-MM-DD.";
        if (to.isBefore(from)) return "The end date is before the start date.";
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            return "The range is limited to " + MAX_DAYS + " days.";
        }
        return null;
    }

    public static Report run(LocalDate from, LocalDate to) throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        String bad = validateRange(from, to);
        if (bad != null) throw new IllegalArgumentException(bad);

        String day = "(b.transc_date AT TIME ZONE '" + TZ + "')::date";
        String perDay = "SELECT " + day + " AS d, COUNT(*), "
                + "COUNT(*) FILTER (WHERE b.status = 'Completed'), "
                + "COUNT(*) FILTER (WHERE b.status = 'Cancelled'), "
                + "COALESCE(SUM(b.total_amount) FILTER (WHERE b.status <> 'Cancelled'), 0) "
                + "FROM bookings b WHERE " + day + " BETWEEN ? AND ? GROUP BY d ORDER BY d";
        String perDriver = "SELECT b.driver_username, u.name, COUNT(*), "
                + "COUNT(*) FILTER (WHERE b.status = 'Completed'), "
                + "COUNT(*) FILTER (WHERE b.status = 'Cancelled') "
                + "FROM bookings b LEFT JOIN users u ON u.username = b.driver_username "
                + "WHERE " + day + " BETWEEN ? AND ? "
                + "GROUP BY b.driver_username, u.name ORDER BY COUNT(*) DESC, b.driver_username LIMIT ?";

        Report r = new Report();
        r.from = from;
        r.to = to;
        try (Connection conn = DatabaseConnection.getConnection()) {
            // one consistent snapshot for all three queries
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement(perDay)) {
                ps.setDate(1, Date.valueOf(from));
                ps.setDate(2, Date.valueOf(to));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        DayRow d = new DayRow();
                        d.day = rs.getDate(1).toLocalDate();
                        d.total = rs.getInt(2);
                        d.completed = rs.getInt(3);
                        d.cancelled = rs.getInt(4);
                        d.value = rs.getBigDecimal(5);
                        r.days.add(d);
                        r.total += d.total;
                        r.completed += d.completed;
                        r.cancelled += d.cancelled;
                        r.value = r.value.add(d.value);
                    }
                }
            }
            r.open = r.total - r.completed - r.cancelled;
            try (PreparedStatement ps = conn.prepareStatement(perDriver)) {
                ps.setDate(1, Date.valueOf(from));
                ps.setDate(2, Date.valueOf(to));
                ps.setInt(3, MAX_DRIVER_ROWS + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (r.drivers.size() == MAX_DRIVER_ROWS) {
                            r.driversTruncated = true;
                            break;
                        }
                        DriverRow d = new DriverRow();
                        d.username = rs.getString(1);
                        d.name = rs.getString(2);
                        d.total = rs.getInt(3);
                        d.completed = rs.getInt(4);
                        d.cancelled = rs.getInt(5);
                        d.open = d.total - d.completed - d.cancelled;
                        r.drivers.add(d);
                    }
                }
            }
            conn.commit();
        }
        return r;
    }
}
