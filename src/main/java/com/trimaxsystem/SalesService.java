package com.trimaxsystem;

import java.math.BigDecimal;
import java.sql.Connection;
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
 * Walk-In and In-House sales: product and customer lists, saving (trimax_private.create_sale: one
 * transaction for the sale, its lines and the stock), the history, one sale with its lines, voiding
 * and marking paid. Every rule is checked again in the database.
 */
final class SalesService {

    static final String WALK_IN = "WALK_IN", IN_HOUSE = "IN_HOUSE";
    static final String[][] METHODS = {{"CASH", "Cash"}, {"GCASH", "GCash"}, {"BANK_TRANSFER", "Bank transfer"}, {"OTHER", "Other"}};

    private SalesService() {
    }

    static String typeLabel(String type) {
        return WALK_IN.equals(type) ? "Walk-In" : IN_HOUSE.equals(type) ? "In-House" : type == null ? "" : type;
    }

    static String methodLabel(String method) {
        if (method == null) return "";
        for (String[] m : METHODS) if (m[0].equals(method)) return m[1];
        return method;
    }

    static String paymentLabel(String status) {
        return "PAID".equals(status) ? "Paid" : "UNPAID".equals(status) ? "Unpaid" : status == null ? "" : status;
    }

    static String statusLabel(String status) {
        return "COMPLETED".equals(status) ? "Completed" : "VOIDED".equals(status) ? "Voided" : status == null ? "" : status;
    }

    // ------------------------------------------------------------------------------------ choices

    static final class ProductOption {
        int id, stock;
        String name, supplier;
        BigDecimal price, cost;
    }

