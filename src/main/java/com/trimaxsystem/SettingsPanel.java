package com.trimaxsystem;

import java.awt.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * System Settings: only things that are real in this app. Nothing is reported healthy until it has actually
 * been checked, and a failed check shows the failure. No passwords, keys or connection strings are shown.
 */
public class SettingsPanel extends JPanel {

    private static final String[] OPTIONAL_TABLES = {"audit_log", "user_locations", "admin_notifications"};

    private final JLabel dbStatus = value("Not checked yet");
    private final JLabel dbLatency = value("-");
    private final JLabel dbLastOk = value("No successful check in this session");
    private final Map<String, JLabel> tableLabels = new LinkedHashMap<>();
    private final JLabel mapStatus = value("Not checked yet");
    private final JButton checkDb = UiKit.primaryButton("Check database now");
    private final JButton checkMap = UiKit.secondaryButton("Test map tile server");

    public SettingsPanel() {
        super(new BorderLayout());
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        AuthService.Session s = AuthService.current();
        JPanel profile = section("Admin profile");
        row(profile, "Name", s == null ? "Not signed in" : s.fullName);
        row(profile, "Username", s == null ? "-" : s.username);
        row(profile, "Email", s == null || s.email == null ? "-" : s.email);
        row(profile, "Role", s == null ? "-" : s.role);
        JButton change = UiKit.secondaryButton("Change password");
        change.addActionListener(e -> ChangePasswordDialog.open(SwingUtilities.getWindowAncestor(this)));
        change.setAlignmentX(Component.LEFT_ALIGNMENT);
        profile.add(change);
        col.add(profile);

        JPanel db = section("Database connection");
        row(db, "Status", dbStatus);
        row(db, "Response time", dbLatency);
        row(db, "Last successful check", dbLastOk);
        for (String t : OPTIONAL_TABLES) {
            JLabel l = value("Not checked yet");
            tableLabels.put(t, l);
            row(db, "Table " + t, l);
        }
        checkDb.addActionListener(e -> checkDatabase());
        checkDb.setAlignmentX(Component.LEFT_ALIGNMENT);
        db.add(checkDb);
        col.add(db);

        JPanel map = section("Map provider");
        row(map, "Provider", "OpenStreetMap public tile server (no API key is used or stored)");
        row(map, "Status", mapStatus);
        checkMap.addActionListener(e -> checkMap());
        checkMap.setAlignmentX(Component.LEFT_ALIGNMENT);
        map.add(checkMap);
        col.add(map);

        Preferences prefs = Preferences.userNodeForPackage(LiveGpsPanel.class);
        JPanel gps = section("GPS monitoring (change these on the Live GPS Monitoring page)");
        row(gps, "Refresh interval", Math.max(5, prefs.getInt("gps.refreshSeconds", 15)) + " seconds");
        row(gps, "A position is stale after", Math.max(1, prefs.getInt("gps.staleMinutes", 5)) + " minutes");
        col.add(gps);

        JPanel env = section("Application");
        String version = SettingsPanel.class.getPackage().getImplementationVersion();
        row(env, "Version", version == null ? "Development build (no version recorded)" : version);
        row(env, "Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        row(env, "Operating system", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        row(env, "Report time zone", ReportService.TZ);
        col.add(env);

        JScrollPane scroll = new JScrollPane(col);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        add(scroll, BorderLayout.CENTER);
    }

    // ---------------- checks ----------------

    private void checkDatabase() {
        checkDb.setEnabled(false);
        dbStatus.setText("Checking...");
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                AuthService.requireAdmin();
                long t0 = System.nanoTime();
                Map<String, Boolean> present = new LinkedHashMap<>();
                try (Connection c = DatabaseConnection.getConnection()) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT now()"); ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    for (String t : OPTIONAL_TABLES) {
                        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
                            ps.setString(1, "public." + t);
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                                present.put(t, rs.getBoolean(1));
                            }
                        }
                    }
                    return new Object[]{ms, present};
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done() {
                checkDb.setEnabled(true);
                try {
                    Object[] r = get();
                    dbStatus.setText("Connected");
                    dbLatency.setText(r[0] + " ms (includes opening the connection)");
                    dbLastOk.setText(ZonedDateTime.now().toLocalTime().withNano(0) + " (this computer's clock)");
                    for (Map.Entry<String, Boolean> e : ((Map<String, Boolean>) r[1]).entrySet()) {
                        tableLabels.get(e.getKey()).setText(e.getValue() ? "Present"
                                : "Missing: the related feature is not active until its SQL script is run");
                    }
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    dbStatus.setText("FAILED: " + (c.getMessage() == null ? c.toString() : firstLine(c.getMessage())));
                    dbLatency.setText("-");
                }
            }
        }.execute();
    }

    private void checkMap() {
        checkMap.setEnabled(false);
        mapStatus.setText("Checking...");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                HttpURLConnection c = (HttpURLConnection) URI.create("https://tile.openstreetmap.org/0/0/0.png")
                        .toURL().openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("User-Agent", "TriMaxSystem/1.0 (Java Swing desktop app)");
                try {
                    return c.getResponseCode();
                } finally {
                    c.disconnect();
                }
            }

            @Override
            protected void done() {
                checkMap.setEnabled(true);
                try {
                    int code = get();
                    mapStatus.setText(code == 200 ? "Reachable (checked " + ZonedDateTime.now().toLocalTime().withNano(0) + ")"
                            : "Reached the server but it answered HTTP " + code);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    mapStatus.setText("NOT reachable: " + c.getClass().getSimpleName());
                }
            }
        }.execute();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    // ---------------- layout helpers ----------------

    private static JLabel value(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT);
        return l;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.CARD);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, 10, 0),
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                        new EmptyBorder(10, 14, 10, 14))));
        JLabel t = new JLabel(title);
        t.setFont(new Font("SansSerif", Font.BOLD, 14));
        t.setForeground(Theme.TEXT);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(t);
        p.add(Box.createVerticalStrut(6));
        return p;
    }

    private static void row(JPanel p, String label, String text) {
        row(p, label, value(text));
    }

    private static void row(JPanel p, String label, JLabel valueLabel) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        r.setOpaque(false);
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label + ":");
        l.setForeground(Theme.MUTED);
        l.setPreferredSize(new Dimension(190, 20));
        r.add(l);
        r.add(valueLabel);
        p.add(r);
    }
}
