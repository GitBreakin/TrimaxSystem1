package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * The supplier names the Admin picks from in Inventory (sql/20261028_02_suppliers.sql). Before that update
 * is installed the list is made from the supplier names the products already have, and a new name is
 * simply saved with the product (as before).
 */
public final class SupplierService {

    private SupplierService() {
    }

    public static boolean installed(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT to_regclass('public.suppliers') IS NOT NULL AND to_regprocedure('trimax_private.save_supplier(text,text)') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /** Every supplier name (the list, plus any name a product uses), sorted, without duplicates. */
    public static List<String> list() throws SQLException {
        TreeMap<String, String> byKey = new TreeMap<>();
        try (Connection conn = DatabaseConnection.getConnection()) {
            boolean table = installed(conn);
            String sql = table
                    ? "SELECT name FROM suppliers UNION SELECT btrim(supplier) FROM products WHERE nullif(btrim(supplier), '') IS NOT NULL"
                    : "SELECT DISTINCT btrim(supplier) FROM products WHERE nullif(btrim(supplier), '') IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String n = rs.getString(1);
                    byKey.putIfAbsent(key(n), n);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Adds a supplier (Admins only) and returns its name as stored; an existing name (any capitals) is
     * returned instead of a duplicate. Returns null when the supplier list is not installed yet: the caller
     * then keeps the name for the product only.
     */
    public static String add(String name) throws SQLException, AuthService.AuthException {
        AuthService.Session me = AuthService.requireAdmin();
        String clean = tidy(name);
        String bad = check(clean);
        if (bad != null) throw new IllegalArgumentException(bad);
        try (Connection conn = DatabaseConnection.getConnection()) {
            if (!installed(conn)) return null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM trimax_private.save_supplier(?, ?)")) {
                ps.setString(1, me.username);
                ps.setString(2, clean);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : clean;
                }
            }
        }
    }

    /** Spaces at the ends removed and runs of spaces made one, like the database does. */
    public static String tidy(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    /** Why a name cannot be used, or null. */
    public static String check(String tidyName) {
        if (tidyName.length() < 2 || tidyName.length() > 120) return "Enter a supplier name of 2 to 120 characters.";
        return null;
    }

    /** Names that differ only in capitals are the same supplier. */
    public static String key(String name) {
        return tidy(name).toLowerCase(Locale.ROOT);
    }

    /** The name in the list that is the same supplier as {@code name}, or null. */
    public static String match(List<String> names, String name) {
        if (name == null) return null;
        String k = key(name);
        for (String n : names) if (key(n).equals(k)) return n;
        return null;
    }
}
