package com.trimaxsystem;

import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Inventory > Stock history: every addition and deduction of stock (starting stock, restocks with
 * their cost, corrections with their reason, bad orders, bookings, sales, voids and cancellations),
 * newest first.
 */
final class StockHistoryPanel extends JPanel {

    private static final int LIMIT = 1000;
    private static final String[][] KINDS = {
            {"All changes", null}, {"Restocks", "RESTOCK"}, {"Sales (Walk-In / In-House)", "SALE"},
            {"Bookings", "BOOKING"}, {"Corrections", "ADJUSTMENT"}, {"Starting stock", "INITIAL"},
            {"Bad orders", "BAD_ORDER"}, {"Voided sales", "SALE_VOIDED"}, {"Cancelled bookings", "BOOKING_CANCELLED"},
            {"Bad orders undone", "BAD_ORDER_VOIDED"}};

    private final JComboBox<Item> product = new JComboBox<>();
    private final JComboBox<String> kind = new JComboBox<>();
    private final JTextField from = new JTextField(9);
    private final JTextField to = new JTextField(9);
    private final JButton apply = UiKit.primaryButton("Show");
    private final JLabel summary = new JLabel(" ");
    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final DefaultTableModel model = new DefaultTableModel(new String[]{
            "When", "Product", "Type", "Change", "Stock", "Cost / unit", "Total cost", "Reference", "By", "Note"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private boolean loadedOnce;
    private int seq;

    private static final class Item {
        final Integer id;
        final String label;

        Item(Integer id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    StockHistoryPanel() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(new EmptyBorder(10, 0, 0, 0));
        for (String[] k : KINDS) kind.addItem(k[0]);
        product.addItem(new Item(null, "All products"));
        LocalDate today = LocalDate.now(Theme.BUSINESS_TZ);
        from.setText(today.minusDays(29).toString());
        to.setText(today.toString());
        for (JTextField f : new JTextField[]{from, to}) {
            UiKit.styleField(f);
            f.setToolTipText("YYYY-MM-DD (Asia/Manila)");
            f.addActionListener(e -> load());
        }
        product.setFont(Theme.SUBTLE);
        kind.setFont(Theme.SUBTLE);
        apply.addActionListener(e -> load());

        JPanel filters = UiKit.card(new BorderLayout(10, 0), 10, 14);
        JPanel row = UiKit.flowRow();
        row.add(UiKit.group("Product", product));
        row.add(UiKit.group("Type", kind));
        row.add(UiKit.group("From", from));
        row.add(UiKit.group("To", to));
        JPanel btn = new JPanel(new BorderLayout());
        btn.setOpaque(false);
        btn.add(apply, BorderLayout.SOUTH);
        row.add(btn);
        filters.add(row, BorderLayout.CENTER);
        add(filters, BorderLayout.NORTH);

        JTable table = new JTable(model);
        UiKit.styleTable(table, 36);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        UiKit.CellRenderer right = new UiKit.CellRenderer(SwingConstants.RIGHT, false, false);
        for (int c : new int[]{4, 5, 6}) table.getColumnModel().getColumn(c).setCellRenderer(right);
        table.getColumnModel().getColumn(3).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, true, false));
        table.getColumnModel().getColumn(2).setCellRenderer(new UiKit.DotRenderer(StockHistoryPanel::kindColor, s -> false));
        UiKit.alignHeaders(table, 3, 4, 5, 6);
        int[] widths = {150, 180, 140, 70, 105, 90, 100, 150, 110, 220};
        int[] min = {140, 120, 120, 60, 95, 80, 90, 110, 100, 120};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.getColumnModel().getColumn(i).setMinWidth(min[i]);
        }

        JPanel tableCard = UiKit.card(new BorderLayout(0, 8), 12, 14);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(UiKit.cardHeading("Stock history", "Every stock addition and deduction, newest first"), BorderLayout.WEST);
        summary.setFont(Theme.SMALL);
        summary.setForeground(Theme.MUTED);
        head.add(summary, BorderLayout.SOUTH);
        tableCard.add(head, BorderLayout.NORTH);
        tableCard.add(UiKit.tableScroll(table), BorderLayout.CENTER);
        center.setOpaque(false);
        center.add(tableCard, "table");
        center.add(state, "state");
        add(center, BorderLayout.CENTER);
        state.showEmpty("Choose a period and press Show", "");
        cards.show(center, "state");
    }

