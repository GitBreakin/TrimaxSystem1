package com.trimaxsystem;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * A small OpenStreetMap viewer built only from Swing: drag to pan, mouse wheel or double-click to zoom,
 * click a marker to select it. With the map focused, arrow keys pan, + and - zoom, Home fits everything.
 *
 * It draws only what it is given. It never invents positions: no markers means an empty map, and the
 * owner can show a message, a banner or an info line on top of it for loading, empty and error states.
 * Marker SHAPE (not only colour) tells a current position (filled circle) from a stale one (ring).
 */
public class TileMapView extends JPanel {

    public enum Shape { SOLID, HOLLOW, DESTINATION }

    /** One thing to draw at a position. */
    public static final class Marker {
        public final String id;
        public final String label;
        public final String tooltipHtml;
        public final double lat, lon;
        public final Color color;
        public final Shape shape;

        public Marker(String id, String label, String tooltipHtml, double lat, double lon, Color color, Shape shape) {
            this.id = id;
            this.label = label;
            this.tooltipHtml = tooltipHtml;
            this.lat = lat;
            this.lon = lon;
            this.color = color;
            this.shape = shape;
        }
    }

    /** One legend row. A null colour makes it a small heading. */
    public static final class LegendItem {
        public final String text;
        public final Color color;
        public final Shape shape;

        private LegendItem(String text, Color color, Shape shape) {
            this.text = text;
            this.color = color;
            this.shape = shape;
        }

        public static LegendItem heading(String text) {
            return new LegendItem(text, null, null);
        }

        public static LegendItem item(String text, Color color, Shape shape) {
            return new LegendItem(text, color, shape);
        }
    }

    private static final int TILE = 256;
    private static final int MIN_ZOOM = 3;
    private static final int MAX_ZOOM = 18;

