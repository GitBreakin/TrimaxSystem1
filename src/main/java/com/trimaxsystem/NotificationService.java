package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads admin notifications from admin_notifications, marks them read (acknowledged) and resolves alerts.
 * Alerts are SOS / problem reports sent from the mobile app (trimax_report_issue); this app does not create
 * notifications and cannot send announcements to anyone, so no delivery is ever claimed.
 *
 * Resolving needs the database update sql/20261025_07_alert_resolution.sql. Without it the page still
 * works (read / mark read) and says that resolving is not available yet. A missing table is reported as
 * "not set up".
 */
public final class NotificationService {

    private static final String TABLE_MISSING = "42P01";
    private static final int LIMIT = 200;
    public static final int MAX_NOTE = 1000;

    private NotificationService() {
    }

    /** What the list shows. */
    public enum Filter { ALL, UNREAD, OPEN_ALERTS }

    public static final class Item {
        public long id;
        public Timestamp createdAt, readAt, resolvedAt;
        public String kind, category, title, body, relatedType, relatedId, readBy, reportedBy, resolvedBy, resolutionNote;

        public boolean unread() {
            return readAt == null;
        }

        public boolean isAlert() {
            return "Alert".equals(kind);
        }

        public boolean resolved() {
            return resolvedAt != null;
        }

        public String related() {
            return relatedType == null || relatedId == null ? "" : relatedType + " " + relatedId;
        }

        /** Short state for the list: Unread / Acknowledged / Resolved (alerts), Unread / Read (announcements). */
        public String stateText() {
            if (resolved()) return "Resolved";
            if (unread()) return "UNREAD";
            return isAlert() ? "Acknowledged" : "Read";
        }
    }

    public static final class Result {
        public final List<Item> items = new ArrayList<>();
        public boolean tableMissing;
        public int unread;
        public boolean truncated;
        /** False until sql/20261025_07 is applied: alerts can only be marked read. */
        public boolean resolutionAvailable;
        /** Alerts not resolved yet (all time); -1 when resolving is not available. */
        public int openAlerts = -1;
    }

    public static Result load(boolean unreadOnly) throws SQLException, AuthService.AuthException {
        return load(unreadOnly ? Filter.UNREAD : Filter.ALL);
    }

    public static Result load(Filter filter) throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        Result res = new Result();
        try (Connection conn = DatabaseConnection.getConnection()) {
            Set<String> cols = columns(conn);
            if (cols.isEmpty()) {
                res.tableMissing = true;
                return res;
            }
            boolean hasReporter = cols.contains("reported_by");
            res.resolutionAvailable = cols.contains("resolved_at") && cols.contains("resolved_by")
                    && cols.contains("resolution_note") && resolveFunctionExists(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM admin_notifications WHERE read_at IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                res.unread = rs.getInt(1);
            }
            if (res.resolutionAvailable) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM admin_notifications WHERE kind = 'Alert' AND resolved_at IS NULL");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    res.openAlerts = rs.getInt(1);
                }
            }
            String where;
            switch (filter) {
                case UNREAD:
                    where = "WHERE read_at IS NULL ";
                    break;
                case OPEN_ALERTS:
                    // without the update every alert is still open: show all alerts, unresolved by definition
                    where = res.resolutionAvailable ? "WHERE kind = 'Alert' AND resolved_at IS NULL " : "WHERE kind = 'Alert' ";
                    break;
                default:
                    where = "";
            }
            String sql = "SELECT notification_id, created_at, kind, category, title, body, related_type, related_id, "
                    + "read_at, read_by, "
                    + (hasReporter ? "reported_by, " : "NULL::text, ")
                    + (res.resolutionAvailable ? "resolved_at, resolved_by, resolution_note " : "NULL::timestamptz, NULL::text, NULL::text ")
                    + "FROM admin_notifications " + where
                    + "ORDER BY created_at DESC, notification_id DESC LIMIT " + (LIMIT + 1);
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (res.items.size() == LIMIT) {
                        res.truncated = true;
                        break;
                    }
                    Item it = new Item();
                    it.id = rs.getLong(1);
                    it.createdAt = rs.getTimestamp(2);
                    it.kind = rs.getString(3);
                    it.category = rs.getString(4);
                    it.title = rs.getString(5);
                    it.body = rs.getString(6);
                    it.relatedType = rs.getString(7);
                    it.relatedId = rs.getString(8);
                    it.readAt = rs.getTimestamp(9);
                    it.readBy = rs.getString(10);
                    it.reportedBy = rs.getString(11);
                    it.resolvedAt = rs.getTimestamp(12);
                    it.resolvedBy = rs.getString(13);
                    it.resolutionNote = rs.getString(14);
                    res.items.add(it);
                }
            }
        } catch (SQLException e) {
            if (TABLE_MISSING.equals(e.getSQLState())) {
                res.tableMissing = true;
                return res;
            }
            throw e;
        }
        return res;
    }

    private static Set<String> columns(Connection conn) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'admin_notifications'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static boolean resolveFunctionExists(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT to_regprocedure('trimax_private.resolve_alert(text,bigint,text)') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    /** Unread count for the header bell: -1 means "unknown" (not set up or could not be read), never 0. */
    public static int unreadCount() {
        try {
            AuthService.requireAdmin();
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM admin_notifications WHERE read_at IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** Marks one notification read (or all unread ones when id is null). Returns how many rows changed. */
    public static int markRead(Long id) throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        AuthService.Session s = AuthService.current();
        String sql = "UPDATE admin_notifications SET read_at = now(), read_by = ? WHERE read_at IS NULL"
                + (id == null ? "" : " AND notification_id = ?");
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, s.username);
            if (id != null) ps.setLong(2, id);
            int n = ps.executeUpdate();
            AuditService.record(conn, id == null ? "notification.mark_all_read" : "notification.mark_read",
                    "notification", id == null ? "all" : String.valueOf(id), "success", n + " changed");
            return n;
        }
    }

    /** Problem with a resolve request that the admin can fix or should know (already resolved, no note...). */
    public static final class ResolveException extends Exception {
        public ResolveException(String message) {
            super(message);
        }
    }

    /** The note as it will be stored, or the reason it cannot be (null = fine). */
    public static String checkNote(String note) {
        String n = note == null ? "" : note.trim();
        if (n.isEmpty()) return "Write what was done before resolving the alert.";
        if (n.length() > MAX_NOTE) return "The note is too long (" + n.length() + " of " + MAX_NOTE + " characters).";
        return null;
    }

    /**
     * Resolves one alert with a note (the server checks the admin, that it is an alert and still open, and
     * writes the audit log; it also acknowledges the alert if nobody had).
     */
    public static void resolve(long id, String note) throws SQLException, AuthService.AuthException, ResolveException {
        AuthService.requireAdmin();
        String bad = checkNote(note);
        if (bad != null) throw new ResolveException(bad);
        AuthService.Session s = AuthService.current();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT trimax_private.resolve_alert(?, ?, ?)")) {
            ps.setString(1, s.username);
            ps.setLong(2, id);
            ps.setString(3, note.trim());
            ps.executeQuery().close();
        } catch (SQLException e) {
            String friendly = MobileIntegration.friendlyMessage(e);
            if (friendly != null) throw new ResolveException(friendly);
            if ("42883".equals(e.getSQLState()) || "3F000".equals(e.getSQLState())) {
                throw new ResolveException("Resolving alerts is not set up on this database yet "
                        + "(sql/20261025_07_alert_resolution.sql).");
            }
            throw e;
        }
    }
}
