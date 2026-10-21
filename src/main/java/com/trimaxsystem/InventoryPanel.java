package com.trimaxsystem;

import java.awt.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

public class InventoryPanel extends JPanel {

    private final DefaultTableModel inventoryTable;
    private final JTable table;

    private final JTextField txtProduct = new JTextField(16);
    private final JTextField txtSupplier = new JTextField(16);
    private final JTextField txtPrice = new JTextField(10);
    private final JTextField txtStock = new JTextField(10);
    private final JTextField txtSearch = new JTextField(20);
    private final JComboBox<String> cmbFilter =
            new JComboBox<>(new String[]{"All stock levels", "High", "Moderate", "Low", "Critical", "Out"});
    private final JLabel lblCount;

    // Product ID of the currently selected row (null = nothing selected)
    private String selectedProductId;

    public InventoryPanel() {
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(14, 20, 16, 20));

        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(buildProductCard());
        controls.add(Box.createVerticalStrut(10));
        controls.add(buildSearchCard());
        add(controls, BorderLayout.NORTH);

        String[] columnNames = {"Product ID", "Product", "Supplier", "Price", "Stock", "Status"};
        inventoryTable = new DefaultTableModel(null, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(inventoryTable);
        styleTable();
        for (int i = 0; i < columnNames.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(100);
        }

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewRow = table.getSelectedRow();
            if (viewRow == -1) {
                selectedProductId = null;
                return;
            }
            int row = table.convertRowIndexToModel(viewRow);

            selectedProductId = String.valueOf(inventoryTable.getValueAt(row, 0));
            txtProduct.setText(String.valueOf(inventoryTable.getValueAt(row, 1)));
            txtSupplier.setText(String.valueOf(inventoryTable.getValueAt(row, 2)));
            txtPrice.setText(String.valueOf(inventoryTable.getValueAt(row, 3)));
            txtStock.setText(String.valueOf(inventoryTable.getValueAt(row, 4)));
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scrollPane.getViewport().setBackground(Theme.CARD);

        JPanel tableCard = new JPanel(new BorderLayout(0, 8));
        tableCard.setBackground(Theme.CARD);
        tableCard.setBorder(cardBorder());
        JPanel tableHeading = new JPanel(new BorderLayout());
        tableHeading.setOpaque(false);
        JLabel tableTitle = new JLabel("Inventory");
        tableTitle.setFont(new Font("SansSerif", Font.BOLD, 16));
        tableTitle.setForeground(Theme.TEXT);
        lblCount = new JLabel(" ");
        lblCount.setFont(Theme.SUBTLE);
        lblCount.setForeground(Theme.MUTED);
        tableHeading.add(tableTitle, BorderLayout.WEST);
        tableHeading.add(lblCount, BorderLayout.EAST);
        tableCard.add(tableHeading, BorderLayout.NORTH);
        tableCard.add(scrollPane, BorderLayout.CENTER);
        add(tableCard, BorderLayout.CENTER);

        loadInventory("", "All stock levels");
    }

    // ---------- HELPERS ----------

