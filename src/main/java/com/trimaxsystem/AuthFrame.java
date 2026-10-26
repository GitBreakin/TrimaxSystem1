package com.trimaxsystem;

import java.awt.*;
import javax.swing.*;

public class AuthFrame extends JFrame {

    private CardLayout cardLayout;
    private JPanel centerCardPanel;

    private LoginPanel loginPanel;
    private ForgotPasswordPanel forgotPasswordPanel;
    private RegisterPanel registerPanel;
    private final JLabel loadingLabel = new JLabel("Restoring your session...");
    private boolean launching;

    public AuthFrame() {
        setTitle("TRI-MAX Goods Trading Management System");
        setIconImages(BrandLogo.createWindowIcons());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);   // a resizable, maximized window leaves the taskbar visible
        setMinimumSize(new Dimension(820, 680));   // below ~1100 px wide the brand panel hides itself

        // Left: brand panel (navy gradient). Right: the sign-in card centred on a light background.
        LoginBrandPanel brandPanel = new LoginBrandPanel();
        JPanel mainContainer = new JPanel(new BorderLayout());
        mainContainer.add(brandPanel, BorderLayout.WEST);

        JPanel workArea = new JPanel(new GridBagLayout());
        workArea.setBackground(AuthUI.PAGE_BG);
        mainContainer.add(workArea, BorderLayout.CENTER);

        // Card switcher: sign in, forgot password, register (not linked from sign-in), loading
        cardLayout = new CardLayout();
        centerCardPanel = new JPanel(cardLayout);
        centerCardPanel.setOpaque(false);
        centerCardPanel.setPreferredSize(new Dimension(420, 560));

        // Instantiate child panels, passing 'this' frame reference
        loginPanel = new LoginPanel(this);
        forgotPasswordPanel = new ForgotPasswordPanel(this);
        registerPanel = new RegisterPanel(this);

