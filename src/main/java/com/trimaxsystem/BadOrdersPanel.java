package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Inventory > Bad orders: broken, damaged, expired, defective or spoiled items taken out of stock, with
 * the reason, what happened to them (thrown away or returned to supplier), the date found and the loss
 * (pieces × puhunan). Totals by reason; the loss lowers net profit in Sales & Profit by the date found.
 * A mistake is undone with a reason: the pieces go back into stock and the loss no longer counts.
 */
final class BadOrdersPanel extends JPanel {

    private static final int LIMIT = 1000;
    private static final int COL_PIECES = 3, COL_REASON = 4, COL_LOSS = 6, COL_STATUS = 9;
    private static final String[] WHAT = {"Thrown away or returned", "Thrown away", "Returned to supplier"};

    private final Runnable onStockChanged;
    private final JComboBox<Item> product = new JComboBox<>();
    private final JComboBox<String> reason = new JComboBox<>();
    private final JComboBox<String> what = new JComboBox<>(WHAT);
    private final JTextField from = new JTextField(9), to = new JTextField(9);
    private final JCheckBox withUndone = new JCheckBox("Show undone");
    private final JButton apply = UiKit.primaryButton("Show");
    private final JLabel kLoss = kpi(), kUnits = kpi(), kCount = kpi(), kNoCost = kpi();
    private final JLabel kCountSub = hint(" "), kNoCostSub = hint(" ");
    private final JLabel byReason = new JLabel(" ");
    private final DefaultTableModel model = new DefaultTableModel(new String[]{
            "#", "Date found", "Product", "Pieces", "Reason", "What happened", "Loss", "Note", "By", "Status"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final List<BadOrderService.Record> rows = new ArrayList<>();
    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JButton btnRecord = UiKit.dangerButton("Record bad order...");
    private final JButton btnRecordFromEmpty = UiKit.dangerButton("Record bad order...");
    private final JButton btnUndo = UiKit.secondaryButton("Undo...");
    private final JComboBox<String> format = new JComboBox<>(new String[]{"CSV", "Excel (.xlsx)", "PDF"});
    private final JButton btnExport = UiKit.secondaryButton("Export");
    private final List<InventoryService.Product> allProducts = new ArrayList<>();
    private BadOrderService.Page current;
    private LocalDate curFrom, curTo;
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

    BadOrdersPanel(Runnable onStockChanged) {
        super(new BorderLayout(0, 10));
        this.onStockChanged = onStockChanged;
        setOpaque(false);
        setBorder(new EmptyBorder(10, 0, 0, 0));

        product.addItem(new Item(null, "All products"));
        reason.addItem("All reasons");
        for (String[] r : BadOrderService.REASONS) reason.addItem(r[1]);
        LocalDate today = LocalDate.now(Theme.BUSINESS_TZ);
        from.setText(today.minusDays(29).toString());
        to.setText(today.toString());
        for (JTextField f : new JTextField[]{from, to}) {
            UiKit.styleField(f);
            f.setToolTipText("YYYY-MM-DD: bad orders found in this range (Asia/Manila, both included)");
            f.addActionListener(e -> load());
        }
        for (JComboBox<?> c : new JComboBox<?>[]{product, reason, what, format}) c.setFont(Theme.SUBTLE);
        product.setPrototypeDisplayValue(new Item(null, "Mineral water 500 ml x24 (Aqua)"));
        table.setToolTipText("Double-click a row for all its details (supplier, puhunan per piece, who undid it and why)");
        withUndone.setOpaque(false);
        withUndone.setFont(Theme.SUBTLE);
        withUndone.setToolTipText("Also list the bad orders that were undone (they never count in the totals)");
        apply.addActionListener(e -> load());
        reason.addActionListener(e -> load());
        what.addActionListener(e -> load());
        withUndone.addActionListener(e -> load());

        JPanel filters = UiKit.card(new BorderLayout(), 10, 14);
        JPanel row = UiKit.flowRow();
        row.add(UiKit.group("Product", product));
        row.add(UiKit.group("Reason", reason));
        row.add(UiKit.group("What happened", what));
        row.add(UiKit.group("Found from", from));
        row.add(UiKit.group("to", to));
        row.add(bottom(withUndone));
        row.add(bottom(apply));
        filters.add(row, BorderLayout.CENTER);

        JPanel kpis = new JPanel(new GridLayout(1, 4, 10, 0));
        kpis.setOpaque(false);
        kpis.add(kpiCard("Loss (puhunan)", kLoss, hint("lowers net profit for these dates")));
        kpis.add(kpiCard("Pieces", kUnits, hint("taken out of stock")));
        kpis.add(kpiCard("Bad orders", kCount, kCountSub));
        kpis.add(kpiCard("Without puhunan", kNoCost, kNoCostSub));
        byReason.setFont(Theme.SMALL);
        byReason.setForeground(Theme.MUTED);

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        kpis.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(filters);
        north.add(Box.createVerticalStrut(10));
        north.add(kpis);
        add(north, BorderLayout.NORTH);

        UiKit.styleTable(table, 36);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        table.getColumnModel().getColumn(0).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, false, true));
        table.getColumnModel().getColumn(2).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, true, false));
        table.getColumnModel().getColumn(COL_PIECES).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, false, false));
        table.getColumnModel().getColumn(COL_LOSS).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, true, false));
        table.getColumnModel().getColumn(COL_REASON).setCellRenderer(new UiKit.DotRenderer(BadOrdersPanel::reasonColor, s -> false));
        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new UiKit.PillRenderer(
                s -> "Counted".equals(s) ? Theme.DANGER : Theme.MUTED));
        UiKit.alignHeaders(table, COL_PIECES, COL_LOSS);
        int[] widths = {45, 100, 200, 68, 105, 155, 118, 200, 100, 95};
        int[] min = {40, 95, 140, 64, 95, 140, 112, 100, 80, 90};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.getColumnModel().getColumn(i).setMinWidth(min[i]);
        }
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) showDetails();
            }
        });

        btnRecord.addActionListener(e -> record());
        btnRecordFromEmpty.addActionListener(e -> record());
        btnUndo.addActionListener(e -> undo());
        btnExport.addActionListener(e -> export());
        btnUndo.setToolTipText("A mistake: put the pieces back into stock; the loss no longer counts (a reason is required)");

        JPanel tableCard = UiKit.card(new BorderLayout(0, 8), 12, 14);
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(UiKit.cardHeading("Bad orders", "Net profit = gross profit − payroll − other expenses − bad orders"),
                BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(format);
        actions.add(btnExport);
        actions.add(btnUndo);
        actions.add(btnRecord);
        head.add(actions, BorderLayout.EAST);
        head.add(byReason, BorderLayout.SOUTH);
        tableCard.add(head, BorderLayout.NORTH);
        tableCard.add(UiKit.tableScroll(table), BorderLayout.CENTER);
        center.setOpaque(false);
        center.add(tableCard, "table");
        JPanel stateCard = UiKit.card(new BorderLayout(), 12, 14);
        JPanel stateActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        stateActions.setOpaque(false);
        stateActions.add(btnRecordFromEmpty);
        stateCard.add(stateActions, BorderLayout.NORTH);
        stateCard.add(state, BorderLayout.CENTER);
        center.add(stateCard, "state");
        add(center, BorderLayout.CENTER);
        state.showEmpty("Choose a period and press Show", "");
        cards.show(center, "state");
        updateButtons();
    }

    private static JPanel bottom(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(c, BorderLayout.SOUTH);
        return p;
    }

    private static JLabel kpi() {
        JLabel l = new JLabel("—");
        l.setFont(Theme.font(Font.BOLD, 20));
        l.setForeground(Theme.TEXT);
        return l;
    }

    private static JLabel hint(String text) {
        JLabel h = new JLabel(text);
        h.setFont(Theme.SMALL);
        h.setForeground(Theme.MUTED);
        return h;
    }

    private static JPanel kpiCard(String title, JLabel value, JLabel hint) {
        JPanel c = UiKit.card(new BorderLayout(0, 4), 12, 14);
        c.add(UiKit.caption(title.toUpperCase()), BorderLayout.NORTH);
        c.add(value, BorderLayout.CENTER);
        c.add(hint, BorderLayout.SOUTH);
        return c;
    }

    private static Color reasonColor(String label) {
        switch (label) {
            case "Broken":
            case "Damaged":
            case "Defective": return Theme.DANGER;
            case "Expired":
            case "Spoiled": return Theme.WARN;
            default: return Theme.MUTED;
        }
    }

    private BadOrderService.Record selected() {
        int r = table.getSelectedRow();
        return r < 0 ? null : rows.get(table.convertRowIndexToModel(r));
    }

    private void updateButtons() {
        BadOrderService.Record r = selected();
        btnUndo.setEnabled(r != null && !r.undone() && r.productId != null);
        btnExport.setEnabled(current != null);
    }

    /** Loads the list the first time the tab is shown; later only when asked. */
    void showing() {
        if (!loadedOnce) load();
    }

    void reloadIfShown() {
        if (loadedOnce) load();
    }

    void load() {
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
        loadedOnce = true;
        final Item p = (Item) product.getSelectedItem();
        int ri = reason.getSelectedIndex();
        final String rc = ri <= 0 ? null : BadOrderService.REASONS[ri - 1][0];
        int wi = what.getSelectedIndex();
        final String dc = wi <= 0 ? null : BadOrderService.DISPOSITIONS[wi - 1][0];
        final boolean undone = withUndone.isSelected();
        final int mySeq = ++seq;
        state.showLoading("Loading bad orders...");
        cards.show(center, "state");
        apply.setEnabled(false);
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                String missing = Db.missingBadOrdersUpdate();
                if (!missing.isEmpty()) return new Object[]{missing};
                InventoryService.Catalog all;
                try {
                    all = InventoryService.list("");
                } catch (java.sql.SQLException e) {
                    throw Db.failure(e, "Could not load the products");
                }
                return new Object[]{null, all, BadOrderService.list(f, t, rc, dc, p == null ? null : p.id, undone, LIMIT)};
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                apply.setEnabled(true);
                try {
                    Object[] r = get();
                    if (r[0] != null) {
                        current = null;
                        btnRecord.setEnabled(false);
                        btnRecordFromEmpty.setEnabled(false);
                        state.showError("Bad orders are not set up on the server yet",
                                Db.notReadyMessage("Bad orders", (String) r[0]), BadOrdersPanel.this::load);
                        cards.show(center, "state");
                        updateButtons();
                        return;
                    }
                    btnRecord.setEnabled(true);
                    btnRecordFromEmpty.setEnabled(true);
                    setProducts(((InventoryService.Catalog) r[1]).products);
                    show((BadOrderService.Page) r[2], f, t);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    state.showError("Could not load the bad orders", c.getMessage(), BadOrdersPanel.this::load);
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    /** Refreshes the product filter (keeping the choice) and the products the Record dialog offers. */
    private void setProducts(List<InventoryService.Product> list) {
        allProducts.clear();
        allProducts.addAll(list);
        Item selected = (Item) product.getSelectedItem();
        List<InventoryService.Product> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(x -> x.name.toLowerCase()));
        product.removeAllItems();
        product.addItem(new Item(null, "All products"));
        for (InventoryService.Product x : sorted) {
            Item it = new Item(x.id, x.name + (x.supplier == null || x.supplier.isEmpty() ? "" : " (" + x.supplier + ")"));
            product.addItem(it);
            if (selected != null && selected.id != null && selected.id == x.id) product.setSelectedItem(it);
        }
    }

    private void show(BadOrderService.Page p, LocalDate f, LocalDate t) {
        current = p;
        curFrom = f;
        curTo = t;
        rows.clear();
        model.setRowCount(0);
        kLoss.setText(Money.peso(p.all.loss));
        kLoss.setForeground(p.all.loss.signum() > 0 ? Theme.DANGER : Theme.TEXT);
        kUnits.setText(String.valueOf(p.all.units));
        kCount.setText(String.valueOf(p.all.count));
        kCountSub.setText(p.undoneCount == 0 ? "undone ones not counted" : p.undoneCount + " undone, not counted");
        kNoCost.setText(String.valueOf(p.all.noCost));
        kNoCost.setForeground(p.all.noCost > 0 ? Theme.WARN : Theme.TEXT);
        kNoCostSub.setText(p.all.noCost > 0 ? "no peso loss counted for these" : "every one has its loss");
        String reasons = BadOrderService.byReasonText(p);
        byReason.setText((reasons.isEmpty() ? " " : "By reason:   " + reasons)
                + (p.truncated ? "   ·   showing the latest " + LIMIT : ""));
        updateButtons();
        if (p.rows.isEmpty()) {
            state.showEmpty("No bad orders found " + f + " to " + t,
                    "Record broken, damaged, expired, defective or spoiled items with Record bad order.");
            cards.show(center, "state");
            return;
        }
        for (BadOrderService.Record r : p.rows) {
            rows.add(r);
            model.addRow(new Object[]{r.id, r.foundOn.toString(),
                    r.productName + (r.productId == null ? " (deleted)" : ""), r.quantity,
                    BadOrderService.reasonLabel(r.reason), BadOrderService.dispositionLabel(r.disposition),
                    r.loss == null ? "no puhunan" : Money.plain(r.loss),
                    r.note == null ? "" : r.note, r.recordedBy == null ? "" : r.recordedBy,
                    r.undone() ? "Undone" : "Counted"});
        }
        cards.show(center, "table");
        updateButtons();
    }

    private void record() {
        if (allProducts.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no products yet.", "Bad order", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Item it = (Item) product.getSelectedItem();
        InventoryService.Product start = null;
        if (it != null && it.id != null) {
            for (InventoryService.Product x : allProducts) if (x.id == it.id) start = x;
        }
        if (StockDialogs.badOrder(SwingUtilities.getWindowAncestor(this), start, allProducts)) {
            load();
            if (onStockChanged != null) onStockChanged.run();
        }
    }

    private void undo() {
        BadOrderService.Record r = selected();
        if (r == null || r.undone()) return;
        if (r.productId == null) {
            JOptionPane.showMessageDialog(this, "The product was deleted, so its stock cannot be put back.",
                    "Undo bad order", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String why = JOptionPane.showInputDialog(this, "Undo bad order #" + r.id + " (" + r.productName + ", "
                        + r.quantity + " " + BadOrderService.reasonLabel(r.reason).toLowerCase() + ", found " + r.foundOn + ")?\n\n"
                        + "The " + r.quantity + " piece(s) go back into stock"
                        + (r.loss == null ? "" : " and the " + Money.peso(r.loss) + " loss no longer counts") + ".\n"
                        + "It stays on record as undone, with your name and the reason.\n\nReason:",
                "Undo bad order", JOptionPane.WARNING_MESSAGE);
        if (why == null) return;
        if (why.trim().length() < 3) {
            JOptionPane.showMessageDialog(this, "Enter the reason (at least 3 characters).", "Undo bad order",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        final long id = r.id;
        final String reasonText = why.trim();
        btnUndo.setEnabled(false);
        new SwingWorker<int[], Void>() {
            @Override
            protected int[] doInBackground() throws Exception {
                return BadOrderService.undo(id, reasonText);
            }

            @Override
            protected void done() {
                try {
                    int[] s = get();
                    JOptionPane.showMessageDialog(BadOrdersPanel.this, r.productName + "\nStock " + s[0] + " → " + s[1]
                            + ". Bad order #" + id + " is undone.", "Bad order undone", JOptionPane.INFORMATION_MESSAGE);
                    load();
                    if (onStockChanged != null) onStockChanged.run();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(BadOrdersPanel.this, c.getMessage(), "Not undone", JOptionPane.WARNING_MESSAGE);
                    updateButtons();
                }
            }
        }.execute();
    }

    private void showDetails() {
        BadOrderService.Record r = selected();
        if (r == null) return;
        StringBuilder b = new StringBuilder();
        b.append(r.productName).append(r.productId == null ? " (deleted)" : "")
                .append(r.supplier == null ? "" : "  ·  " + r.supplier).append('\n');
        b.append(r.quantity).append(" piece(s), ").append(BadOrderService.reasonLabel(r.reason).toLowerCase())
                .append(", ").append(BadOrderService.dispositionLabel(r.disposition).toLowerCase())
                .append(", found ").append(r.foundOn).append('\n');
        b.append(r.loss == null ? "No puhunan: no peso loss counted" : "Loss " + Money.peso(r.loss) + " ("
                + r.quantity + " × " + Money.peso(r.unitCost) + ")").append('\n');
        if (r.note != null) b.append("Note: ").append(r.note).append('\n');
        b.append("Recorded by ").append(r.recordedBy == null ? "?" : r.recordedBy).append(", ")
                .append(Theme.formatWhen(r.recordedAt));
        if (r.undone()) {
            b.append("\n\nUndone by ").append(r.voidedBy == null ? "?" : r.voidedBy).append(", ")
                    .append(Theme.formatWhen(r.voidedAt)).append(":\n").append(r.voidReason);
        }
        JOptionPane.showMessageDialog(this, b.toString(), "Bad order #" + r.id, JOptionPane.INFORMATION_MESSAGE);
    }

    private void export() {
        if (current == null) return;
        BadOrderService.Page p = current;
        ReportTable t = new ReportTable();
        t.title = "Bad orders";
        t.meta.add("Found: " + curFrom + " to " + curTo + " (Asia/Manila, both included)");
        t.meta.add("Filter: " + product.getSelectedItem() + ", " + reason.getSelectedItem() + ", " + what.getSelectedItem()
                + (withUndone.isSelected() ? ", with undone" : ""));
        t.meta.add("Loss: pieces x puhunan at the time it was recorded; it lowers net profit by the date found");
        t.meta.add("Total counted: " + p.all.count + " bad orders, " + p.all.units + " pieces, loss " + plain(p.all.loss)
                + (p.all.noCost > 0 ? " (" + p.all.noCost + " without puhunan, no loss counted)" : ""));
        t.header = List.of("#", "Date found", "Product", "Supplier", "Reason", "What happened", "Note", "Recorded by",
                "Status", "Pieces", "Puhunan per piece", "Loss");
        t.firstNumericCol = 9;
        for (BadOrderService.Record r : p.rows) {
            t.rows.add(List.of(String.valueOf(r.id), r.foundOn.toString(), r.productName, nz(r.supplier),
                    BadOrderService.reasonLabel(r.reason), BadOrderService.dispositionLabel(r.disposition), nz(r.note),
                    nz(r.recordedBy), r.undone() ? "Undone: " + r.voidReason : "Counted", String.valueOf(r.quantity),
                    plain(r.unitCost), plain(r.loss)));
        }
        t.footer.add(List.of("Reason", "Bad orders", "Pieces", "Loss"));
        for (java.util.Map.Entry<String, BadOrderService.Totals> e : p.byReason.entrySet()) {
            BadOrderService.Totals x = e.getValue();
            t.footer.add(List.of(BadOrderService.reasonLabel(e.getKey()), String.valueOf(x.count), String.valueOf(x.units),
                    plain(x.loss)));
        }
        t.footer.add(List.of("Total", String.valueOf(p.all.count), String.valueOf(p.all.units), plain(p.all.loss)));
        int kind = format.getSelectedIndex();
        String ext = kind == 0 ? ".csv" : kind == 1 ? ".xlsx" : ".pdf";
        try {
            ExportDirectories.initialize();
            JFileChooser chooser = new JFileChooser(ExportDirectories.reportsDirectory().toFile());
            chooser.setSelectedFile(new java.io.File("trimax-bad-orders_" + curFrom + "_" + curTo + ext));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path file = chooser.getSelectedFile().toPath();
            if (!file.getFileName().toString().toLowerCase().endsWith(ext)) file = file.resolveSibling(file.getFileName() + ext);
            if (kind == 0) CsvExport.write(file, t.csvRows());
            else if (kind == 1) XlsxExport.write(file, t);
            else PdfExport.write(file, t);
            JOptionPane.showMessageDialog(this, "Saved:\n" + file, "Export", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not export: " + ex.getMessage(), "Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
