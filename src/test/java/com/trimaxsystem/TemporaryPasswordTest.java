package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemporaryPasswordTest {

    @Test
    void firstNameThenFourRandomCharacters() {
        String p = TemporaryPassword.generate("Kenjie Dela Cruz", new Random(1));
        assertTrue(p.matches("Kenjie-[A-HJ-NP-Z2-9]{4}"), p);
    }

    @Test
    void alwaysMeetsThePasswordRules() {
        Random r = new Random(7);
        for (String name : new String[]{"Al", "José", "  maría  santos ", "O'Neil", "李", "", null, "Maximilianoxyzabc"}) {
            for (int i = 0; i < 200; i++) {
                String p = TemporaryPassword.generate(name, r);
                assertNull(AuthService.validatePassword(p.toCharArray()), name + " -> " + p);
                assertTrue(p.length() >= 8 && p.length() <= 20, p);
                assertTrue(p.matches("[A-Za-z]+-[A-Z2-9]+"), p);   // plain ASCII, easy to type
            }
        }
        assertTrue(TemporaryPassword.generate("José", r).startsWith("Jose-"));
        assertTrue(TemporaryPassword.generate("李", r).startsWith("Trimax-"));
        assertTrue(TemporaryPassword.generate("Al", r).matches("Al-[A-Z2-9]{5}"));
    }

    @Test
    void notGuessableFromTheName() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) seen.add(TemporaryPassword.generate("Kenjie"));
        assertTrue(seen.size() > 990, "random part repeats too often: " + seen.size());
    }
}
