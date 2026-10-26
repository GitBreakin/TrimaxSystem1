package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Duty attendance from the mobile app: every Start duty / End duty an agent or driver records, with the
 * server's time, the photo they took and where they were (trimax_private.duty_events, written only by
 * the app through trimax_record_duty_event). Read-only here.
 */
public final class AttendanceService {

    private static final int LIMIT = 500;

    private AttendanceService() {
    }

    public static final class Event {
        public long id;
        public String username, name, role, type, photoPath;
        public Timestamp at;
        public Double latitude, longitude;
        /** For an END: seconds since that person's previous START (null if there was none). */
        public Long shiftSeconds;

        public boolean isStart() {
            return "START".equals(type);
        }

        public boolean hasGps() {
            return latitude != null && longitude != null;
        }

        public String displayName() {
            return name == null || name.isBlank() ? username : name;
        }
    }

    public static final class OnDuty {
        public String username, name, role;
        public Timestamp since;
    }

    public static final class Result {
        public final List<Event> events = new ArrayList<>();
        public final List<OnDuty> onDutyNow = new ArrayList<>();
        public boolean notSetUp;
        public boolean truncated;
    }

    /**
     * @param from   only events at or after this instant (null = no lower bound)
     * @param role   "Agent", "Driver" or null for both
     * @param search part of a name or username (case-insensitive), or null
     */
    public static Result load(Instant from, String role, String search) throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        Result res = new Result();
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        try (Connection conn = DatabaseConnection.getConnection()) {
            String sql = "SELECT e.event_id, e.username, u.name, r.role_name, e.event_type, e.recorded_at, "
                    + "       e.latitude, e.longitude, e.photo_path, "
                    + "       CASE WHEN e.event_type = 'END' THEN extract(epoch FROM e.recorded_at - ("
                    + "           SELECT s.recorded_at FROM trimax_private.duty_events s "
                    + "            WHERE s.username = e.username AND s.event_type = 'START' AND s.recorded_at <= e.recorded_at "
                    + "            ORDER BY s.recorded_at DESC, s.event_id DESC LIMIT 1))::bigint END AS shift_seconds "
                    + "  FROM trimax_private.duty_events e "
                    + "  LEFT JOIN users u ON u.username = e.username "
                    + "  LEFT JOIN roles r ON r.role_id = u.role_id "
                    + " WHERE (CAST(? AS timestamptz) IS NULL OR e.recorded_at >= ?) "
                    + "   AND (CAST(? AS text) IS NULL OR r.role_name = ?) "
                    + "   AND (? = '' OR lower(e.username) LIKE ? OR lower(coalesce(u.name, '')) LIKE ?) "
                    + " ORDER BY e.recorded_at DESC, e.event_id DESC LIMIT " + (LIMIT + 1);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                Timestamp fromTs = from == null ? null : Timestamp.from(from);
                ps.setTimestamp(1, fromTs);
                ps.setTimestamp(2, fromTs);
                ps.setString(3, role);
                ps.setString(4, role);
                String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                ps.setString(5, q);
                ps.setString(6, like);
                ps.setString(7, like);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (res.events.size() == LIMIT) {
                            res.truncated = true;
                            break;
                        }
                        Event ev = new Event();
                        ev.id = rs.getLong(1);
                        ev.username = rs.getString(2);
                        ev.name = rs.getString(3);
                        ev.role = rs.getString(4);
                        ev.type = rs.getString(5);
                        ev.at = rs.getTimestamp(6);
                        ev.latitude = (Double) rs.getObject(7);
                        ev.longitude = (Double) rs.getObject(8);
                        ev.photoPath = rs.getString(9);
                        long secs = rs.getLong(10);
                        ev.shiftSeconds = rs.wasNull() ? null : secs;
                        res.events.add(ev);
                    }
                }
            }
            // Who is on duty right now: people whose latest event is a START.
            String now = "SELECT x.username, u.name, r.role_name, x.recorded_at FROM ("
                    + "  SELECT DISTINCT ON (e.username) e.username, e.event_type, e.recorded_at "
                    + "    FROM trimax_private.duty_events e ORDER BY e.username, e.recorded_at DESC, e.event_id DESC) x "
                    + "  LEFT JOIN users u ON u.username = x.username "
                    + "  LEFT JOIN roles r ON r.role_id = u.role_id "
                    + " WHERE x.event_type = 'START' ORDER BY x.recorded_at";
            try (PreparedStatement ps = conn.prepareStatement(now); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OnDuty d = new OnDuty();
                    d.username = rs.getString(1);
                    d.name = rs.getString(2);
                    d.role = rs.getString(3);
                    d.since = rs.getTimestamp(4);
                    res.onDutyNow.add(d);
                }
            }
        } catch (SQLException e) {
            // 42P01: no such table, 3F000: no such schema (mobile duty not installed on this database)
            if ("42P01".equals(e.getSQLState()) || "3F000".equals(e.getSQLState())) {
                res.notSetUp = true;
                return res;
            }
            throw e;
        }
        return res;
    }

    /** "8 h 05 min", "12 min", "under a minute". */
    public static String formatShift(Long seconds) {
        if (seconds == null) return "";
        if (seconds < 60) return "under a minute";
        long minutes = seconds / 60;
        long h = minutes / 60, m = minutes % 60;
        return h == 0 ? m + " min" : h + " h " + String.format(Locale.ROOT, "%02d", m) + " min";
    }
}
