package com.trimaxsystem;

import java.awt.*;
import java.math.BigDecimal;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Restock (newly bought stock with its cost per unit) and Correct stock (a reason is required).
 * Each dialog keeps one request id, so a double click or a retry after a lost connection never adds
 * the stock twice; the database checks everything again.
 */
final class StockDialogs {

    private StockDialogs() {
    }

    /** @return true when stock was added (the caller reloads). */
    static boolean restock(Window owner, InventoryService.Product p) {
        JDialog d = new JDialog(owner, "Restock: " + p.name, Dialog.ModalityType.APPLICATION_MODAL);
        final UUID requestId = Db.requestId();
        final boolean[] saved = {false};

        JTextField qty = new JTextField(8);
        JTextField unitCost = new JTextField(8);
        JTextField note = new JTextField(24);
        JCheckBox useAsCost = new JCheckBox();
        JLabel total = new JLabel(" ");
        JLabel error = new JLabel(" ");
        for (JTextField f : new JTextField[]{qty, unitCost, note}) UiKit.styleField(f);
        UiKit.placeholder(qty, "e.g. 24");
        UiKit.placeholder(note, "Supplier, invoice no. (optional)");
        if (p.cost != null) unitCost.setText(p.cost.toPlainString());
        useAsCost.setOpaque(false);
        useAsCost.setFont(Theme.SUBTLE);
        useAsCost.setSelected(p.cost == null);
        total.setFont(Theme.font(Font.BOLD, 14));
        error.setFont(Theme.SMALL);
        error.setForeground(Theme.DANGER);

        Runnable recalc = () -> {
            error.setText(" ");
            try {
                BigDecimal c = Money.parse(unitCost.getText(), "cost per unit", true);
                int q = qty.getText().isBlank() ? 0 : Money.parseInt(qty.getText(), "quantity", 1, 100_000);
                total.setText(c == null || q == 0 ? "Total cost: —"
                        : "Total cost: " + Money.peso(c.multiply(BigDecimal.valueOf(q))));
                String text = "Use " + (c == null ? "this cost" : Money.peso(c)) + " as the new puhunan";
                if (c != null && p.cost != null && c.compareTo(p.cost) == 0) {
                    text += "<br>(same as now)";
                } else if (c != null && p.markupType != null) {
                    text += "<br>(the selling price becomes " + Money.peso(Money.sellingPrice(c, p.markupType, p.markupValue)) + ")";
                } else if (p.cost == null) {
                    text += "<br>(this product has no puhunan yet)";
                }
                useAsCost.setText("<html>" + text + "</html>");
            } catch (IllegalArgumentException ex) {
                total.setText("Total cost: —");
                error.setText(ex.getMessage());
            }
        };
        DocumentListener dl = listener(recalc);
        qty.getDocument().addDocumentListener(dl);
        unitCost.getDocument().addDocumentListener(dl);
        recalc.run();

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.gridwidth = 2;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 10, 10);
        JLabel now = new JLabel("<html>In stock now: <b>" + p.stock + "</b> &nbsp;·&nbsp; Puhunan now: <b>"
                + (p.cost == null ? "none" : Money.peso(p.cost)) + "</b> &nbsp;·&nbsp; Selling price: <b>"
                + Money.peso(p.price) + "</b></html>");
        now.setFont(Theme.SUBTLE);
        form.add(now, g);
        g.gridy++;
        g.gridwidth = 1;
        g.weightx = 0.5;
        form.add(UiKit.group("Quantity received", qty), g);
        g.gridx = 1;
        form.add(UiKit.group("Cost per unit (₱)", unitCost), g);
        g.gridx = 0;
        g.gridy++;
        g.gridwidth = 2;
        form.add(UiKit.group("Note", note), g);
        g.gridy++;
        form.add(total, g);
        g.gridy++;
        form.add(useAsCost, g);
        g.gridy++;
        JLabel hint = new JLabel("<html>A restock is a purchase of stock. It is never counted as a sale or revenue.</html>");
        hint.setFont(Theme.SMALL);
        hint.setForeground(Theme.MUTED);
        form.add(hint, g);
        g.gridy++;
        form.add(error, g);

        JButton save = UiKit.primaryButton("Save restock");
        JButton cancel = UiKit.secondaryButton("Cancel");
        cancel.addActionListener(e -> d.dispose());
        save.addActionListener(e -> {
            final int q;
            final BigDecimal c;
            try {
                q = Money.parseInt(qty.getText(), "quantity received", 1, 100_000);
                c = Money.parse(unitCost.getText(), "cost per unit", false);
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                return;
            }
            save.setEnabled(false);
            cancel.setEnabled(false);
            save.setText("Saving...");
            final String n = note.getText().trim();
            final boolean use = useAsCost.isSelected();
            new SwingWorker<InventoryService.RestockResult, Void>() {
                @Override
                protected InventoryService.RestockResult doInBackground() throws Exception {
                    return InventoryService.restock(p.id, q, c, n.isEmpty() ? null : n, use, requestId);
                }

                @Override
                protected void done() {
                    try {
                        InventoryService.RestockResult r = get();
                        saved[0] = true;
                        d.dispose();
                        JOptionPane.showMessageDialog(owner, p.name + "\nStock " + r.stockBefore + " → " + r.stockAfter
                                        + ". Purchase of " + Money.peso(r.totalCost) + " recorded."
                                        + (use ? "\nPuhunan is now " + Money.peso(c) + "; selling price " + Money.peso(r.price) + "." : ""),
                                "Restocked", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        error.setText("<html>" + esc(cause.getMessage()) + "</html>");
                        save.setEnabled(true);
                        cancel.setEnabled(true);
                        save.setText("Try again");
                    }
                }
            }.execute();
        });

        show(d, owner, form, save, cancel, "Record newly bought stock");
        return saved[0];
    }

