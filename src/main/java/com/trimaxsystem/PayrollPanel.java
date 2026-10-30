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
 * Payroll (Admin only; agents and drivers never see it). One record per employee and pay period:
 * basic pay + allowances - deductions = net pay, Unpaid or Paid (with the date). Wrong records are
 * voided with a reason, never deleted; every create, edit and void is in the audit log.
 * Payroll counts in Sales & Profit by the END of its pay period (paid and unpaid shown apart).
 */
public class PayrollPanel extends JPanel {

    private static final String[] STATUS = {"Unpaid and paid", "Unpaid", "Paid", "Voided", "All, with voided"};
    private static final String[] STATUS_CODE = {null, "UNPAID", "PAID", "VOID", "ALL"};

    private final JComboBox<Object> employee = new JComboBox<>();
    private final JComboBox<String> status = new JComboBox<>(STATUS);
    private final JTextField from = new JTextField(9), to = new JTextField(9);
    private final JLabel kCount = kpi(), kTotal = kpi(), kPaid = kpi(), kUnpaid = kpi();
    private final DefaultTableModel model = new DefaultTableModel(new String[]{
            "#", "Employee", "Pay period", "Basic", "Allowances", "Deductions", "Net pay", "Status", "Paid on", "Days x rate"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final List<PayrollService.Record> rows = new ArrayList<>();
    private final List<PayrollService.Employee> employees = new ArrayList<>();
    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JButton btnNew = UiKit.primaryButton("New payroll record");
    private final JButton btnEdit = UiKit.secondaryButton("Edit...");
    private final JButton btnVoid = UiKit.dangerButton("Void...");
    /** Employees without an app account (helpers, cashiers...): sql/20261029_01. */
    private final JButton btnStaff = UiKit.secondaryButton("Employees...");
    private int seq;
    /** What counting the payroll from attendance did (20261030_02). */
    private final JLabel autoNote = new JLabel(" ");
    private long lastCount;

    public PayrollPanel() {
        super(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 20, 16, 20));

        YearMonth ym = YearMonth.now(Theme.BUSINESS_TZ);
        from.setText(ym.atDay(1).toString());
        // up to the end of this month, or of this pay week when it ends in the next month (weekly pay, 20261030_02)
        LocalDate weekEnd = PayrollService.payPeriod(LocalDate.now(Theme.BUSINESS_TZ))[1];
        to.setText((weekEnd.isAfter(ym.atEndOfMonth()) ? weekEnd : ym.atEndOfMonth()).toString());
        for (JTextField f : new JTextField[]{from, to}) {
            UiKit.styleField(f);
            f.setToolTipText("YYYY-MM-DD: pay periods that END in this range");
            f.addActionListener(e -> load());
        }
        employee.setFont(Theme.SUBTLE);
        status.setFont(Theme.SUBTLE);
        employee.addItem("All employees");
        JButton show = UiKit.primaryButton("Show");
        show.addActionListener(e -> load());
        status.addActionListener(e -> load());
        employee.addActionListener(e -> load());

        JPanel filters = UiKit.card(new BorderLayout(), 10, 14);
        JPanel row = UiKit.flowRow();
        row.add(UiKit.group("Employee", employee));
        row.add(UiKit.group("Status", status));
        row.add(UiKit.group("Period ends from", from));
        row.add(UiKit.group("to", to));
        JPanel b = new JPanel(new BorderLayout());
        b.setOpaque(false);
        b.add(show, BorderLayout.SOUTH);
        row.add(b);
        filters.add(row, BorderLayout.CENTER);

        JPanel kpis = new JPanel(new GridLayout(1, 4, 10, 0));
        kpis.setOpaque(false);
        kpis.add(kpiCard("Records", kCount, "voided ones not counted"));
        kpis.add(kpiCard("Total net pay", kTotal, "payroll expense of these periods"));
        kpis.add(kpiCard("Paid", kPaid, "already paid out"));
        kpis.add(kpiCard("Unpaid", kUnpaid, "still owed to employees"));

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        kpis.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(filters);
        north.add(Box.createVerticalStrut(10));
        north.add(kpis);
        // payroll made by attendance (20261030_02): what the last count did, or its problems
        autoNote.setFont(Theme.SMALL);
        autoNote.setForeground(Theme.MUTED);
        autoNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        autoNote.setBorder(new EmptyBorder(8, 2, 0, 0));
        north.add(autoNote);
        add(north, BorderLayout.NORTH);

        UiKit.styleTable(table, 38);
        table.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.RIGHT, false, false));
        table.getColumnModel().getColumn(0).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, false, true));
        table.getColumnModel().getColumn(1).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, true, false));
        table.getColumnModel().getColumn(2).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        table.getColumnModel().getColumn(6).setCellRenderer(new UiKit.CellRenderer(SwingConstants.RIGHT, true, false));
        table.getColumnModel().getColumn(7).setCellRenderer(new UiKit.PillRenderer(
                s -> "Paid".equals(s) ? Theme.OK : "Unpaid".equals(s) ? Theme.WARN : Theme.MUTED));
        table.getColumnModel().getColumn(8).setCellRenderer(new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
        UiKit.alignHeaders(table, 3, 4, 5, 6);
        int[] widths = {40, 190, 170, 90, 90, 90, 100, 90, 100, 200};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) edit();
            }
        });

        btnNew.addActionListener(e -> {
            if (PayrollEditor.open(SwingUtilities.getWindowAncestor(this), null, employees)) load();
        });
        btnEdit.addActionListener(e -> edit());
        btnVoid.addActionListener(e -> voidSelected());
        btnStaff.setToolTipText("Add the employees who have no app account (helper, cashier...), so they can be paid here too");
        btnStaff.addActionListener(e -> openStaff());

        JPanel tableCard = UiKit.card(new BorderLayout(0, 8), 12, 14);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(UiKit.cardHeading("Payroll records", "Net pay = basic pay + allowances − deductions"), BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnStaff);
        actions.add(btnVoid);
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
        JButton newFromEmpty = UiKit.primaryButton("New payroll record");
        newFromEmpty.addActionListener(e -> {
            if (PayrollEditor.open(SwingUtilities.getWindowAncestor(this), null, employees)) load();
        });
        // Also here: before the first payroll record the list (and its buttons) is not shown.
        JButton staffFromEmpty = UiKit.secondaryButton("Employees...");
        staffFromEmpty.setToolTipText(btnStaff.getToolTipText());
        staffFromEmpty.addActionListener(e -> openStaff());
        stateActions.add(staffFromEmpty);
        stateActions.add(newFromEmpty);
        stateCard.add(stateActions, BorderLayout.NORTH);
        stateCard.add(state, BorderLayout.CENTER);
        center.add(stateCard, "state");
        add(center, BorderLayout.CENTER);
        updateButtons();

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                loadEmployees();
                load();
            }
        });
    }

    private static JLabel kpi() {
        JLabel l = new JLabel("—");
        l.setFont(Theme.font(Font.BOLD, 20));
        l.setForeground(Theme.TEXT);
        return l;
    }

    private static JPanel kpiCard(String title, JLabel value, String hint) {
        JPanel c = UiKit.card(new BorderLayout(0, 4), 12, 14);
        JLabel t = UiKit.caption(title.toUpperCase());
        JLabel h = new JLabel(hint);
        h.setFont(Theme.SMALL);
        h.setForeground(Theme.MUTED);
        c.add(t, BorderLayout.NORTH);
        c.add(value, BorderLayout.CENTER);
        c.add(h, BorderLayout.SOUTH);
        return c;
    }

    private void updateButtons() {
        int r = table.getSelectedRow();
        boolean live = r >= 0 && !"VOID".equals(rows.get(table.convertRowIndexToModel(r)).status);
        btnEdit.setEnabled(live);
        btnVoid.setEnabled(live);
    }

    /** Employees without an app account: add, edit, mark as no longer working here. */
    private void openStaff() {
        if (!Db.payrollStaffReady()) {
            JOptionPane.showMessageDialog(this, Db.notReadyMessage("The list of employees without an app account",
                    Db.UPDATE_29_PAYROLL_STAFF) + "\nUntil then, choose \"Other person (type the name)\" in a payroll record.",
                    "Employees", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        PayrollStaffDialog.open(SwingUtilities.getWindowAncestor(this));
        loadEmployees();
    }

    private void loadEmployees() {
        new SwingWorker<List<PayrollService.Employee>, Void>() {
            @Override
            protected List<PayrollService.Employee> doInBackground() throws Exception {
                return Db.salesReady() ? PayrollService.employees() : new ArrayList<>();
            }

            @Override
            protected void done() {
                try {
                    List<PayrollService.Employee> list = get();
                    Object keep = employee.getSelectedItem();
                    employees.clear();
                    employees.addAll(list);
                    ActionListenerGuard.run(employee, () -> {
                        employee.removeAllItems();
                        employee.addItem("All employees");
                        for (PayrollService.Employee e : list) {
                            employee.addItem(e);
                            if (keep instanceof PayrollService.Employee && ((PayrollService.Employee) keep).same(e)) {
                                employee.setSelectedItem(e);
                            }
                        }
                    });
                } catch (Exception ignored) {
                    // the list below shows the error
                }
            }
        }.execute();
    }

    void load() {
        if (!Db.salesReady()) {
            state.showError("Payroll is not set up on the server yet", Db.notReadyMessage("Payroll"), this::load);
            cards.show(center, "state");
            btnNew.setEnabled(false);
            return;
        }
        btnNew.setEnabled(true);
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
        Object e = employee.getSelectedItem();
        final PayrollService.Employee who = e instanceof PayrollService.Employee ? (PayrollService.Employee) e : null;
        final String user = who != null && !who.isStaff() ? who.username : null;
        final Long staffId = who != null && who.isStaff() ? who.staffId : null;
        final String st = STATUS_CODE[Math.max(0, status.getSelectedIndex())];
        final int mySeq = ++seq;
        // Count the payroll from attendance first (this period and the one before), at most every 20 seconds
        final boolean count = Db.payrollAutoReady() && System.currentTimeMillis() - lastCount > 20_000;
        if (count) lastCount = System.currentTimeMillis();
        final String[] counted = {null};
        state.showLoading(count ? "Counting the payroll from attendance..." : "Loading payroll...");
        cards.show(center, "state");
        new SwingWorker<PayrollService.Page, Void>() {
            @Override
            protected PayrollService.Page doInBackground() throws Exception {
                if (count) counted[0] = countFromAttendance();
                return PayrollService.list(user, staffId, st, f, t);
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                if (counted[0] != null) {
                    autoNote.setText(counted[0]);
                    autoNote.setForeground(counted[0].contains("Not counted") ? Theme.DANGER : Theme.MUTED);
                }
                try {
                    PayrollService.Page p = get();
                    rows.clear();
                    model.setRowCount(0);
                    kCount.setText(String.valueOf(p.count));
                    kTotal.setText(Money.peso(p.total));
                    kPaid.setText(Money.peso(p.paid));
                    kUnpaid.setText(Money.peso(p.unpaid));
                    kUnpaid.setForeground(p.unpaid.signum() > 0 ? Theme.WARN : Theme.TEXT);
                    if (p.rows.isEmpty()) {
                        state.showEmpty("No payroll records for pay periods ending " + f + " to " + t,
                                "Press New payroll record to add one.");
                        cards.show(center, "state");
                        updateButtons();
                        return;
                    }
                    for (PayrollService.Record r : p.rows) {
                        rows.add(r);
                        model.addRow(new Object[]{r.id, r.name + (r.username == null ? "" : "  (" + r.username + ")"), r.period(),
                                Money.plain(r.basic), Money.plain(r.allowances), Money.plain(r.deductions), Money.plain(r.net),
                                PayrollService.statusLabel(r.status), r.paidAt == null ? "" : r.paidAt.toString(),
                                PayrollService.daysText(r.daysWorked, r.dailyRate) + (r.auto ? "  · auto" : "")});
                    }
                    cards.show(center, "table");
                    updateButtons();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    state.showError("Could not load the payroll", c.getMessage(), PayrollPanel.this::load);
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    /**
     * Counts the payroll from attendance for the current and the previous pay period (everyone with a daily rate) and
     * says what it did. A paid record is never changed by the server.
     */
    private static String countFromAttendance() {
        LocalDate today = LocalDate.now(Theme.BUSINESS_TZ);
        LocalDate[] now = PayrollService.payPeriod(today);
        List<String> problems = new ArrayList<>();
        int made = 0, changed = 0;
        try {
            for (LocalDate day : new LocalDate[]{now[0].minusDays(1), today}) {
                PayrollService.SyncResult r = PayrollService.syncAll(day);
                made += r.created;
                changed += r.updated;
                problems.addAll(r.problems);
            }
        } catch (Db.Failure e) {
            return "<html>Not counted from attendance: " + StockDialogs.esc(e.getMessage()) + "</html>";
        }
        String text = "Payroll is made from attendance for employees with a daily rate (Employees...): weekly, Monday to "
                + "Sunday, counted by itself" + (made + changed > 0 ? " (just now: " + made + " made, " + changed + " updated)" : "")
                + ". Paid records are never changed.";
        if (problems.isEmpty()) return text;
        return "<html>" + StockDialogs.esc(text) + "<br><font color='#DC2626'>Not counted: "
                + StockDialogs.esc(String.join("; ", problems)) + "</font></html>";
    }

    private void edit() {
        int r = table.getSelectedRow();
        if (r < 0) return;
        PayrollService.Record rec = rows.get(table.convertRowIndexToModel(r));
        if ("VOID".equals(rec.status)) {
            JOptionPane.showMessageDialog(this, "Payroll record #" + rec.id + " was voided on " + Theme.formatWhen(rec.voidedAt)
                    + " by " + rec.voidedBy + ":\n" + rec.voidReason, "Voided record", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (PayrollEditor.open(SwingUtilities.getWindowAncestor(this), rec, employees)) load();
    }

    private void voidSelected() {
        int r = table.getSelectedRow();
        if (r < 0) return;
        PayrollService.Record rec = rows.get(table.convertRowIndexToModel(r));
        String reason = JOptionPane.showInputDialog(this, "Void payroll record #" + rec.id + " for " + rec.name + " ("
                        + rec.period() + ", net " + Money.peso(rec.net) + ")?\n\nIt will no longer count as payroll. It stays on record "
                        + "with your name and the reason.\n\nReason:", "Void payroll record", JOptionPane.WARNING_MESSAGE);
        if (reason == null) return;
        if (reason.trim().length() < 3) {
            JOptionPane.showMessageDialog(this, "Enter the reason (at least 3 characters).", "Void", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            PayrollService.voidRecord(rec.id, reason.trim());
            load();
        } catch (Db.Failure ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Not voided", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Changes a combo box without firing its listeners (so filling it does not reload the list). */
    private static final class ActionListenerGuard {
        static void run(JComboBox<?> box, Runnable r) {
            java.awt.event.ActionListener[] ls = box.getActionListeners();
            for (java.awt.event.ActionListener l : ls) box.removeActionListener(l);
            try {
                r.run();
            } finally {
                for (java.awt.event.ActionListener l : ls) box.addActionListener(l);
            }
        }
    }

    // =====================================================================================
    // Editor
    // =====================================================================================

    static final class PayrollEditor {
        private PayrollEditor() {
        }

        /** New (rec null) or edit. @return true when saved. */
        static boolean open(Window owner, PayrollService.Record rec, List<PayrollService.Employee> employees) {
            boolean isNew = rec == null;
            JDialog d = new JDialog(owner, isNew ? "New payroll record" : "Edit payroll record #" + rec.id,
                    Dialog.ModalityType.APPLICATION_MODAL);
            final UUID requestId = Db.requestId();
            final boolean[] saved = {false};

            JComboBox<Object> who = new JComboBox<>();
            who.addItem("Other person (type the name)");
            for (PayrollService.Employee e : employees) {
                // employees without an account who no longer work here: only on their own records
                boolean gone = e.isStaff() && !"Active".equals(e.status);
                if (!gone || (!isNew && rec.staffId != null && rec.staffId.equals(e.staffId))) who.addItem(e);
            }
            JTextField name = new JTextField(22);
            JTextField start = new JTextField(10), end = new JTextField(10);
            JTextField basic = new JTextField(10), allow = new JTextField(10), ded = new JTextField(10);
            JLabel net = new JLabel(" ");
            // Days worked from attendance x daily rate = basic pay (sql/20261030_01)
            final boolean withDays = Db.payrollAttendanceReady();
            JTextField days = new JTextField(6), rate = new JTextField(10);
            JLabel att = new JLabel(" ");
            JLabel calc = new JLabel(" ");   // basic pay = days x rate, or why it is not
            JButton useAtt = UiKit.secondaryButton("Use attendance");
            final PayrollService.AttendanceDays[] lastAtt = {null};
            final int[] attSeq = {0};
            final boolean[] daysTyped = {false}, filling = {false};
            JRadioButton unpaid = new JRadioButton("Unpaid", true), paid = new JRadioButton("Paid");
            ButtonGroup g = new ButtonGroup();
            g.add(unpaid);
            g.add(paid);
            JTextField paidOn = new JTextField(10);
            JComboBox<String> method = new JComboBox<>();
            method.addItem("(not recorded)");
            for (String[] m : SalesService.METHODS) method.addItem(m[1]);
            JTextField notes = new JTextField(28);
            JLabel error = new JLabel(" ");
            for (JTextField f : new JTextField[]{name, start, end, basic, allow, ded, paidOn, notes, days, rate}) UiKit.styleField(f);
            att.setFont(Theme.SMALL);
            att.setForeground(Theme.MUTED);
            calc.setFont(Theme.font(Font.BOLD, 12));
            calc.setForeground(Theme.MUTED);
            UiKit.placeholder(days, "0");
            UiKit.placeholder(rate, "e.g. 600");
            days.setToolTipText("Days worked in the pay period. Filled in from attendance; you can change it. "
                    + "Leave it empty to pay a fixed amount.");
            rate.setToolTipText("Pay for one full day. Basic pay becomes days worked x daily rate (a half day = half).");
            who.setFont(Theme.SUBTLE);
            method.setFont(Theme.SUBTLE);
            for (JRadioButton r : new JRadioButton[]{unpaid, paid}) {
                r.setOpaque(false);
                r.setFont(Theme.font(Font.BOLD, 13));
            }
            net.setFont(Theme.font(Font.BOLD, 16));
            error.setFont(Theme.SMALL);
            error.setForeground(Theme.DANGER);
            UiKit.placeholder(allow, "0.00");
            UiKit.placeholder(ded, "0.00");
            UiKit.placeholder(notes, "Notes (optional)");
            LocalDate today = LocalDate.now(Theme.BUSINESS_TZ);

            if (isNew) {
                // weekly pay (Monday to Sunday) once the server makes payroll from attendance; else half a month
                LocalDate[] week = PayrollService.payPeriod(today);
                boolean weekly = Db.payrollAutoReady();
                LocalDate s = weekly ? week[0] : today.getDayOfMonth() <= 15 ? today.withDayOfMonth(1) : today.withDayOfMonth(16);
                LocalDate e = weekly ? week[1] : today.getDayOfMonth() <= 15 ? today.withDayOfMonth(15) : YearMonth.from(today).atEndOfMonth();
                start.setText(s.toString());
                end.setText(e.toString());
                paidOn.setText(today.toString());
            } else {
                boolean found = false;
                for (int i = 1; i < who.getItemCount(); i++) {
                    PayrollService.Employee e = (PayrollService.Employee) who.getItemAt(i);
                    boolean match = rec.staffId != null ? rec.staffId.equals(e.staffId)
                            : rec.username != null && rec.username.equals(e.username);
                    if (match) {
                        who.setSelectedIndex(i);
                        found = true;
                    }
                }
                if (!found) who.setSelectedIndex(0);
                name.setText(rec.name);
                start.setText(rec.start.toString());
                end.setText(rec.end.toString());
                basic.setText(rec.basic.toPlainString());
                allow.setText(rec.allowances.signum() == 0 ? "" : rec.allowances.toPlainString());
                ded.setText(rec.deductions.signum() == 0 ? "" : rec.deductions.toPlainString());
                paid.setSelected("PAID".equals(rec.status));
                unpaid.setSelected(!"PAID".equals(rec.status));
                paidOn.setText(rec.paidAt == null ? today.toString() : rec.paidAt.toString());
                for (int i = 0; i < SalesService.METHODS.length; i++) {
                    if (SalesService.METHODS[i][0].equals(rec.method)) method.setSelectedIndex(i + 1);
                }
                notes.setText(rec.notes == null ? "" : rec.notes);
                if (rec.daysWorked != null) days.setText(Money.qty(rec.daysWorked));
                if (rec.dailyRate != null) rate.setText(rec.dailyRate.toPlainString());
                daysTyped[0] = rec.daysWorked != null;   // keep the saved days; "Use attendance" refreshes them
            }

            Runnable refresh = () -> {
                boolean other = who.getSelectedIndex() == 0;
                name.setEnabled(other);
                if (!other) name.setText(((PayrollService.Employee) who.getSelectedItem()).name);
                paidOn.setEnabled(paid.isSelected());
                method.setEnabled(paid.isSelected());
                error.setText(" ");
                try {
                    BigDecimal n = PayrollService.net(Money.parse(basic.getText(), "basic pay", true),
                            Money.parse(allow.getText(), "allowances", true), Money.parse(ded.getText(), "deductions", true));
                    net.setText("Net pay: " + Money.peso(n));
                    net.setForeground(n.signum() < 0 ? Theme.DANGER : Theme.TEXT);
                    if (n.signum() < 0) error.setText("Deductions cannot be more than basic pay plus allowances.");
                } catch (IllegalArgumentException ex) {
                    net.setText("Net pay: —");
                    error.setText(ex.getMessage());
                }
            };
            who.addActionListener(e -> refresh.run());
            unpaid.addActionListener(e -> refresh.run());
            paid.addActionListener(e -> refresh.run());
            for (JTextField f : new JTextField[]{basic, allow, ded}) f.getDocument().addDocumentListener(StockDialogs.listener(refresh));
            refresh.run();

            // ---- days worked x daily rate -> basic pay; the days come from attendance
            Runnable daysToBasic = () -> {
                try {
                    BigDecimal dd = PayrollService.parseDays(days.getText());
                    BigDecimal rr = Money.parse(rate.getText(), "daily rate", true);
                    BigDecimal b0 = PayrollService.basicFromDays(dd, rr);
                    if (b0 != null) basic.setText(b0.toPlainString());
                } catch (IllegalArgumentException ignored) {
                    // shown when saving
                }
            };
            // Under the attendance line: what the basic pay is (days x rate) or why it is not; on a saved record, also
            // when attendance now counts other days than the record has (owner, 2026-10-30: "kahit halfday, yung
            // basic pay nya hindi nagiging half").
            Runnable showCalc = () -> {
                if (!withDays) return;
                BigDecimal dd, rr, bb;
                try {
                    dd = PayrollService.parseDays(days.getText());
                    rr = Money.parse(rate.getText(), "daily rate", true);
                    bb = Money.parse(basic.getText(), "basic pay", true);
                } catch (IllegalArgumentException ex) {
                    calc.setText(" ");
                    return;
                }
                List<String> lines = new ArrayList<>();
                boolean warn = false;
                String problem = PayrollService.basicCheck(dd, rr, bb);
                if (problem != null) {
                    lines.add(problem);
                    warn = true;
                } else if (dd != null && rr != null) {
                    lines.add("Basic pay = " + Money.qty(dd) + " x " + Money.peso(rr) + " = "
                            + Money.peso(PayrollService.basicFromDays(dd, rr)));
                }
                PayrollService.AttendanceDays a = lastAtt[0];
                boolean auto = !isNew && rec.auto;
                if (auto && !"PAID".equals(rec.status)) {
                    lines.add("Made from attendance: change the attendance or the daily rate to change it.");
                } else if (!isNew && a != null && a.any() && (dd == null || a.days.compareTo(dd) != 0)) {
                    lines.add("Attendance now counts " + PayrollService.dayWord(a.days) + "; this record has "
                            + (dd == null ? "no days" : PayrollService.dayWord(dd))
                            + (auto ? " (paid, so it is not changed)." : ". Click Use attendance to update it."));
                    warn = true;
                }
                calc.setForeground(warn ? Theme.WARN : Theme.MUTED);
                calc.setText(lines.isEmpty() ? " " : "<html>" + StockDialogs.esc(String.join("\n", lines)).replace("\n", "<br>") + "</html>");
            };
            days.getDocument().addDocumentListener(StockDialogs.listener(() -> {
                if (!filling[0]) daysTyped[0] = true;
                daysToBasic.run();
                showCalc.run();
            }));
            rate.getDocument().addDocumentListener(StockDialogs.listener(() -> {
                daysToBasic.run();
                showCalc.run();
            }));
            basic.getDocument().addDocumentListener(StockDialogs.listener(showCalc));
            Runnable fetchAttendance = () -> {
                if (!withDays) return;
                Object w = who.getSelectedItem();
                PayrollService.Employee emp = w instanceof PayrollService.Employee ? (PayrollService.Employee) w : null;
                final LocalDate ps, pe;
                try {
                    ps = LocalDate.parse(start.getText().trim());
                    pe = LocalDate.parse(end.getText().trim());
                } catch (DateTimeParseException ex) {
                    att.setText("Enter the pay period to count the days worked.");
                    return;
                }
                if (emp == null) {
                    lastAtt[0] = null;
                    useAtt.setEnabled(false);
                    att.setText("No attendance for a typed name. Choose the employee from the list to count their days.");
                    return;
                }
                if (pe.isBefore(ps) || pe.toEpochDay() - ps.toEpochDay() > 92) {
                    att.setText("Check the pay period (at most 3 months).");
                    return;
                }
                final int my = ++attSeq[0];
                att.setText("Counting the days worked...");
                useAtt.setEnabled(false);
                final String u = emp.isStaff() ? null : emp.username;
                final Long sid = emp.isStaff() ? emp.staffId : null;
                new SwingWorker<Object[], Void>() {
                    @Override
                    protected Object[] doInBackground() throws Exception {
                        return new Object[]{PayrollService.attendanceDays(u, sid, ps, pe), PayrollService.lastDailyRate(u, sid)};
                    }

                    @Override
                    protected void done() {
                        if (my != attSeq[0]) return;
                        try {
                            Object[] r = get();
                            PayrollService.AttendanceDays a = (PayrollService.AttendanceDays) r[0];
                            lastAtt[0] = a;
                            if (a.any()) {
                                att.setText("<html>From attendance: <b>" + StockDialogs.esc(a.summary()) + "</b></html>");
                            } else {
                                att.setText("<html>No attendance recorded in this period"
                                        + (emp.isStaff() ? " (mark it in Attendance → Employees without an app...)" : " (no Start / End duty)")
                                        + ". Type the days worked, or leave them empty to pay a fixed amount.</html>");
                            }
                            useAtt.setEnabled(a.any() && (isNew || !rec.auto));   // a record made by attendance stays locked
                            // Only a new record is filled in by itself; an existing one changes only with "Use attendance"
                            // (opening a saved record must never change its pay). Nothing recorded: days left empty.
                            if (isNew) {
                                filling[0] = true;
                                try {
                                    if (!daysTyped[0]) days.setText(a.any() ? Money.qty(a.days) : "");
                                    BigDecimal known = emp.dailyRate != null ? emp.dailyRate : (BigDecimal) r[1];
                                    if (emp.dailyRate != null) rate.setText(emp.dailyRate.toPlainString());
                                    else if (rate.getText().trim().isEmpty() && known != null) rate.setText(known.toPlainString());
                                } finally {
                                    filling[0] = false;
                                }
                                if (emp.dailyRate != null && Db.payrollAutoReady()) {
                                    att.setText("<html>" + att.getText().replace("<html>", "").replace("</html>", "")
                                            + "<br><font color='#B45309'>" + StockDialogs.esc(emp.name) + " has a daily rate: their payroll "
                                            + "is made from attendance by itself and shows in the list. No need to add it here.</font></html>");
                                }
                            }
                            showCalc.run();
                        } catch (Exception ex) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            att.setText("<html>" + StockDialogs.esc(cause.getMessage()) + "</html>");
                        }
                    }
                }.execute();
            };
            useAtt.addActionListener(e -> {
                if (lastAtt[0] == null) return;
                filling[0] = true;
                try {
                    days.setText(Money.qty(lastAtt[0].days));
                } finally {
                    filling[0] = false;
                }
                daysTyped[0] = false;
                if (rate.getText().trim().isEmpty()) rate.requestFocusInWindow();   // basic pay needs the daily rate
            });
            Timer attTimer = new Timer(500, e -> fetchAttendance.run());
            attTimer.setRepeats(false);
            who.addActionListener(e -> {
                if (isNew) daysTyped[0] = false;   // another employee: their own days
                attTimer.restart();
            });
            for (JTextField f : new JTextField[]{start, end}) f.getDocument().addDocumentListener(StockDialogs.listener(attTimer::restart));
            if (withDays) {
                showCalc.run();
                fetchAttendance.run();
            }

            JButton thisWeek = UiKit.secondaryButton("Week");
            thisWeek.setToolTipText("Monday to Sunday of the start date's week");
            JButton firstHalf = UiKit.secondaryButton("1st–15th");
            JButton secondHalf = UiKit.secondaryButton("16th–end");
            JButton month = UiKit.secondaryButton("Whole month");
            firstHalf.addActionListener(e -> {
                LocalDate base = parseOr(start.getText(), today);
                start.setText(base.withDayOfMonth(1).toString());
                end.setText(base.withDayOfMonth(15).toString());
            });
            secondHalf.addActionListener(e -> {
                LocalDate base = parseOr(start.getText(), today);
                start.setText(base.withDayOfMonth(16).toString());
                end.setText(YearMonth.from(base).atEndOfMonth().toString());
            });
            month.addActionListener(e -> {
                LocalDate base = parseOr(start.getText(), today);
                start.setText(base.withDayOfMonth(1).toString());
                end.setText(YearMonth.from(base).atEndOfMonth().toString());
            });
            thisWeek.addActionListener(e -> {
                LocalDate[] w = PayrollService.payPeriod(parseOr(start.getText(), today));
                start.setText(w[0].toString());
                end.setText(w[1].toString());
            });
            // A record made by attendance (20261030_02): the employee, period, days, rate and basic pay are counted by
            // the server; only allowances, deductions, notes and Paid are set here.
            if (!isNew && rec.auto) {
                for (JComponent comp : new JComponent[]{who, name, start, end, days, rate, basic, useAtt, thisWeek, firstHalf, secondHalf, month}) {
                    comp.setEnabled(false);
                }
            }

            JPanel form = new JPanel(new GridBagLayout());
            form.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, 10, 10);
            c.gridx = 0;
            c.gridy = 0;
            c.gridwidth = 2;
            form.add(UiKit.group("Employee", who), c);
            c.gridx = 2;
            c.gridwidth = 1;
            form.add(UiKit.group("Name on the record", name), c);
            c.gridy = 1;
            c.gridx = 0;
            form.add(UiKit.group("Period start", start), c);
            c.gridx = 1;
            form.add(UiKit.group("Period end", end), c);
            JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            quick.setOpaque(false);
            quick.add(thisWeek);
            quick.add(firstHalf);
            quick.add(secondHalf);
            quick.add(month);
            c.gridx = 2;
            form.add(UiKit.group("Quick period", quick), c);
            if (withDays) {
                c.gridy = 2;
                c.gridx = 0;
                form.add(UiKit.group("Days worked", days), c);
                c.gridx = 1;
                form.add(UiKit.group("Daily rate (₱)", rate), c);
                JPanel attRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                attRow.setOpaque(false);
                attRow.add(useAtt);
                c.gridx = 2;
                form.add(UiKit.group("Basic pay = days x daily rate", attRow), c);
                c.gridy = 3;
                c.gridx = 0;
                c.gridwidth = 3;
                att.setPreferredSize(new Dimension(560, 34));
                att.setVerticalAlignment(SwingConstants.TOP);
                calc.setPreferredSize(new Dimension(560, 34));
                calc.setVerticalAlignment(SwingConstants.TOP);
                JPanel attBox = new JPanel(new BorderLayout(0, 2));
                attBox.setOpaque(false);
                attBox.add(att, BorderLayout.NORTH);
                attBox.add(calc, BorderLayout.CENTER);
                form.add(attBox, c);
                c.gridwidth = 1;
            }
            c.gridy = 4;
            c.gridx = 0;
            form.add(UiKit.group("Basic pay (₱)", basic), c);
            c.gridx = 1;
            form.add(UiKit.group("Allowances (₱)", allow), c);
            c.gridx = 2;
            form.add(UiKit.group("Deductions (₱)", ded), c);
            c.gridy = 5;
            c.gridx = 0;
            c.gridwidth = 3;
            form.add(net, c);
            c.gridy = 6;
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
            c.gridy = 7;
            c.gridx = 0;
            c.gridwidth = 3;
            form.add(UiKit.group("Notes", notes), c);
            c.gridy = 8;
            form.add(error, c);

            JButton save = UiKit.primaryButton(isNew ? "Save record" : "Save changes");
            JButton cancel = UiKit.secondaryButton("Cancel");
            cancel.addActionListener(e -> d.dispose());
            save.addActionListener(e -> {
                final LocalDate s, en, po;
                final BigDecimal typedBasic, a, de;
                final String nm = name.getText().trim();
                try {
                    s = LocalDate.parse(start.getText().trim());
                    en = LocalDate.parse(end.getText().trim());
                    po = paid.isSelected() ? LocalDate.parse(paidOn.getText().trim()) : null;
                } catch (DateTimeParseException ex) {
                    error.setText("Write dates as YYYY-MM-DD, for example 2026-10-15.");
                    return;
                }
                final BigDecimal dw, dr;
                try {
                    typedBasic = Money.parse(basic.getText(), "basic pay", false);
                    a = Money.parse(allow.getText(), "allowances", true);
                    de = Money.parse(ded.getText(), "deductions", true);
                    dw = withDays ? PayrollService.parseDays(days.getText()) : null;
                    dr = withDays ? Money.parse(rate.getText(), "daily rate", true) : null;
                } catch (IllegalArgumentException ex) {
                    error.setText(ex.getMessage());
                    return;
                }
                // The basic pay must be days worked x daily rate (a half day pays half); ask when it is not.
                BigDecimal basicToSave = typedBasic;
                String problem = withDays ? PayrollService.basicCheck(dw, dr, typedBasic) : null;
                if (problem != null) {
                    if (dr == null) {
                        Object[] opts = {"Enter the daily rate", "Save as typed"};
                        int ch = JOptionPane.showOptionDialog(d, "<html>" + StockDialogs.esc(problem)
                                        + "<br><br>Days worked: <b>" + StockDialogs.esc(PayrollService.dayWord(dw)) + "</b>"
                                        + "<br>Basic pay typed: <b>" + StockDialogs.esc(Money.peso(typedBasic)) + "</b>"
                                        + "<br><br>Without a daily rate the basic pay is not counted from the days."
                                        + "<br>To pay a fixed amount instead, choose Save as typed.</html>",
                                "Daily rate", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
                        if (ch != 1) {
                            error.setText("Enter the daily rate (pay for one full day).");
                            rate.requestFocusInWindow();
                            return;
                        }
                    } else {
                        BigDecimal want = PayrollService.basicFromDays(dw, dr);
                        Object[] opts = {"Use " + Money.peso(want), "Keep " + Money.peso(typedBasic), "Cancel"};
                        int ch = JOptionPane.showOptionDialog(d, "<html>" + StockDialogs.esc(problem)
                                        + "<br><br>Use the days x daily rate, or keep the basic pay you typed?</html>",
                                "Basic pay", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
                        if (ch == 0) {
                            basic.setText(want.toPlainString());
                            basicToSave = want;
                        } else if (ch != 1) {
                            return;
                        }
                    }
                }
                final BigDecimal b = basicToSave;
                // save the days and rate when given, or to clear ones the record had
                final boolean sendDays = withDays && (dw != null || dr != null
                        || (!isNew && (rec.daysWorked != null || rec.dailyRate != null)));
                if (nm.isEmpty()) {
                    error.setText("Choose the employee or type the name.");
                    return;
                }
                Object w = who.getSelectedItem();
                final PayrollService.Employee chosen = w instanceof PayrollService.Employee ? (PayrollService.Employee) w : null;
                final String user = chosen != null && !chosen.isStaff() ? chosen.username : null;
                final Long staffId = chosen != null && chosen.isStaff() ? chosen.staffId : null;
                final String m = method.getSelectedIndex() <= 0 ? null : SalesService.METHODS[method.getSelectedIndex() - 1][0];
                final boolean isPaid = paid.isSelected();
                final String nt = notes.getText().trim();
                save.setEnabled(false);
                cancel.setEnabled(false);
                save.setText("Saving...");
                new SwingWorker<Long, Void>() {
                    @Override
                    protected Long doInBackground() throws Exception {
                        long id = PayrollService.save(isNew ? null : rec.id, requestId, user, staffId, nm, s, en, b,
                                a == null ? BigDecimal.ZERO : a, de == null ? BigDecimal.ZERO : de, isPaid, po, m,
                                nt.isEmpty() ? null : nt, dw, dr, sendDays);
                        // a record made by attendance: count it again (the attendance may have changed meanwhile)
                        if (!isNew && rec.auto && !isPaid && Db.payrollAutoReady()) PayrollService.syncOne(user, staffId, s);
                        return id;
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
                            save.setText(isNew ? "Save record" : "Save changes");
                        }
                    }
                }.execute();
            });

            JPanel root = new JPanel(new BorderLayout(0, 12));
            root.setBackground(Theme.CARD);
            root.setBorder(new EmptyBorder(16, 18, 14, 18));
            root.add(UiKit.cardHeading(d.getTitle(), "Payroll counts in Sales & Profit by the end of its pay period"),
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

        private static LocalDate parseOr(String text, LocalDate fallback) {
            try {
                return LocalDate.parse(text.trim());
            } catch (DateTimeParseException e) {
                return fallback;
            }
        }
    }
}
