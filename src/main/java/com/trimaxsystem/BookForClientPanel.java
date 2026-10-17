package com.trimaxsystem;

import java.awt.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

public class BookForClientPanel extends JPanel {

    private static final Color DARK       = Color.decode("#45494D");
    private static final Color DARK_LINE  = Color.decode("#5E6368");
    private static final Color TEXT_LIGHT = Color.decode("#D1D5DB");
    private static final Color BLUE       = Color.decode("#2563EB");
    private static final Color HEADING    = Color.decode("#1E293B");
    private static final Color BORDER_CLR = Color.decode("#555555");
    private static final Color FIELD_BG   = Color.decode("#F4F6F8");

    private static final int AMOUNT_COL = 5;

    private final JTable clientTable;
    private final JTable itemsTable;
    private final DefaultTableModel clientModel;
    private final DefaultTableModel itemsModel;
    private final JTextField txtSearchClient;
    private final JButton btnProceed;

    /** Simple holder for an ordered line item. */
    public static class OrderItem {
        public final String productId;
        public final String productName;
        public final double price;
        public final int amount;
        public final double subtotal;

        public OrderItem(String productId, String productName, double price, int amount, double subtotal) {
            this.productId = productId;
            this.productName = productName;
            this.price = price;
            this.amount = amount;
            this.subtotal = subtotal;
        }
    }

