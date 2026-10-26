package com.trimaxsystem;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Attendance: every Start duty / End duty that agents and drivers record in the mobile app, with the
 * server's date and time, where they were, how long the shift lasted, and the attendance photo.
 * Read-only; times are Asia/Manila.
 */
public class AttendancePanel extends JPanel {

    private static final String[] PERIODS = {"Today", "Last 7 days", "Last 30 days", "All"};
    private static final String[] ROLES = {"Agents and drivers", "Agents", "Drivers"};
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM dd, hh:mm a", Locale.US);
    private static final int PHOTO_W = 340, PHOTO_H = 260;

    private final JComboBox<String> period = new JComboBox<>(PERIODS);
    private final JComboBox<String> role = new JComboBox<>(ROLES);
    private final JTextField search = new JTextField(14);
    private final JButton refresh = UiKit.primaryButton("Refresh");
    private final JLabel info = new JLabel(" ");
    private final JLabel onDutyChip = new JLabel(" ");
    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"When (Asia/Manila)", "Name", "Role", "Event", "Shift length", "GPS"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = new JTable(model);

    // details side
    private final JLabel photo = new JLabel("", SwingConstants.CENTER);
    private final JButton photoRetry = UiKit.secondaryButton("Try again");
    private final JPanel retryRow = UiKit.flowRow();
    private final JPanel facts = new JPanel(new GridBagLayout());
    private final JButton openMap = UiKit.secondaryButton("Open location in map");

    private List<AttendanceService.Event> shown = new ArrayList<>();
    private AttendanceService.Event selected;
    private int seq, photoSeq;
    private boolean loadedOnce;

    public AttendancePanel() {
        super(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel filters = UiKit.flowRow();
        filters.add(UiKit.caption("Period"));
        filters.add(period);
        filters.add(UiKit.caption("Show"));
        filters.add(role);
        filters.add(UiKit.caption("Search"));
        UiKit.styleField(search);
        search.setToolTipText("Name or username");
        filters.add(search);
        filters.add(refresh);
        period.setSelectedIndex(1);

        onDutyChip.setFont(new Font("SansSerif", Font.BOLD, 13));
        info.setForeground(Theme.MUTED);
        JPanel summary = UiKit.flowRow();
        summary.add(onDutyChip);
        summary.add(info);
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(filters);
        top.add(summary);
        add(top, BorderLayout.NORTH);

        period.addActionListener(e -> load());
        role.addActionListener(e -> load());
        search.addActionListener(e -> load());
        refresh.addActionListener(e -> load());

        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(3).setCellRenderer(new EventRenderer());
        int[] widths = {130, 160, 60, 85, 100, 60};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected();
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), buildDetails());
        split.setResizeWeight(1.0);
        split.setDividerSize(8);
        split.setBorder(null);
        split.setOpaque(false);

        center.setOpaque(false);
        center.add(state, "state");
        center.add(split, "content");
        add(center, BorderLayout.CENTER);

