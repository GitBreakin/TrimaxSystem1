package com.trimaxsystem;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Analytics & Reports: bookings created in a date range (Asia/Manila days), with completion and
 * cancellation rates, a per-day chart, daily and per-driver tables, and CSV export of exactly what is shown.
 * Formulas are in {@link ReportService} and are repeated on screen and in every export.
 */
public class AnalyticsPanel extends JPanel {

    private static final ZoneId TZ = ZoneId.of(ReportService.TZ);
    private static final String[] PRESETS = {"Last 7 days", "Last 30 days", "This month", "Custom"};
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DecimalFormat PCT = new DecimalFormat("0.0");

    private final JComboBox<String> preset = new JComboBox<>(PRESETS);
    private final JTextField fromField = new JTextField(9);
    private final JTextField toField = new JTextField(9);
    private final JButton apply = UiKit.primaryButton("Apply");
    private final JComboBox<String> format = new JComboBox<>(new String[]{"CSV", "Excel (.xlsx)", "PDF"});
    private final JButton exportDaily = UiKit.secondaryButton("Export per day");
    private final JButton exportDrivers = UiKit.secondaryButton("Export per driver");
    private final JLabel formError = new JLabel(" ");

    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JLabel kTotal = kpi(), kDone = kpi(), kCancel = kpi(), kOpen = kpi(), kValue = kpi();
    private final BarChart chart = new BarChart();
    private final DefaultTableModel dayModel = model("Date", "Created", "Completed", "Cancelled", "Value (PHP)");
    private final DefaultTableModel driverModel = model("Driver", "Username", "Assigned", "Completed", "Cancelled", "Open");
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
        filters.add(apply);
        filters.add(new JLabel("Export as"));
        filters.add(format);
        filters.add(exportDaily);
        filters.add(exportDrivers);
        fromField.setToolTipText("YYYY-MM-DD");
        toField.setToolTipText("YYYY-MM-DD");
        fromField.addActionListener(e -> apply());
        toField.addActionListener(e -> apply());
        apply.addActionListener(e -> apply());
        exportDaily.addActionListener(e -> exportDaily());
        exportDrivers.addActionListener(e -> exportDrivers());
        format.setToolTipText("File format for the export buttons");
        setExportEnabled(false);

