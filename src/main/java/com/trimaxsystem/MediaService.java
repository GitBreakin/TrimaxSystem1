package com.trimaxsystem;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Loads one private photo (duty attendance photo or delivery proof) for the signed-in admin.
 *
 * No storage key is kept on this PC. The database (we connect as its owner) issues a one-time ticket
 * for that one photo (trimax_private.issue_media_ticket, which also writes an audit row); the Supabase
 * Edge Function "trimax-admin-media" exchanges the ticket for the photo. See
 * sql/20261025_05_admin_media_tickets.sql and supabase/functions/trimax-admin-media.
 */
public final class MediaService {

    public static final String DUTY_PHOTOS = "duty-photos";
    public static final String DELIVERY_PROOFS = "delivery-proofs";
    private static final String FUNCTION = "/functions/v1/trimax-admin-media";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Pattern ERROR_TEXT = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]{1,300})\"");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** The last photos opened, so going back and forth between rows does not download them again. */
    private static final Map<String, BufferedImage> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > 30;
        }
    };

    private MediaService() {
    }

    /** A problem the admin can act on; the message is shown as is. */
    public static final class MediaException extends Exception {
        public MediaException(String message) {
            super(message);
        }
    }

    /** Downloads the photo (or returns it from the small cache). Call off the Swing thread. */
    public static BufferedImage load(String bucket, String path) throws MediaException {
        String key = bucket + "/" + path;
        synchronized (CACHE) {
            BufferedImage cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        BufferedImage image = decode(fetch(ticket(bucket, path)));
        synchronized (CACHE) {
            CACHE.put(key, image);
        }
        return image;
    }

    private static String ticket(String bucket, String path) throws MediaException {
        AuthService.Session admin;
        try {
            admin = AuthService.requireAdmin();
        } catch (AuthService.AuthException e) {
            throw new MediaException(e.getMessage());
        }
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select trimax_private.issue_media_ticket(?, ?, ?)::text")) {
            ps.setString(1, admin.username);
            ps.setString(2, bucket);
            ps.setString(3, path);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            if ("42883".equals(e.getSQLState()) || "3F000".equals(e.getSQLState())) {
                throw new MediaException("Photo viewing is not set up on the server yet "
                        + "(database update 20261025_05_admin_media_tickets).");
            }
            if (e.getSQLState() != null && e.getSQLState().startsWith("TM")) {
                throw new MediaException(firstLine(e.getMessage()));
            }
            throw new MediaException("Could not prepare the photo: " + firstLine(e.getMessage()));
        }
    }

    /** Exchanges a ticket for the photo bytes (package-private for tests). */
    static byte[] fetch(String ticket) throws MediaException {
        String url;
        try {
            url = DatabaseConnection.supabaseUrl() + FUNCTION;
        } catch (SQLException e) {
            throw new MediaException(e.getMessage());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .header("Accept", "image/jpeg, application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ticket\":\"" + ticket + "\"}"))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new MediaException("No connection to the photo service. Check the internet connection and try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaException("Cancelled.");
        }
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (response.statusCode() == 200) {
            if (body.length == 0 || body.length > MAX_BYTES) throw new MediaException("The photo file is empty or too large.");
            return body;
        }
        String text = new String(body, 0, Math.min(body.length, 2000), java.nio.charset.StandardCharsets.UTF_8);
        Matcher m = ERROR_TEXT.matcher(text);
        if (m.find()) throw new MediaException(m.group(1));
        if (response.statusCode() == 404) {
            throw new MediaException("Photo viewing is not set up on the server yet (Edge Function trimax-admin-media).");
        }
        throw new MediaException("The photo service answered " + response.statusCode() + ". Try again.");
    }

    private static BufferedImage decode(byte[] jpeg) throws MediaException {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (image == null) throw new MediaException("The file is not a readable photo.");
            return image;
        } catch (IOException e) {
            throw new MediaException("The file is not a readable photo.");
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).replaceFirst("^ERROR:\\s*", "");
    }
}
