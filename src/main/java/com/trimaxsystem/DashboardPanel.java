package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

/**
 * Admin Dashboard. Everything on it comes from the database; a section that cannot be loaded says
 * "Unavailable" instead of showing zero.
 *
 * KPI definitions (all counts are of rows in the bookings table):
 *   - Total Bookings: every booking, whatever its status. It is the sum of the status counts below,
 *     so no booking is counted twice.
 *   - Pending / Confirmed / In Progress / Completed / Cancelled: bookings whose status is exactly that value.
 *   - Booking Trends: bookings CREATED per day (all statuses), days counted in {@link #BUSINESS_TZ}.
 *   - Today's Revenue: sum of total_amount of bookings created today, excluding Cancelled.
 *   - Active Drivers / Agents: users with that role and account_status = 'Active'.
 *
 * Refreshes on open, via the Refresh button, and every 30 seconds while the panel is showing.
 */
public class DashboardPanel extends JPanel {

    /** Day boundaries ("today", chart days) follow this time zone, not the database server's. */
    private static final ZoneId BUSINESS_TZ = ZoneId.of("Asia/Manila");
    private static final String TZ_SQL = "Asia/Manila";

    /** Canonical booking statuses (bookings.status CHECK constraint), in display order. */
    private static final String[] STATUSES = {"Pending", "Confirmed", "In Progress", "Completed", "Cancelled"};

    private static final int REFRESH_MS = 30_000;
    private static final String PESO = "₱";
    private static final String UNAVAILABLE = "Unavailable";

    // ---------- SQL ----------
    private static final String SQL_STATUS_COUNTS =
            "SELECT status, COUNT(*) FROM bookings GROUP BY status";

    private static final String SQL_TREND =
            "SELECT (transc_date AT TIME ZONE '" + TZ_SQL + "')::date AS d, COUNT(*) "
                    + "FROM bookings WHERE (transc_date AT TIME ZONE '" + TZ_SQL + "')::date >= ? "
                    + "AND (transc_date AT TIME ZONE '" + TZ_SQL + "')::date <= ? "
                    + "GROUP BY d ORDER BY d";

    private static final String SQL_RECENT =
            "SELECT b.booking_id, c.client_name, "
                    + "to_char(b.transc_date AT TIME ZONE '" + TZ_SQL + "', 'Mon DD, YYYY HH24:MI'), "
                    + "b.status, b.total_amount "
                    + "FROM bookings b JOIN clients c ON c.client_id = b.client_id "
                    + "ORDER BY b.transc_date DESC LIMIT 8";

    private static final String SQL_ACTIVE =
            "SELECT b.booking_id, c.client_name, COALESCE(u.name, '-'), COALESCE(b.vehicle_id, '-') "
                    + "FROM bookings b JOIN clients c ON c.client_id = b.client_id "
                    + "LEFT JOIN users u ON u.username = b.driver_username "
                    + "WHERE b.status = 'In Progress' ORDER BY b.transc_date";

    private static final String SQL_DRIVERS =
            "SELECT u.username, u.name, COALESCE(v.vehicle_id, '-'), COALESCE(v.status, 'No vehicle') "
                    + "FROM users u JOIN roles r ON r.role_id = u.role_id "
                    + "LEFT JOIN vehicles v ON v.username = u.username "
                    + "WHERE r.role_name = 'Driver' AND u.account_status = 'Active' ORDER BY u.username";

    private static final String SQL_CUSTOMERS = "SELECT COUNT(*) FROM clients";

    private static final String SQL_ACTIVE_BY_ROLE =
            "SELECT COUNT(*) FROM users u JOIN roles r ON r.role_id = u.role_id "
                    + "WHERE r.role_name = ? AND u.account_status = 'Active'";

    private static final String SQL_REVENUE_TODAY =
            "SELECT COALESCE(SUM(total_amount), 0) FROM bookings "
                    + "WHERE (transc_date AT TIME ZONE '" + TZ_SQL + "')::date = ? AND status <> 'Cancelled'";

    // ---------- state ----------
    private final DecimalFormat money = new DecimalFormat("#,##0.00");
    private final JLabel lblUpdated = new JLabel(" ");

    private final JLabel[] kpiValues = new JLabel[1 + STATUSES.length];   // Total + one per status
    private final BarChart trendChart = new BarChart();
    private final DonutChart donut = new DonutChart();
    private final JPanel legend = new JPanel();

    private final DefaultTableModel recentModel = readOnlyModel("Booking", "Customer", "Date / Time", "Status", "Amount");
    private final DefaultTableModel activeModel = readOnlyModel("Booking", "Customer", "Driver", "Vehicle");
    private final DefaultTableModel driverModel = readOnlyModel("Username", "Driver", "Vehicle", "Vehicle status");
    private final JLabel[] quickValues = new JLabel[4];

