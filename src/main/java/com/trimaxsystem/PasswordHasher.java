package com.trimaxsystem;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Salted PBKDF2-HMAC-SHA256 password hashing using only the JDK.
 * Stored format:  pbkdf2$<iterations>$<salt base64>$<hash base64>
 */
final class PasswordHasher {

    private static final String PREFIX     = "pbkdf2";
    private static final int    ITERATIONS = 600_000;   // raise over time as computers get faster
    private static final int    SALT_BYTES = 16;
    private static final int    KEY_BITS   = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** Hashes a password with a fresh random salt. */
    static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(key);
    }

    static boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX + "$");
    }

    /** True for old plain-text passwords or hashes made with fewer iterations than today's setting. */
    static boolean needsRehash(String stored) {
        if (!isHashed(stored)) return true;
        String[] p = stored.split("\\$");
        try {
            return p.length != 4 || Integer.parseInt(p[1]) < ITERATIONS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * Checks a password against the stored value.
     * Old plain-text values (from before hashing existed) are still accepted so they can be
     * upgraded to a hash right after a successful login.
     */
    static boolean verify(char[] password, String stored) {
        if (stored == null) return false;

        if (!isHashed(stored)) {
            byte[] a = new String(password).getBytes(StandardCharsets.UTF_8);
            byte[] b = stored.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(a, b);
        }

        String[] p = stored.split("\\$");
        if (p.length != 4) return false;
        try {
            int iterations = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Spends the same time as a real check, so "unknown username" can't be told apart by timing. */
    static void burnTime(char[] password) {
        derive(password, new byte[SALT_BYTES], ITERATIONS);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available on this Java runtime", e);
        } finally {
            spec.clearPassword();
        }
    }
}