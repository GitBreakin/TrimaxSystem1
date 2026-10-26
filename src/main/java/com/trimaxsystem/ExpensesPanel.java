package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Other expenses (Admin only; agents and drivers never see it): electricity, rent, fuel and other costs.
 * Unpaid or Paid (with the date and how). Wrong ones are voided with a reason, never deleted; every
 * create, edit and void is in the expense history and the audit log. Non-voided expenses lower net
 * profit in Sales & Profit by their date (net profit = gross profit - payroll - other expenses).
 */
public class ExpensesPanel extends JPanel {

    private static final String[] STATUS = {"Unpaid and paid", "Unpaid", "Paid", "Voided", "All, with voided"};
    private static final String[] STATUS_CODE = {null, "UNPAID", "PAID", "VOID", "ALL"};

    private final JComboBox<String> category = new JComboBox<>();
    private final JComboBox<String> status = new JComboBox<>(STATUS);
    private final JTextField from = new JTextField(9), to = new JTextField(9), search = new JTextField(14);
    private final JLabel kCount = kpi(), kTotal = kpi(), kPaid = kpi(), kUnpaid = kpi();
    private final DefaultTableModel model = new DefaultTableModel(new String[]{
            "#", "Date", "Category", "Description", "Amount", "Status", "Paid on", "Paid by", "Entered by"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final List<ExpenseService.Record> rows = new ArrayList<>();
    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JButton btnNew = UiKit.primaryButton("New expense");
    private final JButton btnNewFromEmpty = UiKit.primaryButton("New expense");
    private final JButton btnEdit = UiKit.secondaryButton("Edit...");
    private final JButton btnHistory = UiKit.secondaryButton("History...");
    private final JButton btnVoid = UiKit.dangerButton("Void...");
    private int seq;

    public ExpensesPanel() {
        super(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 20, 16, 20));

        YearMonth ym = YearMonth.now(Theme.BUSINESS_TZ);
        from.setText(ym.atDay(1).toString());
        to.setText(ym.atEndOfMonth().toString());
        for (JTextField f : new JTextField[]{from, to}) {
            UiKit.styleField(f);
            f.setToolTipText("YYYY-MM-DD: expenses dated in this range");
            f.addActionListener(e -> load());
        }
        UiKit.searchField(search, "Words in the description");
        search.addActionListener(e -> load());
        category.addItem("All categories");
        for (String[] c : ExpenseService.CATEGORIES) category.addItem(c[1]);
        category.setFont(Theme.SUBTLE);
        status.setFont(Theme.SUBTLE);
        JButton show = UiKit.primaryButton("Show");
        show.addActionListener(e -> load());
        status.addActionListener(e -> load());
        category.addActionListener(e -> load());
        JButton thisMonth = UiKit.secondaryButton("This month");
        JButton lastMonth = UiKit.secondaryButton("Last month");
        thisMonth.addActionListener(e -> setMonth(YearMonth.now(Theme.BUSINESS_TZ)));
        lastMonth.addActionListener(e -> setMonth(YearMonth.now(Theme.BUSINESS_TZ).minusMonths(1)));

        JPanel filters = UiKit.card(new BorderLayout(), 10, 14);
        JPanel row = UiKit.flowRow();
        row.add(UiKit.group("Category", category));
        row.add(UiKit.group("Status", status));
        row.add(UiKit.group("Dated from", from));
        row.add(UiKit.group("to", to));
        row.add(UiKit.group("Search", search));
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        b.setOpaque(false);
        b.add(thisMonth);
        b.add(lastMonth);
        b.add(show);
        JPanel bWrap = new JPanel(new BorderLayout());
        bWrap.setOpaque(false);
        bWrap.add(b, BorderLayout.SOUTH);
        row.add(bWrap);
        filters.add(row, BorderLayout.CENTER);

        JPanel kpis = new JPanel(new GridLayout(1, 4, 10, 0));
        kpis.setOpaque(false);
        kpis.add(kpiCard("Expenses", kCount, "voided ones not counted"));
        kpis.add(kpiCard("Total", kTotal, "lowers net profit for these dates"));
        kpis.add(kpiCard("Paid", kPaid, "already paid"));
        kpis.add(kpiCard("Unpaid", kUnpaid, "still to pay (bills, rent due)"));

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        kpis.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(filters);
        north.add(Box.createVerticalStrut(10));
        north.add(kpis);
        add(north, BorderLayout.NORTH);

        UiKit.styleTable(table, 38);
        table.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        table.getColumnModel().getColumn(0).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, false, true));
        table.getColumnModel().getColumn(3).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, true, false));
        table.getColumnModel().getColumn(4).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, true, false));
        table.getColumnModel().getColumn(5).setCellRenderer(new UiKit.PillRenderer(
                s -> "Paid".equals(s) ? Theme.OK : "Unpaid".equals(s) ? Theme.WARN : Theme.MUTED));
        UiKit.alignHeaders(table, 4);
        int[] widths = {50, 100, 150, 280, 110, 90, 100, 110, 120};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) edit();
            }
        });

        btnNew.addActionListener(e -> createNew());
        btnNewFromEmpty.addActionListener(e -> createNew());
        btnEdit.addActionListener(e -> edit());
        btnHistory.addActionListener(e -> showHistory());
        btnVoid.addActionListener(e -> voidSelected());

        JPanel tableCard = UiKit.card(new BorderLayout(0, 8), 12, 14);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(UiKit.cardHeading("Other expenses", "Net profit = gross profit − payroll − other expenses"), BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnVoid);
        actions.add(btnHistory);
        actions.add(btnEdit);
        actions.add(btnNew);
        head.add(actions, BorderLayout.EAST);
        tableCard.add(head, BorderLayout.NORTH);
        tableCard.add(UiKit.tableScroll(table), BorderLayout.CENTER);
        center.setOpaque(false);
        center.add(tableCard, "table");
        JPanel stateCard = UiKit.card(new BorderLayout(), 12, 14);
        JPanel stateActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        stateActions.setOpaque(false);
        stateActions.add(btnNewFromEmpty);
        stateCard.add(stateActions, BorderLayout.NORTH);
        stateCard.add(state, BorderLayout.CENTER);
        center.add(stateCard, "state");
        add(center, BorderLayout.CENTER);
        updateButtons();

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) load();
        });
    }

    private void setMonth(YearMonth m) {
        from.setText(m.atDay(1).toString());
        to.setText(m.atEndOfMonth().toString());
        load();
    }

    private static JLabel kpi() {
        JLabel l = new JLabel("—");
        l.setFont(Theme.font(Font.BOLD, 20));
        l.setForeground(Theme.TEXT);
        return l;
    }

    private static JPanel kpiCard(String title, JLabel value, String hint) {
        JPanel c = UiKit.card(new BorderLayout(0, 4), 12, 14);
        JLabel h = new JLabel(hint);
        h.setFont(Theme.SMALL);
        h.setForeground(Theme.MUTED);
        c.add(UiKit.caption(title.toUpperCase()), BorderLayout.NORTH);
        c.add(value, BorderLayout.CENTER);
        c.add(h, BorderLayout.SOUTH);
        return c;
    }

    private ExpenseService.Record selected() {
        int r = table.getSelectedRow();
        return r < 0 ? null : rows.get(table.convertRowIndexToModel(r));
    }

    private void updateButtons() {
        ExpenseService.Record rec = selected();
        boolean live = rec != null && !rec.voided();
        btnEdit.setEnabled(live);
        btnVoid.setEnabled(live);
        btnHistory.setEnabled(rec != null);
    }

    private void createNew() {
        if (ExpenseEditor.open(SwingUtilities.getWindowAncestor(this), null)) load();
    }

    void load() {
        String missing = Db.missingExpensesUpdate();
        if (!missing.isEmpty()) {
            state.showError("Expenses are not set up on the server yet", Db.notReadyMessage("The Expenses page", missing), this::load);
            cards.show(center, "state");
            btnNew.setEnabled(false);
            btnNewFromEmpty.setEnabled(false);
            return;
        }
        btnNew.setEnabled(true);
        btnNewFromEmpty.setEnabled(true);
        final LocalDate f, t;
        try {
            f = LocalDate.parse(from.getText().trim());
            t = LocalDate.parse(to.getText().trim());
        } catch (DateTimeParseException e) {
            state.showError("Check the dates", "Write dates as YYYY-MM-DD, for example 2026-10-01.", null);
            cards.show(center, "state");
            return;
        }
        if (t.isBefore(f)) {
            state.showError("Check the dates", "The To date is before the From date.", null);
            cards.show(center, "state");
            return;
        }
        int ci = category.getSelectedIndex();
        final String cat = ci <= 0 ? null : ExpenseService.CATEGORIES[ci - 1][0];
        final String st = STATUS_CODE[Math.max(0, status.getSelectedIndex())];
        final String q = search.getText().trim();
        final int mySeq = ++seq;
        state.showLoading("Loading expenses...");
        cards.show(center, "state");
        new SwingWorker<ExpenseService.Page, Void>() {
            @Override
            protected ExpenseService.Page doInBackground() throws Exception {
                return ExpenseService.list(cat, st, f, t, q);
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                try {
                    ExpenseService.Page p = get();
                    rows.clear();
                    model.setRowCount(0);
                    kCount.setText(String.valueOf(p.count));
                    kTotal.setText(Money.peso(p.total));
                    kPaid.setText(Money.peso(p.paid));
                    kUnpaid.setText(Money.peso(p.unpaid));
                    kUnpaid.setForeground(p.unpaid.signum() > 0 ? Theme.WARN : Theme.TEXT);
                    if (p.rows.isEmpty()) {
                        state.showEmpty("No expenses dated " + f + " to " + t, "Press New expense to add one.");
                        cards.show(center, "state");
                        updateButtons();
                        return;
                    }
                    for (ExpenseService.Record r : p.rows) {
                        rows.add(r);
                        model.addRow(new Object[]{r.id, r.date.toString(), ExpenseService.categoryLabel(r.category),
                                r.description, Money.plain(r.amount), r.statusLabel(),
                                r.paidAt == null ? "" : r.paidAt.toString(), ExpenseService.methodLabel(r.method), r.createdBy});
                    }
                    cards.show(center, "table");
                    updateButtons();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    state.showError("Could not load the expenses", c.getMessage(), ExpensesPanel.this::load);
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    private void edit() {
        ExpenseService.Record rec = selected();
        if (rec == null) return;
        if (rec.voided()) {
            JOptionPane.showMessageDialog(this, "Expense #" + rec.id + " was voided on " + Theme.formatWhen(rec.voidedAt)
                    + " by " + rec.voidedBy + ":\n" + rec.voidReason, "Voided expense", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (ExpenseEditor.open(SwingUtilities.getWindowAncestor(this), rec)) load();
    }

    private void voidSelected() {
        ExpenseService.Record rec = selected();
        if (rec == null || rec.voided()) return;
        String reason = JOptionPane.showInputDialog(this, "Void expense #" + rec.id + " (" + rec.date + ", "
                        + ExpenseService.categoryLabel(rec.category) + ", " + rec.description + ", " + Money.peso(rec.amount)
                        + ")?\n\nIt will no longer lower net profit. It stays on record with your name and the reason.\n\nReason:",
                "Void expense", JOptionPane.WARNING_MESSAGE);
        if (reason == null) return;
        if (reason.trim().length() < 3) {
            JOptionPane.showMessageDialog(this, "Enter the reason (at least 3 characters).", "Void", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            ExpenseService.voidExpense(rec.id, reason.trim());
            load();
        } catch (Db.Failure ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Not voided", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void showHistory() {
        ExpenseService.Record rec = selected();
        if (rec == null) return;
        final long id = rec.id;
        new SwingWorker<List<ExpenseService.Change>, Void>() {
            @Override
            protected List<ExpenseService.Change> doInBackground() throws Exception {
                return ExpenseService.history(id);
            }

            @Override
            protected void done() {
                try {
                    List<ExpenseService.Change> list = get();
                    DefaultTableModel m = new DefaultTableModel(new String[]{"When", "By", "What changed"}, 0) {
                        @Override
                        public boolean isCellEditable(int r, int c) {
                            return false;
                        }
                    };
                    for (ExpenseService.Change c : list) m.addRow(new Object[]{Theme.formatWhen(c.at), c.by, c.summary});
                    JTable t = new JTable(m);
                    UiKit.styleTable(t, 32);
                    t.getColumnModel().getColumn(0).setPreferredWidth(150);
                    t.getColumnModel().getColumn(1).setPreferredWidth(110);
                    t.getColumnModel().getColumn(2).setPreferredWidth(520);
                    JScrollPane sp = UiKit.tableScroll(t);
                    sp.setPreferredSize(new Dimension(800, Math.min(420, 70 + 32 * list.size())));
                    JOptionPane.showMessageDialog(ExpensesPanel.this, sp, "History of expense #" + id,
                            JOptionPane.PLAIN_MESSAGE);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(ExpensesPanel.this, c.getMessage(), "History", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    // =====================================================================================
    // Editor
    // =====================================================================================

    static final class ExpenseEditor {
        private ExpenseEditor() {
        }

        /** New (rec null) or edit. @return true when saved. */
        static boolean open(Window owner, ExpenseService.Record rec) {
            boolean isNew = rec == null;
            JDialog d = new JDialog(owner, isNew ? "New expense" : "Edit expense #" + rec.id,
                    Dialog.ModalityType.APPLICATION_MODAL);
            final UUID requestId = Db.requestId();
            final boolean[] saved = {false};

            JTextField date = new JTextField(10);
            JComboBox<String> cat = new JComboBox<>();
            for (String[] c : ExpenseService.CATEGORIES) cat.addItem(c[1]);
            JTextField desc = new JTextField(28);
            JTextField amount = new JTextField(10);
            JRadioButton unpaid = new JRadioButton("Unpaid", true), paid = new JRadioButton("Paid");
            ButtonGroup g = new ButtonGroup();
            g.add(unpaid);
            g.add(paid);
            JTextField paidOn = new JTextField(10);
            JComboBox<String> method = new JComboBox<>();
            method.addItem("(not recorded)");
            for (String[] m : ExpenseService.METHODS) method.addItem(m[1]);
            JTextField notes = new JTextField(28);
            JLabel error = new JLabel(" ");
            for (JTextField f : new JTextField[]{date, desc, amount, paidOn, notes}) UiKit.styleField(f);
            cat.setFont(Theme.SUBTLE);
            method.setFont(Theme.SUBTLE);
            for (JRadioButton r : new JRadioButton[]{unpaid, paid}) {
                r.setOpaque(false);
                r.setFont(Theme.font(Font.BOLD, 13));
            }
            error.setFont(Theme.SMALL);
            error.setForeground(Theme.DANGER);
            UiKit.placeholder(desc, "For example: Meralco bill, October");
            UiKit.placeholder(notes, "Notes (optional)");
            LocalDate today = LocalDate.now(Theme.BUSINESS_TZ);

            if (isNew) {
                date.setText(today.toString());
                paidOn.setText(today.toString());
            } else {
                date.setText(rec.date.toString());
                for (int i = 0; i < ExpenseService.CATEGORIES.length; i++) {
                    if (ExpenseService.CATEGORIES[i][0].equals(rec.category)) cat.setSelectedIndex(i);
                }
                desc.setText(rec.description);
                amount.setText(rec.amount.toPlainString());
                paid.setSelected("PAID".equals(rec.paymentStatus));
                unpaid.setSelected(!"PAID".equals(rec.paymentStatus));
                paidOn.setText(rec.paidAt == null ? today.toString() : rec.paidAt.toString());
                for (int i = 0; i < ExpenseService.METHODS.length; i++) {
                    if (ExpenseService.METHODS[i][0].equals(rec.method)) method.setSelectedIndex(i + 1);
                }
                notes.setText(rec.notes == null ? "" : rec.notes);
            }

            Runnable refresh = () -> {
                paidOn.setEnabled(paid.isSelected());
                method.setEnabled(paid.isSelected());
                error.setText(" ");
            };
            unpaid.addActionListener(e -> refresh.run());
            paid.addActionListener(e -> refresh.run());
            refresh.run();

            JButton todayBtn = UiKit.secondaryButton("Today");
            todayBtn.addActionListener(e -> date.setText(today.toString()));

            JPanel form = new JPanel(new GridBagLayout());
            form.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, 10, 10);
            c.gridx = 0;
            c.gridy = 0;
            JPanel dateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            dateRow.setOpaque(false);
            dateRow.add(date);
            dateRow.add(todayBtn);
            form.add(UiKit.group("Date of the expense", dateRow), c);
            c.gridx = 1;
            form.add(UiKit.group("Category", cat), c);
            c.gridx = 2;
            form.add(UiKit.group("Amount (₱)", amount), c);
            c.gridy = 1;
            c.gridx = 0;
            c.gridwidth = 3;
            form.add(UiKit.group("What it was for", desc), c);
            c.gridy = 2;
            c.gridwidth = 1;
            JPanel st = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            st.setOpaque(false);
            st.add(unpaid);
            st.add(Box.createHorizontalStrut(12));
            st.add(paid);
            form.add(UiKit.group("Status", st), c);
            c.gridx = 1;
            form.add(UiKit.group("Paid on", paidOn), c);
            c.gridx = 2;
            form.add(UiKit.group("Paid by", method), c);
            c.gridy = 3;
            c.gridx = 0;
            c.gridwidth = 3;
            form.add(UiKit.group("Notes", notes), c);
            c.gridy = 4;
            form.add(error, c);

            JButton save = UiKit.primaryButton(isNew ? "Save expense" : "Save changes");
            JButton cancel = UiKit.secondaryButton("Cancel");
            cancel.addActionListener(e -> d.dispose());
            save.addActionListener(e -> {
                final LocalDate dt, po;
                final BigDecimal amt;
                try {
                    dt = LocalDate.parse(date.getText().trim());
                    po = paid.isSelected() ? LocalDate.parse(paidOn.getText().trim()) : null;
                } catch (DateTimeParseException ex) {
                    error.setText("Write dates as YYYY-MM-DD, for example 2026-10-15.");
                    return;
                }
                try {
                    amt = Money.parse(amount.getText(), "amount", false);
                } catch (IllegalArgumentException ex) {
                    error.setText(ex.getMessage());
                    return;
                }
                if (amt.signum() <= 0) {
                    error.setText("The amount must be more than 0.");
                    return;
                }
                final String ds = desc.getText().trim();
                if (ds.isEmpty()) {
                    error.setText("Enter what the expense was for.");
                    return;
                }
                final String cc = ExpenseService.CATEGORIES[Math.max(0, cat.getSelectedIndex())][0];
                final String m = method.getSelectedIndex() <= 0 ? null : ExpenseService.METHODS[method.getSelectedIndex() - 1][0];
                final boolean isPaid = paid.isSelected();
                final String nt = notes.getText().trim();
                save.setEnabled(false);
                cancel.setEnabled(false);
                save.setText("Saving...");
                new SwingWorker<Long, Void>() {
                    @Override
                    protected Long doInBackground() throws Exception {
                        return ExpenseService.save(isNew ? null : rec.id, requestId, dt, cc, ds, amt, isPaid, po, m,
                                nt.isEmpty() ? null : nt);
                    }

                    @Override
                    protected void done() {
                        try {
                            get();
                            saved[0] = true;
                            d.dispose();
                        } catch (Exception ex) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            error.setText("<html>" + StockDialogs.esc(cause.getMessage()) + "</html>");
                            save.setEnabled(true);
                            cancel.setEnabled(true);
                            save.setText(isNew ? "Save expense" : "Save changes");
                        }
                    }
                }.execute();
            });

            JPanel root = new JPanel(new BorderLayout(0, 12));
            root.setBackground(Theme.CARD);
            root.setBorder(new EmptyBorder(16, 18, 14, 18));
            root.add(UiKit.cardHeading(d.getTitle(), "Expenses lower net profit in Sales & Profit by their date"),
                    BorderLayout.NORTH);
            root.add(form, BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            buttons.add(cancel);
            buttons.add(save);
            root.add(buttons, BorderLayout.SOUTH);
            d.setContentPane(root);
            d.getRootPane().setDefaultButton(save);
            d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            d.pack();
            d.setLocationRelativeTo(owner);
            d.setVisible(true);
            return saved[0];
        }
    }
}
