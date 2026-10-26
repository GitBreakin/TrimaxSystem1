package com.trimaxsystem;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Admin notifications: operational alerts (SOS / problem reports from the mobile app) and announcements.
 * Alerts go Unread -> Acknowledged (marked read) -> Resolved (with a note of what was done, by whom, when).
 * The Kind and Status columns say this in words, not just colour. This page only shows what is in the
 * database; it cannot send announcements, and nothing here claims anything was delivered to anyone.
 */
public class NotificationsPanel extends JPanel {

    private final JComboBox<String> filter = new JComboBox<>(new String[]{"All", "Unread only", "Open alerts"});
    private final JButton refresh = UiKit.secondaryButton("Refresh");
    private final JButton markOne = UiKit.secondaryButton("Mark selected as read");
    private final JButton markAll = UiKit.secondaryButton("Mark all as read");
    private final JButton resolve = UiKit.secondaryButton("Resolve alert...");
    private final JLabel info = new JLabel(" ");
    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Status", "When (Asia/Manila)", "Kind", "Category", "Title", "Reported by", "Related record"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JTextArea detail = new JTextArea(9, 40);
    private final Runnable onChanged;
    private List<NotificationService.Item> shown = new ArrayList<>();
    private int seq;
    private boolean busy, loadedOnce, resolutionAvailable;

