package com.trimaxsystem;

import java.awt.*;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

/**
 * Review window shown after "Proceed": client details, ordered items, driver
 * assignment, grand total, an on-the-spot change calculator (never saved),
 * and a Confirm button that saves the booking and creates the receipt PDF.
 */
public class BookingReviewDialog extends JDialog {

    private static final Color DARK       = Color.decode("#45494D");
    private static final Color DARK_LINE  = Color.decode("#5E6368");
    private static final Color TEXT_LIGHT = Color.decode("#D1D5DB");
    private static final Color BLUE       = Color.decode("#2563EB");
    private static final Color HEADING    = Color.decode("#1E293B");
    private static final Color BORDER_CLR = Color.decode("#555555");
    private static final Color FIELD_BG   = Color.decode("#F4F6F8");
    private static final Color RED        = Color.decode("#DC2626");
    private static final Color GREEN      = Color.decode("#16A34A");

    private final DecimalFormat money = new DecimalFormat("#,##0.00");
    private final double grandTotal;

    private final String clientId, clientName, address, contact;
    private final List<BookForClientPanel.OrderItem> items;
    private final Runnable onSaved;

    private final JComboBox<BookingService.DriverOption> cmbDriver = new JComboBox<>();
    private final JTextField txtPaid = new JTextField();
    private final JLabel lblChange = new JLabel("0.00");
    private final JLabel lblStatus = new JLabel(" ");
    private final JButton btnConfirm = new JButton("Confirm Booking");
    private final JButton btnBack = new JButton("Back");

    /**
     * @param onSaved called after the booking is saved (e.g. to reload the product list)
     */
    public BookingReviewDialog(Window owner, String clientId, String clientName,
                               String address, String contact,
                               List<BookForClientPanel.OrderItem> items,
                               Runnable onSaved) {
        super(owner, "Review Booking", ModalityType.APPLICATION_MODAL);
        this.clientId = clientId;
        this.clientName = clientName;
        this.address = address;
        this.contact = contact;
        this.items = items;
        this.onSaved = onSaved;

        double sum = 0;
        for (BookForClientPanel.OrderItem it : items) {
            sum += it.subtotal;
        }
        grandTotal = sum;

        // Same size and position as the window that opened it
        if (owner != null) {
            setSize(owner.getSize());
            setLocation(owner.getLocation());
        } else {
            setSize(900, 600);
            setLocationRelativeTo(null);
        }
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(Color.WHITE);
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        setContentPane(root);

        // ---------- TOP: heading + client info + driver ----------
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        top.add(makeHeading("Review Booking", 26));
        top.add(Box.createVerticalStrut(6));

        JPanel info = new JPanel(new GridLayout(2, 2, 10, 4));
        info.setBackground(FIELD_BG);
        info.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_CLR),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        info.add(infoLabel("Client ID: ", clientId));
        info.add(infoLabel("Client Name: ", clientName));
        info.add(infoLabel("Address: ", address));
        info.add(infoLabel("Contact Number: ", contact));
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(info);
        top.add(Box.createVerticalStrut(8));

        JPanel driverRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        driverRow.setOpaque(false);
        driverRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        driverRow.add(rowLabel("Assign Driver:"));
        cmbDriver.setPreferredSize(new Dimension(320, 28));
        cmbDriver.setFont(new Font("SansSerif", Font.PLAIN, 13));
        driverRow.add(cmbDriver);
        top.add(driverRow);
        top.add(Box.createVerticalStrut(8));

        top.add(makeHeading("Items to Order", 18));
        root.add(top, BorderLayout.NORTH);

        // ---------- CENTER: items table ----------
        String[] cols = {"Product ID", "Product", "Price", "Amount", "Subtotal"};
        DefaultTableModel model = new DefaultTableModel(null, cols) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (BookForClientPanel.OrderItem it : items) {
            model.addRow(new Object[]{
                    it.productId, it.productName,
                    money.format(it.price), it.amount, money.format(it.subtotal)});
        }
        JTable table = makeTable(model);
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        sp.getViewport().setBackground(DARK_LINE);
        root.add(sp, BorderLayout.CENTER);

        // ---------- BOTTOM: totals, change calculator, buttons ----------
        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setOpaque(false);

