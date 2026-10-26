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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Inventory: products with puhunan (capital cost), patong (markup) and the selling price, stock,
 * restocking with its purchase cost, stock corrections with a reason, the full stock history, and
 * (Stage F) the product picture agents see in the mobile app.
 *
 * Selling price = puhunan + patong (a peso amount or a percent of the puhunan); the database keeps
 * it that way. Editing a product never changes its stock: stock only moves through Restock, Correct
 * stock, bookings and sales, and every move is in the Stock history tab.
 */
public class InventoryPanel extends JPanel {

    private static final int COL_PICTURE = 0, COL_ID = 1, COL_NAME = 2, COL_SUPPLIER = 3, COL_COST = 4,
            COL_MARKUP = 5, COL_PRICE = 6, COL_STOCK = 7, COL_STATUS = 8;
    private static final int THUMB = 36, PREVIEW = 96;
    private static final String[] MARKUP_TYPES = {"₱ amount", "% of puhunan"};

    private final DefaultTableModel inventoryTable;
    private final JTable table;

    private final JTextField txtProduct = new JTextField(16);
    private final JTextField txtSupplier = new JTextField(16);
    private final JTextField txtCost = new JTextField(8);
    private final JComboBox<String> cmbMarkupType = new JComboBox<>(MARKUP_TYPES);
    private final JTextField txtMarkup = new JTextField(6);
    private final JTextField txtPrice = new JTextField(8);
    private final JTextField txtMinMarkup = new JTextField(8);
    private final JTextField txtStock = new JTextField(8);
    private final JLabel lblStockCaption = UiKit.caption("Starting stock");
    private final JLabel lblPricing = new JLabel(" ");
    private final JLabel lblServerNote = new JLabel(" ");
    private final JTextField txtSearch = new JTextField(20);
    private final JComboBox<String> cmbFilter =
            new JComboBox<>(new String[]{"All stock levels", "High", "Moderate", "Low", "Critical", "Out"});
    private final JLabel lblCount = new JLabel(" ");

    // Product ID of the currently selected row (null = nothing selected)
    private String selectedProductId;
    private final Map<String, InventoryService.Product> products = new HashMap<>();
    private boolean pricingSupported;

    // Pictures: what the database has, and what the admin has chosen but not saved yet
    private final Map<String, String> imagePaths = new HashMap<>();
    private final Set<String> thumbsLoading = new HashSet<>();
    private final Set<String> thumbsFailed = new HashSet<>();
    private boolean picturesSupported = true;
    private byte[] pendingJpeg;
    private boolean pendingRemove;
    private boolean imageBusy;
    private boolean saving;
    private int previewSeq;
    private final JLabel preview = new JLabel("", SwingConstants.CENTER);
    private final JLabel imageStatus = new JLabel(" ");
    private final JProgressBar imageProgress = new JProgressBar();
    private final JButton btnChooseImage = UiKit.secondaryButton("Choose picture...");
    private final JButton btnRemoveImage = UiKit.secondaryButton("Remove picture");
    private JButton btnAdd, btnUpdate, btnDelete, btnClear, btnRestock, btnAdjust;

    private final StockHistoryPanel historyPanel = new StockHistoryPanel();
    private final JTabbedPane tabs = new JTabbedPane();

    public InventoryPanel() {
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(12, 20, 16, 20));

        JPanel productsTab = new JPanel(new BorderLayout(0, 12));
        productsTab.setOpaque(false);
        productsTab.setBorder(new EmptyBorder(10, 0, 0, 0));
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(buildProductCard());
        controls.add(Box.createVerticalStrut(10));
        controls.add(buildSearchCard());
        productsTab.add(controls, BorderLayout.NORTH);

