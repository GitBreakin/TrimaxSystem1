package com.trimaxsystem;

import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Restock (newly bought stock with its cost per unit), Correct stock (a reason is required) and Bad order
 * (broken, damaged, expired... items taken out of stock as a loss).
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

    /**
     * badOrders: the server has bad orders (20261028_04); then Damaged / Expired point to Bad order, so the
     * loss is counted.
     * @return true when the stock was corrected (the caller reloads).
     */
    static boolean adjust(Window owner, InventoryService.Product p, boolean badOrders) {
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

        final String boText = "<html><div style='width:380px'>Broken, damaged or expired items: use <b>Bad order...</b> "
                + "instead, so their puhunan is counted as a loss in Sales &amp; Profit.</div></html>";
        JLabel boHint = new JLabel(boText);
        boHint.setFont(Theme.SMALL);
        boHint.setForeground(Theme.WARN);
        boHint.setPreferredSize(boHint.getPreferredSize());   // keeps its room while it is empty
        boHint.setVisible(badOrders);
        Runnable recalc = () -> {
            error.setText(" ");
            String why = (String) reason.getSelectedItem();
            boHint.setText(remove.isSelected() && ("Damaged".equals(why) || "Expired".equals(why)) ? boText : "");
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
        reason.addActionListener(e -> recalc.run());
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
        form.add(boHint, g);
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

    /**
     * Bad order (BO): broken, damaged, expired, defective or spoiled pieces taken out of stock, with what
     * happened to them and the date found. The loss (pieces × puhunan) lowers net profit by that date.
     * p: the product to start with (null = choose); choices: the products to choose from (null = only p).
     * @return true when it was recorded (the caller reloads).
     */
    static boolean badOrder(Window owner, InventoryService.Product p, java.util.Collection<InventoryService.Product> choices) {
        JDialog d = new JDialog(owner, p == null ? "Bad order" : "Bad order: " + p.name, Dialog.ModalityType.APPLICATION_MODAL);
        final UUID requestId = Db.requestId();
        final boolean[] saved = {false};
        final LocalDate today = LocalDate.now(Theme.BUSINESS_TZ);

        JComboBox<ProductChoice> product = new JComboBox<>();
        java.util.List<InventoryService.Product> list = new java.util.ArrayList<>();
        if (choices != null) list.addAll(choices);
        else if (p != null) list.add(p);
        list.sort(java.util.Comparator.comparing(x -> x.name.toLowerCase()));
        if (p == null) product.addItem(new ProductChoice(null));
        for (InventoryService.Product x : list) {
            ProductChoice c = new ProductChoice(x);
            product.addItem(c);
            if (p != null && x.id == p.id) product.setSelectedItem(c);
        }
        product.setFont(Theme.SUBTLE);
        product.setEnabled(choices != null);

        JTextField qty = new JTextField(8);
        JComboBox<String> reason = new JComboBox<>();
        for (String[] r : BadOrderService.REASONS) reason.addItem(r[1]);
        JRadioButton disposed = new JRadioButton(BadOrderService.DISPOSITIONS[0][1], true);
        JRadioButton returned = new JRadioButton(BadOrderService.DISPOSITIONS[1][1]);
        ButtonGroup what = new ButtonGroup();
        what.add(disposed);
        what.add(returned);
        for (JRadioButton b : new JRadioButton[]{disposed, returned}) {
            b.setOpaque(false);
            b.setFont(Theme.SUBTLE);
        }
        JTextField found = new JTextField(10);
        found.setText(today.toString());
        found.setToolTipText("YYYY-MM-DD: the day the bad items were found (Asia/Manila)");
        JTextField note = new JTextField(26);
        // Loss to deduct: pieces x puhunan, or an amount set by hand (then 0 pieces = part of one item)
        JRadioButton lossPuhunan = new JRadioButton("Puhunan \u00D7 pieces", true);
        JRadioButton lossHand = new JRadioButton("Other amount:");
        ButtonGroup lossGroup = new ButtonGroup();
        lossGroup.add(lossPuhunan);
        lossGroup.add(lossHand);
        for (JRadioButton b : new JRadioButton[]{lossPuhunan, lossHand}) {
            b.setOpaque(false);
            b.setFont(Theme.SUBTLE);
        }
        JTextField amount = new JTextField(7);
        UiKit.styleField(amount);
        UiKit.placeholder(amount, "\u20B1 e.g. 10");
        amount.setToolTipText("The peso loss to deduct from net profit, for example P10 for one broken bottle of a pack");
        final String partText = "<html><div style='width:440px'>0 pieces = only part of one item is bad (for example one bottle "
                + "of a 6-pack): the stock stays the same, only the amount is deducted.</div></html>";
        JLabel partHint = new JLabel(partText);
        partHint.setFont(Theme.SMALL);
        partHint.setForeground(Theme.MUTED);
        partHint.setPreferredSize(partHint.getPreferredSize());   // keeps its room while it is empty
        JLabel now = new JLabel(" ");
        JLabel result = new JLabel(" ");
        JLabel error = new JLabel(" ");
        UiKit.styleField(qty);
        UiKit.styleField(found);
        UiKit.styleField(note);
        UiKit.placeholder(qty, "e.g. 3");
        UiKit.placeholder(note, "What happened (required for Other)");
        reason.setFont(Theme.SUBTLE);
        now.setFont(Theme.SUBTLE);
        result.setFont(Theme.font(Font.BOLD, 14));
        error.setFont(Theme.SMALL);
        error.setForeground(Theme.DANGER);

        Runnable recalc = () -> {
            error.setText(" ");
            InventoryService.Product cur = ((ProductChoice) product.getSelectedItem()).product;
            if (cur == null) {
                now.setText("Choose the product.");
                result.setText(" ");
                return;
            }
            now.setText("<html>In stock now: <b>" + cur.stock + "</b> &nbsp;·&nbsp; Puhunan: <b>"
                    + (cur.cost == null ? "none" : Money.peso(cur.cost)) + "</b>"
                    + (cur.supplier == null || cur.supplier.isBlank() ? "" : " &nbsp;·&nbsp; Supplier: <b>" + esc(cur.supplier) + "</b>")
                    + "</html>");
            boolean hand = lossHand.isSelected();
            amount.setEnabled(hand);
            partHint.setText(hand ? partText : "");
            try {
                Integer q = qty.getText().isBlank() ? null : Money.parseInt(qty.getText(), "quantity", hand ? 0 : 1, 1_000_000);
                lossPuhunan.setText(cur.cost == null ? "Puhunan \u00D7 pieces (no puhunan: no loss)"
                        : "Puhunan \u00D7 pieces" + (q == null || q == 0 ? " (" + Money.peso(cur.cost) + " each)"
                        : " (" + Money.peso(BadOrderService.loss(q, cur.cost)) + ")"));
                BigDecimal custom = hand ? Money.parse(amount.getText(), "amount to deduct", true) : null;
                if (q == null || (hand && custom == null)) {
                    result.setText(" ");
                } else {
                    result.setText(BadOrderService.preview(cur.stock, q, cur.cost, custom));
                }
                result.setForeground(q != null && q > cur.stock ? Theme.DANGER : Theme.TEXT);
                if (q != null && q > cur.stock) error.setText("Only " + cur.stock + " in stock.");
            } catch (IllegalArgumentException ex) {
                result.setText(" ");
                error.setText(ex.getMessage());
            }
        };
        qty.getDocument().addDocumentListener(listener(recalc));
        amount.getDocument().addDocumentListener(listener(recalc));
        product.addActionListener(e -> recalc.run());
        lossPuhunan.addActionListener(e -> recalc.run());
        lossHand.addActionListener(e -> {
            recalc.run();
            amount.requestFocusInWindow();
        });
        recalc.run();

        JButton todayBtn = UiKit.secondaryButton("Today");
        todayBtn.addActionListener(e -> found.setText(today.toString()));

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.gridwidth = 2;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 10, 10);
        if (choices != null) {
            form.add(UiKit.group("Product", product), g);
            g.gridy++;
        }
        form.add(now, g);
        g.gridy++;
        g.gridwidth = 1;
        g.weightx = 0.5;
        form.add(UiKit.group("Bad pieces", qty), g);
        g.gridx = 1;
        form.add(UiKit.group("Reason", reason), g);
        g.gridx = 0;
        g.gridy++;
        JPanel whatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        whatRow.setOpaque(false);
        whatRow.add(disposed);
        whatRow.add(Box.createHorizontalStrut(12));
        whatRow.add(returned);
        form.add(UiKit.group("What happened to them", whatRow), g);
        g.gridx = 1;
        JPanel dateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        dateRow.setOpaque(false);
        dateRow.add(found);
        dateRow.add(todayBtn);
        form.add(UiKit.group("Date found", dateRow), g);
        g.gridx = 0;
        g.gridy++;
        g.gridwidth = 2;
        JPanel lossRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        lossRow.setOpaque(false);
        lossRow.add(lossPuhunan);
        lossRow.add(Box.createHorizontalStrut(14));
        lossRow.add(lossHand);
        lossRow.add(Box.createHorizontalStrut(6));
        lossRow.add(amount);
        form.add(UiKit.group("Loss to deduct", lossRow), g);
        g.gridy++;
        form.add(partHint, g);
        g.gridy++;
        form.add(UiKit.group("Note", note), g);
        g.gridy++;
        form.add(result, g);
        g.gridy++;
        JLabel hint = new JLabel("<html><div style='width:440px'>The pieces leave the stock now. The loss (their puhunan, "
                + "or the amount you set) lowers net profit in Sales &amp; Profit on the date found. A mistake can be "
                + "undone in Inventory → Bad orders.</div></html>");
        hint.setFont(Theme.SMALL);
        hint.setForeground(Theme.MUTED);
        form.add(hint, g);
        g.gridy++;
        form.add(error, g);

        JButton save = UiKit.dangerButton("Record bad order");
        JButton cancel = UiKit.secondaryButton("Cancel");
        cancel.addActionListener(e -> d.dispose());
        save.addActionListener(e -> {
            final InventoryService.Product cur = ((ProductChoice) product.getSelectedItem()).product;
            if (cur == null) {
                error.setText("Choose the product.");
                return;
            }
            final boolean hand = lossHand.isSelected();
            Integer q;
            try {
                q = Money.parseInt(qty.getText(), "bad pieces", hand ? 0 : 1, 1_000_000);
            } catch (IllegalArgumentException ex) {
                q = null;
            }
            final BigDecimal custom;
            try {
                custom = hand ? Money.parse(amount.getText(), "amount to deduct", false) : null;
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                return;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(found.getText().trim());
            } catch (java.time.format.DateTimeParseException ex) {
                day = null;
            }
            final String r = BadOrderService.REASONS[Math.max(0, reason.getSelectedIndex())][0];
            final String n = note.getText().trim();
            String problem = BadOrderService.check(q, cur.stock, r, n, day, today, custom);
            if (problem != null) {
                error.setText(problem);
                return;
            }
            final int quantity = q;
            final LocalDate foundOn = day;
            final String disp = BadOrderService.DISPOSITIONS[returned.isSelected() ? 1 : 0][0];
            save.setEnabled(false);
            cancel.setEnabled(false);
            product.setEnabled(false);
            save.setText("Saving...");
            new SwingWorker<BadOrderService.Result, Void>() {
                @Override
                protected BadOrderService.Result doInBackground() throws Exception {
                    return BadOrderService.record(cur.id, quantity, r, disp, foundOn, n.isEmpty() ? null : n, requestId, custom);
                }

                @Override
                protected void done() {
                    try {
                        BadOrderService.Result res = get();
                        saved[0] = true;
                        d.dispose();
                        JOptionPane.showMessageDialog(owner, cur.name + "\n"
                                        + (quantity == 0 ? "Part of one item" : String.valueOf(quantity)) + " "
                                        + BadOrderService.reasonLabel(r).toLowerCase() + ", "
                                        + BadOrderService.dispositionLabel(disp).toLowerCase() + " (found " + foundOn + ")."
                                        + (quantity == 0 ? "\nStock stays " + res.stockAfter + ". "
                                        : "\nStock " + res.stockBefore + " → " + res.stockAfter + ". ")
                                        + (res.loss == null ? "No puhunan, so no peso loss was counted."
                                        : "Loss " + Money.peso(res.loss) + (custom != null ? " (set by hand)" : "")
                                        + " lowers net profit."),
                                "Bad order recorded", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        error.setText("<html>" + esc(cause.getMessage()) + "</html>");
                        save.setEnabled(true);
                        cancel.setEnabled(true);
                        product.setEnabled(choices != null);
                        save.setText("Try again");
                    }
                }
            }.execute();
        });

        show(d, owner, form, save, cancel, "Broken, damaged, expired, defective or spoiled items");
        return saved[0];
    }

    private static final class ProductChoice {
        final InventoryService.Product product;

        ProductChoice(InventoryService.Product product) {
            this.product = product;
        }

        @Override
        public String toString() {
            if (product == null) return "Choose a product...";
            return product.name + "  (" + product.stock + " in stock)";
        }
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
