package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;

/**
 * Walk-In / In-House sales (Admin, at the store or the office).
 *  - Walk-In: a customer buys at the store, pays and takes the goods; no customer record is needed.
 *  - In-House: a sale for a customer from the Customers list (may be paid later).
 * New sale: products on the left, the sale on the right (several products, quantity, a peso discount per
 * line), payment, then Complete sale: the database saves the sale, its lines and the stock change in one
 * transaction (or nothing), gives the receipt number, and refuses negative stock and double saves.
 * Sales history: search and date filters, each sale with its receipt, Mark as paid, and Void (the stock
 * comes back and the sale stays on record with who, when and why).
 */
public class SalesPanel extends JPanel {

    private final JTabbedPane tabs = new JTabbedPane();
    private final StatePanel notReady = new StatePanel();
    private final CardLayout pageCards = new CardLayout();
    private final JPanel page = new JPanel(pageCards);

    // ---- new sale
    private final JTextField productSearch = new JTextField(18);
    private final DefaultTableModel productModel = new DefaultTableModel(new String[]{"Product", "Price", "Half case", "Stock"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable productTable = new JTable(productModel);
    private final List<SalesService.ProductOption> allProducts = new ArrayList<>();
    private final List<SalesService.ProductOption> shownProducts = new ArrayList<>();
    private final JLabel productState = new JLabel(" ");
    private final JButton addHalf = UiKit.secondaryButton("Add half case");

    private final JRadioButton walkIn = new JRadioButton("Walk-In", true);
    private final JRadioButton inHouse = new JRadioButton("In-House");
    private final JTextField walkInName = new JTextField(18);
    private final JComboBox<SalesService.Customer> customer = new JComboBox<>();
    private final CardLayout customerCards = new CardLayout();
    private final JPanel customerBox = new JPanel(customerCards);

    private final SaleCart cart = new SaleCart();
    private final CartModel cartModel = new CartModel();
    private final JTable cartTable = new JTable(cartModel);
    private final JLabel lblSubtotal = new JLabel(), lblDiscount = new JLabel(), lblTotal = new JLabel();
    private final JLabel warning = new JLabel(" ");

    private final JRadioButton paidNow = new JRadioButton("Paid now", true);
    private final JRadioButton payLater = new JRadioButton("Unpaid (pay later)");
    private final JComboBox<String> method = new JComboBox<>();
    private final JTextField tendered = new JTextField(9);
    private final JLabel change = new JLabel(" ");
    private final JTextField note = new JTextField(18);
    private final JButton complete = UiKit.primaryButton("Complete sale");
    private UUID requestId = Db.requestId();
    private boolean saving;

    // ---- history
    private final JTextField search = new JTextField(14);
    private final JComboBox<String> fType = new JComboBox<>(new String[]{"All types", "Walk-In", "In-House"});
    private final JComboBox<String> fStatus = new JComboBox<>(new String[]{"All", "Completed", "Voided"});
    private final JComboBox<String> fPayment = new JComboBox<>(new String[]{"All", "Paid", "Unpaid"});
    private final JTextField from = new JTextField(9), to = new JTextField(9);
    private final DefaultTableModel historyModel = new DefaultTableModel(
            new String[]{"Receipt", "Date", "Type", "Customer", "Items", "Total", "Payment", "Status"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable historyTable = new JTable(historyModel);
    private final List<SalesService.Sale> historyRows = new ArrayList<>();
    private final JLabel historySummary = new JLabel(" ");
    private final StatePanel historyState = new StatePanel();
    private final CardLayout historyCards = new CardLayout();
    private final JPanel historyCenter = new JPanel(historyCards);
    private int historySeq;

    public SalesPanel() {
        super(new BorderLayout());
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 20, 16, 20));
        tabs.addTab("New sale", buildNewSale());
        tabs.addTab("Sales history", buildHistory());
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 1) loadHistory();
        });
        page.setOpaque(false);
        page.add(tabs, "tabs");
        page.add(notReady, "notReady");
        add(page, BorderLayout.CENTER);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) reload();
        });
    }

    /** Called when the page is opened: checks the server, then loads products and customers. */
    void reload() {
        if (!Db.salesReady()) {
            notReady.showError("Sales are not set up on the server yet", Db.notReadyMessage("Walk-In / In-House sales"), this::reload);
            pageCards.show(page, "notReady");
            return;
        }
        pageCards.show(page, "tabs");
        loadChoices();
        if (tabs.getSelectedIndex() == 1) loadHistory();
    }

    // =====================================================================================
    // New sale
    // =====================================================================================

    private JComponent buildNewSale() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(10, 0, 0, 0));
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        g.gridy = 0;
        g.gridx = 0;
        g.weightx = 0.40;
        g.insets = new Insets(0, 0, 0, 12);
        root.add(buildProductCard(), g);
        g.gridx = 1;
        g.weightx = 0.60;
        g.insets = new Insets(0, 0, 0, 0);
        root.add(buildSaleCard(), g);
        return root;
    }

    private JComponent buildProductCard() {
        JPanel card = UiKit.card(new BorderLayout(0, 8), 12, 14);
        JPanel head = new JPanel(new BorderLayout(0, 8));
        head.setOpaque(false);
        head.add(UiKit.cardHeading("Products", "Double-click a product (or select it and press Add) to put it in the sale"),
                BorderLayout.NORTH);
        UiKit.searchField(productSearch, "Search products");
        productSearch.getDocument().addDocumentListener(StockDialogs.listener(this::filterProducts));
        productSearch.addActionListener(e -> addSelectedProduct(false));
        head.add(productSearch, BorderLayout.SOUTH);
        card.add(head, BorderLayout.NORTH);

        UiKit.styleTable(productTable, 34);
        productTable.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        productTable.getColumnModel().getColumn(1).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, false, false));
        productTable.getColumnModel().getColumn(2).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, false, false));
        productTable.getColumnModel().getColumn(3).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, false, true));
        productTable.getColumnModel().getColumn(0).setPreferredWidth(230);
        productTable.getColumnModel().getColumn(2).setPreferredWidth(95);
        productTable.getTableHeader().setToolTipText("Half case: the price of half a case (blank = whole cases only)");
        UiKit.alignHeaders(productTable, 1, 2, 3);
        productTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) addSelectedProduct(false);
            }
        });
        productTable.getSelectionModel().addListSelectionListener(e -> updateHalfButton());
        card.add(UiKit.tableScroll(productTable), BorderLayout.CENTER);

        JButton add = UiKit.primaryButton("Add to sale");
        add.addActionListener(e -> addSelectedProduct(false));
        addHalf.setToolTipText("Add half a case at the product's half case price (0.5 comes off the stock)");
        addHalf.addActionListener(e -> addSelectedProduct(true));
        addHalf.setEnabled(false);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setOpaque(false);
        productState.setFont(Theme.SMALL);
        productState.setForeground(Theme.MUTED);
        south.add(productState, BorderLayout.CENTER);
        JPanel addButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        addButtons.setOpaque(false);
        addButtons.add(addHalf);
        addButtons.add(add);
        south.add(addButtons, BorderLayout.EAST);
        card.add(south, BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildSaleCard() {
        JPanel card = UiKit.card(new BorderLayout(0, 10), 12, 16);

        // type and customer
        ButtonGroup types = new ButtonGroup();
        types.add(walkIn);
        types.add(inHouse);
        for (JRadioButton b : new JRadioButton[]{walkIn, inHouse, paidNow, payLater}) {
            b.setOpaque(false);
            b.setFont(Theme.font(Font.BOLD, 13));
        }
        walkIn.setToolTipText("A customer buys at the store, pays and takes the goods");
        inHouse.setToolTipText("A sale for a customer from the Customers list");
        walkIn.addActionListener(e -> customerCards.show(customerBox, "walkin"));
        inHouse.addActionListener(e -> customerCards.show(customerBox, "inhouse"));
        UiKit.styleField(walkInName);
        UiKit.placeholder(walkInName, "Customer name (optional)");
        customer.setFont(Theme.SUBTLE);
        customerBox.setOpaque(false);
        customerBox.add(walkInName, "walkin");
        customerBox.add(customer, "inhouse");

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(UiKit.cardHeading("Sale", "Walk-In: bought at the store.  In-House: for a saved customer."), BorderLayout.NORTH);
        JPanel typeRow = new JPanel(new BorderLayout(12, 0));
        typeRow.setOpaque(false);
        JPanel radios = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        radios.setOpaque(false);
        radios.add(walkIn);
        radios.add(Box.createHorizontalStrut(14));
        radios.add(inHouse);
        typeRow.add(radios, BorderLayout.WEST);
        typeRow.add(customerBox, BorderLayout.CENTER);
        top.add(typeRow, BorderLayout.SOUTH);
        card.add(top, BorderLayout.NORTH);

        // cart
        UiKit.styleTable(cartTable, 34);
        cartTable.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.RIGHT, false, false));
        cartTable.getColumnModel().getColumn(0).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        cartTable.getColumnModel().getColumn(5).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, true, false));
        int[] cw = {250, 75, 45, 85, 80, 95};
        for (int i = 0; i < cw.length; i++) cartTable.getColumnModel().getColumn(i).setPreferredWidth(cw[i]);
        cartTable.getColumnModel().getColumn(4).setMinWidth(80);
        UiKit.alignHeaders(cartTable, 1, 2, 3, 4, 5);
        cartTable.setSurrendersFocusOnKeystroke(true);
        cartTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JButton minus = UiKit.secondaryButton("− 1");
        JButton plus = UiKit.secondaryButton("+ 1");
        JButton remove = UiKit.secondaryButton("Remove");
        JButton clear = UiKit.secondaryButton("Clear sale");
        minus.setToolTipText("One less of the selected line");
        plus.setToolTipText("One more of the selected line");
        minus.addActionListener(e -> changeQty(-1));
        plus.addActionListener(e -> changeQty(1));
        remove.addActionListener(e -> {
            int r = cartTable.getSelectedRow();
            if (r >= 0) {
                cart.remove(r);
                cartChanged();
            }
        });
        clear.addActionListener(e -> {
            if (!cart.isEmpty() && JOptionPane.showConfirmDialog(this, "Remove every product from this sale?",
                    "Clear sale", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                cart.clear();
                cartChanged();
            }
        });
        JPanel cartButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cartButtons.setOpaque(false);
        cartButtons.add(minus);
        cartButtons.add(plus);
        cartButtons.add(remove);
        cartButtons.add(clear);
        JLabel editHint = new JLabel("Qty and Discount (₱) can be typed in the table.");
        editHint.setFont(Theme.SMALL);
        editHint.setForeground(Theme.MUTED);
        JPanel cartSouth = new JPanel(new BorderLayout());
        cartSouth.setOpaque(false);
        cartSouth.add(cartButtons, BorderLayout.WEST);
        cartSouth.add(editHint, BorderLayout.EAST);
        JPanel cartBox = new JPanel(new BorderLayout(0, 6));
        cartBox.setOpaque(false);
        JScrollPane cartScroll = UiKit.tableScroll(cartTable);
        cartScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.BORDER));
        cartBox.add(cartScroll, BorderLayout.CENTER);
        cartBox.add(cartSouth, BorderLayout.SOUTH);
        card.add(cartBox, BorderLayout.CENTER);

        // totals + payment
        ButtonGroup pay = new ButtonGroup();
        pay.add(paidNow);
        pay.add(payLater);
        for (String[] m : SalesService.METHODS) method.addItem(m[1]);
        method.setFont(Theme.SUBTLE);
        UiKit.styleField(tendered);
        UiKit.styleField(note);
        UiKit.placeholder(tendered, "Cash received");
        UiKit.placeholder(note, "Note (optional)");
        tendered.getDocument().addDocumentListener(StockDialogs.listener(this::updateChange));
        paidNow.addActionListener(e -> updatePaymentFields());
        payLater.addActionListener(e -> updatePaymentFields());
        method.addActionListener(e -> updatePaymentFields());

        JPanel totals = new JPanel(new GridBagLayout());
        totals.setOpaque(false);
        addTotalRow(totals, 0, "Subtotal", lblSubtotal, false);
        addTotalRow(totals, 1, "Discounts", lblDiscount, false);
        addTotalRow(totals, 2, "TOTAL", lblTotal, true);

        JPanel payment = new JPanel(new GridBagLayout());
        payment.setOpaque(false);
        GridBagConstraints p = new GridBagConstraints();
        p.anchor = GridBagConstraints.WEST;
        p.insets = new Insets(0, 0, 6, 8);
        p.gridx = 0;
        p.gridy = 0;
        p.gridwidth = 3;
        JPanel payRadios = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        payRadios.setOpaque(false);
        payRadios.add(paidNow);
        payRadios.add(Box.createHorizontalStrut(12));
        payRadios.add(payLater);
        payment.add(payRadios, p);
        p.gridy = 1;
        p.gridwidth = 1;
        p.fill = GridBagConstraints.HORIZONTAL;
        payment.add(UiKit.group("Paid by", method), p);
        p.gridx = 1;
        payment.add(UiKit.group("Amount received (₱)", tendered), p);
        p.gridx = 2;
        change.setFont(Theme.font(Font.BOLD, 13));
        payment.add(UiKit.group("Change", change), p);
        p.gridx = 0;
        p.gridy = 2;
        p.gridwidth = 3;
        payment.add(note, p);
        p.gridx = 3;
        p.gridy = 0;
        p.gridwidth = 1;
        p.weightx = 1;
        payment.add(Box.createHorizontalGlue(), p);   // keeps the payment fields on the left

        warning.setFont(Theme.SMALL);
        warning.setForeground(Theme.WARN);
        complete.setFont(Theme.font(Font.BOLD, 15));
        complete.addActionListener(e -> completeSale());

        JPanel bottom = new JPanel(new GridBagLayout());
        bottom.setOpaque(false);
        GridBagConstraints b = new GridBagConstraints();
        b.gridx = 0;
        b.gridy = 0;
        b.anchor = GridBagConstraints.NORTHWEST;
        b.weightx = 1;
        b.fill = GridBagConstraints.HORIZONTAL;
        bottom.add(payment, b);
        b.gridx = 1;
        b.weightx = 0;
        b.insets = new Insets(0, 16, 0, 0);
        bottom.add(totals, b);
        b.gridx = 0;
        b.gridy = 1;
        b.gridwidth = 2;
        b.insets = new Insets(4, 0, 6, 0);
        bottom.add(warning, b);
        b.gridy = 2;
        b.fill = GridBagConstraints.NONE;
        b.anchor = GridBagConstraints.EAST;
        bottom.add(complete, b);
        card.add(bottom, BorderLayout.SOUTH);

        cartChanged();
        updatePaymentFields();
        return card;
    }

    private static void addTotalRow(JPanel p, int row, String label, JLabel value, boolean big) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = row;
        g.gridx = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, 4, 18);
        JLabel l = new JLabel(label);
        l.setFont(big ? Theme.font(Font.BOLD, 16) : Theme.SUBTLE);
        l.setForeground(big ? Theme.TEXT : Theme.MUTED);
        p.add(l, g);
        g.gridx = 1;
        g.anchor = GridBagConstraints.EAST;
        g.insets = new Insets(0, 0, 4, 0);
        value.setFont(big ? Theme.font(Font.BOLD, 20) : Theme.font(Font.PLAIN, 14));
        value.setForeground(big ? Theme.BLUE_DARK : Theme.TEXT);
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        p.add(value, g);
    }

    private void loadChoices() {
        productState.setText("Loading products...");
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                return new Object[]{SalesService.products(), SalesService.customers()};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done() {
                try {
                    Object[] r = get();
                    allProducts.clear();
                    allProducts.addAll((List<SalesService.ProductOption>) r[0]);
                    filterProducts();
                    Object keep = customer.getSelectedItem();
                    customer.removeAllItems();
                    for (SalesService.Customer c : (List<SalesService.Customer>) r[1]) {
                        customer.addItem(c);
                        if (keep instanceof SalesService.Customer && ((SalesService.Customer) keep).id == c.id) customer.setSelectedItem(c);
                    }
                    if (customer.getItemCount() == 0) inHouse.setToolTipText("No customers yet: add them on the Customers page");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    productState.setText("<html>" + StockDialogs.esc(c.getMessage()) + "</html>");
                    productState.setForeground(Theme.DANGER);
                }
            }
        }.execute();
    }

    private void filterProducts() {
        String q = productSearch.getText().trim().toLowerCase();
        shownProducts.clear();
        productModel.setRowCount(0);
        for (SalesService.ProductOption p : allProducts) {
            String hay = (p.name + " " + (p.supplier == null ? "" : p.supplier) + " " + p.id).toLowerCase();
            if (!q.isEmpty() && !hay.contains(q)) continue;
            shownProducts.add(p);
            productModel.addRow(new Object[]{p.name, Money.plain(p.price), p.halfPrice == null ? "" : Money.plain(p.halfPrice),
                    p.stock.signum() <= 0 ? "Out" : Money.qty(p.stock)});
        }
        updateHalfButton();
        productState.setForeground(Theme.MUTED);
        productState.setText(shownProducts.size() + " of " + allProducts.size() + " products");
        if (shownProducts.size() == 1) productTable.setRowSelectionInterval(0, 0);
    }

    /** The product picked in the list (or the only one shown), or null. */
    private SalesService.ProductOption selectedProduct() {
        int r = productTable.getSelectedRow();
        if (r < 0) {
            if (shownProducts.size() == 1) r = 0;
            else return null;
        }
        return shownProducts.get(productTable.convertRowIndexToModel(r));
    }

    private void updateHalfButton() {
        SalesService.ProductOption p = selectedProduct();
        addHalf.setEnabled(p != null && p.halfPrice != null);
        addHalf.setToolTipText(p != null && p.halfPrice == null
                ? p.name + " is sold by the whole case only (set a half case price in Inventory)"
                : "Add half a case at the product's half case price (0.5 comes off the stock)");
    }

    /** half: add a half case (needs the product's half case price). */
    private void addSelectedProduct(boolean half) {
        SalesService.ProductOption p = selectedProduct();
        if (p == null) return;
        if (half && p.halfPrice == null) {
            JOptionPane.showMessageDialog(this, p.name + " is sold by the whole case only. Set its half case price in "
                    + "Inventory to sell half cases.", "Half case", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String problem = cart.addProblem(p.id, p.name, p.stock, half);
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, p.stock.signum() <= 0 ? "Out of stock" : "Stock",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        cart.add(p.id, p.name, half ? p.halfPrice : p.price, p.cost, p.stock, half);
        cartChanged();
        for (int i = 0; i < cart.lines().size(); i++) {
            SaleCart.Line l = cart.lines().get(i);
            if (l.productId == p.id && l.half == half) cartTable.setRowSelectionInterval(i, i);
        }
    }

    private void changeQty(int delta) {
        int r = cartTable.getSelectedRow();
        if (r < 0) return;
        SaleCart.Line l = cart.lines().get(r);
        int q = l.quantity + delta;
        String problem = delta > 0 ? cart.quantityProblem(r, q) : null;
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "Stock", JOptionPane.WARNING_MESSAGE);
            return;
        }
        cart.setQuantity(r, q);
        cartChanged();
        if (q > 0) cartTable.setRowSelectionInterval(r, r);
    }

    private void cartChanged() {
        cartModel.fireTableDataChanged();
        lblSubtotal.setText(Money.peso(cart.subtotal()));
        lblDiscount.setText(cart.discount().signum() == 0 ? Money.peso(BigDecimal.ZERO) : "− " + Money.peso(cart.discount()));
        lblTotal.setText(Money.peso(cart.total()));
        List<String> below = cart.belowCost();
        warning.setText(below.isEmpty() ? " " : "<html>Sold below puhunan: " + StockDialogs.esc(String.join(", ", below))
                + ". Check the discount.</html>");
        complete.setEnabled(!saving && !cart.isEmpty());
        updateChange();
    }

    private void updatePaymentFields() {
        boolean paid = paidNow.isSelected();
        method.setEnabled(paid);
        boolean cash = paid && method.getSelectedIndex() == 0;
        tendered.setEnabled(cash);
        if (!cash) tendered.setText("");
        updateChange();
    }

    private void updateChange() {
        try {
            BigDecimal t = Money.parse(tendered.getText(), "amount received", true);
            if (t == null || !tendered.isEnabled()) {
                change.setText("—");
                change.setForeground(Theme.MUTED);
                return;
            }
            BigDecimal c = t.subtract(cart.total());
            change.setText(c.signum() < 0 ? "Short " + Money.peso(c.negate()) : Money.peso(c));
            change.setForeground(c.signum() < 0 ? Theme.DANGER : Theme.OK);
        } catch (IllegalArgumentException ex) {
            change.setText("Check the amount");
            change.setForeground(Theme.DANGER);
        }
    }

    private void completeSale() {
        if (saving) return;
        if (cartTable.isEditing()) cartTable.getCellEditor().stopCellEditing();
        String problem = cart.problem();
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "Check the sale", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final boolean house = inHouse.isSelected();
        final SalesService.Customer c = house ? (SalesService.Customer) customer.getSelectedItem() : null;
        if (house && c == null) {
            JOptionPane.showMessageDialog(this, "Choose the customer for an In-House sale (add customers on the Customers page).",
                    "Customer", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final boolean paid = paidNow.isSelected();
        final String m = paid ? SalesService.METHODS[Math.max(0, method.getSelectedIndex())][0] : null;
        final BigDecimal t;
        try {
            t = tendered.isEnabled() ? Money.parse(tendered.getText(), "amount received", true) : null;
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Payment", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (t != null && t.compareTo(cart.total()) < 0) {
            JOptionPane.showMessageDialog(this, "The amount received is less than the total (" + Money.peso(cart.total()) + ").",
                    "Payment", JOptionPane.WARNING_MESSAGE);
            return;
        }
        StringBuilder confirm = new StringBuilder();
        confirm.append(house ? "In-House sale for " + c.name : "Walk-In sale"
                + (walkInName.getText().isBlank() ? "" : " for " + walkInName.getText().trim())).append("\n");
        confirm.append(cart.lines().size()).append(" product line(s), total ").append(Money.peso(cart.total())).append("\n");
        confirm.append(paid ? "Paid by " + SalesService.methodLabel(m) : "UNPAID (to be paid later)").append("\n");
        List<String> below = cart.belowCost();
        if (!below.isEmpty()) confirm.append("\nWarning: sold below puhunan: ").append(String.join(", ", below)).append("\n");
        confirm.append("\nThe stock is taken out when you confirm.");
        if (JOptionPane.showConfirmDialog(this, confirm.toString(), "Complete sale", JOptionPane.OK_CANCEL_OPTION,
                below.isEmpty() ? JOptionPane.QUESTION_MESSAGE : JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        saving = true;
        complete.setEnabled(false);
        complete.setText("Saving...");
        final UUID id = requestId;
        final String name = walkInName.getText().trim();
        final String n = note.getText().trim();
        new SwingWorker<SalesService.Sale, Void>() {
            @Override
            protected SalesService.Sale doInBackground() throws Exception {
                SalesService.Created created = SalesService.create(id, house ? SalesService.IN_HOUSE : SalesService.WALK_IN,
                        house ? c.id : null, house || name.isEmpty() ? null : name, cart, paid, m, t, n.isEmpty() ? null : n);
                return SalesService.detail(created.saleId);
            }

            @Override
            protected void done() {
                saving = false;
                complete.setText("Complete sale");
                try {
                    SalesService.Sale s = get();
                    requestId = Db.requestId();     // the next sale is a new one
                    cart.clear();
                    walkInName.setText("");
                    note.setText("");
                    tendered.setText("");
                    cartChanged();
                    loadChoices();
                    saleSaved(s);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    complete.setEnabled(true);
                    // The same request id is kept: pressing Complete again cannot save it twice.
                    JOptionPane.showMessageDialog(SalesPanel.this, cause.getMessage(), "Sale not saved", JOptionPane.WARNING_MESSAGE);
                    loadChoices();
                }
            }
        }.execute();
    }

    private void saleSaved(SalesService.Sale s) {
        Object[] options = {"Print receipt", "Save receipt PDF", "Done"};
        int choice = JOptionPane.showOptionDialog(this, "Sale saved.\n\nReceipt " + s.receiptNo + "\nTotal " + Money.peso(s.total)
                        + ("PAID".equals(s.paymentStatus) ? (s.change != null ? "\nChange " + Money.peso(s.change) : "") : "\nUnpaid"),
                "Sale saved", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (choice == 0) ReceiptActions.print(this, SalesService.receipt(s), "JAIMILA receipt " + s.receiptNo);
        else if (choice == 1) ReceiptActions.save(this, SalesService.receipt(s), "Receipt_" + s.receiptNo + ".pdf");
    }

    /** The cart as a table; Qty and Discount can be edited in place. */
    private final class CartModel extends AbstractTableModel {
        private final String[] cols = {"Product", "Price", "Qty", "Subtotal", "Discount", "Total"};

        @Override
        public int getRowCount() {
            return cart.lines().size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 2 || c == 4;
        }

        @Override
        public Object getValueAt(int r, int c) {
            SaleCart.Line l = cart.lines().get(r);
            switch (c) {
                case 0: return l.cartName();
                case 1: return Money.plain(l.price);
                case 2: return String.valueOf(l.quantity);
                case 3: return Money.plain(l.subtotal());
                case 4: return l.discount.signum() == 0 ? "" : Money.plain(l.discount);
                default: return Money.plain(l.total());
            }
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            if (r >= cart.lines().size()) return;
            SaleCart.Line l = cart.lines().get(r);
            try {
                if (c == 2) {
                    int q = Money.parseInt(String.valueOf(value), "quantity", 0, 100_000);
                    String problem = q > l.quantity ? cart.quantityProblem(r, q) : null;
                    if (problem != null) throw new IllegalArgumentException(problem);
                    cart.setQuantity(r, q);
                } else if (c == 4) {
                    BigDecimal d = Money.parse(String.valueOf(value), "discount", true);
                    d = d == null ? BigDecimal.ZERO : d;
                    if (d.compareTo(l.subtotal()) > 0) {
                        throw new IllegalArgumentException("The discount is more than the line subtotal (" + Money.peso(l.subtotal()) + ").");
                    }
                    cart.setDiscount(r, d);
                }
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(SalesPanel.this, ex.getMessage(), "Check the line", JOptionPane.WARNING_MESSAGE);
            }
            SwingUtilities.invokeLater(SalesPanel.this::cartChanged);
        }
    }

    // =====================================================================================
    // Sales history
    // =====================================================================================

    private JComponent buildHistory() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(10, 0, 0, 0));
        LocalDate today = LocalDate.now(Theme.BUSINESS_TZ);
        from.setText(today.minusDays(29).toString());
        to.setText(today.toString());
        UiKit.searchField(search, "Receipt no. or customer");
        for (JTextField f : new JTextField[]{from, to}) {
            UiKit.styleField(f);
            f.setToolTipText("YYYY-MM-DD (Asia/Manila)");
        }
        for (JComboBox<String> c : List.of(fType, fStatus, fPayment)) c.setFont(Theme.SUBTLE);
        JButton show = UiKit.primaryButton("Show");
        show.addActionListener(e -> loadHistory());
        search.addActionListener(e -> loadHistory());
        from.addActionListener(e -> loadHistory());
        to.addActionListener(e -> loadHistory());

        JPanel filters = UiKit.card(new BorderLayout(), 10, 14);
        JPanel row = UiKit.flowRow();
        row.add(UiKit.group("Search", search));
        row.add(UiKit.group("Type", fType));
        row.add(UiKit.group("Status", fStatus));
        row.add(UiKit.group("Payment", fPayment));
        row.add(UiKit.group("From", from));
        row.add(UiKit.group("To", to));
        JPanel btn = new JPanel(new BorderLayout());
        btn.setOpaque(false);
        btn.add(show, BorderLayout.SOUTH);
        row.add(btn);
        filters.add(row, BorderLayout.CENTER);
        root.add(filters, BorderLayout.NORTH);

        UiKit.styleTable(historyTable, 38);
        historyTable.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        historyTable.getColumnModel().getColumn(0).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, true, false));
        historyTable.getColumnModel().getColumn(4).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, false, false));
        historyTable.getColumnModel().getColumn(5).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, true, false));
        historyTable.getColumnModel().getColumn(6).setCellRenderer(new UiKit.DotRenderer(
                s -> s.startsWith("Unpaid") ? Theme.WARN : Theme.OK, s -> s.startsWith("Unpaid")));
        historyTable.getColumnModel().getColumn(7).setCellRenderer(new UiKit.PillRenderer(
                s -> "Voided".equals(s) ? Theme.DANGER : Theme.OK));
        UiKit.alignHeaders(historyTable, 4, 5);
        int[] widths = {170, 140, 90, 220, 60, 110, 140, 110};
        for (int i = 0; i < widths.length; i++) historyTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        historyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openSelected();
            }
        });

        JPanel tableCard = UiKit.card(new BorderLayout(0, 8), 12, 14);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(UiKit.cardHeading("Sales history", "Double-click a sale for its details, receipt, payment or void"),
                BorderLayout.WEST);
        JButton open = UiKit.secondaryButton("Open sale");
        open.addActionListener(e -> openSelected());
        head.add(open, BorderLayout.EAST);
        historySummary.setFont(Theme.SMALL);
        historySummary.setForeground(Theme.MUTED);
        head.add(historySummary, BorderLayout.SOUTH);
        tableCard.add(head, BorderLayout.NORTH);
        tableCard.add(UiKit.tableScroll(historyTable), BorderLayout.CENTER);
        historyCenter.setOpaque(false);
        historyCenter.add(tableCard, "table");
        historyCenter.add(historyState, "state");
        root.add(historyCenter, BorderLayout.CENTER);
        return root;
    }

    private void loadHistory() {
        final LocalDate f, t;
        try {
            f = LocalDate.parse(from.getText().trim());
            t = LocalDate.parse(to.getText().trim());
        } catch (DateTimeParseException e) {
            historyState.showError("Check the dates", "Write dates as YYYY-MM-DD, for example 2026-10-01.", null);
            historyCards.show(historyCenter, "state");
            return;
        }
        if (t.isBefore(f)) {
            historyState.showError("Check the dates", "The To date is before the From date.", null);
            historyCards.show(historyCenter, "state");
            return;
        }
        final String type = new String[]{null, SalesService.WALK_IN, SalesService.IN_HOUSE}[fType.getSelectedIndex()];
        final String status = new String[]{null, "COMPLETED", "VOIDED"}[fStatus.getSelectedIndex()];
        final String pay = new String[]{null, "PAID", "UNPAID"}[fPayment.getSelectedIndex()];
        final String q = search.getText().trim();
        final int mySeq = ++historySeq;
        historyState.showLoading("Loading sales...");
        historyCards.show(historyCenter, "state");
        new SwingWorker<SalesService.Page, Void>() {
            @Override
            protected SalesService.Page doInBackground() throws Exception {
                return SalesService.list(q, type, status, pay, f, t, 500);
            }

            @Override
            protected void done() {
                if (mySeq != historySeq) return;
                try {
                    SalesService.Page p = get();
                    historyRows.clear();
                    historyModel.setRowCount(0);
                    if (p.rows.isEmpty()) {
                        historyState.showEmpty("No sales from " + f + " to " + t,
                                "New sales appear here as soon as they are saved. Try other filters or dates.");
                        historyCards.show(historyCenter, "state");
                        return;
                    }
                    for (SalesService.Sale s : p.rows) {
                        historyRows.add(s);
                        historyModel.addRow(new Object[]{s.receiptNo, Theme.formatWhen(s.soldAt), SalesService.typeLabel(s.type),
                                s.customerName == null ? "—" : s.customerName, s.itemCount, Money.plain(s.total),
                                "PAID".equals(s.paymentStatus) ? "Paid · " + SalesService.methodLabel(s.paymentMethod) : "Unpaid",
                                SalesService.statusLabel(s.status)});
                    }
                    historySummary.setText(p.count + " sale(s)   ·   paid " + Money.peso(p.paidTotal) + "   ·   unpaid "
                            + Money.peso(p.unpaidTotal) + " (counted as sales only when paid)"
                            + (p.truncated ? "   ·   showing the latest " + p.rows.size() : ""));
                    historyCards.show(historyCenter, "table");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    historyState.showError("Could not load the sales", c.getMessage(), SalesPanel.this::loadHistory);
                    historyCards.show(historyCenter, "state");
                }
            }
        }.execute();
    }

    private void openSelected() {
        int r = historyTable.getSelectedRow();
        if (r < 0) return;
        SalesService.Sale s = historyRows.get(historyTable.convertRowIndexToModel(r));
        if (SaleDetailDialog.open(SwingUtilities.getWindowAncestor(this), s.id)) {
            loadHistory();
            loadChoices();
        }
    }
}
