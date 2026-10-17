package com.trimaxsystem;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

public class DriversNVehiclesPanel extends JPanel {

    // Reference width of the design (taken from the screenshot)
    private static final double DESIGN_WIDTH = 1107.0;

    private static final int  POLL_MS  = 5000;                 // how often locations refresh
    private static final long STALE_MS = 5 * 60 * 1000L;       // no update for 5 min = faded marker

    private static final Color FIELD_BG   = Color.decode("#F4F6F8");
    private static final Color DARK       = Color.decode("#45494D");
    private static final Color BLUE       = Color.decode("#2563EB");
    private static final Color HEADING    = Color.decode("#1E293B");
    private static final Color BORDER_CLR = Color.decode("#555555");

    private static final Pattern PLATE = Pattern.compile("[A-Za-z0-9 \\-]{2,20}");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private DefaultTableModel vehicleModel;
    private JTable table;
    private MapView map;

    private JTextField txtPlate;
    private JTextField txtType;
    private JTextField txtDriver;
    private JTextField txtSearch;
    private JComboBox<String> cmbStatus;

    // vehicle_id of the currently selected row (null = nothing selected)
    private String selectedVehicleId = null;
    private boolean selectingFromMap = false;
    private boolean polling = false;

    private final Timer pollTimer = new Timer(POLL_MS, e -> refreshLocations());

