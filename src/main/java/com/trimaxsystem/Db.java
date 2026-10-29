package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Small database helpers shared by the sales, stock, payroll and profit screens.
 * The business rules live in the database functions of sql/20261027_01_sales_inventory_payroll.sql,
 * sql/20261027_02_expenses.sql and sql/20261028_04_bad_orders.sql;
 * these helpers only check that they are there, label stock changes and turn errors into plain text.
 */
final class Db {

    static final String UPDATE_27 = "database update 20261027_01_sales_inventory_payroll";
    static final String UPDATE_27_EXPENSES = "database update 20261027_02_expenses";
    static final String UPDATE_28_BAD_ORDERS = "database update 20261028_04_bad_orders";
    static final String UPDATE_28_BAD_ORDER_LOSS = "database update 20261028_06_bad_order_custom_loss";
    static final String UPDATE_28_HALF_CASE = "database update 20261028_07_half_case";
    static final String UPDATE_29_PAYROLL_STAFF = "database update 20261029_01_payroll_staff";
    static final String UPDATE_29_BOOKING_EDIT = "database update 20261029_03_booking_edit_and_driver_summary";

    private static volatile boolean salesReady;   // only a "yes" is remembered; "no" is asked again
    private static volatile boolean expensesReady;
    private static volatile boolean badOrdersReady;
    private static volatile boolean halfCaseReady;
    private static volatile boolean payrollStaffReady;
    private static volatile boolean bookingEditReady;

    private Db() {
    }

