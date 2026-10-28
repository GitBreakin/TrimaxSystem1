package com.trimaxsystem;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Products, restocking, stock corrections and the stock history for the Inventory page (bad orders:
 * {@link BadOrderService}).
 * Saving goes through the database functions of 20261027_01 (Admin checked there, retry-safe);
 * reading works on older databases too (pricing columns are then simply absent).
 */
final class InventoryService {

    private InventoryService() {
    }

    static final class Product {
        int id;
        String name, supplier, imagePath, markupType;
        BigDecimal price, cost, markupValue, minMarkup;
        int stock;
        /** Agents may discount this item (20261027_04); null when the server does not have the setting. */
        Boolean agentDiscount;

        /** "₱ 12.00" or "25%" or "" when there is no patong. */
        String markupText() {
            if (markupType == null || markupValue == null) return "";
            return "PERCENT".equals(markupType) ? Money.plain(markupValue).replaceAll("\\.00$", "") + "%"
                    : Money.peso(markupValue);
        }
    }

    static final class Catalog {
        final List<Product> products = new ArrayList<>();
        boolean pricing;    // the products table has puhunan / patong (20261027_01)
        boolean pictures;   // the products table has image_path (20261025_06)
        boolean itemDiscounts;   // the products table has agent_discount_allowed (20261027_04)
    }