        String[] columnNames = {"Picture", "ID", "Product", "Supplier", "Puhunan", "Patong", "Price", "Stock", "Status"};
        inventoryTable = new DefaultTableModel(null, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(inventoryTable);
        UiKit.styleTable(table, 44);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        int[] widths = {60, 50, 220, 160, 90, 80, 90, 70, 110};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(COL_PICTURE).setMaxWidth(70);
        table.getColumnModel().getColumn(COL_ID).setMaxWidth(70);
        table.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        UiKit.CellRenderer right = new UiKit.CellRenderer(SwingConstants.RIGHT, false, false);
        for (int c : new int[]{COL_COST, COL_MARKUP, COL_PRICE, COL_STOCK}) table.getColumnModel().getColumn(c).setCellRenderer(right);
        table.getColumnModel().getColumn(COL_PRICE).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, true, false));
        table.getColumnModel().getColumn(COL_ID).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, false, true));
        table.getColumnModel().getColumn(COL_PICTURE).setCellRenderer(new ThumbRenderer());
        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new UiKit.PillRenderer(InventoryPanel::statusColor));
        UiKit.alignHeaders(table, COL_COST, COL_MARKUP, COL_PRICE, COL_STOCK);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int viewRow = table.getSelectedRow();
            if (viewRow == -1) {
                selectedProductId = null;
                updateFormMode();
                return;
            }
            int row = table.convertRowIndexToModel(viewRow);
            selectedProductId = String.valueOf(inventoryTable.getValueAt(row, COL_ID));
            fillForm(products.get(selectedProductId));
            pendingJpeg = null;
            pendingRemove = false;
            showSavedPicture();
        });

        JPanel tableCard = UiKit.card(new BorderLayout(0, 8), 12, 14);
        JPanel tableHeading = new JPanel(new BorderLayout());
        tableHeading.setOpaque(false);
        tableHeading.add(UiKit.cardHeading("Products", null), BorderLayout.WEST);
        lblCount.setFont(Theme.SMALL);
        lblCount.setForeground(Theme.MUTED);
        tableHeading.add(lblCount, BorderLayout.EAST);
        tableCard.add(tableHeading, BorderLayout.NORTH);
        tableCard.add(UiKit.tableScroll(table), BorderLayout.CENTER);
        productsTab.add(tableCard, BorderLayout.CENTER);

        tabs.addTab("Products", productsTab);
        tabs.addTab("Stock history", historyPanel);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == historyPanel) historyPanel.reloadProducts(products.values());
        });
        add(tabs, BorderLayout.CENTER);

        loadInventory("", "All stock levels");
    }

    private static Color statusColor(String status) {
        switch (status) {
            case "High": return Theme.OK;
            case "Moderate": return Theme.BLUE;
            case "Low": return Theme.WARN;
            case "Critical":
            case "Out": return Theme.DANGER;
            default: return Theme.MUTED;
        }
    }

    // ---------- FORM ----------

    private JPanel buildProductCard() {
        for (JTextField f : new JTextField[]{txtProduct, txtSupplier, txtCost, txtMarkup, txtPrice, txtMinMarkup, txtStock}) {
            UiKit.styleField(f);
        }
        UiKit.placeholder(txtCost, "e.g. 45.00");
        UiKit.placeholder(txtMarkup, "e.g. 12");
        UiKit.placeholder(txtMinMarkup, "0.00");
        cmbMarkupType.setFont(Theme.SUBTLE);
        txtCost.setToolTipText("Puhunan: what one unit costs you (capital cost)");
        txtMarkup.setToolTipText("Patong: what you add on top of the puhunan, in pesos or as a percent of the puhunan");
        txtPrice.setToolTipText("Selling price = puhunan + patong. Typed in only for products without puhunan.");
        txtMinMarkup.setToolTipText("Agents can discount a booking down to puhunan + this amount per unit, never below");
        txtStock.setToolTipText("Stock on hand when you add the product. Later use Restock or Correct stock.");

        DocumentListener recalc = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updatePricing(); }
            public void removeUpdate(DocumentEvent e) { updatePricing(); }
            public void changedUpdate(DocumentEvent e) { updatePricing(); }
        };
        // Not on the price field: the price is what this writes (a listener may not change its own field).
        for (JTextField f : new JTextField[]{txtCost, txtMarkup, txtMinMarkup}) f.getDocument().addDocumentListener(recalc);
        cmbMarkupType.addActionListener(e -> updatePricing());
        // A product that only had a price gets its patong filled in when its puhunan is first typed.
        txtCost.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                suggestMarkupFromPrice();
            }
        });

        JPanel card = UiKit.card(new BorderLayout(14, 10), 14, 16);
        card.add(UiKit.cardHeading("Product details",
                "Select a product below to edit it. Selling price = puhunan + patong."), BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.NORTHWEST;
        g.insets = new Insets(0, 0, 10, 12);
        g.weightx = 1;

        g.gridy = 0;
        g.gridx = 0;
        g.gridwidth = 2;
        fields.add(UiKit.group("Product", txtProduct), g);
        g.gridx = 2;
        fields.add(UiKit.group("Supplier", txtSupplier), g);

        g.gridy = 1;
        g.gridwidth = 1;
        g.gridx = 0;
        fields.add(UiKit.group("Puhunan per unit (₱)", txtCost), g);
        JPanel markup = new JPanel(new BorderLayout(6, 0));
        markup.setOpaque(false);
        markup.add(cmbMarkupType, BorderLayout.WEST);
        markup.add(txtMarkup, BorderLayout.CENTER);
        g.gridx = 1;
        fields.add(UiKit.group("Patong (markup)", markup), g);
        g.gridx = 2;
        fields.add(UiKit.group("Selling price (₱)", txtPrice), g);
        g.gridx = 3;
        fields.add(UiKit.group("Minimum patong for discounts (₱)", txtMinMarkup), g);

        g.gridy = 2;
        g.gridx = 0;
        JPanel stockGroup = new JPanel(new BorderLayout(0, 5));
        stockGroup.setOpaque(false);
        stockGroup.add(lblStockCaption, BorderLayout.NORTH);
        stockGroup.add(txtStock, BorderLayout.CENTER);
        fields.add(stockGroup, g);
        g.gridx = 1;
        g.gridwidth = 3;
        lblPricing.setFont(Theme.SMALL);
        lblPricing.setForeground(Theme.MUTED);
        fields.add(lblPricing, g);

        g.gridy = 3;
        g.gridx = 0;
        g.gridwidth = 4;
        g.insets = new Insets(0, 0, 0, 12);
        lblServerNote.setFont(Theme.SMALL);
        lblServerNote.setForeground(Theme.WARN);
        fields.add(lblServerNote, g);

        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setOpaque(false);
        body.add(fields, BorderLayout.CENTER);
        body.add(buildPictureBox(), BorderLayout.EAST);
        card.add(body, BorderLayout.CENTER);

        btnClear = UiKit.secondaryButton("Clear fields");
        btnAdd = UiKit.primaryButton("Add product");
        btnUpdate = UiKit.secondaryButton("Update product");
        btnDelete = UiKit.dangerButton("Delete product");
        btnRestock = UiKit.secondaryButton("Restock...");
        btnAdjust = UiKit.secondaryButton("Correct stock...");
        btnRestock.setToolTipText("Record newly bought stock: quantity and cost per unit (a purchase, not a sale)");
        btnAdjust.setToolTipText("Fix the stock count with a reason (damaged, expired, count difference)");
        btnClear.addActionListener(e -> clearFields());
        btnAdd.addActionListener(e -> saveProduct(true));
        btnUpdate.addActionListener(e -> saveProduct(false));
        btnDelete.addActionListener(e -> {
            if (deleteProduct()) clearFields();
        });
        btnRestock.addActionListener(e -> openRestock());
        btnAdjust.addActionListener(e -> openAdjust());

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JPanel stockActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        stockActions.setOpaque(false);
        stockActions.add(btnRestock);
        stockActions.add(btnAdjust);
        JPanel productActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        productActions.setOpaque(false);
        productActions.add(btnClear);
        productActions.add(btnAdd);
        productActions.add(btnUpdate);
        productActions.add(btnDelete);
        actions.add(stockActions, BorderLayout.WEST);
        actions.add(productActions, BorderLayout.EAST);
        card.add(actions, BorderLayout.SOUTH);
        updateFormMode();
        return card;
    }

    private void fillForm(InventoryService.Product p) {
        if (p == null) return;
        txtProduct.setText(p.name);
        txtSupplier.setText(p.supplier == null ? "" : p.supplier);
        txtCost.setText(p.cost == null ? "" : p.cost.toPlainString());
        cmbMarkupType.setSelectedIndex("PERCENT".equals(p.markupType) ? 1 : 0);
        txtMarkup.setText(p.markupValue == null ? "" : p.markupValue.stripTrailingZeros().toPlainString());
        txtPrice.setText(p.price == null ? "" : p.price.toPlainString());
        txtMinMarkup.setText(p.minMarkup == null || p.minMarkup.signum() == 0 ? "" : p.minMarkup.toPlainString());
        txtStock.setText(String.valueOf(p.stock));
        updateFormMode();
    }

    private void suggestMarkupFromPrice() {
        if (!pricingSupported || !txtMarkup.getText().trim().isEmpty()) return;
        try {
            BigDecimal cost = Money.parse(txtCost.getText(), "puhunan", true);
            BigDecimal price = Money.parse(txtPrice.getText(), "selling price", true);
            if (cost != null && price != null && price.compareTo(cost) >= 0) {
                cmbMarkupType.setSelectedIndex(0);
                txtMarkup.setText(price.subtract(cost).toPlainString());
            }
        } catch (IllegalArgumentException ignored) {
            // the field shows its own problem when saving
        }
    }

    private String markupType() {
        return cmbMarkupType.getSelectedIndex() == 1 ? "PERCENT" : "AMOUNT";
    }

    /** Which fields can be typed in, and the button states, for "new product" / "selected" / older server. */
    private void updateFormMode() {
        boolean selected = selectedProductId != null;
        boolean hasCost = !txtCost.getText().trim().isEmpty();
        txtCost.setEnabled(pricingSupported);
        cmbMarkupType.setEnabled(pricingSupported && hasCost);
        txtMarkup.setEnabled(pricingSupported && hasCost);
        txtMinMarkup.setEnabled(pricingSupported);
        txtPrice.setEditable(!pricingSupported || !hasCost);
        if (pricingSupported) {
            lblStockCaption.setText(selected ? "In stock" : "Starting stock");
            txtStock.setEditable(!selected);
        } else {
            lblStockCaption.setText("Stock quantity");
            txtStock.setEditable(true);
        }
        if (!selected && !pricingSupported) txtStock.setEditable(true);
        readOnlyLook(txtStock);
        boolean idle = !saving && !imageBusy;
        btnAdd.setEnabled(idle);
        btnUpdate.setEnabled(idle && selected);
        btnDelete.setEnabled(idle && selected);
        btnClear.setEnabled(idle);
        btnRestock.setEnabled(idle && selected && pricingSupported);
        btnAdjust.setEnabled(idle && selected && pricingSupported);
        updatePricing();
    }

    /** The live line under the price: how the price is made and what agents may discount to. */
    private void updatePricing() {
        if (!pricingSupported) {
            lblPricing.setText(" ");
            return;
        }
        boolean hasCost = !txtCost.getText().trim().isEmpty();
        txtPrice.setEditable(!hasCost);
        readOnlyLook(txtPrice);
        cmbMarkupType.setEnabled(hasCost);
        txtMarkup.setEnabled(hasCost);
        try {
            BigDecimal cost = Money.parse(txtCost.getText(), "puhunan", true);
            if (cost == null) {
                lblPricing.setText(html("No puhunan: the price is typed in. Profit cannot be worked out and agents "
                        + "cannot give discounts on this product until a puhunan is set."));
                lblPricing.setForeground(Theme.MUTED);
                return;
            }
            BigDecimal mv = parseMarkupValue(true);
            if (mv == null) {
                lblPricing.setText(html("Enter the patong: the selling price is puhunan + patong."));
                lblPricing.setForeground(Theme.MUTED);
                return;
            }
            BigDecimal price = Money.sellingPrice(cost, markupType(), mv);
            if (!price.toPlainString().equals(txtPrice.getText())) txtPrice.setText(price.toPlainString());
            BigDecimal min = Money.parse(txtMinMarkup.getText(), "minimum patong", true);
            BigDecimal floor = cost.add(min == null ? BigDecimal.ZERO : min);
            BigDecimal margin = Money.marginPercent(cost, price);
            lblPricing.setText(html("Selling price " + Money.peso(price) + " = puhunan " + Money.peso(cost) + " + patong "
                    + Money.peso(price.subtract(cost)) + (margin == null ? "" : " (" + margin + "% of the price)")
                    + ". Agents may discount down to " + Money.peso(floor) + " per unit."));
            lblPricing.setForeground(Theme.TEXT);
        } catch (IllegalArgumentException ex) {
            lblPricing.setText(html(ex.getMessage()));
            lblPricing.setForeground(Theme.DANGER);
        }
    }

    /** A field that shows a value but cannot be typed in looks greyed, so nobody tries. */
    private static void readOnlyLook(JTextField f) {
        f.setBackground(f.isEditable() && f.isEnabled() ? Theme.CARD : Theme.SUBTLE_BG);
    }

    private BigDecimal parseMarkupValue(boolean optional) {
        String t = txtMarkup.getText().trim().replace("%", "");
        if (t.isEmpty()) {
            if (optional) return null;
            throw new IllegalArgumentException("Enter the patong (markup).");
        }
        BigDecimal v = Money.parse(t, "patong", false);
        if ("PERCENT".equals(markupType()) && v.compareTo(new BigDecimal(1000)) > 0) {
            throw new IllegalArgumentException("A percent patong can be at most 1000%.");
        }
        return v;
    }

    // ---------- PICTURE ----------

    private JPanel buildPictureBox() {
        preview.setPreferredSize(new Dimension(PREVIEW, PREVIEW));
        preview.setMinimumSize(new Dimension(PREVIEW, PREVIEW));
        preview.setOpaque(true);
        preview.setBackground(Theme.SUBTLE_BG);
        preview.setForeground(Theme.MUTED);
        preview.setFont(Theme.SMALL);
        preview.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        imageStatus.setFont(Theme.SMALL);
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
        right.add(UiKit.caption("Picture (mobile app)"), BorderLayout.NORTH);
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
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));   // keeps the box at its own width
        wrap.setOpaque(false);
        wrap.add(box);
        showSavedPicture();
        return wrap;
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
                if (view >= 0) {
                    table.getSelectionModel().setSelectionInterval(view, view);
                    table.scrollRectToVisible(table.getCellRect(view, 0, true));
                }
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

    static Image fit(BufferedImage img, int max) {
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
        updateFormMode();
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
        return "<html>" + safe + "</html>";
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
                setText("—");
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
        UiKit.searchField(txtSearch, "Search by ID, product or supplier");
        txtSearch.addActionListener(e -> searchProducts());
        cmbFilter.setFont(Theme.SUBTLE);
        cmbFilter.addActionListener(e -> searchProducts());

        JButton btnSearch = UiKit.primaryButton("Search");
        btnSearch.addActionListener(e -> searchProducts());

        JButton btnRefresh = UiKit.secondaryButton("Refresh");
        btnRefresh.setToolTipText("Reload inventory from the shared database while keeping the current search and stock filter.");
        btnRefresh.addActionListener(e -> loadInventory());

        JPanel card = UiKit.card(new BorderLayout(12, 0), 10, 14);
        JPanel inputs = new JPanel(new GridBagLayout());
        inputs.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 10);
        c.weightx = 1;
        inputs.add(UiKit.group("Search products", txtSearch), c);
        c.gridx = 1;
        c.weightx = 0;
        inputs.add(UiKit.group("Stock status", cmbFilter), c);
        card.add(inputs, BorderLayout.CENTER);
        JPanel button = new JPanel(new GridLayout(2, 1, 0, 8));
        button.setOpaque(false);
        button.add(btnSearch);
        button.add(btnRefresh);
        card.add(button, BorderLayout.EAST);
        return card;
    }

    private static String getStatus(int stock) {
        if (stock > 99) return "High";
        else if (stock > 59) return "Moderate";
        else if (stock > 29) return "Low";
        else if (stock == 0) return "Out";
        else return "Critical";
    }

    /** Blanks the form and deselects the table row. */
    private void clearFields() {
        for (JTextField f : new JTextField[]{txtProduct, txtSupplier, txtCost, txtMarkup, txtPrice, txtMinMarkup, txtStock}) {
            f.setText("");
        }
        cmbMarkupType.setSelectedIndex(0);
        selectedProductId = null;
        table.clearSelection();
        pendingJpeg = null;
        pendingRemove = false;
        showSavedPicture();
        updateFormMode();
    }

    // ---------- DATA ----------

    /** Reload everything (keeps the older loadInventory() calls working). */
    public void loadInventory() {
        loadInventory(txtSearch.getText().trim(), (String) cmbFilter.getSelectedItem());
    }

    private void searchProducts() {
        loadInventory(txtSearch.getText().trim(), (String) cmbFilter.getSelectedItem());
    }

    private void loadInventory(String keyword, String statusFilter) {
        String keep = selectedProductId;
        inventoryTable.setRowCount(0);
        imagePaths.clear();
        products.clear();
        thumbsLoading.clear();
        thumbsFailed.clear();
        try {
            InventoryService.Catalog catalog = InventoryService.list(keyword);
            picturesSupported = catalog.pictures;
            pricingSupported = catalog.pricing && Db.salesReady();
            for (InventoryService.Product p : catalog.products) {
                String status = getStatus(p.stock);
                if (statusFilter != null && !"All stock levels".equals(statusFilter) && !status.equals(statusFilter)) {
                    continue;
                }
                String id = String.valueOf(p.id);
                products.put(id, p);
                if (p.imagePath != null) imagePaths.put(id, p.imagePath);
                inventoryTable.addRow(new Object[]{
                        p.imagePath == null ? "" : p.imagePath,
                        id,
                        p.name,
                        p.supplier == null ? "" : p.supplier,
                        p.cost == null ? "—" : Money.plain(p.cost),
                        p.markupText(),
                        Money.plain(p.price),
                        String.valueOf(p.stock),
                        status
                });
            }
            long noCost = catalog.products.stream().filter(p -> p.cost == null).count();
            lblCount.setText(inventoryTable.getRowCount() + " products"
                    + (pricingSupported && noCost > 0 ? "   ·   " + noCost + " without puhunan" : "")
                    + (picturesSupported ? "" : "   (pictures: not set up on the server yet)"));
            lblServerNote.setText(pricingSupported ? " "
                    : html("Puhunan, patong, restocking and the stock history need the " + Db.UPDATE_27
                    + " on the server. Until then products are saved as before."));
            updatePictureButtons();
        } catch (SQLException e) {
            lblCount.setText("Unable to load products");
            JOptionPane.showMessageDialog(this, Db.message(e, "Could not load the products"),
                    "Inventory", JOptionPane.ERROR_MESSAGE);
        }
        if (keep != null && products.containsKey(keep)) {
            selectRow(keep);
        } else {
            selectedProductId = null;
        }
        updateFormMode();
        historyPanel.reloadProducts(products.values());
    }

    // ---------- ADD / UPDATE / DELETE ----------

    private void saveProduct(boolean add) {
        if (!add && selectedProductId == null) {
            JOptionPane.showMessageDialog(this, "Click a product in the table first.", "No selection",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        String name = txtProduct.getText().trim();
        String supplier = txtSupplier.getText().trim();
        if (name.isEmpty() || supplier.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Product and Supplier are required.", "Missing data",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!pricingSupported) {
            Integer id = add ? legacyAdd(name, supplier) : legacyUpdate(name, supplier);
            if (id != null) savePicture(id, add);
            return;
        }
        final BigDecimal cost, markupValue, minMarkup, price;
        final Integer startingStock;
        try {
            cost = Money.parse(txtCost.getText(), "puhunan", true);
            markupValue = cost == null ? null : parseMarkupValue(false);
            price = cost == null ? Money.parse(txtPrice.getText(), "selling price", false) : null;
            minMarkup = Money.parse(txtMinMarkup.getText(), "minimum patong", true);
            startingStock = add ? Money.parseInt(txtStock.getText().isBlank() ? "0" : txtStock.getText(),
                    "starting stock", 0, 1_000_000) : null;
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Check the product", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (cost == null && minMarkup != null && minMarkup.signum() > 0) {
            JOptionPane.showMessageDialog(this, "Enter the puhunan first: the minimum patong is added to it.",
                    "Check the product", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final Integer id = add ? null : Integer.valueOf(selectedProductId);
        final String type = markupType();
        saving = true;
        updateFormMode();
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return InventoryService.save(id, name, supplier, cost, type, markupValue, minMarkup, price, startingStock);
            }

            @Override
            protected void done() {
                saving = false;
                try {
                    int savedId = get();
                    selectedProductId = String.valueOf(savedId);
                    loadInventory();
                    savePicture(savedId, add);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    updateFormMode();
                    JOptionPane.showMessageDialog(InventoryPanel.this, c.getMessage(),
                            add ? "Product not added" : "Product not updated", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    private void openRestock() {
        InventoryService.Product p = selectedProductId == null ? null : products.get(selectedProductId);
        if (p == null) return;
        if (StockDialogs.restock(SwingUtilities.getWindowAncestor(this), p)) {
            loadInventory();
            historyPanel.reloadIfShown();
        }
    }

    private void openAdjust() {
        InventoryService.Product p = selectedProductId == null ? null : products.get(selectedProductId);
        if (p == null) return;
        if (StockDialogs.adjust(SwingUtilities.getWindowAncestor(this), p)) {
            loadInventory();
            historyPanel.reloadIfShown();
        }
    }

    // Older servers (before 20261027_01): the previous direct statements, price and stock typed in.

    private Integer legacyAdd(String name, String supplier) {
        try {
            BigDecimal price = Money.parse(txtPrice.getText(), "price", false);
            int stock = Money.parseInt(txtStock.getText(), "stock quantity", 0, 1_000_000);
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement pst = conn.prepareStatement(
                         "INSERT INTO products (product_name, supplier, price, stock) VALUES (?, ?, ?, ?) RETURNING product_id")) {
                pst.setString(1, name);
                pst.setString(2, supplier);
                pst.setBigDecimal(3, price);
                pst.setInt(4, stock);
                try (ResultSet rs = pst.executeQuery()) {
                    rs.next();
                    int id = rs.getInt(1);
                    loadInventory();
                    return id;
                }
            }
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Check the product", JOptionPane.WARNING_MESSAGE);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, Db.message(e, "Could not add the product"), "Error", JOptionPane.ERROR_MESSAGE);
        }
        return null;
    }

    private Integer legacyUpdate(String name, String supplier) {
        final String updatedId = selectedProductId;
        try {
            BigDecimal price = Money.parse(txtPrice.getText(), "price", false);
            int stock = Money.parseInt(txtStock.getText(), "stock quantity", 0, 1_000_000);
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement pst = conn.prepareStatement(
                         "UPDATE products SET product_name = ?, supplier = ?, price = ?, stock = ? WHERE product_id::text = ?")) {
                pst.setString(1, name);
                pst.setString(2, supplier);
                pst.setBigDecimal(3, price);
                pst.setInt(4, stock);
                pst.setString(5, updatedId);
                pst.executeUpdate();
                loadInventory();
                return Integer.valueOf(updatedId);
            }
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Check the product", JOptionPane.WARNING_MESSAGE);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, Db.message(e, "Could not update the product"), "Error", JOptionPane.ERROR_MESSAGE);
        }
        return null;
    }

    /** @return false if the user cancelled the confirmation, true otherwise. */
    private boolean deleteProduct() {
        if (selectedProductId == null) {
            JOptionPane.showMessageDialog(this, "Click a product in the table first.",
                    "No selection", JOptionPane.WARNING_MESSAGE);
            return true;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete this product? Products already used in bookings or sales cannot be deleted.\n"
                        + "Its stock history is kept.", "Confirm delete", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return false;

        final String deletedId = selectedProductId;
        final String deletedPicture = imagePaths.get(deletedId);
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement("DELETE FROM products WHERE product_id::text = ?")) {
            pst.setString(1, deletedId);
            int n = pst.executeUpdate();
            if (n > 0 && deletedPicture != null) {
                // The product is gone; its picture is no longer used by anything: delete it in the background.
                new Thread(() -> ProductImageService.deleteQuietly(null, Integer.parseInt(deletedId), deletedPicture),
                        "delete-product-picture").start();
            }
            selectedProductId = null;
            loadInventory();
        } catch (SQLException e) {
            String msg = "23503".equals(e.getSQLState())
                    ? "This product is used in bookings or sales, so it cannot be deleted. Its history must stay correct."
                    : Db.message(e, "Could not delete the product");
            JOptionPane.showMessageDialog(this, msg, "Not deleted", JOptionPane.WARNING_MESSAGE);
        }
        return true;
    }
}
