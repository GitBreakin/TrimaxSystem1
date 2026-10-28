package com.trimaxsystem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sales and profit from the database (trimax_private.profit_summary / profit_trend, sql/20261027_01
 * part 9, sql/20261027_02 and sql/20261028_04), so the Dashboard, Sales & Profit and the exports all use
 * the same rules:
 *   Net sales      = sales - discounts   (completed bookings by completion date + paid Walk-In / In-House sales)
 *   Gross profit   = net sales - cost of goods sold (the puhunan recorded on each sold line)
 *   Net profit     = gross profit - payroll - other expenses - bad orders
 *   Payroll        = pay periods ending in the range, paid and unpaid
 *   Other expenses = expenses dated in the range, paid and unpaid, not voided (20261027_02)
 *   Bad orders     = puhunan of broken / damaged / expired... items found in the range, not undone (20261028_04)
 * Days are Asia/Manila days; a range [from, to] includes both dates.
 * On a server without 20261027_02 / 20261028_04 those columns are missing: they read as 0,
 * {@link Summary#expensesTracked} / {@link Summary#badOrdersTracked} is false, and net profit is the
 * server's (without them).
 */
final class ProfitService {

    private ProfitService() {
    }

    static final class Summary {
        LocalDate from, to;
        int bookingsCount, salesCount, unpaidCount, payrollCount, restockCount, bookingsNoCost, salesNoCost, otherExpensesCount;
        BigDecimal bookingsGross, bookingsDiscount, bookingsNet, bookingsCogs;
        BigDecimal salesGross, salesDiscount, salesNet, salesCogs;
        BigDecimal unpaidTotal, revenueGross, discounts, revenueNet, cogs, grossProfit;
        BigDecimal payrollPaid, payrollUnpaid, netProfit, restockCost;
        BigDecimal otherExpensesPaid = BigDecimal.ZERO, otherExpensesUnpaid = BigDecimal.ZERO;
        /** False when the server does not have 20261027_02 yet (no other expenses in net profit). */
        boolean expensesTracked;
        /** Puhunan of the bad orders found in the range (20261028_04); 0 when not tracked. */
        BigDecimal badOrdersLoss = BigDecimal.ZERO;
        int badOrdersCount, badOrdersNoCost;
        /** False when the server does not have 20261028_04 yet (no bad orders in net profit). */
        boolean badOrdersTracked;

        BigDecimal otherExpenses() {
            return otherExpensesPaid.add(otherExpensesUnpaid);
        }

        int linesWithoutCost() {
            return bookingsNoCost + salesNoCost;
        }

        BigDecimal payroll() {
            return payrollPaid.add(payrollUnpaid);
        }

        /** Gross profit as a percent of net sales, or null when there are no sales. */
        BigDecimal grossMargin() {
            if (revenueNet.signum() <= 0) return null;
            return grossProfit.multiply(new BigDecimal(100)).divide(revenueNet, 1, RoundingMode.HALF_UP);
        }

        boolean empty() {
            return bookingsCount == 0 && salesCount == 0 && unpaidCount == 0 && payrollCount == 0 && restockCount == 0
                    && otherExpensesCount == 0 && badOrdersCount == 0;
        }
    }

    private static final String SUMMARY_SQL = "select * from trimax_private.profit_summary("
            + "(?::date)::timestamp at time zone 'Asia/Manila', ((?::date) + 1)::timestamp at time zone 'Asia/Manila')";

    static Summary summary(LocalDate from, LocalDate to) throws Db.Failure {
        try (Connection conn = DatabaseConnection.getConnection()) {
            return summary(conn, from, to);
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load sales and profit");
        }
    }

    private static Summary summary(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SUMMARY_SQL)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Summary s = new Summary();
                s.from = from;
                s.to = to;
                s.bookingsCount = rs.getInt("bookings_count");
                s.bookingsGross = rs.getBigDecimal("bookings_gross");
                s.bookingsDiscount = rs.getBigDecimal("bookings_discount");
                s.bookingsNet = rs.getBigDecimal("bookings_net");
                s.bookingsCogs = rs.getBigDecimal("bookings_cogs");
                s.bookingsNoCost = rs.getInt("bookings_lines_no_cost");
                s.salesCount = rs.getInt("sales_count");
                s.salesGross = rs.getBigDecimal("sales_gross");
                s.salesDiscount = rs.getBigDecimal("sales_discount");
                s.salesNet = rs.getBigDecimal("sales_net");
                s.salesCogs = rs.getBigDecimal("sales_cogs");
                s.salesNoCost = rs.getInt("sales_lines_no_cost");
                s.unpaidCount = rs.getInt("unpaid_sales_count");
                s.unpaidTotal = rs.getBigDecimal("unpaid_sales_total");
                s.revenueGross = rs.getBigDecimal("revenue_gross");
                s.discounts = rs.getBigDecimal("discounts");
                s.revenueNet = rs.getBigDecimal("revenue_net");
                s.cogs = rs.getBigDecimal("cogs");
                s.grossProfit = rs.getBigDecimal("gross_profit");
                s.payrollPaid = rs.getBigDecimal("payroll_paid");
                s.payrollUnpaid = rs.getBigDecimal("payroll_unpaid");
                s.payrollCount = rs.getInt("payroll_count");
                s.netProfit = rs.getBigDecimal("net_profit");
                s.restockCost = rs.getBigDecimal("restock_cost");
                s.restockCount = rs.getInt("restock_count");
                s.expensesTracked = hasColumn(rs.getMetaData(), "other_expenses_paid");
                if (s.expensesTracked) {
                    s.otherExpensesPaid = rs.getBigDecimal("other_expenses_paid");
                    s.otherExpensesUnpaid = rs.getBigDecimal("other_expenses_unpaid");
                    s.otherExpensesCount = rs.getInt("other_expenses_count");
                }
                s.badOrdersTracked = hasColumn(rs.getMetaData(), "bad_orders_loss");
                if (s.badOrdersTracked) {
                    s.badOrdersLoss = rs.getBigDecimal("bad_orders_loss");
                    s.badOrdersCount = rs.getInt("bad_orders_count");
                    s.badOrdersNoCost = rs.getInt("bad_orders_no_cost");
                }
                return s;
            }
        }
    }

    /** Today, Yesterday, Last 7 days, This month and This year, in one connection. */
    static Map<String, Summary> periods(LocalDate today) throws Db.Failure {
        Map<String, Summary> out = new LinkedHashMap<>();
        try (Connection conn = DatabaseConnection.getConnection()) {
            for (Map.Entry<String, LocalDate[]> e : presets(today).entrySet()) {
                out.put(e.getKey(), summary(conn, e.getValue()[0], e.getValue()[1]));
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load sales and profit");
        }
        return out;
    }

    /** The preset ranges, both dates included (Asia/Manila days). */
    static Map<String, LocalDate[]> presets(LocalDate today) {
        Map<String, LocalDate[]> p = new LinkedHashMap<>();
        p.put("Today", new LocalDate[]{today, today});
        p.put("Yesterday", new LocalDate[]{today.minusDays(1), today.minusDays(1)});
        p.put("Last 7 days", new LocalDate[]{today.minusDays(6), today});
        p.put("This month", new LocalDate[]{today.withDayOfMonth(1), today});
        p.put("This year", new LocalDate[]{today.withDayOfYear(1), today});
        return p;
    }

    static final class Point {
        LocalDate bucket;
        BigDecimal revenue, cogs, grossProfit, payroll, netProfit;
        BigDecimal otherExpenses = BigDecimal.ZERO;
        BigDecimal badOrders = BigDecimal.ZERO;
    }

    /** True when the result has that column (case-insensitive; PostgreSQL returns lower case). */
    static boolean hasColumn(ResultSetMetaData md, String name) throws SQLException {
        for (int i = 1; i <= md.getColumnCount(); i++) {
            if (name.equalsIgnoreCase(md.getColumnLabel(i))) return true;
        }
        return false;
    }

    /** Daily points for ranges up to 92 days, monthly ones above that. */
    static boolean monthly(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) > 92;
    }

    static List<Point> trend(LocalDate from, LocalDate to) throws Db.Failure {
        List<Point> out = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select * from trimax_private.profit_trend("
                     + "(?::date)::timestamp at time zone 'Asia/Manila', ((?::date) + 1)::timestamp at time zone 'Asia/Manila', ?)")) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            ps.setString(3, monthly(from, to) ? "month" : "day");
            try (ResultSet rs = ps.executeQuery()) {
                boolean withExpenses = hasColumn(rs.getMetaData(), "other_expenses");
                boolean withBadOrders = hasColumn(rs.getMetaData(), "bad_orders");
                while (rs.next()) {
                    Point p = new Point();
                    p.bucket = rs.getDate("bucket_start").toLocalDate();
                    p.revenue = rs.getBigDecimal("revenue");
                    p.cogs = rs.getBigDecimal("cogs");
                    p.grossProfit = rs.getBigDecimal("gross_profit");
                    p.payroll = rs.getBigDecimal("payroll");
                    p.netProfit = rs.getBigDecimal("net_profit");
                    if (withExpenses) p.otherExpenses = rs.getBigDecimal("other_expenses");
                    if (withBadOrders) p.badOrders = rs.getBigDecimal("bad_orders");
                    out.add(p);
                }
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the sales trend");
        }
        return out;
    }
}
