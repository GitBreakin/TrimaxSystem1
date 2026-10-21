package com.trimaxsystem;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** Vehicle administration screen for adding, updating, assigning and removing vehicles. */
public class ManageVehiclesPanel extends JPanel {

    private static final String[] STATUSES = {
            "Available", "On Delivery", "Unavailable"
    };
    private static final int DRIVER_ROLE_ID = 3;
    private static final Pattern PLATE = Pattern.compile("[A-Za-z0-9 \\-]{2,20}");
    private static final String[] COLUMNS = {
            "Vehicle ID", "Vehicle Type", "Driver Username", "Driver", "Status"
    };

    private final JTextField vehicleIdField = new JTextField(16);
    private final JTextField vehicleTypeField = new JTextField(16);
    private final JTextField driverField = new JTextField(16);
    private final JTextField searchField = new JTextField(24);
    private final JComboBox<String> statusFilter = new JComboBox<>(statusFilterOptions());
    private final JLabel countLabel = new JLabel(" ");
    private final DefaultTableModel vehicleModel = new DefaultTableModel(null, COLUMNS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(vehicleModel);
    private String selectedVehicleId;

    public ManageVehiclesPanel() {
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(14, 20, 16, 20));

        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(buildVehicleCard());
        controls.add(Box.createVerticalStrut(10));
        controls.add(buildSearchCard());
        add(controls, BorderLayout.NORTH);

        add(buildTableCard(), BorderLayout.CENTER);
        loadVehicles();
    }

