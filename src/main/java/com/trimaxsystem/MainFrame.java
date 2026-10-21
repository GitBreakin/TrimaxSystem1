package com.trimaxsystem;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * Main admin window: navy sidebar on the left, a header with the page title and the signed-in
 * admin on top, and the screen itself in the middle. Only screens that really exist are listed.
 */
public class MainFrame extends JFrame {

    /** One sidebar entry: text on the button, the page title shown in the header, and the card key. */
    private static final String[][] PAGES = {
            // { sidebar label, header title, header subtitle }
            {"Dashboard",           "Dashboard",           "Overview of today's operations"},
            {"Inventory",           "Inventory",           "Products, suppliers, prices and stock"},
            {"Bookings",            "Bookings",            "All bookings and their status"},
            {"Live GPS Monitoring", "Live GPS Monitoring", "Last known vehicle and agent positions (current / stale)"},
            {"Manage Vehicles",     "Manage Vehicles",     "Vehicle records, status, and driver assignments"},
            {"Agents & Drivers",    "Agents & Drivers",    "Accounts, roles and account status"},
            {"Customers",           "Customers",           "Customer records, assigned agents and locations"},
            {"Analytics & Reports", "Analytics & Reports", "Booking volume, completion and cancellation, with CSV, Excel and PDF export"},
            {"Notifications",       "Notifications",       "Operational alerts and announcements"},
            {"Audit Logs",          "Audit Logs",          "Who changed what, and when (read-only)"},
            {"System Settings",     "System Settings",     "Your profile, connection health and environment"},
    };

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel mainContentPanel = new JPanel(cardLayout);
    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();
    private final JLabel breadcrumbLabel = new JLabel();
    private final JButton bell = UiKit.secondaryButton("Notifications");
    private NotificationsPanel notificationsPanel;
    private final NavButton[] navButtons = new NavButton[PAGES.length];

    /** Opens the main window straight away. */
    public MainFrame() {
        this(null);
        setVisible(true);
    }

    /**
     * Builds the main window WITHOUT showing it. onReady runs once the dashboard has finished its first
     * load (successfully or not); the caller then shows the window. Used by the login screen.
     */
    public MainFrame(Runnable onReady) {
        setTitle("TRI-MAX Goods Trading Management System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setMinimumSize(new Dimension(1000, 640));
        setLayout(new BorderLayout());
        getContentPane().setBackground(Theme.BACKGROUND);

        // --- SCREENS (CENTER) ---
        mainContentPanel.add(new DashboardPanel(onReady), PAGES[0][0]);
        mainContentPanel.add(new InventoryPanel(), PAGES[1][0]);
        mainContentPanel.add(new BookingsPanel(), PAGES[2][0]);
        mainContentPanel.add(buildLiveGpsPage(), PAGES[3][0]);
        mainContentPanel.add(new ManageVehiclesPanel(), PAGES[4][0]);
        mainContentPanel.add(new AccountsPanel(), PAGES[5][0]);
        mainContentPanel.add(new ClientsPanel(), PAGES[6][0]);
        mainContentPanel.add(new AnalyticsPanel(), PAGES[7][0]);
        notificationsPanel = new NotificationsPanel(this::refreshBell);
        mainContentPanel.add(notificationsPanel, PAGES[8][0]);
        mainContentPanel.add(new AuditLogsPanel(), PAGES[9][0]);
        mainContentPanel.add(new SettingsPanel(), PAGES[10][0]);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Theme.BACKGROUND);
        right.add(buildHeader(), BorderLayout.NORTH);
        right.add(mainContentPanel, BorderLayout.CENTER);

        add(buildSidebar(), BorderLayout.WEST);
        add(right, BorderLayout.CENTER);

        showPage(0);
    }

