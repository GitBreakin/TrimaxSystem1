package com.trimaxsystem;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Live GPS Monitoring: where the vehicles last reported themselves, with an honest freshness state.
 *
 * - Only drivers' vehicles report positions (the database keeps one "last known position" per vehicle).
 *   There is no live position for agents and no history. Nothing is guessed or animated.
 * - A position is CURRENT only if it was updated within the configured limit; otherwise it is STALE and
 *   drawn as a ring, labelled "stale". A vehicle with no coordinates is listed as "No location".
 * - Locations refresh on a configurable interval, only while this page is showing.
 * - Selecting a vehicle shows its details and, if the driver has an In Progress booking whose customer
 *   has coordinates, the destination and the straight-line distance (not a road route).
 *
 * The refresh interval and the stale limit are remembered on this computer (Java Preferences).
 */
public class LiveGpsPanel extends JPanel {

    private static final Preferences PREFS = Preferences.userNodeForPackage(LiveGpsPanel.class);
    private static final String KEY_REFRESH = "gps.refreshSeconds";
    private static final String KEY_STALE = "gps.staleMinutes";
    private static final int[] REFRESH_CHOICES = {10, 15, 30, 60};
    private static final int[] STALE_CHOICES = {2, 5, 10, 15, 30};
    private static final int DEFAULT_REFRESH = 15;
    private static final int DEFAULT_STALE = 5;
    private static final String[] VEHICLE_STATUSES = {
            "Available", "On Delivery", "Unavailable"};
    private static final String DEST_ID = "__destination";

    private static final String[] COLS = {"Vehicle", "Driver", "Vehicle status", "Location", "Last update"};

    // ---------- controls ----------
    private final JTextField txtSearch = new JTextField(16);
    private final JComboBox<String> cmbStatus = new JComboBox<>();
    private final JComboBox<String> cmbLocation = new JComboBox<>(new String[]{"All", "Current", "Stale", "No location"});
    private final JComboBox<Choice> cmbRefresh = new JComboBox<>();
    private final JComboBox<Choice> cmbStale = new JComboBox<>();
    private final JLabel lblUpdated = new JLabel(" ");
    private final JLabel lblActive = new JLabel(" ");
    private final JButton btnRefresh = UiKit.primaryButton("Refresh now");

    private final JLabel chipCurrent = chip();
    private final JLabel chipStale = chip();
    private final JLabel chipNone = chip();
    private final JLabel chipTotal = chip();
    private final JLabel lblShowing = new JLabel(" ");