    /** A user-facing failure: the message can be shown as it is. */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    /** True when the server has the sales, stock history and payroll functions (20261027_01). */
    static boolean salesReady() {
        if (salesReady) return true;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select to_regprocedure('trimax_private.create_sale(text,uuid,text,integer,text,jsonb,text,text,numeric,text)') is not null "
                             + "and to_regprocedure('trimax_private.profit_summary(timestamptz,timestamptz)') is not null "
                             + "and to_regclass('public.payroll_records') is not null");
             ResultSet rs = ps.executeQuery()) {
            salesReady = rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
        return salesReady;
    }

    static String notReadyMessage(String what) {
        return notReadyMessage(what, UPDATE_27);
    }

    static String notReadyMessage(String what, String update) {
        return what + " needs the " + update + " on the server. Ask the person who manages the database to apply it.";
    }

    /** True when the server has the expense table and functions (20261027_02), on top of 20261027_01. */
    static boolean expensesReady() {
        if (expensesReady) return true;
        if (!salesReady()) return false;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select to_regprocedure('trimax_private.save_expense(text,bigint,uuid,date,text,text,numeric,text,date,text,text)') is not null "
                             + "and to_regprocedure('trimax_private.void_expense(text,bigint,text)') is not null "
                             + "and to_regclass('public.expenses') is not null");
             ResultSet rs = ps.executeQuery()) {
            expensesReady = rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
        return expensesReady;
    }

    /** The update a screen still needs ("" when the server has both). */
    static String missingExpensesUpdate() {
        if (!salesReady()) return UPDATE_27;
        return expensesReady() ? "" : UPDATE_27_EXPENSES;
    }

    /** True when the server has the bad-order table and functions (20261028_04), on top of 20261027_01. */
    static boolean badOrdersReady() {
        if (badOrdersReady) return true;
        if (!salesReady()) return false;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select to_regprocedure('trimax_private.record_bad_order(text,integer,integer,text,text,date,text,uuid)') is not null "
                             + "and to_regprocedure('trimax_private.void_bad_order(text,bigint,text)') is not null "
                             + "and to_regclass('public.bad_orders') is not null");
             ResultSet rs = ps.executeQuery()) {
            badOrdersReady = rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
        return badOrdersReady;
    }

    /**
     * True when the server sells half cases (20261028_07): stock can end in .5, sale and booking lines carry a
     * unit, and products have a half case price.
     */
    static boolean halfCaseReady() {
        if (halfCaseReady) return true;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select to_regprocedure('trimax_private.set_product_half_price(text,integer,numeric)') is not null "
                             + "and exists (select 1 from information_schema.columns where table_schema = 'public' "
                             + "and table_name = 'sale_items' and column_name = 'stock_qty')");
             ResultSet rs = ps.executeQuery()) {
            halfCaseReady = rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
        return halfCaseReady;
    }

    /**
     * True when the server keeps a list of employees without an app account for payroll (20261029_01).
     */
    static boolean payrollStaffReady() {
        if (payrollStaffReady) return true;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select to_regclass('public.payroll_staff') is not null "
                             + "and to_regprocedure('trimax_private.save_payroll_staff(text,bigint,text,text,text,boolean)') is not null "
                             + "and to_regprocedure('trimax_private.save_payroll(text,bigint,uuid,text,text,date,date,numeric,"
                             + "numeric,numeric,text,date,text,text,bigint)') is not null");
             ResultSet rs = ps.executeQuery()) {
            payrollStaffReady = rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
        return payrollStaffReady;
    }

    /** True when the server lets the Admin change a booking's order after booking (20261029_03). */
    static boolean bookingEditReady() {
        if (bookingEditReady) return true;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select to_regprocedure('trimax_private.update_booking_items(text,integer,text,jsonb,text)') is not null "
                             + "and to_regprocedure('trimax_private.booking_items_signature(integer)') is not null");
             ResultSet rs = ps.executeQuery()) {
            bookingEditReady = rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
        return bookingEditReady;
    }

    /** The update the bad-order screens still need ("" when the server has it). */
    static String missingBadOrdersUpdate() {
        if (!salesReady()) return UPDATE_27;
        return badOrdersReady() ? "" : UPDATE_28_BAD_ORDERS;
    }

    /** A new id for one save, so a double click or a retry is saved only once. */
    static UUID requestId() {
        return UUID.randomUUID();
    }

    /** The signed-in Admin (checked again in the database by every function). */
    static String admin() throws Failure {
        try {
            return AuthService.requireAdmin().username;
        } catch (AuthService.AuthException e) {
            throw new Failure(e.getMessage());
        }
    }

    /**
     * Says why stock changes in this transaction, so the stock history shows it (see the migration,
     * part 3). Works on databases without the migration too (the settings are then just ignored).
     */
    static void stockContext(Connection conn, String kind, String refType, String refId) throws SQLException {
        AuthService.Session s = AuthService.current();
        try (PreparedStatement ps = conn.prepareStatement(
                "select set_config('trimax.stock_kind', ?, true), set_config('trimax.stock_ref_type', ?, true), "
                        + "set_config('trimax.stock_ref_id', ?, true), set_config('trimax.stock_actor', ?, true), "
                        + "set_config('trimax.stock_note', '', true), set_config('trimax.stock_unit_cost', '', true), "
                        + "set_config('trimax.stock_request_id', '', true)")) {
            ps.setString(1, kind);
            ps.setString(2, refType == null ? "" : refType);
            ps.setString(3, refId == null ? "" : refId);
            ps.setString(4, s == null ? "" : s.username);
            ps.executeQuery().close();
        }
    }

    /** The server's message for TRIMAX rules (TMxxx), a "not set up" hint, or "what: reason". */
    static Failure failure(SQLException e, String what) {
        return new Failure(message(e, what));
    }

    /** Like failure(e, what), but a missing table or function names the given database update. */
    static Failure failure(SQLException e, String what, String update) {
        return new Failure(message(e, what, update));
    }

    static String message(SQLException e, String what) {
        return message(e, what, UPDATE_27);
    }

    static String message(SQLException e, String what, String update) {
        String state = e.getSQLState();
        if ("42883".equals(state) || "3F000".equals(state) || "42703".equals(state) || "42P01".equals(state)) {
            return notReadyMessage(what, update);
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        int nl = msg.indexOf('\n');
        msg = (nl < 0 ? msg : msg.substring(0, nl)).replaceFirst("^ERROR:\\s*", "").trim();
        if (state != null && state.startsWith("TM")) return msg;
        if ("08001".equals(state) || "08006".equals(state) || (state != null && state.startsWith("08"))) {
            return what + ": no connection to the database. Check the internet connection and try again.";
        }
        return what + ": " + msg;
    }

    /** JSON string literal (for the small JSON arrays sent to the functions). */
    static String json(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
