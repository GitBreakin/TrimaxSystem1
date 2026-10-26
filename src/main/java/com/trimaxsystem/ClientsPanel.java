package com.trimaxsystem;

import java.awt.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/**
 * Customers page: add, edit and delete customers, filter by assigned agent, and see where they are on a map
 * above the table. Only customers with a saved latitude and longitude appear on the map; the page says how many
 * do not, and never guesses a position. An admin sets or moves a location by picking it on the map (or typing it).
 * The Android app can also set it. Every change is
 * admin-checked and written to the audit log (when that table exists).
 */
public class ClientsPanel extends JPanel {

    private static final Pattern PHONE = Pattern.compile("09[0-9]{9}");
    private static final Color[] PALETTE = {
            Color.decode("#2563EB"), Color.decode("#059669"), Color.decode("#D97706"), Color.decode("#7C3AED"),
            Color.decode("#DB2777"), Color.decode("#0891B2"), Color.decode("#65A30D"), Color.decode("#DC2626")};
    private static final Color MULTI = Color.decode("#0F172A");

    /** One row of the table and, if it has a position, one marker. */
    private static final class Row {
        int id, agentCount;
        String name, address, contact, agents, firstAgent, agentCsv;
        Double lat, lon;

        boolean hasLocation() {
            return lat != null && lon != null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
        }
    }

    private static final class Data {
        final List<Row> rows = new ArrayList<>();
        final List<String> agentUsernames = new ArrayList<>();
        final List<String> agentNames = new ArrayList<>();
    }

    // form
    private final JTextField txtName = new JTextField(14);
    private final JTextField txtAddress = new JTextField(18);
    private final JTextField txtContact = new JTextField(11);
    private final JTextField txtLat = new JTextField(9);
    private final JTextField txtLon = new JTextField(9);
    private final JButton btnPick = UiKit.secondaryButton("Pick on map");
    private final JLabel locationNote = new JLabel(" ");
    private final JLabel formError = new JLabel(" ");
    private final JButton btnAdd = UiKit.primaryButton("Add");
    private final JButton btnUpdate = UiKit.secondaryButton("Update");
    private final JButton btnDelete = UiKit.dangerButton("Delete");
    private final JButton btnClear = UiKit.secondaryButton("Clear form");
    private final JButton btnClearFilters = UiKit.secondaryButton("Clear filters");
    private final JButton btnAssign = UiKit.secondaryButton("Assign agent...");

    // filters
    private final JTextField txtSearch = new JTextField(18);
    private final JComboBox<String> cmbAgent = new JComboBox<>(new String[]{"All agents", "Unassigned"});
    private final JButton btnSearch = UiKit.primaryButton("Search");
    private final JLabel summary = new JLabel(" ");

    // content
    private final TileMapView map = new TileMapView();
    private final JSplitPane split;
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"ID", "Customer", "Address", "Contact number", "Assigned agent", "Latitude", "Longitude"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final StatePanel state = new StatePanel();
    private final CardLayout tableCards = new CardLayout();
    private final JPanel tableArea = new JPanel(tableCards);

    private List<Row> shown = new ArrayList<>();
    private List<TileMapView.Marker> baseMarkers = new ArrayList<>();
    private boolean picking;
    private Double savedLat, savedLon;     // saved position of the selected customer, if any
    private final List<String> agentUsernames = new ArrayList<>();   // parallel to combo items from index 2
    private Integer selectedClientId;
    private boolean adjusting;       // true while the code (not the user) changes the selection or the combo
    private boolean busy;
    private int seq;
    private boolean loadedOnce;

    public ClientsPanel() {
        super(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 16, 12, 16));

        add(buildTopCard(), BorderLayout.NORTH);

