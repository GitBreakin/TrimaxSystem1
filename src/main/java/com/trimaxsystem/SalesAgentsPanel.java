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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

public class SalesAgentsPanel extends JPanel {

    // Reference width of the design (taken from the screenshot)
    private static final double DESIGN_WIDTH = 1083.0;

    private static final Color FIELD_BG   = Color.decode("#F4F6F8");
    private static final Color DARK       = Color.decode("#45494D");
    private static final Color BLUE       = Color.decode("#2563EB");
    private static final Color HEADING    = Color.decode("#1E293B");
    private static final Color BORDER_CLR = Color.decode("#555555");

    // Table columns
    private static final int COL_USERNAME = 1;
    private static final int COL_NAME     = 2;
    private static final int COL_CLIENTS  = 3;

    private DefaultTableModel agentModel;
    private JTable table;
    private MapView map;

    private JTextField txtSearch;
    private JComboBox<String> cmbStatus;

    // Map data: every client that has coordinates, plus counters for the info line
    private List<ClientMarker> allMarkers = new ArrayList<>();
    private int totalClients = 0;
    private boolean markersLoaded = false;
    private String markersError = null;

    // Currently selected agent (null = none, the map shows every agent's clients)
    private String selectedUsername = null;
    private String selectedName = null;
    private int selectedClientCount = 0;

    private boolean selectingFromMap = false;
    private boolean shownOnce = false;

