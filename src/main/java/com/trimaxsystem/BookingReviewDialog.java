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
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Review window shown after "Proceed": client details, ordered items, driver
 * assignment, grand total, an on-the-spot change calculator (never saved),
 * and a Confirm button that saves the booking and creates the receipt PDF.
 */
public class BookingReviewDialog extends JDialog {

    private final DecimalFormat money = new DecimalFormat("#,##0.00");
    private final double grandTotal;

    private final String clientId, clientName, address, contact;
    private final List<BookForClientPanel.OrderItem> items;
    private final Runnable onSaved;

    private final JComboBox<BookingService.DriverOption> cmbDriver = new JComboBox<>();
    private final JTextField txtPaid = new JTextField();
    private final JLabel lblChange = new JLabel("0.00");
    private final JLabel lblStatus = new JLabel(" ");
    private final JButton btnConfirm = UiKit.primaryButton("Confirm Booking");
    private final JButton btnBack = UiKit.secondaryButton("Back");

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

        Rectangle bounds;
        if (owner != null) {
            bounds = owner.getBounds();
        } else {
            bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        }
        setBounds(bounds);
        setMinimumSize(bounds.getSize());
        setMaximumSize(bounds.getSize());
        setResizable(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(new EmptyBorder(14, 20, 16, 20));
        setContentPane(root);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(makeHeading("Review booking", 24));
        top.add(Box.createVerticalStrut(10));

        JPanel info = new JPanel(new GridLayout(2, 2, 10, 4));
        info.setOpaque(false);
        info.add(infoLabel("Client ID: ", clientId));
        info.add(infoLabel("Client Name: ", clientName));
        info.add(infoLabel("Address: ", address));
        info.add(infoLabel("Contact Number: ", contact));
        JPanel clientCard = new JPanel(new BorderLayout(0, 8));
        clientCard.setBackground(Theme.CARD);
        clientCard.setBorder(cardBorder());
        clientCard.add(sectionHeading("Client details", "Confirm the customer information for this booking."),
                BorderLayout.NORTH);
        clientCard.add(info, BorderLayout.CENTER);
        clientCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(clientCard);
        top.add(Box.createVerticalStrut(8));

        JPanel driverRow = new JPanel(new BorderLayout(12, 0));
        driverRow.setBackground(Theme.CARD);
        driverRow.setBorder(cardBorder());
        driverRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        driverRow.add(sectionHeading("Driver assignment", "Select an available driver for delivery."),
                BorderLayout.WEST);
        cmbDriver.setPreferredSize(new Dimension(320, 36));
        cmbDriver.setFont(Theme.SUBTLE);
        cmbDriver.setBackground(Theme.CARD);
        cmbDriver.setForeground(Theme.TEXT);
        cmbDriver.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        JPanel driverControl = new JPanel(new BorderLayout(0, 5));
        driverControl.setOpaque(false);
        driverControl.add(UiKit.caption("Assign driver"), BorderLayout.NORTH);
        driverControl.add(cmbDriver, BorderLayout.CENTER);
        driverRow.add(driverControl, BorderLayout.EAST);
        top.add(driverRow);
        root.add(top, BorderLayout.NORTH);

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
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        sp.getViewport().setBackground(Theme.CARD);
        JPanel itemsCard = new JPanel(new BorderLayout(0, 8));
        itemsCard.setBackground(Theme.CARD);
        itemsCard.setBorder(cardBorder());
        itemsCard.add(sectionHeading("Items to order", "Review quantities and subtotals before confirming."),
                BorderLayout.NORTH);
        itemsCard.add(sp, BorderLayout.CENTER);
        root.add(itemsCard, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setBackground(Theme.CARD);
        bottom.setBorder(cardBorder());

        JPanel calc = new JPanel(new GridBagLayout());
        calc.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 0, 3, 10);
        g.anchor = GridBagConstraints.WEST;

        JLabel lblTotal = new JLabel(money.format(grandTotal));
        lblTotal.setFont(new Font("SansSerif", Font.BOLD, 22));
        lblTotal.setForeground(Theme.TEXT);

        txtPaid.setPreferredSize(new Dimension(180, 30));
        txtPaid.setBackground(Theme.CARD);
        UiKit.styleField(txtPaid);

        lblChange.setFont(new Font("SansSerif", Font.BOLD, 22));
        lblChange.setForeground(Theme.OK);
        lblStatus.setFont(Theme.SUBTLE);
        lblStatus.setForeground(Theme.DANGER);

        g.gridx = 0; g.gridy = 0; calc.add(rowLabel("Total:"), g);
        g.gridx = 1;              calc.add(lblTotal, g);
        g.gridx = 0; g.gridy = 1; calc.add(rowLabel("Amount to Pay:"), g);
        g.gridx = 1;              calc.add(txtPaid, g);
        g.gridx = 0; g.gridy = 2; calc.add(rowLabel("Change:"), g);
        g.gridx = 1;              calc.add(lblChange, g);
        g.gridx = 1; g.gridy = 3; calc.add(lblStatus, g);
        bottom.add(calc, BorderLayout.WEST);

        btnBack.setPreferredSize(new Dimension(110, 42));
        btnBack.addActionListener(e -> dispose());

        btnConfirm.setPreferredSize(new Dimension(180, 42));
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
            File pdf = ReceiptPdfGenerator.generate(data,
                    new File(ExportDirectories.receiptsDirectory(), "Receipt_" + result.bookingId + ".pdf"));
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
            lblChange.setForeground(Theme.OK);
            lblStatus.setText(" ");
            return;
        }
        try {
            double paid = Double.parseDouble(text);
            double change = paid - grandTotal;
            if (change < 0) {
                lblChange.setText("0.00");
                lblChange.setForeground(Theme.DANGER);
                lblStatus.setText("Short by " + money.format(-change));
            } else {
                lblChange.setText(money.format(change));
                lblChange.setForeground(Theme.OK);
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
        l.setForeground(Theme.TEXT);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel infoLabel(String title, String value) {
        JLabel label = new JLabel("<html><font color='#64748B'><b>" + title
                + "</b></font>" + escape(value) + "</html>");
        label.setFont(Theme.SUBTLE);
        label.setForeground(Theme.TEXT);
        return label;
    }

    private JPanel sectionHeading(String titleText, String subtitleText) {
        JPanel heading = new JPanel(new BorderLayout(0, 3));
        heading.setOpaque(false);
        JLabel title = new JLabel(titleText);
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(Theme.TEXT);
        JLabel subtitle = new JLabel(subtitleText);
        subtitle.setFont(Theme.SUBTLE);
        subtitle.setForeground(Theme.MUTED);
        heading.add(title, BorderLayout.NORTH);
        heading.add(subtitle, BorderLayout.SOUTH);
        return heading;
    }

    private javax.swing.border.Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(12, 14, 12, 14));
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JLabel rowLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.SUBTLE);
        l.setForeground(Theme.MUTED);
        return l;
    }

    private JTable makeTable(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setRowHeight(32);
        t.setBackground(Theme.CARD);
        t.setForeground(Theme.TEXT);
        t.setSelectionBackground(Color.decode("#DBEAFE"));
        t.setSelectionForeground(Theme.TEXT);
        t.setShowVerticalLines(false);
        t.setGridColor(Theme.BORDER);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setFillsViewportHeight(true);
        t.setFont(Theme.SUBTLE);
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                           boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, false, row, column);
                setFont(Theme.SUBTLE);
                setForeground(Theme.TEXT);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                return this;
            }
        });
        DefaultTableCellRenderer header = new DefaultTableCellRenderer();
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setBackground(Color.decode("#F8FAFC"));
        header.setForeground(Theme.MUTED);
        header.setFont(new Font("SansSerif", Font.BOLD, 12));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        t.getTableHeader().setDefaultRenderer(header);
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setPreferredSize(new Dimension(100, 34));
        return t;
    }
}