    private boolean monthView = false;
    private boolean customView = false;
    private LocalDate customStart;
    private LocalDate customEnd;
    private final Timer timer;
    private Runnable onFirstLoad;   // called once, after the first refresh finishes (success or failure)

    /** Everything fetched from the database in one refresh. A false *Ok flag means "Unavailable". */
    private static final class Snapshot {
        boolean statusOk, trendOk, recentOk, activeOk, driversOk, quickOk;
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        String[] trendLabels = new String[0];
        long[] trendValues = new long[0];
        LocalDate[] trendDates = new LocalDate[0];
        String trendCaption = "";
        List<Object[]> recent = new ArrayList<>(), active = new ArrayList<>(), drivers = new ArrayList<>();
        String[] quick = new String[4];
    }

    private interface Task {
        void run() throws SQLException;
    }

    /** Runs one section's queries; a failure only marks that section unavailable. */
    private static boolean attempt(Task t) {
        try {
            t.run();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public DashboardPanel() {
        this(null);
    }

    public DashboardPanel(Runnable onFirstLoad) {
        this.onFirstLoad = onFirstLoad;
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout());

        // ---------- TOP BAR: last updated + refresh (the page title is in the main window header) ----------
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        bar.setOpaque(false);
        lblUpdated.setFont(Theme.SUBTLE);
        lblUpdated.setForeground(Theme.MUTED);
        JButton btnRefresh = primaryButton("Refresh");
        btnRefresh.addActionListener(e -> refresh());
        bar.add(lblUpdated);
        bar.add(btnRefresh);
        add(bar, BorderLayout.NORTH);

        // ---------- CONTENT (scrolls if the window is small) ----------
        ScrollablePanel content = new ScrollablePanel(new GridBagLayout());
        content.setBackground(Theme.BACKGROUND);
        content.setBorder(new EmptyBorder(0, 20, 20, 20));

        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.fill = GridBagConstraints.BOTH;
        g.weightx = 1;
        g.insets = new Insets(0, 0, 14, 0);

        g.gridy = 0;
        content.add(buildKpiRow(), g);
        g.gridy = 1;
        content.add(twoColumns(buildTrendCard(), buildStatusCard(), 0.62), g);
        g.gridy = 2;
        content.add(twoColumns(buildRecentCard(), buildActiveCard(), 0.62), g);
        g.gridy = 3;
        g.insets = new Insets(0, 0, 0, 0);
        content.add(twoColumns(buildDriversCard(), buildQuickStatsCard(), 0.62), g);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        add(scroll, BorderLayout.CENTER);

        trendChart.setMessage("Loading...");
        donut.setMessage("Loading...");

        // Auto-refresh only while the panel is actually on screen
        timer = new Timer(REFRESH_MS, e -> refresh());
        refresh();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    // =====================================================
    // LOADING
    // =====================================================

    /** Reloads every section in the background. */
    public void refresh() {
        final boolean month = monthView;
        final boolean custom = customView;
        final LocalDate start = customStart;
        final LocalDate end = customEnd;
        new SwingWorker<Snapshot, Void>() {
            @Override
            protected Snapshot doInBackground() throws Exception {
                return fetch(month, custom, start, end);
            }

            @Override
            protected void done() {
                try {
                    Snapshot s = get();
                    apply(s);
                    boolean all = s.statusOk && s.trendOk && s.recentOk && s.activeOk && s.driversOk && s.quickOk;
                    if (all) {
                        lblUpdated.setForeground(Theme.MUTED);
                        lblUpdated.setText("Last updated: " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
                    } else {
                        lblUpdated.setForeground(Theme.DANGER);
                        lblUpdated.setText("Some sections could not be loaded. Press Refresh to retry.");
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    apply(new Snapshot());   // every flag false: all sections show "Unavailable"
                    lblUpdated.setForeground(Theme.DANGER);
                    lblUpdated.setText("Could not reach the database: " + cause.getMessage());
                    ex.printStackTrace();
                }
                if (onFirstLoad != null) {
                    Runnable r = onFirstLoad;
                    onFirstLoad = null;
                    r.run();
                }
            }
        }.execute();
    }

    private Snapshot fetch(boolean month, boolean custom, LocalDate rangeStart, LocalDate rangeEnd) throws SQLException {
        Snapshot s = new Snapshot();
        LocalDate today = LocalDate.now(BUSINESS_TZ);
        try (Connection conn = DatabaseConnection.getConnection()) {

            s.statusOk = attempt(() -> {
                Map<String, Long> raw = new LinkedHashMap<>();
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(SQL_STATUS_COUNTS)) {
                    while (rs.next()) raw.put(rs.getString(1), rs.getLong(2));
                }
                for (String status : STATUSES) {            // canonical statuses first, in order
                    s.statusCounts.put(status, raw.getOrDefault(status, 0L));
                }
                for (Map.Entry<String, Long> e : raw.entrySet()) {   // anything else, shown as stored
                    if (!s.statusCounts.containsKey(e.getKey())) s.statusCounts.put(e.getKey(), e.getValue());
                }
            });

            s.trendOk = attempt(() -> {
                LocalDate start = custom ? rangeStart : month ? today.withDayOfMonth(1) : today.minusDays(6);
                LocalDate end = custom ? rangeEnd : today;
                int days = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
                s.trendLabels = new String[days];
                s.trendValues = new long[days];
                s.trendDates = new LocalDate[days];
                for (int i = 0; i < days; i++) {
                    LocalDate d = start.plusDays(i);
                    s.trendDates[i] = d;
                    s.trendLabels[i] = custom ? d.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd"))
                            : month ? String.valueOf(d.getDayOfMonth())
                            : d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                }
                try (PreparedStatement ps = conn.prepareStatement(SQL_TREND)) {
                    ps.setDate(1, java.sql.Date.valueOf(start));
                    ps.setDate(2, java.sql.Date.valueOf(end));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int idx = (int) (rs.getDate(1).toLocalDate().toEpochDay() - start.toEpochDay());
                            if (idx >= 0 && idx < days) s.trendValues[idx] = rs.getLong(2);
                        }
                    }
                }
                s.trendCaption = (custom ? start + " to " + end + ", "
                        : month ? "This month, " : "Last 7 days, ")
                        + "bookings created per day (all statuses). "
                        + "Days in Asia/Manila time (UTC+8).";
            });

            s.recentOk = attempt(() -> s.recent = rows(conn, SQL_RECENT, 5, 5, true));
            s.activeOk = attempt(() -> s.active = rows(conn, SQL_ACTIVE, 4, -1, true));
            s.driversOk = attempt(() -> s.drivers = rows(conn, SQL_DRIVERS, 4, -1, false));

            s.quickOk = attempt(() -> {
                s.quick[0] = scalar(conn, SQL_CUSTOMERS, null);
                s.quick[1] = scalar(conn, SQL_ACTIVE_BY_ROLE, "Driver");
                s.quick[2] = scalar(conn, SQL_ACTIVE_BY_ROLE, "Agent");
                try (PreparedStatement ps = conn.prepareStatement(SQL_REVENUE_TODAY)) {
                    ps.setDate(1, java.sql.Date.valueOf(today));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        BigDecimal v = rs.getBigDecimal(1);
                        s.quick[3] = PESO + " " + money.format(v == null ? BigDecimal.ZERO : v);
                    }
                }
            });
        }
        return s;
    }

