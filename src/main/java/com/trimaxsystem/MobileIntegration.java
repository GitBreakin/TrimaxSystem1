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

    // =====================================================
    // MOBILE INVITATIONS (provisioning queue, sql/20261024_01) AND DUTY STATUS
    // =====================================================

    /** True when the provisioning queue (sql/20261024_01_hub_gps_duty_provisioning.sql) is installed. */
    public static boolean provisioningInstalled(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT to_regprocedure('trimax_private.request_mobile_access(text,text)') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /** Latest mobile invitation request of one account. */
    public static final class Invite {
        public boolean installed;
        public String status;          // pending, processing, invited, linked, failed; null when never requested
        public String lastError;
        public Timestamp updatedAt;
        public String requestedBy;

        /** One line for the account window. */
        public String describe() {
            if (!installed) return "Automatic invitations are not set up (run sql/20261024_01).";
            if (status == null) return "No invitation sent";
            switch (status) {
                case "pending":
                case "processing": return "Invitation queued - waiting for the server";
                case "invited":    return "Invitation email sent - waiting for the person to set a password";
                case "linked":     return "Linked to an existing TRIMAX sign-in (password email sent)";
                case "failed":     return "Invitation failed: " + (lastError == null ? "unknown reason" : lastError);
                default:           return status;
            }
        }
    }

    public static Invite loadInvite(Connection conn, String username) throws SQLException {
        Invite i = new Invite();
        i.installed = provisioningInstalled(conn);
        if (!i.installed) return i;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status, last_error, updated_at, requested_by FROM trimax_private.provisioning_requests "
                        + "WHERE username = ? ORDER BY request_id DESC LIMIT 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    i.status = rs.getString(1);
                    i.lastError = rs.getString(2);
                    i.updatedAt = rs.getTimestamp(3);
                    i.requestedBy = rs.getString(4);
                }
            }
        }
        return i;
    }

    /**
     * Queues a mobile sign-in invitation for an Agent/Driver. The Supabase Edge Function creates the Auth
     * user, emails the invitation and links it; nothing secret is needed here. Returns the request id.
     */
    public static long requestInvite(String username) throws SQLException, AuthService.AuthException {
        AuthService.Session me = AuthService.requireAdmin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT trimax_private.request_mobile_access(?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, me.username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * After creating an employee account: queue the invitation if the queue is installed. Never throws;
     * returns a sentence for the confirmation message.
     */
    public static String requestInviteQuietly(String username) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            if (!provisioningInstalled(conn)) {
                return "Mobile app access: set it up from the account window (Link mobile sign-in).";
            }
        } catch (SQLException e) {
            return "Mobile app access could not be checked; use the account window to send the invitation.";
        }
        try {
            requestInvite(username);
            return "A mobile app invitation has been queued; the person receives an email to set their password.";
        } catch (SQLException e) {
            String rule = friendlyMessage(e);
            return "Mobile app invitation not sent: " + (rule != null ? rule : "database error") + ".";
        } catch (AuthService.AuthException e) {
            return "Mobile app invitation not sent: " + e.getMessage();
        }
    }

    /** Duty state recorded by the phone (sql/20261024_01), or null when that is not installed. */
    public static String dutySummary(Connection conn, String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT to_regclass('trimax_private.duty_events') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || !rs.getBoolean(1)) return null;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT event_type, recorded_at FROM trimax_private.duty_events WHERE username = ? "
                        + "ORDER BY recorded_at DESC, event_id DESC LIMIT 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "No duty recorded from the mobile app";
                String when = new java.text.SimpleDateFormat("MMM d, yyyy h:mm a").format(rs.getTimestamp(2));
                return "START".equals(rs.getString(1)) ? "On duty since " + when + " (photo on file)"
                        : "Off duty since " + when + " (photo on file)";
            }
        }
    }
}
