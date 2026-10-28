package com.trimaxsystem;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Operational report over bookings created in a date range, read from the same tables the booking and
 * delivery screens use (bookings, booking_items, clients, products, the server's delivery events and
 * admin alerts), in one consistent snapshot.
 *
 * Definitions (shown to the admin on the Analytics page and written into every export):
 *  - A booking belongs to the day it was CREATED (transc_date), in Asia/Manila time. Both dates are inclusive.
 *    Each booking is counted once: in one period, one status, one driver row and one customer row.
 *  - Completion rate   = Completed / all bookings created in the range.
 *  - Cancellation rate = Cancelled / all bookings created in the range.
 *    Bookings still open (Pending, Confirmed, In Progress) are in the denominator, so a recent range
 *    shows lower completion until its bookings finish. With no bookings the rate is undefined (null), never 0%.
 *  - Value = sum of total_amount of bookings that are not Cancelled.
 *  - Status = the booking's status now (Pending, Confirmed, In Progress, Completed, Cancelled).
 *  - Driver activity: bookings per assigned driver; Delivered / Failed = the driver's delivery result
 *    (delivery_status). Average start-to-delivered time uses the server's delivery events and only
 *    bookings that have both; it is not a score or a ranking.
 *  - Products: quantities and subtotals of order lines of bookings that are not Cancelled. "Stock now" is
 *    today's stock, not the stock at the time.
 *  - Exceptions: SOS and problem reports from the mobile app (admin alerts created in the range, and how
 *    many of them are not resolved yet), failed deliveries and cancelled bookings.
 */
public final class ReportService {

    public static final String TZ = "Asia/Manila";
    public static final int MAX_DAYS = 366;
    private static final int MAX_DRIVER_ROWS = 500;
    private static final int MAX_CUSTOMER_ROWS = 100;
    private static final int MAX_PRODUCT_ROWS = 500;
    /** Booking statuses in workflow order (bookings.status). */
    public static final List<String> STATUSES = List.of("Pending", "Confirmed", "In Progress", "Completed", "Cancelled");

    private ReportService() {
    }

    /** How the trend is grouped. Weeks start on Monday (ISO). */
    public enum Grouping {
        DAY("day"), WEEK("week"), MONTH("month");

        final String sqlUnit;

        Grouping(String sqlUnit) {
            this.sqlUnit = sqlUnit;
        }

