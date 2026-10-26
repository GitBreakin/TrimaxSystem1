package com.trimaxsystem;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Creates the Supabase sign-in (auth.users) of an Agent or Driver and links it to the TRIMAX account, so
 * nobody has to add users in the Supabase Dashboard.
 *
 * No Supabase key is kept on this PC. The database (we connect as its owner) checks that the signed-in
 * admin is an Active Admin, queues the request and returns a one-time ticket
 * (trimax_private.issue_provisioning_ticket); the Edge Function "trimax-accounts", which holds the secret
 * key on the Supabase server, exchanges the ticket for the new sign-in. See
 * sql/20261026_01_employee_mobile_sign_in.sql and supabase/functions/trimax-accounts.
 *
 * The person then chooses the phone password with the setup code from the email (Android: "Set up or
 * reset password").
 */
public final class MobileProvisioning {

    private static final String FUNCTION = "/functions/v1/trimax-accounts";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private MobileProvisioning() {
    }

    /** What happened. {@link #ready()} is true when the person can now set a phone password and sign in. */
    public static final class Result {
        public final String status;    // invited | linked | failed | busy | not_installed | error
        public final String message;   // for the admin; never contains a secret

        Result(String status, String message) {
            this.status = status;
            this.message = message;
        }

        public boolean ready() {
            return "invited".equals(status) || "linked".equals(status);
        }

        /** One line for the admin. */
        public String describe() {
            switch (status) {
                case "invited":
                    return "Mobile sign-in created. The person sets the phone password with the code from the email "
                            + "(TRIMAX app > Set up or reset password).";
                case "linked":
                    return "Mobile sign-in linked" + (message == null ? "." : ": " + message);
                default:
                    return message == null ? "The mobile sign-in was not created." : message;
            }
        }
    }

    /** True when the database part is installed (sql/20261026_01). */
    public static boolean isInstalled(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "select to_regprocedure('trimax_private.issue_provisioning_ticket(text,text)') is not null");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /**
     * Creates (or, after an interrupted earlier try, links) the mobile sign-in of one account.
     * Call off the Swing thread. Problems come back as a Result, not as an exception.
     */
    public static Result create(String username) {
        AuthService.Session admin;
        try {
            admin = AuthService.requireAdmin();
        } catch (AuthService.AuthException e) {
            return new Result("error", e.getMessage());
        }
        String ticket;
        try (Connection conn = DatabaseConnection.getConnection()) {
            if (!isInstalled(conn)) {
                return new Result("not_installed", "Automatic mobile sign-ins are not set up on the server yet "
                        + "(database update 20261026_01). Add the user in Supabase and use \"Link mobile sign-in\".");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "select trimax_private.issue_provisioning_ticket(?, ?)::text")) {
                ps.setString(1, admin.username);
                ps.setString(2, username);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    ticket = rs.getString(1);
                }
            }
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("TM")) {
                return new Result("failed", firstLine(e.getMessage()));
            }
            return new Result("error", "Could not reach the database: " + firstLine(e.getMessage()));
        }
        return send(ticket);
    }

    /** Exchanges the ticket with the Edge Function (package-private for tests). */
    static Result send(String ticket) {
        String url;
        try {
            url = DatabaseConnection.supabaseUrl() + FUNCTION;
        } catch (SQLException e) {
            return new Result("error", e.getMessage());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"provision\",\"ticket\":\"" + ticket + "\"}"))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Result("error", "No connection to the sign-in service. The account was saved; open it and press "
                    + "\"Create mobile sign-in\" when the internet is back.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("error", "Cancelled.");
        }
        String body = response.body() == null ? "" : response.body();
        if (body.length() > 4000) body = body.substring(0, 4000);
        String status = jsonString(body, "status");
        String message = jsonString(body, "message");
        String error = jsonString(body, "error");
        if (response.statusCode() == 200 && status != null) return new Result(status, message);
        if (status != null) return new Result(status, message != null ? message : error);
        if (error != null) return new Result("error", error);
        if (response.statusCode() == 404) {
            return new Result("not_installed", "The sign-in service is not set up on the server yet (Edge Function trimax-accounts).");
        }
        return new Result("error", "The sign-in service answered " + response.statusCode() + ". Try again.");
    }

    /** The value of a top-level string field in a small JSON object, or null (handles escapes). */
    static String jsonString(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int at = json.indexOf(key);
        while (at >= 0) {
            int i = at + key.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                if (i >= json.length() || json.charAt(i) != '"') return null;   // null or not a string
                StringBuilder sb = new StringBuilder();
                for (i++; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (c == '"') return sb.toString();
                    if (c == '\\' && i + 1 < json.length()) {
                        char e = json.charAt(++i);
                        switch (e) {
                            case 'n': sb.append('\n'); break;
                            case 't': sb.append('\t'); break;
                            case 'r': sb.append('\r'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'u':
                                if (i + 4 < json.length()) {
                                    try {
                                        sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                        i += 4;
                                    } catch (NumberFormatException ex) {
                                        return null;
                                    }
                                }
                                break;
                            default: sb.append(e);   // \" \\ \/
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return null;
            }
            at = json.indexOf(key, at + 1);
        }
        return null;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).replaceFirst("^ERROR:\\s*", "").trim();
    }
}
