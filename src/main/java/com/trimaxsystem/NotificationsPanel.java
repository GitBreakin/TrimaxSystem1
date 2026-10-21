package com.trimaxsystem;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Admin notifications: operational alerts and announcements, with read / unread state. The Kind column keeps
 * the two apart in words, not just colour. This page only shows what is in the database; it cannot send
 * announcements, and nothing here claims anything was delivered to anyone.
 */
public class NotificationsPanel extends JPanel {

    private final JComboBox<String> filter = new JComboBox<>(new String[]{"All", "Unread only"});
    private final JButton refresh = UiKit.secondaryButton("Refresh");
    private final JButton markOne = UiKit.secondaryButton("Mark selected as read");
    private final JButton markAll = UiKit.secondaryButton("Mark all as read");
    private final JLabel info = new JLabel(" ");
    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Status", "When (Asia/Manila)", "Kind", "Category", "Title", "Related record"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JTextArea detail = new JTextArea(4, 40);
    private final Runnable onChanged;
    private List<NotificationService.Item> shown = new ArrayList<>();
    private int seq;
    private boolean busy, loadedOnce;

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
        top.add(info);
        add(top, BorderLayout.NORTH);

        filter.addActionListener(e -> load());
        refresh.addActionListener(e -> load());
        markOne.addActionListener(e -> markSelected());
        markAll.addActionListener(e -> markAllRead());
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int r = table.getSelectedRow();
            if (r < 0 || r >= shown.size()) {
                detail.setText("");
                return;
            }
            NotificationService.Item it = shown.get(r);
            detail.setText(it.title + "\n\n" + (it.body == null ? "(no details)" : it.body)
                    + (it.unread() ? "" : "\n\nRead " + Theme.formatWhen(it.readAt)
                    + (it.readBy == null ? "" : " by " + it.readBy)));
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
    }

    /** Called by the main window each time this page is opened, so the list is never silently out of date. */
    public void reload() {
        if (loadedOnce) load();
    }

    private void load() {
        final int mySeq = ++seq;
        final boolean unreadOnly = filter.getSelectedIndex() == 1;
        setButtons(false);
        state.showLoading("Loading notifications...");
        cards.show(center, "state");
        new SwingWorker<NotificationService.Result, Void>() {
            @Override
            protected NotificationService.Result doInBackground() throws Exception {
                return NotificationService.load(unreadOnly);
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                try {
                    show(get(), unreadOnly);
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

    private void show(NotificationService.Result r, boolean unreadOnly) {
        if (r.tableMissing) {
            shown = new ArrayList<>();
            state.showEmpty("Notifications are not set up yet",
                    "The database has no admin_notifications table (see sql/004_admin_notifications.sql). "
                            + "This does not mean there is nothing to report.");
            cards.show(center, "state");
            return;
        }
        shown = r.items;
        if (shown.isEmpty()) {
            state.showEmpty(unreadOnly ? "No unread notifications" : "No notifications yet", null);
            cards.show(center, "state");
            info.setText("Unread: " + r.unread);
            return;
        }
        model.setRowCount(0);
        for (NotificationService.Item it : shown) {
            model.addRow(new Object[]{it.unread() ? "UNREAD" : "Read", Theme.formatWhen(it.createdAt), it.kind,
                    it.category, it.title, it.related()});
        }
        info.setText("Unread: " + r.unread + (r.truncated ? "   (showing the newest 200)" : ""));
        setButtons(r.unread > 0);
        cards.show(center, "content");
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
