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
 * server's time, the photo they took and where they were (trimax_private.duty_events, written by the app through
 * trimax_record_duty_event). Since sql/20261030_03 the Admin can also record a Time in / Time out for them
 * (trimax_private.admin_duty_event): no photo, with who recorded it and why.
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
        /** The Admin who recorded it on the Desktop (20261030_03), their name, and the note; null = the person's phone. */
        public String recordedBy, recordedByName, note;

        public boolean byAdmin() {
            return recordedBy != null;
        }

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
                    + "            ORDER BY s.recorded_at DESC, s.event_id DESC LIMIT 1))::bigint END AS shift_seconds, "
                    // who recorded it and why (20261030_03), through to_jsonb so older servers work too
                    + "       to_jsonb(e) ->> 'recorded_by', "
                    + "       (SELECT coalesce(nullif(btrim(a.name), ''), a.username) FROM users a WHERE a.username = to_jsonb(e) ->> 'recorded_by'), "
                    + "       to_jsonb(e) ->> 'note' "
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
                        ev.recordedBy = rs.getString(11);
                        ev.recordedByName = rs.getString(12);
                        ev.note = rs.getString(13);
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

    /** An agent or driver for the Admin's Time in / Time out, with their last duty record. */
    public static final class Person {
        public String username, name, role, lastType;
        public Timestamp lastAt;

        public boolean onDuty() {
            return "START".equals(lastType);
        }

        @Override
        public String toString() {
            return name + "  (" + role + ") — " + (onDuty() ? "on duty since " + Theme.formatWhen(lastAt) : "off duty");
        }
    }

    /** Active agents and drivers, with whether they are on duty now (their last record is a Start). */
    public static List<Person> people() throws Db.Failure {
        List<Person> out = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT u.username, coalesce(nullif(btrim(u.name), ''), u.username), r.role_name, last.event_type, last.recorded_at "
                             + "  FROM users u JOIN roles r ON r.role_id = u.role_id "
                             + "  LEFT JOIN LATERAL (SELECT e.event_type, e.recorded_at FROM trimax_private.duty_events e "
                             + "                      WHERE e.username = u.username ORDER BY e.recorded_at DESC, e.event_id DESC LIMIT 1) last ON true "
                             + " WHERE r.role_name IN ('Agent', 'Driver') AND u.account_status = 'Active' "
                             + " ORDER BY lower(coalesce(u.name, u.username)), u.username");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Person p = new Person();
                p.username = rs.getString(1);
                p.name = rs.getString(2);
                p.role = rs.getString(3);
                p.lastType = rs.getString(4);
                p.lastAt = rs.getTimestamp(5);
                out.add(p);
            }
        } catch (SQLException e) {
            throw Db.failure(e, "Could not load the agents and drivers", Db.UPDATE_30_ADMIN_DUTY);
        }
        return out;
    }

    /**
     * The Admin records a Time in ("START") or Time out ("END") for an agent or driver at {@code at}. The server checks
     * the order (no Time in while on duty...), the time (not in the future, at most 7 days back, after their last
     * record, a duty under 24 hours) and audits it; a Time out also updates their payroll (20261030_02).
     */
    public static void adminEvent(String username, String type, Instant at, String note) throws Db.Failure {
        String admin = Db.admin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select trimax_private.admin_duty_event(?, ?, ?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setString(2, username);
            ps.setString(3, type);
            ps.setObject(4, java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC));
            ps.setString(5, note == null || note.isBlank() ? null : note.trim());
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw Db.failure(e, "The " + ("START".equals(type) ? "Time in" : "Time out") + " was not saved", Db.UPDATE_30_ADMIN_DUTY);
        }
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
