package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Database work and rules for live vehicle locations.
 *
 * What the database actually holds: only a vehicle's LAST known position (vehicles.latitude, longitude,
 * last_location_at), written by the driver's phone. There is no location history, and agents do not
 * report a live position. Nothing here invents a position, a movement or an online state.
 *
 * Freshness (decided by {@link #classify}):
 *   - NO_LOCATION: the vehicle has no coordinates.
 *   - STALE: it has coordinates, but the last update is older than the configured limit, or has no
 *     timestamp (so it cannot be shown to be current).
 *   - CURRENT: it has coordinates and the last update is within the limit.
 * Ages are measured with the DATABASE clock (now() - last_location_at), so a wrong clock on this
 * computer cannot make an old position look current.
 */
public final class GpsService {

    private GpsService() {
    }

    /** More vehicles than this are not loaded (the map is for a working fleet, not a catalogue). */
    public static final int MAX_VEHICLES = 1000;

    public enum Freshness {
        CURRENT("Current"), STALE("Stale"), NO_LOCATION("No location");

        public final String label;

        Freshness(String label) {
            this.label = label;
        }
    }

    /**
     * @param ageSeconds seconds since the last update, or NaN when there is no timestamp
     */
    public static Freshness classify(boolean hasCoordinates, double ageSeconds, long staleAfterSeconds) {
        if (!hasCoordinates) return Freshness.NO_LOCATION;
        if (Double.isNaN(ageSeconds)) return Freshness.STALE;
        return Math.max(0, ageSeconds) > staleAfterSeconds ? Freshness.STALE : Freshness.CURRENT;
    }

    /** Straight-line (great-circle) distance in kilometres. NOT a driving distance. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    // =====================================================
    // VEHICLE POSITIONS
    // =====================================================

    /** One vehicle with its last known position (if any). */
    public static final class VehiclePosition {
        public final String vehicleId, vehicleType, vehicleStatus;
        public final String driverUsername, driverName, driverAccountStatus;   // null when no driver
        public final Double lat, lon;                                          // null when no position
        public final Timestamp lastLocationAt;                                 // null when never reported
        private final double ageAtFetchSeconds;                                // NaN when no timestamp
        private final long fetchedNanos;

        VehiclePosition(String vehicleId, String vehicleType, String vehicleStatus,
                        String driverUsername, String driverName, String driverAccountStatus,
                        Double lat, Double lon, Timestamp lastLocationAt,
                        double ageAtFetchSeconds, long fetchedNanos) {
            this.vehicleId = vehicleId;
            this.vehicleType = vehicleType;
            this.vehicleStatus = vehicleStatus;
            this.driverUsername = driverUsername;
            this.driverName = driverName;
            this.driverAccountStatus = driverAccountStatus;
            this.lat = lat;
            this.lon = lon;
            this.lastLocationAt = lastLocationAt;
            this.ageAtFetchSeconds = ageAtFetchSeconds;
            this.fetchedNanos = fetchedNanos;
        }

        public boolean hasCoordinates() {
            return lat != null && lon != null;
        }

        /** Seconds since the last update as of now (it keeps growing after the fetch), or NaN if unknown. */
        public double ageSeconds() {
            if (Double.isNaN(ageAtFetchSeconds)) return Double.NaN;
            return Math.max(0, ageAtFetchSeconds) + (System.nanoTime() - fetchedNanos) / 1e9;
        }

        public Freshness freshness(long staleAfterSeconds) {
            return classify(hasCoordinates(), ageSeconds(), staleAfterSeconds);
        }

        public String driverLabel() {
            if (driverUsername == null) return "No driver";
            return driverName == null ? driverUsername : driverName;
        }
    }

    public static final class Snapshot {
        public final List<VehiclePosition> vehicles = new ArrayList<>();
        public boolean truncated;          // more than MAX_VEHICLES exist
        public long fetchedAtMillis;
    }

    /**
     * Loads every vehicle with its last position. If verifyAdmin is set, the signed-in user's admin role
     * is re-checked in the database first (so a demoted or disabled admin cannot keep watching locations).
     */
    public static Snapshot fetch(boolean verifyAdmin) throws SQLException, AuthService.AuthException {
        if (verifyAdmin) AuthService.requireAdmin();

        String sql = "SELECT v.vehicle_id, v.vehicle_type, v.status, v.latitude, v.longitude, v.last_location_at, "
                + "EXTRACT(EPOCH FROM (now() - v.last_location_at)) AS age_s, "
                + "v.username, u.name, u.account_status "
                + "FROM vehicles v LEFT JOIN users u ON u.username = v.username "
                + "ORDER BY v.vehicle_id LIMIT ?";

        Snapshot snap = new Snapshot();
        long fetchedNanos = System.nanoTime();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, MAX_VEHICLES + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (snap.vehicles.size() == MAX_VEHICLES) {
                        snap.truncated = true;
                        break;
                    }
                    double lat = rs.getDouble("latitude");
                    boolean hasLat = !rs.wasNull();
                    double lon = rs.getDouble("longitude");
                    boolean hasLon = !rs.wasNull();
                    double age = rs.getDouble("age_s");
                    boolean hasAge = !rs.wasNull();
                    snap.vehicles.add(new VehiclePosition(
                            rs.getString("vehicle_id"), rs.getString("vehicle_type"), rs.getString("status"),
                            rs.getString("username"), rs.getString("name"), rs.getString("account_status"),
                            hasLat && hasLon ? lat : null, hasLat && hasLon ? lon : null,
                            rs.getTimestamp("last_location_at"),
                            hasAge ? age : Double.NaN, fetchedNanos));
                }
            }
        }
        snap.fetchedAtMillis = System.currentTimeMillis();
        return snap;
    }

    // =====================================================
    // AGENT POSITIONS (table user_locations, see sql/003_user_locations.sql)
    // =====================================================

    /** One Agent with their last reported position. Legacy 'Rider' accounts are treated as Agents. */
    public static final class AgentPosition {
        public final String username, name, accountStatus;
        public final double lat, lon;
        public final Double accuracyM;                 // null when the phone did not report it
        public final Timestamp recordedAt;
        private final double ageAtFetchSeconds;        // NaN when there is no timestamp
        private final long fetchedNanos;

        AgentPosition(String username, String name, String accountStatus, double lat, double lon,
                      Double accuracyM, Timestamp recordedAt, double ageAtFetchSeconds, long fetchedNanos) {
            this.username = username;
            this.name = name;
            this.accountStatus = accountStatus;
            this.lat = lat;
            this.lon = lon;
            this.accuracyM = accuracyM;
            this.recordedAt = recordedAt;
            this.ageAtFetchSeconds = ageAtFetchSeconds;
            this.fetchedNanos = fetchedNanos;
        }

        public double ageSeconds() {
            if (Double.isNaN(ageAtFetchSeconds)) return Double.NaN;
            return Math.max(0, ageAtFetchSeconds) + (System.nanoTime() - fetchedNanos) / 1e9;
        }

        public Freshness freshness(long staleAfterSeconds) {
            return classify(true, ageSeconds(), staleAfterSeconds);
        }

        public String label() {
            return name == null || name.isBlank() ? username : name;
        }
    }

    public static final class AgentSnapshot {
        public final List<AgentPosition> agents = new ArrayList<>();
        /** True when the user_locations table does not exist yet: "not set up", not "nobody is out". */
        public boolean tableMissing;
        public boolean truncated;
    }

    /** Loads the last known position of every Agent that has one. Admin role is re-checked first. */
    public static AgentSnapshot fetchAgents(boolean verifyAdmin) throws SQLException, AuthService.AuthException {
        if (verifyAdmin) AuthService.requireAdmin();
        String sql = "SELECT l.username, u.name, u.account_status, l.latitude, l.longitude, l.accuracy_m, "
                + "l.recorded_at, EXTRACT(EPOCH FROM (now() - l.recorded_at)) AS age_s "
                + "FROM user_locations l JOIN users u ON u.username = l.username "
                + "JOIN roles r ON r.role_id = u.role_id "
                + "WHERE r.role_name IN ('Agent', 'Rider') ORDER BY u.name, l.username LIMIT ?";
        AgentSnapshot snap = new AgentSnapshot();
        long fetchedNanos = System.nanoTime();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, MAX_VEHICLES + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (snap.agents.size() == MAX_VEHICLES) {
                        snap.truncated = true;
                        break;
                    }
                    double acc = rs.getDouble("accuracy_m");
                    boolean hasAcc = !rs.wasNull();
                    double age = rs.getDouble("age_s");
                    boolean hasAge = !rs.wasNull();
                    snap.agents.add(new AgentPosition(rs.getString("username"), rs.getString("name"),
                            rs.getString("account_status"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                            hasAcc ? acc : null, rs.getTimestamp("recorded_at"),
                            hasAge ? age : Double.NaN, fetchedNanos));
                }
            }
        } catch (SQLException e) {
            if ("42P01".equals(e.getSQLState())) {   // undefined_table
                snap.tableMissing = true;
                return snap;
            }
            throw e;
        }
        return snap;
    }

    // =====================================================
    // DESTINATION OF A DRIVER'S CURRENT DELIVERY
    // =====================================================

    /** The delivery a driver is on right now: their latest In Progress booking. */
    public static final class Destination {
        public int bookingId;
        public String clientName, address;
        public Double lat, lon;            // null when the customer has no coordinates
        public int inProgressCount;        // how many In Progress bookings this driver has

        public boolean hasCoordinates() {
            return lat != null && lon != null;
        }
    }

    /** Returns the driver's current delivery, or null if they have no In Progress booking. */
    public static Destination loadDestination(String driverUsername) throws SQLException {
        String sql = "SELECT b.booking_id, c.client_name, c.address, c.latitude, c.longitude, COUNT(*) OVER () "
                + "FROM bookings b JOIN clients c ON c.client_id = b.client_id "
                + "WHERE b.driver_username = ? AND b.status = 'In Progress' "
                + "ORDER BY b.transc_date DESC LIMIT 1";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, driverUsername);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Destination d = new Destination();
                d.bookingId = rs.getInt(1);
                d.clientName = rs.getString(2);
                d.address = rs.getString(3);
                double lat = rs.getDouble(4);
                boolean hasLat = !rs.wasNull();
                double lon = rs.getDouble(5);
                boolean hasLon = !rs.wasNull();
                if (hasLat && hasLon) {
                    d.lat = lat;
                    d.lon = lon;
                }
                d.inProgressCount = rs.getInt(6);
                return d;
            }
        }
    }
}