        // Shown while a remembered session is being checked or the main window is loading
        JPanel loadingPanel = new JPanel(new GridBagLayout());
        loadingPanel.setBackground(AuthUI.PANEL_BG);
        loadingLabel.setFont(new Font("SansSerif", Font.PLAIN, 15));
        loadingLabel.setForeground(AuthUI.MUTED);
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(220, 6));
        progress.setBorderPainted(false);
        progress.setForeground(AuthUI.BLUE);
        progress.getAccessibleContext().setAccessibleName("Loading");
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = 0;
        loadingPanel.add(loadingLabel, lc);
        lc.gridy = 1;
        lc.insets = new Insets(16, 0, 0, 0);
        loadingPanel.add(progress, lc);

        // Add views to CardLayout (the first one added is the one shown first)
        centerCardPanel.add(loginPanel, "LOGIN");
        centerCardPanel.add(forgotPasswordPanel, "FORGOT");
        centerCardPanel.add(registerPanel, "REGISTER");
        centerCardPanel.add(loadingPanel, "LOADING");

        CardShell card = new CardShell();
        card.add(centerCardPanel, BorderLayout.CENTER);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        workArea.add(card, gbc);

        String version = AuthFrame.class.getPackage() != null && AuthFrame.class.getPackage().getImplementationVersion() != null
                ? AuthFrame.class.getPackage().getImplementationVersion() : "1.0";
        JLabel footer = new JLabel("\u00A9 " + java.time.Year.now(Theme.BUSINESS_TZ) + " TRI-MAX Goods Trading  \u00B7  Admin Console " + version);
        footer.setFont(new Font("SansSerif", Font.PLAIN, 12));
        footer.setForeground(AuthUI.MUTED);
        gbc.gridy = 1;
        gbc.insets = new Insets(14, 0, 0, 0);
        workArea.add(footer, gbc);

        // Responsive: the brand panel takes ~42% of the width and hides on narrow windows.
        mainContainer.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int w = mainContainer.getWidth();
                boolean show = w >= 1100;
                brandPanel.setPreferredSize(new Dimension(Math.max(460, (int) (w * 0.42)), 10));
                if (brandPanel.isVisible() != show) brandPanel.setVisible(show);
                mainContainer.revalidate();
            }
        });

        // Add container to JFrame
        add(mainContainer);

        setVisible(true);

        // "Remember session": if this computer has a saved session, try it before asking for a login
        if (AuthService.hasSavedSession()) {
            showCard("LOADING");
            AuthUI.async(
                    AuthService::loginWithSavedSession,
                    session -> {
                        if (session != null) {
                            onLoginSuccess(session);
                        } else {
                            showCard("LOGIN");
                        }
                    },
                    message -> showCard("LOGIN"));
        }
    }

    // Helper method accessible by child panels to switch screens
    public void showCard(String cardName) {
        cardLayout.show(centerCardPanel, cardName);
    }

    /** Goes back to the login screen, optionally with the username filled in. */
    public void showLogin(String usernameToShow) {
        loginPanel.resetForm(usernameToShow);
        showCard("LOGIN");
    }

    /** Called by the login screen (or the remembered session) after a successful administrator sign-in. */
    public void onLoginSuccess(AuthService.Session session) {
        launchMainApp(session);
    }

    /**
     * Opens the main window. A loading screen is shown while the main window is built and its first
     * data (the dashboard) is fetched; only then is the main window shown and this window closed.
     */
    private void launchMainApp(AuthService.Session session) {
        if (launching) return;   // ignore a second sign-in signal while the main window is loading
        launching = true;
        loadingLabel.setText("Loading TRI-MAX...");
        showCard("LOADING");

        // Let the loading screen paint first, then build the main window (hidden).
        SwingUtilities.invokeLater(() -> {
            final MainFrame[] frame = new MainFrame[1];
            final boolean[] revealed = {false};
            final Timer[] giveUp = new Timer[1];

            Runnable reveal = () -> {
                if (revealed[0]) return;
                revealed[0] = true;
                if (giveUp[0] != null) giveUp[0].stop();
                frame[0].setVisible(true);
                dispose();
            };

            try {
                frame[0] = new MainFrame(reveal);
            } catch (RuntimeException ex) {
                ex.printStackTrace();
                launching = false;
                showCard("LOGIN");
                AuthUI.error(this, "Could not open the main window: " + ex.getMessage());
                return;
            }

            // If the database is very slow, still open the window (its sections show their own states).
            giveUp[0] = new Timer(20_000, e -> reveal.run());
            giveUp[0].setRepeats(false);
            giveUp[0].start();
        });
    }

    /**
     * Logout. Call this from the Logout button of your main window:
     *
     *     AuthFrame.logout(this);     // "this" = the main window; it is closed and the login screen opens
     */
    public static void logout(Window currentWindow) {
        AuthUI.async(
                () -> {
                    AuthService.logout();
                    return null;
                },
                done -> {
                    if (currentWindow != null) currentWindow.dispose();
                    new AuthFrame();
                },
                message -> {
                    if (currentWindow != null) currentWindow.dispose();
                    new AuthFrame();
                });
    }

    /** White rounded card with a soft shadow that holds the sign-in screens. */
    private static final class CardShell extends JPanel {
        private static final int SHADOW = 18;

        CardShell() {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(SHADOW, SHADOW, SHADOW + 4, SHADOW));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                // soft shadow: stacked translucent rounded rectangles, darker toward the card
                for (int i = SHADOW; i > 0; i -= 2) {
                    g2.setColor(new Color(15, 23, 42, Math.max(1, 7 - i / 3)));
                    g2.fill(new java.awt.geom.RoundRectangle2D.Double(SHADOW - i, SHADOW - i + 6, w - 2 * (SHADOW - i), h - 2 * (SHADOW - i) - 6, 22 + i, 22 + i));
                }
                g2.setColor(AuthUI.PANEL_BG);
                g2.fill(new java.awt.geom.RoundRectangle2D.Double(SHADOW, SHADOW, w - 2 * SHADOW, h - 2 * SHADOW - 4, 22, 22));
                g2.setColor(Color.decode("#E3E9F3"));
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(SHADOW + 0.5, SHADOW + 0.5, w - 2 * SHADOW - 1, h - 2 * SHADOW - 5, 22, 22));
            } finally {
                g2.dispose();
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Laf.install();   // modern light look; falls back to the default Swing look if FlatLaf is missing
            new AuthFrame();
        });
    }
}