        JLabel defs = new JLabel("<html>Bookings are counted on the day they were created (Asia/Manila, both dates included). "
                + "<b>Completion rate</b> = Completed / all created. <b>Cancellation rate</b> = Cancelled / all created. "
                + "Open bookings count in the total, so recent periods look lower. "
                + "<b>Value</b> = total of bookings that are not Cancelled.</html>");
        defs.setForeground(Theme.MUTED);
        defs.setFont(Theme.SUBTLE);

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{filters, formError, defs}) {
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

        JTabbedPane tables = new JTabbedPane();
        tables.addTab("Per day", new JScrollPane(new JTable(dayModel)));
        tables.addTab("Per driver", new JScrollPane(new JTable(driverModel)));
        notes.setForeground(Theme.MUTED);
        notes.setFont(Theme.SUBTLE);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        top.add(kpis, BorderLayout.NORTH);
        chart.setPreferredSize(new Dimension(100, 200));
        top.add(chart, BorderLayout.CENTER);
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
            default -> { /* Custom: leave the fields for the admin to type */ }
        }
    }

    private void setDates(LocalDate from, LocalDate to) {
        fromField.setText(from.toString());
        toField.setText(to.toString());
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
        load(from, to);
    }

    private void load(LocalDate from, LocalDate to) {
        final int seq = ++requestSeq;   // ignore answers that arrive after a newer request
        apply.setEnabled(false);
        setExportEnabled(false);
        state.showLoading("Loading report...");
        cards.show(center, "state");
        new SwingWorker<ReportService.Report, Void>() {
            @Override
            protected ReportService.Report doInBackground() throws Exception {
                return ReportService.run(from, to);
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
                            c.getMessage() == null ? c.toString() : c.getMessage(), () -> load(from, to));
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

        dayModel.setRowCount(0);
        for (ReportService.DayRow d : r.days) {
            dayModel.addRow(new Object[]{d.day, d.total, d.completed, d.cancelled, MONEY.format(d.value)});
        }
        driverModel.setRowCount(0);
        for (ReportService.DriverRow d : r.drivers) {
            driverModel.addRow(new Object[]{d.username == null ? "No driver assigned" : (d.name == null ? "-" : d.name),
                    d.username == null ? "-" : d.username, d.total, d.completed, d.cancelled, d.open});
        }
        chart.setData(r);
        notes.setText("Period " + r.from + " to " + r.to + " (Asia/Manila)."
                + " Days with no bookings are not listed in the table but count as 0 in the chart."
                + (r.driversTruncated ? "  Driver table shows the first 500 only." : ""));
        cards.show(center, "content");
        setExportEnabled(true);
    }

    private static String pct(Double v) {
        return v == null ? "n/a" : PCT.format(v) + "%";
    }

    private void setExportEnabled(boolean on) {
        exportDaily.setEnabled(on);
        exportDrivers.setEnabled(on);
    }

    // ---------------- export ----------------

    private ReportTable table(String title) {
        AuthService.Session s = AuthService.current();
        ReportTable t = new ReportTable();
        t.title = title;
        t.meta.add("Period (Asia/Manila, both dates included): " + current.from + " to " + current.to);
        t.meta.add("Generated for: " + (s == null ? "unknown" : s.username));
        t.meta.add("Generated at: " + java.time.ZonedDateTime.now(TZ));
        t.meta.add("Definition: bookings are counted on the day created. Completion rate = Completed / all created; "
                + "Cancellation rate = Cancelled / all created; n/a when nothing was created.");
        return t;
    }

    private static String money(java.math.BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private void exportDaily() {
        if (current == null) return;
        ReportTable t = table("Bookings per day");
        t.header = List.of("Date", "Created", "Completed", "Cancelled", "Value (PHP, excl. cancelled)");
        t.firstNumericCol = 1;
        for (ReportService.DayRow d : current.days) {
            t.rows.add(List.of(d.day.toString(), String.valueOf(d.total), String.valueOf(d.completed),
                    String.valueOf(d.cancelled), money(d.value)));
        }
        t.footer.add(List.of("Total", String.valueOf(current.total), String.valueOf(current.completed),
                String.valueOf(current.cancelled), money(current.value)));
        t.footer.add(List.of("Completion rate", pct(current.completionRate())));
        t.footer.add(List.of("Cancellation rate", pct(current.cancellationRate())));
        save("trimax-bookings-per-day_" + current.from + "_to_" + current.to, t);
    }

    private void exportDrivers() {
        if (current == null) return;
        ReportTable t = table("Bookings per driver (counts only, not a ranking)");
        t.header = List.of("Driver", "Username", "Assigned", "Completed", "Cancelled", "Open");
        t.firstNumericCol = 2;
        for (ReportService.DriverRow d : current.drivers) {
            t.rows.add(List.of(d.username == null ? "No driver assigned" : (d.name == null ? "" : d.name),
                    d.username == null ? "" : d.username, String.valueOf(d.total), String.valueOf(d.completed),
                    String.valueOf(d.cancelled), String.valueOf(d.open)));
        }
        if (current.driversTruncated) t.footer.add(List.of("Note", "Only the first 500 drivers are included."));
        save("trimax-bookings-per-driver_" + current.from + "_to_" + current.to, t);
    }

    private void save(String baseName, ReportTable t) {
        int kind = format.getSelectedIndex();   // 0 CSV, 1 Excel, 2 PDF
        String ext = kind == 0 ? ".csv" : kind == 1 ? ".xlsx" : ".pdf";
        try {
            JFileChooser fc = new JFileChooser(ExportDirectories.reportsDirectory());
            fc.setSelectedFile(new java.io.File(baseName + ext));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
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

    /** Bars of bookings created per day; zero-booking days inside the range are drawn as empty. */
    private static final class BarChart extends JComponent {
        private ReportService.Report r;

        void setData(ReportService.Report r) {
            this.r = r;
            repaint();
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
            g.drawString("Bookings created per day (count)", 12, 18);
            if (r == null || r.days.isEmpty()) {
                g.dispose();
                return;
            }
            int days = (int) java.time.temporal.ChronoUnit.DAYS.between(r.from, r.to) + 1;
            int[] counts = new int[days];
            int max = 1;
            for (ReportService.DayRow d : r.days) {
                int i = (int) java.time.temporal.ChronoUnit.DAYS.between(r.from, d.day);
                if (i >= 0 && i < days) {
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
            g.drawString(r.from.toString(), left, getHeight() - 8);
            String end = r.to.toString();
            g.drawString(end, left + w - g.getFontMetrics().stringWidth(end), getHeight() - 8);
            double slot = (double) w / days;
            int barW = (int) Math.max(1, Math.min(30, slot * 0.7));
            g.setColor(Theme.BLUE);
            for (int i = 0; i < days; i++) {
                int bh = (int) Math.round((double) counts[i] / max * (h - 4));
                int x = left + (int) (i * slot + (slot - barW) / 2);
                g.fillRect(x, top + h - bh, barW, bh);
            }
            g.dispose();
        }
    }
}
