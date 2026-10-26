package com.trimaxsystem;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Random;

/**
 * The first phone password of a new Agent or Driver: the first name and 4 random characters, e.g.
 * "Kenjie-7Q4M". Easy to read out or type, but not guessable from the name (about 700,000 possibilities),
 * and the app asks for a new password at the first sign-in. It is shown once to the admin and emailed;
 * TRIMAX does not keep it.
 */
public final class TemporaryPassword {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O/1/I
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPassword() {
    }

    public static String generate(String fullName) {
        return generate(fullName, RANDOM);
    }

    static String generate(String fullName, Random random) {
        String first = fullName == null ? "" : fullName.trim().split("\\s+", 2)[0];
        String letters = Normalizer.normalize(first, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replaceAll("[^A-Za-z]", "");
        if (letters.isEmpty()) letters = "Trimax";
        if (letters.length() > 12) letters = letters.substring(0, 12);
        String name = letters.substring(0, 1).toUpperCase(Locale.ROOT) + letters.substring(1).toLowerCase(Locale.ROOT);
        int suffixLength = Math.max(4, 8 - name.length() - 1);          // at least 8 characters in all
        String suffix;
        do {
            StringBuilder sb = new StringBuilder(suffixLength);
            for (int i = 0; i < suffixLength; i++) sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
            suffix = sb.toString();
        } while (!suffix.matches(".*[0-9].*"));                          // the password rules need a number
        return name + "-" + suffix;
    }
}
