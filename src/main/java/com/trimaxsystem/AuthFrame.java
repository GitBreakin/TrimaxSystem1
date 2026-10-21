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

        // Placeholder for Company Logo
        JLabel logoLabel = new JLabel("TRI-MAX", SwingConstants.CENTER);
        logoLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
        logoLabel.setForeground(Color.decode("#64748B"));

        // Uncomment & update path below when your logo image is ready:
        // ImageIcon logoIcon = new ImageIcon(getClass().getResource("/path/to/logo.png"));
        // JLabel logoLabel = new JLabel(logoIcon);

        logoPanel.add(logoLabel);

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