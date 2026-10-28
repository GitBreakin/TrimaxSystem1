package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Analytics &amp; Reports &gt; Sales &amp; Inventory Intelligence: best sellers, slow, non-moving and overstocked
 * products, and restock recommendations for a chosen period, each with the reason and a suggested action.
 * Everything comes from {@link InsightService} (the database's rules); nothing is estimated here. Clicking a
 * product opens it in Inventory.
 */
public class InsightsPanel extends JPanel {

    private static final ZoneId TZ = Theme.BUSINESS_TZ;
    private static final String[] PRESETS = {"Last 7 days", "Last 30 days", "Last 90 days", "Custom"};
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private final JComboBox<String> preset = new JComboBox<>(PRESETS);
    private final JTextField fromField = new JTextField(9);
    private final JTextField toField = new JTextField(9);
    private final JButton apply = UiKit.primaryButton("Apply");
    private final JTextField search = new JTextField(16);
    private final JComboBox<String> bestSort = new JComboBox<>(new String[]{"Quantity sold", "Revenue"});
    private final JLabel formError = new JLabel(" ");
    private final JLabel period = new JLabel(" ");
    private final JLabel rules = new JLabel(" ");
    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JTabbedPane tabs = new JTabbedPane();

    private final JLabel kAll = kpi(), kFast = kpi(), kSlow = kpi(), kNon = kpi(), kOver = kpi(), kRestock = kpi(),
            kLow = kpi(), kInsufficient = kpi();

    private final Section best = new Section("Best-selling",
            new String[]{"Rank", "ID", "Product", "Supplier", "Qty sold", "Revenue (₱)", "Stock now", "Trend vs previous", "Suggested"},
            i -> i.unitsSold != null && i.unitsSold.signum() > 0);
    private final Section slow = new Section("Slow-moving",
            new String[]{"ID", "Product", "Stock now", "Sold", "Last sale", "Days since", "Per day", "Why", "Suggested action"},
            i -> i.has("SLOW"));
    private final Section non = new Section("Non-moving",
            new String[]{"ID", "Product", "Stock now", "Observed", "Last sale", "Days since", "Why", "Suggested action"},
            i -> i.has("NON_MOVING"));
    private final Section over = new Section("Overstock",
            new String[]{"ID", "Product", "Stock now", "Sold", "Per day", "Days of stock", "Why", "Suggested action"},
            i -> i.has("OVERSTOCK"));
    private final Section restock = new Section("Restock & low stock",
            new String[]{"ID", "Product", "Stock now", "Per day", "Days of stock", "Order about", "Why", "Suggested action"},
            i -> i.has("RESTOCK") || i.has("CRITICAL"));
    private final Section all = new Section("All products",
            new String[]{"ID", "Product", "Supplier", "Stock now", "Sold", "Per day", "Days of stock", "Status", "Why"},
            i -> true);

    private final IntConsumer openProduct;
    private final Runnable onAlerts;
    private InsightService.Result current;
    private int seq;
    private boolean loadedOnce;

    /**
     * @param openProduct opens a product in Inventory (by id)
     * @param onAlerts    called after the alerts were refreshed (so the header bell can update)
     */
    public InsightsPanel(IntConsumer openProduct, Runnable onAlerts) {
        super(new BorderLayout(0, 10));
        this.openProduct = openProduct;
        this.onAlerts = onAlerts;
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 16, 12, 16));

        preset.setFont(Theme.SUBTLE);
        bestSort.setFont(Theme.SUBTLE);
        UiKit.styleField(fromField);
        UiKit.styleField(toField);
        UiKit.searchField(search, "Product or supplier");
        fromField.setToolTipText("YYYY-MM-DD (Manila)");
        toField.setToolTipText("YYYY-MM-DD (Manila), included");
        preset.addActionListener(e -> {
            applyPreset();
            if (preset.getSelectedIndex() < PRESETS.length - 1) apply();
        });
        apply.addActionListener(e -> apply());
        fromField.addActionListener(e -> apply());
        toField.addActionListener(e -> apply());
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { render(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { render(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { render(); }
        });
        bestSort.addActionListener(e -> render());
        formError.setForeground(Theme.DANGER);
        formError.setFont(Theme.SMALL);

        JPanel filters = UiKit.flowRow();
        filters.add(UiKit.caption("Period"));
        filters.add(preset);
        filters.add(UiKit.caption("From"));
        filters.add(fromField);
        filters.add(UiKit.caption("To"));
        filters.add(toField);
        filters.add(apply);
        filters.add(UiKit.caption("Search"));
        filters.add(search);
        filters.add(formError);

        period.setFont(Theme.SUBTLE);
        period.setForeground(Theme.TEXT);
        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        period.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(filters);
        north.add(period);
        add(north, BorderLayout.NORTH);

        JPanel kpis = new JPanel(new GridLayout(1, 8, 8, 0));
        kpis.setOpaque(false);
        kpis.add(kpiCard("Products", kAll, null));
        kpis.add(kpiCard("Fast-moving", kFast, "FAST"));
        kpis.add(kpiCard("Critical / low", kLow, "CRITICAL"));
        kpis.add(kpiCard("Restock", kRestock, "RESTOCK"));
        kpis.add(kpiCard("Slow-moving", kSlow, "SLOW"));
        kpis.add(kpiCard("Non-moving", kNon, "NON_MOVING"));
        kpis.add(kpiCard("Overstock", kOver, "OVERSTOCK"));
        kpis.add(kpiCard("Insufficient data", kInsufficient, "INSUFFICIENT_DATA"));

        JPanel bestHead = UiKit.flowRow();
        bestHead.add(UiKit.caption("Sort by"));
        bestHead.add(bestSort);
        JLabel bestNote = new JLabel("Quantity sold and revenue are different rankings: an expensive item can earn more while selling fewer.");
        bestNote.setFont(Theme.SMALL);
        bestNote.setForeground(Theme.MUTED);
        bestHead.add(bestNote);
        JPanel bestTab = new JPanel(new BorderLayout(0, 6));
        bestTab.setOpaque(false);
        bestTab.add(bestHead, BorderLayout.NORTH);
        bestTab.add(best.scroll, BorderLayout.CENTER);
        tabs.addTab(best.title, bestTab);
        for (Section s : new Section[]{restock, slow, non, over, all}) tabs.addTab(s.title, s.scroll);

        rules.setFont(Theme.SMALL);
        rules.setForeground(Theme.MUTED);
        JLabel hint = new JLabel("Click a product to open it in Inventory. Hover a row to read the full reason.");
        hint.setFont(Theme.SMALL);
        hint.setForeground(Theme.MUTED);
        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        rules.setAlignmentX(Component.LEFT_ALIGNMENT);
        south.add(hint);
        south.add(rules);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(kpis, BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);
        body.add(south, BorderLayout.SOUTH);
        center.setOpaque(false);
        center.add(state, "state");
        center.add(body, "content");
        add(center, BorderLayout.CENTER);

        preset.setSelectedIndex(1);   // Last 30 days (the listener fills the dates; loading waits until shown)
        addHierarchyListener(e -> {
            if (!loadedOnce && isShowing()) {
                loadedOnce = true;
                apply();
                refreshAlerts();
            }
        });
    }

    // =====================================================
    // PERIOD
    // =====================================================

    private void applyPreset() {
        LocalDate today = LocalDate.now(TZ);
        switch (preset.getSelectedIndex()) {
            case 0 -> setDates(today.minusDays(6), today);
            case 1 -> setDates(today.minusDays(29), today);
            case 2 -> setDates(today.minusDays(89), today);
            default -> { /* Custom: the Admin types the dates */ }
        }
    }

    private void setDates(LocalDate from, LocalDate to) {
        fromField.setText(from.toString());
        toField.setText(to.toString());
    }

    private void apply() {
        if (!loadedOnce) return;
        LocalDate from, to;
        try {
            from = LocalDate.parse(fromField.getText().trim());
            to = LocalDate.parse(toField.getText().trim());
        } catch (DateTimeParseException ex) {
            formError.setText("Enter both dates as YYYY-MM-DD.");
            return;
        }
        LocalDate today = LocalDate.now(TZ);
        if (from.isAfter(to)) {
            formError.setText("The start must be on or before the end.");
            return;
        }
        if (to.isAfter(today)) {
            formError.setText("The end cannot be after today.");
            return;
        }
        if (from.plusDays(365).isBefore(to)) {
            formError.setText("Choose at most one year.");
            return;
        }
        formError.setText(" ");
        Instant a = from.atStartOfDay(TZ).toInstant();
        // Up to the end of the last day, but never past now (today is not over yet).
        Instant b = to.plusDays(1).atStartOfDay(TZ).toInstant();
        if (b.isAfter(Instant.now())) b = Instant.now();
        load(a, b, from, to);
    }

    private void load(Instant a, Instant b, LocalDate fromDay, LocalDate toDay) {
        final int mySeq = ++seq;
        apply.setEnabled(false);
        state.showLoading("Analysing sales and stock...");
        cards.show(center, "state");
        new SwingWorker<InsightService.Result, Void>() {
            @Override
            protected InsightService.Result doInBackground() throws Exception {
                return InsightService.load(a, b);
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                apply.setEnabled(true);
                try {
                    current = get();
                    LocalDate prevTo = fromDay.minusDays(1);
                    LocalDate prevFrom = prevTo.minusDays(toDay.toEpochDay() - fromDay.toEpochDay());
                    period.setText(DAY.format(fromDay) + " to " + DAY.format(toDay) + " (Manila)   ·   trend compared with "
                            + DAY.format(prevFrom) + " to " + DAY.format(prevTo));
                    rules.setText("<html>" + esc(InsightService.rulesText(current.settings)) + "</html>");
                    render();
                    cards.show(center, "content");
                } catch (Exception ex) {
                    current = null;
                    Throwable c = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (c instanceof InsightService.NotInstalledException) {
                        state.showEmpty("Not set up yet", c.getMessage()
                                + " Until then, the Booking reports tab still works.");
                    } else {
                        state.showError("Could not analyse the sales and stock",
                                c.getMessage() == null ? c.toString() : c.getMessage(), () -> apply());
                    }
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    /** Opening this tab also checks for new inventory alerts (there is no scheduler on the server). */
    private void refreshAlerts() {
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return InsightService.refreshAlerts();
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ignored) {
                    // alerts are a convenience; the analysis on screen does not depend on them
                }
                if (onAlerts != null) onAlerts.run();
            }
        }.execute();
    }

    // =====================================================
    // SHOWING
    // =====================================================

    private void render() {
        if (current == null) return;
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        List<InsightService.Item> items = new ArrayList<>();
        for (InsightService.Item i : current.items) {
            if (q.isEmpty() || (i.name != null && i.name.toLowerCase(Locale.ROOT).contains(q))
                    || (i.supplier != null && i.supplier.toLowerCase(Locale.ROOT).contains(q))
                    || String.valueOf(i.productId).equals(q)) {
                items.add(i);
            }
        }
        kAll.setText(String.valueOf(items.size()));
        kFast.setText(count(items, "FAST"));
        kLow.setText(count(items, "CRITICAL"));
        kRestock.setText(count(items, "RESTOCK"));
        kSlow.setText(count(items, "SLOW"));
        kNon.setText(count(items, "NON_MOVING"));
        kOver.setText(count(items, "OVERSTOCK"));
        kInsufficient.setText(count(items, "INSUFFICIENT_DATA"));

        // Best-selling: quantity (default) or revenue
        List<InsightService.Item> sold = filter(items, best.include);
        sold.sort(InsightRules.bestSelling(i -> i.unitsSold, i -> i.revenue, i -> i.name, bestSort.getSelectedIndex() == 1));
        best.reset(sold);
        int rank = 0;
        for (InsightService.Item i : sold) {
            rank++;
            String suggested = i.has("RESTOCK") ? "Restock: order about " + i.recommendedQty
                    : i.has("CRITICAL") ? InsightRules.label("CRITICAL") + " - " + i.action("CRITICAL") : "";
            best.model.addRow(new Object[]{rank, i.productId, i.name, nz(i.supplier), InsightRules.qty(i.unitsSold),
                    Money.plain(i.revenue), Money.qty(i.stock), InsightRules.trend(i.unitsSold, i.prevUnits, i.trendPct), suggested});
        }

        List<InsightService.Item> s1 = filter(items, slow.include);
        slow.reset(s1);
        for (InsightService.Item i : s1) {
            slow.model.addRow(new Object[]{i.productId, i.name, Money.qty(i.stock), InsightRules.qty(i.unitsSold), lastSale(i),
                    i.daysSinceLastSale == null ? "-" : i.daysSinceLastSale, InsightRules.perDay(i.avgDaily),
                    i.reason("SLOW"), i.action("SLOW")});
        }

        List<InsightService.Item> s2 = filter(items, non.include);
        non.reset(s2);
        for (InsightService.Item i : s2) {
            non.model.addRow(new Object[]{i.productId, i.name, Money.qty(i.stock),
                    "last " + current.settings.nonMovingDays + " days", i.neverSold ? "Never sold" : lastSale(i),
                    i.daysSinceLastSale == null ? "-" : i.daysSinceLastSale, i.reason("NON_MOVING"), i.action("NON_MOVING")});
        }

        List<InsightService.Item> s3 = filter(items, over.include);
        over.reset(s3);
        for (InsightService.Item i : s3) {
            over.model.addRow(new Object[]{i.productId, i.name, Money.qty(i.stock), InsightRules.qty(i.unitsSold),
                    InsightRules.perDay(i.avgDaily), InsightRules.coverage(i.coverageDays), i.reason("OVERSTOCK"), i.action("OVERSTOCK")});
        }

        List<InsightService.Item> s4 = filter(items, restock.include);
        s4.sort((x, y) -> {
            BigDecimal cx = x.coverageDays == null ? BigDecimal.valueOf(Long.MAX_VALUE) : x.coverageDays;
            BigDecimal cy = y.coverageDays == null ? BigDecimal.valueOf(Long.MAX_VALUE) : y.coverageDays;
            int c = cx.compareTo(cy);
            return c != 0 ? c : x.stock.compareTo(y.stock);
        });
        restock.reset(s4);
        for (InsightService.Item i : s4) {
            String why = i.has("RESTOCK") ? i.reason("RESTOCK") : i.reason("CRITICAL");
            String act = i.has("RESTOCK") ? i.action("RESTOCK") : i.action("CRITICAL")
                    + (i.avgDaily == null || i.avgDaily.signum() == 0 ? " No sales rate yet, so no quantity is suggested." : "");
            restock.model.addRow(new Object[]{i.productId, i.name, Money.qty(i.stock), InsightRules.perDay(i.avgDaily),
                    InsightRules.coverage(i.coverageDays), i.recommendedQty == null ? "-" : i.recommendedQty, why, act});
        }

        all.reset(items);
        for (InsightService.Item i : items) {
            all.model.addRow(new Object[]{i.productId, i.name, nz(i.supplier), Money.qty(i.stock), InsightRules.qty(i.unitsSold),
                    InsightRules.perDay(i.avgDaily), InsightRules.coverage(i.coverageDays), InsightRules.labels(i.flags),
                    i.allReasons().replace('\n', ' ')});
        }

        best.emptyIf(2, "No completed sales in this period.");
        restock.emptyIf(1, "No product needs restocking now.");
        slow.emptyIf(1, "No slow-moving product in this period.");
        non.emptyIf(1, "No non-moving product (in stock, no sale in " + current.settings.nonMovingDays + " days).");
        over.emptyIf(1, "No overstocked product.");
        all.emptyIf(1, q.isEmpty() ? "There are no products." : "No product matches the search.");

        for (Section s : new Section[]{best, restock, slow, non, over, all}) {
            int idx = indexOfTab(s.title);
            if (idx >= 0) tabs.setTitleAt(idx, s.title + " (" + s.shown.size() + ")");
        }
    }

    private int indexOfTab(String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getTitleAt(i).startsWith(title)) return i;
        return -1;
    }

    private static List<InsightService.Item> filter(List<InsightService.Item> items, Predicate<InsightService.Item> p) {
        List<InsightService.Item> out = new ArrayList<>();
        for (InsightService.Item i : items) if (p.test(i)) out.add(i);
        return out;
    }

    private static String count(List<InsightService.Item> items, String flag) {
        return String.valueOf(items.stream().filter(i -> i.has(flag)).count());
    }

    private static String lastSale(InsightService.Item i) {
        return i.lastSaleAt == null ? "Never" : DAY.format(i.lastSaleAt.toInstant().atZone(TZ));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JLabel kpi() {
        JLabel l = new JLabel("-");
        l.setFont(Theme.font(Font.BOLD, 20));
        l.setForeground(Theme.TEXT);
        return l;
    }

    /** A summary card; clicking it opens the matching list. */
    private JPanel kpiCard(String title, JLabel value, String flag) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBackground(Theme.CARD);
        Color accent = flag == null ? Theme.BORDER : InsightRules.color(flag);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                        BorderFactory.createMatteBorder(0, 3, 0, 0, accent)),
                new EmptyBorder(8, 10, 8, 10)));
        JLabel t = new JLabel(title);
        t.setForeground(Theme.MUTED);
        t.setFont(Theme.SMALL);
        p.add(t, BorderLayout.NORTH);
        p.add(value, BorderLayout.CENTER);
        String tab = flag == null ? "All products" : switch (flag) {
            case "FAST" -> "Best-selling";
            case "CRITICAL", "RESTOCK" -> "Restock & low stock";
            case "SLOW" -> "Slow-moving";
            case "NON_MOVING" -> "Non-moving";
            case "OVERSTOCK" -> "Overstock";
            default -> "All products";
        };
        p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        p.setToolTipText("Show " + tab);
        p.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = indexOfTab(tab);
                if (idx >= 0) tabs.setSelectedIndex(idx);
            }
        });
        return p;
    }

    /** One list: its table, the items it shows (for clicks and tooltips) and which items belong in it. */
    private final class Section {
        final String title;
        final Predicate<InsightService.Item> include;
        final DefaultTableModel model;
        final JTable table;
        final JScrollPane scroll;
        List<InsightService.Item> shown = new ArrayList<>();

        Section(String title, String[] cols, Predicate<InsightService.Item> include) {
            this.title = title;
            this.include = include;
            this.model = new DefaultTableModel(cols, 0) {
                @Override
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
            this.table = new JTable(model) {
                @Override
                public String getToolTipText(MouseEvent e) {
                    int r = rowAtPoint(e.getPoint());
                    if (r < 0 || r >= shown.size()) return null;
                    InsightService.Item i = shown.get(r);
                    return "<html><b>" + esc(i.name) + "</b> (#" + i.productId + ")<br>"
                            + esc(i.allReasons()).replace("\n", "<br>") + "<br><br><b>Suggested:</b> " + esc(i.allActions())
                            + "<br><i>Click to open it in Inventory</i></html>";
                }
            };
            UiKit.styleTable(table, 30);
            table.setDefaultRenderer(Object.class, new UiKit.CellRenderer(SwingConstants.LEFT, false, false));
            table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
            table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            // Narrow number columns, room for the product name and the written reason / action.
            for (int c = 0; c < cols.length; c++) {
                String h = cols[c];
                int w = h.equals("Product") ? 190 : h.equals("Supplier") ? 120
                        : h.equals("Why") || h.equals("Suggested action") || h.equals("Suggested") ? 330
                        : h.equals("Status") ? 200 : h.equals("Last sale") || h.equals("Observed") ? 110
                        : h.equals("Trend vs previous") || h.equals("Revenue (₱)") ? 110 : 70;
                table.getColumnModel().getColumn(c).setPreferredWidth(w);
            }
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int r = table.rowAtPoint(e.getPoint());
                    if (e.getClickCount() == 1 && r >= 0 && r < shown.size() && openProduct != null) {
                        openProduct.accept(shown.get(r).productId);
                    }
                }
            });
            this.scroll = UiKit.tableScroll(table);
            scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        }

        void reset(List<InsightService.Item> items) {
            shown = items;
            model.setRowCount(0);
        }

        /** An empty list says so in words (one muted line that is not a product). */
        void emptyIf(int column, String message) {
            if (!shown.isEmpty()) return;
            Object[] row = new Object[model.getColumnCount()];
            row[column] = message;
            model.addRow(row);
        }
    }
}
