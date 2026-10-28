package com.trimaxsystem;

import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * Main admin window: navy sidebar on the left, a header with the page title and the signed-in
 * admin on top, and the screen itself in the middle. Only screens that really exist are listed.
 */
public class MainFrame extends JFrame {

    /**
     * One sidebar entry: text on the button, the page title shown in the header, and the card key.
     */
    private static final String[][] PAGES = {
            // { sidebar label, header title, header subtitle }
            {"Dashboard", "Dashboard", "Overview of today's operations"},
            {"Sales & Profit", "Sales & Profit", "Net sales, cost of goods, gross profit, payroll, other expenses and net profit"},
            {"Walk-In / In-House", "Walk-In / In-House Sales", "Sell at the store or for a saved customer; receipts, payments and voids"},
            {"Inventory", "Inventory", "Products, puhunan and patong, prices, restocking and stock history"},
            {"Bookings", "Bookings", "All bookings and their status"},
            {"Live GPS Monitoring", "Live GPS Monitoring", "Last known vehicle and agent positions, and recorded location history"},
            {"Manage Vehicles", "Manage Vehicles", "Vehicle records, status, and driver assignments"},
            {"Agents & Drivers", "Agents & Drivers", "Accounts, roles and account status"},
            {"Attendance", "Attendance", "Start and end of duty from the mobile app, with photo, time and location"},
            {"Payroll", "Payroll", "Pay per employee and pay period; paid and unpaid (Admin only)"},
            {"Expenses", "Other Expenses", "Electricity, rent, fuel and other costs; they lower net profit (Admin only)"},
            {"Customers", "Customers", "Customer records, photos, assigned agents and locations"},
            {"Analytics & Reports", "Analytics & Reports", "Booking reports, and which products sell, stall or need restocking"},
            {"Notifications", "Notifications", "Operational alerts and announcements"},
            {"Audit Logs", "Audit Logs", "Who changed what, and when (read-only)"},
            {"System Settings", "System Settings", "Your profile, connection health and environment"},
    };

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel mainContentPanel = new JPanel(cardLayout);
    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();
    private final JLabel breadcrumbLabel = new JLabel();
    private final JButton bell = headerIconButton(new HeaderIcon(true, 0), "Notifications");
    private NotificationsPanel notificationsPanel;
    private AttendancePanel attendancePanel;
    private ProfitPanel profitPanel;
    private InventoryPanel inventoryPanel;
    private final NavButton[] navButtons = new NavButton[PAGES.length];

    /**
     * Opens the main window straight away.
     */
    public MainFrame() {
        this(null);
        setVisible(true);
    }

    /**
     * Builds the main window WITHOUT showing it. onReady runs once the dashboard has finished its first
     * load (successfully or not); the caller then shows the window. Used by the login screen.
     */
    public MainFrame(Runnable onReady) {
        setTitle("TRI-MAX Consumer Goods Trading Management System");
        setIconImages(BrandLogo.createWindowIcons());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setMinimumSize(new Dimension(1000, 640));
        setLayout(new BorderLayout());
        getContentPane().setBackground(Theme.BACKGROUND);

        // --- SCREENS (CENTER) ---
        profitPanel = new ProfitPanel();
        DashboardPanel dashboard = new DashboardPanel(onReady);
        dashboard.setOpenProfit(name -> {
            showPage(PROFIT_PAGE);
            profitPanel.showPreset(name);
        });
        mainContentPanel.add(dashboard, PAGES[0][0]);
        mainContentPanel.add(profitPanel, PAGES[PROFIT_PAGE][0]);
        mainContentPanel.add(new SalesPanel(), PAGES[2][0]);
        inventoryPanel = new InventoryPanel();
        mainContentPanel.add(inventoryPanel, PAGES[3][0]);
        mainContentPanel.add(new BookingsPanel(), PAGES[4][0]);
        mainContentPanel.add(buildLiveGpsPage(), PAGES[5][0]);
        mainContentPanel.add(new ManageVehiclesPanel(), PAGES[6][0]);
        mainContentPanel.add(new AccountsPanel(), PAGES[7][0]);
        attendancePanel = new AttendancePanel();
        mainContentPanel.add(attendancePanel, PAGES[ATTENDANCE_PAGE][0]);
        mainContentPanel.add(new PayrollPanel(), PAGES[9][0]);
        mainContentPanel.add(new ExpensesPanel(), PAGES[10][0]);
        mainContentPanel.add(new ClientsPanel(), PAGES[11][0]);
        mainContentPanel.add(buildAnalyticsPage(), PAGES[12][0]);
        notificationsPanel = new NotificationsPanel(this::refreshBell);
        notificationsPanel.setOpenProduct(this::openProduct);
        mainContentPanel.add(notificationsPanel, PAGES[NOTIFICATIONS_PAGE][0]);
        mainContentPanel.add(new AuditLogsPanel(), PAGES[14][0]);
        mainContentPanel.add(new SettingsPanel(), PAGES[15][0]);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Theme.BACKGROUND);
        right.add(buildHeader(), BorderLayout.NORTH);
        right.add(mainContentPanel, BorderLayout.CENTER);