    private JPanel buildVehicleCard() {
        styleField(vehicleIdField);
        styleField(vehicleTypeField);
        styleField(driverField);
        driverField.setToolTipText("Enter the driver's username or full name; leave blank to unassign.");

        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBackground(Theme.CARD);
        card.setBorder(cardBorder());

        JPanel heading = new JPanel(new BorderLayout(0, 3));
        heading.setOpaque(false);
        JLabel title = new JLabel("Vehicle details");
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(Theme.TEXT);
        JLabel hint = new JLabel("Select a vehicle below to edit its details or driver assignment.");
        hint.setFont(Theme.SUBTLE);
        hint.setForeground(Theme.MUTED);
        heading.add(title, BorderLayout.NORTH);
        heading.add(hint, BorderLayout.SOUTH);
        card.add(heading, BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridLayout(1, 3, 12, 0));
        fields.setOpaque(false);
        fields.add(fieldGroup("Vehicle ID / plate", vehicleIdField));
        fields.add(fieldGroup("Vehicle type", vehicleTypeField));
        fields.add(fieldGroup("Driver (name or username)", driverField));
        card.add(fields, BorderLayout.CENTER);

        JButton clearButton = UiKit.secondaryButton("Clear fields");
        JButton addButton = UiKit.primaryButton("Add vehicle");
        JButton updateButton = UiKit.secondaryButton("Update vehicle");
        JButton deleteButton = UiKit.dangerButton("Delete vehicle");
        clearButton.addActionListener(e -> clearFields());
        addButton.addActionListener(e -> {
            if (addVehicle()) clearFields();
        });
        updateButton.addActionListener(e -> {
            if (updateVehicle()) clearFields();
        });
        deleteButton.addActionListener(e -> {
            if (deleteVehicle()) clearFields();
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(clearButton);
        actions.add(addButton);
        actions.add(updateButton);
        actions.add(deleteButton);
        card.add(actions, BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildSearchCard() {
        styleField(searchField);
        searchField.setToolTipText("Search by vehicle ID, type, driver username, or driver name");
        searchField.addActionListener(e -> loadVehicles());

        statusFilter.setFont(Theme.SUBTLE);
        statusFilter.setBackground(Theme.CARD);
        statusFilter.setForeground(Theme.TEXT);
        statusFilter.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        statusFilter.addActionListener(e -> loadVehicles());

        JButton searchButton = UiKit.primaryButton("Search");
        searchButton.addActionListener(e -> loadVehicles());

        JPanel searchGroup = new JPanel(new BorderLayout(0, 5));
        searchGroup.setOpaque(false);
        searchGroup.add(UiKit.caption("Search vehicles"), BorderLayout.NORTH);
        searchGroup.add(searchField, BorderLayout.CENTER);

        JPanel filterGroup = new JPanel(new BorderLayout(0, 5));
        filterGroup.setOpaque(false);
        filterGroup.add(UiKit.caption("Vehicle status"), BorderLayout.NORTH);
        filterGroup.add(statusFilter, BorderLayout.CENTER);

        JPanel inputs = new JPanel(new GridBagLayout());
        inputs.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 12);
        c.weightx = 1;
        inputs.add(searchGroup, c);
        c.gridx = 1;
        c.weightx = 0;
        inputs.add(filterGroup, c);

        JPanel card = new JPanel(new BorderLayout(12, 0));
        card.setBackground(Theme.CARD);
        card.setBorder(cardBorder());
        card.add(inputs, BorderLayout.CENTER);
        card.add(searchButton, BorderLayout.EAST);
        return card;
    }

    private JPanel buildTableCard() {
        styleTable();
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scrollPane.getViewport().setBackground(Theme.CARD);

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JLabel title = new JLabel("Vehicles");
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(Theme.TEXT);
        countLabel.setFont(Theme.SUBTLE);
        countLabel.setForeground(Theme.MUTED);
        heading.add(title, BorderLayout.WEST);
        heading.add(countLabel, BorderLayout.EAST);

        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(Theme.CARD);
        card.setBorder(cardBorder());
        card.add(heading, BorderLayout.NORTH);
        card.add(scrollPane, BorderLayout.CENTER);
        return card;
    }

    private void styleTable() {
        table.setRowHeight(34);
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
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                           boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, false, row, column);
                setFont(Theme.SUBTLE);
                setForeground(Theme.TEXT);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                if (column == 4 && !selected) {
                    setForeground(statusColor(value == null ? "" : value.toString()));
                    setFont(Theme.NAV_ON);
                }
                return this;
            }
        });
        DefaultTableCellRenderer headerRenderer = new DefaultTableCellRenderer();
        headerRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        headerRenderer.setBackground(Color.decode("#F8FAFC"));
        headerRenderer.setForeground(Theme.MUTED);
        headerRenderer.setFont(new Font("SansSerif", Font.BOLD, 12));
        headerRenderer.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        table.getTableHeader().setDefaultRenderer(headerRenderer);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(100, 34));
        int[] widths = {120, 130, 140, 160, 115};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                selectedVehicleId = null;
                return;
            }
            int row = table.convertRowIndexToModel(viewRow);
            selectedVehicleId = String.valueOf(vehicleModel.getValueAt(row, 0));
            vehicleIdField.setText(selectedVehicleId);
            vehicleTypeField.setText(String.valueOf(vehicleModel.getValueAt(row, 1)));
            driverField.setText(String.valueOf(vehicleModel.getValueAt(row, 2)));
        });
    }

    private JPanel fieldGroup(String label, JTextField field) {
        JPanel group = new JPanel(new BorderLayout(0, 5));
        group.setOpaque(false);
        group.add(UiKit.caption(label), BorderLayout.NORTH);
        group.add(field, BorderLayout.CENTER);
        return group;
    }

    private void styleField(JTextField field) {
        field.setBackground(Theme.CARD);
        UiKit.styleField(field);
    }

    private javax.swing.border.Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(12, 14, 12, 14));
    }

    private static String[] statusFilterOptions() {
        String[] options = new String[STATUSES.length + 1];
        options[0] = "All statuses";
        System.arraycopy(STATUSES, 0, options, 1, STATUSES.length);
        return options;
    }

    private static Color statusColor(String status) {
        switch (status) {
            case "Available":
                return Theme.OK;
            case "On Delivery":
                return Theme.BLUE;
            case "Unavailable":
                return Theme.WARN;
            default:
                return Theme.MUTED;
        }
    }

    private void clearFields() {
        vehicleIdField.setText("");
        vehicleTypeField.setText("");
        driverField.setText("");
        selectedVehicleId = null;
        table.clearSelection();
    }

    private void warn(String message, String title) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.WARNING_MESSAGE);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String plate() {
        return vehicleIdField.getText().trim().toUpperCase(Locale.ROOT);
    }

    /** Reloads the table using the current search text and status filter. */
    public void loadVehicles() {
        String keyword = searchField.getText().trim();
        boolean filterStatus = statusFilter.getSelectedIndex() > 0;
        String sql = "SELECT v.vehicle_id, v.vehicle_type, v.username, u.name AS driver_name, v.status "
                + "FROM vehicles v LEFT JOIN users u ON u.username = v.username "
                + "WHERE (v.vehicle_id ILIKE ? OR COALESCE(v.vehicle_type, '') ILIKE ? "
                + "    OR COALESCE(v.username, '') ILIKE ? OR COALESCE(u.name, '') ILIKE ?) "
                + (filterStatus ? "AND v.status = ? " : "")
                + "ORDER BY v.vehicle_id";

        vehicleModel.setRowCount(0);
        selectedVehicleId = null;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement statement = conn.prepareStatement(sql)) {
            String like = "%" + keyword + "%";
            for (int i = 1; i <= 4; i++) statement.setString(i, like);
            if (filterStatus) statement.setString(5, (String) statusFilter.getSelectedItem());
            try (ResultSet rs = statement.executeQuery()) {
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
            countLabel.setText(vehicleModel.getRowCount() + " vehicle(s)");
        } catch (SQLException e) {
            countLabel.setText("Unable to load vehicles");
            JOptionPane.showMessageDialog(this, "Could not load vehicles: " + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private boolean validateInputs() {
        String id = vehicleIdField.getText().trim();
        if (id.isEmpty()) {
            warn("Vehicle ID / plate number is required.", "Missing Data");
            return false;
        }
        if (!PLATE.matcher(id).matches()) {
            warn("Vehicle ID / plate number must be 2 to 20 characters: letters, digits, spaces or dashes.",
                    "Invalid Input");
            return false;
        }
        if (vehicleTypeField.getText().trim().length() > 30) {
            warn("Vehicle type can be at most 30 characters.", "Invalid Input");
            return false;
        }
        return true;
    }

    /** Resolves a driver's username, accepting either a username or an unambiguous full name. */
    private boolean resolveDriver(String[] out) {
        out[0] = null;
        String text = driverField.getText().trim();
        if (text.isEmpty()) return true;
        String sql = "SELECT u.username FROM users u "
                + "WHERE u.role_id = ? "
                + "AND (LOWER(u.username) = LOWER(?) OR LOWER(u.name) = LOWER(?))";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setInt(1, DRIVER_ROLE_ID);
            statement.setString(2, text);
            statement.setString(3, text);
            List<String> matches = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) matches.add(rs.getString(1));
            }
            if (matches.isEmpty()) {
                warn("No Driver" + DRIVER_ROLE_ID
                        + ") was found with the username or name \"" + text + "\".", "Driver Not Found");
                return false;
            }
            if (matches.size() > 1) {
                for (String match : matches) {
                    if (match.equalsIgnoreCase(text)) {
                        out[0] = match;
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
        String sql = "INSERT INTO vehicles (vehicle_id, username, vehicle_type) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, plate());
            statement.setString(2, driver[0]);
            statement.setString(3, emptyToNull(vehicleTypeField.getText()));
            statement.executeUpdate();
            loadVehicles();
            return true;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
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
        String sql = "UPDATE vehicles SET vehicle_id = ?, username = ?, vehicle_type = ? WHERE vehicle_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, plate());
            statement.setString(2, driver[0]);
            statement.setString(3, emptyToNull(vehicleTypeField.getText()));
            statement.setString(4, selectedVehicleId);
            statement.executeUpdate();
            loadVehicles();
            return true;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                warn("A vehicle with the plate \"" + plate() + "\" already exists.", "Duplicate Vehicle");
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
             PreparedStatement statement = conn.prepareStatement("DELETE FROM vehicles WHERE vehicle_id = ?")) {
            statement.setString(1, selectedVehicleId);
            statement.executeUpdate();
            loadVehicles();
            return true;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not delete vehicle: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }
}