        JPanel calc = new JPanel(new GridBagLayout());
        calc.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 0, 3, 10);
        g.anchor = GridBagConstraints.WEST;

        JLabel lblTotal = new JLabel(money.format(grandTotal));
        lblTotal.setFont(new Font("SansSerif", Font.BOLD, 22));
        lblTotal.setForeground(HEADING);

        txtPaid.setPreferredSize(new Dimension(180, 30));
        txtPaid.setBackground(FIELD_BG);
        txtPaid.setFont(new Font("SansSerif", Font.PLAIN, 15));
        txtPaid.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_CLR),
                BorderFactory.createEmptyBorder(0, 6, 0, 6)));

        lblChange.setFont(new Font("SansSerif", Font.BOLD, 22));
        lblChange.setForeground(GREEN);
        lblStatus.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblStatus.setForeground(RED);

        g.gridx = 0; g.gridy = 0; calc.add(rowLabel("Total:"), g);
        g.gridx = 1;              calc.add(lblTotal, g);
        g.gridx = 0; g.gridy = 1; calc.add(rowLabel("Amount to Pay:"), g);
        g.gridx = 1;              calc.add(txtPaid, g);
        g.gridx = 0; g.gridy = 2; calc.add(rowLabel("Change:"), g);
        g.gridx = 1;              calc.add(lblChange, g);
        g.gridx = 1; g.gridy = 3; calc.add(lblStatus, g);
        bottom.add(calc, BorderLayout.WEST);

        styleButton(btnBack);
        btnBack.setPreferredSize(new Dimension(100, 44));
        btnBack.addActionListener(e -> dispose());

        styleButton(btnConfirm);
        btnConfirm.setPreferredSize(new Dimension(170, 44));
        btnConfirm.addActionListener(e -> handleConfirm());

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        btns.add(btnBack);
        btns.add(btnConfirm);
        bottom.add(btns, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        txtPaid.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { updateChange(); }
            public void removeUpdate(DocumentEvent e)  { updateChange(); }
            public void changedUpdate(DocumentEvent e) { updateChange(); }
        });

        loadDrivers();
    }

    // ---------- DRIVERS ----------
    private void loadDrivers() {
        try {
            List<BookingService.DriverOption> drivers = BookingService.loadAvailableDrivers();
            cmbDriver.removeAllItems();
            for (BookingService.DriverOption d : drivers) {
                cmbDriver.addItem(d);
            }
            if (drivers.isEmpty()) {
                lblStatus.setText("No Available drivers right now.");
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not load drivers: " + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    // ---------- CONFIRM ----------
    private void handleConfirm() {
        BookingService.DriverOption driver = (BookingService.DriverOption) cmbDriver.getSelectedItem();
        if (driver == null) {
            JOptionPane.showMessageDialog(this, "Please assign an Available driver first.",
                    "No Driver Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Save this booking for " + clientName + " (Total: " + money.format(grandTotal) + ")?",
                "Confirm Booking", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        BookingService.BookingResult result;
        try {
            result = BookingService.createBooking(Integer.parseInt(clientId), driver, items);
        } catch (SQLException | NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Booking was NOT saved:\n" + ex.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace();
            return;
        }

        // Saved: lock the screen so it can't be submitted twice
        btnConfirm.setEnabled(false);
        cmbDriver.setEnabled(false);
        btnBack.setText("Close");
        if (onSaved != null) {
            onSaved.run();
        }

        // Optional on-the-spot payment info for the printed receipt (only if enough was paid)
        BigDecimal paid = null, change = null;
        try {
            BigDecimal p = new BigDecimal(txtPaid.getText().trim().replace(",", ""));
            if (p.compareTo(result.totalAmount) >= 0) {
                paid = p.setScale(2, RoundingMode.HALF_UP);
                change = paid.subtract(result.totalAmount);
            }
        } catch (NumberFormatException ignored) {
            // no valid payment typed: receipt is printed without it
        }

        // Build the receipt data and create the PDF
        ReceiptPdfGenerator.ReceiptData data = new ReceiptPdfGenerator.ReceiptData();
        data.bookingId = result.bookingId;
        data.date = result.transcDate;
        data.clientId = clientId;
        data.clientName = clientName;
        data.address = address;
        data.contact = contact;
        data.driverName = driver.name;
        data.driverUsername = driver.username;
        data.vehicleId = driver.vehicleId;
        data.items = new ArrayList<>(items);
        data.total = result.totalAmount;
        data.amountPaid = paid;
        data.change = change;

        try {
            File pdf = ReceiptPdfGenerator.generate(data, new File("receipts", "Receipt_" + result.bookingId + ".pdf"));
            JOptionPane.showMessageDialog(this,
                    "Booking #" + result.bookingId + " saved.\nReceipt: " + pdf.getAbsolutePath(),
                    "Success", JOptionPane.INFORMATION_MESSAGE);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(pdf);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Booking #" + result.bookingId + " WAS saved, but the receipt could not be created:\n"
                            + ex.getMessage(),
                    "Receipt Error", JOptionPane.WARNING_MESSAGE);
            ex.printStackTrace();
        }
    }

    /** Change = amount paid - total. Display only, never saved. */
    private void updateChange() {
        String text = txtPaid.getText().trim().replace(",", "");
        if (text.isEmpty()) {
            lblChange.setText("0.00");
            lblChange.setForeground(GREEN);
            lblStatus.setText(" ");
            return;
        }
        try {
            double paid = Double.parseDouble(text);
            double change = paid - grandTotal;
            if (change < 0) {
                lblChange.setText("0.00");
                lblChange.setForeground(RED);
                lblStatus.setText("Short by " + money.format(-change));
            } else {
                lblChange.setText(money.format(change));
                lblChange.setForeground(GREEN);
                lblStatus.setText(" ");
            }
        } catch (NumberFormatException ex) {
            lblChange.setText("0.00");
            lblStatus.setText("Enter a valid number");
        }
    }

    // ---------- HELPERS ----------
    private JLabel makeHeading(String text, int size) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, size));
        l.setForeground(HEADING);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel infoLabel(String title, String value) {
        return new JLabel("<html><b>" + title + "</b>" + escape(value) + "</html>");
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JLabel rowLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.PLAIN, 15));
        l.setForeground(Color.decode("#222222"));
        return l;
    }

    private void styleButton(JButton b) {
        b.setBackground(BLUE);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", Font.PLAIN, 13));
        b.setFocusable(false);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorder(BorderFactory.createLineBorder(HEADING));
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
}