    static List<ProductOption> products() throws Db.Failure {
        List<ProductOption> out = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select product_id, product_name, supplier, price, capital_cost, stock from products "
                             + "order by lower(product_name), product_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ProductOption p = new ProductOption();
                p.id = rs.getInt(1);
                p.name = rs.getString(2);
                p.supplier = rs.getString(3);
                p.price = rs.getBigDecimal(4);
                p.cost = rs.getBigDecimal(5);
                p.stock = rs.getInt(6);
                out.add(p);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the products");
        }
        return out;
    }

    static final class Customer {
        int id;
        String name, contact, address;

        @Override
        public String toString() {
            return name + (contact == null || contact.isBlank() ? "" : "  ·  " + contact);
        }
    }

    static List<Customer> customers() throws Db.Failure {
        List<Customer> out = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select client_id, client_name, contact_num, address from clients order by lower(client_name), client_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Customer c = new Customer();
                c.id = rs.getInt(1);
                c.name = rs.getString(2);
                c.contact = rs.getString(3);
                c.address = rs.getString(4);
                out.add(c);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the customers");
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ saving

    static final class Created {
        long saleId;
        String receiptNo;
        BigDecimal total;
        boolean alreadySaved;
    }

    static Created create(UUID requestId, String type, Integer clientId, String customerName, SaleCart cart,
                          boolean paid, String method, BigDecimal tendered, String note) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select * from trimax_private.create_sale(?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setObject(2, requestId);
            ps.setString(3, type);
            if (clientId == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, clientId);
            ps.setString(5, customerName);
            ps.setString(6, cart.itemsJson());
            ps.setString(7, paid ? "PAID" : "UNPAID");
            ps.setString(8, paid ? method : null);
            ps.setBigDecimal(9, paid ? tendered : null);
            ps.setString(10, note);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Created c = new Created();
                c.saleId = rs.getLong("sale_id");
                c.receiptNo = rs.getString("receipt_no");
                c.total = rs.getBigDecimal("total_amount");
                c.alreadySaved = rs.getBoolean("already_existed");
                return c;
            }
        } catch (SQLException e) {
            throw Db.failure(e, "The sale was not saved");
        }
    }

    static void voidSale(long saleId, String reason) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select trimax_private.void_sale(?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setLong(2, saleId);
            ps.setString(3, reason);
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw Db.failure(e, "The sale was not voided");
        }
    }

    static void markPaid(long saleId, String method) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select trimax_private.set_sale_paid(?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setLong(2, saleId);
            ps.setString(3, method);
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw Db.failure(e, "The payment was not recorded");
        }
    }

    // ------------------------------------------------------------------------------------ reading

    static final class Sale {
        long id;
        String receiptNo, type, customerName, status, paymentStatus, paymentMethod, note, createdBy,
                voidedBy, voidReason, clientContact, clientAddress;
        Integer clientId;
        Timestamp soldAt, paidAt, voidedAt;
        BigDecimal subtotal, discount, total, tendered, change, cost;
        int itemCount, costMissingLines;
        final List<SaleLine> lines = new ArrayList<>();
    }

    static final class SaleLine {
        int lineNo, quantity;
        String name;
        BigDecimal unitPrice, unitCost, subtotal, discount, total;
    }

    private static final String SALE_COLUMNS = "s.sale_id, s.receipt_no, s.sale_type, s.customer_name, s.status, "
            + "s.payment_status, s.payment_method, s.note, s.created_by, s.voided_by, s.void_reason, s.client_id, "
            + "s.sold_at, s.paid_at, s.voided_at, s.subtotal_amount, s.discount_amount, s.total_amount, "
            + "s.amount_tendered, s.change_due, s.cost_amount, s.cost_missing_lines, "
            + "(select count(*) from sale_items i where i.sale_id = s.sale_id) as items, c.contact_num, c.address ";

    private static Sale read(ResultSet rs) throws SQLException {
        Sale s = new Sale();
        s.id = rs.getLong("sale_id");
        s.receiptNo = rs.getString("receipt_no");
        s.type = rs.getString("sale_type");
        s.customerName = rs.getString("customer_name");
        s.status = rs.getString("status");
        s.paymentStatus = rs.getString("payment_status");
        s.paymentMethod = rs.getString("payment_method");
        s.note = rs.getString("note");
        s.createdBy = rs.getString("created_by");
        s.voidedBy = rs.getString("voided_by");
        s.voidReason = rs.getString("void_reason");
        int cid = rs.getInt("client_id");
        s.clientId = rs.wasNull() ? null : cid;
        s.soldAt = rs.getTimestamp("sold_at");
        s.paidAt = rs.getTimestamp("paid_at");
        s.voidedAt = rs.getTimestamp("voided_at");
        s.subtotal = rs.getBigDecimal("subtotal_amount");
        s.discount = rs.getBigDecimal("discount_amount");
        s.total = rs.getBigDecimal("total_amount");
        s.tendered = rs.getBigDecimal("amount_tendered");
        s.change = rs.getBigDecimal("change_due");
        s.cost = rs.getBigDecimal("cost_amount");
        s.costMissingLines = rs.getInt("cost_missing_lines");
        s.itemCount = rs.getInt("items");
        s.clientContact = rs.getString("contact_num");
        s.clientAddress = rs.getString("address");
        return s;
    }

    static final class Page {
        final List<Sale> rows = new ArrayList<>();
        boolean truncated;
        int count;
        BigDecimal paidTotal = BigDecimal.ZERO, unpaidTotal = BigDecimal.ZERO;
    }

    /**
     * Sales sold in [from, to] (Asia/Manila days), newest first. type/status/payment: null for all.
     * search: receipt number or customer name.
     */
    static Page list(String search, String type, String status, String payment, LocalDate from, LocalDate to, int limit)
            throws Db.Failure {
        Page page = new Page();
        StringBuilder where = new StringBuilder("where s.sold_at >= (?::date)::timestamp at time zone 'Asia/Manila' "
                + "and s.sold_at < ((?::date) + 1)::timestamp at time zone 'Asia/Manila' ");
        List<Object> args = new ArrayList<>();
        args.add(from.toString());
        args.add(to.toString());
        if (search != null && !search.isBlank()) {
            where.append("and (s.receipt_no ilike ? or coalesce(s.customer_name, '') ilike ?) ");
            args.add("%" + search.trim() + "%");
            args.add("%" + search.trim() + "%");
        }
        if (type != null) {
            where.append("and s.sale_type = ? ");
            args.add(type);
        }
        if (status != null) {
            where.append("and s.status = ? ");
            args.add(status);
        }
        if (payment != null) {
            where.append("and s.payment_status = ? ");
            args.add(payment);
        }
        String sql = "select " + SALE_COLUMNS + "from sales s left join clients c on c.client_id = s.client_id "
                + where + "order by s.sold_at desc, s.sale_id desc limit ?";
        String totals = "select count(*), coalesce(sum(s.total_amount) filter (where s.status = 'COMPLETED' and s.payment_status = 'PAID'), 0), "
                + "coalesce(sum(s.total_amount) filter (where s.status = 'COMPLETED' and s.payment_status = 'UNPAID'), 0) "
                + "from sales s " + where;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             PreparedStatement pt = conn.prepareStatement(totals)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
                pt.setObject(i + 1, args.get(i));
            }
            ps.setInt(args.size() + 1, limit + 1);
            try (ResultSet rs = pt.executeQuery()) {
                rs.next();
                page.count = rs.getInt(1);
                page.paidTotal = rs.getBigDecimal(2);
                page.unpaidTotal = rs.getBigDecimal(3);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (page.rows.size() == limit) {
                        page.truncated = true;
                        break;
                    }
                    page.rows.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the sales");
        }
        return page;
    }

    static Sale detail(long saleId) throws Db.Failure {
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select " + SALE_COLUMNS
                     + "from sales s left join clients c on c.client_id = s.client_id where s.sale_id = ?");
             PreparedStatement pl = conn.prepareStatement("select line_no, product_name, quantity, unit_price, unit_cost, "
                     + "line_subtotal, line_discount, line_total from sale_items where sale_id = ? order by line_no")) {
            ps.setLong(1, saleId);
            Sale s;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new Db.Failure("Sale not found.");
                s = read(rs);
            }
            pl.setLong(1, saleId);
            try (ResultSet rs = pl.executeQuery()) {
                while (rs.next()) {
                    SaleLine l = new SaleLine();
                    l.lineNo = rs.getInt(1);
                    l.name = rs.getString(2);
                    l.quantity = rs.getInt(3);
                    l.unitPrice = rs.getBigDecimal(4);
                    l.unitCost = rs.getBigDecimal(5);
                    l.subtotal = rs.getBigDecimal(6);
                    l.discount = rs.getBigDecimal(7);
                    l.total = rs.getBigDecimal(8);
                    s.lines.add(l);
                }
            }
            return s;
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the sale");
        }
    }

    /** The printed receipt of a saved sale (both copies on one landscape A4). */
    static ReceiptPdfGenerator.ReceiptData receipt(Sale s) {
        ReceiptPdfGenerator.ReceiptData d = new ReceiptPdfGenerator.ReceiptData();
        d.title = "SALES RECEIPT";
        d.numberLabel = "Receipt No.";
        d.number = s.receiptNo;
        d.typeLabel = typeLabel(s.type);
        d.date = s.soldAt;
        d.clientId = s.clientId == null ? null : String.valueOf(s.clientId);
        d.clientName = s.customerName;
        d.contact = s.clientContact;
        d.address = s.clientAddress;
        d.showDelivery = false;
        d.items = new ArrayList<>();
        d.lineDiscounts = new ArrayList<>();
        for (SaleLine l : s.lines) {
            d.items.add(new BookForClientPanel.OrderItem("", l.name, l.unitPrice.doubleValue(), l.quantity, l.subtotal.doubleValue()));
            d.lineDiscounts.add(l.discount);
        }
        d.subtotal = s.subtotal;
        d.discount = s.discount;
        d.total = s.total;
        d.paymentStatus = paymentLabel(s.paymentStatus);
        d.paymentMethod = methodLabel(s.paymentMethod);
        d.amountPaid = s.tendered;
        d.change = s.change;
        d.note = s.note;
        if ("VOIDED".equals(s.status)) {
            d.voidedText = "VOIDED " + Theme.formatWhen(s.voidedAt) + " by " + s.voidedBy
                    + (s.voidReason == null ? "" : ": " + s.voidReason);
        }
        return d;
    }
}
