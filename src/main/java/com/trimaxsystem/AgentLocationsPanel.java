package com.trimaxsystem;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.HierarchyEvent;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Read-only map of agents' last reported positions.
 */
public class AgentLocationsPanel extends JPanel {

    private static final Preferences PREFS = Preferences.userNodeForPackage(LiveGpsPanel.class);
    private static final String KEY_REFRESH = "gps.refreshSeconds";
    private static final String KEY_STALE = "gps.staleMinutes";
    private static final int[] REFRESH_CHOICES = {10, 15, 30, 60};
    private static final int[] STALE_CHOICES = {2, 5, 10, 15, 30};
    private static final int DEFAULT_REFRESH = 15;
    private static final int DEFAULT_STALE = 5;
    private static final String[] COLUMNS = {"Agent", "Username", "Account status", "Location", "Last update"};

    private final JTextField searchField = new JTextField(16);
    private final JComboBox<String> locationFilter = new JComboBox<>(new String[]{"All", "Current", "Stale"});
    private final JComboBox<Choice> refreshChoice = new JComboBox<>();
    private final JComboBox<Choice> staleChoice = new JComboBox<>();
    private final JLabel updatedLabel = new JLabel(" ");
    private final JLabel showingLabel = new JLabel(" ");
    private final JLabel currentChip = chip();
    private final JLabel staleChip = chip();
    private final JLabel totalChip = chip();
    private final TileMapView map = new TileMapView();
    private final DefaultTableModel model = new DefaultTableModel(null, COLUMNS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JPanel details = new JPanel(new BorderLayout(0, 8));
    private final javax.swing.JButton refreshButton = UiKit.primaryButton("Refresh now");
    private final Timer pollTimer;

    private GpsService.AgentSnapshot snapshot;
    private String lastError;
    private List<GpsService.AgentPosition> visible = new ArrayList<>();
    private String selectedUsername;
    private long fetchedAtMillis;
    private boolean fetching;
    private boolean loadingControls = true;
    private boolean suppressTableEvents;

    private static final class Choice {
        final int value;

        Choice(int value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value + (value == 1 ? " minute" : " minutes");
        }
    }

    public AgentLocationsPanel() {
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(14, 20, 16, 20));
        add(buildControls(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildMapSide(), buildListSide());
        split.setResizeWeight(0.66);
        split.setDividerSize(8);
        split.setBorder(null);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);

        pollTimer = new Timer(refreshSeconds() * 1000, e -> load(false));
        pollTimer.setRepeats(true);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) {
                load(true);
                pollTimer.start();
            } else {
                pollTimer.stop();
            }
        });

        map.setLegend(buildLegend());
        map.setOnMarkerClick(this::selectAgentFromMap);
        map.setMessage("Loading agent locations...");
        renderDetails();
    }

    private JPanel buildControls() {
        UiKit.styleField(searchField);
        searchField.setToolTipText("Search by agent name or username");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFilters();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFilters();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFilters();
            }
        });
        locationFilter.setFont(Theme.SUBTLE);
        locationFilter.addActionListener(e -> applyFilters());

        int refreshSeconds = refreshSeconds();
        for (int seconds : REFRESH_CHOICES) {
            Choice choice = new Choice(seconds);
            refreshChoice.addItem(choice);
            if (seconds == refreshSeconds) refreshChoice.setSelectedItem(choice);
        }
        int staleMinutes = staleMinutes();
        for (int minutes : STALE_CHOICES) {
            Choice choice = new Choice(minutes);
            staleChoice.addItem(choice);
            if (minutes == staleMinutes) staleChoice.setSelectedItem(choice);
        }
        for (JComboBox<?> combo : new JComboBox<?>[]{locationFilter, refreshChoice, staleChoice}) {
            combo.setFont(Theme.SUBTLE);
        }
        loadingControls = false;

        refreshChoice.setToolTipText("How often locations refresh while this page is open");
        refreshChoice.addActionListener(e -> {
            if (loadingControls) return;
            Choice choice = (Choice) refreshChoice.getSelectedItem();
            if (choice == null) return;
            int seconds = choice.value;
            PREFS.putInt(KEY_REFRESH, seconds);
            pollTimer.setDelay(seconds * 1000);
            pollTimer.restart();
        });
        staleChoice.setToolTipText("Positions older than this are shown as stale");
        staleChoice.addActionListener(e -> {
            if (loadingControls) return;
            Choice choice = (Choice) staleChoice.getSelectedItem();
            if (choice == null) return;
            PREFS.putInt(KEY_STALE, choice.value);
            applyFilters();
        });

        javax.swing.JButton clearButton = UiKit.secondaryButton("Clear filters");
        clearButton.addActionListener(e -> {
            searchField.setText("");
            locationFilter.setSelectedIndex(0);
        });
        refreshButton.addActionListener(e -> load(true));

        JPanel row1 = UiKit.flowRow();
        row1.add(UiKit.caption("Search"));
        row1.add(searchField);
        row1.add(UiKit.caption("Location"));
        row1.add(locationFilter);
        row1.add(clearButton);

        updatedLabel.setFont(Theme.SUBTLE);
        updatedLabel.setForeground(Theme.MUTED);
        JPanel row2 = UiKit.flowRow();
        row2.add(UiKit.caption("Refresh every"));
        row2.add(refreshChoice);
        row2.add(UiKit.caption("Stale after"));
        row2.add(staleChoice);
        row2.add(refreshButton);
        row2.add(updatedLabel);

        JLabel note = new JLabel("Map points show agents' last reported positions, not live movement or location history.");
        note.setFont(Theme.SUBTLE);
        note.setForeground(Theme.MUTED);
        JPanel row3 = UiKit.flowRow();
        row3.add(note);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(8, 12, 4, 12)));
        card.add(row1);
        card.add(row2);
        card.add(row3);
        return card;
    }

    private JPanel buildMapSide() {
        map.setMinimumSize(new Dimension(300, 300));
        map.setPreferredSize(new Dimension(700, 500));
        return map;
    }

    private JPanel buildListSide() {
        JPanel chips = new JPanel(new GridLayout(1, 3, 8, 0));
        chips.setOpaque(false);
        chips.add(currentChip);
        chips.add(staleChip);
        chips.add(totalChip);

        showingLabel.setFont(Theme.SUBTLE);
        showingLabel.setForeground(Theme.MUTED);
        styleTable();
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        tableScroll.getViewport().setBackground(Theme.CARD);

        JPanel listCard = new JPanel(new BorderLayout(0, 6));
        listCard.setOpaque(false);
        listCard.add(showingLabel, BorderLayout.NORTH);
        listCard.add(tableScroll, BorderLayout.CENTER);

        details.setBackground(Theme.CARD);
        details.setBorder(new EmptyBorder(12, 14, 12, 14));
        JScrollPane detailsScroll = new JScrollPane(details);
        detailsScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        detailsScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listCard, detailsScroll);
        vertical.setResizeWeight(0.58);
        vertical.setDividerSize(8);
        vertical.setBorder(null);
        vertical.setOpaque(false);

        JPanel side = new JPanel(new BorderLayout(0, 10));
        side.setOpaque(false);
        side.setMinimumSize(new Dimension(340, 300));
        side.setPreferredSize(new Dimension(420, 500));
        side.add(chips, BorderLayout.NORTH);
        side.add(vertical, BorderLayout.CENTER);
        return side;
    }

    private static JLabel chip() {
        JLabel label = new JLabel(" ", SwingConstants.CENTER);
        label.setFont(new Font("SansSerif", Font.BOLD, 12));
        label.setOpaque(true);
        label.setBackground(Theme.CARD);
        label.setForeground(Theme.TEXT);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(6, 4, 6, 4)));
        return label;
    }

    private void styleTable() {
        table.setRowHeight(30);
        table.setFont(Theme.SUBTLE);
        table.setForeground(Theme.TEXT);
        table.setBackground(Theme.CARD);
        table.setSelectionBackground(Color.decode("#DBEAFE"));
        table.setSelectionForeground(Theme.TEXT);
        table.setShowVerticalLines(false);
        table.setGridColor(Theme.BORDER);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                           boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, false, row, column);
                setFont(column == 3 ? Theme.NAV_ON : Theme.SUBTLE);
                setForeground(Theme.TEXT);
                String text = value == null ? "" : value.toString();
                if (column == 3) {
                    boolean current = text.startsWith("Current");
                    setForeground(current ? Theme.OK : Theme.WARN);
                    text = (current ? "● " : "○ ") + text;
                }
                setText(text);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                return this;
            }
        });
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        table.getTableHeader().setToolTipText("Click a column to sort");
        int[] widths = {130, 100, 105, 85, 105};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || suppressTableEvents) return;
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                selectedUsername = null;
                map.setSelected(null);
                renderDetails();
                return;
            }
            String username = (String) model.getValueAt(table.convertRowIndexToModel(viewRow), 1);
            selectAgent(username, true);
        });
    }

    private static List<TileMapView.LegendItem> buildLegend() {
        List<TileMapView.LegendItem> legend = new ArrayList<>();
        legend.add(TileMapView.LegendItem.heading("Position"));
        legend.add(TileMapView.LegendItem.item("Current", Theme.BLUE, TileMapView.Shape.SOLID));
        legend.add(TileMapView.LegendItem.item("Stale (last known)", Theme.MUTED, TileMapView.Shape.HOLLOW));
        return legend;
    }

    private static int refreshSeconds() {
        int value = PREFS.getInt(KEY_REFRESH, DEFAULT_REFRESH);
        for (int choice : REFRESH_CHOICES) if (choice == value) return value;
        return DEFAULT_REFRESH;
    }

    private static int staleMinutes() {
        int value = PREFS.getInt(KEY_STALE, DEFAULT_STALE);
        for (int choice : STALE_CHOICES) if (choice == value) return value;
        return DEFAULT_STALE;
    }

    private long staleSeconds() {
        Choice choice = (Choice) staleChoice.getSelectedItem();
        return (choice == null ? DEFAULT_STALE : choice.value) * 60L;
    }

    private void load(boolean verifyAdmin) {
        if (fetching) return;
        fetching = true;
        refreshButton.setEnabled(false);
        if (snapshot == null) map.setMessage("Loading agent locations...");
        new SwingWorker<GpsService.AgentSnapshot, Void>() {
            @Override
            protected GpsService.AgentSnapshot doInBackground() throws Exception {
                return GpsService.fetchAgents(verifyAdmin);
            }

            @Override
            protected void done() {
                fetching = false;
                refreshButton.setEnabled(true);
                try {
                    snapshot = get();
                    fetchedAtMillis = System.currentTimeMillis();
                    lastError = null;
                    updatedLabel.setForeground(Theme.MUTED);
                    updatedLabel.setText("Updated " + Theme.formatWhen(new Timestamp(fetchedAtMillis)) + " (Manila time)");
                    applyFilters();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    if (cause instanceof AuthService.AuthException) {
                        pollTimer.stop();
                        java.awt.Window window = SwingUtilities.getWindowAncestor(AgentLocationsPanel.this);
                        javax.swing.JOptionPane.showMessageDialog(AgentLocationsPanel.this, cause.getMessage(),
                                "Not allowed", javax.swing.JOptionPane.ERROR_MESSAGE);
                        AuthFrame.logout(window);
                        return;
                    }
                    lastError = describe(cause);
                    updatedLabel.setForeground(Theme.DANGER);
                    updatedLabel.setText("Could not refresh");
                    cause.printStackTrace();
                    applyFilters();
                }
            }
        }.execute();
    }

    private static String describe(Throwable cause) {
        if (cause instanceof java.sql.SQLException && "42703".equals(((java.sql.SQLException) cause).getSQLState())) {
            return "the user_locations table is missing required location columns";
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.toString() : message;
    }

    private void applyFilters() {
        if (snapshot == null) {
            visible = new ArrayList<>();
            clearTable();
            map.setMarkers(new ArrayList<>());
            map.setSelected(null);
            if (lastError != null) {
                map.setBanner(null, false);
                map.setMessage("Could not load agent locations:\n" + lastError + "\nUse \"Refresh now\" to try again.");
                setChips(0, 0, 0);
                showingLabel.setText("No data loaded");
            }
            renderDetails();
            return;
        }

        if (snapshot.tableMissing) {
            visible = new ArrayList<>();
            selectedUsername = null;
            clearTable();
            map.setMarkers(new ArrayList<>());
            map.setSelected(null);
            map.setBanner(lastError == null ? null : "Could not refresh (" + lastError + ").", lastError != null);
            map.setMessage("Agent location tracking is not set up yet.\nCreate the user_locations table to enable tracking.");
            map.setInfo("");
            setChips(0, 0, 0);
            showingLabel.setText("Location tracking is not configured");
            renderDetails();
            return;
        }

        long staleLimit = staleSeconds();
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        String location = (String) locationFilter.getSelectedItem();
        int current = 0;
        int stale = 0;
        List<GpsService.AgentPosition> filtered = new ArrayList<>();
        for (GpsService.AgentPosition agent : snapshot.agents) {
            GpsService.Freshness freshness = agent.freshness(staleLimit);
            if (freshness == GpsService.Freshness.CURRENT) current++;
            else stale++;
            if (!"All".equals(location) && !freshness.label.equals(location)) continue;
            if (!query.isEmpty() && !matches(agent, query)) continue;
            filtered.add(agent);
        }
        visible = filtered;
        setChips(current, stale, snapshot.agents.size());
        if (selectedUsername != null && filtered.stream().noneMatch(a -> a.username.equals(selectedUsername))) {
            selectedUsername = null;
        }

        suppressTableEvents = true;
        model.setRowCount(0);
        for (GpsService.AgentPosition agent : filtered) {
            GpsService.Freshness freshness = agent.freshness(staleLimit);
            model.addRow(new Object[]{agent.label(), agent.username,
                    agent.accountStatus == null ? "-" : agent.accountStatus,
                    freshness.label, lastUpdateText(agent)});
        }
        suppressTableEvents = false;
        syncTableSelection();
        showingLabel.setText("Showing " + filtered.size() + " of " + snapshot.agents.size()
                + " agent(s) with reported locations"
                + (snapshot.truncated ? " (first " + GpsService.MAX_VEHICLES + " loaded)" : ""));
        updateMap(staleLimit);

        if (lastError != null) {
            map.setBanner("Could not refresh (" + lastError + "). Showing last successful data from "
                    + Theme.formatWhen(new Timestamp(fetchedAtMillis)) + ".", true);
        } else {
            map.setBanner(null, false);
        }
        if (snapshot.agents.isEmpty()) {
            map.setMessage("No agent has reported a location yet.");
        } else if (filtered.isEmpty()) {
            map.setMessage("No agents match these filters.");
        } else {
            map.setMessage(null);
        }
        map.setInfo(filtered.size() + " reported agent(s) shown");
        renderDetails();
    }

    private static boolean matches(GpsService.AgentPosition agent, String query) {
        return contains(agent.name, query) || contains(agent.username, query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private void setChips(int current, int stale, int total) {
        currentChip.setText("● Current: " + current);
        currentChip.setForeground(Theme.OK);
        staleChip.setText("○ Stale: " + stale);
        staleChip.setForeground(Theme.WARN);
        totalChip.setText("Agents with locations: " + total);
    }

    private void updateMap(long staleLimit) {
        List<TileMapView.Marker> markers = new ArrayList<>();
        for (GpsService.AgentPosition agent : visible) {
            boolean isStale = agent.freshness(staleLimit) == GpsService.Freshness.STALE;
            String update = lastUpdateText(agent);
            String tooltip = "<html><b>" + TileMapView.esc(agent.label()) + "</b><br>Username: "
                    + TileMapView.esc(agent.username) + "<br>Account status: "
                    + TileMapView.esc(agent.accountStatus == null ? "-" : agent.accountStatus)
                    + "<br>" + (isStale ? "<b>Stale</b>, last known position" : "Current position")
                    + "<br>Last update: " + TileMapView.esc(update) + "</html>";
            markers.add(new TileMapView.Marker(agent.username,
                    agent.label() + (isStale ? " (stale)" : ""), tooltip, agent.lat, agent.lon,
                    Theme.BLUE,
                    isStale ? TileMapView.Shape.HOLLOW : TileMapView.Shape.SOLID));
        }
        map.setMarkers(markers);
        map.setSelected(selectedUsername);
    }

    private static String lastUpdateText(GpsService.AgentPosition agent) {
        if (agent.recordedAt == null) return "Timestamp unknown";
        return Theme.formatWhen(agent.recordedAt) + " (" + agoText(agent.ageSeconds()) + ")";
    }

    private static String agoText(double seconds) {
        if (Double.isNaN(seconds)) return "unknown age";
        long age = (long) Math.max(0, seconds);
        if (age < 60) return "just now";
        if (age < 3600) return (age / 60) + " min ago";
        if (age < 86400) return (age / 3600) + " h ago";
        return (age / 86400) + " day(s) ago";
    }

    private void selectAgentFromMap(String username) {
        selectAgent(username, false);
    }

    private void selectAgent(String username, boolean fromTable) {
        selectedUsername = username;
        syncTableSelection();
        map.setSelected(username);
        if (fromTable && username != null) map.focusMarker(username);
        renderDetails();
    }

    private void syncTableSelection() {
        suppressTableEvents = true;
        try {
            table.clearSelection();
            if (selectedUsername == null) return;
            for (int row = 0; row < model.getRowCount(); row++) {
                if (!selectedUsername.equals(model.getValueAt(row, 1))) continue;
                int viewRow = table.convertRowIndexToView(row);
                if (viewRow >= 0) {
                    table.setRowSelectionInterval(viewRow, viewRow);
                    table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                }
                break;
            }
        } finally {
            suppressTableEvents = false;
        }
    }

    private GpsService.AgentPosition selectedAgent() {
        if (selectedUsername == null) return null;
        for (GpsService.AgentPosition agent : visible) {
            if (selectedUsername.equals(agent.username)) return agent;
        }
        return null;
    }

    private void renderDetails() {
        details.removeAll();
        GpsService.AgentPosition agent = selectedAgent();
        if (agent == null) {
            JLabel prompt = new JLabel("Select an agent or map marker to view details.", SwingConstants.LEFT);
            prompt.setFont(Theme.SUBTLE);
            prompt.setForeground(Theme.MUTED);
            details.add(prompt, BorderLayout.NORTH);
        } else {
            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            JLabel title = new JLabel(agent.label());
            title.setFont(new Font("SansSerif", Font.BOLD, 16));
            title.setForeground(Theme.TEXT);
            content.add(title);
            JPanel rows = new JPanel(new java.awt.GridBagLayout());
            rows.setOpaque(false);
            GpsService.Freshness freshness = agent.freshness(staleSeconds());
            UiKit.addDetailRow(rows, 0, "Username", agent.username, 260);
            UiKit.addDetailRow(rows, 1, "Account status", agent.accountStatus == null ? "-" : agent.accountStatus, 260);
            UiKit.addDetailRow(rows, 2, "Location status", freshness.label, 260);
            UiKit.addDetailRow(rows, 3, "Last reported", agent.recordedAt == null
                    ? "Timestamp unknown" : Theme.formatWhen(agent.recordedAt), 260);
            UiKit.addDetailRow(rows, 4, "Age", agoText(agent.ageSeconds()), 260);
            UiKit.addDetailRow(rows, 5, "Coordinates",
                    String.format(Locale.ENGLISH, "%.6f, %.6f", agent.lat, agent.lon), 260);
            UiKit.addDetailRow(rows, 6, "Accuracy", agent.accuracyM == null
                    ? "Not reported" : String.format(Locale.ENGLISH, "%.0f m", agent.accuracyM), 260);
            content.add(rows);
            details.add(content, BorderLayout.NORTH);
        }
        details.revalidate();
        details.repaint();
    }

    private void clearTable() {
        suppressTableEvents = true;
        model.setRowCount(0);
        table.clearSelection();
        suppressTableEvents = false;
    }
}