    private static String scalar(Connection conn, String sql, String param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (param != null) ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return String.valueOf(rs.getLong(1));
            }
        }
    }

    /**
     * Reads rows as strings. moneyCol (1-based) is formatted as money, or -1 for none.
     * With bookingIdFirst, column 1 is a booking id and is shown as "#123".
     */
    private List<Object[]> rows(Connection conn, String sql, int cols, int moneyCol,
                                boolean bookingIdFirst) throws SQLException {
        List<Object[]> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Object[] row = new Object[cols];
                for (int i = 1; i <= cols; i++) {
                    if (i == moneyCol) {
                        BigDecimal v = rs.getBigDecimal(i);
                        row[i - 1] = v == null ? "" : PESO + " " + money.format(v);
                    } else if (i == 1 && bookingIdFirst) {
                        row[i - 1] = "#" + rs.getString(i);   // booking id
                    } else {
                        row[i - 1] = rs.getString(i);
                    }
                }
                list.add(row);
            }
        }
        return list;
    }

    // =====================================================
    // SHOWING THE DATA
    // =====================================================

    private void apply(Snapshot s) {
        // KPI cards
        if (s.statusOk) {
            long total = 0;
            for (long v : s.statusCounts.values()) total += v;
            kpiValues[0].setText(String.valueOf(total));
            for (int i = 0; i < STATUSES.length; i++) {
                kpiValues[i + 1].setText(String.valueOf(s.statusCounts.get(STATUSES[i])));
            }
        } else {
            for (JLabel l : kpiValues) l.setText(UNAVAILABLE);
        }
        for (JLabel l : kpiValues) {
            l.setFont(l.getText().equals(UNAVAILABLE) ? new Font("SansSerif", Font.BOLD, 15) : KPI_FONT);
        }

        // Trend chart
        if (s.trendOk) trendChart.setData(s.trendLabels, s.trendValues, s.trendDates, s.trendCaption);
        else trendChart.setMessage(UNAVAILABLE);

        // Status donut + legend
        legend.removeAll();
        if (s.statusOk) {
            donut.setData(s.statusCounts);
            long total = 0;
            for (long v : s.statusCounts.values()) total += v;
            for (Map.Entry<String, Long> e : s.statusCounts.entrySet()) {
                double pct = total == 0 ? 0 : e.getValue() * 100.0 / total;
                JLabel row = new JLabel(e.getKey() + "   " + e.getValue() + "  (" + String.format(Locale.ENGLISH, "%.1f", pct) + "%)");
                row.setIcon(new DotIcon(Theme.statusColor(e.getKey())));
                row.setIconTextGap(8);
                row.setFont(Theme.SUBTLE);
                row.setForeground(Theme.TEXT);
                row.setBorder(new EmptyBorder(3, 0, 3, 0));
                legend.add(row);
            }
        } else {
            donut.setMessage(UNAVAILABLE);
        }
        legend.revalidate();
        legend.repaint();

        // Tables
        fill(recentModel, s.recentOk, s.recent, "No bookings yet.");
        fill(activeModel, s.activeOk, s.active, "No bookings are in progress.");
        fill(driverModel, s.driversOk, s.drivers, "No active drivers.");

        // Quick stats
        for (int i = 0; i < quickValues.length; i++) {
            quickValues[i].setText(s.quickOk ? s.quick[i] : UNAVAILABLE);
        }
    }

    private void fill(DefaultTableModel model, boolean ok, List<Object[]> rows, String emptyText) {
        model.setRowCount(0);
        int cols = model.getColumnCount();
        if (!ok || rows.isEmpty()) {
            Object[] msg = new Object[cols];
            msg[0] = ok ? emptyText : UNAVAILABLE + " - could not load.";
            model.addRow(msg);
            return;
        }
        for (Object[] r : rows) model.addRow(r);
    }

    // =====================================================
    // BUILDING THE UI
    // =====================================================

    private static final Font KPI_FONT = new Font("SansSerif", Font.BOLD, 28);

    private JComponent buildKpiRow() {
        String[] labels = {"Total Bookings", "Pending", "Confirmed", "In Progress", "Completed", "Cancelled"};
        Color[] accents = {Theme.NAVY, Theme.statusColor("Pending"), Theme.statusColor("Confirmed"),
                Theme.statusColor("In Progress"), Theme.statusColor("Completed"), Theme.statusColor("Cancelled")};
        String[] tips = {
                "Every booking, whatever its status.",
                "Bookings with status Pending.",
                "Bookings with status Confirmed.",
                "Bookings with status In Progress.",
                "Bookings with status Completed.",
                "Bookings with status Cancelled."};

        JPanel row = new JPanel(new GridLayout(1, labels.length, 12, 0));
        row.setOpaque(false);
        for (int i = 0; i < labels.length; i++) {
            JLabel value = new JLabel("...");
            value.setFont(KPI_FONT);
            value.setForeground(Theme.TEXT);
            kpiValues[i] = value;

            JLabel title = new JLabel(labels[i]);
            title.setFont(new Font("SansSerif", Font.BOLD, 13));
            title.setForeground(Theme.MUTED);
            JLabel sub = new JLabel("All time");
            sub.setFont(new Font("SansSerif", Font.PLAIN, 11));
            sub.setForeground(Theme.MUTED);

            JPanel card = new JPanel(new BorderLayout(0, 4));
            card.setBackground(Theme.CARD);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(4, 0, 0, 0, accents[i]),
                            new EmptyBorder(10, 14, 10, 14))));
            card.add(title, BorderLayout.NORTH);
            card.add(value, BorderLayout.CENTER);
            card.add(sub, BorderLayout.SOUTH);
            card.setToolTipText(tips[i]);
            row.add(card);
        }
        return row;
    }

    private JComponent buildTrendCard() {
        JToggleButton week = toggle("Last 7 days", true);
        JToggleButton month = toggle("This month", false);
        JToggleButton custom = toggle("Custom range", false);
        ButtonGroup group = new ButtonGroup();
        group.add(week);
        group.add(month);
        group.add(custom);
        week.addActionListener(e -> {
            monthView = false;
            customView = false;
            refresh();
        });
        month.addActionListener(e -> {
            monthView = true;
            customView = false;
            refresh();
        });
        custom.addActionListener(e -> showCustomRangeDialog(week, month, custom));

        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        toggles.setOpaque(false);
        toggles.add(week);
        toggles.add(month);
        toggles.add(custom);

        trendChart.setPreferredSize(new Dimension(400, 260));
        return card("Booking Trends", toggles, trendChart);
    }

    private void showCustomRangeDialog(JToggleButton week, JToggleButton month, JToggleButton custom) {
        LocalDate today = LocalDate.now(BUSINESS_TZ);
        LocalDate defaultStart = customStart != null ? customStart : today.minusDays(6);
        LocalDate defaultEnd = customEnd != null ? customEnd : today;
        JTextField startField = dateField(defaultStart);
        JTextField endField = dateField(defaultEnd);

        JPanel fields = new JPanel(new GridLayout(2, 2, 10, 10));
        fields.setBackground(Theme.CARD);
        fields.add(dateLabel("Start date"));
        fields.add(startField);
        fields.add(dateLabel("End date"));
        fields.add(endField);
        fields.setBorder(new EmptyBorder(12, 12, 12, 12));

        while (JOptionPane.showConfirmDialog(this, fields, "Custom booking date range",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                LocalDate start = LocalDate.parse(startField.getText().trim());
                LocalDate end = LocalDate.parse(endField.getText().trim());
                if (end.isBefore(start)) {
                    throw new IllegalArgumentException("End date must be on or after the start date.");
                }
                if (end.toEpochDay() - start.toEpochDay() > 365) {
                    throw new IllegalArgumentException("Choose a date range of no more than one year.");
                }
                customStart = start;
                customEnd = end;
                monthView = false;
                customView = true;
                refresh();
                return;
            } catch (java.time.format.DateTimeParseException ex) {
                JOptionPane.showMessageDialog(this, "Enter both dates in YYYY-MM-DD format.",
                        "Invalid date", JOptionPane.ERROR_MESSAGE);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(),
                        "Invalid date range", JOptionPane.ERROR_MESSAGE);
            }
        }

        if (customView) custom.setSelected(true);
        else if (monthView) month.setSelected(true);
        else week.setSelected(true);
    }

    private static JTextField dateField(LocalDate value) {
        JTextField field = new JTextField(value.toString(), 10);
        field.setFont(new Font("SansSerif", Font.PLAIN, 13));
        field.setForeground(Theme.TEXT);
        field.setBackground(Theme.CARD);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(7, 9, 7, 9)));
        return field;
    }

    private static JLabel dateLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("SansSerif", Font.BOLD, 12));
        label.setForeground(Theme.TEXT);
        return label;
    }

    private JComponent buildStatusCard() {
        legend.setOpaque(false);
        legend.setLayout(new BoxLayout(legend, BoxLayout.Y_AXIS));
        donut.setPreferredSize(new Dimension(190, 190));

        JPanel body = new JPanel(new BorderLayout(14, 0));
        body.setOpaque(false);
        body.add(donut, BorderLayout.WEST);
        JPanel legendHolder = new JPanel(new GridBagLayout());   // centres the legend vertically
        legendHolder.setOpaque(false);
        legendHolder.add(legend);
        body.add(legendHolder, BorderLayout.CENTER);
        return card("Booking Status", null, body);
    }

    private JComponent buildRecentCard() {
        return card("Recent Bookings", null, tableScroll(makeTable(recentModel, 3, 4)));
    }

    private JComponent buildActiveCard() {
        return card("Active Deliveries (In Progress)", null, tableScroll(makeTable(activeModel, -1, -1)));
    }

    private JComponent buildDriversCard() {
        return card("Driver Status", null, tableScroll(makeTable(driverModel, -1, -1)));
    }

    private JComponent buildQuickStatsCard() {
        String[] labels = {"Total Customers", "Active Drivers", "Active Agents", "Today's Revenue"};
        String[] tips = {
                "Rows in the clients table.",
                "Users with the Driver role and an Active account.",
                "Users with the Agent role and an Active account.",
                "Total of bookings created today (Asia/Manila), excluding Cancelled."};
        JPanel grid = new JPanel(new GridLayout(2, 2, 10, 10));
        grid.setOpaque(false);
        for (int i = 0; i < labels.length; i++) {
            JLabel title = new JLabel(labels[i]);
            title.setFont(Theme.SUBTLE);
            title.setForeground(Theme.MUTED);
            JLabel value = new JLabel("...");
            value.setFont(new Font("SansSerif", Font.BOLD, 20));
            value.setForeground(Theme.TEXT);
            quickValues[i] = value;

            JPanel cell = new JPanel(new BorderLayout(0, 4));
            cell.setBackground(Theme.BACKGROUND);
            cell.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(10, 12, 10, 12)));
            cell.add(title, BorderLayout.NORTH);
            cell.add(value, BorderLayout.CENTER);
            cell.setToolTipText(tips[i]);
            grid.add(cell);
        }
        return card("Quick Stats", null, grid);
    }

    /** A white rounded-looking card with a title (and optional controls) on top. */
    private JPanel card(String title, JComponent headerRight, JComponent body) {
        JLabel t = new JLabel(title);
        t.setFont(new Font("SansSerif", Font.BOLD, 16));
        t.setForeground(Theme.TEXT);

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(new EmptyBorder(0, 0, 10, 0));
        head.add(t, BorderLayout.WEST);
        if (headerRight != null) head.add(headerRight, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(14, 16, 14, 16)));
        p.add(head, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    /** Two cards side by side; the left one gets leftShare of the width. */
    private JComponent twoColumns(JComponent left, JComponent right, double leftShare) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        g.gridy = 0;
        g.gridx = 0;
        g.weightx = leftShare;
        g.insets = new Insets(0, 0, 0, 7);
        row.add(left, g);
        g.gridx = 1;
        g.weightx = 1 - leftShare;
        g.insets = new Insets(0, 7, 0, 0);
        row.add(right, g);
        return row;
    }

    private JScrollPane tableScroll(JTable table) {
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        sp.getViewport().setBackground(Theme.CARD);
        sp.setPreferredSize(new Dimension(300, 230));
        return sp;
    }

    private static JButton primaryButton(String text) {
        JButton b = new JButton(text);
        b.setPreferredSize(new Dimension(100, 32));
        b.setBackground(Theme.BLUE);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorder(BorderFactory.createLineBorder(Theme.BLUE_DARK));
        return b;   // stays focusable so it can be used from the keyboard
    }

    private static JToggleButton toggle(String text, boolean selected) {
        JToggleButton b = new JToggleButton(text, selected);
        b.setFont(new Font("SansSerif", Font.PLAIN, 12));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(4, 10, 4, 10)));
        Runnable style = () -> {
            b.setBackground(b.isSelected() ? Theme.BLUE : Theme.CARD);
            b.setForeground(b.isSelected() ? Color.WHITE : Theme.TEXT);
        };
        b.addChangeListener(e -> style.run());
        style.run();
        return b;
    }

    private static DefaultTableModel readOnlyModel(String... cols) {
        return new DefaultTableModel(null, cols) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    /** statusCol / moneyCol are 0-based column indexes, or -1 when the table has none. */
    private JTable makeTable(DefaultTableModel model, int statusCol, int moneyCol) {
        JTable t = new JTable(model);
        t.setRowHeight(30);
        t.setBackground(Theme.CARD);
        t.setForeground(Theme.TEXT);
        t.setSelectionBackground(Color.decode("#DBEAFE"));
        t.setSelectionForeground(Theme.TEXT);
        t.setShowHorizontalLines(true);
        t.setShowVerticalLines(false);
        t.setGridColor(Theme.BORDER);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setFillsViewportHeight(true);
        t.setFont(new Font("SansSerif", Font.PLAIN, 13));
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        DefaultTableCellRenderer plain = new DefaultTableCellRenderer();
        plain.setHorizontalAlignment(SwingConstants.LEFT);
        plain.setBorder(new EmptyBorder(0, 8, 0, 8));
        t.setDefaultRenderer(Object.class, plain);

        if (statusCol >= 0) {
            // Status is always written out as text; the colour is only a second cue.
            t.getColumnModel().getColumn(statusCol).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean sel,
                                                               boolean focus, int row, int col) {
                    super.getTableCellRendererComponent(table, value, sel, false, row, col);
                    String status = value == null ? "" : value.toString();
                    setForeground(Theme.statusColor(status));
                    setFont(getFont().deriveFont(Font.BOLD));
                    setBorder(new EmptyBorder(0, 8, 0, 8));
                    return this;
                }
            });
        }
        if (moneyCol >= 0) {
            DefaultTableCellRenderer right = new DefaultTableCellRenderer();
            right.setHorizontalAlignment(SwingConstants.RIGHT);
            right.setBorder(new EmptyBorder(0, 8, 0, 12));
            t.getColumnModel().getColumn(moneyCol).setCellRenderer(right);
        }

        JTableHeader header = t.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(100, 32));
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, false, false, row, column);
                setHorizontalAlignment(column == moneyCol ? SwingConstants.RIGHT : SwingConstants.LEFT);
                setBackground(Theme.BACKGROUND);
                setForeground(Theme.MUTED);
                setFont(new Font("SansSerif", Font.BOLD, 12));
                setOpaque(true);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                        new EmptyBorder(0, 8, 0, column == moneyCol ? 12 : 8)));
                return this;
            }
        });
        return t;
    }

    // =====================================================
    // SMALL CUSTOM COMPONENTS
    // =====================================================

    /** A small filled circle used in the legend. */
    private static final class DotIcon implements Icon {
        private final Color color;

        DotIcon(Color color) {
            this.color = color;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y + 1, 10, 10);
            g2.dispose();
        }

        public int getIconWidth()  { return 12; }
        public int getIconHeight() { return 12; }
    }

    /** Vertical bar chart with a value label on every bar and horizontal grid lines. */
    private static final class BarChart extends JPanel {
        private String[] labels = new String[0];
        private long[] values = new long[0];
        private LocalDate[] dates = new LocalDate[0];
        private String caption = "";
        private String message;   // when set, shown instead of the chart
        private int hoveredBar = -1;

        BarChart() {
            setOpaque(false);
            setToolTipText("");
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int bar = barAt(e.getPoint());
                    if (bar != hoveredBar) {
                        hoveredBar = bar;
                        repaint();
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseExited(MouseEvent e) {
                    hoveredBar = -1;
                    repaint();
                }
            });
        }

        void setData(String[] labels, long[] values, LocalDate[] dates, String caption) {
            this.labels = labels;
            this.values = values;
            this.dates = dates;
            this.caption = caption;
            hoveredBar = -1;
            long sum = 0;
            for (long v : values) sum += v;
            this.message = sum == 0 ? "No bookings in this period." : null;
            repaint();
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int bar = barAt(event.getPoint());
            if (bar < 0 || bar >= dates.length) return null;
            String date = dates[bar].format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH));
            String day = dates[bar].getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            return "<html><b>" + date + "</b><br>" + day + "<br>"
                    + values[bar] + " Bookings</html>";
        }

        private int barAt(Point point) {
            if (message != null || values.length == 0) return -1;
            long max = 0;
            for (long value : values) max = Math.max(max, value);
            long step = niceStep(Math.max(1, (max + 3) / 4));
            long top = step * 4;
            int left = 36, right = 8, topPad = 14, bottomPad = 22 + 22;
            int plotW = getWidth() - left - right, plotH = getHeight() - topPad - bottomPad;
            if (plotW <= 0 || plotH <= 0) return -1;
            double slot = plotW / (double) values.length;
            int barW = (int) Math.max(4, Math.min(48, slot * 0.6));
            int baseline = topPad + plotH;
            for (int i = 0; i < values.length; i++) {
                int cx = left + (int) Math.round(slot * i + slot / 2);
                int barHeight = (int) Math.round(plotH * values[i] / (double) top);
                int x = cx - barW / 2;
                int y = baseline - barHeight;
                Rectangle hitArea = new Rectangle(x, barHeight == 0 ? baseline - 6 : y,
                        barW, Math.max(barHeight, 6));
                if (hitArea.contains(point)) return i;
            }
            return -1;
        }

        void setMessage(String message) {
            this.message = message;
            hoveredBar = -1;
            repaint();
        }

        private static long niceStep(long n) {
            long mag = 1;
            while (mag * 10 <= n) mag *= 10;
            for (long m : new long[]{1, 2, 5, 10}) {
                if (m * mag >= n) return m * mag;
            }
            return 10 * mag;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Font small = new Font("SansSerif", Font.PLAIN, 11);
            g2.setFont(small);
            FontMetrics fm = g2.getFontMetrics();

            int captionH = 22;
            if (message != null) {
                g2.setColor(Theme.MUTED);
                g2.setFont(Theme.SUBTLE);
                String m = message;
                g2.drawString(m, (w - g2.getFontMetrics().stringWidth(m)) / 2, h / 2);
                g2.dispose();
                return;
            }

            long max = 0;
            for (long v : values) max = Math.max(max, v);
            long step = niceStep(Math.max(1, (max + 3) / 4));
            long top = step * 4;

            int left = 36, right = 8, topPad = 14, bottomPad = 22 + captionH;
            int plotW = w - left - right, plotH = h - topPad - bottomPad;
            if (plotW <= 0 || plotH <= 0) {
                g2.dispose();
                return;
            }

            // grid + y labels
            for (int i = 0; i <= 4; i++) {
                int y = topPad + plotH - (int) Math.round(plotH * (i * step) / (double) top);
                g2.setColor(Theme.BORDER);
                g2.drawLine(left, y, left + plotW, y);
                g2.setColor(Theme.MUTED);
                String s = String.valueOf(i * step);
                g2.drawString(s, left - 6 - fm.stringWidth(s), y + fm.getAscent() / 2 - 1);
            }

            // bars
            int n = values.length;
            double slot = plotW / (double) n;
            int barW = (int) Math.max(4, Math.min(48, slot * 0.6));
            int labelEvery = slot < 22 ? 2 : 1;           // keep x labels from overlapping
            boolean valueLabels = slot >= 20;
            for (int i = 0; i < n; i++) {
                int cx = left + (int) Math.round(slot * i + slot / 2);
                int bh = (int) Math.round(plotH * values[i] / (double) top);
                int x = cx - barW / 2, y = topPad + plotH - bh;
                g2.setColor(i == hoveredBar ? Theme.BLUE_DARK : Theme.BLUE);
                if (bh > 0) g2.fillRoundRect(x, y, barW, bh, 4, 4);
                if (i == hoveredBar) {
                    g2.setColor(Theme.BLUE_DARK);
                    g2.drawRoundRect(x, y, barW, Math.max(bh, 1), 4, 4);
                }
                if (valueLabels && values[i] > 0) {
                    g2.setColor(Theme.TEXT);
                    String s = String.valueOf(values[i]);
                    g2.drawString(s, cx - fm.stringWidth(s) / 2, y - 3);
                }
                if (i % labelEvery == 0 || i == n - 1) {
                    g2.setColor(Theme.MUTED);
                    g2.drawString(labels[i], cx - fm.stringWidth(labels[i]) / 2, topPad + plotH + 15);
                }
            }

            g2.setColor(Theme.MUTED);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g2.drawString(caption, left, h - 6);
            g2.dispose();
        }
    }

    /** Donut chart of booking counts per status; the total is written in the middle. */
    private static final class DonutChart extends JPanel {
        private Map<String, Long> counts = new LinkedHashMap<>();
        private String message;

        DonutChart() {
            setOpaque(false);
        }

        void setData(Map<String, Long> counts) {
            this.counts = counts;
            long total = 0;
            for (long v : counts.values()) total += v;
            this.message = total == 0 ? "No bookings yet." : null;
            repaint();
        }

        void setMessage(String message) {
            this.message = message;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight()) - 8;
            int x = (getWidth() - size) / 2, y = (getHeight() - size) / 2;

            if (message != null) {
                g2.setColor(Theme.MUTED);
                g2.setFont(Theme.SUBTLE);
                String m = message;
                g2.drawString(m, (getWidth() - g2.getFontMetrics().stringWidth(m)) / 2, getHeight() / 2);
                g2.dispose();
                return;
            }

            long total = 0;
            for (long v : counts.values()) total += v;
            double start = 90;   // 12 o'clock, going clockwise
            for (Map.Entry<String, Long> e : counts.entrySet()) {
                if (e.getValue() == 0) continue;
                double extent = -360.0 * e.getValue() / total;
                g2.setColor(Theme.statusColor(e.getKey()));
                g2.fill(new Arc2D.Double(x, y, size, size, start, extent, Arc2D.PIE));
                start += extent;
            }
            // white separators between slices, then cut the hole
            g2.setColor(Theme.CARD);
            g2.setStroke(new BasicStroke(2f));
            start = 90;
            for (Map.Entry<String, Long> e : counts.entrySet()) {
                if (e.getValue() == 0) continue;
                double a = Math.toRadians(start);
                g2.drawLine(x + size / 2, y + size / 2,
                        x + size / 2 + (int) Math.round(Math.cos(a) * size / 2),
                        y + size / 2 - (int) Math.round(Math.sin(a) * size / 2));
                start += -360.0 * e.getValue() / total;
            }
            int hole = (int) (size * 0.58);
            Area ring = new Area(new Ellipse2D.Double(x + (size - hole) / 2.0, y + (size - hole) / 2.0, hole, hole));
            g2.fill(ring);

            g2.setColor(Theme.TEXT);
            g2.setFont(new Font("SansSerif", Font.BOLD, 24));
            String t = String.valueOf(total);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(t, x + (size - fm.stringWidth(t)) / 2, y + size / 2 + 2);
            g2.setColor(Theme.MUTED);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
            String sub = "Total Bookings";
            fm = g2.getFontMetrics();
            g2.drawString(sub, x + (size - fm.stringWidth(sub)) / 2, y + size / 2 + 18);
            g2.dispose();
        }
    }

    /** A panel that always matches the scroll pane's width (so only vertical scrolling happens). */
    private static class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel(LayoutManager lm) {
            super(lm);
        }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 64; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
