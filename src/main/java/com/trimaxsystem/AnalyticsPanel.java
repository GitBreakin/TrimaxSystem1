package com.trimaxsystem;

import java.awt.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Analytics & Reports: bookings created in a date range (Asia/Manila days) from the same records the
 * booking and delivery screens use. Trend by day, week or month; counts by status; driver activity and
 * delivery results; customers; products; exceptions (SOS / problem reports, failed deliveries,
 * cancellations). Each tab exports exactly what it shows as CSV, Excel or PDF. Formulas are in
 * {@link ReportService} and are repeated on screen and in every export.
 */
public class AnalyticsPanel extends JPanel {

    private static final ZoneId TZ = ZoneId.of(ReportService.TZ);
    private static final String[] PRESETS = {"Last 7 days", "Last 30 days", "This month", "Last 90 days", "Custom"};
    private static final String[] GROUPS = {"Day", "Week", "Month"};
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DecimalFormat PCT = new DecimalFormat("0.0");
    private static final DecimalFormat MIN = new DecimalFormat("0");

    private final JComboBox<String> preset = new JComboBox<>(PRESETS);
    private final JComboBox<String> groupBy = new JComboBox<>(GROUPS);
    private final JTextField fromField = new JTextField(9);
    private final JTextField toField = new JTextField(9);
    private final JButton apply = UiKit.primaryButton("Apply");
    private final JComboBox<String> format = new JComboBox<>(new String[]{"CSV", "Excel (.xlsx)", "PDF"});
    private final JButton export = UiKit.secondaryButton("Export this table");
    private final JLabel formError = new JLabel(" ");

    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JLabel kTotal = kpi(), kDone = kpi(), kCancel = kpi(), kOpen = kpi(), kValue = kpi();
    private final JLabel breakdown = new JLabel(" ");
    private final BarChart chart = new BarChart();
    private final JTabbedPane tables = new JTabbedPane();
    private final DefaultTableModel trendModel = model("Period", "Created", "Completed", "Cancelled", "Value (PHP)");
    private final DefaultTableModel statusModel = model("Status", "Bookings", "Share of all created");
    private final DefaultTableModel driverModel = model("Driver", "Username", "Assigned", "Delivered", "Failed",
            "Completed", "Cancelled", "Open", "Avg start to delivered");
    private final DefaultTableModel customerModel = model("Customer", "Bookings", "Completed", "Value (PHP)", "Last booking");
    private final DefaultTableModel productModel = model("Product", "Quantity ordered", "Bookings", "Value (PHP)", "Stock now");
    private final DefaultTableModel exceptionModel = model("Exception", "Count", "Where to look");
    private final JLabel notes = new JLabel(" ");

    private ReportService.Report current;   // exactly what is on screen, so exports match it
    private int requestSeq;
    private boolean loadedOnce;

    public AnalyticsPanel() {
        super(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 16, 12, 16));

        preset.addActionListener(e -> applyPreset());
        formError.setForeground(Theme.DANGER);
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.setOpaque(false);
        filters.add(new JLabel("Period:"));
        filters.add(preset);
        filters.add(new JLabel("From"));
        filters.add(fromField);
        filters.add(new JLabel("To"));
        filters.add(toField);
        filters.add(new JLabel("Group by"));
        filters.add(groupBy);
        filters.add(apply);
        JPanel exports = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        exports.setOpaque(false);
        exports.add(new JLabel("Export as"));
        exports.add(format);
        exports.add(export);
        fromField.setToolTipText("YYYY-MM-DD");
        toField.setToolTipText("YYYY-MM-DD");
        groupBy.setToolTipText("How the trend table and chart are grouped (weeks start on Monday)");
        fromField.addActionListener(e -> apply());
        toField.addActionListener(e -> apply());
        apply.addActionListener(e -> apply());
        groupBy.addActionListener(e -> {
            if (loadedOnce) apply();
        });
        export.addActionListener(e -> exportSelected());
        format.setToolTipText("File format for the export");
        export.setToolTipText("Saves the table of the tab that is open, with the period and the definitions");
        setExportEnabled(false);