    /** Products matching the keyword (id, name or supplier), by id. */
    static Catalog list(String keyword) throws SQLException {
        Catalog c = new Catalog();
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM products "
                     + "WHERE product_id::text ILIKE ? OR product_name ILIKE ? OR coalesce(supplier, '') ILIKE ? "
                     + "ORDER BY product_id")) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            try (ResultSet rs = ps.executeQuery()) {
                c.pricing = has(rs, "capital_cost") && has(rs, "min_markup");
                c.pictures = has(rs, "image_path");
                c.itemDiscounts = has(rs, "agent_discount_allowed");
                while (rs.next()) {
                    Product p = new Product();
                    p.id = rs.getInt("product_id");
                    p.name = rs.getString("product_name");
                    p.supplier = rs.getString("supplier");
                    p.price = rs.getBigDecimal("price");
                    p.stock = rs.getInt("stock");
                    if (c.pictures) p.imagePath = rs.getString("image_path");
                    if (c.pricing) {
                        p.cost = rs.getBigDecimal("capital_cost");
                        p.markupType = rs.getString("markup_type");
                        p.markupValue = rs.getBigDecimal("markup_value");
                        p.minMarkup = rs.getBigDecimal("min_markup");
                    }
                    if (c.itemDiscounts) p.agentDiscount = rs.getBoolean("agent_discount_allowed");
                    c.products.add(p);
                }
            }
        }
        return c;
    }

    private static boolean has(ResultSet rs, String column) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            if (column.equalsIgnoreCase(md.getColumnName(i))) return true;
        }
        return false;
    }

    /**
     * Adds (id null) or edits a product. With a puhunan the patong is required and the database
     * calculates the selling price; without one the typed price is kept. Editing never changes stock.
     * agentDiscount: whether agents may discount the item (null = leave it, or a server without 20261027_04).
     * @return the product id
     */
    static int save(Integer id, String name, String supplier, BigDecimal cost, String markupType,
                    BigDecimal markupValue, BigDecimal minMarkup, BigDecimal price, Integer startingStock,
                    Boolean agentDiscount)
            throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(agentDiscount == null
                     ? "select trimax_private.save_product(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                     : "select trimax_private.save_product(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, admin);
            if (id == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, id);
            ps.setString(3, name);
            ps.setString(4, supplier);
            ps.setBigDecimal(5, cost);
            ps.setString(6, cost == null ? null : markupType);
            ps.setBigDecimal(7, cost == null ? null : markupValue);
            ps.setBigDecimal(8, minMarkup == null ? BigDecimal.ZERO : minMarkup);
            ps.setBigDecimal(9, price);
            if (startingStock == null) ps.setNull(10, Types.INTEGER); else ps.setInt(10, startingStock);
            if (agentDiscount != null) ps.setBoolean(11, agentDiscount);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not save the product");
        }
    }

    static final class RestockResult {
        long movementId;
        int stockBefore, stockAfter;
        BigDecimal totalCost, price;
        boolean alreadySaved;
    }

    /** Adds stock and records the purchase. The same requestId never adds twice. */
    static RestockResult restock(int productId, int quantity, BigDecimal unitCost, String note,
                                 boolean useAsNewCost, UUID requestId) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select * from trimax_private.restock_product(?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setInt(2, productId);
            ps.setInt(3, quantity);
            ps.setBigDecimal(4, unitCost);
            ps.setString(5, note);
            ps.setBoolean(6, useAsNewCost);
            ps.setObject(7, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                RestockResult r = new RestockResult();
                r.movementId = rs.getLong("movement_id");
                r.stockBefore = rs.getInt("stock_before");
                r.stockAfter = rs.getInt("stock_after");
                r.totalCost = rs.getBigDecimal("total_cost");
                r.price = rs.getBigDecimal("price");
                r.alreadySaved = rs.getBoolean("already_existed");
                return r;
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not restock");
        }
    }

    /** Stock correction (+ or -) with a reason; never below 0. Returns {before, after}. */
    static int[] adjust(int productId, int change, String reason, UUID requestId) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select stock_before, stock_after from trimax_private.adjust_stock(?, ?, ?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setInt(2, productId);
            ps.setInt(3, change);
            ps.setString(4, reason);
            ps.setObject(5, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new int[]{rs.getInt(1), rs.getInt(2)};
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not correct the stock");
        }
    }

    // ------------------------------------------------------------------------------------ history

    /** Movement kinds with the words the screens use. */
    static String kindLabel(String kind) {
        if (kind == null) return "";
        switch (kind) {
            case "INITIAL": return "Starting stock";
            case "RESTOCK": return "Restock";
            case "ADJUSTMENT": return "Correction";
            case "BOOKING": return "Booking";
            case "BOOKING_CANCELLED": return "Booking cancelled";
            case "BOOKING_CHANGED": return "Booking changed";
            case "SALE": return "Sale";
            case "SALE_VOIDED": return "Sale voided";
            case "BAD_ORDER": return "Bad order";
            case "BAD_ORDER_VOIDED": return "Bad order undone";
            default: return kind;
        }
    }

    static final class Movement {
        long id;
        Timestamp at;
        Integer productId;
        String productName, kind, referenceType, referenceId, actor, note;
        int change, before, after;
        BigDecimal unitCost, totalCost;
    }

    static final class History {
        final List<Movement> rows = new ArrayList<>();
        boolean truncated;
        int restockUnits;
        BigDecimal restockCost = BigDecimal.ZERO;
        int unitsIn, unitsOut;
        /** Bad orders (BAD_ORDER) in the range: pieces out and their puhunan (undone ones come back as "in"). */
        int badOrderUnits;
        BigDecimal badOrderCost = BigDecimal.ZERO;
    }

    /**
     * Stock history in a date range (Asia/Manila days, both included), newest first.
     * kind: null for all, or one of the movement kinds.
     */
    static History history(Integer productId, String kind, LocalDate from, LocalDate to, int limit) throws Db.Failure {
        History h = new History();
        String where = "from inventory_movements "
                + "where occurred_at >= (?::date)::timestamp at time zone 'Asia/Manila' "
                + "and occurred_at < ((?::date) + 1)::timestamp at time zone 'Asia/Manila' "
                + (productId != null ? "and product_id = ? " : "")
                + (kind != null ? "and kind = ? " : "");
        String sql = "select movement_id, occurred_at, product_id, product_name, kind, quantity_change, stock_before, "
                + "stock_after, unit_cost, total_cost, reference_type, reference_id, actor_username, note "
                + where + "order by occurred_at desc, movement_id desc limit ?";
        String totals = "select coalesce(sum(quantity_change) filter (where kind = 'RESTOCK'), 0), "
                + "coalesce(sum(total_cost) filter (where kind = 'RESTOCK'), 0), "
                + "coalesce(sum(quantity_change) filter (where quantity_change > 0), 0), "
                + "coalesce(-sum(quantity_change) filter (where quantity_change < 0), 0), "
                + "coalesce(-sum(quantity_change) filter (where kind = 'BAD_ORDER'), 0), "
                + "coalesce(sum(total_cost) filter (where kind = 'BAD_ORDER'), 0) " + where;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             PreparedStatement pt = conn.prepareStatement(totals)) {
            int i = 1;
            for (PreparedStatement p : new PreparedStatement[]{ps, pt}) {
                i = 1;
                p.setString(i++, from.toString());
                p.setString(i++, to.toString());
                if (productId != null) p.setInt(i++, productId);
                if (kind != null) p.setString(i++, kind);
            }
            ps.setInt(i, limit + 1);
            try (ResultSet rs = pt.executeQuery()) {
                rs.next();
                h.restockUnits = rs.getInt(1);
                h.restockCost = rs.getBigDecimal(2);
                h.unitsIn = rs.getInt(3);
                h.unitsOut = rs.getInt(4);
                h.badOrderUnits = rs.getInt(5);
                h.badOrderCost = rs.getBigDecimal(6);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (h.rows.size() == limit) {
                        h.truncated = true;
                        break;
                    }
                    Movement m = new Movement();
                    m.id = rs.getLong(1);
                    m.at = rs.getTimestamp(2);
                    int pid = rs.getInt(3);
                    m.productId = rs.wasNull() ? null : pid;
                    m.productName = rs.getString(4);
                    m.kind = rs.getString(5);
                    m.change = rs.getInt(6);
                    m.before = rs.getInt(7);
                    m.after = rs.getInt(8);
                    m.unitCost = rs.getBigDecimal(9);
                    m.totalCost = rs.getBigDecimal(10);
                    m.referenceType = rs.getString(11);
                    m.referenceId = rs.getString(12);
                    m.actor = rs.getString(13);
                    m.note = rs.getString(14);
                    h.rows.add(m);
                }
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the stock history");
        }
        return h;
    }

    /** "Booking #42", "Receipt TRX-...", "" ... for the Reference column. */
    static String referenceText(Movement m) {
        if (m.referenceId == null || m.referenceId.isEmpty()) return "";
        if ("booking".equals(m.referenceType)) return "Booking #" + m.referenceId;
        if ("sale".equals(m.referenceType)) return "Receipt " + m.referenceId;
        if ("bad_order".equals(m.referenceType)) return "Bad order #" + m.referenceId;
        return m.referenceId;
    }
}
