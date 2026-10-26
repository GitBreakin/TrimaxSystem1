package com.trimaxsystem;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.awt.print.PrinterJob;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPageable;

/**
 * Booking details with the status actions that are allowed from the booking's current status.
 * Every change asks for confirmation, is re-checked on the server side of the app
 * ({@link BookingService#changeStatus}), and shows what actually happened.
 */
public class BookingDetailDialog extends JDialog {

    private final Window owner;
    private final int bookingId;
    private final Runnable onChanged;
    private final JPanel body = new JPanel(new BorderLayout());

    private BookingService.Detail detail;
    private String notice;          // success message shown after a change
    private boolean busy;

    public BookingDetailDialog(Window owner, int bookingId, Runnable onChanged) {
        super(owner, "Booking #" + bookingId, ModalityType.APPLICATION_MODAL);
        this.owner = owner;
        this.bookingId = bookingId;
        this.onChanged = onChanged;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(720, 660);
        setMinimumSize(new Dimension(560, 480));
        setLocationRelativeTo(owner);

        body.setBackground(Theme.BACKGROUND);
        body.setBorder(new EmptyBorder(16, 20, 16, 20));
        setContentPane(body);

        // Escape closes the window
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        load();
    }

    // =====================================================
    // LOADING
    // =====================================================

    private void load() {
        showMessage("Loading booking...", false);
        new SwingWorker<BookingService.Detail, Void>() {
            @Override
            protected BookingService.Detail doInBackground() throws Exception {
                return BookingService.loadDetail(bookingId);
            }

            @Override
            protected void done() {
                try {
                    detail = get();
                    if (detail == null) {
                        showMessage("This booking no longer exists.", true);
                    } else {
                        render();
                    }
                } catch (Exception ex) {
                    showMessage("Could not load this booking: " + rootMessage(ex), true);
                }
            }
        }.execute();
    }