        JLabel defs = new JLabel("<html>Bookings are counted once, on the day they were created (Asia/Manila, both dates included). "
                + "<b>Completion rate</b> = Completed / all created. <b>Cancellation rate</b> = Cancelled / all created. "
                + "Open bookings count in the total, so recent periods look lower. "
                + "<b>Value</b> = total of bookings that are not Cancelled.</html>");
        defs.setForeground(Theme.MUTED);
        defs.setFont(Theme.SUBTLE);

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{filters, exports, formError, defs}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            north.add(c);
        }
        add(north, BorderLayout.NORTH);

        JPanel kpis = new JPanel(new GridLayout(1, 5, 10, 0));
        kpis.setOpaque(false);
        kpis.add(card("Created", kTotal));
        kpis.add(card("Completed", kDone));
        kpis.add(card("Cancelled", kCancel));
        kpis.add(card("Still open", kOpen));
        kpis.add(card("Value (PHP)", kValue));
        breakdown.setFont(Theme.SUBTLE);
        breakdown.setForeground(Theme.TEXT);

        tables.addTab("Trend", scroll(trendModel));
        tables.addTab("By status", scroll(statusModel));
        tables.addTab("Drivers", scroll(driverModel));
        tables.addTab("Customers", scroll(customerModel));
        tables.addTab("Products", scroll(productModel));
        tables.addTab("Exceptions", scroll(exceptionModel));
        tables.addChangeListener(e -> export.setText("Export " + tables.getTitleAt(tables.getSelectedIndex())));
        export.setText("Export " + tables.getTitleAt(0));
        notes.setForeground(Theme.MUTED);
        notes.setFont(Theme.SUBTLE);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(kpis, BorderLayout.NORTH);
        top.add(breakdown, BorderLayout.CENTER);
        chart.setPreferredSize(new Dimension(100, 190));
        top.add(chart, BorderLayout.SOUTH);
        body.add(top, BorderLayout.NORTH);
        body.add(tables, BorderLayout.CENTER);
        body.add(notes, BorderLayout.SOUTH);

        center.setOpaque(false);
        center.add(state, "state");
        center.add(body, "content");
        add(center, BorderLayout.CENTER);

        preset.setSelectedIndex(1);   // Last 30 days
        applyPreset();
        addHierarchyListener(e -> {
            if (!loadedOnce && isShowing()) {
                loadedOnce = true;
                apply();
            }
        });
    }

    private void applyPreset() {
        LocalDate today = LocalDate.now(TZ);
        switch (preset.getSelectedIndex()) {
            case 0 -> setDates(today.minusDays(6), today);
            case 1 -> setDates(today.minusDays(29), today);
            case 2 -> setDates(today.withDayOfMonth(1), today);
            case 3 -> setDates(today.minusDays(89), today);
            default -> { /* Custom: leave the fields for the admin to type */ }
        }
    }

    private void setDates(LocalDate from, LocalDate to) {
        fromField.setText(from.toString());
        toField.setText(to.toString());
    }

    private ReportService.Grouping grouping() {
        return ReportService.Grouping.values()[Math.max(0, groupBy.getSelectedIndex())];
    }

    private void apply() {
        LocalDate from, to;
        try {
            from = LocalDate.parse(fromField.getText().trim());
            to = LocalDate.parse(toField.getText().trim());
        } catch (DateTimeParseException ex) {
            formError.setText("Enter both dates as YYYY-MM-DD.");
            return;
        }
        String bad = ReportService.validateRange(from, to);
        if (bad != null) {
            formError.setText(bad);
            return;
        }
        formError.setText(" ");
        load(from, to, grouping());
    }

    private void load(LocalDate from, LocalDate to, ReportService.Grouping g) {
        final int seq = ++requestSeq;   // ignore answers that arrive after a newer request
        apply.setEnabled(false);
        setExportEnabled(false);
        state.showLoading("Loading report...");
        cards.show(center, "state");
        new SwingWorker<ReportService.Report, Void>() {
            @Override
            protected ReportService.Report doInBackground() throws Exception {
                return ReportService.run(from, to, g);
            }

            @Override
            protected void done() {
                if (seq != requestSeq) return;
                apply.setEnabled(true);
                try {
                    show(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    current = null;
                    state.showError("Could not load the report",
                            c.getMessage() == null ? c.toString() : c.getMessage(), () -> load(from, to, g));
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    private void show(ReportService.Report r) {
        current = r;
        if (r.total == 0) {
            state.showEmpty("No bookings were created from " + r.from + " to " + r.to,
                    "Try a different period. Rates are not shown because there is nothing to divide by.");
            cards.show(center, "state");
            setExportEnabled(false);
            return;
        }
        kTotal.setText(String.valueOf(r.total));
        kDone.setText(r.completed + "  (" + pct(r.completionRate()) + ")");
        kCancel.setText(r.cancelled + "  (" + pct(r.cancellationRate()) + ")");
        kOpen.setText(String.valueOf(r.open));
        kValue.setText(MONEY.format(r.value));

        StringBuilder b = new StringBuilder("<html>");
        boolean first = true;
        for (Map.Entry<String, Integer> e : r.byStatus.entrySet()) {
            b.append(first ? "" : " &nbsp;\u00b7&nbsp; ").append(esc(e.getKey())).append(" <b>").append(e.getValue()).append("</b>");
            first = false;
        }
        b.append(" &nbsp;&nbsp;|&nbsp;&nbsp; From the mobile app <b>").append(r.fromMobile)
                .append("</b> &nbsp;\u00b7&nbsp; Desktop or older <b>").append(r.fromDesktop).append("</b>");
        b.append(" &nbsp;&nbsp;|&nbsp;&nbsp; Failed deliveries <b>").append(r.failedDeliveries).append("</b>");
        if (r.alerts != null) {
            int sos = r.alerts.getOrDefault("SOS", 0);
            int problems = r.alerts.values().stream().mapToInt(Integer::intValue).sum() - sos;
            b.append(" &nbsp;\u00b7&nbsp; SOS <b>").append(sos).append("</b> &nbsp;\u00b7&nbsp; Problem reports <b>")
                    .append(problems).append("</b>");
            if (r.openAlerts != null) b.append(" &nbsp;\u00b7&nbsp; Alerts not resolved <b>").append(r.openAlerts).append("</b>");
        }
        breakdown.setText(b.append("</html>").toString());

        trendModel.setRowCount(0);
        for (ReportService.DayRow d : r.days) {
            trendModel.addRow(new Object[]{r.grouping.label(d.day), d.total, d.completed, d.cancelled, MONEY.format(d.value)});
        }
        statusModel.setRowCount(0);
        for (Map.Entry<String, Integer> e : r.byStatus.entrySet()) {
            statusModel.addRow(new Object[]{e.getKey(), e.getValue(), pct(ReportService.rate(e.getValue(), r.total))});
        }
        driverModel.setRowCount(0);
        for (ReportService.DriverRow d : r.drivers) {
            driverModel.addRow(new Object[]{d.username == null ? "No driver assigned" : (d.name == null ? "-" : d.name),
                    d.username == null ? "-" : d.username, d.total, d.delivered, d.failed, d.completed, d.cancelled, d.open,
                    avgText(d, r)});
        }
        customerModel.setRowCount(0);
        for (ReportService.CustomerRow c : r.customers) {
            customerModel.addRow(new Object[]{customerName(c), c.bookings, c.completed, MONEY.format(c.value),
                    Theme.formatWhen(c.lastBooking)});
        }
        productModel.setRowCount(0);
        for (ReportService.ProductRow p : r.products) {
            productModel.addRow(new Object[]{p.name, p.quantity, p.bookings, MONEY.format(p.value),
                    p.stockNow == null ? "-" : p.stockNow});
        }
        exceptionModel.setRowCount(0);
        for (List<String> row : exceptionRows(r)) exceptionModel.addRow(row.toArray());

        chart.setData(r);
        StringBuilder n = new StringBuilder("Period " + r.from + " to " + r.to + " (Asia/Manila), grouped by "
                + r.grouping.name().toLowerCase() + ". Periods with no bookings are not listed in the table but show as 0 in the chart."
                + (r.grouping == ReportService.Grouping.DAY ? "" : " The first and last " + r.grouping.name().toLowerCase()
                + " count only the days inside the period."));
        if (r.driversTruncated) n.append("  Drivers: first 500 only.");
        if (r.customersTruncated) n.append("  Customers: top 100 only.");
        if (r.productsTruncated) n.append("  Products: first 500 only.");
        if (!r.deliveryTimesAvailable) n.append("  Delivery times: not available on this database.");
        notes.setText("<html>" + esc(n.toString()) + "</html>");
        cards.show(center, "content");
        setExportEnabled(true);
    }

    private static String avgText(ReportService.DriverRow d, ReportService.Report r) {
        if (!r.deliveryTimesAvailable) return "n/a";
        if (d.username == null || d.timedBookings == 0 || d.avgMinutes == null) return "-";
        return MIN.format(d.avgMinutes) + " min (" + d.timedBookings + ")";
    }

    private static String customerName(ReportService.CustomerRow c) {
        if (c.clientId == null) return "No customer recorded";
        return c.name == null || c.name.isBlank() ? "Customer #" + c.clientId : c.name;
    }

    private static List<List<String>> exceptionRows(ReportService.Report r) {
        List<List<String>> rows = new ArrayList<>();
        if (r.alerts == null) {
            rows.add(List.of("SOS and problem reports", "n/a", "Notifications are not set up on this database"));
        } else if (r.alerts.isEmpty()) {
            rows.add(List.of("SOS and problem reports", "0", "Notifications"));
        } else {
            for (Map.Entry<String, Integer> e : r.alerts.entrySet()) {
                rows.add(List.of("Alert: " + e.getKey(), String.valueOf(e.getValue()), "Notifications"));
            }
        }
        if (r.alerts != null) {
            rows.add(List.of("Alerts not resolved yet", r.openAlerts == null ? "n/a" : String.valueOf(r.openAlerts),
                    r.openAlerts == null ? "Resolving alerts is not set up on this database" : "Notifications (Open alerts)"));
        }
        rows.add(List.of("Failed deliveries", String.valueOf(r.failedDeliveries), "Bookings (delivery status FAILED)"));
        rows.add(List.of("Cancelled bookings", String.valueOf(r.cancelled), "Bookings (status Cancelled)"));
        rows.add(List.of("Waiting for confirmation", String.valueOf(r.byStatus.getOrDefault("Pending", 0)),
                "Bookings (status Pending)"));
        return rows;
    }

    private static String pct(Double v) {
        return v == null ? "n/a" : PCT.format(v) + "%";
    }

    private void setExportEnabled(boolean on) {
        export.setEnabled(on);
    }

    // ---------------- export ----------------

    private ReportTable table(String title) {
        AuthService.Session s = AuthService.current();
        ReportTable t = new ReportTable();
        t.title = title;
        t.meta.add("Period (Asia/Manila, both dates included): " + current.from + " to " + current.to);
        t.meta.add("Generated for: " + (s == null ? "unknown" : s.username));
        t.meta.add("Generated at: " + java.time.ZonedDateTime.now(TZ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " (Asia/Manila)");
        t.meta.add("Definition: bookings are counted once, on the day created. Completion rate = Completed / all created; "
                + "Cancellation rate = Cancelled / all created; n/a when nothing was created.");
        return t;
    }

    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private void exportSelected() {
        if (current == null) return;
        Export e = exportFor(tables.getSelectedIndex());
        save(e.name, e.table);
    }

    /** What a tab exports: the file name (without extension) and the table. */
    static final class Export {
        final String name;
        final ReportTable table;

        Export(String name, ReportTable table) {
            this.name = name;
            this.table = table;
        }
    }

    /** The export of tab i (0 Trend ... 5 Exceptions), built from the same report that is on screen. */
    Export exportFor(int tab) {
        String range = current.from + "_to_" + current.to;
        ReportTable t;
        String name;
        switch (tab) {
            case 1 -> {
                t = table("Bookings by status (status now)");
                t.header = List.of("Status", "Bookings", "Share of all created");
                t.firstNumericCol = 1;
                for (Map.Entry<String, Integer> e : current.byStatus.entrySet()) {
                    t.rows.add(List.of(e.getKey(), String.valueOf(e.getValue()), pct(ReportService.rate(e.getValue(), current.total))));
                }
                t.footer.add(List.of("Total", String.valueOf(current.total)));
                t.footer.add(List.of("From the mobile app", String.valueOf(current.fromMobile)));
                t.footer.add(List.of("Desktop or older", String.valueOf(current.fromDesktop)));
                name = "trimax-bookings-by-status_" + range;
            }
            case 2 -> {
                t = table("Bookings per driver (counts only, not a ranking)");
                t.meta.add("Delivered / Failed = the driver's delivery result. Average start to delivered: from the server's "
                        + "delivery events, only bookings that have both (number of bookings in brackets).");
                t.header = List.of("Driver", "Username", "Assigned", "Delivered", "Failed", "Completed", "Cancelled", "Open",
                        "Avg start to delivered");
                t.firstNumericCol = 2;
                for (ReportService.DriverRow d : current.drivers) {
                    t.rows.add(List.of(d.username == null ? "No driver assigned" : (d.name == null ? "" : d.name),
                            d.username == null ? "" : d.username, String.valueOf(d.total), String.valueOf(d.delivered),
                            String.valueOf(d.failed), String.valueOf(d.completed), String.valueOf(d.cancelled),
                            String.valueOf(d.open), avgText(d, current)));
                }
                if (current.driversTruncated) t.footer.add(List.of("Note", "Only the first 500 drivers are included."));
                name = "trimax-bookings-per-driver_" + range;
            }
            case 3 -> {
                t = table("Customers by bookings");
                t.meta.add("Value excludes cancelled bookings. Top 100 customers by number of bookings.");
                t.header = List.of("Customer", "Bookings", "Completed", "Value (PHP)", "Last booking (Asia/Manila)");
                t.firstNumericCol = 1;
                for (ReportService.CustomerRow c : current.customers) {
                    t.rows.add(List.of(customerName(c), String.valueOf(c.bookings), String.valueOf(c.completed),
                            money(c.value), Theme.formatWhen(c.lastBooking)));
                }
                name = "trimax-customers_" + range;
            }
            case 4 -> {
                t = table("Products ordered");
                t.meta.add("Order lines of bookings that are not Cancelled. Stock now = today's stock, not the stock at the time.");
                t.header = List.of("Product", "Quantity ordered", "Bookings", "Value (PHP)", "Stock now");
                t.firstNumericCol = 1;
                for (ReportService.ProductRow p : current.products) {
                    t.rows.add(List.of(p.name, String.valueOf(p.quantity), String.valueOf(p.bookings), money(p.value),
                            p.stockNow == null ? "" : String.valueOf(p.stockNow)));
                }
                name = "trimax-products_" + range;
            }
            case 5 -> {
                t = table("Exceptions");
                t.meta.add("Alerts = SOS and problem reports sent from the mobile app, by the day they were sent.");
                t.header = List.of("Exception", "Count", "Where to look");
                t.firstNumericCol = 1;
                t.rows.addAll(exceptionRows(current));
                name = "trimax-exceptions_" + range;
            }
            default -> {
                t = table("Bookings per " + current.grouping.name().toLowerCase());
                if (current.grouping != ReportService.Grouping.DAY) {
                    t.meta.add("Weeks start on Monday. The first and last period count only the days inside the range.");
                }
                t.header = List.of("Period", "Created", "Completed", "Cancelled", "Value (PHP, excl. cancelled)");
                t.firstNumericCol = 1;
                for (ReportService.DayRow d : current.days) {
                    t.rows.add(List.of(current.grouping.label(d.day), String.valueOf(d.total), String.valueOf(d.completed),
                            String.valueOf(d.cancelled), money(d.value)));
                }
                t.footer.add(List.of("Total", String.valueOf(current.total), String.valueOf(current.completed),
                        String.valueOf(current.cancelled), money(current.value)));
                t.footer.add(List.of("Completion rate", pct(current.completionRate())));
                t.footer.add(List.of("Cancellation rate", pct(current.cancellationRate())));
                name = "trimax-bookings-per-" + current.grouping.name().toLowerCase() + "_" + range;
            }
        }
        return new Export(name, t);
    }

    private void save(String baseName, ReportTable t) {
        int kind = format.getSelectedIndex();   // 0 CSV, 1 Excel, 2 PDF
        String ext = kind == 0 ? ".csv" : kind == 1 ? ".xlsx" : ".pdf";
        try {
            ExportDirectories.initialize();
            JFileChooser fc = new JFileChooser(ExportDirectories.reportsDirectory().toFile());
            fc.setSelectedFile(new java.io.File(baseName + ext));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;   // asked once (it was asked twice)
            Path p = fc.getSelectedFile().toPath();
            if (!p.getFileName().toString().toLowerCase().endsWith(ext)) {
                p = p.resolveSibling(p.getFileName() + ext);
            }
            if (Files.exists(p) && JOptionPane.showConfirmDialog(this, p.getFileName() + " already exists. Replace it?",
                    "Replace file", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            if (kind == 0) CsvExport.write(p, t.csvRows());
            else if (kind == 1) XlsxExport.write(p, t);
            else PdfExport.write(p, t);
            JOptionPane.showMessageDialog(this, "Saved " + t.rows.size() + " rows to " + p,
                    "Export complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "The file was NOT saved: " + ex.getMessage(),
                    "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- small UI helpers ----------------

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JScrollPane scroll(DefaultTableModel m) {
        JTable t = new JTable(m);
        t.setRowHeight(24);
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
        return new JScrollPane(t);
    }

    private static JLabel kpi() {
        JLabel l = new JLabel("-");
        l.setFont(new Font("SansSerif", Font.BOLD, 20));
        l.setForeground(Theme.TEXT);
        return l;
    }

    private static JPanel card(String title, JLabel value) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(Theme.CARD);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(10, 12, 10, 12)));
        JLabel t = new JLabel(title);
        t.setForeground(Theme.MUTED);
        t.setFont(Theme.SUBTLE);
        p.add(t, BorderLayout.NORTH);
        p.add(value, BorderLayout.CENTER);
        return p;
    }

    private static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    /** Bars of bookings created per period; periods inside the range without bookings are drawn as empty. */
    static final class BarChart extends JComponent {
        private ReportService.Report r;

        void setData(ReportService.Report r) {
            this.r = r;
            repaint();
        }

        /** Every period start from the range's first period to its last (package-private for tests). */
        static List<LocalDate> periods(ReportService.Report r) {
            List<LocalDate> out = new ArrayList<>();
            LocalDate p = r.grouping.startOf(r.from);
            LocalDate last = r.grouping.startOf(r.to);
            while (!p.isAfter(last) && out.size() < 400) {
                out.add(p);
                p = switch (r.grouping) {
                    case WEEK -> p.plusWeeks(1);
                    case MONTH -> p.plusMonths(1);
                    default -> p.plusDays(1);
                };
            }
            return out;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.CARD);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Theme.BORDER);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.setFont(Theme.SUBTLE);
            g.setColor(Theme.TEXT);
            String unit = r == null ? "day" : r.grouping.name().toLowerCase();
            g.drawString("Bookings created per " + unit + " (count)", 12, 18);
            if (r == null || r.days.isEmpty()) {
                g.dispose();
                return;
            }
            List<LocalDate> ps = periods(r);
            int[] counts = new int[ps.size()];
            int max = 1;
            for (ReportService.DayRow d : r.days) {
                int i = ps.indexOf(d.day);
                if (i >= 0) {
                    counts[i] = d.total;
                    max = Math.max(max, d.total);
                }
            }
            int left = 40, right = 12, top = 28, bottom = 24;
            int w = getWidth() - left - right, h = getHeight() - top - bottom;
            g.setColor(Theme.MUTED);
            g.drawLine(left, top, left, top + h);
            g.drawLine(left, top + h, left + w, top + h);
            g.drawString(String.valueOf(max), 8, top + 10);
            g.drawString("0", 24, top + h);
            g.drawString(r.grouping.label(ps.get(0)), left, getHeight() - 8);
            String end = r.grouping.label(ps.get(ps.size() - 1));
            g.drawString(end, left + w - g.getFontMetrics().stringWidth(end), getHeight() - 8);
            double slot = (double) w / ps.size();
            int barW = (int) Math.max(1, Math.min(48, slot * 0.7));
            g.setColor(Theme.BLUE);
            for (int i = 0; i < ps.size(); i++) {
                int bh = (int) Math.round((double) counts[i] / max * (h - 4));
                int x = left + (int) (i * slot + (slot - barW) / 2);
                g.fillRect(x, top + h - bh, barW, bh);
                if (counts[i] > 0 && slot >= 26) {          // value on the bar when there is room
                    String v = String.valueOf(counts[i]);
                    g.setColor(Theme.TEXT);
                    g.drawString(v, x + (barW - g.getFontMetrics().stringWidth(v)) / 2, top + h - bh - 3);
                    g.setColor(Theme.BLUE);
                }
            }
            g.dispose();
        }
    }
}
