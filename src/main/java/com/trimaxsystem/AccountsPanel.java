package com.trimaxsystem;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

/**
 * Agents &amp; Drivers: one list of accounts with search, role and status filters, server-side sorting
 * and paging. Opening an account shows its details and the actions an admin may take
 * (see {@link AccountDetailDialog}). Passwords are never shown.
 */
public class AccountsPanel extends JPanel {

    private static final String[] COLS = {"Username", "Name", "Role", "Account status", "Contact", "Summary"};
    private static final int COL_STATUS = 3;

    private final JTextField txtSearch = new JTextField(22);
    private final JComboBox<String> cmbView = new JComboBox<>(new String[]{
            AccountService.VIEW_AGENTS_DRIVERS, AccountService.VIEW_AGENTS,
            AccountService.VIEW_DRIVERS, AccountService.VIEW_ADMINS});
    private final JComboBox<String> cmbStatus = new JComboBox<>(new String[]{"All statuses", "Active", "Inactive", "Pending"});
    private final JLabel lblActive = new JLabel(" ");

    private final DefaultTableModel model = new DefaultTableModel(null, COLS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final JLabel lblMessage = new JLabel(" ");
    private final JButton btnRetry = UiKit.secondaryButton("Retry");

    private final JLabel lblShowing = new JLabel(" ");
    private final JLabel lblPage = new JLabel(" ");
    private final JButton btnPrev = UiKit.secondaryButton("Previous");
    private final JButton btnNext = UiKit.secondaryButton("Next");
    private final JButton btnView = UiKit.primaryButton("View Details");

    private AccountService.Query applied = new AccountService.Query();   // last applied filters, sort and page
    private int requestId = 0;
    private long totalRows = 0;
    /** True while Clear all resets the controls, so their change listeners do not each start a reload. */
    private boolean resetting = false;
    /** Typing in the search box filters after a short pause, not on every key. */
    private final Timer searchDelay = new Timer(300, e -> applyFilters());

    public AccountsPanel() {
        setBackground(Theme.BACKGROUND);
        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(14, 20, 16, 20));
        add(buildFilterCard(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        load();
    }

    // =====================================================
    // BUILDING THE UI
    // =====================================================

    private JComponent buildFilterCard() {
        txtSearch.setToolTipText("Username, name, email or contact number");
        UiKit.searchField(txtSearch, "Username, name, email or contact");
        cmbView.setFont(Theme.SUBTLE);
        cmbStatus.setFont(Theme.SUBTLE);
        cmbView.setToolTipText("Which accounts to list");
        cmbStatus.setToolTipText("Only accounts with this status");

        // Filters apply as soon as they change: picking a role or status reloads the list at once, and the
        // search runs after a short pause in typing (or on Enter). There is no separate Apply button, so
        // what the controls show is always what the list shows.
        searchDelay.setRepeats(false);
        txtSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { searchChanged(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { searchChanged(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { searchChanged(); }
        });
        txtSearch.addActionListener(e -> {
            searchDelay.stop();
            applyFilters();
        });
        cmbView.addActionListener(e -> {
            if (!resetting) applyFilters();
        });
        cmbStatus.addActionListener(e -> {
            if (!resetting) applyFilters();
        });

        JButton btnClear = UiKit.secondaryButton("Clear all");
        JButton btnRefresh = UiKit.secondaryButton("Refresh");
        JButton btnCreateEmployee = UiKit.primaryButton("Create Employee Account");
        btnClear.addActionListener(e -> clearFilters());
        btnRefresh.addActionListener(e -> load());
        btnCreateEmployee.addActionListener(e -> new CreateEmployeeAccountDialog(
                SwingUtilities.getWindowAncestor(this), this::load).setVisible(true));

        JPanel row1 = UiKit.flowRow();
        row1.add(UiKit.caption("Search"));
        row1.add(txtSearch);
        row1.add(UiKit.caption("Show"));
        row1.add(cmbView);
        row1.add(UiKit.caption("Status"));
        row1.add(cmbStatus);
        row1.add(btnClear);
        row1.add(btnRefresh);

        lblActive.setFont(Theme.SUBTLE);
        lblActive.setForeground(Theme.BLUE_DARK);
        // On its own row: at the end of row 1 it wrapped out of sight on common screen widths
        // (a FlowLayout row in a BoxLayout only gets the height of one line).
        JPanel row2 = UiKit.flowRow();
        row2.add(btnCreateEmployee);
        row2.add(lblActive);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(8, 12, 4, 12)));
        card.add(row1);
        card.add(row2);
        return card;
    }

    private JComponent buildCenter() {
        styleTable();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.CARD);

        lblMessage.setFont(Theme.SUBTLE);
        btnRetry.addActionListener(e -> load());
        JPanel msg = new JPanel(new GridBagLayout());
        msg.setBackground(Theme.CARD);
        msg.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.insets = new Insets(4, 4, 4, 4);
        msg.add(lblMessage, g);
        msg.add(btnRetry, g);

        center.add(scroll, "TABLE");
        center.add(msg, "MESSAGE");
        return center;
    }

    private JComponent buildFooter() {
        lblShowing.setFont(Theme.SUBTLE);
        lblShowing.setForeground(Theme.MUTED);
        lblPage.setFont(Theme.SUBTLE);
        lblPage.setForeground(Theme.MUTED);

        btnPrev.addActionListener(e -> {
            if (applied.page > 0) {
                applied.page--;
                load();
            }
        });
        btnNext.addActionListener(e -> {
            if ((long) (applied.page + 1) * AccountService.PAGE_SIZE < totalRows) {
                applied.page++;
                load();
            }
        });
        btnPrev.setEnabled(false);
        btnNext.setEnabled(false);
        btnView.setEnabled(false);
        btnView.addActionListener(e -> openDetails());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(lblShowing);
        left.add(btnPrev);
        left.add(lblPage);
        left.add(btnNext);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(btnView);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private void styleTable() {
        table.setRowHeight(32);
        table.setFont(Theme.SUBTLE);
        table.setForeground(Theme.TEXT);
        table.setBackground(Theme.CARD);
        table.setSelectionBackground(Color.decode("#DBEAFE"));
        table.setSelectionForeground(Theme.TEXT);
        table.setShowVerticalLines(false);
        table.setGridColor(Theme.BORDER);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, sel, false, row, col);
                setFont(Theme.SUBTLE);
                setForeground(Theme.TEXT);
                if (col == COL_STATUS) {
                    // Always written out; the colour is only a second cue.
                    String s = value == null ? "" : value.toString();
                    setForeground("Active".equals(s) ? Theme.OK : "Pending".equals(s) ? Theme.WARN : Theme.MUTED);
                    setFont(Theme.NAV_ON);
                }
                setBorder(new EmptyBorder(0, 10, 0, 10));
                return this;
            }
        });

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(100, 34));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setToolTipText("Click username, name, role or status to sort");
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, false, false, row, col);
                String arrow = col == applied.sortColumn ? (applied.ascending ? "  ▲" : "  ▼") : "";
                setText(COLS[col] + arrow);
                setHorizontalAlignment(SwingConstants.LEFT);
                setBackground(Theme.BACKGROUND);
                setForeground(Theme.MUTED);
                setFont(new Font("SansSerif", Font.BOLD, 12));
                setOpaque(true);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(0, 10, 0, 10)));
                return this;
            }
        });
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int col = header.columnAtPoint(e.getPoint());
                if (col < 0 || col > 3) return;   // contact and summary are not sortable
                if (col == applied.sortColumn) applied.ascending = !applied.ascending;
                else {
                    applied.sortColumn = col;
                    applied.ascending = true;
                }
                applied.page = 0;
                load();
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> btnView.setEnabled(table.getSelectedRow() >= 0));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) openDetails();
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "details");
        table.getActionMap().put("details", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (table.getSelectedRow() >= 0) openDetails();
            }
        });
    }

    // =====================================================
    // FILTERS AND LOADING
    // =====================================================

    private void searchChanged() {
        if (!resetting) searchDelay.restart();
    }

    private void applyFilters() {
        String view = (String) cmbView.getSelectedItem();
        String search = txtSearch.getText().trim();
        String status = cmbStatus.getSelectedIndex() > 0 ? (String) cmbStatus.getSelectedItem() : null;
        // Typing a space or re-picking the same item changes nothing: keep the page and skip the reload.
        if (view.equals(applied.view) && search.equals(applied.search) && java.util.Objects.equals(status, applied.status)) {
            return;
        }
        applied.view = view;
        applied.search = search;
        applied.status = status;
        applied.page = 0;
        load();
    }

    private void clearFilters() {
        searchDelay.stop();
        resetting = true;
        try {
            txtSearch.setText("");
            cmbView.setSelectedIndex(0);
            cmbStatus.setSelectedIndex(0);
        } finally {
            resetting = false;
        }
        applied.view = AccountService.VIEW_AGENTS_DRIVERS;
        applied.search = "";
        applied.status = null;
        applied.page = 0;
        load();
    }

    /** Reloads the current page with the applied filters and sort. */
    public void load() {
        final int req = ++requestId;
        final AccountService.Query q = copyOf(applied);
        showMessage("Loading accounts...", false);
        updateActiveLabel(q);

        new SwingWorker<AccountService.Page, Void>() {
            @Override
            protected AccountService.Page doInBackground() throws Exception {
                return AccountService.list(q);
            }

            @Override
            protected void done() {
                if (req != requestId) return;   // a newer request has been started
                try {
                    AccountService.Page r = get();
                    totalRows = r.total;
                    if (r.rows.isEmpty() && r.total > 0 && q.page > 0) {   // the page no longer exists
                        applied.page = (int) ((r.total - 1) / AccountService.PAGE_SIZE);
                        load();
                        return;
                    }
                    model.setRowCount(0);
                    for (Object[] row : r.rows) model.addRow(row);
                    table.getTableHeader().repaint();
                    if (r.rows.isEmpty()) {
                        showMessage(q.hasFilters() ? "No accounts match these filters." : "There are no accounts yet.", false);
                    } else {
                        centerCards.show(center, "TABLE");
                    }
                    updateFooter(q.page, r.rows.size());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    totalRows = 0;
                    model.setRowCount(0);
                    showMessage("Could not load accounts: " + cause.getMessage(), true);
                    updateFooter(0, 0);
                    ex.printStackTrace();
                }
            }
        }.execute();
    }

    private static AccountService.Query copyOf(AccountService.Query a) {
        AccountService.Query q = new AccountService.Query();
        q.view = a.view;
        q.search = a.search;
        q.status = a.status;
        q.sortColumn = a.sortColumn;
        q.ascending = a.ascending;
        q.page = a.page;
        return q;
    }

    private void updateActiveLabel(AccountService.Query q) {
        if (!q.hasFilters()) {
            lblActive.setText(" ");
            return;
        }
        List<String> parts = new ArrayList<>();
        if (!AccountService.VIEW_AGENTS_DRIVERS.equals(q.view)) parts.add("showing " + q.view);
        if (!q.search.isEmpty()) parts.add("search \"" + q.search + "\"");
        if (q.status != null) parts.add("status " + q.status);
        lblActive.setText("Active filters: " + String.join(", ", parts));
    }

    private void showMessage(String text, boolean error) {
        lblMessage.setText(text);
        lblMessage.setForeground(error ? Theme.DANGER : Theme.MUTED);
        btnRetry.setVisible(error);
        centerCards.show(center, "MESSAGE");
    }

    private void updateFooter(int page, int shown) {
        if (totalRows == 0 || shown == 0) {
            lblShowing.setText(totalRows == 0 ? "0 accounts" : " ");
            lblPage.setText(" ");
        } else {
            long first = (long) page * AccountService.PAGE_SIZE + 1;
            long pages = (totalRows + AccountService.PAGE_SIZE - 1) / AccountService.PAGE_SIZE;
            lblShowing.setText("Showing " + first + "-" + (first + shown - 1) + " of " + totalRows);
            lblPage.setText("Page " + (page + 1) + " of " + pages);
        }
        btnPrev.setEnabled(page > 0);
        btnNext.setEnabled((long) (page + 1) * AccountService.PAGE_SIZE < totalRows);
        btnView.setEnabled(table.getSelectedRow() >= 0);
    }

    private void openDetails() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        String username = (String) model.getValueAt(row, 0);
        new AccountDetailDialog(SwingUtilities.getWindowAncestor(this), username, this::load).setVisible(true);
    }
}
