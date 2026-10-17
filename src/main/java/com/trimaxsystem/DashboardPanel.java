package com.trimaxsystem;

import java.awt.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

/**
 * Admin Dashboard: summary cards + Today's Booking Summary, Active Deliveries,
 * Driver Status and Recent Activity. Refreshes on open, via the Refresh
 * button, and every 30 seconds while the panel is showing.
 *
 * Cards whose sql is null are not tracked by the current database schema and
 * show "N/A". Put a query in CARDS later and they will start working.
 */
public class DashboardPanel extends JPanel {

    private static final Color DARK       = Color.decode("#45494D");
    private static final Color DARK_LINE  = Color.decode("#5E6368");
    private static final Color TEXT_LIGHT = Color.decode("#D1D5DB");
    private static final Color BLUE       = Color.decode("#2563EB");
    private static final Color HEADING    = Color.decode("#1E293B");
    private static final Color BORDER_CLR = Color.decode("#555555");
    private static final Color FIELD_BG   = Color.decode("#F4F6F8");
    private static final Color MUTED      = Color.decode("#6B7280");
    private static final Color RED        = Color.decode("#DC2626");

    private static final int REFRESH_MS = 30_000;

    // Shared SQL fragments
    private static final String DRIVER_JOIN =
            "FROM users u JOIN roles r ON r.role_id = u.role_id WHERE r.role_name = 'Driver' ";

    /** One dashboard card. sql = null means "not available in the current schema". */
    private static final class Card {
        final String label;
        final String sql;
        final boolean money;

        Card(String label, String sql, boolean money) {
            this.label = label;
            this.sql = sql;
            this.money = money;
        }
    }

    private static final Card[] CARDS = {
            new Card("Total Bookings",
                    "SELECT COUNT(*) FROM bookings", false),
            new Card("Pending Bookings",
                    "SELECT COUNT(*) FROM bookings WHERE status = 'Pending'", false),
            // No 'Approved' status exists: treated as Pending bookings that already have a driver.
            new Card("Approved Bookings",
                    "SELECT COUNT(*) FROM bookings WHERE status = 'Pending' AND driver_username IS NOT NULL", false),
            new Card("Assigned Bookings",
                    "SELECT COUNT(*) FROM bookings WHERE driver_username IS NOT NULL "
                            + "AND status IN ('Pending', 'In Transit')", false),
            new Card("Active Deliveries",
                    "SELECT COUNT(*) FROM bookings WHERE status = 'In Transit'", false),
            // No delivered-at column: uses the booking date.
            new Card("Delivered Today",
                    "SELECT COUNT(*) FROM bookings WHERE status = 'Delivered' "
                            + "AND transc_date::date = CURRENT_DATE", false),
            new Card("Declined Bookings", null, false),
            new Card("Cancelled Bookings",
                    "SELECT COUNT(*) FROM bookings WHERE status = 'Cancelled'", false),
            new Card("Failed Deliveries", null, false),
            new Card("Active Drivers",
                    "SELECT COUNT(*) " + DRIVER_JOIN + "AND u.account_status = 'Active'", false),
            // No login tracking: "online" = active driver whose vehicle is Available or On Delivery.
            new Card("Online Drivers",
                    "SELECT COUNT(DISTINCT u.username) " + DRIVER_JOIN + "AND u.account_status = 'Active' "
                            + "AND EXISTS (SELECT 1 FROM vehicles v WHERE v.username = u.username "
                            + "AND v.status IN ('Available', 'On Delivery'))", false),
            new Card("Offline Drivers",
                    "SELECT COUNT(*) " + DRIVER_JOIN + "AND u.account_status = 'Active' "
                            + "AND NOT EXISTS (SELECT 1 FROM vehicles v WHERE v.username = u.username "
                            + "AND v.status IN ('Available', 'On Delivery'))", false),
            // Riders = every user with the Driver role, any account status.
            new Card("Registered Riders",
                    "SELECT COUNT(*) " + DRIVER_JOIN, false),
            new Card("Total Customers",
                    "SELECT COUNT(*) FROM clients", false),
            new Card("Today's Revenue",
                    "SELECT COALESCE(SUM(total_amount), 0) FROM bookings "
                            + "WHERE transc_date::date = CURRENT_DATE AND status <> 'Cancelled'", true),
            new Card("Pending Problems", null, false)
    };

    private static final String SQL_TODAY =
            "SELECT b.booking_id, c.client_name, b.total_amount, b.status, to_char(b.transc_date, 'HH24:MI') "
                    + "FROM bookings b JOIN clients c ON c.client_id = b.client_id "
                    + "WHERE b.transc_date::date = CURRENT_DATE ORDER BY b.transc_date DESC";