    public BookForClientPanel() {
        setBackground(Color.WHITE);
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        // ---------- CLIENT HEADER ROW (heading on the left, search on the right) ----------
        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        headerRow.add(makeHeading("Select A Client"), BorderLayout.WEST);

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        searchPanel.setOpaque(false);

        JLabel lblSearch = new JLabel("Search Client:");
        lblSearch.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblSearch.setForeground(Color.decode("#222222"));

        txtSearchClient = new JTextField();
        txtSearchClient.setPreferredSize(new Dimension(243, 26));
        txtSearchClient.setBackground(FIELD_BG);
        txtSearchClient.setFont(new Font("SansSerif", Font.PLAIN, 13));
        txtSearchClient.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_CLR),
                BorderFactory.createEmptyBorder(0, 6, 0, 6)));

        JButton btnSearch = new JButton("Search");
        btnSearch.setPreferredSize(new Dimension(113, 37));
        btnSearch.setBackground(BLUE);
        btnSearch.setForeground(Color.WHITE);
        btnSearch.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btnSearch.setFocusable(false);
        btnSearch.setOpaque(true);
        btnSearch.setContentAreaFilled(true);
        btnSearch.setBorder(BorderFactory.createLineBorder(Color.decode("#1E293B")));

        searchPanel.add(lblSearch);
        searchPanel.add(txtSearchClient);
        searchPanel.add(btnSearch);
        headerRow.add(searchPanel, BorderLayout.EAST);

        gbc.gridy = 0;
        gbc.insets = new Insets(0, 2, 2, 2);
        add(headerRow, gbc);

        // ---------- CLIENT TABLE ----------
        String[] clientCols = {"Client ID", "Client Name", "Address", "Contact Number"};
        clientModel = new DefaultTableModel(null, clientCols) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // nothing editable in the client table
            }
        };
        clientTable = makeTable(clientModel);
        clientTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane clientScroll = makeScroll(clientTable);
        clientScroll.setPreferredSize(new Dimension(100, 177));

        gbc.gridy = 1;
        gbc.insets = new Insets(0, 2, 0, 2);
        add(clientScroll, gbc);

        // ---------- ITEMS SECTION ----------
        gbc.gridy = 2;
        gbc.insets = new Insets(10, 2, 2, 0);
        add(makeHeading("Select Items"), gbc);

        String[] itemCols = {"Product ID", "Product", "Supplier", "Price", "Stock", "Amount"};
        itemsModel = new DefaultTableModel(null, itemCols) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == AMOUNT_COL; // ONLY "Amount" is editable
            }
        };
        itemsTable = makeTable(itemsModel);
        itemsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Commit whatever is being typed when focus leaves the cell (e.g. clicking Proceed)
        itemsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        // Editor for the Amount column (readable on the dark background)
        JTextField editorField = new JTextField();
        editorField.setBackground(DARK_LINE);
        editorField.setForeground(Color.WHITE);
        editorField.setCaretColor(Color.WHITE);
        editorField.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        itemsTable.getColumnModel().getColumn(AMOUNT_COL).setCellEditor(new DefaultCellEditor(editorField));

        JScrollPane itemsScroll = makeScroll(itemsTable);

        gbc.gridy = 3;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 2, 0, 2);
        add(itemsScroll, gbc);

        // ---------- PROCEED BUTTON (bottom right) ----------
        btnProceed = new JButton("Proceed");
        btnProceed.setBackground(BLUE);
        btnProceed.setForeground(Color.WHITE);
        btnProceed.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btnProceed.setFocusable(false);
        btnProceed.setOpaque(true);
        btnProceed.setContentAreaFilled(true);
        btnProceed.setBorder(BorderFactory.createLineBorder(Color.decode("#1E293B")));
        btnProceed.setPreferredSize(new Dimension(100, 54));

        JPanel bottom = new JPanel(new GridBagLayout());
        bottom.setOpaque(false);
        GridBagConstraints b = new GridBagConstraints();
        b.gridy = 0;
        b.fill = GridBagConstraints.HORIZONTAL;
        b.gridx = 0;
        b.weightx = 0.66;                       // spacer on the left
        bottom.add(Box.createHorizontalGlue(), b);
        b.gridx = 1;
        b.weightx = 0.34;                       // button takes ~1/3 of the width
        b.insets = new Insets(8, 0, 6, 6);
        bottom.add(btnProceed, b);

        gbc.gridy = 4;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 0, 0);
        add(bottom, gbc);

        // ---------- LISTENERS ----------
        btnSearch.addActionListener(e -> loadClients(txtSearchClient.getText().trim()));
        txtSearchClient.addActionListener(e -> loadClients(txtSearchClient.getText().trim()));
        btnProceed.addActionListener(e -> handleProceed());

        // ---------- LOAD DATA FROM THE DATABASE ----------
        loadClients("");
        loadProducts();
    }

    // ---------- DATABASE ----------

    /** Loads clients, optionally filtered by a keyword (empty = all). */
    public void loadClients(String keyword) {
        // Assumes a "clients" table with these column names. Adjust them to match yours.
        String query = "SELECT client_id, client_name, address, contact_num FROM clients "
                + "WHERE client_id::text ILIKE ? OR client_name ILIKE ? "
                + "OR address ILIKE ? OR contact_num::text ILIKE ? "
                + "ORDER BY client_id";

        clientModel.setRowCount(0);

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(query)) {

            String like = "%" + keyword + "%";
            for (int i = 1; i <= 4; i++) {
                pst.setString(i, like);
            }

            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) {
                    clientModel.addRow(new Object[]{
                            rs.getString("client_id"),
                            rs.getString("client_name"),
                            rs.getString("address"),
                            rs.getString("contact_num")
                    });
                }
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not load clients: " + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    /** Loads all products. The Amount column starts blank for every row. */
    public void loadProducts() {
        String query = "SELECT product_id, product_name, supplier, price, stock "
                + "FROM products ORDER BY product_id";

        itemsModel.setRowCount(0);

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(query);
             ResultSet rs = pst.executeQuery()) {

            while (rs.next()) {
                itemsModel.addRow(new Object[]{
                        rs.getString("product_id"),
                        rs.getString("product_name"),
                        rs.getString("supplier"),
                        rs.getDouble("price"),   // Double so it renders as 11 instead of 11.0
                        rs.getInt("stock"),
                        ""                       // Amount (editable)
                });
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not load products: " + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    // ---------- PROCEED LOGIC ----------
    private void handleProceed() {
        // Make sure a cell still being edited is saved first
        if (itemsTable.isEditing()) {
            itemsTable.getCellEditor().stopCellEditing();
        }

        // 1. Check that a client is selected
        int selectedClientRow = clientTable.getSelectedRow();
        if (selectedClientRow == -1) {
            JOptionPane.showMessageDialog(this,
                    "Please select a client from the top table first.",
                    "Selection Error",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String clientId   = String.valueOf(clientTable.getValueAt(selectedClientRow, 0));
        String clientName = String.valueOf(clientTable.getValueAt(selectedClientRow, 1));

        // 2. Collect products with amount > 0
        List<OrderItem> selectedItems = new ArrayList<>();
        double grandTotal = 0.0;

        for (int i = 0; i < itemsModel.getRowCount(); i++) {
            // Columns: 0=ID, 1=Product, 2=Supplier, 3=Price, 4=Stock, 5=Amount
            String productId   = String.valueOf(itemsModel.getValueAt(i, 0));
            String productName = String.valueOf(itemsModel.getValueAt(i, 1));
            double price       = Double.parseDouble(String.valueOf(itemsModel.getValueAt(i, 3)));
            int stock          = Integer.parseInt(String.valueOf(itemsModel.getValueAt(i, 4)));

            Object amountObj = itemsModel.getValueAt(i, AMOUNT_COL);
            int amount = 0;
            if (amountObj != null && !amountObj.toString().trim().isEmpty()) {
                try {
                    amount = Integer.parseInt(amountObj.toString().trim());
                } catch (NumberFormatException ex) {
                    amount = 0; // ignore invalid numbers
                }
            }

            if (amount > 0) {
                if (amount > stock) {
                    JOptionPane.showMessageDialog(this,
                            "Amount for \"" + productName + "\" exceeds available stock (" + stock + ").",
                            "Insufficient Stock",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                double subtotal = price * amount;
                grandTotal += subtotal;
                selectedItems.add(new OrderItem(productId, productName, price, amount, subtotal));
            }
        }

        // 3. At least one item must be ordered
        if (selectedItems.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter an amount of at least 1 for at least one product.",
                    "No Items Selected",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 4. Success: pass these to your review screen or database handler
        // 4. Open the review window (same size/position as the main window)
        Window owner = SwingUtilities.getWindowAncestor(this);
        String address = String.valueOf(clientTable.getValueAt(selectedClientRow, 2));
        String contact = String.valueOf(clientTable.getValueAt(selectedClientRow, 3));

        new BookingReviewDialog(owner, clientId, clientName, address, contact, selectedItems,
                this::loadProducts).setVisible(true);
    }

    // ---------- HELPERS ----------
    private JLabel makeHeading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, 26));
        l.setForeground(HEADING);
        return l;
    }

    private JTable makeTable(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setRowHeight(25);
        t.setBackground(DARK);
        t.setForeground(TEXT_LIGHT);
        t.setSelectionBackground(BLUE);
        t.setSelectionForeground(Color.WHITE);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFillsViewportHeight(true);
        t.setFont(new Font("SansSerif", Font.PLAIN, 13));
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        // Left-aligned cells; prices shown as 11 instead of 11.0
        final DecimalFormat fmt = new DecimalFormat("0.##");
        DefaultTableCellRenderer cell = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                if (value instanceof Double) {
                    setText(fmt.format(value));
                } else {
                    super.setValue(value);
                }
            }
        };
        cell.setHorizontalAlignment(SwingConstants.LEFT);
        cell.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        t.setDefaultRenderer(Object.class, cell);

        // Dark header with light, centered text
        JTableHeader header = t.getTableHeader();
        header.setReorderingAllowed(false);
        header.setResizingAllowed(false);
        header.setPreferredSize(new Dimension(100, 32));
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, false, false, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                setBackground(DARK);
                setForeground(TEXT_LIGHT);
                setFont(new Font("SansSerif", Font.PLAIN, 13));
                setOpaque(true);
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, DARK_LINE));
                return this;
            }
        });
        return t;
    }

    private JScrollPane makeScroll(JTable table) {
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        sp.getViewport().setBackground(DARK_LINE);
        return sp;
    }
}