    private static final String[] REASONS = {"Count difference", "Damaged", "Expired", "Returned by customer", "Lost", "Other"};

    /** @return true when the stock was corrected (the caller reloads). */
    static boolean adjust(Window owner, InventoryService.Product p) {
        JDialog d = new JDialog(owner, "Correct stock: " + p.name, Dialog.ModalityType.APPLICATION_MODAL);
        final UUID requestId = Db.requestId();
        final boolean[] saved = {false};

        JRadioButton remove = new JRadioButton("Remove", true);
        JRadioButton add = new JRadioButton("Add");
        ButtonGroup dir = new ButtonGroup();
        dir.add(remove);
        dir.add(add);
        for (JRadioButton b : new JRadioButton[]{remove, add}) {
            b.setOpaque(false);
            b.setFont(Theme.SUBTLE);
        }
        JTextField qty = new JTextField(8);
        JComboBox<String> reason = new JComboBox<>(REASONS);
        JTextField detail = new JTextField(24);
        JLabel result = new JLabel(" ");
        JLabel error = new JLabel(" ");
        UiKit.styleField(qty);
        UiKit.styleField(detail);
        UiKit.placeholder(detail, "Details (required for Other)");
        reason.setFont(Theme.SUBTLE);
        result.setFont(Theme.font(Font.BOLD, 14));
        error.setFont(Theme.SMALL);
        error.setForeground(Theme.DANGER);

        Runnable recalc = () -> {
            error.setText(" ");
            try {
                int q = qty.getText().isBlank() ? 0 : Money.parseInt(qty.getText(), "quantity", 1, 1_000_000);
                int after = p.stock + (add.isSelected() ? q : -q);
                result.setText("Stock " + p.stock + " → " + after);
                result.setForeground(after < 0 ? Theme.DANGER : Theme.TEXT);
                if (after < 0) error.setText("Stock cannot go below 0.");
            } catch (IllegalArgumentException ex) {
                result.setText(" ");
                error.setText(ex.getMessage());
            }
        };
        qty.getDocument().addDocumentListener(listener(recalc));
        remove.addActionListener(e -> recalc.run());
        add.addActionListener(e -> recalc.run());
        recalc.run();

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.gridwidth = 2;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 10, 10);
        JLabel now = new JLabel("<html>In stock now: <b>" + p.stock + "</b></html>");
        now.setFont(Theme.SUBTLE);
        form.add(now, g);
        g.gridy++;
        JPanel dirRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        dirRow.setOpaque(false);
        dirRow.add(remove);
        dirRow.add(Box.createHorizontalStrut(12));
        dirRow.add(add);
        form.add(dirRow, g);
        g.gridy++;
        g.gridwidth = 1;
        form.add(UiKit.group("Quantity", qty), g);
        g.gridx = 1;
        form.add(UiKit.group("Reason", reason), g);
        g.gridx = 0;
        g.gridy++;
        g.gridwidth = 2;
        form.add(UiKit.group("Details", detail), g);
        g.gridy++;
        form.add(result, g);
        g.gridy++;
        form.add(error, g);

        JButton save = UiKit.primaryButton("Save correction");
        JButton cancel = UiKit.secondaryButton("Cancel");
        cancel.addActionListener(e -> d.dispose());
        save.addActionListener(e -> {
            final int change;
            try {
                int q = Money.parseInt(qty.getText(), "quantity", 1, 1_000_000);
                change = add.isSelected() ? q : -q;
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                return;
            }
            String r = (String) reason.getSelectedItem();
            String more = detail.getText().trim();
            if ("Other".equals(r) && more.length() < 3) {
                error.setText("Describe the reason in Details.");
                return;
            }
            final String why = more.isEmpty() ? r : r + ": " + more;
            save.setEnabled(false);
            cancel.setEnabled(false);
            save.setText("Saving...");
            new SwingWorker<int[], Void>() {
                @Override
                protected int[] doInBackground() throws Exception {
                    return InventoryService.adjust(p.id, change, why, requestId);
                }

                @Override
                protected void done() {
                    try {
                        int[] r2 = get();
                        saved[0] = true;
                        d.dispose();
                        JOptionPane.showMessageDialog(owner, p.name + "\nStock " + r2[0] + " → " + r2[1] + " (" + why + ").",
                                "Stock corrected", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        error.setText("<html>" + esc(cause.getMessage()) + "</html>");
                        save.setEnabled(true);
                        cancel.setEnabled(true);
                        save.setText("Try again");
                    }
                }
            }.execute();
        });

        show(d, owner, form, save, cancel, "Fix the stock count, with the reason");
        return saved[0];
    }

    private static void show(JDialog d, Window owner, JPanel form, JButton primary, JButton cancel, String subtitle) {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.CARD);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        root.add(UiKit.cardHeading(d.getTitle(), subtitle), BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(primary);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(primary);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.pack();
        d.setMinimumSize(new Dimension(Math.max(460, d.getWidth()), d.getHeight()));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    static DocumentListener listener(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
