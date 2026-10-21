package com.trimaxsystem;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

/**
 * Bookings list: one row per booking, with search, filters (status, driver, date range), server-side
 * sorting and paging, and a detail window (see {@link BookingDetailDialog}) for status changes.
 * "Add New Booking" still opens the existing booking window.
 */
public class BookingsPanel extends JPanel {

    private static final int PAGE_SIZE = 25;
    private static final String TZ_SQL = "Asia/Manila";

    private static final String[] COLS = {"Booking", "Customer", "Date / Time", "Items", "Total", "Driver", "Status"};
    /** ORDER BY expression for each column. Only these fixed strings are ever put into the query. */
    private static final String[] SORT_SQL = {
            "b.booking_id", "LOWER(c.client_name)", "b.transc_date", "item_count",
            "b.total_amount", "LOWER(COALESCE(u.name, 'Unassigned'))", "b.status"};
    private static final int COL_ID = 0, COL_ITEMS = 3, COL_TOTAL = 4, COL_STATUS = 6;

    private static final String FROM_WHERE_BASE =
            " FROM bookings b "
                    + "LEFT JOIN clients c ON c.client_id = b.client_id "
                    + "LEFT JOIN users u   ON u.username = b.driver_username ";

    // ---------- filter controls ----------
    private final JTextField txtSearch = new JTextField(20);
    private final JComboBox<String> cmbStatus = new JComboBox<>();
    private final JComboBox<DriverItem> cmbDriver = new JComboBox<>();
    private final JTextField txtFrom = new JTextField(9);
    private final JTextField txtTo = new JTextField(9);
    private final JLabel lblFilterError = new JLabel(" ");
    private final JLabel lblActive = new JLabel(" ");

    // ---------- table + states ----------
    private final DefaultTableModel model = new DefaultTableModel(null, COLS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final JLabel lblMessage = new JLabel(" ");
    private final JButton btnRetry = secondaryButton("Retry");

    // ---------- footer ----------
    private final JLabel lblShowing = new JLabel(" ");
    private final JLabel lblPage = new JLabel(" ");
    private final JButton btnPrev = secondaryButton("Previous");
    private final JButton btnNext = secondaryButton("Next");
    private final JButton btnView = secondaryButton("View Details");

    // ---------- state ----------
    private Filters applied = new Filters();   // filters of the last "Apply"; paging and refresh reuse them
    private int page = 0;
    private int sortCol = 2;                   // newest first by default
    private boolean sortAsc = false;
    private int requestId = 0;                 // lets a slow, old answer be ignored
    private long totalRows = 0;

    // Keeps only one "Add New Booking" window open at a time
    private JFrame bookingFrame;

    /** The filters the user applied. */
    private static final class Filters {
        String search = "";
        String status = null;              // null = any
        String driverUsername = null;      // null = any
        boolean unassignedOnly = false;
        LocalDate from, to;

        boolean any() {
            return !search.isEmpty() || status != null || driverUsername != null || unassignedOnly
                    || from != null || to != null;
        }
    }

    /** An entry of the driver filter. */
    private static final class DriverItem {
        final String username;   // null for the two special entries
        final String label;
        final boolean unassigned;

        DriverItem(String username, String label, boolean unassigned) {
            this.username = username;
            this.label = label;
            this.unassigned = unassigned;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class Result {
        long total;
        List<Object[]> rows = new ArrayList<>();
    }

    public BookingsPanel() {
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(14, 20, 16, 20));

        add(buildFilterCard(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        loadDrivers();
        load();
    }

    // =====================================================
    // BUILDING THE UI
    // =====================================================

    private JComponent buildFilterCard() {
        txtSearch.setToolTipText("Booking number, customer name, or driver name/username");
        txtFrom.setToolTipText("YYYY-MM-DD, e.g. 2026-10-01 (Manila time)");
        txtTo.setToolTipText("YYYY-MM-DD, e.g. 2026-10-31 (Manila time)");
        cmbStatus.addItem("All statuses");
        for (String s : BookingStatus.ALL) cmbStatus.addItem(s);
        cmbDriver.addItem(new DriverItem(null, "All drivers", false));
        cmbDriver.addItem(new DriverItem(null, "Unassigned", true));

        for (JTextField f : new JTextField[]{txtSearch, txtFrom, txtTo}) {
            f.setFont(Theme.SUBTLE);
            f.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(4, 8, 4, 8)));
            f.addActionListener(e -> applyFilters());   // Enter applies
        }
        cmbStatus.setFont(Theme.SUBTLE);
        cmbDriver.setFont(Theme.SUBTLE);

        JButton btnApply = primaryButton("Apply filters");
        JButton btnClear = secondaryButton("Clear all");
        JButton btnRefresh = secondaryButton("Refresh");
        btnApply.addActionListener(e -> applyFilters());
        btnClear.addActionListener(e -> clearFilters());
        btnRefresh.addActionListener(e -> load());

        JPanel row1 = flow();
        row1.add(label("Search"));
        row1.add(txtSearch);
        row1.add(label("Status"));
        row1.add(cmbStatus);
        row1.add(label("Driver"));
        row1.add(cmbDriver);

        JPanel row2 = flow();
        row2.add(label("From"));
        row2.add(txtFrom);
        row2.add(label("To"));
        row2.add(txtTo);
        row2.add(btnApply);
        row2.add(btnClear);
        row2.add(btnRefresh);

        lblFilterError.setFont(Theme.SUBTLE);
        lblFilterError.setForeground(Theme.DANGER);
        lblActive.setFont(Theme.SUBTLE);
        lblActive.setForeground(Theme.BLUE_DARK);
        JPanel row3 = flow();
        row3.add(lblFilterError);
        row3.add(lblActive);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(8, 12, 4, 12)));
        card.add(row1);
        card.add(row2);
        card.add(row3);
        return card;
    }

