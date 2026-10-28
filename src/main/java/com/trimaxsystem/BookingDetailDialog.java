package com.trimaxsystem;

import java.math.BigDecimal;

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
        // Taller than before for the drop-off photos, but never taller than the screen.
        setSize(760, Math.min(820, Toolkit.getDefaultToolkit().getScreenSize().height - 60));
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
        JComponent next = buildNextStep(d);
        next.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(Box.createVerticalStrut(10));
        top.add(next);
        body.add(top, BorderLayout.NORTH);

        // ---- middle: details + items ----
        JPanel middle = new JPanel(new BorderLayout(0, 12));
        middle.setOpaque(false);
        // Drop-off photos from the driver app, once a driver is on the booking (or photos exist).
        if (d.driverUsername != null || !d.proofs.isEmpty()) {
            JPanel upper = new JPanel(new BorderLayout(0, 12));
            upper.setOpaque(false);
            upper.add(buildInfoCard(d), BorderLayout.NORTH);
            upper.add(card(new ProofPhotosPanel(d.proofs, d.deliveryStatus)), BorderLayout.CENTER);
            middle.add(upper, BorderLayout.NORTH);
        } else {
            middle.add(buildInfoCard(d), BorderLayout.NORTH);
        }
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
        if (d.hasDiscount()) {
            BigDecimal original = d.subtotal != null ? d.subtotal : d.total.add(d.discountAmount);
            addRow(grid, r++, "Original price", Theme.money(original));
            addRow(grid, r++, "Agent discount", "\u2212 " + Theme.money(d.discountAmount) + "  (" + d.discountText()
                    + (d.discountAt == null ? "" : ", " + Theme.formatWhen(d.discountAt)) + ")");
        }
        addRow(grid, r++, d.hasDiscount() ? "Final price" : "Total", Theme.money(d.total));
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
        // A discount per item (20261027_04) adds "Less / piece" and "Line total"; otherwise the table is as before.
        boolean perItem = d.items.stream().anyMatch(l -> l.discountPerUnit != null && l.discountPerUnit.signum() > 0);
        String[] cols = perItem ? new String[]{"Product", "Qty", "Unit price", "Less / piece", "Line total"}
                                : new String[]{"Product", "Qty", "Unit price", "Subtotal"};
        DefaultTableModel model = new DefaultTableModel(null, cols) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (BookingService.Line l : d.items) {
            if (perItem) {
                model.addRow(new Object[]{orDash(l.product), l.quantity, Theme.money(l.unitPrice),
                        l.discountPerUnit.signum() > 0 ? "\u2212 " + Theme.money(l.discountPerUnit) : "",
                        Theme.money(l.lineTotal())});
            } else {
                model.addRow(new Object[]{orDash(l.product), l.quantity, Theme.money(l.unitPrice), Theme.money(l.subtotal)});
            }
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
        for (int c = 1; c < cols.length; c++) t.getColumnModel().getColumn(c).setCellRenderer(right);

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

    /**
     * The "Next step" box: who is assigned, and the one highlighted action for this booking (Confirm &amp;
     * assign driver, Confirm, Assign driver or Print receipt; see {@link BookingActions}). The receipt
     * buttons only exist once a driver is saved on the booking.
     */
    private JComponent buildNextStep(BookingService.Detail d) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(Color.decode("#EFF6FF"));
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.decode("#BFDBFE")), new EmptyBorder(10, 12, 10, 12)));

        String driver = BookingActions.hasDriver(d.driverUsername)
                ? (d.driverName == null ? d.driverUsername : d.driverName + " (" + d.driverUsername + ")")
                  + (d.vehicleId == null ? "" : "  -  vehicle " + d.vehicleId)
                : "No driver yet";
        JLabel who = new JLabel("Driver: " + driver);
        who.setFont(new Font("SansSerif", Font.BOLD, 13));
        who.setForeground(Theme.TEXT);
        who.setAlignmentX(Component.LEFT_ALIGNMENT);
        String nextText = BookingActions.nextStepText(d.status, d.driverUsername);
        JLabel what = new JLabel(nextText == null ? " " : nextText);
        what.setFont(Theme.SUBTLE);
        what.setForeground(Theme.MUTED);
        what.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(who);
        box.add(Box.createVerticalStrut(2));
        box.add(what);

        JPanel row = UiKit.flowRow();
        row.setBorder(new EmptyBorder(6, -8, 0, 0));
        switch (BookingActions.primary(d.status, d.driverUsername)) {
            case CONFIRM_AND_ASSIGN: {
                JButton b = UiKit.primaryButton("Confirm & assign driver");
                b.setToolTipText("Confirms the booking and assigns the driver you choose, in one step");
                b.addActionListener(e -> chooseAndAssignDriver(true));
                row.add(b);
                break;
            }
            case CONFIRM: {
                JButton b = UiKit.primaryButton("Confirm booking");
                b.addActionListener(e -> confirmAndChange(BookingStatus.CONFIRMED));
                row.add(b);
                break;
            }
            case ASSIGN: {
                JButton b = UiKit.primaryButton("Assign driver");
                b.addActionListener(e -> chooseAndAssignDriver(false));
                row.add(b);
                break;
            }
            default:
                break;
        }
        if (BookingActions.canPrint(d.driverUsername)) {
            boolean main = BookingActions.primary(d.status, d.driverUsername) == BookingActions.Primary.PRINT;
            JButton print = main ? UiKit.primaryButton("Print receipt") : UiKit.secondaryButton("Print receipt");
            print.addActionListener(e -> withSavedDriver(this::printReceipt));
            JButton save = UiKit.secondaryButton("Save receipt PDF");
            save.addActionListener(e -> withSavedDriver(this::saveReceipt));
            row.add(print);
            row.add(save);
        }
        if (BookingActions.canChangeDriver(d.status, d.driverUsername, d.deliveryStatus)) {
            JButton change = UiKit.secondaryButton("Change driver");
            change.addActionListener(e -> chooseAndAssignDriver(false));
            row.add(change);
        }
        if (row.getComponentCount() > 0) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.add(row);
        }
        return box;
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

        List<String> next = BookingStatus.next(d.status);
        if (next.isEmpty()) {
            JLabel fin = new JLabel("This booking is " + d.status + ". No further status changes are possible.");
            fin.setFont(Theme.SUBTLE);
            fin.setForeground(Theme.MUTED);
            row.add(fin);
        } else {
            for (String target : next) {
                boolean cancel = BookingStatus.CANCELLED.equals(target);
                // Confirming with a driver already set is the highlighted "Confirm booking" above.
                if (BookingStatus.CONFIRMED.equals(target) && BookingActions.hasDriver(d.driverUsername)) continue;
                String text = cancel ? "Cancel booking"
                        : BookingStatus.CONFIRMED.equals(target) ? "Confirm without a driver"
                        : "Mark as " + target;
                // Plain (white) buttons: the one highlighted action of this booking is in the Next step box above.
                JButton b = cancel ? button(text, true) : UiKit.secondaryButton(text);
                if (BookingStatus.CONFIRMED.equals(target)) {
                    b.setToolTipText("Confirm now and assign the driver later");
                }
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

    /**
     * Runs a receipt action only after re-reading the booking from the database and finding its driver
     * still saved there, so a receipt is never made for a booking whose assignment did not succeed (or was
     * removed meanwhile).
     */
    private void withSavedDriver(java.util.function.Consumer<BookingService.Detail> action) {
        if (busy) return;
        busy = true;
        new SwingWorker<BookingService.Detail, Void>() {
            @Override
            protected BookingService.Detail doInBackground() throws Exception {
                return BookingService.loadDetail(bookingId);
            }

            @Override
            protected void done() {
                busy = false;
                BookingService.Detail fresh;
                try {
                    fresh = get();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(BookingDetailDialog.this,
                            "Could not check the booking before printing: " + rootMessage(ex),
                            "Receipt", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (fresh == null || !BookingActions.canPrint(fresh.driverUsername)) {
                    JOptionPane.showMessageDialog(BookingDetailDialog.this,
                            fresh == null ? "This booking no longer exists."
                                    : BookingActions.whyNoReceipt(fresh.status, fresh.driverUsername),
                            "Receipt", JOptionPane.INFORMATION_MESSAGE);
                    load();
                    return;
                }
                action.accept(fresh);
            }
        }.execute();
    }

    private void saveReceipt(BookingService.Detail d) {
        if (!BookingActions.canPrint(d.driverUsername)) return;   // only reached through withSavedDriver
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
        if (!BookingActions.canPrint(d.driverUsername)) return;   // only reached through withSavedDriver
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
        if (detail.hasDiscount()) {
            data.discount = detail.discountAmount;
            data.subtotal = detail.subtotal != null ? detail.subtotal : detail.total.add(detail.discountAmount);
            data.discountNote = detail.discountText();
        }
        data.items = new ArrayList<>();
        List<java.math.BigDecimal> lineDiscounts = new ArrayList<>();
        for (BookingService.Line line : detail.items) {
            data.items.add(new BookForClientPanel.OrderItem(
                    "", orDash(line.product), line.unitPrice == null ? 0 : line.unitPrice.doubleValue(),
                    line.quantity, line.subtotal == null ? 0 : line.subtotal.doubleValue()));
            lineDiscounts.add(line.lineDiscount());
        }
        // Discounts per item print on their lines (the receipt's discount column), like Walk-In sales.
        if (lineDiscounts.stream().anyMatch(x -> x.signum() > 0)) data.lineDiscounts = lineDiscounts;
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

    /**
     * Picks a driver and saves it. With {@code confirm} (a Pending booking) the same click also confirms
     * the booking, in one transaction. Choosing the driver in this window is the confirmation; changing an
     * existing driver says so on its button.
     */
    private void chooseAndAssignDriver(boolean confirm) {
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
                            (d.driverUsername == null ? "No" : "No other") + " active driver has an Available vehicle right now."
                                    + (confirm ? "\nYou can confirm the booking without a driver and assign one later." : ""),
                            "Assign driver", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                boolean change = d.driverUsername != null;
                JComboBox<BookingService.DriverOption> combo =
                        new JComboBox<>(options.toArray(new BookingService.DriverOption[0]));
                String title = confirm ? "Confirm & assign driver" : change ? "Change driver" : "Assign driver";
                String okText = confirm ? "Confirm & assign" : change ? "Change driver" : "Assign driver";
                String intro = confirm
                        ? "Confirm booking #" + d.bookingId + " for " + orDash(d.clientName) + " and assign this driver:"
                        : change ? "Booking #" + d.bookingId + " is assigned to " + d.driverUsername + ". Change it to:"
                        : "Driver for booking #" + d.bookingId + ":";
                Object[] buttons = {okText, "Cancel"};
                int ok = JOptionPane.showOptionDialog(BookingDetailDialog.this, new Object[]{intro, combo}, title,
                        JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, buttons, buttons[0]);
                if (ok != 0) return;
                BookingService.DriverOption chosen = (BookingService.DriverOption) combo.getSelectedItem();
                if (chosen != null && Boolean.FALSE.equals(chosen.mobileLinked)
                        && JOptionPane.showConfirmDialog(BookingDetailDialog.this,
                        chosen.username + " has no mobile app sign-in, so this delivery will not appear on a phone.\n"
                                + "Link a sign-in under Accounts if they use the app. Assign anyway?",
                        title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                    return;
                }
                runAssign(d, chosen, confirm);
            }
        }.execute();
    }

    private void runAssign(BookingService.Detail d, BookingService.DriverOption choice, boolean confirm) {
        if (busy || choice == null) return;
        busy = true;
        showMessage(confirm ? "Confirming and assigning..." : "Updating booking...", false);
        final String status = d.status, previous = d.driverUsername;
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return confirm ? BookingService.confirmAndAssignDriver(d.bookingId, previous, choice)
                               : BookingService.assignDriver(d.bookingId, status, previous, choice);
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    boolean audited = get();
                    notice = (confirm ? "Booking confirmed and driver set to " : "Driver set to ")
                            + choice.username + " (vehicle " + choice.vehicleId + "). You can print the receipt now."
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
                                    : (confirm ? "The booking was NOT confirmed and no driver was assigned: "
                                               : "The driver was NOT changed: ") + cause.getMessage(),
                            confirm ? "Not confirmed" : "Driver not changed", JOptionPane.ERROR_MESSAGE);
                    if (onChanged != null) onChanged.run();
                }
                load();   // always show what the database really contains now (Print appears only if it saved)
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
