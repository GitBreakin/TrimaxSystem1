package com.trimaxsystem;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * One account: profile (no password), a role-specific summary, and the status actions an admin may
 * take. Changing the status asks for confirmation, is re-checked by {@link AccountService#setStatus},
 * and the window then reloads to show what the database really contains.
 */
public class AccountDetailDialog extends JDialog {

    private final Window owner;
    private final String username;
    private final Runnable onChanged;
    private final JPanel body = new JPanel(new BorderLayout());

    private AccountService.Detail detail;
    private String notice;
    private boolean busy;

    public AccountDetailDialog(Window owner, String username, Runnable onChanged) {
        super(owner, "Account: " + username, ModalityType.APPLICATION_MODAL);
        this.owner = owner;
        this.username = username;
        this.onChanged = onChanged;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(740, 700);
        setMinimumSize(new Dimension(580, 500));
        setLocationRelativeTo(owner);

        body.setBackground(Theme.BACKGROUND);
        body.setBorder(new EmptyBorder(16, 20, 16, 20));
        setContentPane(body);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        load();
    }

    // =====================================================
    // LOADING
    // =====================================================

    private void load() {
        showMessage("Loading account...", false);
        new SwingWorker<AccountService.Detail, Void>() {
            @Override
            protected AccountService.Detail doInBackground() throws Exception {
                return AccountService.loadDetail(username);
            }

            @Override
            protected void done() {
                try {
                    detail = get();
                    if (detail == null) showMessage("This account no longer exists.", true);
                    else render();
                } catch (Exception ex) {
                    showMessage("Could not load this account: " + rootMessage(ex), true);
                }
            }
        }.execute();
    }

    private void showMessage(String text, boolean withButtons) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.insets = new Insets(4, 4, 4, 4);
        JLabel l = new JLabel(text);
        l.setFont(Theme.SUBTLE);
        l.setForeground(withButtons ? Theme.DANGER : Theme.MUTED);
        p.add(l, g);
        if (withButtons) {
            JButton retry = UiKit.secondaryButton("Retry");
            retry.addActionListener(e -> load());
            JButton close = UiKit.secondaryButton("Close");
            close.addActionListener(e -> dispose());
            p.add(retry, g);
            p.add(close, g);
        }
        body.removeAll();
        body.add(p, BorderLayout.CENTER);
        body.revalidate();
        body.repaint();
    }

    // =====================================================
    // SHOWING THE ACCOUNT
    // =====================================================

    private void render() {
        AccountService.Detail d = detail;
        body.removeAll();

        JLabel title = new JLabel(d.name + "  (" + d.username + ")");
        title.setFont(Theme.TITLE);
        title.setForeground(Theme.TEXT);
        JLabel sub = new JLabel(d.role + "  |  Account status: " + d.status);
        sub.setFont(new Font("SansSerif", Font.BOLD, 14));
        sub.setForeground("Active".equals(d.status) ? Theme.OK : "Pending".equals(d.status) ? Theme.WARN : Theme.MUTED);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(title);
        top.add(Box.createVerticalStrut(2));
        top.add(sub);
        if (notice != null) {
            JLabel n = new JLabel(notice);
            n.setFont(Theme.SUBTLE);
            n.setForeground(Theme.OK);
            n.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(Box.createVerticalStrut(6));
            top.add(n);
        }
        top.setBorder(new EmptyBorder(0, 0, 12, 0));
        body.add(top, BorderLayout.NORTH);

        JPanel middle = new JPanel(new BorderLayout(0, 12));
        middle.setOpaque(false);
        middle.add(card(buildProfile(d)), BorderLayout.NORTH);
        JComponent extra = buildRoleSection(d);
        if (extra != null) middle.add(card(extra), BorderLayout.CENTER);
        body.add(middle, BorderLayout.CENTER);

        body.add(buildActions(d), BorderLayout.SOUTH);
        body.revalidate();
        body.repaint();
    }

    private JComponent buildProfile(AccountService.Detail d) {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        int r = 0;
        addRow(grid, r++, "Username", d.username);
        addRow(grid, r++, "Name", d.name);
        addRow(grid, r++, "Role", d.role);
        addRow(grid, r++, "Email", orDash(d.email));
        addRow(grid, r++, "Contact", orDash(d.contact));
        addRow(grid, r++, "Last activity", "Not tracked");
        if (d.lockedUntil != null) {
            addRow(grid, r++, "Sign-in", "Temporarily locked after failed attempts, until " + Theme.formatWhen(d.lockedUntil));
        }
        if (d.mobile != null) {
            addRow(grid, r++, "Mobile app", !d.mobile.installed
                    ? "Not set up on this database (run sql/20261023_01_mobile_booking_integration.sql)"
                    : d.mobile.linked
                    ? "Can sign in as " + d.mobile.email + "  (linked " + Theme.formatWhen(d.mobile.linkedAt)
                      + (d.mobile.linkedBy == null ? "" : "provisioning".equals(d.mobile.linkedBy)
                              ? " automatically" : " by " + d.mobile.linkedBy) + ")"
                    : "Cannot sign in to the mobile app (no linked sign-in)");
        }
        return grid;
    }

    private JComponent buildRoleSection(AccountService.Detail d) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        if (d.isDriver()) {
            p.add(heading("Vehicle"));
            if (d.vehicles.isEmpty()) {
                p.add(note("No vehicle is assigned to this driver."));
            }
            for (AccountService.VehicleInfo v : d.vehicles) {
                String loc = v.lastLocationAt == null ? "no location reported yet"
                        : "last location " + ago(v.lastLocationAt) + " (" + Theme.formatWhen(v.lastLocationAt) + ")";
                p.add(note(v.vehicleId + (v.type == null ? "" : " - " + v.type) + "  |  " + v.status + "  |  " + loc));
            }
            p.add(Box.createVerticalStrut(10));

            p.add(heading("Bookings assigned"));
            StringBuilder counts = new StringBuilder();
            for (java.util.Map.Entry<String, Long> e : d.bookingCounts.entrySet()) {
                if (counts.length() > 0) counts.append("   ");
                counts.append(e.getKey()).append(": ").append(e.getValue());
            }
            p.add(note(counts.toString()));
            p.add(Box.createVerticalStrut(8));

            if (d.recentBookings.isEmpty()) {
                p.add(note("No bookings assigned yet."));
            } else {
                DefaultTableModel m = new DefaultTableModel(null, new String[]{"Booking", "Date / Time", "Customer", "Status"}) {
                    @Override
                    public boolean isCellEditable(int r, int c) {
                        return false;
                    }
                };
                for (AccountService.BookingRow b : d.recentBookings) {
                    m.addRow(new Object[]{"#" + b.bookingId, Theme.formatWhen(b.date), orDash(b.client), b.status});
                }
                p.add(smallTable(m, "Most recent 10 bookings"));
            }
        } else if (d.isAgent()) {
            p.add(heading("Assigned clients (" + d.clients.size() + ")"));
            if (d.clients.isEmpty()) {
                p.add(note("No clients are assigned to this agent."));
            } else {
                DefaultTableModel m = new DefaultTableModel(null, new String[]{"Client", "Address"}) {
                    @Override
                    public boolean isCellEditable(int r, int c) {
                        return false;
                    }
                };
                for (AccountService.ClientInfo c : d.clients) m.addRow(new Object[]{c.name, orDash(c.address)});
                p.add(smallTable(m, null));
            }
        } else {
            return null;
        }
        return p;
    }

    private JComponent smallTable(DefaultTableModel m, String caption) {
        JTable t = new JTable(m);
        t.setRowHeight(26);
        t.setFont(Theme.SUBTLE);
        t.setShowVerticalLines(false);
        t.setGridColor(Theme.BORDER);
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sp.setPreferredSize(new Dimension(300, 170));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(sp, BorderLayout.CENTER);
        if (caption != null) {
            JLabel c = new JLabel(caption);
            c.setFont(new Font("SansSerif", Font.PLAIN, 11));
            c.setForeground(Theme.MUTED);
            wrap.add(c, BorderLayout.SOUTH);
        }
        return wrap;
    }

    private JComponent buildActions(AccountService.Detail d) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(12, 0, 0, 0));

        JLabel hint = new JLabel("Passwords are never shown here. Phone passwords are set with an emailed setup code.");
        hint.setFont(new Font("SansSerif", Font.PLAIN, 12));
        hint.setForeground(Theme.MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(hint);
        p.add(Box.createVerticalStrut(8));

        JPanel row = UiKit.flowRow();   // wraps onto a second line instead of hiding buttons

        AuthService.Session me = AuthService.current();
        boolean self = me != null && me.username.equals(d.username);
        if (self) {
            row.add(note("This is your own account. You cannot change its status."));
        } else if ("Pending".equals(d.status)) {
            JButton approve = UiKit.primaryButton("Approve (set Active)");
            approve.addActionListener(e -> confirmAndChange(AccountService.ACTIVE));
            JButton reject = UiKit.dangerButton("Reject (set Inactive)");
            reject.addActionListener(e -> confirmAndChange(AccountService.INACTIVE));
            row.add(approve);
            row.add(reject);
        } else if ("Active".equals(d.status)) {
            JButton off = UiKit.dangerButton("Deactivate account");
            off.addActionListener(e -> confirmAndChange(AccountService.INACTIVE));
            row.add(off);
        } else {
            JButton on = UiKit.primaryButton("Reactivate account");
            on.addActionListener(e -> confirmAndChange(AccountService.ACTIVE));
            row.add(on);
        }
        if (!self && d.mobile != null && d.mobile.installed) {
            boolean active = "Active".equals(d.status);
            if (d.mobile.linked) {
                if (active) {
                    JButton code = UiKit.secondaryButton("Send new setup code");
                    code.setToolTipText("Emails a one-time code for choosing the phone password (24 hours)");
                    code.addActionListener(e -> confirmAndSendSetupCode());
                    row.add(code);
                }
                JButton unlink = UiKit.dangerButton("Remove mobile sign-in");
                unlink.addActionListener(e -> confirmAndUnlinkMobile());
                row.add(unlink);
            } else {
                if (active) {
                    JButton create = UiKit.primaryButton("Create mobile sign-in");
                    create.setToolTipText("Creates the Supabase sign-in for this account and links it (no Dashboard needed)");
                    create.addActionListener(e -> confirmAndCreateMobile());
                    row.add(create);
                }
                JButton link = UiKit.secondaryButton("Link mobile sign-in");
                link.setToolTipText("For a sign-in that already exists in Supabase");
                link.addActionListener(e -> confirmAndLinkMobile());
                row.add(link);
            }
        }
        JButton close = UiKit.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        row.add(close);
        p.add(row);
        return p;
    }

    // =====================================================
    // MOBILE APP SIGN-IN
    // =====================================================

    private void confirmAndLinkMobile() {
        if (busy) return;
        final AccountService.Detail d = detail;
        if (d.email == null || d.email.isBlank()) {
            JOptionPane.showMessageDialog(this, "This account has no email address. The mobile sign-in is matched by email,\n"
                    + "so add one to the account first.", "Link mobile sign-in", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Let " + d.name + " (" + d.username + ") sign in to the TRIMAX mobile app as " + d.email + "?\n\n"
                        + "Use this only for a Supabase sign-in that already exists (made by hand earlier); it must\n"
                        + "have exactly this email and be confirmed. Otherwise use \"Create mobile sign-in\".\n\n"
                        + "The phone always uses the role and Active status of this TRIMAX account.",
                "Link mobile sign-in", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        runMobileChange(true);
    }

    private void confirmAndCreateMobile() {
        if (busy) return;
        final AccountService.Detail d = detail;
        if (d.email == null || d.email.isBlank()) {
            JOptionPane.showMessageDialog(this, "This account has no email address. The mobile sign-in uses the email,\n"
                    + "so add one to the account first.", "Create mobile sign-in", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Create the TRIMAX mobile app sign-in for " + d.name + " (" + d.username + ") with " + d.email + "?\n\n"
                        + "TRIMAX creates it in Supabase and links it to this account. The person then chooses the\n"
                        + "phone password with the emailed setup code (use \"Send new setup code\" if it expired).\n\n"
                        + "The phone always uses the role and Active status of this TRIMAX account.",
                "Create mobile sign-in", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        busy = true;
        showMessage("Creating mobile sign-in...", false);
        new SwingWorker<MobileProvisioning.Result, Void>() {
            @Override
            protected MobileProvisioning.Result doInBackground() {
                return MobileProvisioning.create(d.username);
            }

            @Override
            protected void done() {
                busy = false;
                MobileProvisioning.Result r;
                try {
                    r = get();
                } catch (Exception ex) {
                    r = null;
                }
                if (AuthService.current() == null) {   // admin access was withdrawn meanwhile
                    dispose();
                    AuthFrame.logout(owner);
                    return;
                }
                if (r != null && r.ready()) {
                    notice = r.describe();
                    if (onChanged != null) onChanged.run();
                } else {
                    notice = null;
                    JOptionPane.showMessageDialog(AccountDetailDialog.this,
                            "The mobile sign-in was not created.\n" + (r == null ? "Unexpected error." : r.describe()),
                            "Create mobile sign-in", JOptionPane.WARNING_MESSAGE);
                }
                load();
            }
        }.execute();
    }

    private void confirmAndSendSetupCode() {
        if (busy) return;
        final AccountService.Detail d = detail;
        int choice = JOptionPane.showConfirmDialog(this,
                "Email a new one-time setup code to " + d.email + "?\n\n"
                        + "Older codes stop working. " + d.name + " enters it in the TRIMAX app\n"
                        + "(Set up or reset password) to choose a new phone password. It works for 24 hours.",
                "Send new setup code", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        busy = true;
        showMessage("Sending the setup code...", false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return AuthService.sendNewSetupCode(d.username);
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    notice = "New setup code emailed to " + get() + ".";
                } catch (Exception ex) {
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    notice = null;
                    if (AuthService.current() == null) {
                        dispose();
                        AuthFrame.logout(owner);
                        return;
                    }
                    JOptionPane.showMessageDialog(AccountDetailDialog.this, "No code was sent.\n" + cause.getMessage(),
                            "Send new setup code", JOptionPane.ERROR_MESSAGE);
                }
                load();
            }
        }.execute();
    }

    private void confirmAndUnlinkMobile() {
        if (busy) return;
        final AccountService.Detail d = detail;
        Object[] options = {"Yes, remove", "Keep"};
        int choice = JOptionPane.showOptionDialog(this,
                "Remove the mobile app sign-in of " + d.name + " (" + d.username + ")?\n"
                        + "They will be refused the next time the app contacts the server. Bookings are not changed.",
                "Remove mobile sign-in", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice != 0) return;
        runMobileChange(false);
    }

    private void runMobileChange(boolean link) {
        final AccountService.Detail d = detail;
        busy = true;
        showMessage(link ? "Linking mobile sign-in..." : "Removing mobile sign-in...", false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (link) MobileIntegration.link(d.username, d.email);
                else MobileIntegration.unlink(d.username);
                return null;
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    get();
                    notice = link ? "Mobile sign-in linked to " + d.email + "." : "Mobile sign-in removed.";
                    if (onChanged != null) onChanged.run();
                } catch (Exception ex) {
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    notice = null;
                    if (cause instanceof AuthService.AuthException) {
                        JOptionPane.showMessageDialog(AccountDetailDialog.this, cause.getMessage(),
                                "Not allowed", JOptionPane.ERROR_MESSAGE);
                        dispose();
                        AuthFrame.logout(owner);
                        return;
                    }
                    String rule = MobileIntegration.friendlyMessage(cause);
                    JOptionPane.showMessageDialog(AccountDetailDialog.this,
                            rule != null ? rule : "Nothing was changed: " + cause.getMessage(),
                            link ? "Link mobile sign-in" : "Remove mobile sign-in", JOptionPane.ERROR_MESSAGE);
                }
                load();
            }
        }.execute();
    }

    // =====================================================
    // CHANGING THE STATUS
    // =====================================================

    private void confirmAndChange(String target) {
        if (busy) return;   // ignore double clicks while a change is running
        final AccountService.Detail d = detail;
        final boolean off = AccountService.INACTIVE.equals(target);

        StringBuilder msg = new StringBuilder();
        if (off) {
            msg.append("Set ").append(d.name).append(" (").append(d.username).append(") to Inactive?\n\n");
            msg.append("They will no longer be treated as an active ").append(d.role.toLowerCase()).append(".");
            if (d.isDriver() && d.openBookings > 0) {
                msg.append("\n\nWarning: this driver still has ").append(d.openBookings)
                        .append(" open booking(s) (Pending, Confirmed or In Progress). They are NOT reassigned.");
            }
        } else {
            msg.append("Set ").append(d.name).append(" (").append(d.username).append(") to Active?");
        }
        Object[] options = off ? new Object[]{"Yes, set Inactive", "Keep as is"} : new Object[]{"Yes, set Active", "No"};
        int choice = JOptionPane.showOptionDialog(this, msg.toString(), "Change account status",
                JOptionPane.DEFAULT_OPTION, off ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE,
                null, options, options[off ? 1 : 0]);
        if (choice != 0) return;

        busy = true;
        showMessage("Updating account...", false);
        final String from = d.status;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                AccountService.setStatus(d.username, from, target);
                return null;
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    get();
                    notice = "Account status changed from " + from + " to " + target + ".";
                    if (onChanged != null) onChanged.run();
                } catch (Exception ex) {
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    notice = null;
                    if (cause instanceof AuthService.AuthException) {
                        JOptionPane.showMessageDialog(AccountDetailDialog.this, cause.getMessage(),
                                "Not allowed", JOptionPane.ERROR_MESSAGE);
                        dispose();
                        AuthFrame.logout(owner);   // the session is no longer a valid admin session
                        return;
                    }
                    boolean friendly = cause instanceof AccountService.ConflictException
                            || cause instanceof IllegalStateException;
                    JOptionPane.showMessageDialog(AccountDetailDialog.this,
                            friendly ? cause.getMessage() : "The status was NOT changed: " + cause.getMessage(),
                            "Status not changed", JOptionPane.ERROR_MESSAGE);
                    if (onChanged != null) onChanged.run();
                }
                load();   // always show what the database really contains now
            }
        }.execute();
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private JPanel card(JComponent inner) {
        JPanel c = new JPanel(new BorderLayout());
        c.setBackground(Theme.CARD);
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(12, 14, 12, 14)));
        c.add(inner, BorderLayout.CENTER);
        return c;
    }

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, 15));
        l.setForeground(Theme.TEXT);
        l.setBorder(new EmptyBorder(0, 0, 6, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel note(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.SUBTLE);
        l.setForeground(Theme.TEXT);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static void addRow(JPanel grid, int row, String label, String value) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = row;
        g.anchor = GridBagConstraints.NORTHWEST;
        g.insets = new Insets(3, 0, 3, 16);
        JLabel k = new JLabel(label);
        k.setFont(new Font("SansSerif", Font.BOLD, 13));
        k.setForeground(Theme.MUTED);
        g.gridx = 0;
        grid.add(k, g);
        JLabel v = new JLabel(value);
        v.setFont(Theme.SUBTLE);
        v.setForeground(Theme.TEXT);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(3, 0, 3, 0);
        grid.add(v, g);
    }

    private static String orDash(String s) {
        return s == null || s.trim().isEmpty() ? "-" : s;
    }

    /** "5 min ago" style text for a past timestamp. */
    private static String ago(java.sql.Timestamp ts) {
        long s = Math.max(0, Duration.between(ts.toInstant(), Instant.now()).getSeconds());
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " day(s) ago";
    }

    private static String rootMessage(Exception ex) {
        Throwable t = ex.getCause() != null ? ex.getCause() : ex;
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
