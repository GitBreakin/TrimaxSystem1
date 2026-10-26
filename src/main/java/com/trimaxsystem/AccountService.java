package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Database work for the Agents &amp; Drivers screen: listing accounts, reading one account, and
 * changing its account status. Passwords, password hashes and sign-in tokens are never read here.
 * <p>
 * Roles: the official roles are Agent and Driver (plus Admin for the desktop app). A legacy role
 * named "Rider", if one still exists in the roles table, is shown as Agent; no record is changed.
 */
public final class AccountService {

    private AccountService() {
    }

    public static final int PAGE_SIZE = 25;

    /**
     * Role choices of the list filter.
     */
    public static final String VIEW_AGENTS_DRIVERS = "Agents & Drivers";
    public static final String VIEW_AGENTS = "Agents";
    public static final String VIEW_DRIVERS = "Drivers";
    public static final String VIEW_ADMINS = "Admins";

    /**
     * ORDER BY expression for each list column. Only these fixed strings are put into the query.
     */
    private static final String[] SORT_SQL = {"LOWER(u.username)", "LOWER(u.name)", "r.role_name", "u.account_status"};

    /**
     * Account statuses an admin may set. (Pending only exists until an account is approved or rejected.)
     */
    public static final String ACTIVE = "Active";
    public static final String INACTIVE = "Inactive";
    private static final Pattern EMPLOYEE_USERNAME = Pattern.compile("[A-Za-z0-9_.]{3,30}");
    private static final Pattern EMPLOYEE_EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern EMPLOYEE_PHONE = Pattern.compile("09[0-9]{9}");

    /**
     * The role name to show: the legacy "Rider" becomes "Agent".
     */
    public static String roleLabel(String roleName) {
        return "Rider".equalsIgnoreCase(roleName) ? "Agent" : roleName;
    }

    // =====================================================
    // LIST
    // =====================================================

    /**
     * What the admin asked for in the list.
     */
    public static final class Query {
        public String view = VIEW_AGENTS_DRIVERS;
        public String search = "";
        public String status = null;      // null = any
        public int sortColumn = 0;        // index into the list columns: username, name, role, status
        public boolean ascending = true;
        public int page = 0;

        public boolean hasFilters() {
            return !search.isEmpty() || status != null || !VIEW_AGENTS_DRIVERS.equals(view);
        }
    }

    public static final class Page {
        public long total;
        /**
         * username, name, role label, account status, contact, summary
         */
        public List<Object[]> rows = new ArrayList<>();
    }

    public static Page list(Query q) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<String> params = new ArrayList<>();

