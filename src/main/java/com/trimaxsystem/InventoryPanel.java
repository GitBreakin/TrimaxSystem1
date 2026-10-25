package com.trimaxsystem;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

/**
 * Products: details, stock and (Stage F) the product picture that agents see in the mobile app.
 * A picture is chosen and previewed first, then saved together with Add product / Update product.
 */
public class InventoryPanel extends JPanel {

    // Table columns
    private static final int COL_PICTURE = 0, COL_ID = 1, COL_NAME = 2, COL_SUPPLIER = 3, COL_PRICE = 4,
            COL_STOCK = 5, COL_STATUS = 6;
    private static final int THUMB = 36, PREVIEW = 96;

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

    // Pictures: what the database has, and what the admin has chosen but not saved yet
    private final Map<String, String> imagePaths = new HashMap<>();
    private final Set<String> thumbsLoading = new HashSet<>();
    private final Set<String> thumbsFailed = new HashSet<>();
    private boolean picturesSupported = true;
    private byte[] pendingJpeg;
    private boolean pendingRemove;
    private boolean imageBusy;
    private int previewSeq;
    private final JLabel preview = new JLabel("", SwingConstants.CENTER);
    private final JLabel imageStatus = new JLabel(" ");
    private final JProgressBar imageProgress = new JProgressBar();
    private final JButton btnChooseImage = UiKit.secondaryButton("Choose picture...");
    private final JButton btnRemoveImage = UiKit.secondaryButton("Remove picture");
    private JButton btnAdd, btnUpdate, btnDelete, btnClear;

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

        String[] columnNames = {"Picture", "Product ID", "Product", "Supplier", "Price", "Stock", "Status"};
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
        table.getColumnModel().getColumn(COL_PICTURE).setPreferredWidth(60);
        table.getColumnModel().getColumn(COL_PICTURE).setMaxWidth(70);
        table.getColumnModel().getColumn(COL_PICTURE).setCellRenderer(new ThumbRenderer());

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewRow = table.getSelectedRow();
            if (viewRow == -1) {
                selectedProductId = null;
                return;
            }
            int row = table.convertRowIndexToModel(viewRow);

            selectedProductId = String.valueOf(inventoryTable.getValueAt(row, COL_ID));
            txtProduct.setText(String.valueOf(inventoryTable.getValueAt(row, COL_NAME)));
            txtSupplier.setText(String.valueOf(inventoryTable.getValueAt(row, COL_SUPPLIER)));
            txtPrice.setText(String.valueOf(inventoryTable.getValueAt(row, COL_PRICE)));
            txtStock.setText(String.valueOf(inventoryTable.getValueAt(row, COL_STOCK)));
            pendingJpeg = null;
            pendingRemove = false;
            showSavedPicture();
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
        fields.add(fieldGroup("Stock Quantity", txtStock));
        // Fields keep the full width (as before); the picture row sits under them.
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(fields, BorderLayout.NORTH);
        body.add(buildPictureBox(), BorderLayout.CENTER);
        card.add(body, BorderLayout.CENTER);

