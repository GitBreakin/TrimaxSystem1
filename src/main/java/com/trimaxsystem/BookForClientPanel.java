package com.trimaxsystem;

import java.awt.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

public class BookForClientPanel extends JPanel {

    private static final int AMOUNT_COL = 5;

    private final JTable clientTable;
    private final JTable itemsTable;
    private final DefaultTableModel clientModel;
    private final DefaultTableModel itemsModel;
    private final JTextField txtSearchClient;
    private final JTextField txtSearchProduct;
    private final JButton btnProceed;
    private final JButton btnCancel;
    private final TableRowSorter<DefaultTableModel> clientSorter;
    private final TableRowSorter<DefaultTableModel> itemsSorter;

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
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(14, 20, 16, 20));

        txtSearchClient = new JTextField(20);
        UiKit.styleField(txtSearchClient);
        txtSearchClient.setToolTipText("Search by client ID, name, address, or contact number");
        txtSearchProduct = new JTextField(18);
        UiKit.styleField(txtSearchProduct);
        txtSearchProduct.setToolTipText("Search products by ID, name, or supplier");
        JPanel clientCard = new JPanel(new BorderLayout(0, 8));
        clientCard.setBackground(Theme.CARD);
        clientCard.setBorder(cardBorder());
        JPanel clientHeader = new JPanel(new BorderLayout(12, 0));
        clientHeader.setOpaque(false);
        clientHeader.add(sectionHeading("Select a client", "Choose the customer for this booking."),
                BorderLayout.WEST);
        JPanel search = new JPanel(new BorderLayout(8, 0));
        search.setOpaque(false);
        search.add(UiKit.caption("Search customers"), BorderLayout.NORTH);
        search.add(txtSearchClient, BorderLayout.CENTER);
        clientHeader.add(search, BorderLayout.EAST);
        clientCard.add(clientHeader, BorderLayout.NORTH);

        String[] clientCols = {"Client ID", "Client Name", "Address", "Contact Number"};
        clientModel = new DefaultTableModel(null, clientCols) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        clientTable = makeTable(clientModel);
        clientSorter = new TableRowSorter<>(clientModel);
        clientTable.setRowSorter(clientSorter);
        clientTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane clientScroll = makeScroll(clientTable);
        clientScroll.setPreferredSize(new Dimension(100, 177));
        clientCard.add(clientScroll, BorderLayout.CENTER);
        add(clientCard, BorderLayout.NORTH);

        String[] itemCols = {"Product ID", "Product", "Supplier", "Price", "Stock", "Amount"};
        itemsModel = new DefaultTableModel(null, itemCols) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == AMOUNT_COL;
            }
        };
        itemsTable = makeTable(itemsModel);
        itemsSorter = new TableRowSorter<>(itemsModel);
        itemsTable.setRowSorter(itemsSorter);
        itemsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        itemsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        JTextField editorField = new JTextField();
        editorField.setBackground(Color.decode("#EFF6FF"));
        editorField.setForeground(Theme.TEXT);
        editorField.setCaretColor(Theme.TEXT);
        editorField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BLUE), new EmptyBorder(0, 7, 0, 7)));
        itemsTable.getColumnModel().getColumn(AMOUNT_COL).setCellEditor(new DefaultCellEditor(editorField));
        JScrollPane itemsScroll = makeScroll(itemsTable);
        JPanel itemsCard = new JPanel(new BorderLayout(0, 8));
        itemsCard.setBackground(Theme.CARD);
        itemsCard.setBorder(cardBorder());
        JPanel itemsHeader = new JPanel(new BorderLayout(12, 0));
        itemsHeader.setOpaque(false);
        itemsHeader.add(sectionHeading("Select items", "Enter an amount for each product to include."),
                BorderLayout.WEST);
        JPanel productSearch = new JPanel(new BorderLayout(8, 0));
        productSearch.setOpaque(false);
        productSearch.add(UiKit.caption("Search products"), BorderLayout.NORTH);
        productSearch.add(txtSearchProduct, BorderLayout.CENTER);
        itemsHeader.add(productSearch, BorderLayout.EAST);
        itemsCard.add(itemsHeader, BorderLayout.NORTH);
        itemsCard.add(itemsScroll, BorderLayout.CENTER);
        add(itemsCard, BorderLayout.CENTER);

        btnProceed = UiKit.primaryButton("Proceed to review");
        btnProceed.setPreferredSize(new Dimension(180, 42));
        btnCancel = UiKit.secondaryButton("Cancel booking");
        btnCancel.addActionListener(e -> cancelBooking());

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        JLabel instruction = new JLabel("Select a client and enter quantities, then review the booking.");
        instruction.setFont(Theme.SUBTLE);
        instruction.setForeground(Theme.MUTED);
        bottom.add(instruction, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnCancel);
        actions.add(btnProceed);
        bottom.add(actions, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        btnProceed.addActionListener(e -> handleProceed());
        addSearchListener(txtSearchClient, this::filterClients);
        addSearchListener(txtSearchProduct, this::filterProducts);

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
                        rs.getBigDecimal("stock").intValue(),   // whole cases here (a half is not counted)
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
    private void addSearchListener(JTextField field, Runnable filter) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filter.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filter.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filter.run();
            }
        });
    }

    private void filterClients() {
        applyTextFilter(clientSorter, txtSearchClient.getText(), 4);
    }

    private void filterProducts() {
        applyTextFilter(itemsSorter, txtSearchProduct.getText(), 3);
    }

    private void applyTextFilter(TableRowSorter<DefaultTableModel> sorter, String text, int columns) {
        String keyword = text.trim();
        if (keyword.isEmpty()) {
            sorter.setRowFilter(null);
            return;
        }
        final Pattern match = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
        RowFilter<DefaultTableModel, Integer> filter = new RowFilter<DefaultTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                for (int column = 0; column < columns; column++) {
                    Object value = entry.getValue(column);
                    if (value != null && match.matcher(value.toString()).find()) {
                        return true;
                    }
                }
                return false;
            }
        };
        sorter.setRowFilter(filter);
    }

    private void cancelBooking() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Cancel this booking? Your current selections will be discarded.",
                "Cancel Booking", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) window.dispose();
    }

    private JPanel sectionHeading(String titleText, String subtitleText) {
        JPanel heading = new JPanel(new BorderLayout(0, 3));
        heading.setOpaque(false);
        JLabel title = new JLabel(titleText);
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(Theme.TEXT);
        JLabel subtitle = new JLabel(subtitleText);
        subtitle.setFont(Theme.SUBTLE);
        subtitle.setForeground(Theme.MUTED);
        heading.add(title, BorderLayout.NORTH);
        heading.add(subtitle, BorderLayout.SOUTH);
        return heading;
    }

    private javax.swing.border.Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(12, 14, 12, 14));
    }

    private JTable makeTable(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setRowHeight(32);
        t.setBackground(Theme.CARD);
        t.setForeground(Theme.TEXT);
        t.setSelectionBackground(Color.decode("#DBEAFE"));
        t.setSelectionForeground(Theme.TEXT);
        t.setShowVerticalLines(false);
        t.setGridColor(Theme.BORDER);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setFillsViewportHeight(true);
        t.setFont(Theme.SUBTLE);
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        final DecimalFormat fmt = new DecimalFormat("0.##");
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                           boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, false, row, column);
                if (value instanceof Double) {
                    setText(fmt.format((Double) value));
                } else {
                    setText(value == null ? "" : value.toString());
                }
                setFont(Theme.SUBTLE);
                setForeground(Theme.TEXT);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                return this;
            }
        });

        DefaultTableCellRenderer header = new DefaultTableCellRenderer();
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setBackground(Color.decode("#F8FAFC"));
        header.setForeground(Theme.MUTED);
        header.setFont(new Font("SansSerif", Font.BOLD, 12));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        t.getTableHeader().setDefaultRenderer(header);
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setPreferredSize(new Dimension(100, 34));
        return t;
    }

    private JScrollPane makeScroll(JTable table) {
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        sp.getViewport().setBackground(Theme.CARD);
        return sp;
    }
}