        // map above the table
        JPanel mapCard = new JPanel(new BorderLayout());
        mapCard.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        mapCard.add(map, BorderLayout.CENTER);
        mapCard.setMinimumSize(new Dimension(100, 160));

        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(2).setPreferredWidth(240);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || adjusting) return;
            onRowSelected();
        });
        tableArea.setOpaque(false);
        tableArea.add(new JScrollPane(table), "table");
        tableArea.add(state, "state");
        tableArea.setMinimumSize(new Dimension(100, 120));

        mapCard.setPreferredSize(new Dimension(100, 420));
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mapCard, tableArea);
        split.setResizeWeight(0.7);   // extra height goes to the map first
        split.setBorder(null);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);

        map.setOnMarkerClick(this::selectClientById);
        map.setOnMapClick((lat, lon) -> {
            txtLat.setText(format6(lat));
            txtLon.setText(format6(lon));
            setPicking(false);
        });
        btnPick.addActionListener(e -> setPicking(!picking));
        javax.swing.event.DocumentListener coordListener = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { coordsEdited(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { coordsEdited(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { coordsEdited(); }
        };
        txtLat.getDocument().addDocumentListener(coordListener);
        txtLon.getDocument().addDocumentListener(coordListener);

        btnSearch.addActionListener(e -> load(false));
        txtSearch.addActionListener(e -> load(false));
        cmbAgent.addActionListener(e -> {
            if (!adjusting) load(false);
        });
        btnClear.addActionListener(e -> {
            clearForm();
            txtName.requestFocusInWindow();
        });
        btnClearFilters.addActionListener(e -> {
            txtSearch.setText("");
            adjusting = true;
            cmbAgent.setSelectedIndex(0);
            adjusting = false;
            load(false);
        });
        btnAdd.addActionListener(e -> addCustomer());
        btnUpdate.addActionListener(e -> updateCustomer());
        btnDelete.addActionListener(e -> deleteCustomer());
        btnAssign.addActionListener(e -> chooseAndAssignAgent());

        addHierarchyListener(e -> {
            if (!loadedOnce && isShowing()) {
                loadedOnce = true;
                SwingUtilities.invokeLater(() -> split.setDividerLocation(0.68));   // map takes about two thirds
                load(true);
            }
        });
    }

    // =====================================================
    // LAYOUT
    // =====================================================

    private JComponent buildTopCard() {
        UiKit.styleField(txtName);
        UiKit.styleField(txtAddress);
        UiKit.styleField(txtContact);
        ((AbstractDocument) txtContact.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr)
                    throws BadLocationException {
                if (text != null && text.matches("[0-9]+")
                        && fb.getDocument().getLength() + text.length() <= 11) {
                    super.insertString(fb, offset, text, attr);
                }
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                    throws BadLocationException {
                String inserted = text == null ? "" : text;
                if (inserted.matches("[0-9]*")
                        && fb.getDocument().getLength() - length + inserted.length() <= 11) {
                    super.replace(fb, offset, length, inserted, attrs);
                }
            }
        });
        UiKit.styleField(txtSearch);
        UiKit.styleField(txtLat);
        UiKit.styleField(txtLon);
        txtLat.setToolTipText("Latitude, -90 to 90. Click \"Pick on map\" and then the map, or type it.");
        txtLon.setToolTipText("Longitude, -180 to 180");
        locationNote.setForeground(Theme.MUTED);
        locationNote.setFont(Theme.SUBTLE);
        txtName.setToolTipText("Required, up to 100 characters");
        txtAddress.setToolTipText("Up to 150 characters");
        txtContact.setToolTipText("Enter 11 digits starting with 09.");
        formError.setForeground(Theme.DANGER);
        summary.setForeground(Theme.MUTED);
        summary.setFont(Theme.SUBTLE);

        JPanel form = UiKit.flowRow();
        form.add(UiKit.caption("Customer name"));
        form.add(txtName);
        form.add(UiKit.caption("Address"));
        form.add(txtAddress);
        form.add(UiKit.caption("Contact number"));
        form.add(txtContact);

        // the action buttons get their own row so none of them can be pushed out of view
        JPanel actions = UiKit.flowRow();
        actions.add(btnAdd);
        actions.add(btnUpdate);
        actions.add(btnDelete);
        actions.add(btnAssign);
        actions.add(btnClear);

        JPanel location = UiKit.flowRow();
        location.add(UiKit.caption("Location"));
        location.add(UiKit.caption("Latitude"));
        location.add(txtLat);
        location.add(UiKit.caption("Longitude"));
        location.add(txtLon);
        location.add(btnPick);
        location.add(locationNote);

        formError.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel filters = UiKit.flowRow();
        filters.add(UiKit.caption("Search"));
        filters.add(txtSearch);
        filters.add(UiKit.caption("Agent"));
        filters.add(cmbAgent);
        filters.add(btnSearch);
        filters.add(btnClearFilters);
        filters.add(summary);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(8, 10, 8, 10)));
        card.add(form);
        card.add(location);
        card.add(actions);
        card.add(formError);
        card.add(filters);
        return card;
    }

    // =====================================================
    // LOADING
    // =====================================================

    /** Reloads the table and map with the current search text and agent filter. */
    public void loadClients() {
        load(false);
    }

    private void load(boolean withAgents) {
        final int mySeq = ++seq;   // an older, slower answer must not replace a newer one
        final String keyword = txtSearch.getText().trim();
        final int agentIdx = cmbAgent.getSelectedIndex();
        final String agentUser = agentIdx >= 2 && agentIdx - 2 < agentUsernames.size()
                ? agentUsernames.get(agentIdx - 2) : null;
        final boolean fetchAgents = withAgents || agentUsernames.isEmpty();
        btnSearch.setEnabled(false);
        state.showLoading("Loading customers...");
        tableCards.show(tableArea, "state");
        new SwingWorker<Data, Void>() {
            @Override
            protected Data doInBackground() throws Exception {
                AuthService.requireAdmin();
                return fetch(keyword, agentIdx, agentUser, fetchAgents);
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                btnSearch.setEnabled(true);
                try {
                    show(get(), fetchAgents);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    shown = new ArrayList<>();
                    map.setMarkers(new ArrayList<>());
                    map.setMessage("Customers could not be loaded");
                    summary.setText(" ");
                    state.showError("Could not load customers",
                            c.getMessage() == null ? c.toString() : c.getMessage(), () -> load(false));
                    tableCards.show(tableArea, "state");
                }
            }
        }.execute();
    }

    private static Data fetch(String keyword, int agentIdx, String agentUser, boolean withAgents) throws SQLException {
        Data data = new Data();
        try (Connection conn = DatabaseConnection.getConnection()) {
            if (withAgents) {
                try (PreparedStatement pst = conn.prepareStatement(
                        "SELECT u.username, u.name FROM users u LEFT JOIN roles r ON r.role_id = u.role_id "
                                + "WHERE r.role_name IN ('Agent', 'Rider') "
                                + "OR EXISTS (SELECT 1 FROM agents a WHERE a.username = u.username) ORDER BY u.name");
                     ResultSet rs = pst.executeQuery()) {
                    while (rs.next()) {
                        data.agentUsernames.add(rs.getString(1));
                        data.agentNames.add(rs.getString(2));
                    }
                }
            }
            // GROUP BY + HAVING so a customer with several agents stays on one row
            StringBuilder sql = new StringBuilder(
                    "SELECT c.client_id, c.client_name, c.address, c.contact_num, "
                            + "COALESCE(STRING_AGG(u.name, ', ' ORDER BY u.name), 'Unassigned') AS agents, "
                            + "COUNT(a.agent_id) AS agent_count, MIN(u.username) AS first_agent, "
                            + "COALESCE(STRING_AGG(u.username, ',' ORDER BY u.username), '') AS agent_users, "
                            + "c.latitude, c.longitude "
                            + "FROM clients c LEFT JOIN agents a ON a.client_id = c.client_id "
                            + "LEFT JOIN users u ON u.username = a.username GROUP BY c.client_id "
                            + "HAVING (c.client_id::text ILIKE ? OR c.client_name ILIKE ? OR c.address ILIKE ? "
                            + "OR c.contact_num ILIKE ? OR COALESCE(STRING_AGG(u.name, ', '), '') ILIKE ?) ");
            if (agentIdx == 1) sql.append("AND COUNT(a.agent_id) = 0 ");
            else if (agentUser != null) sql.append("AND BOOL_OR(a.username = ?) ");
            sql.append("ORDER BY c.client_id");
            try (PreparedStatement pst = conn.prepareStatement(sql.toString())) {
                String like = "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                for (int i = 1; i <= 5; i++) pst.setString(i, like);
                if (agentIdx != 1 && agentUser != null) pst.setString(6, agentUser);
                try (ResultSet rs = pst.executeQuery()) {
                    while (rs.next()) {
                        Row r = new Row();
                        r.id = rs.getInt("client_id");
                        r.name = nz(rs.getString("client_name"));
                        r.address = nz(rs.getString("address"));
                        r.contact = nz(rs.getString("contact_num"));
                        r.agents = rs.getString("agents");
                        r.agentCount = rs.getInt("agent_count");
                        r.firstAgent = rs.getString("first_agent");
                        r.agentCsv = rs.getString("agent_users");
                        BigDecimal la = rs.getBigDecimal("latitude"), lo = rs.getBigDecimal("longitude");
                        r.lat = la == null ? null : la.doubleValue();
                        r.lon = lo == null ? null : lo.doubleValue();
                        data.rows.add(r);
                    }
                }
            }
        }
        return data;
    }

    private void show(Data data, boolean agentsIncluded) {
        if (agentsIncluded) {
            String keep = (String) cmbAgent.getSelectedItem();
            adjusting = true;
            cmbAgent.removeAllItems();
            cmbAgent.addItem("All agents");
            cmbAgent.addItem("Unassigned");
            agentUsernames.clear();
            for (int i = 0; i < data.agentUsernames.size(); i++) {
                agentUsernames.add(data.agentUsernames.get(i));
                cmbAgent.addItem(data.agentNames.get(i));
            }
            cmbAgent.setSelectedItem(keep);
            if (cmbAgent.getSelectedIndex() < 0) cmbAgent.setSelectedIndex(0);
            adjusting = false;
        }
        shown = data.rows;
        selectedClientId = null;
        savedLat = null;
        savedLon = null;

        adjusting = true;
        model.setRowCount(0);
        for (Row r : shown) {
            model.addRow(new Object[]{r.id, r.name, r.address, r.contact, r.agents,
                    r.lat == null ? "" : r.lat, r.lon == null ? "" : r.lon});
        }
        table.clearSelection();
        adjusting = false;

        updateMap();
        if (shown.isEmpty()) {
            state.showEmpty("No customers match", "Change the search or agent filter, or add a customer above.");
            tableCards.show(tableArea, "state");
        } else {
            tableCards.show(tableArea, "table");
        }
    }

    private void updateMap() {
        List<TileMapView.Marker> markers = new ArrayList<>();
        Set<Integer> usedColours = new LinkedHashSet<>();
        boolean multi = false, none = false;
        int noLocation = 0;
        for (Row r : shown) {
            if (!r.hasLocation()) {
                noLocation++;
                continue;
            }
            Color colour;
            TileMapView.Shape shape = TileMapView.Shape.SOLID;
            String agentText;
            if (r.agentCount == 0) {
                colour = Theme.MUTED;
                shape = TileMapView.Shape.HOLLOW;
                agentText = "Unassigned";
                none = true;
            } else if (r.agentCount > 1) {
                colour = MULTI;
                agentText = r.agents;
                multi = true;
            } else {
                int idx = agentUsernames.indexOf(r.firstAgent);
                int ci = (idx < 0 ? 0 : idx) % PALETTE.length;
                colour = PALETTE[ci];
                usedColours.add(idx < 0 ? 0 : idx);
                agentText = r.agents;
            }
            String tip = "<html><b>" + TileMapView.esc(r.name) + "</b><br>" + TileMapView.esc(r.address)
                    + "<br>Agent: " + TileMapView.esc(agentText) + "</html>";
            markers.add(new TileMapView.Marker(String.valueOf(r.id), r.name, tip, r.lat, r.lon, colour, shape));
        }
        baseMarkers = markers;
        applyMarkers();

        List<TileMapView.LegendItem> legend = new ArrayList<>();
        legend.add(TileMapView.LegendItem.heading("Assigned agent"));
        for (int idx : usedColours) {
            legend.add(TileMapView.LegendItem.item(idx < agentUsernames.size() ? agentLabel(idx) : "Agent",
                    PALETTE[idx % PALETTE.length], TileMapView.Shape.SOLID));
        }
        if (multi) legend.add(TileMapView.LegendItem.item("More than one agent", MULTI, TileMapView.Shape.SOLID));
        if (none) legend.add(TileMapView.LegendItem.item("Unassigned", Theme.MUTED, TileMapView.Shape.HOLLOW));
        map.setLegend(legend);

        map.setSelected(null);
        map.setMessage(markers.isEmpty()
                ? (shown.isEmpty() ? "No customers to show" : "None of these customers has a saved location") : null);
        if (!markers.isEmpty()) map.fitAll();
        summary.setText(shown.size() + " customer" + (shown.size() == 1 ? "" : "s") + ", " + markers.size()
                + " on the map" + (noLocation > 0 ? " (" + noLocation + " without a saved location)" : ""));
    }

    // ---- location pin (the new position the admin is about to save) ----

    private static String format6(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** The coordinates typed or picked, null if both are blank, or throws if only one or either is invalid. */
    private double[] enteredCoords() {
        String a = txtLat.getText().trim(), b = txtLon.getText().trim();
        if (a.isEmpty() && b.isEmpty()) return null;
        if (a.isEmpty() || b.isEmpty()) throw new IllegalArgumentException("Enter both latitude and longitude, or leave both blank.");
        try {
            double lat = Double.parseDouble(a), lon = Double.parseDouble(b);
            if (!(lat >= -90 && lat <= 90) || !(lon >= -180 && lon <= 180)) {
                throw new IllegalArgumentException("Latitude must be -90 to 90 and longitude -180 to 180.");
            }
            return new double[]{lat, lon};
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Latitude and longitude must be numbers such as 14.8280 and 121.0560.");
        }
    }

    /** True when the entered position is new: the customer has none saved, or it differs from the saved one. */
    private boolean coordsChanged(double[] c) {
        if (c == null) return false;
        if (selectedClientId == null || savedLat == null || savedLon == null) return true;
        return Math.abs(c[0] - savedLat) > 1e-6 || Math.abs(c[1] - savedLon) > 1e-6;
    }

    private void coordsEdited() {
        applyMarkers();
        try {
            double[] c = enteredCoords();
            locationNote.setText(c == null
                    ? (savedLat == null ? "No location saved. Leave blank to save none."
                    : "Blank keeps the saved location.")
                    : coordsChanged(c) ? "New location will be saved with Add or Update (red pin)."
                    : "Same as the saved location.");
            locationNote.setForeground(Theme.MUTED);
        } catch (IllegalArgumentException ex) {
            locationNote.setText(ex.getMessage());
            locationNote.setForeground(Theme.DANGER);
        }
    }

    /** Draws the customer markers plus a red pin when there is a new, valid position. */
    private void applyMarkers() {
        List<TileMapView.Marker> all = new ArrayList<>(baseMarkers);
        try {
            double[] c = enteredCoords();
            if (coordsChanged(c)) {
                all.add(new TileMapView.Marker("__pin__", "New location",
                        "<html><b>New location</b><br>Not saved yet</html>", c[0], c[1],
                        Theme.DANGER, TileMapView.Shape.SOLID));
            }
        } catch (IllegalArgumentException ignored) {
            // an incomplete or invalid entry just shows no pin; the note under the form explains why
        }
        map.setMarkers(all);
    }

    private void setPicking(boolean on) {
        picking = on;
        map.setPickMode(on);
        map.setBanner(on ? "Click the map to place the customer's location" : null, false);
        btnPick.setText(on ? "Cancel picking" : "Pick on map");
    }

    private String agentLabel(int idx) {
        int i = idx + 2;   // combo items start with "All agents", "Unassigned"
        return i < cmbAgent.getItemCount() ? cmbAgent.getItemAt(i) : "Agent";
    }

    // =====================================================
    // SELECTION
    // =====================================================

    private void onRowSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= shown.size()) return;
        Row r = shown.get(row);
        selectedClientId = r.id;
        txtName.setText(r.name);
        txtAddress.setText(r.address);
        txtContact.setText(r.contact);
        formError.setText(" ");
        setPicking(false);
        savedLat = r.hasLocation() ? r.lat : null;
        savedLon = r.hasLocation() ? r.lon : null;
        txtLat.setText(savedLat == null ? "" : format6(savedLat));
        txtLon.setText(savedLon == null ? "" : format6(savedLon));
        coordsEdited();
        map.setSelected(String.valueOf(r.id));
        if (r.hasLocation()) map.focusMarker(String.valueOf(r.id));
    }

    private void selectClientById(String id) {
        for (int i = 0; i < shown.size(); i++) {
            if (String.valueOf(shown.get(i).id).equals(id)) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
    }

    private void error(String message) {
        formError.setForeground(Theme.DANGER);
        formError.setText(message);
    }

    private void clearForm() {
        txtName.setText("");
        txtAddress.setText("");
        txtContact.setText("");
        formError.setText(" ");
        selectedClientId = null;
        savedLat = null;
        savedLon = null;
        txtLat.setText("");
        txtLon.setText("");
        setPicking(false);
        adjusting = true;
        table.clearSelection();
        adjusting = false;
        map.setSelected(null);
        coordsEdited();
    }

    // =====================================================
    // ADD / UPDATE / DELETE
    // =====================================================

    private interface Write {
        /** Runs the change on the given connection; returns the text for the success message. */
        String run(Connection conn) throws Exception;
    }

    private String validateForm() {
        String name = txtName.getText().trim(), address = txtAddress.getText().trim(),
                contact = txtContact.getText().trim();
        if (name.isEmpty()) return "Customer name is required.";
        if (name.length() > 100) return "Customer name can be at most 100 characters.";
        if (address.length() > 150) return "Address can be at most 150 characters.";
        if (!PHONE.matcher(contact).matches()) {
            return "Contact number must be 11 digits and start with 09 (for example, 09123456789).";
        }
        try {
            enteredCoords();
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
        return null;
    }

    private void addCustomer() {
        if (selectedClientId != null) {
            // The most common slip: a row is selected and Add is clicked instead of Update.
            error("Customer #" + selectedClientId + " is selected. Click Update to change it, "
                    + "or Clear first to add a new customer.");
            return;
        }
        String bad = validateForm();
        if (bad != null) {
            error(bad);
            return;
        }
        final String name = txtName.getText().trim(), address = emptyToNull(txtAddress.getText()),
                contact = emptyToNull(txtContact.getText());
        final double[] pos = enteredCoords();
        write("add the customer", conn -> {
            String dup = findDuplicate(conn, name, address, contact, null);
            if (dup != null) throw new IllegalStateException(dup + " Nothing was added.");
            conn.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement pst = conn.prepareStatement(
                        "INSERT INTO clients (client_name, address, contact_num, latitude, longitude) "
                                + "VALUES (?, ?, ?, ?, ?) RETURNING client_id")) {
                    pst.setString(1, name);
                    pst.setString(2, address);
                    pst.setString(3, contact);
                    bindCoords(pst, 4, pos);
                    try (ResultSet rs = pst.executeQuery()) {
                        rs.next();
                        id = rs.getInt(1);
                    }
                }
                AuditService.record(conn, "customer.add", "customer", String.valueOf(id), "success",
                        pos == null ? "created" : "created, location set");
                conn.commit();
                return "Customer #" + id + " added" + (pos == null ? " without a location" : " with a location")
                        + ". It has no agent yet; use Assign agent to give it one.";
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        });
    }

    private void updateCustomer() {
        if (selectedClientId == null) {
            error("Click a customer in the table first.");
            return;
        }
        String bad = validateForm();
        if (bad != null) {
            error(bad);
            return;
        }
        final int id = selectedClientId;
        final String name = txtName.getText().trim(), address = emptyToNull(txtAddress.getText()),
                contact = emptyToNull(txtContact.getText());
        final double[] entered = enteredCoords();
        final double[] pos = coordsChanged(entered) ? entered : null;   // null = leave the saved location alone
        Row current = null;
        for (Row r : shown) if (r.id == id) current = r;
        if (current != null && pos == null && current.name.equals(name)
                && current.address.equals(address == null ? "" : address)
                && current.contact.equals(contact == null ? "" : contact)) {
            error("Nothing was changed, so there is nothing to update.");
            return;
        }
        write("update the customer", conn -> {
            String dup = findDuplicate(conn, name, address, contact, id);
            if (dup != null) throw new IllegalStateException(dup + " Nothing was changed.");
            try (PreparedStatement pst = conn.prepareStatement(
                    "UPDATE clients SET client_name = ?, address = ?, contact_num = ?, "
                            + "latitude = COALESCE(?, latitude), longitude = COALESCE(?, longitude) "
                            + "WHERE client_id = ?")) {
                pst.setString(1, name);
                pst.setString(2, address);
                pst.setString(3, contact);
                bindCoords(pst, 4, pos);
                pst.setInt(6, id);
                if (pst.executeUpdate() != 1) throw new IllegalStateException("That customer no longer exists.");
            }
            AuditService.record(conn, "customer.update", "customer", String.valueOf(id), "success",
                    pos == null ? "details changed" : "details changed, location set");
            return "Customer #" + id + " updated.";
        });
    }

    private void deleteCustomer() {
        if (selectedClientId == null) {
            error("Click a customer in the table first.");
            return;
        }
        final int id = selectedClientId;
        if (JOptionPane.showConfirmDialog(this,
                "Delete customer #" + id + " (" + txtName.getText().trim() + ")?\n"
                        + "Their agent assignments will be removed too. This cannot be undone.",
                "Confirm delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        write("delete the customer", conn -> {
            try (PreparedStatement pst = conn.prepareStatement("DELETE FROM clients WHERE client_id = ?")) {
                pst.setInt(1, id);
                if (pst.executeUpdate() != 1) throw new IllegalStateException("That customer no longer exists.");
            } catch (SQLException e) {
                if ("23503".equals(e.getSQLState())) {   // foreign key violation
                    throw new IllegalStateException("This customer has bookings, so it can't be deleted.");
                }
                throw e;
            }
            AuditService.record(conn, "customer.delete", "customer", String.valueOf(id), "success", "deleted");
            return "Customer #" + id + " deleted.";
        });
    }

    private void write(String what, Write w) {
        if (busy) return;
        busy = true;
        setButtons(false);
        formError.setForeground(Theme.MUTED);
        formError.setText("Working...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                AuthService.requireAdmin();
                try (Connection conn = DatabaseConnection.getConnection()) {
                    return w.run(conn);
                }
            }

            @Override
            protected void done() {
                busy = false;
                setButtons(true);
                try {
                    String ok = get();
                    clearForm();
                    formError.setForeground(Theme.OK);
                    formError.setText(ok);
                    txtName.requestFocusInWindow();   // ready for the next customer
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    formError.setForeground(Theme.DANGER);
                    formError.setText("Could not " + what + ": " + (c.getMessage() == null ? c.toString() : c.getMessage()));
                }
                load(false);   // always show what the database says now
            }
        }.execute();
    }

    /**
     * Looks for another customer with the same contact number (digits only), or the same name and address
     * (ignoring case and extra spaces). Returns a sentence naming it, or null if there is none. This guards
     * against slips and repeat clicks; two admins saving at the same instant would need a unique index
     * in the database (see sql/005_unique_customers.sql).
     */
    private static String findDuplicate(Connection conn, String name, String address, String contact, Integer excludeId)
            throws SQLException {
        String normName = name.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        String normAddr = address == null ? "" : address.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        String digits = contact == null ? "" : contact.replaceAll("\\D", "");
        String sql = "SELECT client_id, client_name, "
                + "(lower(regexp_replace(btrim(client_name), '\\s+', ' ', 'g')) = ? "
                + " AND lower(regexp_replace(btrim(COALESCE(address, '')), '\\s+', ' ', 'g')) = ?) AS same_person "
                + "FROM clients WHERE client_id <> ? AND ("
                + "(lower(regexp_replace(btrim(client_name), '\\s+', ' ', 'g')) = ? "
                + " AND lower(regexp_replace(btrim(COALESCE(address, '')), '\\s+', ' ', 'g')) = ?) "
                + "OR (? <> '' AND regexp_replace(COALESCE(contact_num, ''), '\\D', '', 'g') = ?)) "
                + "ORDER BY client_id LIMIT 1";
        try (PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, normName);
            pst.setString(2, normAddr);
            pst.setInt(3, excludeId == null ? -1 : excludeId);
            pst.setString(4, normName);
            pst.setString(5, normAddr);
            pst.setString(6, digits);
            pst.setString(7, digits);
            try (ResultSet rs = pst.executeQuery()) {
                if (!rs.next()) return null;
                return "Customer #" + rs.getInt(1) + " (" + rs.getString(2) + ") already has "
                        + (rs.getBoolean(3) ? "this name and address." : "this contact number.");
            }
        }
    }

    // ---- assigning an agent ----

    private static final class AgentChoice {
        final String username, label;

        AgentChoice(String username, String label) {
            this.username = username;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void chooseAndAssignAgent() {
        if (selectedClientId == null) {
            error("Click a customer in the table first.");
            return;
        }
        Row row = null;
        for (Row r : shown) if (r.id == selectedClientId) row = r;
        if (row == null || busy) return;
        final Row target = row;
        final AuthService.Session me = AuthService.current();
        busy = true;
        setButtons(false);
        new SwingWorker<List<AgentChoice>, Void>() {
            @Override
            protected List<AgentChoice> doInBackground() throws Exception {
                AuthService.requireAdmin();
                List<AgentChoice> list = new ArrayList<>();
                list.add(new AgentChoice(me.username, "Me (" + me.fullName + ")"));
                try (Connection conn = DatabaseConnection.getConnection();
                     PreparedStatement pst = conn.prepareStatement(
                             "SELECT u.username, u.name FROM users u JOIN roles r ON r.role_id = u.role_id "
                                     + "WHERE r.role_name IN ('Agent', 'Rider') AND u.account_status = 'Active' "
                                     + "AND u.username <> ? ORDER BY u.name")) {
                    pst.setString(1, me.username);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) list.add(new AgentChoice(rs.getString(1), rs.getString(2)));
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                busy = false;
                setButtons(true);
                List<AgentChoice> choices;
                try {
                    choices = get();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    error("Could not load agents: " + (c.getMessage() == null ? c.toString() : c.getMessage()));
                    return;
                }
                JComboBox<AgentChoice> combo = new JComboBox<>(choices.toArray(new AgentChoice[0]));
                String now = target.agentCount == 0 ? "currently unassigned" : "currently " + target.agents;
                String warn = target.agentCount > 1
                        ? "\nThis customer has " + target.agentCount + " agents. All of them will be replaced." : "";
                int ok = JOptionPane.showConfirmDialog(ClientsPanel.this,
                        new Object[]{"Agent for customer #" + target.id + " (" + target.name + "), " + now + ":"
                                + warn, combo},
                        "Assign agent", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (ok != JOptionPane.OK_OPTION) return;
                AgentChoice pick = (AgentChoice) combo.getSelectedItem();
                if (pick == null) return;
                if (target.agentCount == 1 && pick.username.equals(target.agentCsv)) {
                    error("That agent is already assigned to this customer.");
                    return;
                }
                assignAgent(target, pick);
            }
        }.execute();
    }

    private void assignAgent(Row target, AgentChoice pick) {
        final int id = target.id;
        final String expected = target.agentCsv == null ? "" : target.agentCsv;
        write("assign the agent", conn -> {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement pst = conn.prepareStatement(
                        "SELECT 1 FROM clients WHERE client_id = ? FOR UPDATE")) {   // serialises two admins
                    pst.setInt(1, id);
                    try (ResultSet rs = pst.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("That customer no longer exists.");
                    }
                }
                StringBuilder now = new StringBuilder();
                try (PreparedStatement pst = conn.prepareStatement(
                        "SELECT username FROM agents WHERE client_id = ? ORDER BY username")) {
                    pst.setInt(1, id);
                    try (ResultSet rs = pst.executeQuery()) {
                        while (rs.next()) now.append(now.length() > 0 ? "," : "").append(rs.getString(1));
                    }
                }
                if (!now.toString().equals(expected)) {
                    throw new IllegalStateException(
                            "This customer's agent was changed by someone else. The list has been reloaded.");
                }
                if (!pick.username.equals(AuthService.current().username)) {
                    try (PreparedStatement pst = conn.prepareStatement(
                            "SELECT 1 FROM users u JOIN roles r ON r.role_id = u.role_id WHERE u.username = ? "
                                    + "AND r.role_name IN ('Agent', 'Rider') AND u.account_status = 'Active'")) {
                        pst.setString(1, pick.username);
                        try (ResultSet rs = pst.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException(pick.label + " is no longer an active agent.");
                        }
                    }
                }
                try (PreparedStatement pst = conn.prepareStatement("DELETE FROM agents WHERE client_id = ?")) {
                    pst.setInt(1, id);
                    pst.executeUpdate();
                }
                try (PreparedStatement pst = conn.prepareStatement(
                        "INSERT INTO agents (username, client_id) VALUES (?, ?)")) {
                    pst.setString(1, pick.username);
                    pst.setInt(2, id);
                    pst.executeUpdate();
                }
                AuditService.record(conn, "customer.assign_agent", "customer", String.valueOf(id), "success",
                        (expected.isEmpty() ? "unassigned" : expected) + " -> " + pick.username);
                conn.commit();
                return "Customer #" + id + " is now assigned to " + pick.label + ".";
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        });
    }

    private static void bindCoords(PreparedStatement pst, int index, double[] pos) throws SQLException {
        if (pos == null) {
            pst.setNull(index, java.sql.Types.NUMERIC);
            pst.setNull(index + 1, java.sql.Types.NUMERIC);
        } else {
            pst.setBigDecimal(index, new BigDecimal(format6(pos[0])));
            pst.setBigDecimal(index + 1, new BigDecimal(format6(pos[1])));
        }
    }

    private void setButtons(boolean on) {
        btnAdd.setEnabled(on);
        btnUpdate.setEnabled(on);
        btnDelete.setEnabled(on);
        btnAssign.setEnabled(on);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
