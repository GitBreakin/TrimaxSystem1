package com.trimaxsystem;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** One Walk-In / In-House sale: lines, totals, payment, receipt, Mark as paid and Void. */
final class SaleDetailDialog {

    private SaleDetailDialog() {
    }

    /** @return true when the sale was changed (paid or voided), so the caller reloads. */
    static boolean open(Window owner, long saleId) {
        final SalesService.Sale s;
        try {
            s = SalesService.detail(saleId);
        } catch (Db.Failure e) {
            JOptionPane.showMessageDialog(owner, e.getMessage(), "Sale", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        JDialog d = new JDialog(owner, "Sale " + s.receiptNo, Dialog.ModalityType.APPLICATION_MODAL);
        final boolean[] changed = {false};

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.CARD);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        boolean voided = "VOIDED".equals(s.status);
        root.add(UiKit.cardHeading("Receipt " + s.receiptNo + (voided ? "  (VOIDED)" : ""),
                SalesService.typeLabel(s.type) + " sale  ·  " + Theme.formatWhen(s.soldAt) + "  ·  by " + s.createdBy),
                BorderLayout.NORTH);

        JPanel info = new JPanel(new GridBagLayout());
        info.setOpaque(false);
        int row = 0;
        UiKit.addDetailRow(info, row++, "Customer", s.customerName == null ? "Walk-in customer"
                : s.customerName + (s.clientContact == null ? "" : "  ·  " + s.clientContact), 420);
        if (s.clientAddress != null) UiKit.addDetailRow(info, row++, "Address", s.clientAddress, 420);
        UiKit.addDetailRow(info, row++, "Payment", "PAID".equals(s.paymentStatus)
                ? "Paid by " + SalesService.methodLabel(s.paymentMethod) + " on " + Theme.formatWhen(s.paidAt)
                + (s.tendered == null ? "" : "  ·  received " + Money.peso(s.tendered) + ", change " + Money.peso(s.change))
                : "UNPAID: " + Money.peso(s.total) + " to collect (not counted as a sale until paid)", 420);
        if (voided) UiKit.addDetailRow(info, row++, "Voided", Theme.formatWhen(s.voidedAt) + " by " + s.voidedBy
                + ": " + s.voidReason + ". The stock was returned.", 420);
        if (s.note != null) UiKit.addDetailRow(info, row++, "Note", s.note, 420);

        DefaultTableModel m = new DefaultTableModel(new String[]{"Product", "Qty", "Price", "Subtotal", "Discount", "Total"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (SalesService.SaleLine l : s.lines) {
            m.addRow(new Object[]{l.name, l.quantity, Money.plain(l.unitPrice), Money.plain(l.subtotal),
                    l.discount.signum() == 0 ? "" : "−" + Money.plain(l.discount), Money.plain(l.total)});
        }
        JTable t = new JTable(m);
        UiKit.styleTable(t, 32);
        t.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.RIGHT, false, false));
        t.getColumnModel().getColumn(0).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        t.getColumnModel().getColumn(5).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, true, false));
        t.getColumnModel().getColumn(0).setPreferredWidth(260);
        UiKit.alignHeaders(t, 1, 2, 3, 4, 5);
        JScrollPane sp = UiKit.tableScroll(t);
        sp.setPreferredSize(new Dimension(640, Math.min(260, 40 + 32 * s.lines.size())));
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.BORDER));

        JLabel totals = new JLabel("<html><div style='text-align:right'>Subtotal " + Money.peso(s.subtotal)
                + (s.discount.signum() > 0 ? "<br>Discounts −" + Money.peso(s.discount) : "")
                + "<br><b style='font-size:14px'>TOTAL " + Money.peso(s.total) + "</b></div></html>");
        totals.setFont(Theme.SUBTLE);
        totals.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.add(info, BorderLayout.NORTH);
        center.add(sp, BorderLayout.CENTER);
        center.add(totals, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        JButton print = UiKit.secondaryButton("Print receipt");
        JButton save = UiKit.secondaryButton("Save PDF");
        JButton paid = UiKit.secondaryButton("Mark as paid...");
        JButton voidBtn = UiKit.dangerButton("Void sale...");
        JButton close = UiKit.primaryButton("Close");
        print.addActionListener(e -> ReceiptActions.print(d, SalesService.receipt(s), "TRI-MAX receipt " + s.receiptNo));
        save.addActionListener(e -> ReceiptActions.save(d, SalesService.receipt(s), "Receipt_" + s.receiptNo + ".pdf"));
        paid.setVisible(!voided && "UNPAID".equals(s.paymentStatus));
        voidBtn.setVisible(!voided);
        close.addActionListener(e -> d.dispose());
        paid.addActionListener(e -> {
            String[] labels = new String[SalesService.METHODS.length];
            for (int i = 0; i < labels.length; i++) labels[i] = SalesService.METHODS[i][1];
            Object pick = JOptionPane.showInputDialog(d, "Receipt " + s.receiptNo + ": " + Money.peso(s.total)
                            + " received.\nHow did the customer pay?", "Mark as paid", JOptionPane.QUESTION_MESSAGE, null,
                    labels, labels[0]);
            if (pick == null) return;
            String code = SalesService.METHODS[java.util.Arrays.asList(labels).indexOf(pick)][0];
            try {
                SalesService.markPaid(s.id, code);
                changed[0] = true;
                d.dispose();
                JOptionPane.showMessageDialog(owner, "Receipt " + s.receiptNo + " is now paid. It counts as a sale on "
                        + Theme.formatWhen(s.soldAt).substring(0, 12) + ".", "Paid", JOptionPane.INFORMATION_MESSAGE);
            } catch (Db.Failure ex) {
                JOptionPane.showMessageDialog(d, ex.getMessage(), "Not recorded", JOptionPane.WARNING_MESSAGE);
            }
        });
        voidBtn.addActionListener(e -> {
            String reason = JOptionPane.showInputDialog(d, "Void receipt " + s.receiptNo + " (" + Money.peso(s.total) + ")?\n\n"
                            + "The stock of its " + s.lines.size() + " line(s) goes back to inventory and the sale no longer counts.\n"
                            + "It stays on record with your name and the reason. This cannot be undone.\n\nReason:",
                    "Void sale", JOptionPane.WARNING_MESSAGE);
            if (reason == null) return;
            if (reason.trim().length() < 3) {
                JOptionPane.showMessageDialog(d, "Enter the reason (at least 3 characters).", "Void sale", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                SalesService.voidSale(s.id, reason.trim());
                changed[0] = true;
                d.dispose();
                JOptionPane.showMessageDialog(owner, "Receipt " + s.receiptNo + " was voided and its stock returned.",
                        "Voided", JOptionPane.INFORMATION_MESSAGE);
            } catch (Db.Failure ex) {
                JOptionPane.showMessageDialog(d, ex.getMessage(), "Not voided", JOptionPane.WARNING_MESSAGE);
            }
        });
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        JPanel leftB = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftB.setOpaque(false);
        leftB.add(print);
        leftB.add(save);
        JPanel rightB = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightB.setOpaque(false);
        rightB.add(paid);
        rightB.add(voidBtn);
        rightB.add(close);
        buttons.add(leftB, BorderLayout.WEST);
        buttons.add(rightB, BorderLayout.EAST);
        root.add(buttons, BorderLayout.SOUTH);

        d.setContentPane(root);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return changed[0];
    }
}
