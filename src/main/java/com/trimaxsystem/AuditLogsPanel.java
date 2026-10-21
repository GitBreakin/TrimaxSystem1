package com.trimaxsystem;

import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Read-only view of the audit log. There is deliberately no edit or delete control here, and the table
 * itself rejects updates and deletes (sql/002_audit_log.sql). Filtering and paging happen in the database.
 */
public class AuditLogsPanel extends JPanel {

    private static final int PAGE_SIZE = 50;

    private final JTextField search = new JTextField(16);
    private final JComboBox<String> result = new JComboBox<>(new String[]{"Any result", "success", "failure"});
    private final JTextField fromField = new JTextField(8);
    private final JTextField toField = new JTextField(8);
    private final JButton apply = UiKit.primaryButton("Search");
    private final JButton clear = UiKit.secondaryButton("Clear filters");
    private final JButton prev = UiKit.secondaryButton("Previous");
    private final JButton next = UiKit.secondaryButton("Next");
    private final JLabel pageInfo = new JLabel(" ");
    private final JLabel formError = new JLabel(" ");

    private final StatePanel state = new StatePanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"When (Asia/Manila)", "Actor", "Action", "Result", "Details"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };

    private int offset;
    private int seq;
    private boolean loadedOnce;
    // the filters of the last search, so Previous/Next and Retry keep them even if the boxes were edited
    private String qText, qResult;
    private LocalDate qFrom, qTo;

    public AuditLogsPanel() {
        super(new BorderLayout(0, 10));
        setBackground(Theme.BACKGROUND);
        setBorder(new EmptyBorder(12, 16, 12, 16));

        formError.setForeground(Theme.DANGER);
        search.setToolTipText("Matches actor, action, target number and details");
        fromField.setToolTipText("YYYY-MM-DD");
        toField.setToolTipText("YYYY-MM-DD");
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.setOpaque(false);
        filters.add(new JLabel("Search"));
        filters.add(search);
        filters.add(result);
        filters.add(new JLabel("From"));
        filters.add(fromField);
        filters.add(new JLabel("To"));
        filters.add(toField);
        filters.add(apply);
        filters.add(clear);
        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        formError.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(filters);
        north.add(formError);
        add(north, BorderLayout.NORTH);

        apply.addActionListener(e -> applyFilters());
        search.addActionListener(e -> applyFilters());
        fromField.addActionListener(e -> applyFilters());
        toField.addActionListener(e -> applyFilters());
        clear.addActionListener(e -> {
            search.setText("");
            result.setSelectedIndex(0);
            fromField.setText("");
            toField.setText("");
            applyFilters();
        });
        prev.addActionListener(e -> load(Math.max(0, offset - PAGE_SIZE)));
        next.addActionListener(e -> load(offset + PAGE_SIZE));

        JTable table = new JTable(model);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(4).setPreferredWidth(380);
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setOpaque(false);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel pager = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pager.setOpaque(false);
        pager.add(prev);
        pager.add(next);
        pager.add(pageInfo);
        content.add(pager, BorderLayout.SOUTH);

        center.setOpaque(false);
        center.add(state, "state");
        center.add(content, "content");
        add(center, BorderLayout.CENTER);

        addHierarchyListener(e -> {
            if (!loadedOnce && isShowing()) {
                loadedOnce = true;
                applyFilters();
            }
        });
    }

    private void applyFilters() {
        LocalDate from = null, to = null;
        try {
            if (!fromField.getText().isBlank()) from = LocalDate.parse(fromField.getText().trim());
            if (!toField.getText().isBlank()) to = LocalDate.parse(toField.getText().trim());
        } catch (DateTimeParseException ex) {
            formError.setText("Dates must look like 2026-10-31.");
            return;
        }
        if (from != null && to != null && to.isBefore(from)) {
            formError.setText("The end date is before the start date.");
            return;
        }
        formError.setText(" ");
        qText = search.getText();
        qResult = result.getSelectedIndex() == 0 ? null : (String) result.getSelectedItem();
        qFrom = from;
        qTo = to;
        load(0);
    }

    private void load(int newOffset) {
        final int mySeq = ++seq;   // a slower, older answer must not overwrite a newer one
        apply.setEnabled(false);
        prev.setEnabled(false);
        next.setEnabled(false);
        state.showLoading("Loading audit log...");
        cards.show(center, "state");
        new SwingWorker<AuditService.Page, Void>() {
            @Override
            protected AuditService.Page doInBackground() throws Exception {
                return AuditService.search(qText, qResult, qFrom, qTo, newOffset, PAGE_SIZE);
            }

            @Override
            protected void done() {
                if (mySeq != seq) return;
                apply.setEnabled(true);
                try {
                    show(get(), newOffset);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    state.showError("Could not load the audit log",
                            c.getMessage() == null ? c.toString() : c.getMessage(), () -> load(newOffset));
                    cards.show(center, "state");
                }
            }
        }.execute();
    }

    private void show(AuditService.Page page, int newOffset) {
        if (page.tableMissing) {
            state.showEmpty("The audit log is not set up yet",
                    "The database has no audit_log table (see sql/002_audit_log.sql). "
                            + "Until it exists, admin changes are not being recorded.");
            cards.show(center, "state");
            return;
        }
        if (page.total == 0) {
            state.showEmpty("No audit entries match", "Change or clear the filters to see more.");
            cards.show(center, "state");
            return;
        }
        offset = newOffset;
        model.setRowCount(0);
        for (AuditService.Entry e : page.rows) {
            model.addRow(new Object[]{Theme.formatWhen(e.when), e.actor, e.action, e.result,
                    e.details == null ? "" : e.details});
        }
        int last = Math.min(page.total, offset + page.rows.size());
        pageInfo.setText("Showing " + (offset + 1) + " to " + last + " of " + page.total);
        prev.setEnabled(offset > 0);
        next.setEnabled(last < page.total);
        cards.show(center, "content");
    }
}