        add(buildSidebar(), BorderLayout.WEST);
        add(right, BorderLayout.CENTER);

        showPage(0);
    }

    /**
     * Live GPS Monitoring page: read-only maps for the last reported vehicle and agent positions, and the
     * recorded location history of one agent or driver (Admins only).
     */
    private JComponent buildAnalyticsPage() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Booking reports", new AnalyticsPanel());
        tabs.addTab("Sales & Inventory Intelligence", new InsightsPanel(this::openProduct, this::refreshBell));
        return tabs;
    }

    /** Opens a product in Inventory (from Analytics or a notification). */
    private void openProduct(int productId) {
        showPage(3);
        inventoryPanel.selectProduct(productId);
    }

    /**
     * Checks for new inventory alerts in the background (the server has no scheduler): when the window
     * opens and every 15 minutes while it is open. Failures are ignored; the bell shows what exists.
     */
    private void refreshInventoryAlerts() {
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return InsightService.refreshAlerts();
            }

            @Override
            protected void done() {
                try {
                    if (get() > 0) refreshBell();
                } catch (Exception ignored) {
                    // not set up yet, offline, or not an admin session: nothing to show
                }
            }
        }.execute();
    }

    private JComponent buildLiveGpsPage() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Vehicle map", new LiveGpsPanel());
        tabs.addTab("Agent locations", new AgentLocationsPanel());
        tabs.addTab("Location history", new LocationHistoryPanel());
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
        JLabel tagline = new JLabel("Consumer Goods Trading");
        tagline.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tagline.setForeground(Theme.ON_NAVY_DIM);

        JPanel brandBox = new JPanel();
        brandBox.setOpaque(false);
        brandBox.setLayout(new BoxLayout(brandBox, BoxLayout.Y_AXIS));
        brandBox.setBorder(new EmptyBorder(16, 22, 10, 16));
        brandBox.add(brand);
        brandBox.add(tagline);
        sidebar.add(brandBox, BorderLayout.NORTH);

        JPanel nav = new JPanel();
        nav.setOpaque(false);
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBorder(new EmptyBorder(2, 12, 4, 10));
        for (int i = 0; i < PAGES.length; i++) {
            final int index = i;
            NavButton btn = new NavButton(PAGES[i][0]);
            btn.addActionListener(e -> showPage(index));
            navButtons[i] = btn;
            // Notifications open from the bell in the header (with the unread count); a second entry in
            // the sidebar went to the same page, so it is not shown there.
            if (i == NOTIFICATIONS_PAGE) continue;
            nav.add(btn);
            nav.add(Box.createVerticalStrut(2));
        }
        // The list scrolls on short screens, so every page stays reachable.
        JPanel navHolder = new JPanel(new BorderLayout());
        navHolder.setOpaque(false);
        navHolder.add(nav, BorderLayout.NORTH);
        JScrollPane navScroll = new JScrollPane(navHolder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        navScroll.setBorder(BorderFactory.createEmptyBorder());
        navScroll.setOpaque(false);
        navScroll.getViewport().setOpaque(false);
        // Thin light thumb on the navy, no white track: nothing is covered when the list scrolls.
        UiKit.slimScrollBar(navScroll.getVerticalScrollBar(), new Color(255, 255, 255, 70), new Color(255, 255, 255, 140));
        sidebar.add(navScroll, BorderLayout.CENTER);

        // Signed-in admin, pinned to the bottom (Sign out is in the profile menu at the top right).
        AuthService.Session session = AuthService.current();
        JLabel who = new JLabel(session == null ? "Not signed in" : session.fullName);
        who.setFont(Theme.NAV_ON);
        who.setForeground(Color.WHITE);
        who.setToolTipText(session == null ? null : session.username);
        JLabel role = new JLabel(session == null ? "" : session.role);
        role.setFont(new Font("SansSerif", Font.PLAIN, 12));
        role.setForeground(Theme.ON_NAVY_DIM);

        JPanel whoBox = new JPanel();
        whoBox.setOpaque(false);
        whoBox.setLayout(new BoxLayout(whoBox, BoxLayout.Y_AXIS));
        whoBox.add(who);
        whoBox.add(Box.createVerticalStrut(2));
        whoBox.add(role);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(255, 255, 255, 30)),
                new EmptyBorder(10, 22, 12, 16)));
        footer.add(whoBox, BorderLayout.CENTER);
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

    private static final int PROFIT_PAGE = 1;
    private static final int ATTENDANCE_PAGE = 8;
    private static final int NOTIFICATIONS_PAGE = 13;
    /** How often the bell asks the database for the unread count (no realtime connection on the Desktop). */
    private static final int BELL_POLL_MS = 30_000;
    /** How often the inventory alerts are re-checked while the app is open. */
    private static final int ALERT_CHECK_MS = 15 * 60_000;
    private int lastUnread = -1;
    private int currentPage = 0;

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
                    bell.setIcon(new HeaderIcon(true, n));
                    bell.setToolTipText(n > 0 ? "Notifications (" + n + " unread)" : "Notifications");
                    // Something new arrived while the Notifications page is open: show it there too.
                    if (lastUnread >= 0 && n > lastUnread && currentPage == NOTIFICATIONS_PAGE && notificationsPanel != null) {
                        notificationsPanel.reload();
                    }
                    lastUnread = n;
                } catch (Exception ex) {
                    bell.setIcon(new HeaderIcon(true, 0));
                    bell.setToolTipText("Notifications");
                }
            }
        }.execute();
    }

    /**
     * Notification bell and admin menu.
     */
    private JComponent buildHeaderActions() {
        bell.setToolTipText("Notifications");
        bell.getAccessibleContext().setAccessibleName("Notifications");
        bell.addActionListener(e -> showPage(NOTIFICATIONS_PAGE));
        refreshBell();
        // New notifications (e.g. a booking from an agent) appear on the bell within half a minute.
        Timer bellPoll = new Timer(BELL_POLL_MS, e -> {
            if (isShowing()) refreshBell();
        });
        bellPoll.setRepeats(true);
        bellPoll.start();
        Timer alertCheck = new Timer(ALERT_CHECK_MS, e -> {
            if (isShowing()) refreshInventoryAlerts();
        });
        alertCheck.setInitialDelay(5_000);   // shortly after sign-in, then every 15 minutes
        alertCheck.setRepeats(true);
        alertCheck.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                bellPoll.stop();   // signed out or closed: stop asking
                alertCheck.stop();
            }
        });

        AuthService.Session session = AuthService.current();
        JButton menuButton = headerIconButton(new HeaderIcon(false, 0), "Profile");
        menuButton.getAccessibleContext().setAccessibleName("Profile menu");
        menuButton.setToolTipText("Profile menu");
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

    private static JButton headerIconButton(Icon icon, String accessibleName) {
        JButton button = UiKit.secondaryButton("");
        button.setIcon(icon);
        button.setText("");
        button.setMargin(new Insets(4, 4, 4, 4));
        button.setPreferredSize(new Dimension(40, 40));
        button.setMinimumSize(new Dimension(40, 40));
        button.setMaximumSize(new Dimension(40, 40));
        button.setToolTipText(accessibleName);
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static final class HeaderIcon implements Icon {
        private final boolean notification;
        private final int unreadCount;

        HeaderIcon(boolean notification, int unreadCount) {
            this.notification = notification;
            this.unreadCount = Math.max(0, unreadCount);
        }

        @Override
        public int getIconWidth() {
            return 24;
        }

        @Override
        public int getIconHeight() {
            return 24;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.TEXT);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (notification) {
                Path2D bellShape = new Path2D.Double();
                bellShape.moveTo(5, 17);
                bellShape.curveTo(7, 15, 7, 13, 7, 10);
                bellShape.curveTo(7, 6, 9, 4, 12, 4);
                bellShape.curveTo(15, 4, 17, 6, 17, 10);
                bellShape.curveTo(17, 13, 17, 15, 19, 17);
                bellShape.closePath();
                g.draw(bellShape);
                g.drawLine(5, 18, 19, 18);
                g.draw(new Arc2D.Double(9, 18, 6, 4, 180, 180, Arc2D.OPEN));
                if (unreadCount > 0) {
                    String badge = unreadCount > 9 ? "9+" : Integer.toString(unreadCount);
                    g.setFont(new Font("SansSerif", Font.BOLD, 9));
                    FontMetrics metrics = g.getFontMetrics();
                    int badgeWidth = Math.max(13, metrics.stringWidth(badge) + 6);
                    int badgeX = 22 - badgeWidth;
                    g.setColor(Theme.DANGER);
                    g.fillRoundRect(badgeX, 0, badgeWidth, 13, 8, 8);
                    g.setColor(Color.WHITE);
                    g.drawString(badge, badgeX + (badgeWidth - metrics.stringWidth(badge)) / 2, 10);
                }
            } else {
                g.draw(new Ellipse2D.Double(8, 3, 8, 8));
                Path2D shoulders = new Path2D.Double();
                shoulders.moveTo(4, 21);
                shoulders.curveTo(4, 16, 7, 13, 12, 13);
                shoulders.curveTo(17, 13, 20, 16, 20, 21);
                g.draw(shoulders);
            }
            g.dispose();
        }
    }

    // =====================================================
    // NAVIGATION
    // =====================================================

    private void showPage(int index) {
        currentPage = index;
        cardLayout.show(mainContentPanel, PAGES[index][0]);
        titleLabel.setText(PAGES[index][1]);
        if (index == NOTIFICATIONS_PAGE && notificationsPanel != null) notificationsPanel.reload();
        if (index == ATTENDANCE_PAGE && attendancePanel != null) attendancePanel.reload();
        if (index != NOTIFICATIONS_PAGE) refreshBell();
        breadcrumbLabel.setText("TRI-MAX  >  " + PAGES[index][1]);
        subtitleLabel.setText(PAGES[index][2]);
        for (int i = 0; i < navButtons.length; i++) {
            navButtons[i].setSelected(i == index);
        }
        // Keep the selected entry in view when the sidebar scrolls (e.g. opened from the dashboard).
        NavButton selected = navButtons[index];
        if (selected.getParent() != null) {
            SwingUtilities.invokeLater(() -> selected.scrollRectToVisible(new Rectangle(0, -4, selected.getWidth(), selected.getHeight() + 8)));
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
            // 34 px rows: all 15 sidebar pages fit on a 1080p screen at 125 % scaling; shorter screens scroll.
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            setPreferredSize(new Dimension(180, 34));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
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
        SwingUtilities.invokeLater(Laf::install);
        try {
            ExportDirectories.initialize();
        } catch (java.io.IOException e) {
            javax.swing.JOptionPane.showMessageDialog(
                    null,
                    "The folder is not created\n" + e.getMessage(),
                    "Folder Error",
                    javax.swing.JOptionPane.ERROR_MESSAGE
            );
            return;
        }
        SwingUtilities.invokeLater(() -> new MainFrame());
    }
}