        switch (q.view) {
            case VIEW_AGENTS:
                where.append(" AND r.role_name IN ('Agent', 'Rider')");
                break;
            case VIEW_DRIVERS:
                where.append(" AND r.role_name = 'Driver'");
                break;
            case VIEW_ADMINS:
                where.append(" AND r.role_name = 'Admin'");
                break;
            default:
                where.append(" AND r.role_name IN ('Agent', 'Rider', 'Driver')");
        }
        if (!q.search.isEmpty()) {
            where.append(" AND (u.username ILIKE ? OR u.name ILIKE ? OR u.email ILIKE ? OR u.contact_num ILIKE ?)");
            String like = "%" + q.search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 4; i++) params.add(like);
        }
        if (q.status != null) {
            where.append(" AND u.account_status = ?");
            params.add(q.status);
        }

        String from = " FROM users u JOIN roles r ON r.role_id = u.role_id" + where;
        String countSql = "SELECT COUNT(*)" + from;
        String dataSql = "SELECT u.username, u.name, r.role_name, u.account_status, COALESCE(u.contact_num, ''), "
                + "CASE "
                + " WHEN r.role_name = 'Driver' THEN COALESCE((SELECT string_agg(v.vehicle_id || ' (' || v.status || ')', ', ' "
                + "      ORDER BY v.vehicle_id) FROM vehicles v WHERE v.username = u.username), 'No vehicle') "
                + " WHEN r.role_name IN ('Agent', 'Rider') THEN "
                + "      (SELECT COUNT(*) FROM agents a WHERE a.username = u.username) || ' client(s)' "
                + " ELSE '' END"
                + from
                + " ORDER BY " + SORT_SQL[q.sortColumn] + (q.ascending ? " ASC" : " DESC") + ", u.username"
                + " LIMIT ? OFFSET ?";

        Page page = new Page();
        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    page.total = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(dataSql)) {
                bind(ps, params);
                ps.setInt(params.size() + 1, PAGE_SIZE);
                ps.setLong(params.size() + 2, (long) q.page * PAGE_SIZE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        page.rows.add(new Object[]{
                                rs.getString(1), rs.getString(2), roleLabel(rs.getString(3)),
                                rs.getString(4), rs.getString(5), rs.getString(6)});
                    }
                }
            }
        }
        return page;
    }

    private static void bind(PreparedStatement ps, List<String> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
    }

    /**
     * Creates an active Agent or Driver account with a role-based username.
     */
    public static String createEmployee(String fullName, String email, String contact, String roleName)
            throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        fullName = fullName == null ? "" : fullName.trim();
        email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        contact = contact == null ? "" : contact.trim();

        if (fullName.length() < 2 || fullName.length() > 100) {
            throw new AuthService.AuthException("Enter a name between 2 and 100 characters.");
        }
        if (email.length() > 100 || !EMPLOYEE_EMAIL.matcher(email).matches()) {
            throw new AuthService.AuthException("Enter a valid email address.");
        }
        if (!EMPLOYEE_PHONE.matcher(contact).matches()) {
            throw new AuthService.AuthException(
                    "Contact number must be 11 digits and start with 09 (for example, 09123456789).");
        }
        if (!"Agent".equals(roleName) && !"Driver".equals(roleName)) {
            throw new AuthService.AuthException("Choose an Agent or Driver role.");
        }
        if (!EmailService.isConfigured()) {
            throw new AuthService.AuthException(
                    EmailService.notSetUpMessage("the new employee cannot be sent a setup code") + "\nNothing was saved.");
        }

        String username = employeeUsername(fullName, roleName);
        char[] initialPassword = new char[32];
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        initialPassword[0] = 'T';
        for (int i = 1; i < initialPassword.length - 1; i++) {
            initialPassword[i] = alphabet.charAt(random.nextInt(alphabet.length()));
        }
        initialPassword[initialPassword.length - 1] = '7';

        String sql = "INSERT INTO users (username, password, name, email, contact_num, role_id, account_status) "
                + "SELECT ?, ?, ?, ?, ?, role_id, 'Active' FROM roles WHERE role_name = ?";
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, username);
                    ps.setString(2, PasswordHasher.hash(initialPassword));
                    ps.setString(3, fullName);
                    ps.setString(4, email);
                    ps.setString(5, contact);
                    ps.setString(6, roleName);
                    if (ps.executeUpdate() != 1) {
                        throw new AuthService.AuthException("The selected employee role is not configured.");
                    }
                }

                // The phone sign-in and its temporary password are made right after this
                // (EmployeeOnboarding); the password is emailed then.
                conn.commit();
                return username;
            } catch (SQLException | AuthService.AuthException ex) {
                conn.rollback();
                if (ex instanceof SQLException && "23505".equals(((SQLException) ex).getSQLState())) {
                    throw new AuthService.AuthException("The generated username \"" + username
                            + "\" or email address is already in use.");
                }
                throw ex;
            } finally {
                conn.setAutoCommit(true);
                java.util.Arrays.fill(initialPassword, (char) 0);
            }
        }
    }

    private static String employeeUsername(String fullName, String roleName) throws AuthService.AuthException {
        String firstName = fullName.split("\\s+", 2)[0];
        String normalized = Normalizer.normalize(firstName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            throw new AuthService.AuthException("The first name must contain letters or numbers to generate a username.");
        }
        String prefix = "Agent".equals(roleName) ? "agent_" : "driver_";
        String username = prefix + normalized;
        if (!EMPLOYEE_USERNAME.matcher(username).matches()) {
            throw new AuthService.AuthException("The generated username must be at most 30 characters. "
                    + "Use a shorter first name.");
        }
        return username;
    }

    // =====================================================
    // ONE ACCOUNT
    // =====================================================

    public static final class VehicleInfo {
        public String vehicleId, type, status;
        public Timestamp lastLocationAt;   // null = never reported a position
    }

    public static final class ClientInfo {
        public int clientId;
        public String name, address;
    }

    public static final class BookingRow {
        public int bookingId;
        public Timestamp date;
        public String client, status;
    }

    public static final class Detail {
        public String username, name, email, contact, role, roleName, status;
        public Timestamp lockedUntil;   // only set while a temporary sign-in lock is active
        public List<VehicleInfo> vehicles = new ArrayList<>();        // drivers
        public Map<String, Long> bookingCounts = new LinkedHashMap<>(); // drivers: bookings by status
        public List<BookingRow> recentBookings = new ArrayList<>();   // drivers
        public List<ClientInfo> clients = new ArrayList<>();          // agents
        public long openBookings;                                     // drivers: Pending/Confirmed/In Progress
        public MobileIntegration.Link mobile;                         // agents and drivers: mobile app sign-in

        public boolean isDriver() {
            return "Driver".equals(roleName);
        }

        public boolean isAgent() {
            return "Agent".equalsIgnoreCase(roleName) || "Rider".equalsIgnoreCase(roleName);
        }
    }

    /**
     * Loads one account with its role-specific summary. Returns null if it no longer exists.
     */
    public static Detail loadDetail(String username) throws SQLException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            Detail d = new Detail();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT u.username, u.name, u.email, u.contact_num, r.role_name, u.account_status, "
                            + "CASE WHEN u.locked_until > now() THEN u.locked_until END "
                            + "FROM users u JOIN roles r ON r.role_id = u.role_id WHERE u.username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    d.username = rs.getString(1);
                    d.name = rs.getString(2);
                    d.email = rs.getString(3);
                    d.contact = rs.getString(4);
                    d.roleName = rs.getString(5);
                    d.role = roleLabel(d.roleName);
                    d.status = rs.getString(6);
                    d.lockedUntil = rs.getTimestamp(7);
                }
            }

            if (d.isDriver()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT vehicle_id, vehicle_type, status, last_location_at FROM vehicles "
                                + "WHERE username = ? ORDER BY vehicle_id")) {
                    ps.setString(1, username);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            VehicleInfo v = new VehicleInfo();
                            v.vehicleId = rs.getString(1);
                            v.type = rs.getString(2);
                            v.status = rs.getString(3);
                            v.lastLocationAt = rs.getTimestamp(4);
                            d.vehicles.add(v);
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status, COUNT(*) FROM bookings WHERE driver_username = ? GROUP BY status")) {
                    ps.setString(1, username);
                    Map<String, Long> raw = new LinkedHashMap<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) raw.put(rs.getString(1), rs.getLong(2));
                    }
                    for (String s : BookingStatus.ALL) d.bookingCounts.put(s, raw.getOrDefault(s, 0L));
                    for (Map.Entry<String, Long> e : raw.entrySet())
                        d.bookingCounts.putIfAbsent(e.getKey(), e.getValue());
                    d.openBookings = d.bookingCounts.getOrDefault(BookingStatus.PENDING, 0L)
                            + d.bookingCounts.getOrDefault(BookingStatus.CONFIRMED, 0L)
                            + d.bookingCounts.getOrDefault(BookingStatus.IN_PROGRESS, 0L);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT b.booking_id, b.transc_date, c.client_name, b.status FROM bookings b "
                                + "LEFT JOIN clients c ON c.client_id = b.client_id "
                                + "WHERE b.driver_username = ? ORDER BY b.transc_date DESC LIMIT 10")) {
                    ps.setString(1, username);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            BookingRow b = new BookingRow();
                            b.bookingId = rs.getInt(1);
                            b.date = rs.getTimestamp(2);
                            b.client = rs.getString(3);
                            b.status = rs.getString(4);
                            d.recentBookings.add(b);
                        }
                    }
                }
            }
            if (d.isDriver() || d.isAgent()) {
                d.mobile = MobileIntegration.loadLink(conn, username);
            }
            if (d.isAgent()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT c.client_id, c.client_name, c.address FROM agents a "
                                + "JOIN clients c ON c.client_id = a.client_id WHERE a.username = ? ORDER BY c.client_name")) {
                    ps.setString(1, username);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ClientInfo c = new ClientInfo();
                            c.clientId = rs.getInt(1);
                            c.name = rs.getString(2);
                            c.address = rs.getString(3);
                            d.clients.add(c);
                        }
                    }
                }
            }
            return d;
        }
    }

    // =====================================================
    // CHANGING THE ACCOUNT STATUS
    // =====================================================

    /**
     * The account was changed by someone else since the admin opened it.
     */
    public static class ConflictException extends Exception {
        public ConflictException(String message) {
            super(message);
        }
    }

    /**
     * Sets an account to Active or Inactive. The admin role is re-checked in the database first. An admin
     * cannot change their own account, and the update only happens if the account still has the status
     * the admin saw. Making an account Inactive also signs out its remembered desktop sessions.
     */
    public static void setStatus(String username, String expectedCurrent, String newStatus)
            throws SQLException, AuthService.AuthException, ConflictException {
        AuthService.Session me = AuthService.requireAdmin();
        if (!ACTIVE.equals(newStatus) && !INACTIVE.equals(newStatus)) {
            throw new IllegalStateException("An account can only be set to Active or Inactive.");
        }
        if (newStatus.equals(expectedCurrent)) {
            throw new IllegalStateException("The account is already " + newStatus + ".");
        }
        if (me.username.equals(username)) {
            throw new IllegalStateException("You cannot change the status of your own account.");
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET account_status = ? WHERE username = ? AND account_status = ?")) {
                    ps.setString(1, newStatus);
                    ps.setString(2, username);
                    ps.setString(3, expectedCurrent);
                    if (ps.executeUpdate() != 1) {
                        throw new ConflictException("This account was changed by someone else. "
                                + "It has been reloaded; please check its current status.");
                    }
                }
                if (INACTIVE.equals(newStatus)) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM login_sessions WHERE username = ?")) {
                        ps.setString(1, username);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException | ConflictException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }
}
