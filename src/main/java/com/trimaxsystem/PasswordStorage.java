package com.trimaxsystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds accounts whose password is still stored as plain text (from before hashing existed) and stores it
 * as a PBKDF2 hash instead. Each person keeps the same password: only the stored form changes. Normally this
 * happens by itself the next time the person signs in to the Desktop; this does it for everyone at once.
 *
 * Safe to run more than once. An account whose password changed in the meantime is skipped (the update
 * only applies if the stored value is still the one that was read). Passwords are never shown, logged or
 * written to the audit log.
 */
final class PasswordStorage {

    private PasswordStorage() {
    }

    /** Accounts whose stored password is not a PBKDF2 hash. */
    static int countUnhashed() throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM users WHERE password IS NOT NULL AND password NOT LIKE 'pbkdf2$%'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static final class Result {
        int secured, skipped;
    }

    /** Hashes every plain-text password. Returns how many were secured and how many changed meanwhile. */
    static Result secureAll() throws SQLException, AuthService.AuthException {
        AuthService.requireAdmin();
        Result r = new Result();
        try (Connection conn = DatabaseConnection.getConnection()) {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT username, password FROM users WHERE password IS NOT NULL AND password NOT LIKE 'pbkdf2$%'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2)});
            }
            for (String[] row : rows) {
                char[] plain = row[1].toCharArray();
                String hashed;
                try {
                    hashed = PasswordHasher.hash(plain);
                } finally {
                    Arrays.fill(plain, '\0');
                }
                int changed;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET password = ? WHERE username = ? AND password = ?")) {
                    ps.setString(1, hashed);
                    ps.setString(2, row[0]);
                    ps.setString(3, row[1]);
                    changed = ps.executeUpdate();
                }
                row[1] = null;
                if (changed == 1) {
                    r.secured++;
                    AuditService.record(conn, "user.password_storage_secured", "user", row[0], "success",
                            "Stored password converted to a PBKDF2 hash (password unchanged)");
                } else {
                    r.skipped++;   // changed or removed since it was read: nothing to do
                }
            }
        }
        return r;
    }
}
