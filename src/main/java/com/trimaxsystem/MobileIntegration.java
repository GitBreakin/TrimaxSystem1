package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;

/**
 * The desktop side of the Android integration (sql/20261023_01_mobile_booking_integration.sql).
 *
 * Every screen asks {@link #isInstalled} first, so this build keeps working unchanged against a
 * database where that script has not been run yet: the extra columns and actions simply do not appear.
 * The check is cached for a minute so that running the script while the app is open is picked up soon.
 *
 * Linking a mobile sign-in is an administrator action. The database function it calls only links a
 * Supabase Auth user whose confirmed email equals the email on the TRIMAX account.
 */
public final class MobileIntegration {

    private static final long CACHE_MILLIS = 60_000;
    private static volatile Boolean installed;
    private static volatile long checkedAt;

    private MobileIntegration() {
    }

    /** True when the integration script has been applied to this database. */
    public static boolean isInstalled(Connection conn) throws SQLException {
        Boolean known = installed;
        if (known != null && System.currentTimeMillis() - checkedAt < CACHE_MILLIS) return known;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT to_regnamespace('trimax_private') IS NOT NULL AND EXISTS ("
                        + "SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'bookings' AND column_name = 'delivery_status')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            installed = rs.getBoolean(1);
            checkedAt = System.currentTimeMillis();
            return installed;
        }
    }

    /** Forget the cached answer (e.g. after an error caused by the schema changing). */
    public static void forget() {
        installed = null;
    }

    /** A message fit for the screen from a database error raised by the integration (SQLSTATE TMxxx), else null. */
    public static String friendlyMessage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException) {
                String state = ((SQLException) c).getSQLState();
                if (state != null && state.startsWith("TM")) {
                    String m = c.getMessage() == null ? "" : c.getMessage();
                    int nl = m.indexOf('\n');
                    if (nl >= 0) m = m.substring(0, nl);
                    return m.startsWith("ERROR: ") ? m.substring(7).trim() : m.trim();
                }
            }
        }
        return null;
    }

    // =====================================================
    // MOBILE SIGN-IN LINKS
    // =====================================================

    /** Mobile sign-in state of one TRIMAX account. */
    public static final class Link {
        public boolean installed;      // false: the integration script has not been run
        public boolean linked;
        public String email;           // the Supabase Auth email it is linked to
        public Timestamp linkedAt;
        public String linkedBy;
    }

    public static Link loadLink(Connection conn, String username) throws SQLException {
        Link l = new Link();
        l.installed = isInstalled(conn);
        if (!l.installed) return l;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT a.email, m.linked_at, m.linked_by FROM trimax_private.mobile_accounts m "
                        + "JOIN auth.users a ON a.id = m.auth_user_id WHERE m.username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    l.linked = true;
                    l.email = rs.getString(1);
                    l.linkedAt = rs.getTimestamp(2);
                    l.linkedBy = rs.getString(3);
                }
            }
        }
        return l;
    }

    /** Usernames that can sign in to the mobile app (empty when the integration is not installed). */
    public static Set<String> linkedUsernames(Connection conn) throws SQLException {
        Set<String> names = new HashSet<>();
        if (!isInstalled(conn)) return names;
        try (PreparedStatement ps = conn.prepareStatement("SELECT username FROM trimax_private.mobile_accounts");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    /**
     * Links the account to the Supabase Auth user with the account's own email. The administrator must
     * first create or invite that user in Supabase (Authentication > Users) with the same email.
     */
    public static void link(String username, String email) throws SQLException, AuthService.AuthException {
        AuthService.Session me = AuthService.requireAdmin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT trimax_private.link_mobile_account(?, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, me.username);
            ps.executeQuery().close();
        }
    }

    /** Removes the account's mobile sign-in link. Returns false if it was not linked. */
    public static boolean unlink(String username) throws SQLException, AuthService.AuthException {
        AuthService.Session me = AuthService.requireAdmin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT trimax_private.unlink_mobile_account(?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, me.username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
