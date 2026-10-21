package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads the audit_log table (see sql/002_audit_log.sql). The application only ever
 * inserts and selects; the table itself blocks updates and deletes. If the table has not been
 * created yet, nothing is recorded and callers are told so, rather than pretending it was logged.
 * Never pass passwords, tokens or other secrets in details.
 */
public final class AuditService {

    private static final String TABLE_MISSING = "42P01";   // PostgreSQL: undefined_table

    private AuditService() {
    }

    public static final class Entry {
        public Timestamp when;
        public String actor, action, result, details;
    }

    /**
     * Adds one audit row using the caller's connection (so it commits or rolls back together with the
     * change it describes). Returns true if it was recorded, false if the audit table does not exist yet.
     * Any other database error is thrown, which rolls the whole change back.
     */
    public static boolean record(Connection conn, String action, String targetType, String targetId,
                                 String result, String details) throws SQLException {
        AuthService.Session s = AuthService.current();
        String actor = s == null ? "unknown" : s.username;
        // A failed statement aborts a PostgreSQL transaction, so guard it with a savepoint.
        Savepoint sp = conn.getAutoCommit() ? null : conn.setSavepoint();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit_log (actor_username, action, target_type, target_id, result, details) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, actor);
            ps.setString(2, action);
            ps.setString(3, targetType);
            ps.setString(4, targetId);
            ps.setString(5, result);
            ps.setString(6, details);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (TABLE_MISSING.equals(e.getSQLState())) {
                if (sp != null) conn.rollback(sp);
                return false;
            }
            throw e;
        }
    }

    /** Newest-first history of one record, or null if the audit table does not exist yet. */
    public static List<Entry> load(Connection conn, String targetType, String targetId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT occurred_at, actor_username, action, result, details FROM audit_log "
                        + "WHERE target_type = ? AND target_id = ? ORDER BY occurred_at DESC, audit_id DESC LIMIT 100")) {
            ps.setString(1, targetType);
            ps.setString(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Entry> list = new ArrayList<>();
                while (rs.next()) {
                    Entry e = new Entry();
                    e.when = rs.getTimestamp(1);
                    e.actor = rs.getString(2);
                    e.action = rs.getString(3);
                    e.result = rs.getString(4);
                    e.details = rs.getString(5);
                    list.add(e);
                }
                return list;
            }
        } catch (SQLException e) {
            if (TABLE_MISSING.equals(e.getSQLState())) return null;
            throw e;
        }
    }

    /** One page of search results plus the total number of matches. */
    public static final class Page {
        public final List<Entry> rows = new ArrayList<>();
        public int total;
        public boolean tableMissing;
    }

    /**
     * Searches the audit log (newest first). Filters are optional (null/blank = ignore). Dates are Asia/Manila
     * days, both included. The search text matches actor, action, target id and details (case-insensitive).
     * result is "success", "failure" or null for both. Admin role is re-checked here.
     */
    public static Page search(String text, String result, java.time.LocalDate from, java.time.LocalDate to,
                              int offset, int limit) throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            where.append(" AND (actor_username ILIKE ? OR action ILIKE ? OR target_id ILIKE ? OR details ILIKE ?)");
            String like = "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 4; i++) args.add(like);
        }
        if (result != null && !result.isBlank()) {
            where.append(" AND result = ?");
            args.add(result);
        }
        if (from != null) {
            where.append(" AND (occurred_at AT TIME ZONE 'Asia/Manila')::date >= ?");
            args.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            where.append(" AND (occurred_at AT TIME ZONE 'Asia/Manila')::date <= ?");
            args.add(java.sql.Date.valueOf(to));
        }
        Page page = new Page();
        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM audit_log" + where)) {
                bind(ps, args, 0);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    page.total = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT occurred_at, actor_username, action, result, details FROM audit_log" + where
                            + " ORDER BY occurred_at DESC, audit_id DESC LIMIT ? OFFSET ?")) {
                bind(ps, args, 0);
                ps.setInt(args.size() + 1, limit);
                ps.setInt(args.size() + 2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Entry e = new Entry();
                        e.when = rs.getTimestamp(1);
                        e.actor = rs.getString(2);
                        e.action = rs.getString(3);
                        e.result = rs.getString(4);
                        e.details = rs.getString(5);
                        page.rows.add(e);
                    }
                }
            }
        } catch (SQLException e) {
            if (TABLE_MISSING.equals(e.getSQLState())) {
                page.tableMissing = true;
                return page;
            }
            throw e;
        }
        return page;
    }

    private static void bind(PreparedStatement ps, List<Object> args, int shift) throws SQLException {
        for (int i = 0; i < args.size(); i++) ps.setObject(i + 1 + shift, args.get(i));
    }
}