        btnClear = UiKit.secondaryButton("Clear fields");
        btnAdd = UiKit.primaryButton("Add product");
        btnUpdate = UiKit.secondaryButton("Update product");
        btnDelete = UiKit.dangerButton("Delete product");
        btnClear.addActionListener(e -> clearFields());
        // A chosen or removed picture is saved together with the product; the fields are cleared
        // only once everything is saved, so a failed upload can be retried with Update product.
        btnAdd.addActionListener(e -> {
            Integer id = addProduct();
            if (id != null) savePicture(id, true);
        });
        btnUpdate.addActionListener(e -> {
            Integer id = updateProduct();
            if (id != null) savePicture(id, false);
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

    // ---------- PICTURE ----------

    private JPanel buildPictureBox() {
        preview.setPreferredSize(new Dimension(PREVIEW, PREVIEW));
        preview.setMinimumSize(new Dimension(PREVIEW, PREVIEW));
        preview.setOpaque(true);
        preview.setBackground(Theme.BACKGROUND);
        preview.setForeground(Theme.MUTED);
        preview.setFont(new Font("SansSerif", Font.PLAIN, 11));
        preview.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        imageStatus.setFont(new Font("SansSerif", Font.PLAIN, 11));
        imageStatus.setForeground(Theme.MUTED);
        imageProgress.setIndeterminate(true);
        imageProgress.setVisible(false);
        imageProgress.setPreferredSize(new Dimension(PREVIEW, 6));
        btnChooseImage.setToolTipText("JPG or PNG, up to 15 MB. It is resized to 1024 px and saved with Add / Update product.");
        btnRemoveImage.setToolTipText("The picture is removed when you choose Update product.");
        btnChooseImage.addActionListener(e -> chooseImage());
        btnRemoveImage.addActionListener(e -> markRemoveImage());

        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 6));
        buttons.setOpaque(false);
        buttons.add(btnChooseImage);
        buttons.add(btnRemoveImage);

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setOpaque(false);
        right.add(UiKit.caption("Picture (shown in the mobile app)"), BorderLayout.NORTH);
        right.add(buttons, BorderLayout.CENTER);
        JPanel statusBox = new JPanel(new BorderLayout(0, 3));
        statusBox.setOpaque(false);
        statusBox.add(imageProgress, BorderLayout.NORTH);
        statusBox.add(imageStatus, BorderLayout.CENTER);
        right.add(statusBox, BorderLayout.SOUTH);

        JPanel box = new JPanel(new BorderLayout(10, 0));
        box.setOpaque(false);
        box.add(preview, BorderLayout.WEST);
        box.add(right, BorderLayout.CENTER);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));   // keeps the box at its own width
        row.setOpaque(false);
        row.add(box);
        showSavedPicture();
        return row;
    }

    private void chooseImage() {
        if (imageBusy) return;
        if (!picturesSupported) {
            JOptionPane.showMessageDialog(this, "Product pictures are not set up on the server yet "
                    + "(database update 20261025_06_product_images).", "Picture", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a product picture");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Pictures (JPG, PNG)", "jpg", "jpeg", "png"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final File file = chooser.getSelectedFile();
        setImageBusy(true, "Checking the picture...");
        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() throws Exception {
                return ProductImageService.prepare(file);
            }

            @Override
            protected void done() {
                setImageBusy(false, null);
                try {
                    pendingJpeg = get();
                    pendingRemove = false;
                    showPicture(ProductImageService.decode(pendingJpeg));
                    imageStatus.setText(html("New picture (" + (pendingJpeg.length / 1024) + " KB): saved with "
                            + (selectedProductId == null ? "Add product." : "Update product.")));
                    imageStatus.setForeground(Theme.BLUE);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(InventoryPanel.this, c.getMessage(), "Picture not used",
                            JOptionPane.WARNING_MESSAGE);
                    if (pendingJpeg == null) showSavedPicture();
                }
                updatePictureButtons();
            }
        }.execute();
    }

    private void markRemoveImage() {
        if (imageBusy) return;
        if (pendingJpeg != null) {           // just undo the choice
            pendingJpeg = null;
            showSavedPicture();
            return;
        }
        if (selectedProductId == null || imagePaths.get(selectedProductId) == null) return;
        pendingRemove = true;
        showPicture(null);
        preview.setText(html("<center>Will be removed</center>"));
        imageStatus.setText(html("The picture is removed when you choose Update product."));
        imageStatus.setForeground(Theme.WARN);
        updatePictureButtons();
    }

    /** Saves the chosen picture (or the removal) for this product, then clears the form. */
    private void savePicture(int productId, boolean added) {
        if (pendingJpeg == null && !pendingRemove) {
            clearFields();
            return;
        }
        final byte[] jpeg = pendingJpeg;
        final boolean remove = pendingRemove && !added;
        setImageBusy(true, jpeg != null ? "Uploading the picture..." : "Removing the picture...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (jpeg != null) ProductImageService.upload(productId, jpeg);
                else if (remove) ProductImageService.remove(productId);
                return null;
            }

            @Override
            protected void done() {
                setImageBusy(false, null);
                try {
                    get();
                    loadInventory();
                    clearFields();
                    imageStatus.setText(html(jpeg != null ? "Picture saved." : "Picture removed."));
                    imageStatus.setForeground(Theme.OK);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    loadInventory();
                    // Keep the product selected and the picture pending, so Update product retries it.
                    selectRow(String.valueOf(productId));
                    pendingJpeg = jpeg;
                    pendingRemove = remove;
                    if (jpeg != null) showPicture(ProductImageService.decode(jpeg));
                    imageStatus.setText(html("Picture not saved: " + c.getMessage()
                            + " Choose Update product to try again."));
                    imageStatus.setForeground(Theme.DANGER);
                    JOptionPane.showMessageDialog(InventoryPanel.this,
                            "The product was saved, but the picture was not:\n" + c.getMessage()
                                    + "\n\nChoose Update product to try again.",
                            "Picture not saved", JOptionPane.WARNING_MESSAGE);
                }
                updatePictureButtons();
            }
        }.execute();
    }

    private void selectRow(String productId) {
        for (int r = 0; r < inventoryTable.getRowCount(); r++) {
            if (productId.equals(String.valueOf(inventoryTable.getValueAt(r, COL_ID)))) {
                int view = table.convertRowIndexToView(r);
                if (view >= 0) table.getSelectionModel().setSelectionInterval(view, view);
                return;
            }
        }
    }

    /** The selected product's saved picture (downloaded in the background), or the empty state. */
    private void showSavedPicture() {
        final int mySeq = ++previewSeq;
        final String path = selectedProductId == null ? null : imagePaths.get(selectedProductId);
        if (path == null) {
            showPicture(null);
            preview.setText(html("<center>No picture</center>"));
            imageStatus.setText(html(selectedProductId == null
                    ? "Optional. Agents see it when they choose items."
                    : "This product has no picture yet."));
            imageStatus.setForeground(Theme.MUTED);
            updatePictureButtons();
            return;
        }
        BufferedImage cached = ProductImageService.cached(path);
        if (cached != null) {
            showPicture(cached);
            imageStatus.setText(" ");
            updatePictureButtons();
            return;
        }
        showPicture(null);
        preview.setText(html("<center>Loading...</center>"));
        imageStatus.setText(" ");
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return ProductImageService.load(path);
            }

            @Override
            protected void done() {
                if (mySeq != previewSeq) return;
                try {
                    showPicture(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    showPicture(null);
                    preview.setText(html("<center>Picture not available</center>"));
                    imageStatus.setText(html(c.getMessage()));
                    imageStatus.setForeground(Theme.DANGER);
                }
                updatePictureButtons();
            }
        }.execute();
        updatePictureButtons();
    }

    private void showPicture(BufferedImage img) {
        if (img == null) {
            preview.setIcon(null);
            return;
        }
        preview.setText(null);
        preview.setIcon(new ImageIcon(fit(img, PREVIEW - 4)));
    }

    private static Image fit(BufferedImage img, int max) {
        double scale = Math.min(max / (double) img.getWidth(), max / (double) img.getHeight());
        if (scale >= 1) return img;
        return img.getScaledInstance(Math.max(1, (int) Math.round(img.getWidth() * scale)),
                Math.max(1, (int) Math.round(img.getHeight() * scale)), Image.SCALE_SMOOTH);
    }

    private void setImageBusy(boolean busy, String text) {
        imageBusy = busy;
        imageProgress.setVisible(busy);
        if (text != null) {
            imageStatus.setText(html(text));
            imageStatus.setForeground(Theme.MUTED);
        }
        for (JButton b : new JButton[]{btnAdd, btnUpdate, btnDelete, btnClear}) {
            if (b != null) b.setEnabled(!busy);
        }
        updatePictureButtons();
    }

    private void updatePictureButtons() {
        btnChooseImage.setEnabled(!imageBusy && picturesSupported);
        boolean hasSaved = selectedProductId != null && imagePaths.get(selectedProductId) != null;
        btnRemoveImage.setEnabled(!imageBusy && picturesSupported && (pendingJpeg != null || (hasSaved && !pendingRemove)));
        btnRemoveImage.setText(pendingJpeg != null ? "Undo new picture" : "Remove picture");
    }

    private static String html(String text) {
        String safe = text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("&lt;center&gt;", "<center>").replace("&lt;/center&gt;", "</center>");
        return "<html><body style='width:150px'>" + safe + "</body></html>";
    }

    private static boolean hasColumn(ResultSet rs, String name) throws SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            if (name.equalsIgnoreCase(md.getColumnName(i))) return true;
        }
        return false;
    }

    /** Small picture in the table: downloaded once in the background, "-" when there is none. */
    private final class ThumbRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, "", selected, false, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            setIcon(null);
            String path = value == null ? "" : value.toString();
            if (path.isEmpty()) {
                setText("\u2014");
                setForeground(Theme.MUTED);
                setToolTipText("No picture");
                return this;
            }
            BufferedImage img = ProductImageService.cached(path);
            if (img != null) {
                setIcon(new ImageIcon(fit(img, THUMB)));
                setToolTipText(null);
            } else if (thumbsFailed.contains(path)) {
                setText("missing");
                setForeground(Theme.DANGER);
                setToolTipText("The picture could not be loaded. Search again to retry.");
            } else {
                setText("...");
                setForeground(Theme.MUTED);
                loadThumb(path);
            }
            return this;
        }
    }

    private void loadThumb(String path) {
        if (!thumbsLoading.add(path)) return;
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return ProductImageService.load(path);
            }

            @Override
            protected void done() {
                try {
                    get();
                    thumbsLoading.remove(path);   // loaded: the renderer finds it in the cache
                } catch (Exception ignored) {
                    thumbsFailed.add(path);       // shown as "missing"; Search / reload tries again
                }
                table.repaint();
            }
        }.execute();
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
        table.setRowHeight(44);
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
                if (column == COL_STATUS && !selected) {
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
        pendingJpeg = null;
        pendingRemove = false;
        showSavedPicture();
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
        imagePaths.clear();
        thumbsLoading.clear();
        thumbsFailed.clear();

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(query)) {

            String like = "%" + keyword + "%";
            pst.setString(1, like);
            pst.setString(2, like);
            pst.setString(3, like);

            try (ResultSet rs = pst.executeQuery()) {
                picturesSupported = hasColumn(rs, "image_path");
                while (rs.next()) {
                    int stock = rs.getInt("stock");
                    String status = getStatus(stock);

                    if (statusFilter != null && !"All stock levels".equals(statusFilter)
                            && !"Status".equals(statusFilter) && !status.equals(statusFilter)) {
                        continue;
                    }

                    String id = rs.getString("product_id");
                    String path = picturesSupported ? rs.getString("image_path") : null;
                    if (path != null) imagePaths.put(id, path);
                    inventoryTable.addRow(new Object[]{
                            path == null ? "" : path,
                            id,
                            rs.getString("product_name"),
                            rs.getString("supplier"),
                            rs.getString("price"),
                            String.valueOf(stock),
                            status
                    });
                }
            }
            lblCount.setText(inventoryTable.getRowCount() + " products"
                    + (picturesSupported ? "" : "   (pictures: not set up on the server yet)"));
            updatePictureButtons();
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

    /** @return the new product's id, or null when nothing was added. */
    private Integer addProduct() {
        if (!validateInputs()) return null;

        // product_id is generated by the database (identity); RETURNING gives it back for the picture.
        String sql = "INSERT INTO products (product_name, supplier, price, stock) VALUES (?, ?, ?, ?) RETURNING product_id";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, txtProduct.getText().trim());
            pst.setString(2, txtSupplier.getText().trim());
            pst.setBigDecimal(3, new BigDecimal(txtPrice.getText().trim()));
            pst.setInt(4, Integer.parseInt(txtStock.getText().trim()));
            Integer id = null;
            try (ResultSet rs = pst.executeQuery()) {
                if (rs.next()) id = rs.getInt(1);
            }
            loadInventory();
            return id;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not add product: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    /** @return the updated product's id, or null when nothing was updated. */
    private Integer updateProduct() {
        if (selectedProductId == null) {
            JOptionPane.showMessageDialog(this, "Click a product in the table first.",
                    "No Selection", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        if (!validateInputs()) return null;
        final String updatedId = selectedProductId;

        String sql = "UPDATE products SET product_name = ?, supplier = ?, price = ?, stock = ? "
                + "WHERE product_id::text = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, txtProduct.getText().trim());
            pst.setString(2, txtSupplier.getText().trim());
            pst.setBigDecimal(3, new BigDecimal(txtPrice.getText().trim()));
            pst.setInt(4, Integer.parseInt(txtStock.getText().trim()));
            pst.setString(5, updatedId);
            pst.executeUpdate();
            loadInventory();
            return Integer.valueOf(updatedId);
        } catch (SQLException | NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Could not update product: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return null;
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

        final String deletedId = selectedProductId;
        final String deletedPicture = imagePaths.get(deletedId);
        String sql = "DELETE FROM products WHERE product_id::text = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql)) {
            pst.setString(1, deletedId);
            int n = pst.executeUpdate();
            if (n > 0 && deletedPicture != null) {
                // The product is gone; its picture is no longer used by anything: delete it in the background.
                new Thread(() -> ProductImageService.deleteQuietly(null, Integer.parseInt(deletedId), deletedPicture),
                        "delete-product-picture").start();
            }
            loadInventory();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not delete product: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
        return true;
    }
}