    private static Color kindColor(String label) {
        switch (label) {
            case "Restock":
            case "Starting stock":
            case "Sale voided":
            case "Bad order undone":
            case "Booking cancelled": return Theme.OK;
            case "Sale":
            case "Booking": return Theme.BLUE;
            case "Correction": return Theme.WARN;
            case "Bad order": return Theme.DANGER;
            default: return Theme.MUTED;
        }
    }

    /** Refreshes the product list of the filter, keeping the choice. */
    void reloadProducts(Collection<InventoryService.Product> products) {
        Item selected = (Item) product.getSelectedItem();
        List<InventoryService.Product> sorted = new ArrayList<>(products);
        sorted.sort(Comparator.comparing(p -> p.name.toLowerCase()));
        product.removeAllItems();
        product.addItem(new Item(null, "All products"));
        for (InventoryService.Product p : sorted) {
            Item it = new Item(p.id, p.name + (p.supplier == null || p.supplier.isEmpty() ? "" : " (" + p.supplier + ")"));
            product.addItem(it);
            if (selected != null && selected.id != null && selected.id == p.id) product.setSelectedItem(it);
        }
        if (!loadedOnce && isShowing()) load();
    }

    void reloadIfShown() {
        if (loadedOnce) load();
    }

    private void load() {
        final LocalDate f, t;
        try {
            f = LocalDate.parse(from.getText().trim());
            t = LocalDate.parse(to.getText().trim());
        } catch (DateTimeParseException e) {
            state.showError("Check the dates", "Write dates as YYYY-MM-DD, for example 2026-10-01.", null);
            cards.show(center, "state");
            return;
        }
        if (t.isBefore(f)) {
            state.showError("Check the dates", "The To date is before the From date.", null);
            cards.show(center, "state");
            return;
        }
        if (!Db.salesReady()) {
            state.showError("Stock history is not set up on the server yet", Db.notReadyMessage("The stock history"), this::load);
            cards.show(center, "state");
            return;
        }
        loadedOnce = true;
        final Item p = (Item) product.getSelectedItem();
        final String k = KINDS[Math.max(0, kind.getSelectedIndex())][1];
        final int mySeq = ++seq;
        state.showLoading("Loading the stock history...");
        cards.show(center, "state");
        apply.setEnabled(false);
        new SwingWorker<InventoryService.History, Void>() {
            @Override
            protected InventoryService.History doInBackground() throws Exception {
                return InventoryService.history(p == null ? null : p.id, k, f, t, LIMIT);
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                apply.setEnabled(true);
                try {
                    show(get(), f, t);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    state.showError("Could not load the stock history", c.getMessage(), StockHistoryPanel.this::load);
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    private void show(InventoryService.History h, LocalDate f, LocalDate t) {
        model.setRowCount(0);
        if (h.rows.isEmpty()) {
            state.showEmpty("No stock changes from " + f + " to " + t, "Try a longer period or another product or type.");
            cards.show(center, "state");
            return;
        }
        for (InventoryService.Movement m : h.rows) {
            model.addRow(new Object[]{
                    Theme.formatWhen(m.at),
                    m.productName + (m.productId == null ? " (deleted)" : ""),
                    InventoryService.kindLabel(m.kind),
                    (m.change.signum() > 0 ? "+" : "") + Money.qty(m.change),
                    Money.qty(m.before) + " → " + Money.qty(m.after),
                    m.unitCost == null ? "" : Money.plain(m.unitCost),
                    ("RESTOCK".equals(m.kind) || "BAD_ORDER".equals(m.kind)) && m.totalCost != null ? Money.plain(m.totalCost) : "",
                    InventoryService.referenceText(m),
                    m.actor == null ? "" : m.actor,
                    m.note == null ? "" : m.note});
        }
        summary.setText("In: " + Money.qty(h.unitsIn) + " units   ·   Out: " + Money.qty(h.unitsOut) + " units   ·   Restocked: "
                + Money.qty(h.restockUnits) + " units for " + Money.peso(h.restockCost) + " (stock purchases, not sales)"
                + (h.badOrderUnits.signum() == 0 ? "" : "   ·   Bad orders: " + Money.qty(h.badOrderUnits) + " units, "
                        + Money.peso(h.badOrderCost) + " puhunan")
                + (h.truncated ? "   ·   showing the latest " + LIMIT + " changes" : ""));
        cards.show(center, "table");
    }
}
