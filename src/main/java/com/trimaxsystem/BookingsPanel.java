package com.trimaxsystem;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

public class BookingsPanel extends JPanel {

    private static final Color DARK       = Color.decode("#45494D");
    private static final Color DARK_LINE  = Color.decode("#5E6368");
    private static final Color TEXT_LIGHT = Color.decode("#D1D5DB");
    private static final Color BLUE       = Color.decode("#2563EB");
    private static final Color HEADING    = Color.decode("#1E293B");
    private static final Color BORDER_CLR = Color.decode("#555555");
    private static final Color FIELD_BG   = Color.decode("#F4F6F8");

    private final DefaultTableModel bookingModel;
    private final JTable bookingTable;
    private final JTextField txtSearchBooking;

    // Keeps only one "Add New Booking" window open at a time
    private JFrame bookingFrame;

    public BookingsPanel() {
        setBackground(Color.WHITE);
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        // ---------- HEADER ROW (heading left, search right) ----------
        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        headerRow.add(makeHeading("Bookings"), BorderLayout.WEST);

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        searchPanel.setOpaque(false);

        JLabel lblSearch = new JLabel("Search Booking:");
        lblSearch.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblSearch.setForeground(Color.decode("#222222"));

        txtSearchBooking = new JTextField();
        txtSearchBooking.setPreferredSize(new Dimension(257, 26));
        txtSearchBooking.setBackground(FIELD_BG);
        txtSearchBooking.setFont(new Font("SansSerif", Font.PLAIN, 13));
        txtSearchBooking.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_CLR),
                BorderFactory.createEmptyBorder(0, 6, 0, 6)));

        JButton btnSearch = makeButton("Search");
        btnSearch.setPreferredSize(new Dimension(106, 24));

        searchPanel.add(lblSearch);
        searchPanel.add(txtSearchBooking);
        searchPanel.add(btnSearch);
        headerRow.add(searchPanel, BorderLayout.EAST);

        gbc.gridy = 0;
        gbc.insets = new Insets(0, 2, 4, 2);
        add(headerRow, gbc);

        // ---------- TABLE ----------
        String[] cols = {"Booking ID", "Client", "Date", "Unit Price", "Quantity",
                "Total", "Assigned Driver", "Status"};
        bookingModel = new DefaultTableModel(null, cols) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        bookingTable = makeTable(bookingModel);
        bookingTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scroll = makeScroll(bookingTable);

        gbc.gridy = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 2, 0, 2);
        add(scroll, gbc);

        // ---------- ADD NEW BOOKING BUTTON (bottom right) ----------
        JButton btnAddBooking = makeButton("Add New Booking");
        btnAddBooking.setPreferredSize(new Dimension(100, 50));

        JPanel bottom = new JPanel(new GridBagLayout());
        bottom.setOpaque(false);
        GridBagConstraints b = new GridBagConstraints();
        b.gridy = 0;
        b.fill = GridBagConstraints.HORIZONTAL;
        b.gridx = 0;
        b.weightx = 0.73;                       // spacer on the left
        bottom.add(Box.createHorizontalGlue(), b);
        b.gridx = 1;
        b.weightx = 0.27;                       // button takes ~1/4 of the width
        b.insets = new Insets(8, 0, 6, 4);
        bottom.add(btnAddBooking, b);

        gbc.gridy = 2;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 0, 0);
        add(bottom, gbc);

        // ---------- LISTENERS ----------
        btnSearch.addActionListener(e -> loadBookings(txtSearchBooking.getText().trim()));
        txtSearchBooking.addActionListener(e -> loadBookings(txtSearchBooking.getText().trim()));
        btnAddBooking.addActionListener(e -> openBookingWindow());

        loadBookings("");
    }

    // ---------- OPEN THE "BOOK FOR CLIENT" WINDOW ----------
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
                loadBookings(txtSearchBooking.getText().trim());
            }
        });

        bookingFrame.setVisible(true);
    }

    // ---------- DATABASE ----------

    /** Loads bookings (one row per booked item), filtered by a keyword (empty = all). */
    public void loadBookings(String keyword) {
        String query =
                "SELECT b.booking_id, c.client_name, b.transc_date, "
                        + "       bi.unit_price, bi.quantity, bi.subtotal, "
                        + "       COALESCE(u.name, 'Unassigned') AS driver_name, b.status "
                        + "FROM bookings b "
                        + "LEFT JOIN clients c        ON c.client_id = b.client_id "
                        + "LEFT JOIN booking_items bi ON bi.booking_id = b.booking_id "
                        + "LEFT JOIN users u          ON u.username = b.driver_username "
                        + "WHERE b.booking_id::text ILIKE ? OR c.client_name ILIKE ? "
                        + "   OR u.name ILIKE ? OR b.status ILIKE ? OR b.transc_date::text ILIKE ? "
                        + "ORDER BY b.booking_id DESC, bi.item_id";

        bookingModel.setRowCount(0);
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(query)) {

            String like = "%" + keyword + "%";
            for (int i = 1; i <= 5; i++) {
                pst.setString(i, like);
            }

            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("transc_date");
                    BigDecimal unitPrice = rs.getBigDecimal("unit_price");
                    BigDecimal subtotal  = rs.getBigDecimal("subtotal");
                    int qty = rs.getInt("quantity");

                    bookingModel.addRow(new Object[]{
                            rs.getString("booking_id"),
                            rs.getString("client_name"),
                            ts == null ? "" : dateFmt.format(ts),
                            unitPrice,
                            rs.wasNull() ? "" : String.valueOf(qty),
                            subtotal,
                            rs.getString("driver_name"),
                            rs.getString("status")
                    });
                }
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not load bookings: " + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    // ---------- HELPERS ----------
    private JLabel makeHeading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, 26));
        l.setForeground(HEADING);
        return l;
    }

    private JButton makeButton(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(BLUE);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btn.setFocusable(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorder(BorderFactory.createLineBorder(Color.decode("#1E293B")));
        return btn;
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

        // Left-aligned cells; money shown like 1,250.5 instead of long decimals
        final DecimalFormat fmt = new DecimalFormat("#,##0.##");
        DefaultTableCellRenderer cell = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                if (value instanceof BigDecimal) {
                    setText(fmt.format(value));
                } else {
                    super.setValue(value);
                }
            }
        };
        cell.setHorizontalAlignment(SwingConstants.LEFT);
        cell.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        t.setDefaultRenderer(Object.class, cell);

        // Dark header with light, centered text
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

    private JScrollPane makeScroll(JTable table) {
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        sp.getViewport().setBackground(DARK);
        return sp;
    }
}