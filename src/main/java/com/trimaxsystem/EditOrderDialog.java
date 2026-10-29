package com.trimaxsystem;

import java.awt.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Booking window → Edit order (sql/20261029_03): the Admin changes what a booking holds after it was booked,
 * e.g. the customer calls to remove the Sprite or to add two more cases. Quantities can be changed, lines
 * removed and products added; nothing is saved until "Save changes", and then the server does it all at
 * once (stock, totals, audit) or nothing.
 */
final class EditOrderDialog extends JDialog {

    /** What happened: null = closed without saving. */
    static final class Result {
        BookingService.ItemsChange change;   // saved
        String conflict;                     // refused because the booking changed meanwhile (reload it)
    }

    private final BookingService.Detail booking;
    private final OrderEdit edit;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel totals = new JLabel(" ");
    private final JLabel problem = new JLabel(" ");
    private final JComboBox<SalesService.ProductOption> product = new JComboBox<>();
    private final JComboBox<String> unit = new JComboBox<>(new String[]{"Whole case", "Half case"});
    private final JSpinner qty = new JSpinner(new SpinnerNumberModel(1, 1, OrderEdit.MAX_QTY, 1));
    private final JButton add = UiKit.secondaryButton("Add");
    private final JButton remove = UiKit.secondaryButton("Remove item");
    private final JTextField note = new JTextField(28);
    private final JButton save = UiKit.primaryButton("Save changes");
    private final JButton cancel = UiKit.secondaryButton("Cancel");
    private final Map<Integer, BigDecimal> stock = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private Result result;
    private boolean saving;

