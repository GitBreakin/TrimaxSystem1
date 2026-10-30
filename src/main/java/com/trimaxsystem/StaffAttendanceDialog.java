package com.trimaxsystem;

import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Attendance → Employees without an app... (sql/20261030_01): the Admin marks Present / Half day / Absent for each
 * employee who has no app account (helpers, cashiers...), one day at a time. Payroll counts these days (Present 1,
 * Half day 0.5) like it counts the agents' and drivers' duty from the app.
 */
final class StaffAttendanceDialog extends JDialog {

    private static final String[] CHOICES = {"—", "Present", "Half day", "Absent"};
    private static final String[] CODES = {null, PayrollService.PRESENT, PayrollService.HALF_DAY, PayrollService.ABSENT};

    private final JTextField day = new JTextField(10);
    private final JLabel info = new JLabel(" ");
    /** After saving: what the payroll became (20261030_02). */
    private final JLabel payInfo = new JLabel(" ");
    /** The server makes payroll from attendance by itself (20261030_02). */
    private final boolean autoReady = Db.payrollAutoReady();
    /** The employees just saved; the reload after saving reports their payroll. */
    private java.util.Set<Long> justSaved;
    private final JLabel error = new JLabel(" ");
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JButton save = UiKit.primaryButton("Save attendance");
    private final JButton allPresent = UiKit.secondaryButton("Mark everyone present");
    private final List<PayrollService.StaffDay> rows = new ArrayList<>();
    private final List<String> loadedStatus = new ArrayList<>();
    private final List<String> loadedNote = new ArrayList<>();
    private LocalDate shown;
    private int seq;

    static void open(Window owner) {
        new StaffAttendanceDialog(owner).setVisible(true);
    }

