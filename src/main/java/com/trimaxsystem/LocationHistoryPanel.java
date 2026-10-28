package com.trimaxsystem;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Live GPS Monitoring &gt; Location history (Admins only): where one agent or driver was, as RECORDED by their
 * phone, for a chosen period. The map joins the recorded points in time order with straight lines; those
 * lines are not the roads taken. Where no point was recorded for more than
 * {@link LocationHistoryService#GAP_MINUTES} minutes the map draws a thin dashed grey line and the list says
 * "no data", because nothing is known about that part. Nothing is estimated or filled in.
 */
public class LocationHistoryPanel extends JPanel {

    private static final String[] COLUMNS = {"#", "Time (Manila)", "Accuracy", "Speed", "Note"};
    private static final String DATE_FORMAT = "MMM d, yyyy h:mm a";

    private final JComboBox<LocationHistoryService.Person> person = new JComboBox<>();
    private final JSpinner from = dateSpinner();
    private final JSpinner to = dateSpinner();
    private final JButton show = UiKit.primaryButton("Show history");
    private final JLabel heading = new JLabel(" ");
    private final JLabel summary = new JLabel(" ");
    private final TileMapView map = new TileMapView();
    private final DefaultTableModel model = new DefaultTableModel(null, COLUMNS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);

    private List<LocationHistoryService.Point> shown = new ArrayList<>();
    private boolean peopleLoaded;
    private boolean busy;
    private boolean syncing;
    private int requestId;

    public LocationHistoryPanel() {
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(14, 20, 16, 20));
        add(buildControls(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildMapSide(), buildListSide());
        split.setResizeWeight(0.68);
        split.setDividerSize(8);
        split.setBorder(null);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);

        map.setLegend(List.of(
                TileMapView.LegendItem.heading("Recorded GPS points"),
                TileMapView.LegendItem.item("First point", Theme.OK, TileMapView.Shape.SOLID),
                TileMapView.LegendItem.item("Last point", Theme.DANGER, TileMapView.Shape.SOLID),
                TileMapView.LegendItem.item("Recorded point", Theme.BLUE, TileMapView.Shape.HOLLOW),
                TileMapView.LegendItem.heading("Lines are straight, not roads"),
                TileMapView.LegendItem.item("Dashed grey: no GPS data", Color.decode("#94A3B8"), TileMapView.Shape.HOLLOW)));
        map.setOnMarkerClick(this::selectFromMap);
        map.setMessage("Choose an agent or driver and a period, then press Show history.");

        setRange(LocationHistoryRules.presets(LocationHistoryService.today(), Theme.BUSINESS_TZ).get("Today"));
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && !peopleLoaded) {
                loadPeople();
            }
        });
    }

    // =====================================================
    // UI
    // =====================================================

    private JComponent buildControls() {
        person.setFont(Theme.SUBTLE);
        person.setPrototypeDisplayValue(new LocationHistoryService.Person("driver_name", "Employee Name", "Driver", "Active"));
        person.setToolTipText("Agents and drivers");
        show.addActionListener(e -> loadHistory());

        JPanel row1 = UiKit.flowRow();
        row1.add(UiKit.caption("Person"));
        row1.add(person);
        row1.add(UiKit.caption("From"));
        row1.add(from);
        row1.add(UiKit.caption("To"));
        row1.add(to);
        row1.add(show);

        JPanel row2 = UiKit.flowRow();
        row2.add(UiKit.caption("Quick"));
        for (Map.Entry<String, Instant[]> p : LocationHistoryRules.presets(LocationHistoryService.today(), Theme.BUSINESS_TZ).entrySet()) {
            JButton b = UiKit.secondaryButton(p.getKey());
            b.addActionListener(e -> {
                // Recomputed on click, so "Today" is right even if the app stays open past midnight.
                setRange(LocationHistoryRules.presets(LocationHistoryService.today(), Theme.BUSINESS_TZ).get(p.getKey()));
                loadHistory();
            });
            row2.add(b);
        }
        JLabel tz = new JLabel("All times are Manila time (UTC+8). Admins only; each viewing is recorded in the Audit Log.");
        tz.setFont(Theme.SMALL);
        tz.setForeground(Theme.MUTED);
        row2.add(tz);

        heading.setFont(Theme.SECTION);
        heading.setForeground(Theme.TEXT);
        summary.setFont(Theme.SUBTLE);
        summary.setForeground(Theme.MUTED);
        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBorder(new EmptyBorder(4, 4, 2, 4));
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.add(heading);
        info.add(summary);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(8, 12, 6, 12)));
        card.add(row1);
        card.add(row2);
        card.add(info);
        return card;
    }

    private JComponent buildMapSide() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setMinimumSize(new Dimension(320, 240));
        p.add(map, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildListSide() {
        table.setRowHeight(28);
        table.setFont(Theme.SUBTLE);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowVerticalLines(false);
        table.setGridColor(Theme.BORDER);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(4).setPreferredWidth(170);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, false, row, col);
                setBorder(new EmptyBorder(0, 6, 0, 6));
                setForeground(col == 4 && v != null && v.toString().startsWith("No GPS data") ? Theme.WARN : Theme.TEXT);
                return this;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || syncing) return;
            int r = table.getSelectedRow();
            if (r >= 0 && r < shown.size()) {
                String id = pointId(shown.get(r));
                map.setSelected(id);
                map.focusMarker(id);
            }
        });

        JScrollPane sp = UiKit.tableScroll(table);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        JLabel title = new JLabel("Recorded points (oldest first)");
        title.setFont(Theme.LABEL);
        title.setForeground(Theme.MUTED);
        title.setBorder(new EmptyBorder(0, 2, 6, 0));
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setMinimumSize(new Dimension(260, 200));
        p.add(title, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private static JSpinner dateSpinner() {
        JSpinner s = new JSpinner(new SpinnerDateModel());
        JSpinner.DateEditor editor = new JSpinner.DateEditor(s, DATE_FORMAT);
        editor.getFormat().setTimeZone(TimeZone.getTimeZone(Theme.BUSINESS_TZ));   // the fields are Manila time
        s.setEditor(editor);
        s.setFont(Theme.SUBTLE);
        editor.getTextField().setColumns(15);
        return s;
    }

    private void setRange(Instant[] r) {
        from.setValue(Date.from(r[0]));
        to.setValue(Date.from(r[1]));
    }

    // =====================================================
    // LOADING
    // =====================================================

    private void loadPeople() {
        peopleLoaded = true;
        map.setMessage("Loading agents and drivers...");
        new SwingWorker<List<LocationHistoryService.Person>, Void>() {
            @Override
            protected List<LocationHistoryService.Person> doInBackground() throws Exception {
                return LocationHistoryService.people();
            }

            @Override
            protected void done() {
                try {
                    List<LocationHistoryService.Person> list = get();
                    person.removeAllItems();
                    for (LocationHistoryService.Person p : list) person.addItem(p);
                    map.setMessage(list.isEmpty() ? "There are no agent or driver accounts yet."
                            : "Choose an agent or driver and a period, then press Show history.");
                } catch (Exception ex) {
                    peopleLoaded = false;   // try again next time the tab is shown
                    map.setMessage("Could not load the agents and drivers:\n" + rootMessage(ex));
                }
            }
        }.execute();
    }

    private void loadHistory() {
        LocationHistoryService.Person who = (LocationHistoryService.Person) person.getSelectedItem();
        if (who == null) {
            JOptionPane.showMessageDialog(this, "Choose an agent or driver first.", "Location history",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Instant a = ((Date) from.getValue()).toInstant();
        Instant b = ((Date) to.getValue()).toInstant();
        String problem = LocationHistoryRules.checkRange(a, b);
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "Location history", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final int req = ++requestId;
        busy = true;
        show.setEnabled(false);
        heading.setText(who.toString());
        summary.setText(LocationHistoryRules.rangeText(a, b, Theme.BUSINESS_TZ) + "  -  loading...");
        clear();
        map.setMessage("Loading the recorded points...");
        new SwingWorker<LocationHistoryService.Result, Void>() {
            @Override
            protected LocationHistoryService.Result doInBackground() throws Exception {
                return LocationHistoryService.load(who.username, a, b);
            }

            @Override
            protected void done() {
                if (req != requestId) return;
                busy = false;
                show.setEnabled(true);
                String range = LocationHistoryRules.rangeText(a, b, Theme.BUSINESS_TZ);
                try {
                    LocationHistoryService.Result r = get();
                    if (r.points.isEmpty()) {
                        summary.setText(range + "  -  " + LocationHistoryRules.summary(0, 0, 1));
                        map.setMessage(LocationHistoryRules.emptyText(who.name == null ? who.username : who.name));
                        return;
                    }
                    summary.setText(range + "  -  " + LocationHistoryRules.summary(r.total, r.points.size(), r.step));
                    render(r);
                } catch (Exception ex) {
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    summary.setText(range);
                    if (cause instanceof AuthService.AuthException) {
                        map.setMessage("Only an active Admin can view location history.\n" + cause.getMessage());
                    } else if (cause instanceof LocationHistoryService.NotInstalledException) {
                        map.setMessage(cause.getMessage());
                    } else {
                        map.setMessage("Could not load the location history:\n" + cause.getMessage());
                    }
                }
            }
        }.execute();
    }

    private void clear() {
        shown = new ArrayList<>();
        model.setRowCount(0);
        map.setRoute(null);
        map.setMarkers(new ArrayList<>());
        map.setBanner(null, false);
    }

    private void render(LocationHistoryService.Result r) {
        shown = r.points;
        List<TileMapView.RoutePoint> route = new ArrayList<>();
        syncing = true;
        try {
            model.setRowCount(0);
            int n = 0;
            for (LocationHistoryService.Point p : r.points) {
                n++;
                String when = LocationHistoryRules.pointTime(p.at.toInstant(), Theme.BUSINESS_TZ);
                String acc = p.accuracyM == null ? "" : "±" + Math.round(p.accuracyM) + " m";
                Long kmh = LocationHistoryRules.kmh(p.speedMps);
                String speed = kmh == null ? "" : kmh + " km/h";
                String note = LocationHistoryRules.isGap(p.gapBeforeS)
                        ? "No GPS data for " + LocationHistoryRules.gapText(p.gapBeforeS) + " before this point" : "";
                model.addRow(new Object[]{n, when, acc, speed, note});
                String tip = "<html><b>" + TileMapView.esc(when) + "</b> (Manila)"
                        + (acc.isEmpty() ? "" : "<br>Accuracy " + TileMapView.esc(acc))
                        + (speed.isEmpty() ? "" : "<br>Speed " + speed)
                        + (note.isEmpty() ? "" : "<br><i>" + TileMapView.esc(note) + "</i>")
                        + "<br>" + String.format(java.util.Locale.ROOT, "%.5f, %.5f", p.lat, p.lon) + "</html>";
                route.add(new TileMapView.RoutePoint(pointId(p), p.lat, p.lon, p.segment, tip));
            }
        } finally {
            syncing = false;
        }
        LocationHistoryService.Point first = r.points.get(0), last = r.points.get(r.points.size() - 1);
        List<TileMapView.Marker> markers = new ArrayList<>();
        markers.add(new TileMapView.Marker("first", "First " + LocationHistoryRules.pointTime(first.at.toInstant(), Theme.BUSINESS_TZ),
                "<html><b>First recorded point</b><br>" + TileMapView.esc(LocationHistoryRules.pointTime(first.at.toInstant(), Theme.BUSINESS_TZ)) + "</html>",
                first.lat, first.lon, Theme.OK, TileMapView.Shape.SOLID));
        if (r.points.size() > 1) {
            markers.add(new TileMapView.Marker("last", "Last " + LocationHistoryRules.pointTime(last.at.toInstant(), Theme.BUSINESS_TZ),
                    "<html><b>Last recorded point</b><br>" + TileMapView.esc(LocationHistoryRules.pointTime(last.at.toInstant(), Theme.BUSINESS_TZ)) + "</html>",
                    last.lat, last.lon, Theme.DANGER, TileMapView.Shape.SOLID));
        }
        map.setMessage(null);
        map.setRoute(route);
        map.setMarkers(markers);
        map.setBanner("Straight lines in time order - not the roads taken", false);
        map.setInfo(r.points.size() + " point" + (r.points.size() == 1 ? "" : "s") + " on the map");
        map.fitAll();
    }

    private void selectFromMap(String id) {
        for (int i = 0; i < shown.size(); i++) {
            if (pointId(shown.get(i)).equals(id)) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
        if ("first".equals(id) && !shown.isEmpty()) selectFromMap(pointId(shown.get(0)));
        else if ("last".equals(id) && !shown.isEmpty()) selectFromMap(pointId(shown.get(shown.size() - 1)));
    }

    private static String pointId(LocationHistoryService.Point p) {
        return "p" + p.id;
    }

    private static String rootMessage(Exception ex) {
        Throwable t = ex.getCause() != null ? ex.getCause() : ex;
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
