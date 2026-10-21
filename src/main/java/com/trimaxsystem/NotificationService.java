package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads admin notifications from admin_notifications (see sql/004_admin_notifications.sql) and marks them read.
 * This app does not create notifications and cannot send announcements to anyone: the database has nothing
 * that delivers them, so no delivery is ever claimed. A missing table is reported as "not set up".
 */
public final class NotificationService {

    private static final String TABLE_MISSING = "42P01";
    private static final int LIMIT = 200;

    private NotificationService() {
    }

    public static final class Item {
        public long id;
        public Timestamp createdAt, readAt;
        public String kind, category, title, body, relatedType, relatedId, readBy;

        public boolean unread() {
            return readAt == null;
        }

        public String related() {
            return relatedType == null || relatedId == null ? "" : relatedType + " " + relatedId;
        }
    }

    public static final class Result {
        public final List<Item> items = new ArrayList<>();
        public boolean tableMissing;
        public int unread;
        public boolean truncated;
    }

    public static Result load(boolean unreadOnly) throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        Result res = new Result();
        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM admin_notifications WHERE read_at IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                res.unread = rs.getInt(1);
            }
            String sql = "SELECT notification_id, created_at, kind, category, title, body, related_type, related_id, "
                    + "read_at, read_by FROM admin_notifications "
                    + (unreadOnly ? "WHERE read_at IS NULL " : "")
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

    /** Unread count for the header bell: -1 means "unknown" (not set up or could not be read), never 0. */
    public static int unreadCount() {
        try {
            Result r = load(true);
            return r.tableMissing ? -1 : r.unread;
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
}