    // ---------- map, list, details ----------
    private final TileMapView map = new TileMapView();
    private final DefaultTableModel model = new DefaultTableModel(null, COLS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 4 ? UpdateCell.class : Object.class;   // lets "Last update" sort by age, not by text
        }
    };
    private final JTable table = new JTable(model);
    private final JPanel details = new ScrollPanel(new BorderLayout());

    // ---------- state ----------
    private GpsService.Snapshot snapshot;                 // last successful fetch (null before the first)
    private String lastError;                             // error of the latest refresh, or null
    private List<GpsService.VehiclePosition> visible = new ArrayList<>();
    private String selectedId;
    private boolean fetching;
    private boolean everLoaded;
    private boolean suppressTableEvents;
    private boolean loadingControls = true;

    private enum DestState { NONE, LOADING, LOADED, ERROR }

    private DestState destState = DestState.NONE;
    private GpsService.Destination destination;
    private String destMessage = "";
    private int destRequest = 0;

    private final Timer pollTimer;
    private int lastDetailsWidth = 0;

    private static final class Choice {
        final String label;
        final int value;

        Choice(String label, int value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public LiveGpsPanel() {
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(14, 20, 16, 20));

        add(buildControls(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildMapSide(), buildListSide());
        split.setResizeWeight(0.66);
        split.setDividerSize(8);
        split.setBorder(null);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);

        pollTimer = new Timer(refreshSeconds() * 1000, e -> load(false));
        pollTimer.setRepeats(true);

        // Load the first time the page is actually shown, and poll only while it is showing.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) {
                load(true);
                pollTimer.start();
            } else {
                pollTimer.stop();
            }
        });

        details.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (Math.abs(details.getWidth() - lastDetailsWidth) > 20) renderDetails();
            }
        });

        map.setLegend(buildLegend());
        map.setOnMarkerClick(id -> {
            if (!DEST_ID.equals(id)) selectVehicle(id, false);
        });
        map.setMessage("Loading vehicle locations...");
        renderDetails();
    }

    // =====================================================
    // BUILDING THE UI
    // =====================================================

    private JComponent buildControls() {
        UiKit.styleField(txtSearch);
        txtSearch.setToolTipText("Vehicle plate/ID, driver name or username, or vehicle type");
        txtSearch.getDocument().addDocumentListener(new SimpleDocListener(this::applyFilters));
        cmbStatus.addItem("All vehicle statuses");
        for (String s : VEHICLE_STATUSES) cmbStatus.addItem(s);
        cmbStatus.addActionListener(e -> applyFilters());
        cmbLocation.addActionListener(e -> applyFilters());
        for (JComboBox<?> c : new JComboBox<?>[]{cmbStatus, cmbLocation, cmbRefresh, cmbStale}) c.setFont(Theme.SUBTLE);

        int refresh = refreshSeconds();
        for (int s : REFRESH_CHOICES) {
            Choice c = new Choice(s + " seconds", s);
            cmbRefresh.addItem(c);
            if (s == refresh) cmbRefresh.setSelectedItem(c);
        }
        int stale = staleMinutes();
        for (int m : STALE_CHOICES) {
            Choice c = new Choice(m + " minutes", m);
            cmbStale.addItem(c);
            if (m == stale) cmbStale.setSelectedItem(c);
        }
        loadingControls = false;

        cmbRefresh.setToolTipText("How often the locations are reloaded while this page is open");
        cmbRefresh.addActionListener(e -> {
            if (loadingControls) return;
            int v = ((Choice) cmbRefresh.getSelectedItem()).value;
            PREFS.putInt(KEY_REFRESH, v);
            pollTimer.setDelay(v * 1000);
            pollTimer.restart();
        });
        cmbStale.setToolTipText("A position older than this is shown as stale, not as live");
        cmbStale.addActionListener(e -> {
            if (loadingControls) return;
            PREFS.putInt(KEY_STALE, ((Choice) cmbStale.getSelectedItem()).value);
            applyFilters();   // re-classify with the stored ages, no new query needed
        });

        JButton btnClear = UiKit.secondaryButton("Clear filters");
        btnClear.addActionListener(e -> {
            txtSearch.setText("");
            cmbStatus.setSelectedIndex(0);
            cmbLocation.setSelectedIndex(0);
        });
        btnRefresh.addActionListener(e -> load(true));

        JPanel row1 = UiKit.flowRow();
        row1.add(UiKit.caption("Search"));
        row1.add(txtSearch);
        row1.add(UiKit.caption("Vehicle status"));
        row1.add(cmbStatus);
        row1.add(UiKit.caption("Location"));
        row1.add(cmbLocation);
        row1.add(btnClear);

        lblUpdated.setFont(Theme.SUBTLE);
        lblUpdated.setForeground(Theme.MUTED);
        JPanel row2 = UiKit.flowRow();
        row2.add(UiKit.caption("Refresh every"));
        row2.add(cmbRefresh);
        row2.add(UiKit.caption("Stale after"));
        row2.add(cmbStale);
        row2.add(btnRefresh);
        row2.add(lblUpdated);

        JLabel note = new JLabel("Only drivers' vehicles report a position, and only the latest one is stored. "
                + "Agents do not report a live position; no history is kept.");
        note.setFont(new Font("SansSerif", Font.PLAIN, 12));
        note.setForeground(Theme.MUTED);
        lblActive.setFont(Theme.SUBTLE);
        lblActive.setForeground(Theme.BLUE_DARK);
        JPanel row3 = UiKit.flowRow();
        row3.add(note);
        JPanel row4 = UiKit.flowRow();
        row4.add(lblActive);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(8, 12, 4, 12)));
        card.add(row1);
        card.add(row2);
        card.add(row3);
        card.add(row4);
        return card;
    }

    private JComponent buildMapSide() {
        map.setMinimumSize(new Dimension(300, 300));
        map.setPreferredSize(new Dimension(700, 500));
        return map;
    }

    private JComponent buildListSide() {
        // summary chips
        JPanel chips = new JPanel(new GridLayout(1, 4, 8, 0));
        chips.setOpaque(false);
        chips.add(chipCurrent);
        chips.add(chipStale);
        chips.add(chipNone);
        chips.add(chipTotal);

        lblShowing.setFont(Theme.SUBTLE);
        lblShowing.setForeground(Theme.MUTED);

        styleTable();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.CARD);

        JPanel listCard = new JPanel(new BorderLayout(0, 6));
        listCard.setOpaque(false);
        listCard.add(lblShowing, BorderLayout.NORTH);
        listCard.add(scroll, BorderLayout.CENTER);

        details.setBackground(Theme.CARD);
        details.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(12, 14, 12, 14)));
        JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setBorder(null);
        detailScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listCard, detailScroll);
        vertical.setResizeWeight(0.5);
        vertical.setDividerSize(8);
        vertical.setBorder(null);
        vertical.setOpaque(false);

        JPanel side = new JPanel(new BorderLayout(0, 10));
        side.setOpaque(false);
        side.setMinimumSize(new Dimension(340, 300));
        side.setPreferredSize(new Dimension(420, 500));
        side.add(chips, BorderLayout.NORTH);
        side.add(vertical, BorderLayout.CENTER);
        return side;
    }

    private static JLabel chip() {
        JLabel l = new JLabel(" ", SwingConstants.CENTER);
        l.setFont(new Font("SansSerif", Font.BOLD, 12));
        l.setOpaque(true);
        l.setBackground(Theme.CARD);
        l.setForeground(Theme.TEXT);
        l.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(6, 4, 6, 4)));
        return l;
    }

    private void styleTable() {
        table.setRowHeight(30);
        table.setFont(Theme.SUBTLE);
        table.setForeground(Theme.TEXT);
        table.setBackground(Theme.CARD);
        table.setSelectionBackground(Color.decode("#DBEAFE"));
        table.setSelectionForeground(Theme.TEXT);
        table.setShowVerticalLines(false);
        table.setGridColor(Theme.BORDER);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, sel, false, row, col);
                String text = value == null ? "" : value.toString();
                setFont(Theme.SUBTLE);
                setForeground(Theme.TEXT);
                if (col == 3) {
                    // Symbol + words, matching the map: filled = current, ring = stale, dash = none.
                    setFont(Theme.NAV_ON);
                    if (text.startsWith("Current")) setForeground(Theme.OK);
                    else if (text.startsWith("Stale")) setForeground(Theme.WARN);
                    else setForeground(Theme.MUTED);
                    text = (text.startsWith("Current") ? "● " : text.startsWith("Stale") ? "○ " : "– ") + text;
                }
                setText(text);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                return this;
            }
        });
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        table.getTableHeader().setToolTipText("Click a column to sort");
        int[] widths = {80, 120, 95, 95, 105};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || suppressTableEvents) return;
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) return;
            String id = (String) model.getValueAt(table.convertRowIndexToModel(viewRow), 0);
            selectVehicle(id, true);
        });
    }

    private static List<TileMapView.LegendItem> buildLegend() {
        List<TileMapView.LegendItem> l = new ArrayList<>();
        l.add(TileMapView.LegendItem.heading("Position"));
        l.add(TileMapView.LegendItem.item("Current", Theme.MUTED, TileMapView.Shape.SOLID));
        l.add(TileMapView.LegendItem.item("Stale (last known)", Theme.MUTED, TileMapView.Shape.HOLLOW));
        l.add(TileMapView.LegendItem.item("Destination", Theme.NAVY, TileMapView.Shape.DESTINATION));
        l.add(TileMapView.LegendItem.heading("Vehicle status"));
        for (String s : VEHICLE_STATUSES) {
            l.add(TileMapView.LegendItem.item(s, statusColor(s), TileMapView.Shape.SOLID));
        }
        return l;
    }

    static Color statusColor(String status) {
        if ("Available".equals(status)) return Color.decode("#16A34A");
        if ("On Delivery".equals(status)) return Color.decode("#2563EB");
        //if ("Maintenance".equals(status)) return Color.decode("#D97706");
        if ("Unavailable".equals(status)) return Color.decode("#475569");
        //if ("On Vacation".equals(status)) return Color.decode("#7C3AED");
        //if ("Suspended".equals(status)) return Color.decode("#DC2626");
        return Color.decode("#6B7280");
    }

    // =====================================================
    // SETTINGS
    // =====================================================

    private static int refreshSeconds() {
        int v = PREFS.getInt(KEY_REFRESH, DEFAULT_REFRESH);
        for (int c : REFRESH_CHOICES) if (c == v) return v;
        return DEFAULT_REFRESH;
    }

    private static int staleMinutes() {
        int v = PREFS.getInt(KEY_STALE, DEFAULT_STALE);
        for (int c : STALE_CHOICES) if (c == v) return v;
        return DEFAULT_STALE;
    }

    private long staleSeconds() {
        Choice c = (Choice) cmbStale.getSelectedItem();
        return (c == null ? DEFAULT_STALE : c.value) * 60L;
    }

    // =====================================================
    // LOADING
    // =====================================================

    /**
     * Fetches the locations in the background. A manual refresh or the first load (verifyAdmin) also
     * re-checks that the signed-in user is still an administrator.
     */
    private void load(boolean verifyAdmin) {
        if (fetching) return;   // never let two fetches overlap
        fetching = true;
        if (verifyAdmin) btnRefresh.setEnabled(false);
        if (snapshot == null) map.setMessage("Loading vehicle locations...");

        new SwingWorker<GpsService.Snapshot, Void>() {
            @Override
            protected GpsService.Snapshot doInBackground() throws Exception {
                return GpsService.fetch(verifyAdmin);
            }

            @Override
            protected void done() {
                fetching = false;
                btnRefresh.setEnabled(true);
                try {
                    snapshot = get();
                    lastError = null;
                    everLoaded = true;
                    lblUpdated.setForeground(Theme.MUTED);
                    lblUpdated.setText("Updated " + Theme.formatWhen(new java.sql.Timestamp(snapshot.fetchedAtMillis))
                            + " (Manila time)");
                    applyFilters();
                    refreshDestination();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof AuthService.AuthException) {
                        pollTimer.stop();
                        Window w = SwingUtilities.getWindowAncestor(LiveGpsPanel.this);
                        JOptionPane.showMessageDialog(LiveGpsPanel.this, cause.getMessage(),
                                "Not allowed", JOptionPane.ERROR_MESSAGE);
                        AuthFrame.logout(w);   // no longer a valid admin session: back to the login screen
                        return;
                    }
                    lastError = describe(cause);
                    lblUpdated.setForeground(Theme.DANGER);
                    lblUpdated.setText("Could not refresh");
                    ex.printStackTrace();
                    applyFilters();
                }
            }
        }.execute();
    }

    private static String describe(Throwable cause) {
        if (cause instanceof java.sql.SQLException && "42703".equals(((java.sql.SQLException) cause).getSQLState())) {
            return "the vehicles table has no location columns (latitude, longitude, last_location_at)";
        }
        String m = cause.getMessage();
        return m == null || m.isEmpty() ? cause.toString() : m;
    }

    // =====================================================
    // SHOWING THE DATA
    // =====================================================

    private void applyFilters() {
        // ---- first load not finished, or failed with nothing to show ----
        if (snapshot == null) {
            visible = new ArrayList<>();
            model.setRowCount(0);
            map.setMarkers(new ArrayList<>());
            map.clearLine();
            if (lastError != null) {
                map.setBanner(null, false);
                map.setMessage("Could not load vehicle locations:\n" + lastError + "\nUse \"Refresh now\" to try again.");
                setChips(0, 0, 0, 0);
                lblShowing.setText("No data loaded");
            }
            renderDetails();
            return;
        }

        long staleSec = staleSeconds();
        String search = txtSearch.getText().trim().toLowerCase(Locale.ROOT);
        String statusFilter = cmbStatus.getSelectedIndex() > 0 ? (String) cmbStatus.getSelectedItem() : null;
        String locFilter = (String) cmbLocation.getSelectedItem();

        int current = 0, stale = 0, none = 0;
        List<GpsService.VehiclePosition> list = new ArrayList<>();
        for (GpsService.VehiclePosition p : snapshot.vehicles) {
            GpsService.Freshness f = p.freshness(staleSec);
            if (f == GpsService.Freshness.CURRENT) current++;
            else if (f == GpsService.Freshness.STALE) stale++;
            else none++;

            if (statusFilter != null && !statusFilter.equals(p.vehicleStatus)) continue;
            if (!"All".equals(locFilter) && !f.label.equals(locFilter)) continue;
            if (!search.isEmpty() && !matches(p, search)) continue;
            list.add(p);
        }
        visible = list;
        setChips(current, stale, none, snapshot.vehicles.size());

        // ---- active filters line ----
        List<String> parts = new ArrayList<>();
        if (!search.isEmpty()) parts.add("search \"" + txtSearch.getText().trim() + "\"");
        if (statusFilter != null) parts.add("status " + statusFilter);
        if (!"All".equals(locFilter)) parts.add("location " + locFilter);
        lblActive.setText(parts.isEmpty() ? " " : "Active filters: " + String.join(", ", parts));

        // ---- table ----
        if (selectedId != null && list.stream().noneMatch(p -> p.vehicleId.equals(selectedId))) {
            selectedId = null;
            destState = DestState.NONE;
            destination = null;
            destRequest++;
        }
        suppressTableEvents = true;
        model.setRowCount(0);
        for (GpsService.VehiclePosition p : list) {
            GpsService.Freshness f = p.freshness(staleSec);
            model.addRow(new Object[]{
                    p.vehicleId, p.driverLabel(), nz(p.vehicleStatus, "-"),
                    f.label,
                    new UpdateCell(p.lastLocationAt == null ? (p.hasCoordinates() ? "No timestamp" : "Never")
                            : agoText(p.ageSeconds()), p.ageSeconds())});
        }
        suppressTableEvents = false;
        syncTableSelection();
        lblShowing.setText("Showing " + list.size() + " of " + snapshot.vehicles.size() + " vehicle(s)"
                + (snapshot.truncated ? " (only the first " + GpsService.MAX_VEHICLES + " are loaded)" : ""));

        // ---- map ----
        updateMap(staleSec);

        // ---- map messages and banner ----
        if (lastError != null) {
            String when = Theme.formatWhen(new java.sql.Timestamp(snapshot.fetchedAtMillis));
            map.setBanner("Could not refresh (" + lastError + "). Showing data from " + when + ".", true);
        } else {
            map.setBanner(null, false);
        }
        long onMap = list.stream().filter(GpsService.VehiclePosition::hasCoordinates).count();
        if (snapshot.vehicles.isEmpty()) {
            map.setMessage("There are no vehicles yet.");
        } else if (list.isEmpty()) {
            map.setMessage("No vehicles match these filters.");
        } else if (onMap == 0) {
            map.setMessage("None of these vehicles has reported a location yet.\nThey are listed on the right as \"No location\".");
        } else {
            map.setMessage(null);
        }
        map.setInfo(onMap + " of " + list.size() + " shown vehicle(s) on the map");
        renderDetails();
    }

    private static boolean matches(GpsService.VehiclePosition p, String q) {
        return contains(p.vehicleId, q) || contains(p.driverName, q) || contains(p.driverUsername, q)
                || contains(p.vehicleType, q);
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isEmpty() ? fallback : s;
    }

    private void setChips(int current, int stale, int none, int total) {
        chipCurrent.setText("● Current: " + current);
        chipStale.setText("○ Stale: " + stale);
        chipNone.setText("– No location: " + none);
        chipTotal.setText("Vehicles: " + total);
    }

    private void updateMap(long staleSec) {
        List<TileMapView.Marker> markers = new ArrayList<>();
        GpsService.VehiclePosition sel = null;
        for (GpsService.VehiclePosition p : visible) {
            if (!p.hasCoordinates()) continue;
            if (p.vehicleId.equals(selectedId)) sel = p;
            boolean isStale = p.freshness(staleSec) == GpsService.Freshness.STALE;
            String tip = "<html><b>" + TileMapView.esc(p.vehicleId) + "</b>"
                    + (p.vehicleType == null ? "" : " · " + TileMapView.esc(p.vehicleType))
                    + "<br>Driver: " + TileMapView.esc(p.driverLabel())
                    + "<br>Vehicle status: " + TileMapView.esc(nz(p.vehicleStatus, "-"))
                    + "<br>" + (isStale ? "<b>Stale</b>, last known position" : "Current position")
                    + "<br>Last update: " + TileMapView.esc(lastUpdateText(p)) + "</html>";
            markers.add(new TileMapView.Marker(p.vehicleId,
                    p.vehicleId + (isStale ? " (stale)" : ""), tip, p.lat, p.lon,
                    statusColor(p.vehicleStatus),
                    isStale ? TileMapView.Shape.HOLLOW : TileMapView.Shape.SOLID));
        }

        // destination of the selected vehicle's current delivery, only when its coordinates are known
        map.clearLine();
        if (sel != null && destState == DestState.LOADED && destination != null && destination.hasCoordinates()) {
            markers.add(new TileMapView.Marker(DEST_ID, "Booking #" + destination.bookingId,
                    "<html><b>Destination</b><br>Booking #" + destination.bookingId + "<br>"
                            + TileMapView.esc(destination.clientName) + "<br>"
                            + TileMapView.esc(nz(destination.address, "")) + "</html>",
                    destination.lat, destination.lon, Theme.NAVY, TileMapView.Shape.DESTINATION));
            double km = GpsService.distanceKm(sel.lat, sel.lon, destination.lat, destination.lon);
            map.setLine(sel.lat, sel.lon, destination.lat, destination.lon,
                    String.format(Locale.ENGLISH, "%.1f km straight line (not a road route)", km));
        }
        map.setMarkers(markers);
        map.setSelected(selectedId);
    }

    private String lastUpdateText(GpsService.VehiclePosition p) {
        if (p.lastLocationAt == null) return p.hasCoordinates() ? "No timestamp" : "Never";
        return Theme.formatWhen(p.lastLocationAt) + " (" + agoText(p.ageSeconds()) + ")";
    }

    private static String agoText(double seconds) {
        if (Double.isNaN(seconds)) return "unknown";
        long s = (long) Math.max(0, seconds);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " day(s) ago";
    }

    // =====================================================
    // SELECTION AND DETAILS
    // =====================================================

    private void selectVehicle(String id, boolean fromTable) {
        if (id != null && id.equals(selectedId)) {
            if (fromTable) map.focusMarker(id);
            return;
        }
        selectedId = id;
        destState = DestState.NONE;
        destination = null;
        destRequest++;
        syncTableSelection();
        refreshDestination();
        updateMap(staleSeconds());
        if (fromTable && id != null) map.focusMarker(id);
        renderDetails();
    }

    private void syncTableSelection() {
        suppressTableEvents = true;
        try {
            table.clearSelection();
            if (selectedId == null) return;
            for (int r = 0; r < model.getRowCount(); r++) {
                if (selectedId.equals(model.getValueAt(r, 0))) {
                    int vr = table.convertRowIndexToView(r);
                    if (vr >= 0) {
                        table.setRowSelectionInterval(vr, vr);
                        table.scrollRectToVisible(table.getCellRect(vr, 0, true));
                    }
                    return;
                }
            }
        } finally {
            suppressTableEvents = false;
        }
    }

    private GpsService.VehiclePosition selected() {
        if (selectedId == null) return null;
        for (GpsService.VehiclePosition p : visible) if (p.vehicleId.equals(selectedId)) return p;
        return null;
    }

    /** Loads the selected driver's current delivery (if any) in the background. */
    private void refreshDestination() {
        GpsService.VehiclePosition p = selected();
        if (p == null) return;
        if (p.driverUsername == null) {
            destState = DestState.NONE;
            destMessage = "This vehicle has no driver, so there is no delivery to show.";
            renderDetails();
            return;
        }
        final int req = ++destRequest;
        final String username = p.driverUsername;
        if (destState != DestState.LOADED) {
            destState = DestState.LOADING;
            renderDetails();
        }
        new SwingWorker<GpsService.Destination, Void>() {
            @Override
            protected GpsService.Destination doInBackground() throws Exception {
                return GpsService.loadDestination(username);
            }

            @Override
            protected void done() {
                if (req != destRequest) return;   // another vehicle was selected meanwhile
                try {
                    destination = get();
                    destState = destination == null ? DestState.NONE : DestState.LOADED;
                    destMessage = destination == null ? "This driver has no booking In Progress." : "";
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    destination = null;
                    destState = DestState.ERROR;
                    destMessage = "Could not load the delivery: " + describe(cause);
                }
                updateMap(staleSeconds());
                renderDetails();
            }
        }.execute();
    }

    private void renderDetails() {
        details.removeAll();
        GpsService.VehiclePosition p = selected();
        if (p == null) {
            JLabel l = new JLabel("<html>Select a vehicle in the list or on the map to see its details.</html>");
            l.setFont(Theme.SUBTLE);
            l.setForeground(Theme.MUTED);
            details.add(l, BorderLayout.NORTH);
            details.revalidate();
            details.repaint();
            return;
        }

        long staleSec = staleSeconds();
        GpsService.Freshness f = p.freshness(staleSec);
        lastDetailsWidth = details.getWidth();
        final int vw = Math.max(160, Math.min(560, details.getWidth() - 230));   // wrap width of the value column

        JLabel title = new JLabel(p.vehicleId + (p.vehicleType == null ? "" : "  -  " + p.vehicleType));
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(Theme.TEXT);
        title.setBorder(new EmptyBorder(0, 0, 8, 0));

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        int r = 0;
        String driver = p.driverUsername == null ? "No driver assigned"
                : nz(p.driverName, p.driverUsername) + " (" + p.driverUsername + ")"
                + ("Active".equals(p.driverAccountStatus) ? "" : ", account " + nz(p.driverAccountStatus, "unknown"));
        UiKit.addDetailRow(grid, r++, "Driver", driver, vw);
        UiKit.addDetailRow(grid, r++, "Vehicle status", nz(p.vehicleStatus, "-"), vw);
        UiKit.addDetailRow(grid, r++, "Location", locationSentence(p, f, staleSec), vw);
        if (p.hasCoordinates()) {
            UiKit.addDetailRow(grid, r++, "Coordinates",
                    String.format(Locale.ENGLISH, "%.5f, %.5f", p.lat, p.lon), vw);
        }
        UiKit.addDetailRow(grid, r++, "Last update", lastUpdateText(p), vw);

        // ---- current delivery ----
        switch (destState) {
            case LOADING:
                UiKit.addDetailRow(grid, r++, "Delivery", "Loading...", vw);
                break;
            case ERROR:
            case NONE:
                UiKit.addDetailRow(grid, r++, "Delivery", destMessage.isEmpty() ? "-" : destMessage, vw);
                break;
            default:
                GpsService.Destination d = destination;
                UiKit.addDetailRow(grid, r++, "Delivery", "Booking #" + d.bookingId + " for " + d.clientName, vw);
                UiKit.addDetailRow(grid, r++, "Address", nz(d.address, "-"), vw);
                if (d.inProgressCount > 1) {
                    UiKit.addDetailRow(grid, r++, "Note", d.inProgressCount
                            + " bookings are In Progress for this driver; the latest is shown.", vw);
                }
                if (!d.hasCoordinates()) {
                    UiKit.addDetailRow(grid, r++, "Destination",
                            "This customer has no coordinates, so the destination cannot be shown on the map.", vw);
                } else if (!p.hasCoordinates()) {
                    UiKit.addDetailRow(grid, r++, "Distance", "Unknown: the vehicle has no position.", vw);
                } else {
                    double km = GpsService.distanceKm(p.lat, p.lon, d.lat, d.lon);
                    UiKit.addDetailRow(grid, r++, "Distance", String.format(Locale.ENGLISH,
                            "%.1f km in a straight line%s. This is not a road route.", km,
                            f == GpsService.Freshness.STALE ? ", from the last known position" : ""), vw);
                }
        }

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(title, BorderLayout.NORTH);
        body.add(grid, BorderLayout.CENTER);
        if (destState == DestState.LOADED) {
            final int bookingId = destination.bookingId;
            JButton open = UiKit.secondaryButton("View booking #" + bookingId);
            open.addActionListener(e -> new BookingDetailDialog(
                    SwingUtilities.getWindowAncestor(this), bookingId, () -> refreshDestination()).setVisible(true));
            JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));
            south.setOpaque(false);
            south.add(open);
            body.add(south, BorderLayout.SOUTH);
        }
        details.add(body, BorderLayout.NORTH);
        details.revalidate();
        details.repaint();
    }

    private static String locationSentence(GpsService.VehiclePosition p, GpsService.Freshness f, long staleSec) {
        long min = staleSec / 60;
        switch (f) {
            case CURRENT:
                return "Current: updated within the last " + min + " min.";
            case STALE:
                return Double.isNaN(p.ageSeconds())
                        ? "Stale: the position has no timestamp, so it cannot be shown to be current. It is the last known position."
                        : "Stale: the last update is older than " + min + " min. This is the last known position, not live.";
            default:
                return "No location: this vehicle has not reported a position.";
        }
    }

    /** "Last update" cell: shows text, sorts by age (newest first when ascending; unknown last). */
    private static final class UpdateCell implements Comparable<UpdateCell> {
        final String text;
        final double age;

        UpdateCell(String text, double age) {
            this.text = text;
            this.age = age;
        }

        @Override
        public int compareTo(UpdateCell o) {
            if (Double.isNaN(age) && Double.isNaN(o.age)) return 0;
            if (Double.isNaN(age)) return 1;
            if (Double.isNaN(o.age)) return -1;
            return Double.compare(age, o.age);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** A panel that always matches the scroll pane's width, so long text wraps instead of scrolling sideways. */
    private static final class ScrollPanel extends JPanel implements Scrollable {
        ScrollPanel(LayoutManager lm) {
            super(lm);
        }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 64; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Runs a callback for every text change of a field. */
    private static final class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable r;

        SimpleDocListener(Runnable r) {
            this.r = r;
        }

        public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }
}