    private JPanel buildProductCard() {
        styleField(txtProduct);
        styleField(txtSupplier);
        styleField(txtPrice);
        styleField(txtStock);

        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBackground(Theme.CARD);
        card.setBorder(cardBorder());

        JPanel heading = new JPanel(new BorderLayout(0, 3));
        heading.setOpaque(false);
        JLabel title = new JLabel("Product details");
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(Theme.TEXT);
        JLabel hint = new JLabel("Select a product below to edit its details.");
        hint.setFont(Theme.SUBTLE);
        hint.setForeground(Theme.MUTED);
        heading.add(title, BorderLayout.NORTH);
        heading.add(hint, BorderLayout.SOUTH);
        card.add(heading, BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridLayout(1, 4, 12, 0));
        fields.setOpaque(false);
        fields.add(fieldGroup("Product", txtProduct));
        fields.add(fieldGroup("Supplier", txtSupplier));
        fields.add(fieldGroup("Price", txtPrice));
        fields.add(fieldGroup("Stock quantity", txtStock));
        card.add(fields, BorderLayout.CENTER);

        JButton btnClear = UiKit.secondaryButton("Clear fields");
        JButton btnAdd = UiKit.primaryButton("Add product");
        JButton btnUpdate = UiKit.secondaryButton("Update product");
        JButton btnDelete = UiKit.dangerButton("Delete product");
        btnClear.addActionListener(e -> clearFields());
        btnAdd.addActionListener(e -> {
            addProduct();
            clearFields();
        });
        btnUpdate.addActionListener(e -> {
            updateProduct();
            clearFields();
        });
        btnDelete.addActionListener(e -> {
            if (deleteProduct()) clearFields();
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnClear);
        actions.add(btnAdd);
        actions.add(btnUpdate);
        actions.add(btnDelete);
        card.add(actions, BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildSearchCard() {
        UiKit.styleField(txtSearch);
        txtSearch.setToolTipText("Search by product ID, product name, or supplier");
        txtSearch.addActionListener(e -> searchProducts());
        cmbFilter.setFont(Theme.SUBTLE);
        cmbFilter.setBackground(Theme.CARD);
        cmbFilter.setForeground(Theme.TEXT);
        cmbFilter.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        cmbFilter.addActionListener(e -> searchProducts());

        JButton btnSearch = UiKit.primaryButton("Search");
        btnSearch.addActionListener(e -> searchProducts());

        JPanel card = new JPanel(new BorderLayout(12, 0));
        card.setBackground(Theme.CARD);
        card.setBorder(cardBorder());
        JPanel searchGroup = new JPanel(new BorderLayout(8, 0));
        searchGroup.setOpaque(false);
        searchGroup.add(UiKit.caption("Search products"), BorderLayout.NORTH);
        searchGroup.add(txtSearch, BorderLayout.CENTER);

        JPanel filterGroup = new JPanel(new BorderLayout(8, 0));
        filterGroup.setOpaque(false);
        filterGroup.add(UiKit.caption("Stock status"), BorderLayout.NORTH);
        filterGroup.add(cmbFilter, BorderLayout.CENTER);

        JPanel inputs = new JPanel(new GridBagLayout());
        inputs.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 10);
        c.weightx = 1;
        inputs.add(searchGroup, c);
        c.gridx = 1;
        c.weightx = 0;
        inputs.add(filterGroup, c);
        card.add(inputs, BorderLayout.CENTER);
        card.add(btnSearch, BorderLayout.EAST);
        return card;
    }

    private JPanel fieldGroup(String label, JTextField field) {
        JPanel group = new JPanel(new BorderLayout(0, 5));
        group.setOpaque(false);
        group.add(UiKit.caption(label), BorderLayout.NORTH);
        group.add(field, BorderLayout.CENTER);
        return group;
    }

    private javax.swing.border.Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(12, 14, 12, 14));
    }

