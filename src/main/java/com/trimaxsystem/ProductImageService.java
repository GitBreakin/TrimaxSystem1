package com.trimaxsystem;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Product pictures (Stage F). The admin picks a JPG or PNG; it is checked, scaled to at most 1024 px and
 * saved as one JPEG (under 2 MB), uploaded to the public "product-images" bucket through the Edge
 * Function "trimax-admin-media" with a one-time ticket from the database, and then attached to the
 * product (products.image_path). The previous picture is deleted afterwards. No storage key is ever on
 * this PC. See sql/20261025_06_product_images.sql.
 */
public final class ProductImageService {

    public static final String BUCKET = "product-images";
    static final long MAX_SOURCE_BYTES = 15L * 1024 * 1024;
    static final int MAX_SIDE = 1024;
    static final int MIN_SIDE = 64;
    static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final String FUNCTION = "/functions/v1/trimax-admin-media";
    private static final Pattern FIELD = Pattern.compile("\"(ticket|path|error)\"\\s*:\\s*\"([^\"]{1,300})\"");
    private static final Pattern PRODUCT_PATH = Pattern.compile("products/[0-9]{1,9}/[A-Za-z0-9_-]{1,80}\\.jpg");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Recently shown pictures (thumbnails in the inventory table, the preview box). */
    private static final Map<String, BufferedImage> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > 80;
        }
    };

    private ProductImageService() {
    }

    /** A problem the admin can act on; the message is shown as is. */
    public static final class ImageException extends Exception {
        public ImageException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ choosing a file

    /** True for the file types the picker offers (by name; the content is checked by prepare()). */
    public static boolean looksLikePicture(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
    }

    /**
     * Checks the chosen file and returns the JPEG that will be uploaded (at most 1024 px on the long side,
     * transparent parts on white, under 2 MB). Safe to call off the Swing thread.
     */
    public static byte[] prepare(File f) throws ImageException {
        if (f == null || !f.isFile()) throw new ImageException("That file no longer exists.");
        if (!looksLikePicture(f)) throw new ImageException("Choose a JPG or PNG picture.");
        long size = f.length();
        if (size == 0) throw new ImageException("That file is empty.");
        if (size > MAX_SOURCE_BYTES) throw new ImageException("That picture is larger than 15 MB. Choose a smaller one.");
        BufferedImage src;
        try {
            src = ImageIO.read(f);
        } catch (IOException e) {
            src = null;
        }
        if (src == null) throw new ImageException("That file is not a picture this program can read. Use JPG or PNG.");
        return toJpeg(src);
    }

    /** Scales and encodes a picture for upload (package-private for tests). */
    static byte[] toJpeg(BufferedImage src) throws ImageException {
        if (src.getWidth() < MIN_SIDE || src.getHeight() < MIN_SIDE) {
            throw new ImageException("That picture is too small (at least " + MIN_SIDE + " x " + MIN_SIDE + " pixels).");
        }
        int side = MAX_SIDE;
        float[] qualities = {0.85f, 0.75f, 0.65f};
        for (int attempt = 0; attempt < 3; attempt++) {
            BufferedImage rgb = scaledRgb(src, side);
            for (float q : qualities) {
                byte[] out = encode(rgb, q);
                if (out.length <= MAX_UPLOAD_BYTES) return out;
            }
            side = side * 3 / 4;
        }
        throw new ImageException("That picture could not be made smaller than 2 MB.");
    }

    private static BufferedImage scaledRgb(BufferedImage src, int maxSide) {
        double scale = Math.min(1.0, maxSide / (double) Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);                  // transparent PNG areas become white, not black
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] encode(BufferedImage rgb, float quality) throws ImageException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new ImageException("This computer cannot write JPEG pictures.");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(rgb, null, null), p);
        } catch (IOException e) {
            throw new ImageException("The picture could not be prepared: " + e.getMessage());
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    public static BufferedImage decode(byte[] jpeg) {
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ server

    /** Uploads the picture, attaches it to the product and deletes the previous one. Returns the new path. */
    public static String upload(int productId, byte[] jpeg) throws ImageException {
        String admin = adminUsername();
        String[] ticketAndPath = ticket(admin, productId, "upload", null);
        String url = functionUrl();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "image/jpeg")
                .header("x-trimax-ticket", ticketAndPath[0])
                .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                .build();
        HttpResponse<byte[]> r = send(request);
        if (r.statusCode() != 200) throw failure(r);
        String path = ticketAndPath[1];

        String old;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select trimax_private.set_product_image(?, ?, ?)")) {
            ps.setString(1, admin);
            ps.setInt(2, productId);
            ps.setString(3, path);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                old = rs.getString(1);
            }
        } catch (SQLException e) {
            deleteQuietly(admin, productId, path);   // uploaded but not attached: do not leave it behind
            throw sql(e, "The picture was uploaded but could not be attached");
        }
        if (old != null) deleteQuietly(admin, productId, old);
        return path;
    }

    /** Takes the picture off the product and deletes it. */
    public static void remove(int productId) throws ImageException {
        String admin = adminUsername();
        String old;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("select trimax_private.set_product_image(?, ?, null)")) {
            ps.setString(1, admin);
            ps.setInt(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                old = rs.getString(1);
            }
        } catch (SQLException e) {
            throw sql(e, "The picture could not be removed");
        }
        if (old != null) deleteQuietly(admin, productId, old);
    }

    /** Deletes the picture of a product that was just deleted (or replaced). Never throws. */
    public static void deleteQuietly(String admin, int productId, String path) {
        try {
            if (admin == null) admin = adminUsername();
            String[] t = ticket(admin, productId, "delete", path);
            HttpRequest request = HttpRequest.newBuilder(URI.create(functionUrl()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"ticket\":\"" + t[0] + "\"}"))
                    .build();
            send(request);
        } catch (Exception ignored) {
            // A left-over file in the bucket is harmless; nothing points at it any more.
        }
    }

    /** The public address of a product picture (the bucket is public; no key needed to read). */
    public static String publicUrl(String path) throws ImageException {
        if (path == null || !PRODUCT_PATH.matcher(path).matches()) throw new ImageException("No picture.");
        try {
            return DatabaseConnection.supabaseUrl() + "/storage/v1/object/public/" + BUCKET + "/" + path;
        } catch (SQLException e) {
            throw new ImageException(e.getMessage());
        }
    }

    /** Downloads a product picture (or takes it from the cache). Call off the Swing thread. */
    public static BufferedImage load(String path) throws ImageException {
        synchronized (CACHE) {
            BufferedImage cached = CACHE.get(path);
            if (cached != null) return cached;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(publicUrl(path)))
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<byte[]> r = send(request);
        if (r.statusCode() != 200) {
            throw new ImageException(r.statusCode() == 400 || r.statusCode() == 404
                    ? "The picture is missing on the server." : "The picture could not be loaded (" + r.statusCode() + ").");
        }
        BufferedImage img = decode(r.body());
        if (img == null) throw new ImageException("The picture on the server is not readable.");
        synchronized (CACHE) {
            CACHE.put(path, img);
        }
        return img;
    }

    /** Thumbnail for the table, if it is already downloaded (null otherwise). */
    public static BufferedImage cached(String path) {
        synchronized (CACHE) {
            return CACHE.get(path);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static String adminUsername() throws ImageException {
        try {
            return AuthService.requireAdmin().username;
        } catch (AuthService.AuthException e) {
            throw new ImageException(e.getMessage());
        }
    }

    /** {ticket, path} for one upload or delete. */
    private static String[] ticket(String admin, int productId, String action, String path) throws ImageException {
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "select trimax_private.issue_product_image_ticket(?, ?, ?, ?)::text")) {
            ps.setString(1, admin);
            ps.setInt(2, productId);
            ps.setString(3, action);
            ps.setString(4, path);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String json = rs.getString(1);
                String ticket = null, p = null;
                Matcher m = FIELD.matcher(json == null ? "" : json);
                while (m.find()) {
                    if ("ticket".equals(m.group(1))) ticket = m.group(2);
                    if ("path".equals(m.group(1))) p = m.group(2);
                }
                if (ticket == null || p == null) throw new ImageException("The server gave no upload ticket.");
                return new String[]{ticket, p};
            }
        } catch (SQLException e) {
            throw sql(e, "Could not prepare the picture");
        }
    }

    static String functionUrl() throws ImageException {
        try {
            return DatabaseConnection.supabaseUrl() + FUNCTION;
        } catch (SQLException e) {
            throw new ImageException(e.getMessage());
        }
    }

    private static HttpResponse<byte[]> send(HttpRequest request) throws ImageException {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ImageException("No connection to the server. Check the internet connection and try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageException("Cancelled.");
        }
    }

    /** The function's own message when it sent one, otherwise a plain explanation. */
    static ImageException failure(HttpResponse<byte[]> r) {
        byte[] body = r.body() == null ? new byte[0] : r.body();
        String text = new String(body, 0, Math.min(body.length, 2000), StandardCharsets.UTF_8);
        Matcher m = FIELD.matcher(text);
        while (m.find()) {
            if ("error".equals(m.group(1))) return new ImageException(m.group(2));
        }
        if (r.statusCode() == 404) {
            return new ImageException("Product pictures are not set up on the server yet (Edge Function trimax-admin-media).");
        }
        return new ImageException("The server answered " + r.statusCode() + ". Try again.");
    }

    private static ImageException sql(SQLException e, String what) {
        String state = e.getSQLState();
        if ("42883".equals(state) || "3F000".equals(state) || "42703".equals(state)) {
            return new ImageException("Product pictures are not set up on the server yet (database update 20261025_06_product_images).");
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        int nl = msg.indexOf('\n');
        msg = (nl < 0 ? msg : msg.substring(0, nl)).replaceFirst("^ERROR:\\s*", "");
        return new ImageException(state != null && state.startsWith("TM") ? msg : what + ": " + msg);
    }
}
