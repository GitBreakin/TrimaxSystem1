package com.trimaxsystem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Locale;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

/**
 * Everything about signing in lives here, so the screens stay small and every rule is in one place:
 * login, admin-only access, remembered sessions, registration, forgot / reset / change password.
 *
 * All methods block while they talk to the database. Call them from a background thread
 * (the panels use AuthUI.async for that).
 */
public final class AuthService {

    // =====================================================
    // SETTINGS
    // =====================================================

    /**
     * Must remain false until reset codes are delivered through a verified email provider.
     * Never expose reset codes in the UI or console.
     */
    public static final boolean DEV_SHOW_RESET_CODE = false;
    // Keep reset disabled until sendResetCode is connected to a verified email provider.
    private static final boolean RESET_EMAIL_CONFIGURED = false;

    private static final String ADMIN_ROLE          = "Admin";
    private static final int    MAX_FAILED_ATTEMPTS = 5;     // wrong passwords before a temporary lock
    private static final int    LOCK_MINUTES        = 15;
    private static final int    REMEMBER_DAYS       = 30;
    private static final int    RESET_MINUTES       = 15;    // how long a reset code works
    private static final int    MAX_RESET_ATTEMPTS  = 5;
    private static final String CODE_CHARS          = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
    private static final int    CODE_LENGTH         = 8;

