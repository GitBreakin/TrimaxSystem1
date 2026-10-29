package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Payroll → Employees...: the employees who have no app account (helpers, cashiers, guards...), so they can be chosen
 * in a payroll record like agents and drivers (sql/20261029_01). Their pay lowers net profit like any payroll.
 * Nobody is deleted: someone who leaves is marked as no longer working here (their payroll history stays).
 */
final class PayrollStaffDialog {

    private static final String[] POSITIONS = {"Helper", "Cashier", "Warehouse staff", "Security guard", "Office staff", "Mechanic"};

    private PayrollStaffDialog() {
    }

    static void open(Window owner) {
        JDialog d = new JDialog(owner, "Employees without an app account", Dialog.ModalityType.APPLICATION_MODAL);
        DefaultTableModel model = new DefaultTableModel(new String[]{"Name", "Position", "Contact number", "Status"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable table = new JTable(model);
        UiKit.styleTable(table, 34);
        table.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        table.getColumnModel().getColumn(0).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, true, false));
        table.getColumnModel().getColumn(3).setCellRenderer(new UiKit.PillRenderer(
                s -> "Working here".equals(s) ? Theme.OK : Theme.MUTED));
        int[] w = {220, 150, 130, 160};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        List<PayrollService.Staff> rows = new ArrayList<>();
        JLabel note = new JLabel(" ");
        note.setFont(Theme.SMALL);
        note.setForeground(Theme.MUTED);

        JButton add = UiKit.primaryButton("Add employee...");
        JButton edit = UiKit.secondaryButton("Edit...");
        JButton toggle = UiKit.secondaryButton("No longer working here");
        JButton close = UiKit.secondaryButton("Close");

        Runnable[] reload = new Runnable[1];
        Runnable buttons = () -> {
            int r = table.getSelectedRow();
            PayrollService.Staff s = r < 0 ? null : rows.get(table.convertRowIndexToModel(r));
            edit.setEnabled(s != null);
            toggle.setEnabled(s != null);
            toggle.setText(s != null && !s.active ? "Working here again" : "No longer working here");
        };
        reload[0] = () -> new SwingWorker<List<PayrollService.Staff>, Void>() {
            @Override
            protected List<PayrollService.Staff> doInBackground() throws Exception {
                return PayrollService.staff();
            }

            @Override
            protected void done() {
                try {
                    List<PayrollService.Staff> list = get();
                    rows.clear();
                    rows.addAll(list);
                    model.setRowCount(0);
                    long active = 0;
                    for (PayrollService.Staff s : list) {
                        if (s.active) active++;
                        model.addRow(new Object[]{s.name, s.position == null ? "" : s.position,
                                s.contact == null ? "" : s.contact, s.active ? "Working here" : "No longer working here"});
                    }
                    note.setText(list.isEmpty()
                            ? "No employees yet. Use Add employee... for anyone paid who has no app account."
                            : active + " working here" + (list.size() > active ? ", " + (list.size() - active) + " no longer" : "")
                              + ". Choose them in New payroll record; their pay lowers net profit in Sales & Profit.");
                    note.setForeground(Theme.MUTED);
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
            if (r >= 0 && form(d, rows.get(table.convertRowIndexToModel(r)))) reload[0].run();
        });
        toggle.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r < 0) return;
            PayrollService.Staff s = rows.get(table.convertRowIndexToModel(r));
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
        root.add(UiKit.cardHeading(d.getTitle(), "Helpers, cashiers, guards... anyone paid who does not use the app"),
                BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setOpaque(false);
        JScrollPane scroll = UiKit.tableScroll(table);
        scroll.setPreferredSize(new Dimension(680, 300));
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

    /** Add (s null) or edit one employee. @return true when saved. */
    static boolean form(Window owner, PayrollService.Staff s) {
        boolean isNew = s == null;
        JDialog d = new JDialog(owner, isNew ? "Add employee" : "Edit employee", Dialog.ModalityType.APPLICATION_MODAL);
        JTextField name = new JTextField(24);
        JComboBox<String> position = new JComboBox<>(POSITIONS);
        position.setEditable(true);
        position.setSelectedItem(isNew ? "" : s.position == null ? "" : s.position);
        JTextField contact = new JTextField(14);
        JLabel error = new JLabel(" ");
        UiKit.styleField(name);
        UiKit.styleField(contact);
        UiKit.placeholder(name, "Full name");
        UiKit.placeholder(contact, "09XXXXXXXXX (optional)");
        position.setFont(Theme.SUBTLE);
        error.setFont(Theme.SMALL);
        error.setForeground(Theme.DANGER);
        if (!isNew) {
            name.setText(s.name);
            contact.setText(s.contact == null ? "" : s.contact);
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
        c.gridx = 0;
        c.gridy = 2;
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
            save.setEnabled(false);
            cancel.setEnabled(false);
            save.setText("Saving...");
            new SwingWorker<Long, Void>() {
                @Override
                protected Long doInBackground() throws Exception {
                    return PayrollService.saveStaff(isNew ? null : s.id, n, pos.isEmpty() ? null : pos,
                            ct.isEmpty() ? null : ct, isNew || s.active);
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
}