    private void styleField(JTextField field) {
        field.setBackground(Theme.CARD);
        UiKit.styleField(field);
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
                if (column == 5 && !selected) {
                    String status = value == null ? "" : value.toString();
                    switch (status) {
                        case "High":
                            setForeground(Theme.OK);
                            break;
                        case "Moderate":
                            setForeground(Theme.BLUE);
                            break;
                        case "Low":
                            setForeground(Theme.WARN);
                            break;
                        case "Critical":
                        case "Out":
                            setForeground(Theme.DANGER);
                            break;
                        default:
                            setForeground(Theme.MUTED);
                    }
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
    }

    private String getStatus(int stock) {
        if (stock > 99)      return "High";
        else if (stock > 59) return "Moderate";
        else if (stock > 29) return "Low";
        //else if (stock > 9)  return "Critical";
        else if (stock == 0) return "Out";
        else                 return "Critical";
    }

    /** Blanks Product/Supplier/Price/Stock and deselects the table row. */
    private void clearFields() {
        txtProduct.setText("");
        txtSupplier.setText("");
        txtPrice.setText("");
        txtStock.setText("");
        selectedProductId = null;
        table.clearSelection();
    }

    // ---------- DATA ----------

    /** Reload everything (keeps your old loadInventory() calls working). */
    public void loadInventory() {
        loadInventory("", "All stock levels");
    }

    private void searchProducts() {
        loadInventory(txtSearch.getText().trim(), (String) cmbFilter.getSelectedItem());
    }

    private void loadInventory(String keyword, String statusFilter) {
        String query = "SELECT * FROM products "
                + "WHERE product_id::text ILIKE ? OR product_name ILIKE ? OR supplier ILIKE ? "
                + "ORDER BY product_id";

        inventoryTable.setRowCount(0);

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(query)) {

            String like = "%" + keyword + "%";
            pst.setString(1, like);
            pst.setString(2, like);
            pst.setString(3, like);

            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) {
                    int stock = rs.getInt("stock");
                    String status = getStatus(stock);

                    if (statusFilter != null && !"All stock levels".equals(statusFilter)
                            && !"Status".equals(statusFilter) && !status.equals(statusFilter)) {
                        continue;
                    }

                    inventoryTable.addRow(new Object[]{
                            rs.getString("product_id"),
                            rs.getString("product_name"),
                            rs.getString("supplier"),
                            rs.getString("price"),
                            String.valueOf(stock),
                            status
                    });
                }
            }
            lblCount.setText(inventoryTable.getRowCount() + " products");
        } catch (SQLException e) {
            lblCount.setText("Unable to load products");
            JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    // ---------- ADD / UPDATE / DELETE ----------
    // (The button listeners call clearFields() afterwards, so these don't need to.)

    private boolean validateInputs() {
        if (txtProduct.getText().trim().isEmpty() || txtSupplier.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Product and Supplier are required.",
                    "Missing Data", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        try {
            new BigDecimal(txtPrice.getText().trim());
            Integer.parseInt(txtStock.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Price and Stock must be valid numbers.",
                    "Invalid Input", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        return true;
    }

    private void addProduct() {
        if (!validateInputs()) return;

        // Assumes product_id is generated by the database (serial/identity/default)
        String sql = "INSERT INTO products (product_name, supplier, price, stock) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, txtProduct.getText().trim());
            pst.setString(2, txtSupplier.getText().trim());
            pst.setBigDecimal(3, new BigDecimal(txtPrice.getText().trim()));
            pst.setInt(4, Integer.parseInt(txtStock.getText().trim()));
            pst.executeUpdate();
            loadInventory();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not add product: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateProduct() {
        if (selectedProductId == null) {
            JOptionPane.showMessageDialog(this, "Click a product in the table first.",
                    "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!validateInputs()) return;

        String sql = "UPDATE products SET product_name = ?, supplier = ?, price = ?, stock = ? "
                + "WHERE product_id::text = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, txtProduct.getText().trim());
            pst.setString(2, txtSupplier.getText().trim());
            pst.setBigDecimal(3, new BigDecimal(txtPrice.getText().trim()));
            pst.setInt(4, Integer.parseInt(txtStock.getText().trim()));
            pst.setString(5, selectedProductId);
            pst.executeUpdate();
            loadInventory();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not update product: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** @return false if the user cancelled the confirmation, true otherwise. */
    private boolean deleteProduct() {
        if (selectedProductId == null) {
            JOptionPane.showMessageDialog(this, "Click a product in the table first.",
                    "No Selection", JOptionPane.WARNING_MESSAGE);
            return true;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete this product?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return false;

        String sql = "DELETE FROM products WHERE product_id::text = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, selectedProductId);
            pst.executeUpdate();
            loadInventory();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not delete product: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
        return true;
    }
}