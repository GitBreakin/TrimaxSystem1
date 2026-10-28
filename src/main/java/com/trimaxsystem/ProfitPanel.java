package com.trimaxsystem;

import java.awt.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Sales & Profit: actual sales and profit for Today, Yesterday, Last 7 days, This month, This year or
 * any range, from the database rules in {@link ProfitService}. Revenue, gross profit and net profit are
 * shown apart and named as such; unpaid sales, restock purchases and lines without a recorded cost are
 * listed separately so nothing is counted twice or silently left out. Net profit = gross profit - payroll
 * - other expenses (the Expenses page; database update 20261027_02) - bad orders (Inventory; 20261028_04).
 */
public class ProfitPanel extends JPanel {

    private static final String[] PRESETS = {"Today", "Yesterday", "Last 7 days", "This month", "This year", "Custom"};

    private final ProfitWidgets.PeriodStrip strip = new ProfitWidgets.PeriodStrip(this::pick);
    private final JComboBox<String> preset = new JComboBox<>(PRESETS);
    private final JTextField from = new JTextField(9), to = new JTextField(9);
    private final JComboBox<String> format = new JComboBox<>(new String[]{"CSV", "Excel (.xlsx)", "PDF"});
    private final JButton export = UiKit.secondaryButton("Export");
    private final JLabel rangeLabel = new JLabel(" ");

