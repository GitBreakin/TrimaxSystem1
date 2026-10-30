package com.trimaxsystem;

import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * Attendance → Time in / Time out...: the Admin records a Start duty ("Time in") or End duty ("Time out") for an agent or
 * driver who could not tap it in the app (owner, 2026-10-30: "dapat pwede si admin mag in and out kay driver").
 * sql/20261030_03: no photo, who recorded it and an optional note are kept, the history (audit log) has it, and a Time
 * out counts in payroll like the app's End duty (8 hours = 1 day).
 */
final class AdminDutyDialog {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private AdminDutyDialog() {
    }

    /** @return true when a Time in / Time out was saved. */
    static boolean open(Window owner) {
        JDialog d = new JDialog(owner, "Time in / Time out", Dialog.ModalityType.APPLICATION_MODAL);
        JComboBox<Object> who = new JComboBox<>();
        who.addItem("Loading the agents and drivers...");
        JLabel action = new JLabel(" ");
        action.setFont(Theme.font(Font.BOLD, 16));
        JTextField day = new JTextField(10), time = new JTextField(6), note = new JTextField(30);
        for (JTextField f : new JTextField[]{day, time, note}) UiKit.styleField(f);
        UiKit.placeholder(note, "Why, e.g. forgot to tap Start duty (optional)");
        day.setToolTipText("YYYY-MM-DD");
        time.setToolTipText("24-hour time, e.g. 08:00 or 17:30 (Philippine time)");
        LocalDateTime now = LocalDateTime.now(Theme.BUSINESS_TZ);
        day.setText(now.toLocalDate().toString());
        time.setText(now.format(HM));
        JLabel shift = new JLabel(" ");
        shift.setFont(Theme.SMALL);
        shift.setForeground(Theme.MUTED);
        JLabel error = new JLabel(" ");
        error.setFont(Theme.SMALL);
        error.setForeground(Theme.DANGER);
        who.setFont(Theme.SUBTLE);
        JButton save = UiKit.primaryButton("Record Time in");
        JButton cancel = UiKit.secondaryButton("Cancel");
        save.setEnabled(false);
        final boolean[] saved = {false};

        Runnable refresh = () -> {
            Object w = who.getSelectedItem();
            error.setText(" ");
            if (!(w instanceof AttendanceService.Person)) {
                action.setText(" ");
                shift.setText(" ");
                save.setEnabled(false);
                return;
            }
            AttendanceService.Person p = (AttendanceService.Person) w;
            boolean out = p.onDuty();
            action.setText(out ? "Time out (End duty) for " + p.name : "Time in (Start duty) for " + p.name);
            action.setForeground(out ? Theme.TEXT : Theme.OK);
            save.setText(out ? "Record Time out" : "Record Time in");
            save.setEnabled(true);
            Instant at = parse(day.getText(), time.getText());
            if (out && at != null && p.lastAt != null) {
                Duration dur = Duration.between(p.lastAt.toInstant(), at);
                shift.setText(dur.isNegative() ? "This is before the Time in (" + Theme.formatWhen(p.lastAt) + ")."
                        : "Shift: " + AttendanceService.formatShift(dur.getSeconds()) + " since the Time in (8 hours = 1 day).");
                shift.setForeground(dur.isNegative() || dur.toHours() >= 24 ? Theme.WARN : Theme.MUTED);
            } else {
                shift.setText(out ? " " : "Then they are on duty: they can End duty in the app, or you record the Time out here.");
                shift.setForeground(Theme.MUTED);
            }
        };
        who.addActionListener(e -> refresh.run());
        for (JTextField f : new JTextField[]{day, time}) f.getDocument().addDocumentListener(StockDialogs.listener(refresh));

        new SwingWorker<List<AttendanceService.Person>, Void>() {
            @Override
            protected List<AttendanceService.Person> doInBackground() throws Exception {
                return AttendanceService.people();
            }

            @Override
            protected void done() {
                try {
                    List<AttendanceService.Person> list = get();
                    who.removeAllItems();
                    if (list.isEmpty()) who.addItem("No active agents or drivers");
                    for (AttendanceService.Person p : list) who.addItem(p);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    who.removeAllItems();
                    who.addItem("Could not load the list");
                    error.setText("<html>" + StockDialogs.esc(c.getMessage()) + "</html>");
                }
                refresh.run();
            }
        }.execute();

        cancel.addActionListener(e -> d.dispose());
        save.addActionListener(e -> {
            Object w = who.getSelectedItem();
            if (!(w instanceof AttendanceService.Person)) return;
            AttendanceService.Person p = (AttendanceService.Person) w;
            Instant at = parse(day.getText(), time.getText());
            if (at == null) {
                error.setText("Write the day as YYYY-MM-DD and the time as HH:MM, for example 2026-10-30 and 08:00.");
                return;
            }
            final String type = p.onDuty() ? "END" : "START";
            final String nt = note.getText().trim();
            save.setEnabled(false);
            cancel.setEnabled(false);
            save.setText("Saving...");
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    AttendanceService.adminEvent(p.username, type, at, nt);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        saved[0] = true;
                        d.dispose();
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        cancel.setEnabled(true);
                        refresh.run();   // the button back to "Record ..." (this clears the error line, so set it after)
                        error.setText("<html>" + StockDialogs.esc(c.getMessage()) + "</html>");
                    }
                }
            }.execute();
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 10, 10);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        form.add(UiKit.group("Agent or driver", who), c);
        c.gridy = 1;
        form.add(action, c);
        c.gridy = 2;
        c.gridwidth = 1;
        form.add(UiKit.group("Day", day), c);
        c.gridx = 1;
        form.add(UiKit.group("Time (24-hour)", time), c);
        JButton nowBtn = UiKit.secondaryButton("Now");
        nowBtn.addActionListener(e -> {
            LocalDateTime n = LocalDateTime.now(Theme.BUSINESS_TZ);
            day.setText(n.toLocalDate().toString());
            time.setText(n.format(HM));
        });
        JPanel nowRow = new JPanel(new BorderLayout());
        nowRow.setOpaque(false);
        nowRow.add(nowBtn, BorderLayout.SOUTH);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.SOUTHWEST;
        form.add(nowRow, c);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 3;
        shift.setPreferredSize(new Dimension(460, 18));
        form.add(shift, c);
        c.gridy = 4;
        form.add(UiKit.group("Note", note), c);
        c.gridy = 5;
        form.add(error, c);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.CARD);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        root.add(UiKit.cardHeading("Time in / Time out",
                "For an agent or driver who could not tap Start / End duty in the app. No photo; your name is kept."), BorderLayout.NORTH);
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

    /** The day and time typed (Philippine time) as an instant, or null when not valid. */
    static Instant parse(String day, String time) {
        try {
            LocalDate dd = LocalDate.parse(day.trim());
            LocalTime tt = LocalTime.parse(time.trim().length() == 4 ? "0" + time.trim() : time.trim());
            return dd.atTime(tt).atZone(Theme.BUSINESS_TZ).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }
}