        addHierarchyListener(e -> {
            if (isShowing() && !loadedOnce) {
                loadedOnce = true;
                load();
            }
        });
    }

    private JComponent buildDetails() {
        JPanel card = new WidthTrackingPanel(new BorderLayout(0, 10));
        card.setBackground(Theme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(14, 14, 14, 14)));

        JLabel heading = new JLabel("Attendance photo");
        heading.setFont(new Font("SansSerif", Font.BOLD, 15));
        heading.setForeground(Theme.TEXT);

        photo.setPreferredSize(new Dimension(PHOTO_W, PHOTO_H));
        photo.setOpaque(true);
        photo.setBackground(Theme.BACKGROUND);
        photo.setForeground(Theme.MUTED);
        photo.setFont(Theme.SUBTLE);
        photo.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        photoRetry.addActionListener(e -> loadPhoto());

        JPanel photoBox = new JPanel(new BorderLayout(0, 6));
        photoBox.setOpaque(false);
        photoBox.add(photo, BorderLayout.CENTER);
        retryRow.add(photoRetry);
        retryRow.setVisible(false);
        photoBox.add(retryRow, BorderLayout.SOUTH);

        facts.setOpaque(false);
        openMap.setEnabled(false);
        openMap.addActionListener(e -> openInMap());
        JPanel mapRow = UiKit.flowRow();
        mapRow.add(openMap);

        // Photo, then the facts, then the map button, all from the top (no gaps in tall windows).
        JPanel stack = new JPanel(new BorderLayout(0, 8));
        stack.setOpaque(false);
        stack.add(facts, BorderLayout.NORTH);
        stack.add(mapRow, BorderLayout.CENTER);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(photoBox, BorderLayout.NORTH);
        body.add(stack, BorderLayout.CENTER);

        card.add(heading, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        showPhotoMessage("Select a row to see the photo.");
        // Short windows: the details scroll instead of being cut off.
        JScrollPane scroll = new JScrollPane(card, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setMinimumSize(new Dimension(300, 0));
        scroll.setPreferredSize(new Dimension(PHOTO_W + 46, 0));
        return scroll;
    }

    // ------------------------------------------------------------------ loading

    /** Called by the main window each time this page is opened, so the list is never silently out of date. */
    public void reload() {
        if (loadedOnce) load();
    }

    private Instant periodStart() {
        LocalDate today = LocalDate.now(Theme.BUSINESS_TZ);
        switch (period.getSelectedIndex()) {
            case 0: return today.atStartOfDay(Theme.BUSINESS_TZ).toInstant();
            case 1: return today.minusDays(6).atStartOfDay(Theme.BUSINESS_TZ).toInstant();
            case 2: return today.minusDays(29).atStartOfDay(Theme.BUSINESS_TZ).toInstant();
            default: return null;
        }
    }

    private void load() {
        final int mySeq = ++seq;
        final Instant from = periodStart();
        final String roleName = role.getSelectedIndex() == 1 ? "Agent" : role.getSelectedIndex() == 2 ? "Driver" : null;
        final String q = search.getText();
        refresh.setEnabled(false);
        state.showLoading("Loading attendance...");
        cards.show(center, "state");
        new SwingWorker<AttendanceService.Result, Void>() {
            @Override
            protected AttendanceService.Result doInBackground() throws Exception {
                return AttendanceService.load(from, roleName, q);
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                refresh.setEnabled(true);
                try {
                    show(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    shown = new ArrayList<>();
                    state.showError("Could not load attendance",
                            c.getMessage() == null ? c.toString() : c.getMessage(), AttendancePanel.this::load);
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    private void show(AttendanceService.Result r) {
        if (r.notSetUp) {
            shown = new ArrayList<>();
            onDutyChip.setText(" ");
            state.showEmpty("Attendance is not set up on this database",
                    "The mobile duty records (trimax_private.duty_events) are missing. This does not mean nobody worked.");
            cards.show(center, "state");
            return;
        }
        renderOnDuty(r.onDutyNow);
        shown = r.events;
        if (shown.isEmpty()) {
            info.setText(" ");
            state.showEmpty("No duty records for this period",
                    "Agents and drivers record attendance with Start duty / End duty in the mobile app.");
            cards.show(center, "state");
            return;
        }
        model.setRowCount(0);
        for (AttendanceService.Event ev : shown) {
            ZonedDateTime at = ev.at.toInstant().atZone(Theme.BUSINESS_TZ);
            model.addRow(new Object[]{
                    WHEN.format(at), ev.displayName(), ev.role == null ? "" : ev.role,
                    ev.isStart() ? "Start duty" : "End duty",
                    ev.isStart() ? "" : (ev.shiftSeconds == null ? "no start found" : AttendanceService.formatShift(ev.shiftSeconds)),
                    ev.hasGps() ? "Yes" : "None"});
        }
        info.setText(shown.size() + (shown.size() == 1 ? " record" : " records")
                + (r.truncated ? " (newest 500 shown)" : "") + "   •   times are Asia/Manila");
        cards.show(center, "content");
        table.getSelectionModel().setSelectionInterval(0, 0);
    }

    private void renderOnDuty(List<AttendanceService.OnDuty> list) {
        if (list.isEmpty()) {
            onDutyChip.setText("On duty now: nobody");
            onDutyChip.setForeground(Theme.MUTED);
            onDutyChip.setToolTipText(null);
            return;
        }
        StringBuilder names = new StringBuilder();
        StringBuilder tip = new StringBuilder("<html>");
        for (AttendanceService.OnDuty d : list) {
            String n = d.name == null || d.name.isBlank() ? d.username : d.name;
            if (names.length() > 0) names.append(", ");
            names.append(n);
            tip.append(esc(n)).append(" (").append(esc(d.role == null ? "?" : d.role)).append(") since ")
                    .append(esc(Theme.formatWhen(d.since))).append("<br>");
        }
        String text = names.length() > 90 ? names.substring(0, 87) + "..." : names.toString();
        onDutyChip.setText("On duty now (" + list.size() + "): " + text);
        onDutyChip.setForeground(Theme.OK);
        onDutyChip.setToolTipText(tip.append("</html>").toString());
    }

    // ------------------------------------------------------------------ details and photo

    private void showSelected() {
        int r = table.getSelectedRow();
        selected = r >= 0 && r < shown.size() ? shown.get(r) : null;
        facts.removeAll();
        if (selected == null) {
            openMap.setEnabled(false);
            showPhotoMessage("Select a row to see the photo.");
            facts.revalidate();
            facts.repaint();
            return;
        }
        AttendanceService.Event ev = selected;
        int row = 0;
        UiKit.addDetailRow(facts, row++, "Name", ev.displayName() + "  (" + ev.username + ")", 160);
        UiKit.addDetailRow(facts, row++, "Role", ev.role == null ? "Unknown" : ev.role, 160);
        UiKit.addDetailRow(facts, row++, "Event", ev.isStart() ? "Start duty" : "End duty", 160);
        UiKit.addDetailRow(facts, row++, "Server time", Theme.formatWhen(ev.at), 160);
        if (!ev.isStart()) {
            UiKit.addDetailRow(facts, row++, "Shift length",
                    ev.shiftSeconds == null ? "No matching Start duty" : AttendanceService.formatShift(ev.shiftSeconds), 160);
        }
        UiKit.addDetailRow(facts, row, "Location", ev.hasGps()
                ? String.format(Locale.ROOT, "%.6f, %.6f", ev.latitude, ev.longitude)
                : "No GPS from the phone", 160);
        openMap.setEnabled(ev.hasGps());
        facts.revalidate();
        facts.repaint();
        loadPhoto();
    }

    private void loadPhoto() {
        final AttendanceService.Event ev = selected;
        retryRow.setVisible(false);
        if (ev == null) return;
        if (ev.photoPath == null) {
            showPhotoMessage("No photo was sent with this record.");
            return;
        }
        final int mySeq = ++photoSeq;
        showPhotoMessage("Loading photo...");
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return MediaService.load(MediaService.DUTY_PHOTOS, ev.photoPath);
            }

            @Override
            protected void done() {
                if (mySeq != photoSeq) return;
                try {
                    BufferedImage img = get();
                    photo.setText(null);
                    photo.setIcon(new ImageIcon(fit(img, PHOTO_W - 4, PHOTO_H - 4)));
                    photo.setToolTipText("Taken " + Theme.formatWhen(ev.at) + " by " + ev.displayName());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    showPhotoMessage(c.getMessage() == null ? "The photo could not be loaded." : c.getMessage());
                    retryRow.setVisible(true);
                }
            }
        }.execute();
    }

    private void showPhotoMessage(String text) {
        photo.setIcon(null);
        photo.setToolTipText(null);
        photo.setText("<html><body style='text-align:center;width:170px'>" + esc(text) + "</body></html>");
    }

    private static Image fit(BufferedImage img, int maxW, int maxH) {
        double scale = Math.min(maxW / (double) img.getWidth(), maxH / (double) img.getHeight());
        if (scale >= 1) return img;
        return img.getScaledInstance((int) Math.round(img.getWidth() * scale), (int) Math.round(img.getHeight() * scale),
                Image.SCALE_SMOOTH);
    }

    private void openInMap() {
        AttendanceService.Event ev = selected;
        if (ev == null || !ev.hasGps()) return;
        String url = String.format(Locale.ROOT, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f",
                ev.latitude, ev.longitude);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to showing the link
        }
        javax.swing.JOptionPane.showMessageDialog(this, url, "Location", javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A panel that is as wide as the scroll pane showing it, so long values wrap instead of being cut off. */
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return r.height - 32;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            // Fill a tall window (no grey band under the card); scroll in a short one.
            return getParent() instanceof javax.swing.JViewport
                    && getParent().getHeight() > getPreferredSize().height;
        }
    }

    /** "Start duty" in green, "End duty" in muted text: the words carry the meaning, colour only helps. */
    private static final class EventRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            if (!sel) {
                boolean start = "Start duty".equals(value);
                c.setForeground(start ? Theme.OK : Theme.TEXT);
                c.setFont(c.getFont().deriveFont(start ? Font.BOLD : Font.PLAIN));
            }
            return c;
        }
    }
}