    /** onChanged is called after the unread count may have changed (so the header bell can refresh). */
    public NotificationsPanel(Runnable onChanged) {
        super(new BorderLayout(0, 10));
        this.onChanged = onChanged;
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.setOpaque(false);
        top.add(new JLabel("Show"));
        top.add(filter);
        top.add(refresh);
        top.add(markOne);
        top.add(markAll);
        top.add(resolve);
        top.add(info);
        add(top, BorderLayout.NORTH);

        filter.addActionListener(e -> load());
        refresh.addActionListener(e -> load());
        markOne.addActionListener(e -> markSelected());
        markAll.addActionListener(e -> markAllRead());
        resolve.addActionListener(e -> resolveSelected());
        markOne.setToolTipText("For an alert this means acknowledged: the office has seen it");
        filter.setToolTipText("Open alerts = SOS and problem reports that nobody has resolved yet");
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            updateButtons();
            int r = table.getSelectedRow();
            if (r < 0 || r >= shown.size()) {
                detail.setText("");
                return;
            }
            detail.setText(details(shown.get(r)));
            detail.setCaretPosition(0);
        });
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setOpaque(false);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(new JScrollPane(detail), BorderLayout.SOUTH);
        center.setOpaque(false);
        center.add(state, "state");
        center.add(content, "content");
        add(center, BorderLayout.CENTER);

        setButtons(false);
        addHierarchyListener(e -> {
            if (isShowing() && !loadedOnce) {
                loadedOnce = true;
                load();
            }
        });
    }

    private void setButtons(boolean on) {
        markOne.setEnabled(on);
        markAll.setEnabled(on);
        resolve.setEnabled(on);
    }

    private NotificationService.Item selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= shown.size() ? null : shown.get(r);
    }

    /** Buttons follow the selected row: only what can be done to it is enabled, with the reason as a tooltip. */
    private void updateButtons() {
        if (busy) return;
        NotificationService.Item it = selected();
        markOne.setEnabled(it != null && it.unread());
        markAll.setEnabled(unread > 0);
        boolean canResolve = resolutionAvailable && it != null && it.isAlert() && !it.resolved();
        resolve.setEnabled(canResolve);
        resolve.setToolTipText(!resolutionAvailable
                ? "Needs the database update sql/20261025_07_alert_resolution.sql"
                : it == null ? "Select an alert first"
                : !it.isAlert() ? "Only alerts are resolved; announcements are just marked read"
                : it.resolved() ? "Already resolved" : "Record what was done about this alert");
    }

    static String details(NotificationService.Item it) {
        StringBuilder b = new StringBuilder(it.title == null ? "" : it.title);
        b.append("\n\n").append(it.body == null ? "(no details)" : it.body);
        b.append("\n\n").append(it.kind).append(it.category == null ? "" : " - " + it.category)
                .append(", sent ").append(Theme.formatWhen(it.createdAt));
        if (it.reportedBy != null) b.append(" by ").append(it.reportedBy);
        if (!it.related().isEmpty()) b.append(" (").append(it.related()).append(")");
        b.append(it.unread() ? "\nNot acknowledged yet."
                : "\n" + (it.isAlert() ? "Acknowledged " : "Read ") + Theme.formatWhen(it.readAt)
                + (it.readBy == null ? "" : " by " + it.readBy));
        if (it.resolved()) {
            b.append("\nResolved ").append(Theme.formatWhen(it.resolvedAt))
                    .append(it.resolvedBy == null ? "" : " by " + it.resolvedBy)
                    .append(":\n").append(it.resolutionNote == null ? "" : it.resolutionNote);
        } else if (it.isAlert()) {
            b.append("\nNot resolved yet.");
        }
        return b.toString();
    }

    /** Called by the main window each time this page is opened, so the list is never silently out of date. */
    public void reload() {
        if (loadedOnce) load();
    }

    private void load() {
        final int mySeq = ++seq;
        final NotificationService.Filter which = NotificationService.Filter.values()[Math.max(0, filter.getSelectedIndex())];
        setButtons(false);
        state.showLoading("Loading notifications...");
        cards.show(center, "state");
        new SwingWorker<NotificationService.Result, Void>() {
            @Override
            protected NotificationService.Result doInBackground() throws Exception {
                return NotificationService.load(which);
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                try {
                    show(get(), which);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    shown = new ArrayList<>();
                    state.showError("Could not load notifications",
                            c.getMessage() == null ? c.toString() : c.getMessage(), NotificationsPanel.this::load);
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    private int unread;

    private void show(NotificationService.Result r, NotificationService.Filter which) {
        if (r.tableMissing) {
            shown = new ArrayList<>();
            state.showEmpty("Notifications are not set up yet",
                    "The database has no admin_notifications table (see sql/004_admin_notifications.sql). "
                            + "This does not mean there is nothing to report.");
            cards.show(center, "state");
            return;
        }
        shown = r.items;
        unread = r.unread;
        resolutionAvailable = r.resolutionAvailable;
        String counts = "Unread: " + r.unread
                + (r.resolutionAvailable ? "   Open alerts: " + r.openAlerts : "   Resolving alerts: not set up yet");
        info.setText(counts + (r.truncated ? "   (showing the newest 200)" : ""));
        info.setToolTipText(r.resolutionAvailable ? null
                : "Needs the database update sql/20261025_07_alert_resolution.sql; alerts can still be marked read");
        if (shown.isEmpty()) {
            state.showEmpty(which == NotificationService.Filter.UNREAD ? "No unread notifications"
                            : which == NotificationService.Filter.OPEN_ALERTS ? "No open alerts" : "No notifications yet",
                    which == NotificationService.Filter.OPEN_ALERTS ? "Every SOS and problem report has been resolved." : null);
            cards.show(center, "state");
            setButtons(false);
            markAll.setEnabled(r.unread > 0);
            return;
        }
        model.setRowCount(0);
        for (NotificationService.Item it : shown) {
            model.addRow(new Object[]{it.stateText(), Theme.formatWhen(it.createdAt), it.kind,
                    it.category, it.title, it.reportedBy == null ? "" : it.reportedBy, it.related()});
        }
        detail.setText("");
        updateButtons();
        cards.show(center, "content");
    }

    private void resolveSelected() {
        NotificationService.Item it = selected();
        if (it == null || !it.isAlert() || it.resolved() || !resolutionAvailable || busy) return;
        JTextArea note = new JTextArea(5, 36);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        JLabel count = new JLabel("0 / " + NotificationService.MAX_NOTE);
        count.setForeground(Theme.MUTED);
        note.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void upd() {
                int n = note.getText().trim().length();
                count.setText(n + " / " + NotificationService.MAX_NOTE);
                count.setForeground(n > NotificationService.MAX_NOTE ? Theme.DANGER : Theme.MUTED);
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) { upd(); }

            public void removeUpdate(javax.swing.event.DocumentEvent e) { upd(); }

            public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
        });
        JPanel p = new JPanel(new BorderLayout(0, 6));
        JLabel head = new JLabel("<html><b>" + esc(it.title) + "</b><br>Sent " + esc(Theme.formatWhen(it.createdAt))
                + (it.reportedBy == null ? "" : " by " + esc(it.reportedBy))
                + "<br><br>What was done? (saved with your name and the time; it cannot be edited later)</html>");
        p.add(head, BorderLayout.NORTH);
        p.add(new JScrollPane(note), BorderLayout.CENTER);
        p.add(count, BorderLayout.SOUTH);
        while (true) {
            int ok = JOptionPane.showConfirmDialog(this, p, "Resolve alert", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return;
            String bad = NotificationService.checkNote(note.getText());
            if (bad == null) break;
            JOptionPane.showMessageDialog(this, bad, "Resolve alert", JOptionPane.WARNING_MESSAGE);
        }
        final String text = note.getText();
        busy = true;
        setButtons(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                NotificationService.resolve(it.id, text);
                return null;
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    get();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(NotificationsPanel.this,
                            c instanceof NotificationService.ResolveException ? c.getMessage()
                                    : "The alert was NOT resolved: " + c.getMessage(),
                            "Resolve alert", JOptionPane.ERROR_MESSAGE);
                }
                load();   // always show what the database says now (also when someone else resolved it first)
                if (onChanged != null) onChanged.run();
            }
        }.execute();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void markSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= shown.size()) {
            JOptionPane.showMessageDialog(this, "Select a notification first.", "Mark as read",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        NotificationService.Item it = shown.get(row);
        if (!it.unread()) {
            JOptionPane.showMessageDialog(this, "That notification is already read.", "Mark as read",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        run(it.id);
    }

    private void markAllRead() {
        if (JOptionPane.showConfirmDialog(this, "Mark every unread notification as read?", "Mark all as read",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            run(null);
        }
    }

    private void run(Long id) {
        if (busy) return;
        busy = true;
        setButtons(false);
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return NotificationService.markRead(id);
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    get();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(NotificationsPanel.this,
                            "Nothing was changed: " + c.getMessage(), "Mark as read", JOptionPane.ERROR_MESSAGE);
                }
                load();   // always show what the database says now
                if (onChanged != null) onChanged.run();
            }
        }.execute();
    }
}