        /** "2026-10-25", "Week 43 (from 2026-10-19)", "October 2026". */
        public String label(LocalDate start) {
            switch (this) {
                case WEEK:
                    return "Week " + start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + " (from " + start + ")";
                case MONTH:
                    return start.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US));
                default:
                    return start.toString();
            }
        }

        /** First day of the period that contains d (the same rule as Postgres date_trunc). */
        public LocalDate startOf(LocalDate d) {
            switch (this) {
                case WEEK:
                    return d.minusDays(d.getDayOfWeek().getValue() - 1L);
                case MONTH:
                    return d.withDayOfMonth(1);
                default:
                    return d;
            }
        }
    }

    public static final class DayRow {
        /** First day of the period (the day itself when grouping by day). */
        public LocalDate day;
        public int total, completed, cancelled;
        public BigDecimal value = BigDecimal.ZERO;
    }

    public static final class DriverRow {
        public String username, name;      // both null = bookings with no driver
        public int total, completed, cancelled, open, delivered, failed;
        /** Average minutes from Start delivery to Delivered, and over how many bookings (0 = no data). */
        public Double avgMinutes;
        public int timedBookings;
    }

    public static final class CustomerRow {
        public Integer clientId;
        public String name;
        public int bookings, completed;
        public BigDecimal value = BigDecimal.ZERO;
        public Timestamp lastBooking;
    }

    public static final class ProductRow {
        public int productId;
        public String name;
        /** Cases ordered; a half case counts 0.5 (20261028_07). */
        public BigDecimal quantity = BigDecimal.ZERO;
        public int bookings;
        public BigDecimal value = BigDecimal.ZERO;
        public BigDecimal stockNow;
    }

    public static final class Report {
        public LocalDate from, to;
        public Grouping grouping = Grouping.DAY;
        public int total, completed, cancelled, open;
        public BigDecimal value = BigDecimal.ZERO;
        /** One row per period with bookings, oldest first (periods without bookings are not listed). */
        public final List<DayRow> days = new ArrayList<>();
        public final List<DriverRow> drivers = new ArrayList<>();
        public boolean driversTruncated;
        /** Every status in STATUSES order, with 0 when none (plus any unexpected value at the end). */
        public final Map<String, Integer> byStatus = new LinkedHashMap<>();
        public int fromMobile, fromDesktop;
        public final List<CustomerRow> customers = new ArrayList<>();
        public boolean customersTruncated;
        public final List<ProductRow> products = new ArrayList<>();
        public boolean productsTruncated;
        public int failedDeliveries;
        /** Alert category -> count (e.g. "SOS", "Delivery problem"); null when alerts are not set up. */
        public Map<String, Integer> alerts = new LinkedHashMap<>();
        /** Of those alerts, how many nobody has resolved yet; null when resolving is not set up (sql/20261025_07). */
        public Integer openAlerts;
        /** False when the server's delivery events could not be read (average times are then not shown). */
        public boolean deliveryTimesAvailable = true;

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

    /** Same as before Stage G: grouped by day. */
    public static Report run(LocalDate from, LocalDate to) throws SQLException, AuthService.AuthException {
        return run(from, to, Grouping.DAY);
    }

    public static Report run(LocalDate from, LocalDate to, Grouping grouping) throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        String bad = validateRange(from, to);
        if (bad != null) throw new IllegalArgumentException(bad);
        Grouping g = grouping == null ? Grouping.DAY : grouping;

        String[] sql = sql(g);
        String perPeriod = sql[0], perStatus = sql[1], perDriver = sql[2], driverTimes = sql[3],
                perCustomer = sql[4], perProduct = sql[5], perAlert = sql[6], openAlerts = sql[7];

        Report r = new Report();
        r.from = from;
        r.to = to;
        r.grouping = g;
        for (String s : STATUSES) r.byStatus.put(s, 0);
        try (Connection conn = DatabaseConnection.getConnection()) {
            // one consistent snapshot for every query below
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setReadOnly(true);
            try (PreparedStatement ps = range(conn.prepareStatement(perPeriod), from, to, null)) {
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
            try (PreparedStatement ps = range(conn.prepareStatement(perStatus), from, to, null);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String status = rs.getString(1) == null ? "(no status)" : rs.getString(1);
                    r.byStatus.merge(status, rs.getInt(2), Integer::sum);
                    r.fromMobile += rs.getInt(3);
                    r.failedDeliveries += rs.getInt(4);
                }
            }
            r.fromDesktop = r.total - r.fromMobile;
            try (PreparedStatement ps = range(conn.prepareStatement(perDriver), from, to, MAX_DRIVER_ROWS + 1);
                 ResultSet rs = ps.executeQuery()) {
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
                    d.delivered = rs.getInt(6);
                    d.failed = rs.getInt(7);
                    d.open = d.total - d.completed - d.cancelled;
                    r.drivers.add(d);
                }
            }
            Savepoint sp = conn.setSavepoint();
            try (PreparedStatement ps = range(conn.prepareStatement(driverTimes), from, to, null);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    for (DriverRow d : r.drivers) {
                        if (rs.getString(1).equals(d.username)) {
                            d.timedBookings = rs.getInt(2);
                            d.avgMinutes = rs.getDouble(3);
                        }
                    }
                }
            } catch (SQLException e) {
                if (!missing(e)) throw e;
                conn.rollback(sp);            // no delivery events on this database: times not shown
                r.deliveryTimesAvailable = false;
            }
            try (PreparedStatement ps = range(conn.prepareStatement(perCustomer), from, to, MAX_CUSTOMER_ROWS + 1);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (r.customers.size() == MAX_CUSTOMER_ROWS) {
                        r.customersTruncated = true;
                        break;
                    }
                    CustomerRow c = new CustomerRow();
                    int id = rs.getInt(1);
                    c.clientId = rs.wasNull() ? null : id;
                    c.name = rs.getString(2);
                    c.bookings = rs.getInt(3);
                    c.completed = rs.getInt(4);
                    c.value = rs.getBigDecimal(5);
                    c.lastBooking = rs.getTimestamp(6);
                    r.customers.add(c);
                }
            }
            try (PreparedStatement ps = range(conn.prepareStatement(perProduct), from, to, MAX_PRODUCT_ROWS + 1);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (r.products.size() == MAX_PRODUCT_ROWS) {
                        r.productsTruncated = true;
                        break;
                    }
                    ProductRow p = new ProductRow();
                    p.productId = rs.getInt(1);
                    p.name = rs.getString(2);
                    p.quantity = InventoryService.qtyOf(rs.getBigDecimal(3));
                    p.bookings = rs.getInt(4);
                    p.value = rs.getBigDecimal(5);
                    BigDecimal stock = rs.getBigDecimal(6);
                    p.stockNow = stock == null ? null : InventoryService.qtyOf(stock);
                    r.products.add(p);
                }
            }
            sp = conn.setSavepoint();
            try (PreparedStatement ps = range(conn.prepareStatement(perAlert), from, to, null);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.alerts.put(rs.getString(1), rs.getInt(2));
            } catch (SQLException e) {
                if (!missing(e)) throw e;
                conn.rollback(sp);            // no admin_notifications table: alerts not set up
                r.alerts = null;
            }
            if (r.alerts != null) {
                sp = conn.setSavepoint();
                try (PreparedStatement ps = range(conn.prepareStatement(openAlerts), from, to, null);
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    r.openAlerts = rs.getInt(1);
                } catch (SQLException e) {
                    if (!missing(e) && !"42703".equals(e.getSQLState())) throw e;
                    conn.rollback(sp);        // resolved_at not there yet (sql/20261025_07 not applied)
                    r.openAlerts = null;
                }
            }
            conn.commit();
        }
        return r;
    }

    /**
     * The report's SQL, in order: per period, per status, per driver, driver times, per customer, per
     * product, alerts, alerts still open. Each takes (from date, to date[, limit]). Package-private so the queries can be
     * checked against a test database.
     */
    static String[] sql(Grouping g) {
        String day = "(b.transc_date AT TIME ZONE '" + TZ + "')::date";
        String inRange = day + " BETWEEN ? AND ?";
        String perPeriod = "SELECT date_trunc('" + g.sqlUnit + "', " + day + ")::date AS d, COUNT(*), "
                + "COUNT(*) FILTER (WHERE b.status = 'Completed'), "
                + "COUNT(*) FILTER (WHERE b.status = 'Cancelled'), "
                + "COALESCE(SUM(b.total_amount) FILTER (WHERE b.status <> 'Cancelled'), 0) "
                + "FROM bookings b WHERE " + inRange + " GROUP BY d ORDER BY d";
        String perStatus = "SELECT b.status, COUNT(*), "
                + "COUNT(*) FILTER (WHERE b.created_via = 'mobile'), "
                + "COUNT(*) FILTER (WHERE b.delivery_status = 'FAILED') "
                + "FROM bookings b WHERE " + inRange + " GROUP BY b.status";
        String perDriver = "SELECT b.driver_username, u.name, COUNT(*), "
                + "COUNT(*) FILTER (WHERE b.status = 'Completed'), "
                + "COUNT(*) FILTER (WHERE b.status = 'Cancelled'), "
                + "COUNT(*) FILTER (WHERE b.delivery_status = 'DELIVERED'), "
                + "COUNT(*) FILTER (WHERE b.delivery_status = 'FAILED') "
                + "FROM bookings b LEFT JOIN users u ON u.username = b.driver_username "
                + "WHERE " + inRange + " "
                + "GROUP BY b.driver_username, u.name ORDER BY COUNT(*) DESC, b.driver_username LIMIT ?";
        String driverTimes = "SELECT b.driver_username, COUNT(*), AVG(EXTRACT(EPOCH FROM (d.at - s.at))) / 60.0 "
                + "FROM bookings b "
                + "JOIN LATERAL (SELECT MIN(e.occurred_at) AS at FROM trimax_private.delivery_events e "
                + "      WHERE e.booking_id = b.booking_id AND e.event_type = 'delivery_status' AND e.to_status = 'STARTED') s ON TRUE "
                + "JOIN LATERAL (SELECT MAX(e.occurred_at) AS at FROM trimax_private.delivery_events e "
                + "      WHERE e.booking_id = b.booking_id AND e.event_type = 'delivery_status' AND e.to_status = 'DELIVERED') d ON TRUE "
                + "WHERE " + inRange + " AND b.driver_username IS NOT NULL AND s.at IS NOT NULL AND d.at IS NOT NULL AND d.at >= s.at "
                + "GROUP BY b.driver_username";
        String perCustomer = "SELECT b.client_id, c.client_name, COUNT(*), "
                + "COUNT(*) FILTER (WHERE b.status = 'Completed'), "
                + "COALESCE(SUM(b.total_amount) FILTER (WHERE b.status <> 'Cancelled'), 0), MAX(b.transc_date) "
                + "FROM bookings b LEFT JOIN clients c ON c.client_id = b.client_id "
                + "WHERE " + inRange + " GROUP BY b.client_id, c.client_name "
                + "ORDER BY COUNT(*) DESC, 5 DESC, c.client_name LIMIT ?";
        // stock_qty: a half case counts 0.5 (20261028_07); read through to_jsonb so older servers work too
        String perProduct = "SELECT p.product_id, p.product_name, "
                + "COALESCE(SUM(coalesce((to_jsonb(i) ->> 'stock_qty')::numeric, i.quantity)), 0), "
                + "COUNT(DISTINCT b.booking_id), COALESCE(SUM(i.subtotal), 0), p.stock "
                + "FROM booking_items i JOIN bookings b ON b.booking_id = i.booking_id "
                + "JOIN products p ON p.product_id = i.product_id "
                + "WHERE " + inRange + " AND b.status <> 'Cancelled' "
                + "GROUP BY p.product_id, p.product_name, p.stock ORDER BY 3 DESC, p.product_name LIMIT ?";
        String perAlert = "SELECT COALESCE(NULLIF(n.category, ''), n.kind), COUNT(*) FROM admin_notifications n "
                + "WHERE n.kind = 'Alert' AND (n.created_at AT TIME ZONE '" + TZ + "')::date BETWEEN ? AND ? "
                + "GROUP BY 1 ORDER BY 2 DESC";
        String openAlerts = "SELECT COUNT(*) FROM admin_notifications n WHERE n.kind = 'Alert' AND n.resolved_at IS NULL "
                + "AND (n.created_at AT TIME ZONE '" + TZ + "')::date BETWEEN ? AND ?";
        return new String[]{perPeriod, perStatus, perDriver, driverTimes, perCustomer, perProduct, perAlert, openAlerts};
    }

    private static PreparedStatement range(PreparedStatement ps, LocalDate from, LocalDate to, Integer limit) throws SQLException {
        ps.setDate(1, Date.valueOf(from));
        ps.setDate(2, Date.valueOf(to));
        if (limit != null) ps.setInt(3, limit);
        return ps;
    }

    /** 42P01 no such table, 3F000 no such schema, 42501 not allowed: the optional part is skipped. */
    private static boolean missing(SQLException e) {
        String s = e.getSQLState();
        return "42P01".equals(s) || "3F000".equals(s) || "42501".equals(s);
    }
}