    private StaffAttendanceDialog(Window owner) {
        super(owner, "Attendance - employees without an app account", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.CARD);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        root.add(UiKit.cardHeading("Attendance - employees without an app account",
                "Helpers, cashiers, guards... Agents and drivers record theirs with Start / End duty in the app. "
                        + "Present = 1 day, Half day = 0.5. With a daily rate, each day goes into payroll by itself."), BorderLayout.NORTH);

        // ---- the day
        UiKit.styleField(day);
        day.setToolTipText("YYYY-MM-DD (not in the future)");
        JButton prev = UiKit.secondaryButton("◀");
        JButton next = UiKit.secondaryButton("▶");
        JButton today = UiKit.secondaryButton("Today");
        JButton show = UiKit.secondaryButton("Show");
        prev.setToolTipText("The day before");
        next.setToolTipText("The day after");
        prev.addActionListener(e -> moveDay(-1));
        next.addActionListener(e -> moveDay(1));
        today.addActionListener(e -> {
            day.setText(LocalDate.now(Theme.BUSINESS_TZ).toString());
            load();
        });
        show.addActionListener(e -> load());
        day.addActionListener(e -> load());
        JPanel dayRow = UiKit.flowRow();
        dayRow.add(UiKit.caption("Day"));
        dayRow.add(prev);
        dayRow.add(day);
        dayRow.add(next);
        dayRow.add(today);
        dayRow.add(show);

        // ---- the employees
        UiKit.styleTable(table, 32);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JComboBox<String> choice = new JComboBox<>(CHOICES);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(choice));
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                String s = String.valueOf(v);
                setFont(Theme.font(Font.BOLD, 13));
                setForeground("Present".equals(s) ? Theme.OK : "Half day".equals(s) ? Theme.WARN
                        : "Absent".equals(s) ? Theme.DANGER : Theme.MUTED);
                setText("—".equals(s) ? "— (click to mark)" : s);
                return this;
            }
        });
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(4).setCellRenderer(right);
        DefaultTableCellRenderer pay = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                setHorizontalAlignment(SwingConstants.RIGHT);
                setForeground(String.valueOf(v).startsWith("No daily rate") ? Theme.WARN : Theme.TEXT);
                return this;
            }
        };
        table.getColumnModel().getColumn(5).setCellRenderer(pay);
        int[] w = {190, 110, 150, 170, 100, 170};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        JScrollPane sp = UiKit.tableScroll(table);
        sp.setPreferredSize(new Dimension(900, 300));

        info.setFont(Theme.SMALL);
        info.setForeground(Theme.MUTED);
        error.setFont(Theme.SMALL);
        error.setForeground(Theme.DANGER);
        payInfo.setFont(Theme.font(Font.BOLD, 12));
        payInfo.setForeground(Theme.TEXT);
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setOpaque(false);
        center.add(dayRow, BorderLayout.NORTH);
        center.add(sp, BorderLayout.CENTER);
        JPanel notes = new JPanel(new GridLayout(3, 1, 0, 2));
        notes.setOpaque(false);
        notes.add(info);
        notes.add(payInfo);
        notes.add(error);
        center.add(notes, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        JButton close = UiKit.secondaryButton("Close");
        close.addActionListener(e -> {
            if (changed() && JOptionPane.showConfirmDialog(this, "Close without saving this day's attendance?",
                    "Attendance", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            dispose();
        });
        allPresent.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            for (PayrollService.StaffDay r : rows) if (r.status == null && r.active) r.status = PayrollService.PRESENT;
            model.fireTableDataChanged();
            updateInfo();
        });
        save.addActionListener(e -> saveDay());
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(allPresent);
        JPanel right2 = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right2.setOpaque(false);
        right2.add(close);
        right2.add(save);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right2, BorderLayout.EAST);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().registerKeyboardAction(e -> close.doClick(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        day.setText(LocalDate.now(Theme.BUSINESS_TZ).toString());
        pack();
        setLocationRelativeTo(owner);
        load();
    }

    private void moveDay(int by) {
        try {
            LocalDate d = LocalDate.parse(day.getText().trim()).plusDays(by);
            if (d.isAfter(LocalDate.now(Theme.BUSINESS_TZ))) return;
            day.setText(d.toString());
            load();
        } catch (DateTimeParseException ex) {
            error.setText("Write the day as YYYY-MM-DD, for example 2026-10-30.");
        }
    }

    private void load() {
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        final LocalDate d;
        try {
            d = LocalDate.parse(day.getText().trim());
        } catch (DateTimeParseException ex) {
            error.setText("Write the day as YYYY-MM-DD, for example 2026-10-30.");
            return;
        }
        if (d.isAfter(LocalDate.now(Theme.BUSINESS_TZ))) {
            error.setText("Attendance can be marked up to today, not for a future day.");
            return;
        }
        if (changed() && shown != null && !shown.equals(d)
                && JOptionPane.showConfirmDialog(this, "Leave " + shown + " without saving its attendance?",
                "Attendance", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            day.setText(shown.toString());
            return;
        }
        final int my = ++seq;
        error.setText(" ");
        info.setText("Loading...");
        save.setEnabled(false);
        new SwingWorker<List<PayrollService.StaffDay>, Void>() {
            @Override
            protected List<PayrollService.StaffDay> doInBackground() throws Exception {
                return PayrollService.staffDay(d);
            }

            @Override
            protected void done() {
                if (my != seq) return;
                try {
                    List<PayrollService.StaffDay> list = get();
                    rows.clear();
                    rows.addAll(list);
                    loadedStatus.clear();
                    loadedNote.clear();
                    for (PayrollService.StaffDay r : list) {
                        loadedStatus.add(r.status);
                        loadedNote.add(r.note);
                    }
                    shown = d;
                    model.fireTableDataChanged();
                    updateInfo();
                    payInfo.setText(justSaved == null ? " " : payrollReport(d, justSaved));
                    justSaved = null;
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    info.setText(" ");
                    error.setText("<html>" + StockDialogs.esc(c.getMessage()) + "</html>");
                }
            }
        }.execute();
    }

    private boolean changed() {
        for (int i = 0; i < rows.size() && i < loadedStatus.size(); i++) {
            PayrollService.StaffDay r = rows.get(i);
            if (!java.util.Objects.equals(r.status, loadedStatus.get(i))) return true;
            if (!java.util.Objects.equals(blankToNull(r.note), blankToNull(loadedNote.get(i)))) return true;
        }
        return false;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void updateInfo() {
        if (rows.isEmpty()) {
            info.setText("No employees without an app account yet. Add them in Payroll → Employees...");
            save.setEnabled(false);
            allPresent.setEnabled(false);
            return;
        }
        int p = 0, h = 0, a = 0, none = 0;
        for (PayrollService.StaffDay r : rows) {
            if (PayrollService.PRESENT.equals(r.status)) p++;
            else if (PayrollService.HALF_DAY.equals(r.status)) h++;
            else if (PayrollService.ABSENT.equals(r.status)) a++;
            else none++;
        }
        info.setText(shown + ":  " + p + " present, " + h + " half day, " + a + " absent"
                + (none > 0 ? ", " + none + " not marked" : "") + (changed() ? "   (not saved yet)" : ""));
        save.setEnabled(changed());
        allPresent.setEnabled(none > 0);
    }

    private void saveDay() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
        if (shown == null || !changed()) return;
        final LocalDate d = shown;
        final List<PayrollService.StaffDay> send = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PayrollService.StaffDay r = rows.get(i);
            // only what changed (an unchanged blank row needs nothing)
            if (!java.util.Objects.equals(r.status, loadedStatus.get(i))
                    || !java.util.Objects.equals(blankToNull(r.note), blankToNull(loadedNote.get(i)))) send.add(r);
        }
        for (PayrollService.StaffDay r : send) {
            if (r.status == null && blankToNull(r.note) != null) {
                error.setText(r.name + ": choose Present, Half day or Absent for the note, or clear the note.");
                return;
            }
        }
        save.setEnabled(false);
        save.setText("Saving...");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return PayrollService.saveStaffDay(d, send);
            }

            @Override
            protected void done() {
                save.setText("Save attendance");
                try {
                    get();
                    shown = null;   // reload without asking
                    justSaved = new java.util.HashSet<>();
                    for (PayrollService.StaffDay r : send) justSaved.add(r.staffId);
                    day.setText(d.toString());
                    load();
                    error.setText(" ");
                    info.setText("Saved.");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    error.setText("<html>" + StockDialogs.esc(c.getMessage()) + "</html>");
                    save.setEnabled(true);
                }
            }
        }.execute();
    }

    /** "Saved. Payroll 2026-10-16 to 2026-10-31: jairus ₱900.00 (1.5 days). No daily rate yet: arlyn ..." */
    private String payrollReport(LocalDate d, java.util.Set<Long> ids) {
        if (!autoReady) return "Saved.";
        LocalDate[] p = PayrollService.payPeriod(d);
        List<String> pay = new ArrayList<>(), noRate = new ArrayList<>(), paid = new ArrayList<>();
        for (PayrollService.StaffDay r : rows) {
            if (!ids.contains(r.staffId)) continue;
            if (r.dailyRate == null) noRate.add(r.name);
            else if ("PAID".equals(r.periodStatus)) paid.add(r.name);
            else pay.add(r.name + " " + r.payrollText());
        }
        StringBuilder sb = new StringBuilder("<html>Saved. Payroll " + p[0] + " to " + p[1] + ": ");
        sb.append(pay.isEmpty() ? "nothing to count" : StockDialogs.esc(String.join("; ", pay))).append('.');
        if (!noRate.isEmpty()) {
            sb.append(" <font color='#B45309'>No daily rate yet: ").append(StockDialogs.esc(String.join(", ", noRate)))
              .append(" (set it in Payroll → Employees..., then their days go to payroll).</font>");
        }
        if (!paid.isEmpty()) {
            sb.append(" <font color='#B45309'>Already paid for this period, not changed: ")
              .append(StockDialogs.esc(String.join(", ", paid))).append(".</font>");
        }
        return sb.append("</html>").toString();
    }

    // ------------------------------------------------------------------------------------ table

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"Name", "Position", "Attendance", "Note", "Days this month", "Payroll this period"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 2 || c == 3;
        }

        @Override
        public Object getValueAt(int r, int c) {
            PayrollService.StaffDay s = rows.get(r);
            switch (c) {
                case 0: return s.name + (s.active ? "" : "  (no longer working here)");
                case 1: return s.position == null ? "" : s.position;
                case 2: {
                    for (int i = 1; i < CODES.length; i++) if (CODES[i].equals(s.status)) return CHOICES[i];
                    return CHOICES[0];
                }
                case 3: return s.note == null ? "" : s.note;
                case 4: return Money.qty(s.monthDays);
                default: return autoReady ? s.payrollText() : "";
            }
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            PayrollService.StaffDay s = rows.get(r);
            if (c == 2) {
                s.status = null;
                for (int i = 1; i < CHOICES.length; i++) if (CHOICES[i].equals(v)) s.status = CODES[i];
            } else if (c == 3) {
                String t = v == null ? "" : String.valueOf(v).trim();
                s.note = t.isEmpty() ? null : (t.length() > 200 ? t.substring(0, 200) : t);
            }
            fireTableRowsUpdated(r, r);
            updateInfo();
        }
    }
}
