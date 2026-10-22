package com.trimaxsystem;

import java.util.Properties;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * SMTP delivery for employee password-setup and password-reset codes.
 *
 * Configure the SMTP values as environment variables; credentials are never stored in source control.
 */
public final class EmailService {
    private static final String HOST = System.getenv("TRIMAX_SMTP_HOST");
    private static final String PORT = System.getenv().getOrDefault("TRIMAX_SMTP_PORT", "587");
    private static final String USER = System.getenv("TRIMAX_SMTP_USER");
    private static final String PASSWORD = System.getenv("TRIMAX_SMTP_PASSWORD");
    private static final String FROM = System.getenv("TRIMAX_SMTP_FROM");
    private static final String STARTTLS = System.getenv().getOrDefault("TRIMAX_SMTP_STARTTLS", "true");

    private EmailService() { }

    public static boolean isConfigured() {
        return hasText(HOST) && hasText(PORT) && hasText(USER)
                && hasText(PASSWORD) && hasText(FROM);
    }

    public static void sendSetupCode(String recipient, String fullName, String username, String code)
            throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("SMTP is not configured. Set TRIMAX_SMTP_HOST, "
                    + "TRIMAX_SMTP_PORT, TRIMAX_SMTP_USER, TRIMAX_SMTP_PASSWORD and TRIMAX_SMTP_FROM.");
        }

        Properties properties = new Properties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.host", HOST);
        properties.put("mail.smtp.port", PORT);
        properties.put("mail.smtp.starttls.enable", STARTTLS);
        properties.put("mail.smtp.starttls.required", STARTTLS);
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");

        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(USER, PASSWORD);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(FROM));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient, false));
        boolean setup = hasText(username);
        message.setSubject(setup ? "TRIMAX account password setup" : "TRIMAX password reset code", "UTF-8");
        String greeting = hasText(fullName) ? fullName.trim() : "TRIMAX user";
        String body;
        if (setup) {
            body = "Hello " + greeting + ",\n\n"
                    + "An administrator created your TRIMAX employee account.\n\n"
                    + "Username: " + username + "\n"
                    + "One-time password setup code: " + code + "\n\n"
                    + "Enter this code in the TRIMAX password setup/reset screen to choose your own password. "
                    + "The code expires in 15 minutes and can only be used once.\n\n"
                    + "If you were not expecting this account, contact your TRIMAX administrator.\n\n"
                    + "TRIMAX";
        } else {
            body = "Hello " + greeting + ",\n\n"
                    + "Use this one-time code to reset your TRIMAX password: " + code + "\n\n"
                    + "The code expires in 15 minutes and can only be used once. "
                    + "If you did not request a reset, contact your TRIMAX administrator.\n\n"
                    + "TRIMAX";
        }
        message.setText(body, "UTF-8");
        Transport.send(message);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