    private static final Color PLACEHOLDER    = Color.decode("#E2E8F0");
    private static final Color PLACEHOLDER_LN = Color.decode("#CBD5E1");
    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 11);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);

    // Shared tile cache and background loader
    private static final Map<String, BufferedImage> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, BufferedImage>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > 300;
                }
            });
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static volatile boolean tilesUnavailable = false;
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "map-tile-loader");
        t.setDaemon(true);
        return t;
    });

    private int zoom = 11;
    private double cx, cy;                      // map centre in world pixels at the current zoom
    private List<Marker> markers = new ArrayList<>();
    private List<LegendItem> legend = new ArrayList<>();
    private String selectedId = null;
    private String info = "";
    private String message = null;              // centred message that dims the map (loading, empty, error)
    private String banner = null;               // one-line notice at the top (for example "showing old data")
    private boolean bannerIsError = false;
    private double[] line = null;               // lat1, lon1, lat2, lon2
    private String lineLabel = "";
    private boolean pendingFit = false;
    private boolean needsInitialFit = true;
    private Consumer<String> onMarkerClick;
    private java.util.function.BiConsumer<Double, Double> onMapClick;   // latitude, longitude of a pick
    private boolean pickMode = false;

    public TileMapView() {
        setBackground(PLACEHOLDER);
        setLayout(null);
        setFocusable(true);
        setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        ToolTipManager.sharedInstance().registerComponent(this);

        // Default view (Metro Manila area) until something is drawn
        cx = lonToX(120.9842, zoom);
        cy = latToY(14.5995, zoom);

        add(makeMapButton("+", 10, 10, 32, 32, "Zoom in", () -> zoomAt(getWidth() / 2, getHeight() / 2, 1)));
        add(makeMapButton("−", 10, 46, 32, 32, "Zoom out", () -> zoomAt(getWidth() / 2, getHeight() / 2, -1)));
        add(makeMapButton("Fit", 10, 82, 32, 26, "Fit all markers", this::fitAll));

        MouseAdapter ma = new MouseAdapter() {
            private Point last;

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                last = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (last == null) return;
                cx -= e.getX() - last.x;
                cy -= e.getY() - last.y;
                last = e.getPoint();
                clampCenter();
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    zoomAt(e.getX(), e.getY(), 1);
                    return;
                }
                Marker m = markerAt(e.getX(), e.getY());
                if (m != null && onMarkerClick != null) onMarkerClick.accept(m.id);
                else if (m == null && pickMode && onMapClick != null && e.getClickCount() == 1) {
                    onMapClick.accept(yToLat(cy + e.getY() - getHeight() / 2.0, zoom),
                            xToLon(cx + e.getX() - getWidth() / 2.0, zoom));
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setCursor(markerAt(e.getX(), e.getY()) != null
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : pickMode ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(e -> zoomAt(e.getX(), e.getY(), e.getWheelRotation() < 0 ? 1 : -1));

        // Keyboard: pan, zoom, fit
        bindKey("LEFT", () -> pan(-80, 0));
        bindKey("RIGHT", () -> pan(80, 0));
        bindKey("UP", () -> pan(0, -80));
        bindKey("DOWN", () -> pan(0, 80));
        bindKey("PLUS", () -> zoomAt(getWidth() / 2, getHeight() / 2, 1));
        bindKey("EQUALS", () -> zoomAt(getWidth() / 2, getHeight() / 2, 1));
        bindKey("ADD", () -> zoomAt(getWidth() / 2, getHeight() / 2, 1));
        bindKey("MINUS", () -> zoomAt(getWidth() / 2, getHeight() / 2, -1));
        bindKey("SUBTRACT", () -> zoomAt(getWidth() / 2, getHeight() / 2, -1));
        bindKey("HOME", this::fitAll);
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
    }

    private void bindKey(String key, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "map-" + key);
        getActionMap().put("map-" + key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void pan(int dx, int dy) {
        cx += dx;
        cy += dy;
        clampCenter();
        repaint();
    }

    // =====================================================
    // PUBLIC API (call from the Swing thread)
    // =====================================================

    /** Called with (latitude, longitude) when the user clicks an empty spot while pick mode is on. */
    public void setOnMapClick(java.util.function.BiConsumer<Double, Double> cb) {
        this.onMapClick = cb;
    }

    /** While on, a single click on empty map reports its position (crosshair cursor). */
    public void setPickMode(boolean on) {
        this.pickMode = on;
        setCursor(on ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
    }

    public void setOnMarkerClick(Consumer<String> cb) {
        this.onMarkerClick = cb;
    }

    public void setMarkers(List<Marker> list) {
        this.markers = new ArrayList<>(list);
        if (needsInitialFit && !markers.isEmpty()) {
            needsInitialFit = false;
            pendingFit = true;      // fit once, then never fight the user's panning
        }
        repaint();
    }

    public void setLegend(List<LegendItem> items) {
        this.legend = new ArrayList<>(items);
        repaint();
    }

    public void setSelected(String id) {
        this.selectedId = id;
        repaint();
    }

    /** Dims the map and shows a message in the middle. Null removes it. */
    public void setMessage(String text) {
        this.message = text;
        repaint();
    }

    /** A one-line notice at the top of the map. Null removes it. */
    public void setBanner(String text, boolean error) {
        this.banner = text;
        this.bannerIsError = error;
        repaint();
    }

    public void setInfo(String text) {
        this.info = text;
        repaint();
    }

    /** A dashed straight line between two points, with a label. It is NOT a road route. */
    public void setLine(double lat1, double lon1, double lat2, double lon2, String label) {
        this.line = new double[]{lat1, lon1, lat2, lon2};
        this.lineLabel = label == null ? "" : label;
        repaint();
    }

    public void clearLine() {
        this.line = null;
        repaint();
    }

    /** Centres on a marker (zooming in a bit if the map is zoomed far out). */
    public void focusMarker(String id) {
        for (Marker m : markers) {
            if (m.id.equals(id)) {
                if (zoom < 13) zoom = 14;
                cx = lonToX(m.lon, zoom);
                cy = latToY(m.lat, zoom);
                clampCenter();
                repaint();
                return;
            }
        }
    }

    public void fitAll() {
        pendingFit = true;
        repaint();
    }

    // =====================================================
    // PROJECTION (Web Mercator)
    // =====================================================

    private static double worldSize(int z) {
        return TILE * Math.pow(2, z);
    }

    private static double lonToX(double lon, int z) {
        return (lon + 180.0) / 360.0 * worldSize(z);
    }

    private static double xToLon(double x, int z) {
        return x / worldSize(z) * 360.0 - 180.0;
    }

    private static double yToLat(double y, int z) {
        double n = Math.PI * (1.0 - 2.0 * y / worldSize(z));
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private static double latToY(double lat, int z) {
        double r = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * worldSize(z);
    }

    private void clampCenter() {
        double ws = worldSize(zoom);
        cx = Math.max(0, Math.min(ws, cx));
        cy = Math.max(0, Math.min(ws, cy));
    }

    private void zoomAt(int mx, int my, int dz) {
        int nz = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + dz));
        if (nz == zoom) return;
        double f = Math.pow(2, nz - zoom);
        double dx = mx - getWidth() / 2.0;
        double dy = my - getHeight() / 2.0;
        cx = (cx + dx) * f - dx;       // keeps the point under the cursor fixed
        cy = (cy + dy) * f - dy;
        zoom = nz;
        clampCenter();
        repaint();
    }

    private void doFit(int w, int h) {
        if (markers.isEmpty()) {
            zoom = 11;
            cx = lonToX(120.9842, zoom);
            cy = latToY(14.5995, zoom);
            clampCenter();
            return;
        }
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (Marker m : markers) {
            minLat = Math.min(minLat, m.lat);
            maxLat = Math.max(maxLat, m.lat);
            minLon = Math.min(minLon, m.lon);
            maxLon = Math.max(maxLon, m.lon);
        }
        int z = 16;
        for (; z > MIN_ZOOM; z--) {
            double spanX = lonToX(maxLon, z) - lonToX(minLon, z);
            double spanY = latToY(minLat, z) - latToY(maxLat, z);
            if (spanX <= w - 140 && spanY <= h - 140) break;
        }
        zoom = z;
        cx = (lonToX(minLon, z) + lonToX(maxLon, z)) / 2.0;
        cy = (latToY(minLat, z) + latToY(maxLat, z)) / 2.0;
        clampCenter();
    }

    // =====================================================
    // TILES
    // =====================================================

    private BufferedImage getTile(int z, int x, int y) {
        int n = 1 << z;
        String key = z + "/" + Math.floorMod(x, n) + "/" + y;

        BufferedImage img = CACHE.get(key);
        if (img != null) return img;

        Long failedAt = FAILED.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 30_000) return null;

        if (PENDING.add(key)) {
            LOADER.submit(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection)
                            URI.create("https://tile.openstreetmap.org/" + key + ".png").toURL().openConnection();
                    c.setRequestProperty("User-Agent", "TriMaxSystem/1.0 (Java Swing desktop app)");
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(8000);
                    BufferedImage loaded;
                    try (InputStream in = c.getInputStream()) {
                        loaded = ImageIO.read(in);
                    }
                    if (loaded == null) throw new IOException("Not an image");
                    CACHE.put(key, loaded);
                    FAILED.remove(key);
                    tilesUnavailable = false;
                } catch (Exception ex) {
                    FAILED.put(key, System.currentTimeMillis());
                    tilesUnavailable = true;
                } finally {
                    PENDING.remove(key);
                    repaint();      // repaint() is safe to call from any thread
                }
            });
        }
        return null;
    }

    // =====================================================
    // PAINTING
    // =====================================================

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            if (pendingFit && w > 0 && h > 0) {
                pendingFit = false;
                doFit(w, h);
            }
            paintTiles(g, w, h);
            paintLine(g, w, h);
            paintMarkers(g, w, h);
            paintOverlays(g, w, h);
            if (isFocusOwner()) {
                g.setColor(Theme.FOCUS);
                g.setStroke(new BasicStroke(2f));
                g.drawRect(1, 1, w - 3, h - 3);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintTiles(Graphics2D g, int w, int h) {
        double left = cx - w / 2.0, top = cy - h / 2.0;
        int n = 1 << zoom;
        int tx0 = (int) Math.floor(left / TILE);
        int tx1 = (int) Math.floor((left + w) / TILE);
        int ty0 = Math.max(0, (int) Math.floor(top / TILE));
        int ty1 = Math.min(n - 1, (int) Math.floor((top + h) / TILE));

        int missing = 0;
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int px = (int) Math.round(tx * (double) TILE - left);
                int py = (int) Math.round(ty * (double) TILE - top);
                BufferedImage img = getTile(zoom, tx, ty);
                if (img != null) {
                    g.drawImage(img, px, py, null);
                } else {
                    missing++;
                    g.setColor(PLACEHOLDER);
                    g.fillRect(px, py, TILE, TILE);
                    g.setColor(PLACEHOLDER_LN);
                    g.drawRect(px, py, TILE, TILE);
                }
            }
        }

        if (missing > 0 && tilesUnavailable) {
            drawChip(g, "Map tiles unavailable – check your internet connection. Vehicle data is still listed on the right.",
                    w / 2, h - 52, true, Theme.DANGER);
        }
    }

    private Point toScreen(double lat, double lon, int w, int h) {
        int sx = (int) Math.round(lonToX(lon, zoom) - cx + w / 2.0);
        int sy = (int) Math.round(latToY(lat, zoom) - cy + h / 2.0);
        return new Point(sx, sy);
    }

    private void paintLine(Graphics2D g, int w, int h) {
        if (line == null) return;
        Point a = toScreen(line[0], line[1], w, h);
        Point b = toScreen(line[2], line[3], w, h);
        g.setColor(Theme.NAVY);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0f, new float[]{8f, 6f}, 0f));
        g.drawLine(a.x, a.y, b.x, b.y);
        g.setStroke(new BasicStroke(1f));
        if (!lineLabel.isEmpty()) {
            drawChip(g, lineLabel, (a.x + b.x) / 2, (a.y + b.y) / 2 - 14, true, Theme.NAVY);
        }
    }

    private void paintMarkers(Graphics2D g, int w, int h) {
        Marker selected = null;
        for (Marker m : markers) {
            if (m.id.equals(selectedId)) {
                selected = m;
                continue;
            }
            drawMarker(g, m, false, w, h);
        }
        if (selected != null) drawMarker(g, selected, true, w, h);
    }

    private void drawMarker(Graphics2D g, Marker m, boolean sel, int w, int h) {
        Point p = toScreen(m.lat, m.lon, w, h);
        if (p.x < -60 || p.y < -60 || p.x > w + 60 || p.y > h + 60) return;

        int r = sel ? 11 : 8;
        Color base = m.color;

        if (sel) {
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 70));
            g.fillOval(p.x - r - 7, p.y - r - 7, (r + 7) * 2, (r + 7) * 2);
        }
        drawShape(g, m.shape, base, p.x, p.y, r);

        g.setFont(LABEL_FONT);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(m.label);
        int bx = p.x - tw / 2 - 4;
        int by = p.y + r + 5;
        g.setColor(new Color(255, 255, 255, 235));
        g.fill(new RoundRectangle2D.Double(bx, by, tw + 8, 16, 8, 8));
        g.setColor(Theme.TEXT);
        g.drawString(m.label, bx + 4, by + 12);
    }

    /** Draws one marker symbol centred on (x, y). Also used by the legend. */
    private static void drawShape(Graphics2D g, Shape shape, Color base, int x, int y, int r) {
        switch (shape) {
            case HOLLOW:                       // ring: a position that is NOT current
                g.setColor(Color.WHITE);
                g.fillOval(x - r - 2, y - r - 2, (r + 2) * 2, (r + 2) * 2);
                g.setColor(base);
                g.setStroke(new BasicStroke(3f));
                g.drawOval(x - r + 1, y - r + 1, r * 2 - 2, r * 2 - 2);
                g.setStroke(new BasicStroke(1f));
                break;
            case DESTINATION:                  // square: where the booking is going
                g.setColor(Color.WHITE);
                g.fillRect(x - r - 2, y - r - 2, (r + 2) * 2, (r + 2) * 2);
                g.setColor(base);
                g.fillRect(x - r, y - r, r * 2, r * 2);
                break;
            default:                           // filled circle: a current position
                g.setColor(Color.WHITE);
                g.fillOval(x - r - 2, y - r - 2, (r + 2) * 2, (r + 2) * 2);
                g.setColor(base);
                g.fillOval(x - r, y - r, r * 2, r * 2);
        }
    }

    private void paintOverlays(Graphics2D g, int w, int h) {
        g.setFont(SMALL_FONT);
        FontMetrics fm = g.getFontMetrics();

        // Legend (top right)
        if (!legend.isEmpty()) {
            int rowH = 18;
            int boxW = 0;
            for (LegendItem it : legend) boxW = Math.max(boxW, fm.stringWidth(it.text));
            boxW += 46;
            int boxH = 10 + legend.size() * rowH;
            int bx = w - boxW - 10, by = 10;
            g.setColor(new Color(255, 255, 255, 235));
            g.fill(new RoundRectangle2D.Double(bx, by, boxW, boxH, 10, 10));
            for (int i = 0; i < legend.size(); i++) {
                LegendItem it = legend.get(i);
                int ry = by + 5 + i * rowH;
                if (it.color == null) {
                    g.setFont(LABEL_FONT);
                    g.setColor(Theme.MUTED);
                    g.drawString(it.text, bx + 10, ry + 13);
                    g.setFont(SMALL_FONT);
                } else {
                    drawShape(g, it.shape, it.color, bx + 17, ry + 9, 5);
                    g.setColor(Theme.TEXT);
                    g.drawString(it.text, bx + 32, ry + 13);
                }
            }
        }

        // Banner (top centre)
        if (banner != null && !banner.isEmpty()) {
            drawChip(g, banner, w / 2, 26, true, bannerIsError ? Theme.DANGER : Theme.MUTED);
        }

        // Info text (bottom left)
        if (info != null && !info.isEmpty()) {
            int tw = fm.stringWidth(info);
            g.setColor(new Color(255, 255, 255, 235));
            g.fill(new RoundRectangle2D.Double(10, h - 30, tw + 16, 20, 10, 10));
            g.setColor(Theme.TEXT);
            g.drawString(info, 18, h - 16);
        }

        // OpenStreetMap attribution (required by the tile usage policy)
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        fm = g.getFontMetrics();
        String attr = "© OpenStreetMap contributors";
        int aw = fm.stringWidth(attr);
        g.setColor(new Color(255, 255, 255, 210));
        g.fillRect(w - aw - 10, h - 16, aw + 10, 16);
        g.setColor(Theme.TEXT);
        g.drawString(attr, w - aw - 5, h - 4);

        // Dimming message (loading / empty / error), on top of everything else
        if (message != null && !message.isEmpty()) {
            g.setColor(new Color(255, 255, 255, 170));
            g.fillRect(0, 0, w, h);
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            fm = g.getFontMetrics();
            String[] lines = message.split("\n");
            int boxW = 0;
            for (String l : lines) boxW = Math.max(boxW, fm.stringWidth(l));
            boxW += 40;
            int boxH = 20 + lines.length * 22;
            int bx = (w - boxW) / 2, by = (h - boxH) / 2;
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(bx, by, boxW, boxH, 12, 12));
            g.setColor(Theme.BORDER);
            g.draw(new RoundRectangle2D.Double(bx, by, boxW, boxH, 12, 12));
            g.setColor(Theme.TEXT);
            for (int i = 0; i < lines.length; i++) {
                g.drawString(lines[i], (w - fm.stringWidth(lines[i])) / 2, by + 28 + i * 22);
            }
        }
    }

    /** A rounded white label centred on (cx, cy) with coloured text. */
    private static void drawChip(Graphics2D g, String text, int cx, int cy, boolean border, Color textColor) {
        g.setFont(SMALL_FONT);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        int bx = cx - tw / 2 - 8, by = cy - 11;
        g.setColor(new Color(255, 255, 255, 240));
        g.fill(new RoundRectangle2D.Double(bx, by, tw + 16, 22, 11, 11));
        if (border) {
            g.setColor(textColor);
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(bx, by, tw + 16, 22, 11, 11));
        }
        g.setColor(textColor);
        g.drawString(text, bx + 8, by + 15);
    }

    // =====================================================
    // HIT TESTING AND TOOLTIPS
    // =====================================================

    private Marker markerAt(int mx, int my) {
        int w = getWidth(), h = getHeight();
        for (int i = markers.size() - 1; i >= 0; i--) {
            Marker m = markers.get(i);
            Point p = toScreen(m.lat, m.lon, w, h);
            if (Math.hypot(p.x - mx, p.y - my) <= 13) return m;
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Marker m = markerAt(e.getX(), e.getY());
        return m == null ? null : m.tooltipHtml;
    }

    private JButton makeMapButton(String text, int x, int y, int w, int h, String tip, Runnable action) {
        JButton b = new JButton(text);
        b.setBounds(x, y, w, h);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setFont(new Font("SansSerif", Font.BOLD, text.length() == 1 ? 16 : 11));
        b.setBackground(Color.WHITE);
        b.setForeground(Theme.TEXT);
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorder(BorderFactory.createLineBorder(Theme.MUTED));
        b.addActionListener(e -> action.run());
        return b;   // stays focusable so it can be used from the keyboard
    }

    /** Escapes text for use inside tooltip HTML. */
    public static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
