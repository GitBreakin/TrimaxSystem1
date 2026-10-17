package com.trimaxsystem;

import java.awt.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

public class ClientsPanel extends JPanel {

    // Reference width of the design (taken from the screenshot)
    private static final double DESIGN_WIDTH = 1085.0;

    private static final Color FIELD_BG   = Color.decode("#F4F6F8");
    private static final Color DARK       = Color.decode("#45494D");
    private static final Color BLUE       = Color.decode("#2563EB");
    private static final Color HEADING    = Color.decode("#1E293B");
    private static final Color BORDER_CLR = Color.decode("#555555");

    private static final Pattern PHONE = Pattern.compile("[0-9+\\-() ]{7,20}");

    private DefaultTableModel clientModel;
    private JTable table;

    private JTextField txtName;
    private JTextField txtAddress;
    private JTextField txtContact;
    private JTextField txtSearch;
    private JComboBox<String> cmbAgent;

    // Parallel to the combo box items: index 0 = "Agents Assigned" (all), 1 = "Unassigned",
    // 2+ = real agents (their usernames)
    private final List<String> agentUsernames = new ArrayList<>();

    // client_id of the currently selected row (null = nothing selected)
    private Integer selectedClientId = null;

    public ClientsPanel() {
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
        top.setPreferredSize(new Dimension(1085, 180));

        // Heading
        place(top, makeHeading("Clients"), 7, 8, 300, 40);

        // Labels
        place(top, makeLabel("Client Name:"),    7,   62, 200, 20);
        place(top, makeLabel("Address:"),        229, 62, 200, 20);
        place(top, makeLabel("Contact Number:"), 452, 62, 200, 20);
        place(top, makeLabel("Search Client:"),  492, 150, 105, 20);

        // Input fields
        txtName    = makeField();
        txtAddress = makeField();
        txtContact = makeField();
        txtSearch  = makeField();

        place(top, txtName,    7,   90,  200, 40);
        place(top, txtAddress, 229, 91,  200, 40);
        place(top, txtContact, 452, 91,  200, 40);
        place(top, txtSearch,  603, 146, 187, 28);

        // Agent filter (loaded from the database)
        loadAgentOptions();
        List<String> comboItems = new ArrayList<>();
        comboItems.add("Agents Assigned");
        comboItems.add("Unassigned");
        comboItems.addAll(agentNamesForCombo);
        cmbAgent = makeCombo(comboItems.toArray(new String[0]));
        cmbAgent.addActionListener(e -> loadClients());
        place(top, cmbAgent, 798, 146, 162, 28);

        // Buttons
        JButton btnAdd    = makeButton("Add");
        JButton btnUpdate = makeButton("Update");
        JButton btnDelete = makeButton("Delete");
        JButton btnClear  = makeButton("Clear");
        JButton btnSearch = makeButton("Search");

        place(top, btnAdd,    697, 90,  77,  41);
        place(top, btnUpdate, 797, 90,  77,  41);
        place(top, btnDelete, 897, 90,  77,  41);
        place(top, btnClear,  997, 90,  77,  41);
        place(top, btnSearch, 968, 146, 106, 28);

        add(top, BorderLayout.NORTH);

        // ---------- TABLE ----------
        String[] columnNames = {"Client ID", "Client Name", "Address", "Contact Number",
                "Assigned Agent", "Latitude", "Longitude"};

        clientModel = new DefaultTableModel(null, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(clientModel);
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

        // Clicking a row fills the text fields above
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row == -1) return;

            selectedClientId = Integer.valueOf(String.valueOf(clientModel.getValueAt(row, 0)));
            txtName.setText(String.valueOf(clientModel.getValueAt(row, 1)));
            txtAddress.setText(String.valueOf(clientModel.getValueAt(row, 2)));
            txtContact.setText(String.valueOf(clientModel.getValueAt(row, 3)));
        });

        // Left-aligned cells with padding
        DefaultTableCellRenderer cellRenderer = new DefaultTableCellRenderer();
        cellRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        cellRenderer.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        table.setDefaultRenderer(Object.class, cellRenderer);

        // Dark header
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
        center.setBorder(BorderFactory.createEmptyBorder(0, 7, 20, 11));
        center.add(scrollPane, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        // ---------- LISTENERS ----------
        btnSearch.addActionListener(e -> loadClients());
        txtSearch.addActionListener(e -> loadClients());   // Enter key also searches

        btnClear.addActionListener(e -> clearFields());

        btnAdd.addActionListener(e -> {
            if (addClient()) clearFields();
        });
        btnUpdate.addActionListener(e -> {
            if (updateClient()) clearFields();
        });
        btnDelete.addActionListener(e -> {
            if (deleteClient()) clearFields();
        });

        loadClients();
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
        txtName.setText("");
        txtAddress.setText("");
        txtContact.setText("");
        selectedClientId = null;
        table.clearSelection();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String coord(BigDecimal d) {
        return d == null ? "" : d.stripTrailingZeros().toPlainString();
    }

    // ---------- DATABASE: LOAD ----------

    // Agent display names, aligned with agentUsernames from index 2 onward
    private final List<String> agentNamesForCombo = new ArrayList<>();

    /** Fills the agent filter list with every user whose role is "Agent". */
    private void loadAgentOptions() {
        agentUsernames.clear();
        agentNamesForCombo.clear();
        agentUsernames.add(null);   // index 0: "Agents Assigned" (no filter)
        agentUsernames.add(null);   // index 1: "Unassigned"

        String query = "SELECT u.username, u.name FROM users u "
                + "JOIN roles r ON r.role_id = u.role_id "
                + "WHERE r.role_name = 'Agent' ORDER BY u.name";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(query);
             ResultSet rs = pst.executeQuery()) {
            while (rs.next()) {
                agentUsernames.add(rs.getString("username"));
                agentNamesForCombo.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            e.printStackTrace();    // the client load below will show the error dialog
        }
    }

    /** Reloads the table using the current search text and agent filter. */
    public void loadClients() {
        String keyword = txtSearch.getText().trim();
        int agentIdx = cmbAgent.getSelectedIndex();

        // GROUP BY + HAVING so a client with several agents stays on one row
        StringBuilder sql = new StringBuilder(
                "SELECT c.client_id, c.client_name, c.address, c.contact_num, "
                        + "       COALESCE(STRING_AGG(u.name, ', ' ORDER BY u.name), 'Unassigned') AS agents, "
                        + "       c.latitude, c.longitude "
                        + "FROM clients c "
                        + "LEFT JOIN agents a ON a.client_id = c.client_id "
                        + "LEFT JOIN users u  ON u.username = a.username "
                        + "GROUP BY c.client_id "
                        + "HAVING (c.client_id::text ILIKE ? OR c.client_name ILIKE ? OR c.address ILIKE ? "
                        + "        OR c.contact_num ILIKE ? OR COALESCE(STRING_AGG(u.name, ', '), '') ILIKE ?) ");

        if (agentIdx == 1) {
            sql.append("AND COUNT(a.agent_id) = 0 ");            // Unassigned
        } else if (agentIdx >= 2 && agentIdx < agentUsernames.size()) {
            sql.append("AND BOOL_OR(a.username = ?) ");          // a specific agent
        }
        sql.append("ORDER BY c.client_id");

        clientModel.setRowCount(0);
        selectedClientId = null;   // the old selection no longer exists after a reload

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql.toString())) {

            String like = "%" + keyword + "%";
            for (int i = 1; i <= 5; i++) {
                pst.setString(i, like);
            }
            if (agentIdx >= 2 && agentIdx < agentUsernames.size()) {
                pst.setString(6, agentUsernames.get(agentIdx));
            }

            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) {
                    clientModel.addRow(new Object[]{
                            rs.getInt("client_id"),
                            nz(rs.getString("client_name")),
                            nz(rs.getString("address")),
                            nz(rs.getString("contact_num")),
                            rs.getString("agents"),
                            coord(rs.getBigDecimal("latitude")),
                            coord(rs.getBigDecimal("longitude"))
                    });
                }
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not load clients: " + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    // ---------- DATABASE: ADD / UPDATE / DELETE ----------
    // Each returns true on success so the button only clears the fields when it worked.
    // Latitude and longitude are intentionally never touched here.

    private boolean validateInputs() {
        String name = txtName.getText().trim();
        String address = txtAddress.getText().trim();
        String contact = txtContact.getText().trim();

        if (name.isEmpty()) {
            warn("Client name is required.", "Missing Data");
            return false;
        }
        if (name.length() > 100) {
            warn("Client name can be at most 100 characters.", "Invalid Input");
            return false;
        }
        if (address.length() > 150) {
            warn("Address can be at most 150 characters.", "Invalid Input");
            return false;
        }
        if (!contact.isEmpty() && !PHONE.matcher(contact).matches()) {
            warn("Contact number should be 7 to 20 characters long and contain only digits, "
                    + "spaces and + - ( ).", "Invalid Input");
            return false;
        }
        return true;
    }

    private void warn(String message, String title) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.WARNING_MESSAGE);
    }

    private boolean addClient() {
        if (!validateInputs()) return false;

        String sql = "INSERT INTO clients (client_name, address, contact_num) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, txtName.getText().trim());
            pst.setString(2, emptyToNull(txtAddress.getText()));
            pst.setString(3, emptyToNull(txtContact.getText()));
            pst.executeUpdate();
            loadClients();
            return true;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not add client: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private boolean updateClient() {
        if (selectedClientId == null) {
            warn("Click a client in the table first.", "No Selection");
            return false;
        }
        if (!validateInputs()) return false;

        String sql = "UPDATE clients SET client_name = ?, address = ?, contact_num = ? "
                + "WHERE client_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, txtName.getText().trim());
            pst.setString(2, emptyToNull(txtAddress.getText()));
            pst.setString(3, emptyToNull(txtContact.getText()));
            pst.setInt(4, selectedClientId);
            pst.executeUpdate();
            loadClients();
            return true;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not update client: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private boolean deleteClient() {
        if (selectedClientId == null) {
            warn("Click a client in the table first.", "No Selection");
            return false;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete this client? Their agent assignments will be removed too.",
                "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return false;

        String sql = "DELETE FROM clients WHERE client_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setInt(1, selectedClientId);
            pst.executeUpdate();
            loadClients();
            return true;
        } catch (SQLException e) {
            if ("23503".equals(e.getSQLState())) {   // foreign key violation
                warn("This client has bookings, so it can't be deleted.", "Cannot Delete");
            } else {
                JOptionPane.showMessageDialog(this, "Could not delete client: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
            return false;
        }
    }
}