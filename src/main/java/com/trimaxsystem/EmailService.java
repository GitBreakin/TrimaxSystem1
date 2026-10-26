package com.trimaxsystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.function.BiFunction;
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
 * Each value comes from its environment variable (TRIMAX_SMTP_HOST, ...) or, when that is not set, from
 * the private trimax.properties file next to the database settings (smtp.host, ...). See
 * SMTP_EMAIL_SETUP.md. Credentials are never stored in source control and never shown or logged.
 */
public final class EmailService {

    /** The SMTP values in use. The password is kept out of {@link #describe()} and toString. */
    public static final class Settings {
        public final String host, port, user, from;
        final String password;
        public final boolean ssl, startTls;
        /** trimax.properties keys that still need a value (or have a wrong one), in a fixed order. */
        public final List<String> missing;

        Settings(String host, String port, String user, String password, String from, boolean ssl,
                 boolean startTls, List<String> missing) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
            this.from = from;
            this.ssl = ssl;
            this.startTls = startTls;
            this.missing = Collections.unmodifiableList(missing);
        }

        public boolean complete() {
            return missing.isEmpty();
        }

        /** For the screen, e.g. "smtp.gmail.com:587, STARTTLS, sign-in trimax@gmail.com". No password. */
        public String describe() {
            String security = ssl ? "SSL" : startTls ? "STARTTLS" : "NOT encrypted";
            return host + ":" + port + ", " + security + ", sign-in " + user
                    + (from != null && !from.equalsIgnoreCase(user) ? ", sender " + from : "");
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    private EmailService() { }

    /** Reads the settings now (environment variable first, then trimax.properties). */
    public static Settings settings() {
        return read(DatabaseConnection::setting);
    }

    /**
     * Builds the settings from a lookup of (environment variable, trimax.properties key) to value.
     * Defaults: port 587 with STARTTLS; port 465 means SSL; the sender is the sign-in address.
     */
    static Settings read(BiFunction<String, String, String> setting) {
        List<String> missing = new ArrayList<>();
        String host = clean(setting.apply("TRIMAX_SMTP_HOST", "smtp.host"));
        String port = clean(setting.apply("TRIMAX_SMTP_PORT", "smtp.port"));
        String user = clean(setting.apply("TRIMAX_SMTP_USER", "smtp.user"));
        String password = clean(setting.apply("TRIMAX_SMTP_PASSWORD", "smtp.password"));
        String from = clean(setting.apply("TRIMAX_SMTP_FROM", "smtp.from"));
        String tls = clean(setting.apply("TRIMAX_SMTP_STARTTLS", "smtp.starttls"));

        if (host == null) missing.add("smtp.host");
        if (port == null) port = "587";
        int portNo;
        try {
            portNo = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            portNo = -1;
        }
        if (portNo < 1 || portNo > 65535) missing.add("smtp.port (a number such as 587)");
        if (user == null) missing.add("smtp.user");
        if (password == null) missing.add("smtp.password");
        if (from == null && user != null && user.contains("@")) from = user;
        if (from == null) missing.add("smtp.from");

        boolean ssl = portNo == 465;
        boolean startTls = !ssl && (tls == null || !"false".equalsIgnoreCase(tls));
        return new Settings(host, port, user, password, from, ssl, startTls, missing);
    }

    public static boolean isConfigured() {
        return settings().complete();
    }

    /**
     * What to tell an administrator when email is not set up on this computer (no secrets in it).
     * @param what what cannot happen, e.g. "the new employee cannot be sent a setup code"
     */
    public static String notSetUpMessage(String what) {
        Settings s = settings();
        String file = DatabaseConnection.settingsFile();
        return "Email is not set up on this computer, so " + what + ".\n"
                + "Missing: " + String.join(", ", s.missing) + "\n"
                + "Add them to " + (file == null ? "trimax.properties" : file)
                + " (see SMTP_EMAIL_SETUP.md), then restart TRIMAX.";
    }

    public static void sendSetupCode(String recipient, String fullName, String username, String code)
            throws Exception {
        boolean setup = hasText(username);
        String greeting = hasText(fullName) ? fullName.trim() : "TRIMAX user";
        String body;
        if (setup) {
            body = "Hello " + greeting + ",\n\n"
                    + "Your TRIMAX employee account is ready.\n\n"
                    + "Username: " + username + "\n"
                    + "Sign-in email: " + recipient + "\n"
                    + "One-time setup code: " + code + "\n\n"
                    + "To choose your password for the TRIMAX mobile app:\n"
                    + "1. Open the TRIMAX app and tap \"Set up or reset password\" on the sign-in screen.\n"
                    + "2. Enter this email address, the code above and your new password.\n"
                    + "3. Sign in with this email address and your new password.\n\n"
                    + "The code works once and expires in 24 hours. If it expired, ask your administrator for a new one.\n\n"
                    + "If you were not expecting this account, contact your TRIMAX administrator.\n\n"
                    + "TRIMAX";
        } else {
            body = "Hello " + greeting + ",\n\n"
                    + "Use this one-time code to reset your TRIMAX password: " + code + "\n\n"
                    + "The code expires in 15 minutes and can only be used once. "
                    + "If you did not request a reset, contact your TRIMAX administrator.\n\n"
                    + "TRIMAX";
        }
        send(recipient, setup ? "Your TRIMAX account: set your password" : "TRIMAX password reset code", body);
    }

    /**
     * The new employee's first phone password. The app asks for a new password at the first sign-in.
     */
    public static void sendTemporaryPassword(String recipient, String fullName, String username, String temporaryPassword)
            throws Exception {
        String greeting = hasText(fullName) ? fullName.trim() : "TRIMAX user";
        send(recipient, "Your TRIMAX account",
                "Hello " + greeting + ",\n\n"
                        + "Your TRIMAX employee account is ready.\n\n"
                        + "Username: " + username + "\n"
                        + "Sign-in email: " + recipient + "\n"
                        + "Temporary password: " + temporaryPassword + "\n\n"
                        + "1. Open the TRIMAX app on your phone.\n"
                        + "2. Sign in with this email address and the temporary password (capital letters count).\n"
                        + "3. The app then asks you to choose your own password.\n\n"
                        + "If the temporary password does not work, ask your administrator.\n"
                        + "If you were not expecting this account, contact your TRIMAX administrator.\n\n"
                        + "TRIMAX");
    }

    /** A short test message, so the settings can be checked without creating an account. */
    public static void sendTest(String recipient) throws Exception {
        send(recipient, "TRIMAX test email",
                "This is a test from the TRIMAX desktop app (System Settings > Email).\n\n"
                        + "If you can read this, employee setup codes and password reset codes can be sent.\n\n"
                        + "TRIMAX");
    }

    private static void send(String recipient, String subject, String body) throws Exception {
        Settings s = settings();
        if (!s.complete()) {
            throw new IllegalStateException(notSetUpMessage("the email cannot be sent"));
        }

        Properties properties = new Properties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.host", s.host);
        properties.put("mail.smtp.port", s.port);
        if (s.ssl) {
            properties.put("mail.smtp.ssl.enable", "true");
        } else {
            properties.put("mail.smtp.starttls.enable", String.valueOf(s.startTls));
            properties.put("mail.smtp.starttls.required", String.valueOf(s.startTls));
        }
        if (s.ssl || s.startTls) {
            properties.put("mail.smtp.ssl.checkserveridentity", "true");   // the certificate must name the host
            String protocols = modernTlsProtocols();
            if (!protocols.isEmpty()) properties.put("mail.smtp.ssl.protocols", protocols);
        }
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");

        final String user = s.user;
        final String password = s.password;
        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, password);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(s.from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient, false));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        Transport.send(message);
    }

    /** TLS 1.2 and 1.3 where this Java has them (older mail defaults can pick protocols servers refuse). */
    private static String modernTlsProtocols() {
        try {
            List<String> supported = java.util.Arrays.asList(
                    javax.net.ssl.SSLContext.getDefault().getSupportedSSLParameters().getProtocols());
            List<String> use = new ArrayList<>();
            for (String p : new String[]{"TLSv1.2", "TLSv1.3"}) if (supported.contains(p)) use.add(p);
            return String.join(" ", use);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * A plain-language reason for a failed send, for the administrator. Never contains the password.
     */
    public static String explain(Throwable error) {
        return explain(error, settings());
    }

    static String explain(Throwable error, Settings s) {
        String server = s.host + ":" + s.port;
        for (Throwable t = error; t != null; t = next(t)) {
            String type = t.getClass().getName();
            if (type.equals("javax.mail.AuthenticationFailedException")) {
                return "The email server did not accept the sign-in for " + s.user + ". Check smtp.user and "
                        + "smtp.password. For Gmail, smtp.password must be a 16-letter App Password "
                        + "(Google Account > Security > 2-Step Verification > App passwords), not the normal password.";
            }
            if (t instanceof java.net.UnknownHostException) {
                String name = t.getMessage() == null || t.getMessage().isBlank() ? s.host : t.getMessage().trim();
                return "The email server \"" + name + "\" was not found. Check smtp.host and the internet connection.";
            }
            if (t instanceof java.net.ConnectException || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.NoRouteToHostException) {
                return "Could not connect to " + server + ". Check smtp.port (587 for STARTTLS, 465 for SSL) "
                        + "and that this network allows outgoing email.";
            }
            if (t instanceof java.io.IOException && t.getMessage() != null
                    && t.getMessage().startsWith("Can't verify identity of server")) {
                return "The email server's certificate is not for \"" + s.host + "\". Write smtp.host exactly as "
                        + "your email provider gives it (for Gmail: smtp.gmail.com), not an IP address.";
            }
            if (t instanceof javax.net.ssl.SSLException) {
                return "The secure connection to " + server + " failed: " + firstLine(t.getMessage());
            }
            if (type.equals("javax.mail.SendFailedException") || type.equals("javax.mail.internet.AddressException")) {
                return "The email address was not accepted: " + firstLine(t.getMessage());
            }
        }
        String deepest = null;
        for (Throwable t = error; t != null; t = next(t)) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) deepest = t.getMessage();
        }
        if (error != null && error.getMessage() != null && error.getMessage().contains("convert socket to TLS")) {
            return "The secure (STARTTLS) connection to " + server + " failed"
                    + (deepest == null || deepest.equals(error.getMessage()) ? "." : ": " + firstLine(deepest));
        }
        String m = error == null ? null : error.getMessage();
        return m == null || m.isBlank() ? (error == null ? "Unknown error" : error.getClass().getSimpleName())
                : firstLine(m);
    }

    private static Throwable next(Throwable t) {
        Throwable cause = t.getCause();
        if (cause == null && t instanceof javax.mail.MessagingException) {
            cause = ((javax.mail.MessagingException) t).getNextException();
        }
        return cause == t ? null : cause;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    private static String clean(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