    private JComponent buildCenter() {
        styleTable();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.CARD);

        // loading / empty / error message
        lblMessage.setFont(Theme.SUBTLE);
        lblMessage.setForeground(Theme.MUTED);
        btnRetry.addActionListener(e -> load());
        JPanel msg = new JPanel(new GridBagLayout());
        msg.setBackground(Theme.CARD);
        msg.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.insets = new Insets(4, 4, 4, 4);
        msg.add(lblMessage, g);
        msg.add(btnRetry, g);

        center.add(scroll, "TABLE");
        center.add(msg, "MESSAGE");
        return center;
    }

    private JComponent buildFooter() {
        lblShowing.setFont(Theme.SUBTLE);
        lblShowing.setForeground(Theme.MUTED);
        lblPage.setFont(Theme.SUBTLE);
        lblPage.setForeground(Theme.MUTED);

        btnPrev.addActionListener(e -> {
            if (page > 0) {
                page--;
                load();
            }
        });
        btnNext.addActionListener(e -> {
            if ((long) (page + 1) * PAGE_SIZE < totalRows) {
                page++;
                load();
            }
        });
        btnPrev.setEnabled(false);
        btnNext.setEnabled(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(lblShowing);
        left.add(btnPrev);
        left.add(lblPage);
        left.add(btnNext);

        btnView.setEnabled(false);
        btnView.addActionListener(e -> openDetails());
        JButton btnAdd = primaryButton("Add New Booking");
        btnAdd.addActionListener(e -> openBookingWindow());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(btnView);
        right.add(btnAdd);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private void styleTable() {
        table.setRowHeight(32);
        table.setFont(Theme.SUBTLE);
        table.setForeground(Theme.TEXT);
        table.setBackground(Theme.CARD);
        table.setSelectionBackground(Color.decode("#DBEAFE"));
        table.setSelectionForeground(Theme.TEXT);
        table.setShowVerticalLines(false);
        table.setGridColor(Theme.BORDER);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, sel, false, row, col);
                String text = value == null ? "" : value.toString();
                int align = SwingConstants.LEFT;
                setFont(Theme.SUBTLE);
                setForeground(Theme.TEXT);
                if (col == COL_ID) {
                    text = "#" + text;
                    setFont(Theme.NAV_ON);
                } else if (col == COL_TOTAL && value instanceof BigDecimal) {
                    text = Theme.money((BigDecimal) value);
                    align = SwingConstants.RIGHT;
                } else if (col == COL_ITEMS) {
                    align = SwingConstants.RIGHT;
                } else if (col == COL_STATUS) {
                    // The status is always written out; the colour is only a second cue.
                    setForeground(Theme.statusColor(text));
                    setFont(Theme.NAV_ON);
                }
                setText(text);
                setHorizontalAlignment(align);
                setBorder(new EmptyBorder(0, 10, 0, 10));
                return this;
            }
        });

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(100, 34));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setToolTipText("Click a column to sort");
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, false, false, row, col);
                String arrow = col == sortCol ? (sortAsc ? "  ▲" : "  ▼") : "";
                setText(COLS[col] + arrow);
                setHorizontalAlignment(col == COL_TOTAL || col == COL_ITEMS ? SwingConstants.RIGHT : SwingConstants.LEFT);
                setBackground(Theme.BACKGROUND);
                setForeground(Theme.MUTED);
                setFont(new Font("SansSerif", Font.BOLD, 12));
                setOpaque(true);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(0, 10, 0, 10)));
                return this;
            }
        });
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int col = header.columnAtPoint(e.getPoint());
                if (col < 0) return;
                if (col == sortCol) {
                    sortAsc = !sortAsc;
                } else {
                    sortCol = col;
                    sortAsc = !(col == 2 || col == COL_ITEMS || col == COL_TOTAL);   // dates/numbers: biggest first
                }
                page = 0;
                load();
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> btnView.setEnabled(table.getSelectedRow() >= 0));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) openDetails();
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "details");
        table.getActionMap().put("details", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (table.getSelectedRow() >= 0) openDetails();
            }
        });
    }

    // =====================================================
    // FILTERS
    // =====================================================

    private void applyFilters() {
        Filters f = new Filters();
        String search = txtSearch.getText().trim();
        if (search.startsWith("#")) search = search.substring(1).trim();
        f.search = search;

        if (cmbStatus.getSelectedIndex() > 0) f.status = (String) cmbStatus.getSelectedItem();

        DriverItem d = (DriverItem) cmbDriver.getSelectedItem();
        if (d != null) {
            f.unassignedOnly = d.unassigned;
            f.driverUsername = d.username;
        }

        try {
            f.from = parseDate(txtFrom.getText());
            f.to = parseDate(txtTo.getText());
        } catch (DateTimeParseException ex) {
            lblFilterError.setText("Dates must look like 2026-10-31 (year-month-day).");
            return;
        }
        if (f.from != null && f.to != null && f.from.isAfter(f.to)) {
            lblFilterError.setText("\"From\" must not be after \"To\".");
            return;
        }
        lblFilterError.setText(" ");
        applied = f;
        page = 0;
        load();
    }

    private static LocalDate parseDate(String text) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() ? null : LocalDate.parse(t);
    }

    private void clearFilters() {
        txtSearch.setText("");
        txtFrom.setText("");
        txtTo.setText("");
        cmbStatus.setSelectedIndex(0);
        cmbDriver.setSelectedIndex(0);
        lblFilterError.setText(" ");
        applied = new Filters();
        page = 0;
        load();
    }

    private void updateActiveLabel() {
        Filters f = applied;
        if (!f.any()) {
            lblActive.setText(" ");
            return;
        }
        List<String> parts = new ArrayList<>();
        if (!f.search.isEmpty()) parts.add("search \"" + f.search + "\"");
        if (f.status != null) parts.add("status " + f.status);
        if (f.unassignedOnly) parts.add("unassigned");
        if (f.driverUsername != null) parts.add("driver " + f.driverUsername);
        if (f.from != null) parts.add("from " + f.from);
        if (f.to != null) parts.add("to " + f.to);
        lblActive.setText("Active filters: " + String.join(", ", parts));
    }

    // =====================================================
    // LOADING
    // =====================================================

    /** Reloads the current page with the applied filters and sort. */
    public void load() {
        final int req = ++requestId;
        final Filters f = applied;
        final int p = page;
        final int sc = sortCol;
        final boolean asc = sortAsc;
        showMessage("Loading bookings...", false);
        updateActiveLabel();

        new SwingWorker<Result, Void>() {
            @Override
            protected Result doInBackground() throws Exception {
                return query(f, p, sc, asc);
            }

            @Override
            protected void done() {
                if (req != requestId) return;   // a newer request has been started
                try {
                    Result r = get();
                    totalRows = r.total;
                    if (r.rows.isEmpty() && r.total > 0 && p > 0) {   // page no longer exists (rows were removed)
                        page = (int) ((r.total - 1) / PAGE_SIZE);
                        load();
                        return;
                    }
                    model.setRowCount(0);
                    for (Object[] row : r.rows) model.addRow(row);
                    header().repaint();
                    if (r.rows.isEmpty()) {
                        showMessage(f.any() ? "No bookings match these filters." : "There are no bookings yet.", false);
                    } else {
                        centerCards.show(center, "TABLE");
                    }
                    updateFooter(r.rows.size());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    totalRows = 0;
                    model.setRowCount(0);
                    showMessage("Could not load bookings: " + cause.getMessage(), true);
                    updateFooter(0);
                    ex.printStackTrace();
                }
            }
        }.execute();
    }

    private JTableHeader header() {
        return table.getTableHeader();
    }

    private void showMessage(String text, boolean error) {
        lblMessage.setText(text);
        lblMessage.setForeground(error ? Theme.DANGER : Theme.MUTED);
        btnRetry.setVisible(error);
        centerCards.show(center, "MESSAGE");
    }

    private void updateFooter(int shown) {
        if (totalRows == 0 || shown == 0) {
            lblShowing.setText(totalRows == 0 ? "0 bookings" : " ");
            lblPage.setText(" ");
        } else {
            long first = (long) page * PAGE_SIZE + 1;
            long last = first + shown - 1;
            long pages = (totalRows + PAGE_SIZE - 1) / PAGE_SIZE;
            lblShowing.setText("Showing " + first + "-" + last + " of " + totalRows);
            lblPage.setText("Page " + (page + 1) + " of " + pages);
        }
        btnPrev.setEnabled(page > 0);
        btnNext.setEnabled((long) (page + 1) * PAGE_SIZE < totalRows);
        btnView.setEnabled(table.getSelectedRow() >= 0);
    }

    private Result query(Filters f, int pageNo, int sc, boolean asc) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (!f.search.isEmpty()) {
            where.append(" AND (b.booking_id::text ILIKE ? OR c.client_name ILIKE ? OR u.name ILIKE ? OR u.username ILIKE ?)");
            String like = "%" + f.search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 4; i++) params.add(like);
        }
        if (f.status != null) {
            where.append(" AND b.status = ?");
            params.add(f.status);
        }
        if (f.unassignedOnly) {
            where.append(" AND b.driver_username IS NULL");
        } else if (f.driverUsername != null) {
            where.append(" AND b.driver_username = ?");
            params.add(f.driverUsername);
        }
        if (f.from != null) {
            where.append(" AND (b.transc_date AT TIME ZONE '" + TZ_SQL + "')::date >= ?");
            params.add(java.sql.Date.valueOf(f.from));
        }
        if (f.to != null) {
            where.append(" AND (b.transc_date AT TIME ZONE '" + TZ_SQL + "')::date <= ?");
            params.add(java.sql.Date.valueOf(f.to));
        }

        String countSql = "SELECT COUNT(*)" + FROM_WHERE_BASE + where;
        String dataSql = "SELECT b.booking_id, c.client_name, "
                + "to_char(b.transc_date AT TIME ZONE '" + TZ_SQL + "', 'YYYY-MM-DD HH24:MI'), "
                + "(SELECT COUNT(*) FROM booking_items bi WHERE bi.booking_id = b.booking_id) AS item_count, "
                + "b.total_amount, COALESCE(u.name, 'Unassigned'), b.status"
                + FROM_WHERE_BASE + where
                + " ORDER BY " + SORT_SQL[sc] + (asc ? " ASC" : " DESC") + ", b.booking_id DESC"
                + " LIMIT ? OFFSET ?";

        Result r = new Result();
        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                bind(ps, params, 0);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    r.total = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(dataSql)) {
                int next = bind(ps, params, 0);
                ps.setInt(next + 1, PAGE_SIZE);
                ps.setLong(next + 2, (long) pageNo * PAGE_SIZE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        r.rows.add(new Object[]{
                                rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                                rs.getBigDecimal(5), rs.getString(6), rs.getString(7)});
                    }
                }
            }
        }
        return r;
    }

    /** Binds the filter values; returns how many parameters were set. */
    private static int bind(PreparedStatement ps, List<Object> params, int offset) throws SQLException {
        int i = offset;
        for (Object o : params) {
            i++;
            if (o instanceof java.sql.Date) ps.setDate(i, (java.sql.Date) o);
            else ps.setString(i, String.valueOf(o));
        }
        return i;
    }

    /** Fills the driver filter. If it fails the filter simply keeps its two built-in entries. */
    private void loadDrivers() {
        new SwingWorker<List<DriverItem>, Void>() {
            @Override
            protected List<DriverItem> doInBackground() throws Exception {
                List<DriverItem> list = new ArrayList<>();
                try (Connection conn = DatabaseConnection.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT u.username, u.name FROM users u JOIN roles r ON r.role_id = u.role_id "
                                     + "WHERE r.role_name = 'Driver' ORDER BY u.name, u.username");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new DriverItem(rs.getString(1), rs.getString(2) + " (" + rs.getString(1) + ")", false));
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                try {
                    for (DriverItem d : get()) cmbDriver.addItem(d);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }.execute();
    }

    // =====================================================
    // ACTIONS
    // =====================================================

    private void openDetails() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        int id = (Integer) model.getValueAt(row, COL_ID);
        new BookingDetailDialog(SwingUtilities.getWindowAncestor(this), id, this::load).setVisible(true);
    }

    // ---------- OPEN THE "BOOK FOR CLIENT" WINDOW (unchanged behaviour) ----------
    private void openBookingWindow() {
        // If it's already open, just bring it to the front
        if (bookingFrame != null && bookingFrame.isDisplayable()) {
            bookingFrame.toFront();
            bookingFrame.requestFocus();
            return;
        }

        bookingFrame = new JFrame("Add New Booking");
        bookingFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        bookingFrame.setContentPane(new BookForClientPanel());

        // Fill the usable area of the screen this window is on (excludes the taskbar)
        GraphicsConfiguration gc = getGraphicsConfiguration();
        Rectangle screen = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Rectangle area = new Rectangle(
                screen.x + in.left,
                screen.y + in.top,
                screen.width - in.left - in.right,
                screen.height - in.top - in.bottom);

        bookingFrame.setBounds(area);

        // Lock it to that size
        bookingFrame.setMinimumSize(area.getSize());
        bookingFrame.setMaximumSize(area.getSize());
        bookingFrame.setResizable(false);

        // Refresh the bookings list once the window is closed
        bookingFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                load();
            }
        });

        bookingFrame.setVisible(true);
    }

    // =====================================================
    // SMALL HELPERS
    // =====================================================

    private static JPanel flow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, 12));
        l.setForeground(Theme.MUTED);
        return l;
    }

    private static JButton primaryButton(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBackground(Theme.BLUE);
        b.setForeground(Color.WHITE);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BLUE_DARK), new EmptyBorder(6, 14, 6, 14)));
        return b;   // stays focusable for keyboard use
    }

    private static JButton secondaryButton(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBackground(Theme.CARD);
        b.setForeground(Theme.TEXT);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(6, 14, 6, 14)));
        return b;
    }
}