    private final JLabel mGross = ProfitWidgets.label(), mGrossSub = ProfitWidgets.label();
    private final JLabel mDisc = ProfitWidgets.label(), mDiscSub = ProfitWidgets.label();
    private final JLabel mNet = ProfitWidgets.label(), mNetSub = ProfitWidgets.label();
    private final JLabel mCogs = ProfitWidgets.label(), mCogsSub = ProfitWidgets.label();
    private final JLabel mGp = ProfitWidgets.label(), mGpSub = ProfitWidgets.label();
    private final JLabel mPay = ProfitWidgets.label(), mPaySub = ProfitWidgets.label();
    private final JLabel mOther = ProfitWidgets.label(), mOtherSub = ProfitWidgets.label();
    private final JLabel mBo = ProfitWidgets.label(), mBoSub = ProfitWidgets.label();
    private final JLabel mNp = ProfitWidgets.label(), mNpSub = ProfitWidgets.label();
    private final JTextArea notes = UiKit.wrapText(" ", Theme.SMALL, Theme.TEXT);
    private final JTextArea costWarning = UiKit.wrapText(" ", Theme.font(Font.BOLD, 12), Theme.WARN);
    private final ProfitWidgets.TrendChart chart = new ProfitWidgets.TrendChart();
    private final DefaultTableModel breakdown = new DefaultTableModel(new String[]{
            "Source", "Count", "Gross sales", "Discounts", "Net sales", "Cost of goods", "Gross profit"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);

    private ProfitService.Summary current;
    private List<ProfitService.Point> currentTrend;
    private int seq;
    private boolean applyingPreset;

    public ProfitPanel() {
        super(new BorderLayout());
        setBackground(Theme.BACKGROUND);

        UiKit.PageBody content = new UiKit.PageBody();
        content.setBorder(new EmptyBorder(12, 20, 20, 20));

        strip.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(strip);
        content.add(Box.createVerticalStrut(10));

        // filters
        for (JTextField f : new JTextField[]{from, to}) {
            UiKit.styleField(f);
            f.setToolTipText("YYYY-MM-DD (Asia/Manila); both dates included");
            f.addActionListener(e -> {
                preset.setSelectedItem("Custom");
                load();
            });
        }
        preset.setFont(Theme.SUBTLE);
        format.setFont(Theme.SUBTLE);
        preset.addActionListener(e -> {
            if (!applyingPreset) applyPreset();
        });
        JButton show = UiKit.primaryButton("Show");
        show.addActionListener(e -> load());
        export.addActionListener(e -> exportReport());
        export.setEnabled(false);
        JPanel filters = UiKit.card(new BorderLayout(), 10, 14);
        JPanel row = UiKit.flowRow();
        row.add(UiKit.group("Period", preset));
        row.add(UiKit.group("From", from));
        row.add(UiKit.group("To", to));
        row.add(bottom(show));
        row.add(Box.createHorizontalStrut(18));
        row.add(UiKit.group("Export as", format));
        row.add(bottom(export));
        filters.add(row, BorderLayout.CENTER);
        rangeLabel.setFont(Theme.SMALL);
        rangeLabel.setForeground(Theme.MUTED);
        filters.add(rangeLabel, BorderLayout.SOUTH);
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(filters);
        content.add(Box.createVerticalStrut(10));

        // report
        JPanel report = new JPanel();
        report.setOpaque(false);
        report.setLayout(new BoxLayout(report, BoxLayout.Y_AXIS));
        // Reads like a statement: sales -> gross profit -> what is taken off -> net profit.
        JPanel metrics = new JPanel(new GridLayout(3, 3, 10, 10));
        metrics.setOpaque(false);
        metrics.add(ProfitWidgets.metric("Gross sales", mGross, mGrossSub));
        metrics.add(ProfitWidgets.metric("Discounts", mDisc, mDiscSub));
        metrics.add(ProfitWidgets.metric("Net sales (revenue)", mNet, mNetSub));
        metrics.add(ProfitWidgets.metric("Cost of goods sold", mCogs, mCogsSub));
        metrics.add(ProfitWidgets.metric("Gross profit", mGp, mGpSub));
        metrics.add(ProfitWidgets.metric("Payroll", mPay, mPaySub));
        metrics.add(ProfitWidgets.metric("Other expenses", mOther, mOtherSub));
        metrics.add(ProfitWidgets.metric("Bad orders (puhunan lost)", mBo, mBoSub));
        metrics.add(ProfitWidgets.metric("Net profit", mNp, mNpSub));
        metrics.setAlignmentX(Component.LEFT_ALIGNMENT);
        report.add(metrics);
        report.add(Box.createVerticalStrut(8));
        JPanel notesCard = UiKit.card(new BorderLayout(0, 4), 10, 14);
        notesCard.add(notes, BorderLayout.CENTER);
        notesCard.add(costWarning, BorderLayout.SOUTH);
        notesCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        report.add(notesCard);
        report.add(Box.createVerticalStrut(10));

        JPanel chartCard = UiKit.card(new BorderLayout(0, 6), 12, 14);
        chartCard.add(UiKit.cardHeading("Trend", "Net sales (bars) and net profit after payroll, other expenses and bad orders (line)"), BorderLayout.NORTH);
        chartCard.add(chart, BorderLayout.CENTER);
        chartCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        report.add(chartCard);
        report.add(Box.createVerticalStrut(10));

        JTable table = new JTable(breakdown);
        UiKit.styleTable(table, 34);
        table.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.RIGHT, false, false));
        table.getColumnModel().getColumn(0).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, true, false));
        UiKit.alignHeaders(table, 1, 2, 3, 4, 5, 6);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        JPanel tableCard = UiKit.card(new BorderLayout(0, 6), 12, 14);
        tableCard.add(UiKit.cardHeading("Where the sales came from", null), BorderLayout.NORTH);
        JScrollPane sp = UiKit.tableScroll(table);
        sp.setPreferredSize(new Dimension(600, 34 * 3 + 40));
        tableCard.add(sp, BorderLayout.CENTER);
        JTextArea defs = UiKit.wrapText("Net sales = sales − discounts: Completed bookings (dated when completed) "
                + "and Paid Walk-In / In-House sales (dated when sold). Pending, cancelled, unpaid and voided ones are not counted. "
                + "Cost of goods sold = the puhunan recorded on each sold line at the time of the sale. "
                + "Gross profit = net sales − cost of goods sold. Net profit = gross profit − payroll − other expenses − bad orders. "
                + "Payroll: pay periods ending in the range, paid and unpaid. Other expenses (Expenses page): dated in the range, "
                + "paid and unpaid. Bad orders (Inventory): the puhunan of broken, damaged, expired, defective or spoiled items, "
                + "by the date found. Voided and undone records never count. Restocks are stock purchases, not sales "
                + "or expenses here: their cost counts when the goods are sold. Days are Asia/Manila days.", Theme.SMALL, Theme.MUTED);
        tableCard.add(defs, BorderLayout.SOUTH);
        tableCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        report.add(tableCard);

        center.setOpaque(false);
        center.add(report, "report");
        center.add(state, "state");
        center.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(center);

        add(content.inScroll(), BorderLayout.CENTER);

        preset.setSelectedItem("This month");
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                strip.reload();
                load();
            }
        });
    }

    private static JPanel bottom(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(c, BorderLayout.SOUTH);
        return p;
    }

    private void pick(String name) {
        preset.setSelectedItem(name);
    }

    /** Opens the page on one of the presets (from the Dashboard cards). */
    void showPreset(String name) {
        if (name.equals(preset.getSelectedItem())) applyPreset();
        else preset.setSelectedItem(name);
    }

    private void applyPreset() {
        String p = (String) preset.getSelectedItem();
        Map<String, LocalDate[]> presets = ProfitService.presets(LocalDate.now(Theme.BUSINESS_TZ));
        if (p != null && presets.containsKey(p)) {
            from.setText(presets.get(p)[0].toString());
            to.setText(presets.get(p)[1].toString());
            load();
        }
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
        if (t.isAfter(f.plusYears(5))) {
            state.showError("Choose a shorter period", "At most 5 years at a time.", null);
            cards.show(center, "state");
            return;
        }
        final int mySeq = ++seq;
        state.showLoading("Loading sales and profit...");
        cards.show(center, "state");
        export.setEnabled(false);
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                if (!Db.salesReady()) throw new Db.Failure(Db.notReadyMessage("Sales & Profit"));
                return new Object[]{ProfitService.summary(f, t), ProfitService.trend(f, t)};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done() {
                if (mySeq != seq) return;
                try {
                    Object[] r = get();
                    show((ProfitService.Summary) r[0], (List<ProfitService.Point>) r[1]);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    state.showError("Could not load sales and profit", c.getMessage(), ProfitPanel.this::load);
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    private void show(ProfitService.Summary s, List<ProfitService.Point> trend) {
        current = s;
        currentTrend = trend;
        rangeLabel.setText(s.from.equals(s.to) ? "Showing " + s.from : "Showing " + s.from + " to " + s.to + " (both included)");
        mGross.setText(Money.peso(s.revenueGross));
        mGrossSub.setText(s.bookingsCount + " booking(s), " + s.salesCount + " Walk-In / In-House sale(s)");
        mDisc.setText(s.discounts.signum() == 0 ? Money.peso(BigDecimal.ZERO) : "− " + Money.peso(s.discounts));
        mDiscSub.setText("agents' booking discounts + sale discounts");
        mNet.setText(Money.peso(s.revenueNet));
        mNetSub.setText("gross sales − discounts");
        mCogs.setText(Money.peso(s.cogs));
        mCogsSub.setText(s.linesWithoutCost() == 0 ? "puhunan of the goods sold"
                : s.linesWithoutCost() + " sold line(s) without puhunan not included");
        mCogsSub.setForeground(s.linesWithoutCost() == 0 ? Theme.MUTED : Theme.WARN);
        mGp.setText(Money.peso(s.grossProfit));
        mGp.setForeground(s.grossProfit.signum() < 0 ? Theme.DANGER : Theme.TEXT);
        mGpSub.setText("net sales − cost of goods" + (s.grossMargin() == null ? "" : "  ·  " + ProfitWidgets.pct(s.grossMargin())));
        mPay.setText(s.payroll().signum() == 0 ? Money.peso(BigDecimal.ZERO) : "− " + Money.peso(s.payroll()));
        mPaySub.setText("paid " + Money.peso(s.payrollPaid) + "  ·  unpaid " + Money.peso(s.payrollUnpaid));
        if (s.expensesTracked) {
            mOther.setText(s.otherExpenses().signum() == 0 ? Money.peso(BigDecimal.ZERO) : "− " + Money.peso(s.otherExpenses()));
            mOther.setForeground(Theme.TEXT);
            mOtherSub.setText("paid " + Money.peso(s.otherExpensesPaid) + "  ·  unpaid " + Money.peso(s.otherExpensesUnpaid));
            mOtherSub.setForeground(Theme.MUTED);
        } else {
            mOther.setText("Not counted yet");
            mOther.setForeground(Theme.MUTED);
            mOtherSub.setText("needs " + Db.UPDATE_27_EXPENSES);
            mOtherSub.setForeground(Theme.WARN);
        }
        if (s.badOrdersTracked) {
            mBo.setText(s.badOrdersLoss.signum() == 0 ? Money.peso(BigDecimal.ZERO) : "− " + Money.peso(s.badOrdersLoss));
            mBo.setForeground(Theme.TEXT);
            mBoSub.setText(s.badOrdersCount == 0 ? "broken, damaged, expired items"
                    : s.badOrdersCount + " bad order(s)" + (s.badOrdersNoCost == 0 ? ""
                    : ", " + s.badOrdersNoCost + " without puhunan (no loss counted)"));
            mBoSub.setForeground(s.badOrdersNoCost == 0 ? Theme.MUTED : Theme.WARN);
        } else {
            mBo.setText("Not counted yet");
            mBo.setForeground(Theme.MUTED);
            mBoSub.setText("needs " + Db.UPDATE_28_BAD_ORDERS);
            mBoSub.setForeground(Theme.WARN);
        }
        mNpSub.setText(netProfitFormula(s));
        mNp.setText(Money.peso(s.netProfit));
        mNp.setForeground(s.netProfit.signum() < 0 ? Theme.DANGER : Theme.OK);

        notes.setText("Not counted as sales: " + s.unpaidCount + " unpaid sale(s) worth " + Money.peso(s.unpaidTotal)
                + " (they count when paid).   Stock purchased (restocks): " + s.restockCount + " restock(s), "
                + Money.peso(s.restockCost) + ", a purchase, not revenue.");
        costWarning.setText(s.linesWithoutCost() == 0 ? "" : "Cost missing: " + s.linesWithoutCost()
                + " sold line(s) have no puhunan recorded (older bookings, or products without puhunan). Their cost is not "
                + "in Cost of goods sold, so gross and net profit are higher than they really are. Set the puhunan of those "
                + "products on the Inventory page; new sales then record it.");
        costWarning.setVisible(s.linesWithoutCost() > 0);

        chart.setData(trend, ProfitService.monthly(s.from, s.to));
        breakdown.setRowCount(0);
        breakdown.addRow(new Object[]{"Bookings (completed)", s.bookingsCount, Money.plain(s.bookingsGross),
                Money.plain(s.bookingsDiscount), Money.plain(s.bookingsNet), Money.plain(s.bookingsCogs),
                Money.plain(s.bookingsNet.subtract(s.bookingsCogs))});
        breakdown.addRow(new Object[]{"Walk-In / In-House (paid)", s.salesCount, Money.plain(s.salesGross),
                Money.plain(s.salesDiscount), Money.plain(s.salesNet), Money.plain(s.salesCogs),
                Money.plain(s.salesNet.subtract(s.salesCogs))});
        breakdown.addRow(new Object[]{"Total", s.bookingsCount + s.salesCount, Money.plain(s.revenueGross),
                Money.plain(s.discounts), Money.plain(s.revenueNet), Money.plain(s.cogs), Money.plain(s.grossProfit)});

        if (s.empty()) {
            state.showEmpty("No sales, payroll, expenses, bad orders or restocks from " + s.from + " to " + s.to,
                    "Completed bookings, paid Walk-In / In-House sales, payroll, other expenses and bad orders appear here.");
            cards.show(center, "state");
            export.setEnabled(true);
            return;
        }
        cards.show(center, "report");
        export.setEnabled(true);
    }

    private void exportReport() {
        if (current == null) return;
        ProfitService.Summary s = current;
        ReportTable t = new ReportTable();
        t.title = "Sales and profit";
        t.meta.add("Period: " + s.from + " to " + s.to + " (Asia/Manila, both included)");
        t.meta.add("Net sales: completed bookings (by completion date) + paid Walk-In / In-House sales, after discounts");
        t.meta.add("Gross profit: net sales - cost of goods sold (puhunan recorded at the time of sale)");
        t.meta.add("Net profit: " + netProfitFormula(s).replace('−', '-'));
        t.meta.add("Payroll: pay periods ending in the period (paid and unpaid)"
                + (s.expensesTracked ? "; Other expenses: dated in the period (paid and unpaid, voided never)" : "")
                + (s.badOrdersTracked ? "; Bad orders: puhunan of the items, by the date found (undone never)" : ""));
        if (!s.expensesTracked) t.meta.add("Note: other expenses are not counted (the server needs " + Db.UPDATE_27_EXPENSES + ")");
        if (!s.badOrdersTracked) t.meta.add("Note: bad orders are not counted (the server needs " + Db.UPDATE_28_BAD_ORDERS + ")");
        if (s.badOrdersNoCost > 0) {
            t.meta.add("Note: " + s.badOrdersNoCost + " bad order(s) have no puhunan; no loss is counted for them");
        }
        if (s.linesWithoutCost() > 0) {
            t.meta.add("Note: " + s.linesWithoutCost() + " sold lines have no recorded puhunan; their cost is not included");
        }
        t.header = List.of("Line", "PHP");
        t.firstNumericCol = 1;
        t.rows.add(List.of("Gross sales", plain(s.revenueGross)));
        t.rows.add(List.of("Discounts", plain(s.discounts)));
        t.rows.add(List.of("Net sales", plain(s.revenueNet)));
        t.rows.add(List.of("Cost of goods sold", plain(s.cogs)));
        t.rows.add(List.of("Gross profit", plain(s.grossProfit)));
        t.rows.add(List.of("Payroll paid", plain(s.payrollPaid)));
        t.rows.add(List.of("Payroll unpaid", plain(s.payrollUnpaid)));
        if (s.expensesTracked) {
            t.rows.add(List.of("Other expenses paid", plain(s.otherExpensesPaid)));
            t.rows.add(List.of("Other expenses unpaid", plain(s.otherExpensesUnpaid)));
        }
        if (s.badOrdersTracked) t.rows.add(List.of("Bad orders (puhunan lost)", plain(s.badOrdersLoss)));
        t.rows.add(List.of("Net profit", plain(s.netProfit)));
        t.rows.add(List.of("Unpaid sales (not counted)", plain(s.unpaidTotal)));
        t.rows.add(List.of("Restock purchases (not revenue)", plain(s.restockCost)));
        if (currentTrend != null) {
            boolean exp = s.expensesTracked, bo = s.badOrdersTracked;
            List<String> head = new java.util.ArrayList<>(List.of("Date", "Net sales", "Cost of goods", "Gross profit", "Payroll"));
            if (exp) head.add("Other expenses");
            if (bo) head.add("Bad orders");
            head.add("Net profit");
            t.footer.add(head);
            for (ProfitService.Point p : currentTrend) {
                List<String> line = new java.util.ArrayList<>(List.of(p.bucket.toString(), plain(p.revenue), plain(p.cogs),
                        plain(p.grossProfit), plain(p.payroll)));
                if (exp) line.add(plain(p.otherExpenses));
                if (bo) line.add(plain(p.badOrders));
                line.add(plain(p.netProfit));
                t.footer.add(line);
            }
        }
        int kind = format.getSelectedIndex();
        String ext = kind == 0 ? ".csv" : kind == 1 ? ".xlsx" : ".pdf";
        try {
            ExportDirectories.initialize();
            JFileChooser chooser = new JFileChooser(ExportDirectories.reportsDirectory().toFile());
            chooser.setSelectedFile(new java.io.File("trimax-sales-profit_" + s.from + "_" + s.to + ext));
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

    /** "gross profit − payroll − other expenses − bad orders", naming what this server does not count yet. */
    static String netProfitFormula(ProfitService.Summary s) {
        String f = "gross profit − payroll";
        if (s.expensesTracked) f += " − other expenses";
        if (s.badOrdersTracked) f += " − bad orders";
        if (!s.expensesTracked && !s.badOrdersTracked) f += " (other expenses and bad orders not counted)";
        else if (!s.expensesTracked) f += " (other expenses not counted)";
        else if (!s.badOrdersTracked) f += " (bad orders not counted)";
        return f;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
