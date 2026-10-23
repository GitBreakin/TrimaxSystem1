package com.trimaxsystem;

import java.awt.*;
import java.awt.geom.Path2D;
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
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);   // a resizable, maximized window leaves the taskbar visible
        setMinimumSize(new Dimension(1000, 700));  // (and uses the full screen when the taskbar is hidden)

        // Main background panel with GridBagLayout to keep both sides centered as a pair
        JPanel mainContainer = new JPanel(new GridBagLayout());
        mainContainer.setBackground(Color.decode("#FFFFFF"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(20, 40, 20, 40); // Spacing between logo and card
        gbc.anchor = GridBagConstraints.CENTER;

        // --- LEFT SIDE: LOGO PANEL ---
        JPanel logoPanel = new JPanel(new GridBagLayout());
        logoPanel.setOpaque(false);
        logoPanel.setPreferredSize(new Dimension(400, 580)); // Match height of card panel

        JPanel branding = new JPanel();
        branding.setOpaque(false);
        branding.setLayout(new BoxLayout(branding, BoxLayout.Y_AXIS));
        branding.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 40));

        JLabel logoLabel = new JLabel(createLogoIcon());
        logoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        branding.add(logoLabel);
        branding.add(Box.createVerticalStrut(16));

        Color brandText = Color.decode("#080F6D");
        JLabel brandName = new JLabel("TRI - MAX");
        brandName.setFont(Theme.BRAND.deriveFont(58f));
        brandName.setForeground(brandText);
        brandName.setAlignmentX(Component.CENTER_ALIGNMENT);
        branding.add(brandName);

        JLabel brandDescription = new JLabel("Goods Trading");
        brandDescription.setFont(Theme.BRAND.deriveFont(38f));
        brandDescription.setForeground(brandText);
        brandDescription.setAlignmentX(Component.CENTER_ALIGNMENT);
        branding.add(brandDescription);

        logoPanel.add(branding);

        gbc.gridx = 0;
        gbc.gridy = 0;
        mainContainer.add(logoPanel, gbc);

        // --- RIGHT SIDE: CARD LAYOUT CONTAINER ---
        cardLayout = new CardLayout();
        centerCardPanel = new JPanel(cardLayout);
        centerCardPanel.setPreferredSize(new Dimension(420, 580));

        // Instantiate child panels, passing 'this' frame reference
        loginPanel = new LoginPanel(this);
        forgotPasswordPanel = new ForgotPasswordPanel(this);
        registerPanel = new RegisterPanel(this);

        // Shown while a remembered session is being checked
        JPanel loadingPanel = new JPanel(new GridBagLayout());
        loadingPanel.setBackground(AuthUI.PANEL_BG);
        loadingLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        loadingLabel.setForeground(AuthUI.MUTED);
        loadingPanel.add(loadingLabel);

        // Add views to CardLayout (the first one added is the one shown first)
        centerCardPanel.add(loginPanel, "LOGIN");
        centerCardPanel.add(forgotPasswordPanel, "FORGOT");
        centerCardPanel.add(registerPanel, "REGISTER");
        centerCardPanel.add(loadingPanel, "LOADING");

        gbc.gridx = 1;
        gbc.gridy = 0;
        mainContainer.add(centerCardPanel, gbc);

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

    private static Icon createLogoIcon() {
        return new Icon() {
            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                try {
                    g2.translate(x, y);
                    g2.scale(getIconWidth() / 100.0, getIconHeight() / 80.0);
                    drawLogoPath(g2, Color.decode("#2563EB"), 10, 10, 90, 10, 75, 26, 25, 26);
                    drawLogoPath(g2, Color.decode("#1D4ED8"), 10, 10, 25, 26, 25, 50, 10, 35);
                    drawLogoPath(g2, Color.decode("#1D4ED8"), 90, 10, 90, 35, 75, 50, 75, 26);
                    drawLogoPath(g2, Color.decode("#2563EB"), 25, 26, 50, 55, 38, 70, 25, 50);
                    drawLogoPath(g2, Color.decode("#3B82F6"), 75, 26, 75, 50, 62, 70, 50, 55);
                    drawLogoPath(g2, Color.decode("#1E40AF"), 50, 26, 62, 40, 50, 55, 38, 40);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return 320;
            }

            @Override
            public int getIconHeight() {
                return 256;
            }
        };
    }

    private static void drawLogoPath(Graphics2D graphics, Color color, double... coordinates) {
        Path2D path = new Path2D.Double();
        path.moveTo(coordinates[0], coordinates[1]);
        for (int i = 2; i < coordinates.length; i += 2) {
            path.lineTo(coordinates[i], coordinates[i + 1]);
        }
        path.closePath();
        graphics.setColor(color);
        graphics.fill(path);
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new AuthFrame());
    }
}