    private void showMessage(String text, boolean withRetry) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.insets = new Insets(4, 4, 4, 4);
        JLabel l = new JLabel(text);
        l.setFont(Theme.SUBTLE);
        l.setForeground(withRetry ? Theme.DANGER : Theme.MUTED);
        p.add(l, g);
        if (withRetry) {
            JButton retry = button("Retry", false);
            retry.addActionListener(e -> load());
            p.add(retry, g);
            JButton close = button("Close", false);
            close.addActionListener(e -> dispose());
            p.add(close, g);
        }
        body.removeAll();
        body.add(p, BorderLayout.CENTER);
        body.revalidate();
        body.repaint();
    }

    // =====================================================
    // SHOWING THE BOOKING
    // =====================================================

    private void render() {
        BookingService.Detail d = detail;
        body.removeAll();

        // ---- top: title, status, notice ----
        JLabel title = new JLabel("Booking #" + d.bookingId);
        title.setFont(Theme.TITLE);
        title.setForeground(Theme.TEXT);

        JLabel status = new JLabel("Status: " + d.status);
        status.setFont(new Font("SansSerif", Font.BOLD, 15));
        status.setForeground(Theme.statusColor(d.status));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(title);
        top.add(Box.createVerticalStrut(2));
        top.add(status);
        if (notice != null) {
            JLabel n = new JLabel(notice);
            n.setFont(Theme.SUBTLE);
            n.setForeground(Theme.OK);
            n.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(Box.createVerticalStrut(6));
            top.add(n);
        }
        top.setBorder(new EmptyBorder(0, 0, 12, 0));
        body.add(top, BorderLayout.NORTH);

        // ---- middle: details + items ----
        JPanel middle = new JPanel(new BorderLayout(0, 12));
        middle.setOpaque(false);
        middle.add(buildInfoCard(d), BorderLayout.NORTH);
        middle.add(buildItemsCard(d), BorderLayout.CENTER);
        body.add(middle, BorderLayout.CENTER);

        // ---- bottom: status history note + actions ----
        body.add(buildActions(d), BorderLayout.SOUTH);

        body.revalidate();
        body.repaint();
    }

    private JComponent buildInfoCard(BookingService.Detail d) {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        String driver = d.driverUsername == null ? "Unassigned"
                : (d.driverName == null ? d.driverUsername : d.driverName + " (" + d.driverUsername + ")");
        String vehicle = d.vehicleId == null ? "None"
                : d.vehicleId + (d.vehicleType == null ? "" : " - " + d.vehicleType);

        int r = 0;
        addRow(grid, r++, "Created", Theme.formatWhen(d.date) + "  (Manila time, UTC+8)"
                + (d.fromMobile() ? "  -  from the mobile app" + (d.createdBy == null ? "" : " (" + d.createdBy + ")") : ""));
        addRow(grid, r++, "Customer", orDash(d.clientName));
        addRow(grid, r++, "Address", orDash(d.address));
        addRow(grid, r++, "Contact", orDash(d.contact));
        if (d.deliveryAddress != null && !d.deliveryAddress.equals(d.address)) {
            addRow(grid, r++, "Deliver to", d.deliveryAddress);
        }
        if (d.schedule != null) addRow(grid, r++, "Requested", d.schedule);
        if (d.instructions != null) addRow(grid, r++, "Instructions", d.instructions);
        addRow(grid, r++, "Driver", driver);
        addRow(grid, r++, "Vehicle", vehicle);
        if (d.driverUsername != null && d.deliveryStatus != null) {
            addRow(grid, r++, "Delivery", DeliveryStatus.label(d.deliveryStatus)
                    + (d.deliveryStatusAt == null ? "" : "  (" + Theme.formatWhen(d.deliveryStatusAt) + ")")
                    + (DeliveryStatus.FAILED.equals(d.deliveryStatus)
                    ? "  -  review the reason in History, then cancel the booking to return its stock" : ""));
        }
        addRow(grid, r++, "Total", Theme.money(d.total));
        return card(grid);
    }

    private static String orDash(String s) {
        return s == null || s.trim().isEmpty() ? "-" : s;
    }

    private static void addRow(JPanel grid, int row, String label, String value) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = row;
        g.anchor = GridBagConstraints.NORTHWEST;
        g.insets = new Insets(3, 0, 3, 16);
        JLabel k = new JLabel(label);
        k.setFont(new Font("SansSerif", Font.BOLD, 13));
        k.setForeground(Theme.MUTED);
        g.gridx = 0;
        grid.add(k, g);

        JLabel v = new JLabel(value);
        v.setFont(Theme.SUBTLE);
        v.setForeground(Theme.TEXT);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(3, 0, 3, 0);
        grid.add(v, g);
    }

    private JComponent buildItemsCard(BookingService.Detail d) {
        DefaultTableModel model = new DefaultTableModel(null, new String[]{"Product", "Qty", "Unit price", "Subtotal"}) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (BookingService.Line l : d.items) {
            model.addRow(new Object[]{orDash(l.product), l.quantity, Theme.money(l.unitPrice), Theme.money(l.subtotal)});
        }

        JTable t = new JTable(model);
        t.setRowHeight(28);
        t.setFont(Theme.SUBTLE);
        t.setShowVerticalLines(false);
        t.setGridColor(Theme.BORDER);
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = 1; c < 4; c++) t.getColumnModel().getColumn(c).setCellRenderer(right);

        JLabel heading = new JLabel("Items");
        heading.setFont(new Font("SansSerif", Font.BOLD, 15));
        heading.setForeground(Theme.TEXT);
        heading.setBorder(new EmptyBorder(0, 0, 8, 0));

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(heading, BorderLayout.NORTH);
        if (d.items.isEmpty()) {
            JLabel none = new JLabel("This booking has no items.");
            none.setFont(Theme.SUBTLE);
            none.setForeground(Theme.MUTED);
            p.add(none, BorderLayout.CENTER);
        } else {
            JScrollPane sp = new JScrollPane(t);
            sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
            p.add(sp, BorderLayout.CENTER);
        }
        return card(p);
    }

    private JComponent buildActions(BookingService.Detail d) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(12, 0, 0, 0));

        p.add(buildHistory(d));
        p.add(Box.createVerticalStrut(8));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton saveReceipt = button("Save receipt PDF", false);
        saveReceipt.addActionListener(e -> saveReceipt(d));
        row.add(saveReceipt);
        JButton printReceipt = button("Print receipt", false);
        printReceipt.addActionListener(e -> printReceipt(d));
        row.add(printReceipt);

        if (BookingService.canAssignDriver(d.status)) {
            JButton assign = button(d.driverUsername == null ? "Assign driver" : "Change driver", false);
            assign.addActionListener(e -> chooseAndAssignDriver());
            row.add(assign);
        }
        List<String> next = BookingStatus.next(d.status);
        if (next.isEmpty()) {
            JLabel fin = new JLabel("This booking is " + d.status + ". No further status changes are possible.");
            fin.setFont(Theme.SUBTLE);
            fin.setForeground(Theme.MUTED);
            row.add(fin);
        } else {
            for (String target : next) {
                boolean cancel = BookingStatus.CANCELLED.equals(target);
                JButton b = button(cancel ? "Cancel booking" : "Mark as " + target, cancel);
                b.addActionListener(e -> confirmAndChange(target));
                row.add(b);
            }
        }
        JButton refresh = button("Refresh", false);
        refresh.setToolTipText("Reload this booking, e.g. to see the driver's latest progress");
        refresh.addActionListener(e -> {
            if (!busy) load();
        });
        row.add(refresh);
        JButton close = button("Close", false);
        close.addActionListener(e -> dispose());
        row.add(close);
        p.add(row);
        return p;
    }

    private void saveReceipt(BookingService.Detail d) {
        try {
            ExportDirectories.initialize();
            JFileChooser chooser = new JFileChooser(ExportDirectories.receiptsDirectory().toFile());
            chooser.setDialogTitle("Save booking receipt");
            chooser.setSelectedFile(new File("Receipt_" + d.bookingId + ".pdf"));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

            File output = chooser.getSelectedFile();
            if (!output.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                output = new File(output.getParentFile(), output.getName() + ".pdf");
            }
            File pdf = ReceiptPdfGenerator.generate(receiptData(d), output);
            JOptionPane.showMessageDialog(this, "Receipt saved to:\n" + pdf.getAbsolutePath(),
                    "Receipt Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save the receipt PDF: " + ex.getMessage(),
                    "Receipt Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void printReceipt(BookingService.Detail d) {
        File pdf = null;
        try {
            pdf = File.createTempFile("Receipt_" + d.bookingId + "_", ".pdf");
            ReceiptPdfGenerator.generate(receiptData(d), pdf);
            try (PDDocument document = PDDocument.load(pdf)) {
                PrinterJob job = PrinterJob.getPrinterJob();
                job.setJobName("TRI-MAX booking receipt #" + d.bookingId);
                job.setPageable(new PDFPageable(document));
                if (!job.printDialog()) return;
                job.print();
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not print the receipt: " + ex.getMessage(),
                    "Print Receipt", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (pdf != null && pdf.exists() && !pdf.delete()) pdf.deleteOnExit();
        }
    }

    private static ReceiptPdfGenerator.ReceiptData receiptData(BookingService.Detail detail) {
        ReceiptPdfGenerator.ReceiptData data = new ReceiptPdfGenerator.ReceiptData();
        data.bookingId = detail.bookingId;
        data.date = detail.date;
        data.clientId = detail.clientId == null ? "" : detail.clientId;
        data.clientName = orDash(detail.clientName);
        data.address = orDash(detail.address);
        data.contact = orDash(detail.contact);
        data.driverName = detail.driverName == null ? "Unassigned" : detail.driverName;
        data.driverUsername = detail.driverUsername == null ? "Unassigned" : detail.driverUsername;
        data.vehicleId = detail.vehicleId == null ? "None" : detail.vehicleId;
        data.total = detail.total;
        data.items = new ArrayList<>();
        for (BookingService.Line line : detail.items) {
            data.items.add(new BookForClientPanel.OrderItem(
                    "", orDash(line.product), line.unitPrice == null ? 0 : line.unitPrice.doubleValue(),
                    line.quantity, line.subtotal == null ? 0 : line.subtotal.doubleValue()));
        }
        return data;
    }

    private JPanel card(JComponent inner) {
        JPanel c = new JPanel(new BorderLayout());
        c.setBackground(Theme.CARD);
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(12, 14, 12, 14)));
        c.add(inner, BorderLayout.CENTER);
        return c;
    }

    // =====================================================
    // CHANGING THE STATUS
    // =====================================================

    private void confirmAndChange(String target) {
        if (busy) return;   // ignore double clicks while a change is running
        final BookingService.Detail d = detail;
        final boolean cancel = BookingStatus.CANCELLED.equals(target);

        String message = cancel
                ? "Cancel booking #" + d.bookingId + " for " + orDash(d.clientName) + "?\n\n"
                + (BookingStatus.IN_PROGRESS.equals(d.status)
                ? "This booking is already out for delivery. Only cancel it if the delivery failed or the\n"
                + "customer did not accept the goods.\n\n" : "")
                + "The stock of its " + d.items.size() + " item line(s) will be returned to inventory.\n"
                + "This cannot be undone."
                : "Change booking #" + d.bookingId + " from " + d.status + " to " + target + "?";
        Object[] options = cancel ? new Object[]{"Yes, cancel booking", "Keep booking"}
                : new Object[]{"Yes, change status", "No"};
        int choice = JOptionPane.showOptionDialog(this, message, cancel ? "Cancel booking" : "Change status",
                JOptionPane.DEFAULT_OPTION, cancel ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE,
                null, options, options[cancel ? 1 : 0]);
        if (choice != 0) return;

        busy = true;
        showMessage("Updating booking...", false);
        final String from = d.status;
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return BookingService.changeStatus(d.bookingId, from, target);
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    boolean audited = get();
                    notice = "Status changed from " + from + " to " + target + "."
                            + (audited ? "" : " (Not recorded in the audit log: the audit table does not exist yet.)");
                    if (onChanged != null) onChanged.run();
                } catch (Exception ex) {
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    notice = null;
                    if (cause instanceof AuthService.AuthException) {
                        JOptionPane.showMessageDialog(BookingDetailDialog.this, cause.getMessage(),
                                "Not allowed", JOptionPane.ERROR_MESSAGE);
                        dispose();
                        AuthFrame.logout(owner);   // session is no longer a valid admin session
                        return;
                    }
                    JOptionPane.showMessageDialog(BookingDetailDialog.this,
                            cause instanceof BookingService.ConflictException || cause instanceof IllegalStateException
                                    ? cause.getMessage()
                                    : "The status was NOT changed: " + cause.getMessage(),
                            "Status not changed", JOptionPane.ERROR_MESSAGE);
                    if (onChanged != null) onChanged.run();
                }
                load();   // always show what the database really contains now
            }
        }.execute();
    }

    // =====================================================
    // HISTORY
    // =====================================================

    private JComponent buildHistory(BookingService.Detail d) {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel("History");
        title.setFont(new Font("SansSerif", Font.BOLD, 13));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(title);
        if (d.history == null) {
            box.add(historyLine("History is not available: the audit_log table has not been created yet "
                    + "(run sql/002_audit_log.sql), or it could not be read."));
        } else if (d.history.isEmpty()) {
            box.add(historyLine("No status or driver changes have been recorded for this booking."));
        } else {
            for (AuditService.Entry e : d.history) {
                String details = "booking.delivery_status".equals(e.action) ? DeliveryStatus.humanize(e.details) : e.details;
                box.add(historyLine(Theme.formatWhen(e.when) + "  |  " + e.actor + "  |  "
                        + historyLabel(e.action) + ": " + orDash(details)
                        + ("success".equals(e.result) ? "" : "  [" + e.result + "]")));
            }
        }
        return box;
    }

    private static String historyLabel(String action) {
        if ("booking.status_change".equals(action)) return "Status";
        if ("booking.assign_driver".equals(action)) return "Driver";
        if ("booking.create".equals(action)) return "Created";
        if ("booking.delivery_status".equals(action)) return "Delivery";
        return action;
    }

    private static JLabel historyLine(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.PLAIN, 12));
        l.setForeground(Theme.MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    // =====================================================
    // ASSIGNING A DRIVER
    // =====================================================

    private void chooseAndAssignDriver() {
        if (busy) return;
        final BookingService.Detail d = detail;
        busy = true;
        showMessage("Loading available drivers...", false);
        new SwingWorker<List<BookingService.DriverOption>, Void>() {
            @Override
            protected List<BookingService.DriverOption> doInBackground() throws Exception {
                return BookingService.loadAvailableDrivers();
            }

            @Override
            protected void done() {
                busy = false;
                List<BookingService.DriverOption> options;
                try {
                    options = get();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(BookingDetailDialog.this,
                            "Could not load drivers: " + rootMessage(ex), "Assign driver", JOptionPane.ERROR_MESSAGE);
                    load();
                    return;
                }
                load();
                options.removeIf(o -> o.username.equals(d.driverUsername));
                if (options.isEmpty()) {
                    JOptionPane.showMessageDialog(BookingDetailDialog.this,
                            "No other active driver has an Available vehicle right now.",
                            "Assign driver", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                JComboBox<BookingService.DriverOption> combo =
                        new JComboBox<>(options.toArray(new BookingService.DriverOption[0]));
                int ok = JOptionPane.showConfirmDialog(BookingDetailDialog.this,
                        new Object[]{"Driver for booking #" + d.bookingId
                                + (d.driverUsername == null ? "" : " (currently " + d.driverUsername + ")") + ":", combo},
                        d.driverUsername == null ? "Assign driver" : "Change driver",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (ok == JOptionPane.OK_OPTION) {
                    BookingService.DriverOption chosen = (BookingService.DriverOption) combo.getSelectedItem();
                    if (chosen != null && Boolean.FALSE.equals(chosen.mobileLinked)
                            && JOptionPane.showConfirmDialog(BookingDetailDialog.this,
                            chosen.username + " has no mobile app sign-in, so this delivery will not appear on a phone.\n"
                                    + "Link a sign-in under Accounts if they use the app. Assign anyway?",
                            "Assign driver", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                        return;
                    }
                    runAssign(d, chosen);
                }
            }
        }.execute();
    }

    private void runAssign(BookingService.Detail d, BookingService.DriverOption choice) {
        if (busy || choice == null) return;
        busy = true;
        showMessage("Updating booking...", false);
        final String status = d.status, previous = d.driverUsername;
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return BookingService.assignDriver(d.bookingId, status, previous, choice);
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    boolean audited = get();
                    notice = "Driver set to " + choice.username + " (vehicle " + choice.vehicleId + ")."
                            + (audited ? "" : " (Not recorded in the audit log: the audit table does not exist yet.)");
                    if (onChanged != null) onChanged.run();
                } catch (Exception ex) {
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    notice = null;
                    if (cause instanceof AuthService.AuthException) {
                        JOptionPane.showMessageDialog(BookingDetailDialog.this, cause.getMessage(),
                                "Not allowed", JOptionPane.ERROR_MESSAGE);
                        dispose();
                        AuthFrame.logout(owner);
                        return;
                    }
                    JOptionPane.showMessageDialog(BookingDetailDialog.this,
                            cause instanceof BookingService.ConflictException || cause instanceof IllegalStateException
                                    ? cause.getMessage()
                                    : "The driver was NOT changed: " + cause.getMessage(),
                            "Driver not changed", JOptionPane.ERROR_MESSAGE);
                    if (onChanged != null) onChanged.run();
                }
                load();
            }
        }.execute();
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private static String rootMessage(Exception ex) {
        Throwable t = ex.getCause() != null ? ex.getCause() : ex;
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    private static JButton button(String text, boolean danger) {
        JButton b = new JButton(text);
        Color base = danger ? Theme.DANGER : Theme.BLUE;
        boolean plain = text.equals("Close") || text.equals("Retry") || text.equals("Refresh");
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBackground(plain ? Theme.CARD : base);
        b.setForeground(plain ? Theme.TEXT : Color.WHITE);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(plain ? Theme.BORDER : base),
                new EmptyBorder(6, 14, 6, 14)));
        return b;
    }
}