    public SalesAgentsPanel() {
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
        top.setPreferredSize(new Dimension(1083, 397));

        place(top, makeHeading("Sales Agents"), 7, 6, 700, 40);

        // Map (the grey rectangle in the design)
        map = new MapView();
        place(top, map, 7, 53, 668, 340);

        // Search row (bottom right of the top area)
        place(top, makeLabel("Search:"), 689, 369, 55, 20);

        txtSearch = makeField();
        place(top, txtSearch, 744, 363, 155, 27);

        cmbStatus = makeCombo(new String[]{"Status", "Active", "Inactive"});
        cmbStatus.addActionListener(e -> loadAgents());
        place(top, cmbStatus, 907, 363, 90, 27);

        JButton btnSearch = makeButton("Search");
        place(top, btnSearch, 1004, 360, 69, 33);

        add(top, BorderLayout.NORTH);

        // ---------- TABLE ----------
        String[] columnNames = {"Agent ID", "Username", "Full Name", "No. Clients Assigned To", "Status"};

        agentModel = new DefaultTableModel(null, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(agentModel);
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

        // Selecting an agent shows only that agent's clients on the map
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row == -1) {
                selectedUsername = null;
                selectedName = null;
                selectedClientCount = 0;
            } else {
                selectedUsername = String.valueOf(agentModel.getValueAt(row, COL_USERNAME));
                selectedName = String.valueOf(agentModel.getValueAt(row, COL_NAME));
                selectedClientCount = Integer.parseInt(String.valueOf(agentModel.getValueAt(row, COL_CLIENTS)));
            }
            if (!selectingFromMap) {
                map.setSelectedClient(null);
            }
            updateMap(!selectingFromMap);
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
        center.setBorder(BorderFactory.createEmptyBorder(3, 7, 8, 9));
        center.add(scrollPane, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        // ---------- MAP CALLBACKS ----------
        // Clicking a client marker selects that client's agent in the table
        map.setOnMarkerClick(m -> {
            if (m.users.isEmpty()) return;   // unassigned client: just highlighted on the map
            String username = (selectedUsername != null && m.users.contains(selectedUsername))
                    ? selectedUsername : m.users.get(0);
            for (int i = 0; i < agentModel.getRowCount(); i++) {
                if (username.equals(String.valueOf(agentModel.getValueAt(i, COL_USERNAME)))) {
                    selectingFromMap = true;
                    table.setRowSelectionInterval(i, i);
                    table.scrollRectToVisible(table.getCellRect(i, 0, true));
                    selectingFromMap = false;
                    return;
                }
            }
        });
        map.setOnShowAll(() -> table.clearSelection());

        // ---------- LISTENERS ----------
        btnSearch.addActionListener(e -> loadAgents());
        txtSearch.addActionListener(e -> loadAgents());   // Enter key also searches

        // Reload when the panel is shown again (e.g. after pinning a client in the Clients panel)
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                if (shownOnce) {
                    loadAgents();
                    refreshMarkers();
                }
                shownOnce = true;
            }
        });

        loadAgents();
        refreshMarkers();
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---------- DATABASE: AGENTS TABLE ----------

    /** Reloads the table using the current search text and status filter. */
    public void loadAgents() {
        String keyword = txtSearch.getText().trim();
        boolean filterStatus = cmbStatus.getSelectedIndex() > 0;

        // One row per agent (a user with the "Agent" role). The agents table holds one row
        // per client assignment, so the client count is the number of those rows.
        String sql = "SELECT u.username, u.name, u.account_status, "
                + "       MIN(a.agent_id) AS agent_id, COUNT(a.client_id) AS client_count "
                + "FROM users u "
                + "JOIN roles r ON r.role_id = u.role_id AND r.role_name = 'Agent' "
                + "LEFT JOIN agents a ON a.username = u.username "
                + (filterStatus ? "WHERE u.account_status = ? " : "")
                + "GROUP BY u.username "
                + "HAVING (u.username ILIKE ? OR u.name ILIKE ? "
                + "        OR COALESCE(MIN(a.agent_id)::text, '') ILIKE ?) "
                + "ORDER BY u.name";

        agentModel.setRowCount(0);

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {

            int p = 1;
            if (filterStatus) {
                pst.setString(p++, (String) cmbStatus.getSelectedItem());
            }
            String like = "%" + keyword + "%";
            pst.setString(p++, like);
            pst.setString(p++, like);
            pst.setString(p, like);

            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) {
                    int agentId = rs.getInt("agent_id");
                    boolean hasAgentId = !rs.wasNull();
                    agentModel.addRow(new Object[]{
                            hasAgentId ? String.valueOf(agentId) : "-",
                            rs.getString("username"),
                            nz(rs.getString("name")),
                            rs.getInt("client_count"),
                            nz(rs.getString("account_status"))
                    });
                }
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not load sales agents: " + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }

        // The old selection no longer exists after a reload
        selectedUsername = null;
        selectedName = null;
        selectedClientCount = 0;
        map.setSelectedClient(null);
        updateMap(true);
    }

    // ---------- DATABASE: CLIENT LOCATIONS FOR THE MAP ----------

    private static class MarkerSnapshot {
        final List<ClientMarker> markers = new ArrayList<>();
        final List<LegendEntry> legend = new ArrayList<>();
        int total = 0;
    }

    /** Loads every client's location (and their agents) without blocking the screen. */
    private void refreshMarkers() {
        new SwingWorker<MarkerSnapshot, Void>() {
            @Override
            protected MarkerSnapshot doInBackground() throws Exception {
                return fetchMarkers();
            }

            @Override
            protected void done() {
                try {
                    MarkerSnapshot snap = get();
                    allMarkers = snap.markers;
                    totalClients = snap.total;
                    markersLoaded = true;
                    markersError = null;
                    map.setLegend(snap.legend);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    markersError = "Could not load client locations";
                    System.out.println("Client location load failed: " + cause.getMessage());
                }
                updateMap(true);
            }
        }.execute();
    }

    private MarkerSnapshot fetchMarkers() throws SQLException {
        String sql = "SELECT c.client_id, c.client_name, c.address, c.latitude, c.longitude, "
                + "       COALESCE(STRING_AGG(a.username, '|' ORDER BY a.username), '') AS usernames, "
                + "       COALESCE(STRING_AGG(u.name, '|' ORDER BY a.username), '')     AS agent_names "
                + "FROM clients c "
                + "LEFT JOIN agents a ON a.client_id = c.client_id "
                + "LEFT JOIN users u  ON u.username = a.username "
                + "GROUP BY c.client_id "
                + "ORDER BY c.client_id";

        // Raw rows first, so every agent gets a stable color (alphabetical order of usernames)
        class Raw {
            String id, name, address, agentNames;
            double lat, lon;
            List<String> users = new ArrayList<>();
        }
        List<Raw> raws = new ArrayList<>();
        TreeMap<String, String> agentFullNames = new TreeMap<>();   // username -> full name
        int total = 0;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql);
             ResultSet rs = pst.executeQuery()) {

            while (rs.next()) {
                total++;

                String usernamesCsv = rs.getString("usernames");
                String namesCsv = rs.getString("agent_names");
                String[] us = usernamesCsv.isEmpty() ? new String[0] : usernamesCsv.split("\\|");
                String[] ns = namesCsv.isEmpty() ? new String[0] : namesCsv.split("\\|");
                for (int i = 0; i < us.length; i++) {
                    agentFullNames.put(us[i], i < ns.length ? ns[i] : us[i]);
                }

                double lat = rs.getDouble("latitude");
                boolean hasLat = !rs.wasNull();
                double lon = rs.getDouble("longitude");
                boolean hasLon = !rs.wasNull();
                if (!hasLat || !hasLon) continue;   // no position yet: counted, but not drawn

                Raw r = new Raw();
                r.id = rs.getString("client_id");
                r.name = nz(rs.getString("client_name"));
                r.address = nz(rs.getString("address"));
                r.lat = lat;
                r.lon = lon;
                for (String u : us) r.users.add(u);
                r.agentNames = namesCsv.isEmpty() ? "" : String.join(", ", ns);
                raws.add(r);
            }
        }

        // Color per agent
        Map<String, Color> colors = new TreeMap<>();
        int idx = 0;
        for (String username : agentFullNames.keySet()) {
            colors.put(username, MapView.PALETTE[idx % MapView.PALETTE.length]);
            idx++;
        }

        MarkerSnapshot snap = new MarkerSnapshot();
        snap.total = total;
        Set<String> present = new java.util.TreeSet<>();
        boolean anyUnassigned = false;

        for (Raw r : raws) {
            Color color;
            if (r.users.isEmpty()) {
                color = MapView.C_UNASSIGNED;
                anyUnassigned = true;
            } else {
                color = colors.get(r.users.get(0));   // first agent decides the color
                present.addAll(r.users);
            }
            snap.markers.add(new ClientMarker(r.id, r.name, r.address, r.agentNames, r.users,
                    r.lat, r.lon, color));
        }

        for (String username : present) {
            snap.legend.add(new LegendEntry(agentFullNames.get(username), colors.get(username)));
        }
        if (anyUnassigned) {
            snap.legend.add(new LegendEntry("Unassigned", MapView.C_UNASSIGNED));
        }
        return snap;
    }

    /** Applies the current agent selection to the map. */
    private void updateMap(boolean fit) {
        List<ClientMarker> shown = new ArrayList<>();
        for (ClientMarker m : allMarkers) {
            if (selectedUsername == null || m.users.contains(selectedUsername)) {
                shown.add(m);
            }
        }
        map.setMarkers(shown);

        String info;
        if (markersError != null) {
            info = markersError;
        } else if (!markersLoaded) {
            info = "Loading client locations...";
        } else if (selectedUsername == null) {
            info = allMarkers.isEmpty()
                    ? "No clients have coordinates yet"
                    : allMarkers.size() + " of " + totalClients + " clients on map";
        } else {
            info = shown.size() + " of " + selectedClientCount + " clients of " + selectedName + " on map";
        }
        map.setInfo(info);

        if (fit) map.fitAll();
    }

    // =====================================================
    // MAP SUPPORT CLASSES
    // =====================================================

    /** One client that has a position. */
    static class ClientMarker {
        final String id, name, address, agentNames;
        final List<String> users;       // usernames of the agents assigned to this client
        final double lat, lon;
        final Color color;

        ClientMarker(String id, String name, String address, String agentNames, List<String> users,
                     double lat, double lon, Color color) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.agentNames = agentNames;
            this.users = users;
            this.lat = lat;
            this.lon = lon;
            this.color = color;
        }
    }

    /** One line of the map legend. */
    static class LegendEntry {
        final String text;
        final Color color;

        LegendEntry(String text, Color color) {
            this.text = text;
            this.color = color;
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
        private static final int MAX_LEGEND_ROWS = 8;

        static final Color[] PALETTE = {
                Color.decode("#2563EB"), Color.decode("#16A34A"), Color.decode("#F59E0B"),
                Color.decode("#8B5CF6"), Color.decode("#DC2626"), Color.decode("#0891B2"),
                Color.decode("#DB2777"), Color.decode("#65A30D")
        };
        static final Color C_UNASSIGNED = Color.decode("#9CA3AF");

        private static final Color MAP_BG         = Color.decode("#3C4043");
        private static final Color PLACEHOLDER    = Color.decode("#3F4448");
        private static final Color PLACEHOLDER_LN = Color.decode("#4B5156");
        private static final Color TEXT_DARK      = Color.decode("#1E293B");

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
        private List<ClientMarker> markers = new ArrayList<>();
        private List<LegendEntry> legend = new ArrayList<>();
        private String selectedId = null;
        private String info = "";
        private boolean pendingFit = false;
        private Consumer<ClientMarker> onMarkerClick;
        private Runnable onShowAll;

        MapView() {
            setBackground(MAP_BG);
            setLayout(null);
            setBorder(BorderFactory.createLineBorder(Color.decode("#2B2F32")));
            ToolTipManager.sharedInstance().registerComponent(this);

            // Default view (Metro Manila area) until clients are known
            cx = lonToX(120.9842, zoom);
            cy = latToY(14.5995, zoom);

            add(makeMapButton("+", 10, 10, 30, 30, () -> zoomAt(getWidth() / 2, getHeight() / 2, 1)));
            add(makeMapButton("\u2212", 10, 44, 30, 30, () -> zoomAt(getWidth() / 2, getHeight() / 2, -1)));
            add(makeMapButton("Fit all", 10, 78, 60, 26, this::fitAll));
            add(makeMapButton("All agents", 10, 108, 80, 26, () -> {
                if (onShowAll != null) onShowAll.run();
            }));

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
                    ClientMarker m = markerAt(e.getX(), e.getY());
                    if (m != null) {
                        selectedId = m.id;
                        repaint();
                        if (onMarkerClick != null) onMarkerClick.accept(m);
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

        void setOnMarkerClick(Consumer<ClientMarker> cb) {
            this.onMarkerClick = cb;
        }

        void setOnShowAll(Runnable r) {
            this.onShowAll = r;
        }

        void setMarkers(List<ClientMarker> list) {
            this.markers = new ArrayList<>(list);
            repaint();
        }

        void setLegend(List<LegendEntry> list) {
            this.legend = new ArrayList<>(list);
            repaint();
        }

        void setInfo(String text) {
            this.info = text;
            repaint();
        }

        void setSelectedClient(String id) {
            this.selectedId = id;
            repaint();
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
            for (ClientMarker m : markers) {
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

        private Point toScreen(ClientMarker m, int w, int h) {
            int sx = (int) Math.round(lonToX(m.lon, zoom) - cx + w / 2.0);
            int sy = (int) Math.round(latToY(m.lat, zoom) - cy + h / 2.0);
            return new Point(sx, sy);
        }

        private void paintMarkers(Graphics2D g, int w, int h) {
            ClientMarker selected = null;
            for (ClientMarker m : markers) {
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

        private void drawMarker(Graphics2D g, ClientMarker m, boolean sel, int w, int h) {
            Point p = toScreen(m, w, h);
            if (p.x < -80 || p.y < -60 || p.x > w + 80 || p.y > h + 60) return;

            int r = sel ? 11 : 8;
            Color base = m.color;

            if (sel) {
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 70));
                g.fillOval(p.x - r - 6, p.y - r - 6, (r + 6) * 2, (r + 6) * 2);
            }
            g.setColor(Color.WHITE);
            g.fillOval(p.x - r - 2, p.y - r - 2, (r + 2) * 2, (r + 2) * 2);
            g.setColor(base);
            g.fillOval(p.x - r, p.y - r, r * 2, r * 2);

            g.setFont(LABEL_FONT);
            FontMetrics fm = g.getFontMetrics();
            String label = m.name.length() > 22 ? m.name.substring(0, 21) + "\u2026" : m.name;
            int tw = fm.stringWidth(label);
            int bx = p.x - tw / 2 - 4;
            int by = p.y + r + 5;
            g.setColor(new Color(255, 255, 255, 225));
            g.fill(new RoundRectangle2D.Double(bx, by, tw + 8, 16, 8, 8));
            g.setColor(TEXT_DARK);
            g.drawString(label, bx + 4, by + 12);
        }

        private void paintOverlays(Graphics2D g, int w, int h) {
            g.setFont(SMALL_FONT);
            FontMetrics fm = g.getFontMetrics();

            // Legend (top right): one row per agent, plus "Unassigned" if needed
            if (!legend.isEmpty()) {
                int rows = Math.min(legend.size(), MAX_LEGEND_ROWS);
                boolean more = legend.size() > MAX_LEGEND_ROWS;
                String moreText = "+" + (legend.size() - MAX_LEGEND_ROWS) + " more";

                int textW = more ? fm.stringWidth(moreText) : 0;
                String[] texts = new String[rows];
                for (int i = 0; i < rows; i++) {
                    String t = legend.get(i).text;
                    texts[i] = t.length() > 20 ? t.substring(0, 19) + "\u2026" : t;
                    textW = Math.max(textW, fm.stringWidth(texts[i]));
                }
                int boxW = textW + 38;
                int boxH = 8 + (rows + (more ? 1 : 0)) * 18;
                int bx = w - boxW - 10, by = 10;
                g.setColor(new Color(255, 255, 255, 225));
                g.fill(new RoundRectangle2D.Double(bx, by, boxW, boxH, 10, 10));
                for (int i = 0; i < rows; i++) {
                    g.setColor(legend.get(i).color);
                    g.fillOval(bx + 10, by + 8 + i * 18, 10, 10);
                    g.setColor(TEXT_DARK);
                    g.drawString(texts[i], bx + 28, by + 17 + i * 18);
                }
                if (more) {
                    g.setColor(Color.decode("#6B7280"));
                    g.drawString(moreText, bx + 28, by + 17 + rows * 18);
                }
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

        // ----- hit testing and tooltips -----

        private ClientMarker markerAt(int mx, int my) {
            int w = getWidth(), h = getHeight();
            for (int i = markers.size() - 1; i >= 0; i--) {
                ClientMarker m = markers.get(i);
                Point p = toScreen(m, w, h);
                if (Math.hypot(p.x - mx, p.y - my) <= 13) return m;
            }
            return null;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            ClientMarker m = markerAt(e.getX(), e.getY());
            if (m == null) return null;
            return "<html><b>" + esc(m.name) + "</b>"
                    + (m.address.isEmpty() ? "" : "<br>" + esc(m.address))
                    + "<br>Agent: " + (m.agentNames.isEmpty() ? "Unassigned" : esc(m.agentNames))
                    + "</html>";
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