    private static final String SQL_ACTIVE =
            "SELECT b.booking_id, c.client_name, c.address, COALESCE(u.name, '-'), COALESCE(b.vehicle_id, '-') "
                    + "FROM bookings b JOIN clients c ON c.client_id = b.client_id "
                    + "LEFT JOIN users u ON u.username = b.driver_username "
                    + "WHERE b.status = 'In Transit' ORDER BY b.transc_date";

    private static final String SQL_DRIVERS =
            "SELECT u.username, u.name, COALESCE(v.vehicle_id, '-'), COALESCE(v.status, 'No vehicle') "
                    + "FROM users u JOIN roles r ON r.role_id = u.role_id "
                    + "LEFT JOIN vehicles v ON v.username = u.username "
                    + "WHERE r.role_name = 'Driver' AND u.account_status = 'Active' ORDER BY u.username";

    private static final String SQL_RECENT =
            "SELECT to_char(b.transc_date, 'YYYY-MM-DD HH24:MI'), "
                    + "'Booking #' || b.booking_id || ' for ' || c.client_name, b.status "
                    + "FROM bookings b JOIN clients c ON c.client_id = b.client_id "
                    + "ORDER BY b.transc_date DESC LIMIT 10";

    private final DecimalFormat money = new DecimalFormat("#,##0.00");
    private final JLabel[] cardValues = new JLabel[CARDS.length];
    private final JLabel lblUpdated = new JLabel(" ");

    private final DefaultTableModel todayModel  = readOnlyModel("Booking ID", "Client", "Total", "Status", "Time");
    private final DefaultTableModel activeModel = readOnlyModel("Booking ID", "Client", "Address", "Driver", "Vehicle");
    private final DefaultTableModel driverModel = readOnlyModel("Username", "Driver", "Vehicle", "Status");
    private final DefaultTableModel recentModel = readOnlyModel("Date / Time", "Activity", "Status");

    private final Timer timer;

    /** Everything fetched from the database in one refresh. */
    private static final class Snapshot {
        final String[] cards = new String[CARDS.length];
        List<Object[]> today, active, drivers, recent;
    }

