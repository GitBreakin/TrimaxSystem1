package com.trimaxsystem;

import java.awt.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

public class InventoryPanel extends JPanel {

    // Reference width of the design (taken from the target screenshot)
    private static final double DESIGN_WIDTH = 1085.0;

    private static final Color FIELD_BG   = Color.decode("#F4F6F8");
    private static final Color DARK       = Color.decode("#45494D");
    private static final Color BLUE       = Color.decode("#2563EB");
    private static final Color BORDER_CLR = Color.decode("#555555");

    private DefaultTableModel inventoryTable;
    private JTable table;

    // Input fields are class-level so the table click can fill them
    private JTextField txtProduct;
    private JTextField txtSupplier;
    private JTextField txtPrice;
    private JTextField txtStock;
    private JTextField txtSearch;
    private JComboBox<String> cmbFilter;

    // Product ID of the currently selected row (null = nothing selected)
    private String selectedProductId = null;

    public InventoryPanel() {
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
        top.setPreferredSize(new Dimension(1085, 190));

        // Labels
        place(top, makeLabel("Product:"),         12,  4,   150, 20);
        place(top, makeLabel("Supplier:"),        314, 4,   150, 20);
        place(top, makeLabel("Price:"),           652, 4,   100, 20);
        place(top, makeLabel("Stock:"),           914, 4,   100, 20);
        place(top, makeLabel("Search Products:"), 12,  110, 200, 20);

        // Input fields
        txtProduct  = makeField();
        txtSupplier = makeField();
        txtPrice    = makeField();
        txtStock    = makeField();
        txtSearch   = makeField();

        place(top, txtProduct,  12,  28,  231, 44);
        place(top, txtSupplier, 314, 28,  231, 44);
        place(top, txtPrice,    652, 28,  162, 44);
        place(top, txtStock,    914, 28,  163, 44);
        place(top, txtSearch,   12,  138, 356, 44);

        // Status filter
        String[] filterOptions = {"Status", "High", "Moderate", "Low", "Critical", "Out"};
        cmbFilter = makeCombo(filterOptions);
        cmbFilter.addActionListener(e -> searchProducts());
        place(top, cmbFilter, 237, 102, 132, 27);

        // ---------- BUTTONS ----------
        JButton btnSearch = makeButton("Search");
        btnSearch.addActionListener(e -> {
            searchProducts();
            clearFields();
        });
        txtSearch.addActionListener(e -> searchProducts()); // Enter key still searches

        JButton btnClear = makeButton("Clear Fields");
        btnClear.addActionListener(e -> clearFields());

        JButton btnAdd = makeButton("Add A Product");
        btnAdd.addActionListener(e -> {
            addProduct();
            clearFields();
        });

        JButton btnUpdate = makeButton("Update Product");
        btnUpdate.addActionListener(e -> {
            updateProduct();
            clearFields();
        });

        JButton btnDelete = makeButton("Delete Product");
        btnDelete.addActionListener(e -> {
            // If the user clicks "No" in the confirmation, keep the fields as they are
            if (deleteProduct()) {
                clearFields();
            }
        });

        place(top, btnSearch, 375, 138, 150, 44);
        place(top, btnClear,  614, 87,  220, 44);
        place(top, btnAdd,    857, 87,  220, 44);
        place(top, btnUpdate, 614, 138, 220, 44);
        place(top, btnDelete, 857, 138, 220, 44);

        add(top, BorderLayout.NORTH);

        // ---------- TABLE ----------
        String[] columnNames = {"Product ID", "Product", "Supplier", "Price", "Stock", "Status"};

        inventoryTable = new DefaultTableModel(null, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(inventoryTable);
        table.setRowHeight(32);
        table.setBackground(FIELD_BG);
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

            selectedProductId = String.valueOf(inventoryTable.getValueAt(row, 0));
            txtProduct.setText(String.valueOf(inventoryTable.getValueAt(row, 1)));
            txtSupplier.setText(String.valueOf(inventoryTable.getValueAt(row, 2)));
            txtPrice.setText(String.valueOf(inventoryTable.getValueAt(row, 3)));
            txtStock.setText(String.valueOf(inventoryTable.getValueAt(row, 4)));
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
        scrollPane.getViewport().setBackground(FIELD_BG);

        add(scrollPane, BorderLayout.CENTER);

        loadInventory("", "Status");
    }

    // ---------- HELPERS ----------

    private void place(JPanel parent, JComponent c, int x, int y, int w, int h) {
        c.putClientProperty("design", new Rectangle(x, y, w, h));
        parent.add(c);
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

    private String getStatus(int stock) {
        if (stock > 99)      return "High";
        else if (stock > 59) return "Moderate";
        else if (stock > 29) return "Low";
        else if (stock > 9)  return "Critical";
        else                 return "Out";
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
        loadInventory("", "Status");
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

                    // Apply the Status filter ("Status" = no filter)
                    if (!"Status".equals(statusFilter) && !status.equals(statusFilter)) {
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
        } catch (SQLException e) {
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