    /**
     * Live GPS Monitoring page: read-only maps for the last reported vehicle and agent positions.
     */
    private JComponent buildLiveGpsPage() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Vehicle map", new LiveGpsPanel());
        tabs.addTab("Agent locations", new AgentLocationsPanel());
        return tabs;
    }

    // =====================================================
    // SIDEBAR
    // =====================================================

    private JComponent buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(Theme.NAVY);
        sidebar.setPreferredSize(new Dimension(230, 0));

        JLabel brand = new JLabel("TRI-MAX");
        brand.setFont(Theme.BRAND);
        brand.setForeground(Color.WHITE);
        JLabel tagline = new JLabel("Booking & Delivery System");
        tagline.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tagline.setForeground(Theme.ON_NAVY_DIM);

        JPanel brandBox = new JPanel();
        brandBox.setOpaque(false);
        brandBox.setLayout(new BoxLayout(brandBox, BoxLayout.Y_AXIS));
        brandBox.setBorder(new EmptyBorder(22, 22, 18, 16));
        brandBox.add(brand);
        brandBox.add(tagline);
        sidebar.add(brandBox, BorderLayout.NORTH);

        JPanel nav = new JPanel();
        nav.setOpaque(false);
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBorder(new EmptyBorder(4, 12, 4, 12));
        for (int i = 0; i < PAGES.length; i++) {
            final int index = i;
            NavButton btn = new NavButton(PAGES[i][0]);
            btn.addActionListener(e -> showPage(index));
            navButtons[i] = btn;
            nav.add(btn);
            nav.add(Box.createVerticalStrut(4));
        }
        sidebar.add(nav, BorderLayout.CENTER);

        // Signed-in admin + sign out, pinned to the bottom
        AuthService.Session session = AuthService.current();
        JLabel who = new JLabel(session == null ? "Not signed in" : session.fullName);
        who.setFont(Theme.NAV_ON);
        who.setForeground(Color.WHITE);
        JLabel role = new JLabel(session == null ? "" : session.role);
        role.setFont(new Font("SansSerif", Font.PLAIN, 12));
        role.setForeground(Theme.ON_NAVY_DIM);

        NavButton signOut = new NavButton("Sign out");
        signOut.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(this, "Sign out of TRI-MAX?",
                    "Sign out", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                AuthFrame.logout(this);
            }
        });

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(new EmptyBorder(10, 12, 16, 12));
        JPanel whoBox = new JPanel();
        whoBox.setOpaque(false);
        whoBox.setLayout(new BoxLayout(whoBox, BoxLayout.Y_AXIS));
        whoBox.setBorder(new EmptyBorder(0, 10, 10, 0));
        whoBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        whoBox.add(who);
        whoBox.add(role);
        footer.add(whoBox);
        footer.add(signOut);
        sidebar.add(footer, BorderLayout.SOUTH);

        return sidebar;
    }

    // =====================================================
    // HEADER
    // =====================================================

    private JComponent buildHeader() {
        titleLabel.setFont(Theme.TITLE);
        titleLabel.setForeground(Theme.TEXT);
        subtitleLabel.setFont(Theme.SUBTLE);
        subtitleLabel.setForeground(Theme.MUTED);

        breadcrumbLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        breadcrumbLabel.setForeground(Theme.MUTED);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(breadcrumbLabel);
        text.add(Box.createVerticalStrut(2));
        text.add(titleLabel);
        text.add(Box.createVerticalStrut(2));
        text.add(subtitleLabel);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.CARD);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                new EmptyBorder(14, 24, 14, 24)));
        header.add(text, BorderLayout.WEST);
        header.add(buildHeaderActions(), BorderLayout.EAST);
        return header;
    }

    private static final int NOTIFICATIONS_PAGE = 8;

    /**
     * Updates the bell's unread count. If the count cannot be known (table missing, database error) the
     * bell shows no number at all, so "unknown" is never mistaken for "nothing new".
     */
    private void refreshBell() {
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                return NotificationService.unreadCount();
            }

            @Override
            protected void done() {
                try {
                    int n = get();
                    bell.setText(n > 0 ? "Notifications (" + n + " unread)" : "Notifications");
                } catch (Exception ex) {
                    bell.setText("Notifications");
                }
            }
        }.execute();
    }

    /** Notification bell and admin menu. */
    private JComponent buildHeaderActions() {
        bell.setToolTipText("Open notifications");
        bell.addActionListener(e -> showPage(NOTIFICATIONS_PAGE));
        refreshBell();

        AuthService.Session session = AuthService.current();
        JButton menuButton = UiKit.secondaryButton(session == null ? "Admin" : session.fullName + " \u25BE");
        JPopupMenu menu = new JPopupMenu();
        JMenuItem who = new JMenuItem(session == null ? "Not signed in"
                : session.username + " (" + session.role + ")");
        who.setEnabled(false);
        JMenuItem change = new JMenuItem("Change password");
        change.addActionListener(e -> ChangePasswordDialog.open(this));
        JMenuItem out = new JMenuItem("Sign out");
        out.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Sign out of TRI-MAX?", "Sign out",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                AuthFrame.logout(this);
            }
        });
        menu.add(who);
        menu.addSeparator();
        menu.add(change);
        menu.add(out);
        menuButton.addActionListener(e -> menu.show(menuButton, 0, menuButton.getHeight()));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(bell);
        actions.add(menuButton);
        return actions;
    }

    // =====================================================
    // NAVIGATION
    // =====================================================

    private void showPage(int index) {
        cardLayout.show(mainContentPanel, PAGES[index][0]);
        titleLabel.setText(PAGES[index][1]);
        if (index == NOTIFICATIONS_PAGE && notificationsPanel != null) notificationsPanel.reload();
        if (index != NOTIFICATIONS_PAGE) refreshBell();
        breadcrumbLabel.setText("TRI-MAX  >  " + PAGES[index][1]);
        subtitleLabel.setText(PAGES[index][2]);
        for (int i = 0; i < navButtons.length; i++) {
            navButtons[i].setSelected(i == index);
        }
        setTitle("TRI-MAX - " + PAGES[index][1]);
    }

    /**
     * Flat sidebar button: highlighted when selected, lighter on hover, and a yellow outline when
     * it has keyboard focus so the app can be used without a mouse.
     */
    private static final class NavButton extends JButton {
        private boolean hover;
        private boolean current;

        NavButton(String text) {
            super(text);
            setFont(Theme.NAV);
            setForeground(Theme.ON_NAVY);
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(new EmptyBorder(0, 14, 0, 10));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            setPreferredSize(new Dimension(180, 42));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e)  { hover = false; repaint(); }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e) { repaint(); }
                @Override public void focusLost(java.awt.event.FocusEvent e)   { repaint(); }
            });
        }

        @Override
        public void setSelected(boolean b) {
            current = b;
            setFont(b ? Theme.NAV_ON : Theme.NAV);
            setForeground(b ? Color.WHITE : Theme.ON_NAVY);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (current) {
                g2.setColor(Theme.BLUE);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            } else if (hover) {
                g2.setColor(Theme.NAVY_HOVER);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            }
            if (isFocusOwner()) {
                g2.setColor(Theme.FOCUS);
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame());
    }
}