    public DriversNVehiclesPanel() {
        setBackground(Color.WHITE);
        setLayout(new BorderLayout());

        // ---------- TOP AREA (absolute layout scaled to panel width) ----------
        JPanel top = new JPanel(null) {
            @Override
            public void doLayout() {
                double s = getWidth() / DESIGN_WIDTH;
                for (Component c : getComponents()) {
                    Rectangle r = (Rectangle) ((JComponent) c).getClientProperty("design");
                    if (r != null) {
                        c.setBounds((int) Math.round(r.x * s), r.y,
                                (int) Math.round(r.width * s), r.height);
                    }
                }
            }
        };
        top.setBackground(Color.WHITE);
        top.setPreferredSize(new Dimension(1107, 400));

        place(top, makeHeading("Drivers And Vehicles"), 6, 8, 700, 40);

        // Map (the grey rectangle in the design)
        map = new MapView();
        place(top, map, 6, 56, 667, 340);

        // Labels
        place(top, makeLabel("Vehicle ID/Plate Number:"), 682, 56,  250, 20);
        place(top, makeLabel("Vehicle Type"),             682, 134, 250, 20);
        place(top, makeLabel("Driver"),                   682, 211, 250, 20);
        place(top, makeLabel("Search:"),                  682, 370, 55,  20);

        // Input fields
        txtPlate  = makeField();
        txtType   = makeField();
        txtDriver = makeField();
        txtSearch = makeField();

        place(top, txtPlate,  682, 83,  250, 27);
        place(top, txtType,   682, 161, 250, 27);
        place(top, txtDriver, 682, 238, 250, 27);
        place(top, txtSearch, 737, 367, 150, 27);

        // Status filter (index 0 = no filter)
        cmbStatus = makeCombo(new String[]{"Status", "Available", "On Delivery", "Maintenance",
                "Unavailable", "On Vacation", "Suspended"});
        cmbStatus.addActionListener(e -> loadVehicles());
        place(top, cmbStatus, 895, 367, 123, 27);

        // Buttons
        JButton btnAdd    = makeButton("Add");
        JButton btnUpdate = makeButton("Update");
        JButton btnDelete = makeButton("Delete");
        JButton btnClear  = makeButton("Clear");
        JButton btnSearch = makeButton("Search");

        place(top, btnAdd,    682, 288, 77, 43);
        place(top, btnUpdate, 782, 288, 77, 43);
        place(top, btnDelete, 882, 288, 77, 43);
        place(top, btnClear,  982, 288, 77, 43);
        place(top, btnSearch, 1026, 363, 75, 33);

        add(top, BorderLayout.NORTH);

        // ---------- TABLE ----------
        String[] columnNames = {"Vehicle ID", "Vehicle Type", "Driver ID", "Driver", "Status"};

        vehicleModel = new DefaultTableModel(null, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(vehicleModel);
        table.setRowHeight(32);
        table.setBackground(Color.WHITE);
        table.setForeground(Color.decode("#222222"));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setFont(new Font("SansSerif", Font.PLAIN, 13));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(BLUE);
        table.setSelectionForeground(Color.WHITE);
        for (int i = 0; i < columnNames.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(100);
        }

        // Clicking a row fills the fields and highlights/centers the vehicle on the map
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row == -1) {
                map.setSelectedVehicle(null);
                return;
            }
            selectedVehicleId = String.valueOf(vehicleModel.getValueAt(row, 0));
            txtPlate.setText(selectedVehicleId);
            txtType.setText(String.valueOf(vehicleModel.getValueAt(row, 1)));
            txtDriver.setText(String.valueOf(vehicleModel.getValueAt(row, 2)));   // driver's username

            map.setSelectedVehicle(selectedVehicleId);
            if (!selectingFromMap) {
                map.focusVehicle(selectedVehicleId);
            }
        });

        DefaultTableCellRenderer cellRenderer = new DefaultTableCellRenderer();
        cellRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        cellRenderer.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        table.setDefaultRenderer(Object.class, cellRenderer);

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setResizingAllowed(false);
        header.setPreferredSize(new Dimension(100, 32));
        DefaultTableCellRenderer headerRenderer = new DefaultTableCellRenderer();
        headerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        headerRenderer.setBackground(DARK);
        headerRenderer.setForeground(Color.decode("#D1D5DB"));
        headerRenderer.setFont(new Font("SansSerif", Font.PLAIN, 13));
        headerRenderer.setOpaque(true);
        headerRenderer.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.decode("#5E6368")));
        header.setDefaultRenderer(headerRenderer);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        scrollPane.getViewport().setBackground(Color.WHITE);

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        center.add(scrollPane, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        // Clicking a marker on the map selects that vehicle's row
        map.setOnMarkerClick(id -> {
            for (int i = 0; i < vehicleModel.getRowCount(); i++) {
                if (id.equals(String.valueOf(vehicleModel.getValueAt(i, 0)))) {
                    selectingFromMap = true;
                    table.setRowSelectionInterval(i, i);
                    table.scrollRectToVisible(table.getCellRect(i, 0, true));
                    selectingFromMap = false;
                    return;
                }
            }
            map.setSelectedVehicle(id);   // vehicle is filtered out of the table: just highlight it
        });

        // ---------- LISTENERS ----------
        btnSearch.addActionListener(e -> loadVehicles());
        txtSearch.addActionListener(e -> loadVehicles());
        btnClear.addActionListener(e -> clearFields());
        btnAdd.addActionListener(e -> {
            if (addVehicle()) clearFields();
        });
        btnUpdate.addActionListener(e -> {
            if (updateVehicle()) clearFields();
        });
        btnDelete.addActionListener(e -> {
            if (deleteVehicle()) clearFields();
        });

        // Refresh the map as soon as this panel becomes visible (e.g. switching sidebar tabs)
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refreshLocations();
            }
        });

        loadVehicles();
    }

    // Poll only while the panel is on screen
    @Override
    public void addNotify() {
        super.addNotify();
        pollTimer.start();
    }

    @Override
    public void removeNotify() {
        pollTimer.stop();
        super.removeNotify();
    }

    // ---------- HELPERS ----------

    private void place(JPanel parent, JComponent c, int x, int y, int w, int h) {
        c.putClientProperty("design", new Rectangle(x, y, w, h));
        parent.add(c);
    }

    private JLabel makeHeading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, 26));
        l.setForeground(HEADING);
        return l;
    }

    private JLabel makeLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.PLAIN, 13));
        l.setForeground(Color.decode("#222222"));
        return l;
    }

    private JTextField makeField() {
        JTextField f = new JTextField();
        f.setBackground(FIELD_BG);
        f.setFont(new Font("SansSerif", Font.PLAIN, 13));
        Border line = BorderFactory.createLineBorder(BORDER_CLR);
        f.setBorder(BorderFactory.createCompoundBorder(line,
                BorderFactory.createEmptyBorder(0, 6, 0, 6)));
        return f;
    }

    private JButton makeButton(String text) {
        JButton b = new JButton(text);
        b.setBackground(BLUE);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", Font.PLAIN, 13));
        b.setFocusable(false);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorder(BorderFactory.createLineBorder(Color.decode("#1E293B")));
        return b;
    }

    private JComboBox<String> makeCombo(String[] items) {
        JComboBox<String> cb = new JComboBox<>(items);
        cb.setFont(new Font("SansSerif", Font.PLAIN, 13));
        cb.setBackground(DARK);
        cb.setForeground(Color.decode("#C9CDD2"));
        cb.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        cb.setUI(new BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                BasicArrowButton arrow = new BasicArrowButton(
                        BasicArrowButton.SOUTH, DARK, DARK, Color.WHITE, DARK);
                arrow.setBorder(BorderFactory.createEmptyBorder());
                return arrow;
            }
        });
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (index == -1) {
                    setBackground(DARK);
                    setForeground(Color.decode("#C9CDD2"));
                }
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
                return this;
            }
        });
        return cb;
    }

    /** Blanks the three input fields and deselects the table row. */
    private void clearFields() {
        txtPlate.setText("");
        txtType.setText("");
        txtDriver.setText("");
        selectedVehicleId = null;
        table.clearSelection();
    }

    private void warn(String message, String title) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.WARNING_MESSAGE);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private String plate() {
        return txtPlate.getText().trim().toUpperCase(Locale.ROOT);
    }

    // ---------- DATABASE: TABLE ----------

    /** Reloads the table using the current search text and status filter. */
    public void loadVehicles() {
        String keyword = txtSearch.getText().trim();
        boolean filterStatus = cmbStatus.getSelectedIndex() > 0;

        String sql = "SELECT v.vehicle_id, v.vehicle_type, v.username, u.name AS driver_name, v.status "
                + "FROM vehicles v LEFT JOIN users u ON u.username = v.username "
                + "WHERE (v.vehicle_id ILIKE ? OR COALESCE(v.vehicle_type, '') ILIKE ? "
                + "    OR COALESCE(v.username, '') ILIKE ? OR COALESCE(u.name, '') ILIKE ?) "
                + (filterStatus ? "AND v.status = ? " : "")
                + "ORDER BY v.vehicle_id";

        vehicleModel.setRowCount(0);
        selectedVehicleId = null;   // the old selection no longer exists after a reload

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {

            String like = "%" + keyword + "%";
            for (int i = 1; i <= 4; i++) {
                pst.setString(i, like);
            }
            if (filterStatus) {
                pst.setString(5, (String) cmbStatus.getSelectedItem());
            }

            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) {
                    String driverName = rs.getString("driver_name");
                    vehicleModel.addRow(new Object[]{
                            rs.getString("vehicle_id"),
                            nz(rs.getString("vehicle_type")),
                            nz(rs.getString("username")),
                            driverName == null ? "Unassigned" : driverName,
                            nz(rs.getString("status"))
                    });
                }
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not load vehicles: " + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    // ---------- DATABASE: ADD / UPDATE / DELETE ----------
    // Each returns true on success so the button only clears the fields when it worked.

    private boolean validateInputs() {
        String p = txtPlate.getText().trim();
        if (p.isEmpty()) {
            warn("Vehicle ID / plate number is required.", "Missing Data");
            return false;
        }
        if (!PLATE.matcher(p).matches()) {
            warn("Vehicle ID / plate number must be 2 to 20 characters: letters, digits, spaces or dashes.",
                    "Invalid Input");
            return false;
        }
        if (txtType.getText().trim().length() > 30) {
            warn("Vehicle type can be at most 30 characters.", "Invalid Input");
            return false;
        }
        return true;
    }

    /**
     * Turns the Driver text (a username or full name) into a driver's username.
     * out[0] receives the username, or null if the field is blank.
     * Returns false (after showing a message) if the driver is invalid.
     */
    private boolean resolveDriver(String[] out) {
        out[0] = null;
        String text = txtDriver.getText().trim();
        if (text.isEmpty()) return true;

        String sql = "SELECT u.username FROM users u JOIN roles r ON r.role_id = u.role_id "
                + "WHERE r.role_name = 'Driver' "
                + "AND (LOWER(u.username) = LOWER(?) OR LOWER(u.name) = LOWER(?))";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, text);
            pst.setString(2, text);

            List<String> matches = new ArrayList<>();
            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) matches.add(rs.getString(1));
            }

            if (matches.isEmpty()) {
                warn("No driver found with the username or name \"" + text + "\".", "Driver Not Found");
                return false;
            }
            if (matches.size() > 1) {
                for (String m : matches) {
                    if (m.equalsIgnoreCase(text)) {
                        out[0] = m;
                        return true;
                    }
                }
                warn("More than one driver is named \"" + text + "\". Please type the driver's username instead.",
                        "Ambiguous Driver");
                return false;
            }
            out[0] = matches.get(0);
            return true;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not look up the driver: " + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private boolean addVehicle() {
        if (!validateInputs()) return false;
        String[] driver = new String[1];
        if (!resolveDriver(driver)) return false;

        // status is left out so the database default ('Available') applies
        String sql = "INSERT INTO vehicles (vehicle_id, username, vehicle_type) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, plate());
            pst.setString(2, driver[0]);
            pst.setString(3, emptyToNull(txtType.getText()));
            pst.executeUpdate();
            loadVehicles();
            refreshLocations();
            return true;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {   // unique violation
                warn("A vehicle with the plate \"" + plate() + "\" already exists.", "Duplicate Vehicle");
            } else {
                JOptionPane.showMessageDialog(this, "Could not add vehicle: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
            return false;
        }
    }

    private boolean updateVehicle() {
        if (selectedVehicleId == null) {
            warn("Click a vehicle in the table first.", "No Selection");
            return false;
        }
        if (!validateInputs()) return false;
        String[] driver = new String[1];
        if (!resolveDriver(driver)) return false;

        String sql = "UPDATE vehicles SET vehicle_id = ?, username = ?, vehicle_type = ? "
                + "WHERE vehicle_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, plate());
            pst.setString(2, driver[0]);
            pst.setString(3, emptyToNull(txtType.getText()));
            pst.setString(4, selectedVehicleId);
            pst.executeUpdate();
            loadVehicles();
            refreshLocations();
            return true;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                warn("Another vehicle already uses the plate \"" + plate() + "\".", "Duplicate Vehicle");
            } else {
                JOptionPane.showMessageDialog(this, "Could not update vehicle: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
            return false;
        }
    }

    private boolean deleteVehicle() {
        if (selectedVehicleId == null) {
            warn("Click a vehicle in the table first.", "No Selection");
            return false;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete vehicle " + selectedVehicleId + "?\n"
                        + "Past bookings keep their record but lose the link to this vehicle.",
                "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return false;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement("DELETE FROM vehicles WHERE vehicle_id = ?")) {
            pst.setString(1, selectedVehicleId);
            pst.executeUpdate();
            loadVehicles();
            refreshLocations();
            return true;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not delete vehicle: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    // ---------- DATABASE: LIVE LOCATIONS ----------

    private static class LocationSnapshot {
        final List<VehicleMarker> markers = new ArrayList<>();
        int total = 0;
    }

    /** Refreshes only the map markers (not the table), so a selected row is never lost. */
    private void refreshLocations() {
        if (polling || !isShowing()) return;
        polling = true;

        new SwingWorker<LocationSnapshot, Void>() {
            @Override
            protected LocationSnapshot doInBackground() throws Exception {
                return fetchLocations();
            }

            @Override
            protected void done() {
                try {
                    LocationSnapshot snap = get();
                    map.setMarkers(snap.markers);
                    map.setInfo(snap.markers.size() + " of " + snap.total + " vehicles on map \u00B7 updated "
                            + LocalTime.now().format(TIME_FMT));
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof SQLException && "42703".equals(((SQLException) cause).getSQLState())) {
                        map.setInfo("Vehicles table has no location columns yet (run the ALTER TABLE SQL)");
                    } else {
                        map.setInfo("Could not refresh locations");
                    }
                    System.out.println("Location refresh failed: " + cause.getMessage());
                } finally {
                    polling = false;
                }
            }
        }.execute();
    }

    private LocationSnapshot fetchLocations() throws SQLException {
        String sql = "SELECT v.vehicle_id, v.vehicle_type, v.status, v.latitude, v.longitude, "
                + "       EXTRACT(EPOCH FROM (now() - v.last_location_at)) AS age_s, "
                + "       COALESCE(u.name, 'No driver') AS driver_name "
                + "FROM vehicles v LEFT JOIN users u ON u.username = v.username "
                + "ORDER BY v.vehicle_id";

        LocationSnapshot snap = new LocationSnapshot();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql);
             ResultSet rs = pst.executeQuery()) {

            while (rs.next()) {
                snap.total++;

                double lat = rs.getDouble("latitude");
                boolean hasLat = !rs.wasNull();
                double lon = rs.getDouble("longitude");
                boolean hasLon = !rs.wasNull();
                double age = rs.getDouble("age_s");
                boolean hasAge = !rs.wasNull();

                if (!hasLat || !hasLon) continue;   // no position yet: counted, but not drawn

                boolean stale = !hasAge || age * 1000 > STALE_MS;
                snap.markers.add(new VehicleMarker(
                        rs.getString("vehicle_id"),
                        nz(rs.getString("vehicle_type")),
                        rs.getString("driver_name"),
                        nz(rs.getString("status")),
                        lat, lon, stale,
                        hasAge ? ago((long) Math.max(0, age)) : "no timestamp"));
            }
        }
        return snap;
    }

    private static String ago(long seconds) {
        if (seconds < 60)    return "just now";
        if (seconds < 3600)  return (seconds / 60) + " min ago";
        if (seconds < 86400) return (seconds / 3600) + " h ago";
        return (seconds / 86400) + " d ago";
    }

    // =====================================================
    // MAP SUPPORT CLASSES
    // =====================================================

    /** One vehicle that has a position. */
    static class VehicleMarker {
        final String id, type, driver, status, seen;
        final double lat, lon;
        final boolean stale;

        VehicleMarker(String id, String type, String driver, String status,
                      double lat, double lon, boolean stale, String seen) {
            this.id = id;
            this.type = type;
            this.driver = driver;
            this.status = status;
            this.lat = lat;
            this.lon = lon;
            this.stale = stale;
            this.seen = seen;
        }
    }

    /**
     * A small OpenStreetMap viewer built only from Swing: drag to pan, mouse wheel or
     * double-click to zoom, click a marker to select it. Tiles are downloaded in the
     * background and cached in memory.
     */
    static class MapView extends JPanel {

        private static final int TILE = 256;
        private static final int MIN_ZOOM = 3;
        private static final int MAX_ZOOM = 18;

        private static final Color MAP_BG         = Color.decode("#3C4043");
        private static final Color PLACEHOLDER    = Color.decode("#3F4448");
        private static final Color PLACEHOLDER_LN = Color.decode("#4B5156");
        private static final Color TEXT_DARK      = Color.decode("#1E293B");

        // One color per status
        private static final Color C_AVAILABLE   = Color.decode("#16A34A");
        private static final Color C_DELIVERY    = Color.decode("#2563EB");
        private static final Color C_MAINT       = Color.decode("#F59E0B");
        private static final Color C_UNAVAILABLE = Color.decode("#475569");
        private static final Color C_VACATION    = Color.decode("#8B5CF6");
        private static final Color C_SUSPENDED   = Color.decode("#DC2626");
        private static final Color C_OTHER       = Color.decode("#9CA3AF");   // unknown status

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
        private double cx, cy;                      // map center in world pixels at the current zoom
        private List<VehicleMarker> markers = new ArrayList<>();
        private String selectedId = null;
        private String info = "";
        private boolean pendingFit = false;
        private boolean needsInitialFit = true;
        private Consumer<String> onMarkerClick;

        MapView() {
            setBackground(MAP_BG);
            setLayout(null);
            setBorder(BorderFactory.createLineBorder(Color.decode("#2B2F32")));
            ToolTipManager.sharedInstance().registerComponent(this);

            // Default view (Metro Manila area) until vehicles are known
            cx = lonToX(120.9842, zoom);
            cy = latToY(14.5995, zoom);

            add(makeMapButton("+", 10, 10, 30, 30, () -> zoomAt(getWidth() / 2, getHeight() / 2, 1)));
            add(makeMapButton("\u2212", 10, 44, 30, 30, () -> zoomAt(getWidth() / 2, getHeight() / 2, -1)));
            add(makeMapButton("Fit all", 10, 78, 60, 26, this::fitAll));

            MouseAdapter ma = new MouseAdapter() {
                private Point last;

                @Override
                public void mousePressed(MouseEvent e) {
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
                    VehicleMarker m = markerAt(e.getX(), e.getY());
                    if (m != null && onMarkerClick != null) {
                        onMarkerClick.accept(m.id);
                    }
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(markerAt(e.getX(), e.getY()) != null
                            ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            : Cursor.getDefaultCursor());
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(e -> zoomAt(e.getX(), e.getY(), e.getWheelRotation() < 0 ? 1 : -1));
        }

        // ----- public API -----

        void setOnMarkerClick(Consumer<String> cb) {
            this.onMarkerClick = cb;
        }

        void setMarkers(List<VehicleMarker> list) {
            this.markers = new ArrayList<>(list);
            if (needsInitialFit && !markers.isEmpty()) {
                needsInitialFit = false;
                pendingFit = true;      // fit once, then never fight the user's panning
            }
            repaint();
        }

        void setInfo(String text) {
            this.info = text;
            repaint();
        }

        void setSelectedVehicle(String id) {
            this.selectedId = id;
            repaint();
        }

        /** Centers on a vehicle (zooming in a bit if the map is zoomed far out). */
        void focusVehicle(String id) {
            for (VehicleMarker m : markers) {
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

        void fitAll() {
            pendingFit = true;
            repaint();
        }

        // ----- projection (Web Mercator) -----

        private static double worldSize(int z) {
            return TILE * Math.pow(2, z);
        }

        private static double lonToX(double lon, int z) {
            return (lon + 180.0) / 360.0 * worldSize(z);
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
            for (VehicleMarker m : markers) {
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

        // ----- tiles -----

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

        // ----- painting -----

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
                paintMarkers(g, w, h);
                paintOverlays(g, w, h);
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
                String msg = "Map tiles unavailable \u2013 check your internet connection";
                g.setFont(SMALL_FONT);
                g.setColor(Color.decode("#9CA3AF"));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
            }
        }

        private Point toScreen(VehicleMarker m, int w, int h) {
            int sx = (int) Math.round(lonToX(m.lon, zoom) - cx + w / 2.0);
            int sy = (int) Math.round(latToY(m.lat, zoom) - cy + h / 2.0);
            return new Point(sx, sy);
        }

        private void paintMarkers(Graphics2D g, int w, int h) {
            VehicleMarker selected = null;
            for (VehicleMarker m : markers) {
                if (m.id.equals(selectedId)) {
                    selected = m;
                    continue;
                }
                drawMarker(g, m, false, w, h);
            }
            if (selected != null) {
                drawMarker(g, selected, true, w, h);
            }
        }

        private void drawMarker(Graphics2D g, VehicleMarker m, boolean sel, int w, int h) {
            Point p = toScreen(m, w, h);
            if (p.x < -60 || p.y < -60 || p.x > w + 60 || p.y > h + 60) return;

            int r = sel ? 11 : 8;
            Color base = statusColor(m.status);

            if (sel) {
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 70));
                g.fillOval(p.x - r - 6, p.y - r - 6, (r + 6) * 2, (r + 6) * 2);
            }
            g.setColor(Color.WHITE);
            g.fillOval(p.x - r - 2, p.y - r - 2, (r + 2) * 2, (r + 2) * 2);
            g.setColor(m.stale ? new Color(base.getRed(), base.getGreen(), base.getBlue(), 110) : base);
            g.fillOval(p.x - r, p.y - r, r * 2, r * 2);

            g.setFont(LABEL_FONT);
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(m.id);
            int bx = p.x - tw / 2 - 4;
            int by = p.y + r + 5;
            g.setColor(new Color(255, 255, 255, 225));
            g.fill(new RoundRectangle2D.Double(bx, by, tw + 8, 16, 8, 8));
            g.setColor(TEXT_DARK);
            g.drawString(m.id, bx + 4, by + 12);
        }

        private void paintOverlays(Graphics2D g, int w, int h) {
            g.setFont(SMALL_FONT);
            FontMetrics fm = g.getFontMetrics();

            // Legend (top right), one row per status
            String[] names = {"Available", "On Delivery", "Maintenance",
                    "Unavailable", "On Vacation", "Suspended"};
            Color[] cols = {C_AVAILABLE, C_DELIVERY, C_MAINT,
                    C_UNAVAILABLE, C_VACATION, C_SUSPENDED};
            int boxW = 112, boxH = 8 + names.length * 18;
            int bx = w - boxW - 10, by = 10;
            g.setColor(new Color(255, 255, 255, 225));
            g.fill(new RoundRectangle2D.Double(bx, by, boxW, boxH, 10, 10));
            for (int i = 0; i < names.length; i++) {
                g.setColor(cols[i]);
                g.fillOval(bx + 10, by + 8 + i * 18, 10, 10);
                g.setColor(TEXT_DARK);
                g.drawString(names[i], bx + 28, by + 17 + i * 18);
            }

            // Info text (bottom left)
            if (info != null && !info.isEmpty()) {
                int tw = fm.stringWidth(info);
                g.setColor(new Color(255, 255, 255, 225));
                g.fill(new RoundRectangle2D.Double(10, h - 30, tw + 16, 20, 10, 10));
                g.setColor(TEXT_DARK);
                g.drawString(info, 18, h - 16);
            }

            // OpenStreetMap attribution (required by the tile usage policy)
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            fm = g.getFontMetrics();
            String attr = "\u00A9 OpenStreetMap contributors";
            int aw = fm.stringWidth(attr);
            g.setColor(new Color(255, 255, 255, 200));
            g.fillRect(w - aw - 10, h - 16, aw + 10, 16);
            g.setColor(TEXT_DARK);
            g.drawString(attr, w - aw - 5, h - 4);
        }

        private static Color statusColor(String status) {
            if ("Available".equals(status))   return C_AVAILABLE;
            if ("On Delivery".equals(status)) return C_DELIVERY;
            if ("Maintenance".equals(status)) return C_MAINT;
            if ("Unavailable".equals(status)) return C_UNAVAILABLE;
            if ("On Vacation".equals(status)) return C_VACATION;
            if ("Suspended".equals(status))   return C_SUSPENDED;
            return C_OTHER;
        }

        // ----- hit testing and tooltips -----

        private VehicleMarker markerAt(int mx, int my) {
            int w = getWidth(), h = getHeight();
            for (int i = markers.size() - 1; i >= 0; i--) {
                VehicleMarker m = markers.get(i);
                Point p = toScreen(m, w, h);
                if (Math.hypot(p.x - mx, p.y - my) <= 13) return m;
            }
            return null;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            VehicleMarker m = markerAt(e.getX(), e.getY());
            if (m == null) return null;
            return "<html><b>" + esc(m.id) + "</b>"
                    + (m.type.isEmpty() ? "" : " \u00B7 " + esc(m.type))
                    + "<br>Driver: " + esc(m.driver)
                    + "<br>Status: " + esc(m.status)
                    + "<br>Last update: " + esc(m.seen) + "</html>";
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private JButton makeMapButton(String text, int x, int y, int w, int h, Runnable action) {
            JButton b = new JButton(text);
            b.setBounds(x, y, w, h);
            b.setMargin(new Insets(0, 0, 0, 0));
            b.setFont(new Font("SansSerif", Font.BOLD, text.length() == 1 ? 16 : 12));
            b.setBackground(Color.WHITE);
            b.setForeground(TEXT_DARK);
            b.setFocusable(false);
            b.setOpaque(true);
            b.setContentAreaFilled(true);
            b.setBorder(BorderFactory.createLineBorder(Color.decode("#9CA3AF")));
            b.addActionListener(e -> action.run());
            return b;
        }
    }
}