    /** Opens the editor (modal) and returns what happened; null when nothing was saved. */
    static Result open(Window owner, BookingService.Detail booking) {
        OrderEdit edit = OrderEdit.of(booking.items);
        if (edit == null) {
            JOptionPane.showMessageDialog(owner, "This booking has an item whose product was deleted, so its order cannot be changed here.",
                    "Edit order", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        EditOrderDialog d = new EditOrderDialog(owner, booking, edit);
        d.setVisible(true);
        return d.result;
    }

    private EditOrderDialog(Window owner, BookingService.Detail booking, OrderEdit edit) {
        super(owner, "Edit order - Booking #" + booking.bookingId, ModalityType.APPLICATION_MODAL);
        this.booking = booking;
        this.edit = edit;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.CARD);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        root.add(UiKit.cardHeading("Edit order - Booking #" + booking.bookingId,
                (booking.clientName == null ? "" : booking.clientName + "  ·  ") + booking.status
                        + ". Change a quantity, remove an item or add one. Stock and the total change only when you save."),
                BorderLayout.NORTH);

        // ---- the lines
        UiKit.styleTable(table, 32);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = 1; c < COLS.length; c++) table.getColumnModel().getColumn(c).setCellRenderer(right);
        table.getColumnModel().getColumn(0).setPreferredWidth(280);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JTextField()) {
            @Override
            public boolean stopCellEditing() {
                String text = String.valueOf(getCellEditorValue()).trim();
                try {
                    int q = Integer.parseInt(text);
                    if (q < 1 || q > OrderEdit.MAX_QTY) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    problem.setText("Type a quantity from 1 to " + OrderEdit.MAX_QTY + ". To take an item out, use Remove item.");
                    return false;
                }
                return super.stopCellEditing();
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> remove.setEnabled(table.getSelectedRow() >= 0 && !saving));
        JScrollPane sp = UiKit.tableScroll(table);
        sp.setPreferredSize(new Dimension(640, 220));

        JLabel hint = new JLabel("Double-click a quantity to change it.");
        hint.setFont(Theme.SMALL);
        hint.setForeground(Theme.MUTED);
        remove.setEnabled(false);
        remove.addActionListener(e -> removeSelected());
        JPanel underTable = new JPanel(new BorderLayout());
        underTable.setOpaque(false);
        underTable.add(hint, BorderLayout.WEST);
        underTable.add(remove, BorderLayout.EAST);

        // ---- add a product
        product.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof SalesService.ProductOption) {
                    SalesService.ProductOption p = (SalesService.ProductOption) value;
                    setText(p.name + "  ·  " + Theme.money(p.price) + "  ·  stock " + Money.qty(p.stock));
                } else {
                    setText(value == null ? "Loading products..." : String.valueOf(value));
                }
                return this;
            }
        });
        product.setEnabled(false);
        unit.setEnabled(false);
        add.setEnabled(false);
        product.addActionListener(e -> updateUnitChoice());
        add.addActionListener(e -> addProduct());
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        addRow.setOpaque(false);
        product.setPreferredSize(new Dimension(330, product.getPreferredSize().height));
        addRow.add(product);
        addRow.add(unit);
        addRow.add(qty);
        addRow.add(add);
        JPanel addBox = new JPanel(new BorderLayout(0, 4));
        addBox.setOpaque(false);
        addBox.add(UiKit.caption("Add a product"), BorderLayout.NORTH);
        addBox.add(addRow, BorderLayout.CENTER);

        // ---- totals, note, problems
        totals.setFont(new Font("SansSerif", Font.BOLD, 14));
        totals.setForeground(Theme.TEXT);
        problem.setFont(Theme.SMALL);
        problem.setForeground(Theme.DANGER);
        UiKit.styleField(note);
        UiKit.placeholder(note, "Reason (optional), e.g. Customer called: no Sprite");
        JPanel noteBox = new JPanel(new BorderLayout(0, 4));
        noteBox.setOpaque(false);
        noteBox.add(UiKit.caption("Reason (saved in the booking history)"), BorderLayout.NORTH);
        noteBox.add(note, BorderLayout.CENTER);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{sp, underTable, addBox, noteBox, totals, problem}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            center.add(c);
            center.add(Box.createVerticalStrut(8));
        }
        underTable.setMaximumSize(new Dimension(Integer.MAX_VALUE, underTable.getPreferredSize().height));
        addBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, addBox.getPreferredSize().height));
        noteBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, noteBox.getPreferredSize().height));
        root.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> saveChanges());
        buttons.add(cancel);
        buttons.add(save);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        refresh();
        pack();
        setMinimumSize(new Dimension(620, getHeight()));
        setLocationRelativeTo(owner);
        loadProducts();
    }

    // ------------------------------------------------------------------------------------ products

    private void loadProducts() {
        new SwingWorker<List<SalesService.ProductOption>, Void>() {
            @Override
            protected List<SalesService.ProductOption> doInBackground() throws Exception {
                return SalesService.products();
            }

            @Override
            protected void done() {
                try {
                    List<SalesService.ProductOption> list = get();
                    product.removeAllItems();
                    for (SalesService.ProductOption p : list) {
                        product.addItem(p);
                        stock.put(p.id, p.stock);
                        names.put(p.id, p.name);
                    }
                    product.setEnabled(!list.isEmpty());
                    add.setEnabled(!list.isEmpty());
                    updateUnitChoice();
                    refresh();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    problem.setText("Could not load the products to add: " + c.getMessage());
                }
            }
        }.execute();
    }

    private void updateUnitChoice() {
        SalesService.ProductOption p = (SalesService.ProductOption) product.getSelectedItem();
        boolean half = p != null && p.halfPrice != null;
        unit.setEnabled(half && !saving);
        if (!half) unit.setSelectedIndex(0);
        unit.setToolTipText(half ? "Half case: " + Theme.money(p.halfPrice) : "This product is sold by the whole case only");
    }

    private void addProduct() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        SalesService.ProductOption p = (SalesService.ProductOption) product.getSelectedItem();
        if (p == null) return;
        boolean half = unit.getSelectedIndex() == 1 && p.halfPrice != null;
        try {
            edit.add(p.id, half ? OrderEdit.HALF : OrderEdit.WHOLE, p.name, half ? p.halfPrice : p.price, (Integer) qty.getValue());
            problem.setText(" ");
        } catch (RuntimeException ex) {
            problem.setText(ex.getMessage());
        }
        qty.setValue(1);
        refresh();
    }

    private void removeSelected() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        int r = table.getSelectedRow();
        if (r < 0) return;
        try {
            edit.remove(r);
            problem.setText(" ");
        } catch (RuntimeException ex) {
            problem.setText(ex.getMessage());
        }
        refresh();
    }

    // ------------------------------------------------------------------------------------ showing

    private void refresh() {
        model.fireTableDataChanged();
        BigDecimal now = edit.total();
        BigDecimal was = booking.total == null ? BigDecimal.ZERO : booking.total;
        boolean orderDiscount = booking.discountType != null && booking.discountValue != null && booking.discountValue.signum() > 0;
        totals.setText("Total: was " + Theme.money(was) + "  →  now " + Theme.money(now)
                + (orderDiscount ? "  (before the whole-order discount, recomputed when saved)" : ""));
        String stockProblem = edit.stockProblem(stock, names);
        if (stockProblem != null) problem.setText(stockProblem);
        else if (problem.getText().startsWith("Not enough stock")) problem.setText(" ");
        save.setEnabled(edit.changed() && stockProblem == null && !saving);
        remove.setEnabled(table.getSelectedRow() >= 0 && !saving);
    }

    // ------------------------------------------------------------------------------------ saving

    private void saveChanges() {
        if (saving) return;
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
        if (!edit.changed()) return;
        StringBuilder list = new StringBuilder();
        for (String c : edit.changes()) list.append("• ").append(c).append('\n');
        int ok = JOptionPane.showConfirmDialog(this,
                "Change the order of booking #" + booking.bookingId + "?\n\n" + list
                        + "\nTotal: " + Theme.money(booking.total) + "  →  " + Theme.money(edit.total())
                        + "\nStock is taken or given back by the difference.",
                "Save changes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        saving = true;
        setControlsEnabled(false);
        save.setText("Saving...");
        final String items = edit.toJson();
        final String reason = note.getText();
        new SwingWorker<BookingService.ItemsChange, Void>() {
            @Override
            protected BookingService.ItemsChange doInBackground() throws Exception {
                return BookingService.updateItems(booking.bookingId, booking.itemsSignature, items, reason);
            }

            @Override
            protected void done() {
                saving = false;
                save.setText("Save changes");
                try {
                    result = new Result();
                    result.change = get();
                    dispose();
                } catch (Exception ex) {
                    result = null;
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    if (c instanceof BookingService.ConflictException) {
                        JOptionPane.showMessageDialog(EditOrderDialog.this, c.getMessage(), "Order not changed",
                                JOptionPane.WARNING_MESSAGE);
                        result = new Result();
                        result.conflict = c.getMessage();
                        dispose();
                        return;
                    }
                    setControlsEnabled(true);
                    problem.setText("<html>" + StockDialogs.esc(c.getMessage()) + "</html>");
                    refresh();
                }
            }
        }.execute();
    }

    private void setControlsEnabled(boolean on) {
        table.setEnabled(on);
        product.setEnabled(on && product.getItemCount() > 0);
        add.setEnabled(on && product.getItemCount() > 0);
        qty.setEnabled(on);
        note.setEnabled(on);
        cancel.setEnabled(on);
        save.setEnabled(on && edit.changed());
        remove.setEnabled(on && table.getSelectedRow() >= 0);
        if (on) updateUnitChoice();
        else unit.setEnabled(false);
    }

    // ------------------------------------------------------------------------------------ table

    private static final String[] COLS = {"Product", "Qty", "Unit price", "Less / piece", "Line total"};
    private static final int QTY = 1;

    private final class Model extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return edit.lines().size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == QTY && !saving;
        }

        @Override
        public Object getValueAt(int r, int c) {
            OrderEdit.Line l = edit.lines().get(r);
            switch (c) {
                case 0: return l.label() + (l.isNew() ? "  (new)" : l.qty != l.originalQty ? "  (was " + l.originalQty + ")" : "");
                case 1: return String.valueOf(l.qty);
                case 2: return Theme.money(l.unitPrice);
                case 3: return l.discountPerUnit.signum() > 0 ? "− " + Theme.money(l.discountPerUnit) : "";
                default: return Theme.money(l.total());
            }
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            if (c != QTY) return;
            try {
                edit.setQty(r, Integer.parseInt(String.valueOf(value).trim()));
                problem.setText(" ");
            } catch (RuntimeException ex) {
                problem.setText("Type a quantity from 1 to " + OrderEdit.MAX_QTY + ".");
            }
            refresh();
        }
    }
}