    private static final String BAD_LOGIN = "Invalid username or password.";
    private static final String BAD_CODE  = "That code is invalid or has expired. Request a new one.";

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.]{3,30}");
    private static final Pattern EMAIL    = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private static final Preferences PREFS = Preferences.userNodeForPackage(AuthService.class);
    private static final String KEY_USER  = "remember.username";
    private static final String KEY_TOKEN = "remember.token";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile Session current;

    private AuthService() {
    }

    // =====================================================
    // TYPES
    // =====================================================

    /** A problem the user should be told about (the message is safe to show on screen). */
    public static final class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }

        public AuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The signed-in administrator. */
    public static final class Session {
        public final String username;
        public final String fullName;
        public final String email;
        public final String role;

        Session(String username, String fullName, String email, String role) {
            this.username = username;
            this.fullName = fullName;
            this.email = email;
            this.role = role;
        }

        public boolean isAdmin() {
            return ADMIN_ROLE.equalsIgnoreCase(role);
        }
    }

    /** The currently signed-in user, or null. */
    public static Session current() {
        return current;
    }

    // =====================================================
    // LOGIN / LOGOUT
    // =====================================================

    /**
     * Signs in. Only an ACTIVE account with the Admin role gets in; the role and status are read
     * from the database on every login, never trusted from the screen.
     */
    public static Session login(String username, char[] password, boolean remember) throws AuthException {
        if (username == null || username.trim().isEmpty() || password == null || password.length == 0) {
            throw new AuthException("Please enter your username and password.");
        }
        username = username.trim();

        String sql = "SELECT u.password, u.name, u.email, u.account_status, r.role_name, "
                + "       CASE WHEN u.locked_until > now() "
                + "            THEN CEIL(EXTRACT(EPOCH FROM (u.locked_until - now())) / 60.0)::int "
                + "            ELSE 0 END AS lock_minutes "
                + "FROM users u LEFT JOIN roles r ON r.role_id = u.role_id "
                + "WHERE u.username = ?";

        try (Connection conn = DatabaseConnection.getConnection()) {
            String storedPassword, fullName, email, status, role;
            int lockMinutes;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        PasswordHasher.burnTime(password);   // same delay as a real check
                        throw new AuthException(BAD_LOGIN);
                    }
                    storedPassword = rs.getString("password");
                    fullName = rs.getString("name");
                    email = rs.getString("email");
                    status = rs.getString("account_status");
                    role = rs.getString("role_name");
                    lockMinutes = rs.getInt("lock_minutes");
                }
            }

            if (lockMinutes > 0) {
                throw new AuthException("Too many failed attempts. Try again in " + lockMinutes
                        + " minute" + (lockMinutes == 1 ? "" : "s") + ".");
            }

            if (!PasswordHasher.verify(password, storedPassword)) {
                recordFailedAttempt(conn, username);
                throw new AuthException(BAD_LOGIN);
            }

            // Password is correct from here on
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET failed_attempts = 0, locked_until = NULL "
                            + "WHERE username = ? AND (failed_attempts <> 0 OR locked_until IS NOT NULL)")) {
                ps.setString(1, username);
                ps.executeUpdate();
            }
            if (PasswordHasher.needsRehash(storedPassword)) {
                updatePassword(conn, username, password);   // upgrades old plain-text passwords
            }

            if (!"Active".equals(status)) {
                if ("Pending".equals(status)) {
                    throw new AuthException("Your account is waiting for approval by an administrator.");
                }
                throw new AuthException("This account is disabled. Please contact an administrator.");
            }
            if (!ADMIN_ROLE.equalsIgnoreCase(role)) {
                throw new AuthException("Access denied. This application is for administrators only.");
            }

            // Remember this computer (or forget it)
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM login_sessions WHERE expires_at < now()")) {
                ps.executeUpdate();
            }
            dropSavedToken(conn);
            if (remember) {
                String token = createRememberToken(conn, username);
                PREFS.put(KEY_USER, username);
                PREFS.put(KEY_TOKEN, token);
            }

            current = new Session(username, fullName, email, role);
            return current;

        } catch (SQLException e) {
            throw db(e);
        }
    }

    /** True if this computer has a remembered session saved (a quick local check, no database). */
    public static boolean hasSavedSession() {
        return !PREFS.get(KEY_USER, "").isEmpty() && !PREFS.get(KEY_TOKEN, "").isEmpty();
    }

    /**
     * Signs in with the remembered session, if it is still valid. The role and status are checked
     * again in the database. Returns null if there is nothing valid to restore.
     */
    public static Session loginWithSavedSession() {
        String username = PREFS.get(KEY_USER, "");
        String token = PREFS.get(KEY_TOKEN, "");
        if (username.isEmpty() || token.isEmpty()) return null;

        try (Connection conn = DatabaseConnection.getConnection()) {
            boolean valid;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM login_sessions WHERE token_hash = ? AND username = ? AND expires_at > now()")) {
                ps.setString(1, sha256Hex(token));
                ps.setString(2, username);
                try (ResultSet rs = ps.executeQuery()) {
                    valid = rs.next();
                }
            }
            Session session = valid ? loadAdminSession(conn, username) : null;
            if (session == null) {
                forgetSavedSession();
                return null;
            }
            current = session;
            return session;
        } catch (SQLException e) {
            return null;   // database not reachable: just show the normal login screen
        }
    }

    /** Signs out and forgets the remembered session on this computer. */
    public static void logout() {
        String token = PREFS.get(KEY_TOKEN, "");
        forgetSavedSession();   // this computer forgets the session first, whatever happens next
        current = null;

        if (!token.isEmpty()) {
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM login_sessions WHERE token_hash = ?")) {
                ps.setString(1, sha256Hex(token));
                ps.executeUpdate();
            } catch (SQLException ignored) {
                // the saved session expires by itself if the database could not be reached
            }
        }
    }

    /**
     * Backend role verification: re-checks in the database that the signed-in user is still an
     * ACTIVE Admin. Call it before sensitive actions (deleting, editing, approving...). If the
     * account was demoted or disabled meanwhile, the user is signed out and an exception is thrown.
     */
    public static Session requireAdmin() throws AuthException {
        Session s = current;
        if (s == null) throw new AuthException("You are not logged in.");

        try (Connection conn = DatabaseConnection.getConnection()) {
            Session fresh = loadAdminSession(conn, s.username);
            if (fresh == null) {
                logout();
                throw new AuthException("Your administrator access is no longer valid. Please log in again.");
            }
            current = fresh;
            return fresh;
        } catch (SQLException e) {
            throw db(e);
        }
    }

    // =====================================================
    // REGISTRATION
    // =====================================================

    /**
     * Creates an account. It always has the Admin role, but it only becomes ACTIVE right away for the
     * very first administrator. Every later account is created as PENDING until an existing
     * administrator activates it.
     *
     * @return "Active" or "Pending"
     */
    public static String register(String fullName, String email, String username, char[] password)
            throws AuthException {
        fullName = fullName == null ? "" : fullName.trim();
        email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        username = username == null ? "" : username.trim();

        if (fullName.length() < 2 || fullName.length() > 100) {
            throw new AuthException("Please enter your full name.");
        }
        if (email.length() > 100 || !EMAIL.matcher(email).matches()) {
            throw new AuthException("Please enter a valid email address.");
        }
        if (!USERNAME.matcher(username).matches()) {
            throw new AuthException("Username must be 3 to 30 characters: letters, numbers, dots or underscores.");
        }
        String passwordProblem = validatePassword(password);
        if (passwordProblem != null) throw new AuthException(passwordProblem);

        String sql = "INSERT INTO users (username, password, name, email, role_id, account_status) "
                + "SELECT ?, ?, ?, ?, (SELECT role_id FROM roles WHERE role_name = 'Admin'), "
                + "       CASE WHEN EXISTS (SELECT 1 FROM users u JOIN roles r ON r.role_id = u.role_id "
                + "                         WHERE r.role_name = 'Admin' AND u.account_status = 'Active') "
                + "            THEN 'Pending' ELSE 'Active' END "
                + "RETURNING account_status";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, PasswordHasher.hash(password));
            ps.setString(3, fullName);
            ps.setString(4, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {   // unique violation
                String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                throw new AuthException(msg.contains("email")
                        ? "That email address is already registered."
                        : "That username is already taken.");
            }
            if ("23502".equals(e.getSQLState())) {   // not-null violation: the Admin role row is missing
                throw new AuthException("The Admin role was not found. Add it to the roles table first.");
            }
            throw db(e);
        }
    }

    // =====================================================
    // FORGOT / RESET / CHANGE PASSWORD
    // =====================================================

    /**
     * Step 1 of "forgot password": creates a one-time code (valid for a few minutes) for the account
     * that matches the email or username. The screen says the same thing whether or not an account
     * matched, so it can't be used to find out which usernames exist.
     *
     * @return the code only while DEV_SHOW_RESET_CODE is true (otherwise null)
     */
    public static String requestPasswordReset(String emailOrUsername) throws AuthException {
        String id = emailOrUsername == null ? "" : emailOrUsername.trim();
        if (id.isEmpty()) throw new AuthException("Please enter your email address or username.");
        if (!emailDeliveryConfigured()) {
            throw new AuthException("Password reset is temporarily unavailable because email delivery is not configured. Please contact an administrator.");
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            String[] user = findUser(conn, id);     // {username, name, email} or null
            if (user == null) return null;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM password_resets WHERE username = ? "
                            + "AND created_at > now() - interval '60 seconds'")) {
                ps.setString(1, user[0]);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new AuthException("A code was just requested. Please wait a minute before asking for another.");
                    }
                }
            }

            StringBuilder code = new StringBuilder();
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM password_resets WHERE username = ?")) {
                ps.setString(1, user[0]);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO password_resets (username, code_hash, expires_at) "
                            + "VALUES (?, ?, now() + make_interval(mins => ?))")) {
                ps.setString(1, user[0]);
                ps.setString(2, sha256Hex(code.toString()));
                ps.setInt(3, RESET_MINUTES);
                ps.executeUpdate();
            }

            sendResetCode(user[2], user[1], code.toString());
            return DEV_SHOW_RESET_CODE ? code.toString() : null;
        } catch (SQLException e) {
            throw db(e);
        }
    }

    /**
     * Step 2 of "forgot password": checks the code and sets the new password.
     * All remembered sessions of that account are ended.
     *
     * @return the username whose password was changed
     */
    public static String resetPassword(String emailOrUsername, String code, char[] newPassword)
            throws AuthException {
        String id = emailOrUsername == null ? "" : emailOrUsername.trim();
        String entered = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (id.isEmpty() || entered.isEmpty()) throw new AuthException("Please enter the reset code.");
        String passwordProblem = validatePassword(newPassword);
        if (passwordProblem != null) throw new AuthException(passwordProblem);

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);

            String[] user = findUser(conn, id);
            if (user == null) throw new AuthException(BAD_CODE);
            String username = user[0];

            int resetId;
            String codeHash;
            int attempts;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT reset_id, code_hash, attempts FROM password_resets "
                            + "WHERE username = ? AND used = false AND expires_at > now() "
                            + "ORDER BY created_at DESC LIMIT 1 FOR UPDATE")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new AuthException(BAD_CODE);
                    resetId = rs.getInt("reset_id");
                    codeHash = rs.getString("code_hash");
                    attempts = rs.getInt("attempts");
                }
            }

            if (attempts >= MAX_RESET_ATTEMPTS) {
                markResetUsed(conn, resetId);
                conn.commit();
                throw new AuthException("Too many wrong attempts. Please request a new code.");
            }

            boolean match = MessageDigest.isEqual(
                    codeHash.getBytes(StandardCharsets.UTF_8),
                    sha256Hex(entered).getBytes(StandardCharsets.UTF_8));
            if (!match) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE password_resets SET attempts = attempts + 1 WHERE reset_id = ?")) {
                    ps.setInt(1, resetId);
                    ps.executeUpdate();
                }
                conn.commit();   // keep the attempt counter even though we report an error
                throw new AuthException(BAD_CODE);
            }

            updatePassword(conn, username, newPassword);
            markResetUsed(conn, resetId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE username = ?")) {
                ps.setString(1, username);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM login_sessions WHERE username = ?")) {
                ps.setString(1, username);
                ps.executeUpdate();
            }
            conn.commit();
            return username;
        } catch (SQLException e) {
            throw db(e);
        }
    }

    /** Changes the password of the signed-in user. Remembered sessions are ended. */
    public static void changePassword(char[] currentPassword, char[] newPassword) throws AuthException {
        Session s = current;
        if (s == null) throw new AuthException("You are not logged in.");
        if (currentPassword == null || currentPassword.length == 0) {
            throw new AuthException("Please enter your current password.");
        }
        String passwordProblem = validatePassword(newPassword);
        if (passwordProblem != null) throw new AuthException(passwordProblem);
        if (java.util.Arrays.equals(currentPassword, newPassword)) {
            throw new AuthException("The new password must be different from the current one.");
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            String stored = null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT password FROM users WHERE username = ?")) {
                ps.setString(1, s.username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) stored = rs.getString(1);
                }
            }
            if (stored == null || !PasswordHasher.verify(currentPassword, stored)) {
                throw new AuthException("Your current password is incorrect.");
            }

            updatePassword(conn, s.username, newPassword);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM login_sessions WHERE username = ?")) {
                ps.setString(1, s.username);
                ps.executeUpdate();
            }
            forgetSavedSession();
        } catch (SQLException e) {
            throw db(e);
        }
    }

    /** Returns a message describing what is wrong with the password, or null if it is acceptable. */
    public static String validatePassword(char[] password) {
        if (password == null || password.length < 8) return "Password must be at least 8 characters.";
        if (password.length > 128) return "Password can be at most 128 characters.";
        boolean letter = false, digit = false;
        for (char c : password) {
            if (Character.isLetter(c)) letter = true;
            if (Character.isDigit(c)) digit = true;
        }
        return letter && digit ? null : "Password must contain at least one letter and one number.";
    }

    /**
     * Delivers the reset code through a configured email provider.
     * Keep RESET_EMAIL_CONFIGURED false until this method actually sends email.
     */
    private static boolean emailDeliveryConfigured() {
        return false; // Enable only after a real email provider is configured.
    }

    private static void sendResetCode(String email, String fullName, String code) {
        // TODO: integrate SMTP or a transactional email provider before enabling password reset.
    }

    // =====================================================
    // INTERNAL HELPERS
    // =====================================================

    private static AuthException db(SQLException e) {
        return new AuthException("Database error: " + e.getMessage(), e);
    }

    /** Finds an account by username or email. Returns {username, name, email}, or null. */
    private static String[] findUser(Connection conn, String emailOrUsername) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT username, name, email FROM users WHERE username = ? OR LOWER(email) = LOWER(?) LIMIT 1")) {
            ps.setString(1, emailOrUsername);
            ps.setString(2, emailOrUsername);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}
                        : null;
            }
        }
    }

    /** Loads the user as a Session only if the account is ACTIVE and has the Admin role. */
    private static Session loadAdminSession(Connection conn, String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT u.username, u.name, u.email, r.role_name FROM users u "
                        + "JOIN roles r ON r.role_id = u.role_id "
                        + "WHERE u.username = ? AND u.account_status = 'Active' AND r.role_name = ?")) {
            ps.setString(1, username);
            ps.setString(2, ADMIN_ROLE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new Session(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                        : null;
            }
        }
    }

    private static void recordFailedAttempt(Connection conn, String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET "
                        + "locked_until = CASE WHEN failed_attempts + 1 >= ? "
                        + "                    THEN now() + make_interval(mins => ?) ELSE locked_until END, "
                        + "failed_attempts = CASE WHEN failed_attempts + 1 >= ? THEN 0 ELSE failed_attempts + 1 END "
                        + "WHERE username = ?")) {
            ps.setInt(1, MAX_FAILED_ATTEMPTS);
            ps.setInt(2, LOCK_MINUTES);
            ps.setInt(3, MAX_FAILED_ATTEMPTS);
            ps.setString(4, username);
            ps.executeUpdate();
        }
    }

    private static void updatePassword(Connection conn, String username, char[] password) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET password = ? WHERE username = ?")) {
            ps.setString(1, PasswordHasher.hash(password));
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    private static void markResetUsed(Connection conn, int resetId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE password_resets SET used = true WHERE reset_id = ?")) {
            ps.setInt(1, resetId);
            ps.executeUpdate();
        }
    }

    /** Creates a random token. Only its SHA-256 hash is stored in the database. */
    private static String createRememberToken(Connection conn, String username) throws SQLException {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO login_sessions (token_hash, username, expires_at) "
                        + "VALUES (?, ?, now() + make_interval(days => ?))")) {
            ps.setString(1, sha256Hex(token));
            ps.setString(2, username);
            ps.setInt(3, REMEMBER_DAYS);
            ps.executeUpdate();
        }
        return token;
    }

    /** Removes the remembered session of this computer (database row and local copy). */
    private static void dropSavedToken(Connection conn) throws SQLException {
        String token = PREFS.get(KEY_TOKEN, "");
        if (!token.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM login_sessions WHERE token_hash = ?")) {
                ps.setString(1, sha256Hex(token));
                ps.executeUpdate();
            }
        }
        forgetSavedSession();
    }

    private static void forgetSavedSession() {
        PREFS.remove(KEY_USER);
        PREFS.remove(KEY_TOKEN);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this Java runtime", e);
        }
    }
}