    public DashboardPanel() {
        setBackground(Color.WHITE);
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        // ---------- HEADER ----------
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(makeHeading("Admin Dashboard", 26), BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        lblUpdated.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblUpdated.setForeground(MUTED);
        JButton btnRefresh = new JButton("Refresh");
        btnRefresh.setPreferredSize(new Dimension(100, 32));
        btnRefresh.setBackground(BLUE);
        btnRefresh.setForeground(Color.WHITE);
        btnRefresh.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btnRefresh.setFocusable(false);
        btnRefresh.setOpaque(true);
        btnRefresh.setContentAreaFilled(true);
        btnRefresh.setBorder(BorderFactory.createLineBorder(HEADING));
        btnRefresh.addActionListener(e -> refresh());
        right.add(lblUpdated);
        right.add(btnRefresh);
        header.add(right, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // ---------- CONTENT (scrolls if the window is small) ----------
        ScrollablePanel content = new ScrollablePanel(new BorderLayout(0, 12));
        content.setBackground(Color.WHITE);

        // Cards: 4 columns
        JPanel cards = new JPanel(new GridLayout(0, 4, 10, 10));
        cards.setOpaque(false);
        for (int i = 0; i < CARDS.length; i++) {
            cards.add(makeCard(i));
        }
        content.add(cards, BorderLayout.NORTH);

        // Sections: 2 x 2
        JPanel sections = new JPanel(new GridLayout(2, 2, 12, 12));
        sections.setOpaque(false);
        sections.setPreferredSize(new Dimension(100, 480));
        sections.add(makeSection("Today's Booking Summary", todayModel));
        sections.add(makeSection("Active Deliveries", activeModel));
        sections.add(makeSection("Driver Status", driverModel));
        sections.add(makeSection("Recent Activity", recentModel));
        content.add(sections, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(Color.WHITE);
        add(scroll, BorderLayout.CENTER);

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

    // ---------- LOADING ----------

    /** Reloads all cards and tables in the background. */
    public void refresh() {
        new SwingWorker<Snapshot, Void>() {
            @Override
            protected Snapshot doInBackground() throws Exception {
                return fetch();
            }

            @Override
            protected void done() {
                try {
                    apply(get());
                    lblUpdated.setForeground(MUTED);
                    lblUpdated.setText("Last updated: " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    lblUpdated.setForeground(RED);
                    lblUpdated.setText("Could not refresh: " + cause.getMessage());
                    ex.printStackTrace();
                }
            }
        }.execute();
    }

    private Snapshot fetch() throws SQLException {
        Snapshot s = new Snapshot();
        try (Connection conn = DatabaseConnection.getConnection()) {
            for (int i = 0; i < CARDS.length; i++) {
                Card c = CARDS[i];
                if (c.sql == null) {
                    s.cards[i] = "N/A";
                    continue;
                }
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(c.sql)) {
                    rs.next();
                    if (c.money) {
                        BigDecimal v = rs.getBigDecimal(1);
                        s.cards[i] = money.format(v == null ? BigDecimal.ZERO : v);
                    } else {
                        s.cards[i] = String.valueOf(rs.getLong(1));
                    }
                }
            }
            s.today   = rows(conn, SQL_TODAY, 5, 3);
            s.active  = rows(conn, SQL_ACTIVE, 5, -1);
            s.drivers = rows(conn, SQL_DRIVERS, 4, -1);
            s.recent  = rows(conn, SQL_RECENT, 3, -1);
        }
        return s;
    }

    /** Reads rows as strings. moneyCol (1-based) is formatted as money, or -1 for none. */
    private List<Object[]> rows(Connection conn, String sql, int cols, int moneyCol) throws SQLException {
        List<Object[]> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Object[] row = new Object[cols];
                for (int i = 1; i <= cols; i++) {
                    if (i == moneyCol) {
                        BigDecimal v = rs.getBigDecimal(i);
                        row[i - 1] = v == null ? "" : money.format(v);
                    } else {
                        row[i - 1] = rs.getString(i);
                    }
                }
                list.add(row);
            }
        }
        return list;
    }

    private void apply(Snapshot s) {
        for (int i = 0; i < CARDS.length; i++) {
            cardValues[i].setText(s.cards[i]);
        }
        fill(todayModel, s.today);
        fill(activeModel, s.active);
        fill(driverModel, s.drivers);
        fill(recentModel, s.recent);
    }

    private void fill(DefaultTableModel model, List<Object[]> rows) {
        model.setRowCount(0);
        for (Object[] r : rows) {
            model.addRow(r);
        }
    }

    // ---------- BUILDING THE UI ----------
    private JPanel makeCard(int index) {
        Card c = CARDS[index];

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(FIELD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_CLR),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 4, 0, 0, BLUE),
                        BorderFactory.createEmptyBorder(8, 10, 8, 10))));

        JLabel value = new JLabel("...");
        value.setFont(new Font("SansSerif", Font.BOLD, 24));
        value.setForeground(c.sql == null ? MUTED : HEADING);
        cardValues[index] = value;

        JLabel title = new JLabel(c.label);
        title.setFont(new Font("SansSerif", Font.PLAIN, 12));
        title.setForeground(Color.decode("#222222"));

        if (c.sql == null) {
            card.setToolTipText("Not tracked in the current database.");
        }

        card.add(value, BorderLayout.CENTER);
        card.add(title, BorderLayout.SOUTH);
        return card;
    }

    private JPanel makeSection(String title, DefaultTableModel model) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        p.add(makeHeading(title, 16), BorderLayout.NORTH);

        JTable table = makeTable(model);
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        sp.getViewport().setBackground(DARK_LINE);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JLabel makeHeading(String text, int size) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, size));
        l.setForeground(HEADING);
        return l;
    }

    private static DefaultTableModel readOnlyModel(String... cols) {
        return new DefaultTableModel(null, cols) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private JTable makeTable(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setRowHeight(25);
        t.setBackground(DARK);
        t.setForeground(TEXT_LIGHT);
        t.setSelectionBackground(BLUE);
        t.setSelectionForeground(Color.WHITE);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFillsViewportHeight(true);
        t.setFont(new Font("SansSerif", Font.PLAIN, 13));
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        DefaultTableCellRenderer cell = new DefaultTableCellRenderer();
        cell.setHorizontalAlignment(SwingConstants.LEFT);
        cell.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        t.setDefaultRenderer(Object.class, cell);

        JTableHeader header = t.getTableHeader();
        header.setReorderingAllowed(false);
        header.setResizingAllowed(false);
        header.setPreferredSize(new Dimension(100, 32));
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, false, false, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                setBackground(DARK);
                setForeground(TEXT_LIGHT);
                setFont(new Font("SansSerif", Font.PLAIN, 13));
                setOpaque(true);
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, DARK_LINE));
                return this;
            }
        });
        return t;
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