package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailSettingsTest {

    /** env = environment variables, file = trimax.properties; the environment wins, like the app. */
    private static EmailService.Settings read(Map<String, String> env, Map<String, String> file) {
        return EmailService.read((envName, key) -> {
            String v = env.get(envName);
            return v != null && !v.isBlank() ? v : file.get(key);
        });
    }

    private static Map<String, String> gmailFile() {
        Map<String, String> f = new HashMap<>();
        f.put("smtp.host", "smtp.gmail.com");
        f.put("smtp.user", "trimax.example@gmail.com");
        f.put("smtp.password", "abcdefghijklmnop");
        return f;
    }

    @Test
    void threeLinesInTrimaxPropertiesAreEnoughForGmail() {
        EmailService.Settings s = read(new HashMap<>(), gmailFile());
        assertTrue(s.complete(), s.missing.toString());
        assertEquals("587", s.port);
        assertTrue(s.startTls);
        assertFalse(s.ssl);
        assertEquals("trimax.example@gmail.com", s.from);   // sender defaults to the sign-in address
    }

    @Test
    void nothingSetListsWhatIsMissingInOrder() {
        EmailService.Settings s = read(new HashMap<>(), new HashMap<>());
        assertFalse(s.complete());
        assertEquals(java.util.Arrays.asList("smtp.host", "smtp.user", "smtp.password", "smtp.from"), s.missing);
    }

    @Test
    void blankValuesCountAsMissing() {
        Map<String, String> f = gmailFile();
        f.put("smtp.password", "   ");
        assertEquals(java.util.Collections.singletonList("smtp.password"), read(new HashMap<>(), f).missing);
    }

    @Test
    void environmentVariablesStillWinOverTheFile() {
        Map<String, String> env = new HashMap<>();
        env.put("TRIMAX_SMTP_HOST", "smtp.office365.com");
        env.put("TRIMAX_SMTP_FROM", "noreply@example.test");
        EmailService.Settings s = read(env, gmailFile());
        assertEquals("smtp.office365.com", s.host);
        assertEquals("noreply@example.test", s.from);
        assertEquals("trimax.example@gmail.com", s.user);   // not in the environment: taken from the file
    }

    @Test
    void port465UsesSslAndStartTlsCanBeTurnedOff() {
        Map<String, String> f = gmailFile();
        f.put("smtp.port", "465");
        EmailService.Settings ssl = read(new HashMap<>(), f);
        assertTrue(ssl.ssl);
        assertFalse(ssl.startTls);

        f.put("smtp.port", "2525");
        f.put("smtp.starttls", "false");
        EmailService.Settings plain = read(new HashMap<>(), f);
        assertFalse(plain.ssl);
        assertFalse(plain.startTls);
    }

    @Test
    void aPortThatIsNotANumberIsReported() {
        Map<String, String> f = gmailFile();
        f.put("smtp.port", "587a");
        EmailService.Settings s = read(new HashMap<>(), f);
        assertFalse(s.complete());
        assertTrue(s.missing.get(0).startsWith("smtp.port"));
    }

    @Test
    void theDescriptionNeverShowsThePassword() {
        EmailService.Settings s = read(new HashMap<>(), gmailFile());
        assertFalse(s.describe().contains("abcdefghijklmnop"));
        assertFalse(s.toString().contains("abcdefghijklmnop"));
        assertTrue(s.describe().contains("smtp.gmail.com:587"));
    }

    @Test
    void failuresAreExplainedInPlainWords() {
        EmailService.Settings s = read(new HashMap<>(), gmailFile());
        String auth = EmailService.explain(new javax.mail.AuthenticationFailedException(
                "535-5.7.8 Username and Password not accepted"), s);
        assertTrue(auth.contains("App Password"), auth);
        assertFalse(auth.contains("abcdefghijklmnop"));

        String host = EmailService.explain(new javax.mail.MessagingException("Unknown SMTP host: smtp.gmial.com",
                new java.net.UnknownHostException("smtp.gmial.com")), s);
        assertTrue(host.contains("\"smtp.gmial.com\" was not found"), host);

        String refused = EmailService.explain(new javax.mail.MessagingException("Could not connect",
                new java.net.ConnectException("Connection refused")), s);
        assertTrue(refused.contains("smtp.gmail.com:587"), refused);
    }
}
