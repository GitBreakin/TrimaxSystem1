package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Payroll → Employees...: the employees who have no app account (helpers, cashiers, guards...), so they can be chosen
 * in a payroll record like agents and drivers (sql/20261029_01). Their pay lowers net profit like any payroll.
 * Nobody is deleted: someone who leaves is marked as no longer working here (their payroll history stays).
 *
 * <p>With sql/20261030_02 every employee, agents and drivers too, has a daily rate (the pay for one full day). Owner,
 * 2026-10-30: "kapag mag add ng new employee dapat i seset agad yung basic pay nya" — with a rate, each day of
 * attendance goes into payroll by itself.
 */
final class PayrollStaffDialog {

    private static final String[] POSITIONS = {"Helper", "Cashier", "Warehouse staff", "Security guard", "Office staff", "Mechanic"};

    private PayrollStaffDialog() {
    }

    /** One line: an employee without an account, or an app account (agent, driver...). */
    private static final class Row {
        PayrollService.Staff staff;
        PayrollService.Employee account;

        String name() {
            return staff != null ? staff.name : account.name;
        }

        BigDecimal rate() {
            return staff != null ? staff.dailyRate : account.dailyRate;
        }
    }

    static void open(Window owner) {
        final boolean rates = Db.payrollAutoReady();
        JDialog d = new JDialog(owner, rates ? "Employees and daily rates" : "Employees without an app account",
                Dialog.ModalityType.APPLICATION_MODAL);
        String[] cols = rates ? new String[]{"Name", "Position / role", "Daily rate", "Contact number", "Status"}
                              : new String[]{"Name", "Position", "Contact number", "Status"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable table = new JTable(model);
        UiKit.styleTable(table, 34);
        table.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        table.getColumnModel().getColumn(0).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, true, false));
        table.getColumnModel().getColumn(cols.length - 1).setCellRenderer(new UiKit.PillRenderer(
                s -> "Working here".equals(s) || "Active".equals(s) ? Theme.OK : Theme.MUTED));
        if (rates) {
            table.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                    super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                    setHorizontalAlignment(SwingConstants.RIGHT);
                    setFont(Theme.font(Font.BOLD, 13));
                    setForeground(String.valueOf(v).startsWith("Not set") ? Theme.WARN : Theme.TEXT);
                    return this;
                }
            });
        }
        int[] w = rates ? new int[]{200, 140, 130, 130, 150} : new int[]{220, 150, 130, 160};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        List<Row> rows = new ArrayList<>();
        JLabel note = new JLabel(" ");
        note.setFont(Theme.SMALL);
        note.setForeground(Theme.MUTED);

        JButton add = UiKit.primaryButton("Add employee...");
        JButton edit = UiKit.secondaryButton(rates ? "Edit / daily rate..." : "Edit...");
        JButton toggle = UiKit.secondaryButton("No longer working here");
        JButton close = UiKit.secondaryButton("Close");

        Runnable[] reload = new Runnable[1];
        Runnable buttons = () -> {
            int r = table.getSelectedRow();
            Row row = r < 0 ? null : rows.get(table.convertRowIndexToModel(r));
            edit.setEnabled(row != null);
            toggle.setEnabled(row != null && row.staff != null);
            toggle.setText(row != null && row.staff != null && !row.staff.active ? "Working here again" : "No longer working here");
        };
        reload[0] = () -> new SwingWorker<List<Row>, Void>() {
            @Override
            protected List<Row> doInBackground() throws Exception {
                List<Row> out = new ArrayList<>();
                for (PayrollService.Staff s : PayrollService.staff()) {
                    Row r = new Row();
                    r.staff = s;
                    out.add(r);
                }
                if (rates) {
                    // agents and drivers (every app account that is not an Admin)
                    for (PayrollService.Employee e : PayrollService.employees()) {
                        if (e.isStaff() || "Admin".equalsIgnoreCase(e.role)) continue;
                        Row r = new Row();
                        r.account = e;
                        out.add(r);
                    }
                }
                return out;
            }

            @Override
            protected void done() {
                try {
                    List<Row> list = get();
                    rows.clear();
                    rows.addAll(list);
                    model.setRowCount(0);
                    long active = 0, noRate = 0;
                    for (Row r : list) {
                        PayrollService.Staff s = r.staff;
                        String rate = r.rate() == null ? "Not set" : Money.peso(r.rate()) + " / day";
                        if (s != null) {
                            if (s.active) active++;
                            if (s.active && r.rate() == null) noRate++;
                            String pos = s.position == null ? "" : s.position;
                            String contact = s.contact == null ? "" : s.contact;
                            String st = s.active ? "Working here" : "No longer working here";
                            model.addRow(rates ? new Object[]{s.name, pos, rate, contact, st} : new Object[]{s.name, pos, contact, st});
                        } else {
                            PayrollService.Employee e = r.account;
                            if (r.rate() == null && "Active".equals(e.status)) noRate++;
                            model.addRow(new Object[]{e.name, e.role + " (app)", rate, "", e.status});
                        }
                    }
                    if (list.isEmpty()) {
                        note.setText("No employees yet. Use Add employee... for anyone paid who has no app account.");
                    } else if (rates) {
                        note.setText(noRate == 0
                                ? "Each day of attendance goes into payroll by itself: days x daily rate, pay periods 1st–15th and 16th–end."
                                : noRate + (noRate == 1 ? " employee has" : " employees have")
                                  + " no daily rate yet: their attendance does not go into payroll until it is set.");
                    } else {
                        note.setText(active + " working here" + (list.size() > active ? ", " + (list.size() - active) + " no longer" : "")
                                + ". Choose them in New payroll record; their pay lowers net profit in Sales & Profit.");
                    }
                    note.setForeground(rates && noRate > 0 ? Theme.WARN : Theme.MUTED);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    note.setText("<html>" + StockDialogs.esc(c.getMessage()) + "</html>");
                    note.setForeground(Theme.DANGER);
                }
                buttons.run();
            }
        }.execute();

        table.getSelectionModel().addListSelectionListener(e -> buttons.run());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) edit.doClick();
            }
        });
        add.addActionListener(e -> {
            if (form(d, null)) reload[0].run();
        });
        edit.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r < 0) return;
            Row row = rows.get(table.convertRowIndexToModel(r));
            boolean saved = row.staff != null ? form(d, row.staff) : rateForm(d, row.account);
            if (saved) reload[0].run();
        });
        toggle.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r < 0) return;
            PayrollService.Staff s = rows.get(table.convertRowIndexToModel(r)).staff;
            if (s == null) return;
            String q = s.active
                    ? "Mark " + s.name + " as no longer working here?\nTheir payroll records stay; they are just not offered for new ones."
                    : "Mark " + s.name + " as working here again?";
            if (JOptionPane.showConfirmDialog(d, q, "Employees", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                PayrollService.saveStaff(s.id, s.name, s.position, s.contact, !s.active);
                reload[0].run();
            } catch (Db.Failure ex) {
                JOptionPane.showMessageDialog(d, ex.getMessage(), "Not saved", JOptionPane.WARNING_MESSAGE);
            }
        });
        close.addActionListener(e -> d.dispose());

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.CARD);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        root.add(UiKit.cardHeading(d.getTitle(), rates
                        ? "Set each one's daily rate (pay for one full day). Their attendance then goes into payroll by itself."
                        : "Helpers, cashiers, guards... anyone paid who does not use the app"),
                BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setOpaque(false);
        JScrollPane scroll = UiKit.tableScroll(table);
        scroll.setPreferredSize(new Dimension(rates ? 780 : 680, 300));
        center.add(scroll, BorderLayout.CENTER);
        center.add(note, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(add);
        left.add(edit);
        left.add(toggle);
        south.add(left, BorderLayout.WEST);
        south.add(close, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.pack();
        d.setLocationRelativeTo(owner);
        buttons.run();
        reload[0].run();
        d.setVisible(true);
    }

    /** The daily rate as typed: required, more than 0, at most 2 decimals. */
    static BigDecimal parseRate(String text) {
        BigDecimal r = Money.parse(text, "daily rate", false);
        if (r.signum() <= 0) throw new IllegalArgumentException("Enter the daily rate: the pay for one full day, for example 600.");
        return r;
    }

    /** Add (s null) or edit one employee without an account. @return true when saved. */
    static boolean form(Window owner, PayrollService.Staff s) {
        boolean isNew = s == null;
        final boolean rates = Db.payrollAutoReady();
        JDialog d = new JDialog(owner, isNew ? "Add employee" : "Edit employee", Dialog.ModalityType.APPLICATION_MODAL);
        JTextField name = new JTextField(24);
        JComboBox<String> position = new JComboBox<>(POSITIONS);
        position.setEditable(true);
        position.setSelectedItem(isNew ? "" : s.position == null ? "" : s.position);
        JTextField contact = new JTextField(14);
        JTextField rate = new JTextField(10);
        JLabel error = new JLabel(" ");
        UiKit.styleField(name);
        UiKit.styleField(contact);
        UiKit.styleField(rate);
        UiKit.placeholder(name, "Full name");
        UiKit.placeholder(contact, "09XXXXXXXXX (optional)");
        UiKit.placeholder(rate, "e.g. 600");
        rate.setToolTipText("Pay for one full day. Present = 1 day, Half day = half, Absent = 0.");
        position.setFont(Theme.SUBTLE);
        error.setFont(Theme.SMALL);
        error.setForeground(Theme.DANGER);
        if (!isNew) {
            name.setText(s.name);
            contact.setText(s.contact == null ? "" : s.contact);
            if (s.dailyRate != null) rate.setText(s.dailyRate.toPlainString());
        }
        final boolean[] saved = {false};

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 10, 10);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        form.add(UiKit.group("Full name", name), c);
        c.gridy = 1;
        c.gridwidth = 1;
        form.add(UiKit.group("Position (type or choose)", position), c);
        c.gridx = 1;
        form.add(UiKit.group("Contact number", contact), c);
        if (rates) {
            c.gridx = 0;
            c.gridy = 2;
            form.add(UiKit.group("Daily rate (₱)", rate), c);
            JLabel hint = new JLabel("<html>Pay for one full day. Each Present / Half day in Attendance<br>goes into payroll by itself.</html>");
            hint.setFont(Theme.SMALL);
            hint.setForeground(Theme.MUTED);
            c.gridx = 1;
            form.add(hint, c);
        }
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        form.add(error, c);

        JButton save = UiKit.primaryButton(isNew ? "Add employee" : "Save changes");
        JButton cancel = UiKit.secondaryButton("Cancel");
        cancel.addActionListener(e -> d.dispose());
        save.addActionListener(e -> {
            String n = name.getText().trim().replaceAll("\\s+", " ");
            Object p = position.getEditor().getItem();
            String pos = p == null ? "" : p.toString().trim();
            String ct = contact.getText().trim();
            String problem = PayrollService.staffProblem(n, pos, ct);
            if (problem != null) {
                error.setText(problem);
                return;
            }
            final BigDecimal r;
            try {
                r = rates ? parseRate(rate.getText()) : null;
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                rate.requestFocusInWindow();
                return;
            }
            save.setEnabled(false);
            cancel.setEnabled(false);
            save.setText("Saving...");
            new SwingWorker<Long, Void>() {
                @Override
                protected Long doInBackground() throws Exception {
                    return PayrollService.saveStaff(isNew ? null : s.id, n, pos.isEmpty() ? null : pos,
                            ct.isEmpty() ? null : ct, isNew || s.active, r, rates);
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
                        save.setText(isNew ? "Add employee" : "Save changes");
                    }
                }
            }.execute();
        });

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.CARD);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        root.add(UiKit.cardHeading(d.getTitle(), "Someone paid who does not use the app"), BorderLayout.NORTH);
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

    /** The daily rate of an app account (agent, driver). Empty = no rate (payroll is not made from their duty). */
    static boolean rateForm(Window owner, PayrollService.Employee e) {
        JDialog d = new JDialog(owner, "Daily rate", Dialog.ModalityType.APPLICATION_MODAL);
        JTextField rate = new JTextField(10);
        UiKit.styleField(rate);
        UiKit.placeholder(rate, "e.g. 700");
        if (e.dailyRate != null) rate.setText(e.dailyRate.toPlainString());
        JLabel error = new JLabel(" ");
        error.setFont(Theme.SMALL);
        error.setForeground(Theme.DANGER);
        JLabel hint = new JLabel("<html>Pay for one full day. Their Start / End duty in the app goes into payroll by itself:<br>"
                + "8 hours of duty = 1 day. Leave it empty to stop making their payroll from duty.</html>");
        hint.setFont(Theme.SMALL);
        hint.setForeground(Theme.MUTED);
        final boolean[] saved = {false};
        JButton save = UiKit.primaryButton("Save");
        JButton cancel = UiKit.secondaryButton("Cancel");
        cancel.addActionListener(ev -> d.dispose());
        save.addActionListener(ev -> {
            final BigDecimal r;
            try {
                r = rate.getText().trim().isEmpty() ? null : parseRate(rate.getText());
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                return;
            }
            if (r == null && e.dailyRate != null && JOptionPane.showConfirmDialog(d,
                    "Remove " + e.name + "'s daily rate?\nTheir duty will no longer make their payroll; records already made stay.",
                    "Daily rate", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            save.setEnabled(false);
            cancel.setEnabled(false);
            new SwingWorker<Integer, Void>() {
                @Override
                protected Integer doInBackground() throws Exception {
                    return PayrollService.setRate(e.username, null, r);
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
                    }
                }
            }.execute();
        });
        JPanel form = new JPanel(new BorderLayout(0, 8));
        form.setOpaque(false);
        form.add(UiKit.group("Daily rate (₱)", rate), BorderLayout.NORTH);
        form.add(hint, BorderLayout.CENTER);
        form.add(error, BorderLayout.SOUTH);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.CARD);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        root.add(UiKit.cardHeading(e.name, e.role + " (app account)"), BorderLayout.NORTH);
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
