package com.trimaxsystem;

import java.awt.*;
import javax.swing.*;

public class AuthFrame extends JFrame {

    private CardLayout cardLayout;
    private JPanel centerCardPanel;

    private LoginPanel loginPanel;
    private ForgotPasswordPanel forgotPasswordPanel;
    private RegisterPanel registerPanel;

    public AuthFrame() {
        setTitle("TRI-MAX Goods Trading Management System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setResizable(false);

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
        JLabel loadingLabel = new JLabel("Restoring your session...");
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
     * ===== CONNECT YOUR MAIN WINDOW HERE =====
     * Replace the dialog below with the code that opens your main window, then close this one:
     *
     *     new MainFrame(session).setVisible(true);   // use your own main window class here
     *     dispose();
     */
    private void launchMainApp(AuthService.Session session) {
        JOptionPane.showMessageDialog(this,
                "Logged in as " + session.fullName + " (" + session.role + ").\n\n"
                        + "Connect your main window in AuthFrame.launchMainApp().",
                "Login successful", JOptionPane.INFORMATION_MESSAGE);
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