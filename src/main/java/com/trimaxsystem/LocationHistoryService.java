package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Recorded GPS samples of agents and drivers (sql/20261028_01_location_history_and_booking_notifications.sql).
 * Admins only: every call re-checks the Admin in the database, and the database function itself refuses
 * anyone but an active Admin and records each viewing in the audit log. Nothing here estimates or fills
 * in positions: only what the phones sent while the app was open and the person was on duty.
 */
public final class LocationHistoryService {

    /** Most points drawn at once; above this the database returns every n-th sample (and says so). */
    public static final int MAX_POINTS = 2000;
    /** No sample for longer than this starts a new segment: the map draws no route across it. */
    public static final int GAP_MINUTES = 5;
    public static final int MAX_DAYS = 31;

    private static volatile boolean purgedThisSession = false;

    private LocationHistoryService() {
    }

    /** An agent or driver who can be chosen. */
    public static final class Person {
        public final String username, name, role, status;

        public Person(String username, String name, String role, String status) {
            this.username = username;
            this.name = name;
            this.role = role;
            this.status = status;
        }

        @Override
        public String toString() {
            return (name == null || name.isBlank() ? username : name + " (" + username + ")")
                    + " - " + role + ("Active".equals(status) ? "" : ", " + status);
        }
    }

    /** One recorded sample. */
    public static final class Point {
        public long id;
        public Timestamp at;
        public double lat, lon;
        public Float accuracyM;
        public Double speedMps;
        public Integer gapBeforeS;
        public int segment;
    }

    public static final class Result {
        public final List<Point> points = new ArrayList<>();
        public int total;     // samples recorded in the range
        public int step = 1;  // 1 = every sample is shown
    }

    /** Thrown when the database update with the history is not installed yet. */
    public static final class NotInstalledException extends Exception {
        NotInstalledException() {
            super("Location history is not set up on the server yet (database update 20261028_01).");
        }
    }

    public static boolean installed(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT to_regprocedure('trimax_private.location_history_for(text,text,timestamptz,timestamptz,integer,integer)') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /** Agents and drivers (active ones first), for the person picker. */
    public static List<Person> people() throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        List<Person> list = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT u.username, u.name, r.role_name, u.account_status FROM users u "
                             + "JOIN roles r ON r.role_id = u.role_id "
                             + "WHERE r.role_name IN ('Agent', 'Rider', 'Driver') "
                             + "ORDER BY (u.account_status = 'Active') DESC, r.role_name, LOWER(COALESCE(u.name, u.username))");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Person(rs.getString(1), rs.getString(2), AccountService.roleLabel(rs.getString(3)),
                        rs.getString(4)));
            }
        }
        return list;
    }

    /**
     * The person's recorded samples in [from, to), oldest first. Removes samples past the retention period
     * once per session first (the database keeps 90 days by default).
     */
    public static Result load(String username, Instant from, Instant to)
            throws SQLException, AuthService.AuthException, NotInstalledException {
        AuthService.requireAdmin();
        String problem = LocationHistoryRules.checkRange(from, to);
        if (problem != null) throw new IllegalArgumentException(problem);
        String admin = AuthService.current().username;
        Result r = new Result();
        try (Connection conn = DatabaseConnection.getConnection()) {
            if (!installed(conn)) throw new NotInstalledException();
            if (!purgedThisSession) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT trimax_private.purge_location_history(?)")) {
                    ps.setString(1, admin);
                    ps.executeQuery().close();
                    purgedThisSession = true;
                } catch (SQLException ignored) {
                    // Retention clean-up is housekeeping; the history can still be shown.
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sample_id, recorded_at, latitude, longitude, accuracy_m, speed_mps, gap_before_s, segment, "
                            + "total_points, step FROM trimax_private.location_history_for(?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, admin);
                ps.setString(2, username);
                ps.setTimestamp(3, Timestamp.from(from));
                ps.setTimestamp(4, Timestamp.from(to));
                ps.setInt(5, MAX_POINTS);
                ps.setInt(6, GAP_MINUTES);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Point p = new Point();
                        p.id = rs.getLong(1);
                        p.at = rs.getTimestamp(2);
                        p.lat = rs.getDouble(3);
                        p.lon = rs.getDouble(4);
                        float acc = rs.getFloat(5);
                        p.accuracyM = rs.wasNull() ? null : acc;
                        double speed = rs.getDouble(6);
                        p.speedMps = rs.wasNull() ? null : speed;
                        int gap = rs.getInt(7);
                        p.gapBeforeS = rs.wasNull() ? null : gap;
                        p.segment = rs.getInt(8);
                        r.total = rs.getInt(9);
                        r.step = Math.max(1, rs.getInt(10));
                        r.points.add(p);
                    }
                }
            }
        }
        return r;
    }

    /** Start of a Manila day as an instant. */
    static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(Theme.BUSINESS_TZ).toInstant();
    }

    /** "today" in Manila. */
    static LocalDate today() {
        return ZonedDateTime.now(Theme.BUSINESS_TZ).toLocalDate();
    }

    static Duration maxRange() {
        return Duration.ofDays(MAX_DAYS);
    }
}
