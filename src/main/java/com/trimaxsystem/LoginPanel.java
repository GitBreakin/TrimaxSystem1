package com.trimaxsystem;

import java.awt.*;
import javax.swing.*;

public class LoginPanel extends JPanel {

    private final AuthFrame parentFrame;
    private final JTextField txtUsername;
    private final JPasswordField txtPassword;
    private final JCheckBox chkShow;
    private final JCheckBox chkRemember;
    private final JButton btnLogin;

    public LoginPanel(AuthFrame parentFrame) {
        this.parentFrame = parentFrame;

        setBackground(AuthUI.PANEL_BG);
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridwidth = 2;

        // Title
        JLabel titleLabel = new JLabel("Welcome Back!");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 28));
        titleLabel.setForeground(AuthUI.TITLE);
        gbc.gridy = 0;
        gbc.insets = new Insets(6, 30, 2, 30);
        add(titleLabel, gbc);

        // Subtitle
        JLabel subtitleLabel = new JLabel("Please login to your account to continue.");
        subtitleLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        subtitleLabel.setForeground(AuthUI.MUTED);
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 30, 20, 30);
        add(subtitleLabel, gbc);

        // Username
        gbc.gridy = 2;
        gbc.insets = new Insets(4, 30, 2, 30);
        add(AuthUI.label("Username:"), gbc);

        txtUsername = AuthUI.textField("Username", 40);
        gbc.gridy = 3;
        gbc.insets = new Insets(0, 30, 10, 30);
        add(txtUsername, gbc);

        // Password
        gbc.gridy = 4;
        gbc.insets = new Insets(4, 30, 2, 30);
        add(AuthUI.label("Password:"), gbc);

        txtPassword = AuthUI.passwordField("Password", 40);
        gbc.gridy = 5;
        gbc.insets = new Insets(0, 30, 6, 30);
        add(txtPassword, gbc);

        // Options row: Show password (left) and Remember session (right)
        chkShow = AuthUI.showPasswordCheck(txtPassword);
        chkRemember = new JCheckBox("Remember session");
        AuthUI.styleCheck(chkRemember);

        JPanel options = new JPanel(new BorderLayout());
        options.setOpaque(false);
        options.add(chkShow, BorderLayout.WEST);
        options.add(chkRemember, BorderLayout.EAST);
        gbc.gridy = 6;
        gbc.insets = new Insets(0, 30, 14, 30);
        add(options, gbc);

        // Log In button
        btnLogin = AuthUI.button("Log In", 320, 44, 14, Font.BOLD);
        gbc.gridy = 7;
        gbc.insets = new Insets(4, 30, 14, 30);
        add(btnLogin, gbc);

        // Bottom navigation buttons (Forgot Password & Register)
        JPanel bottomButtonsPanel = new JPanel(new GridLayout(1, 2, 12, 0));
        bottomButtonsPanel.setOpaque(false);

        JButton btnForgot = AuthUI.button("Forgot Password", 0, 38, 12, Font.PLAIN);
        btnForgot.addActionListener(e -> parentFrame.showCard("FORGOT"));

        JButton btnRegister = AuthUI.button("Register Here", 0, 38, 12, Font.PLAIN);
        btnRegister.addActionListener(e -> parentFrame.showCard("REGISTER"));

        bottomButtonsPanel.add(btnForgot);
        bottomButtonsPanel.add(btnRegister);

        gbc.gridy = 8;
        gbc.insets = new Insets(0, 30, 20, 30);
        add(bottomButtonsPanel, gbc);

        // Enter key: username -> password -> log in
        txtUsername.addActionListener(e -> txtPassword.requestFocusInWindow());
        txtPassword.addActionListener(e -> doLogin());
        btnLogin.addActionListener(e -> doLogin());
    }

    private void doLogin() {
        final String username = txtUsername.getText().trim();
        final char[] password = txtPassword.getPassword();
        final boolean remember = chkRemember.isSelected();

        setBusy(true);
        AuthUI.async(
                () -> AuthService.login(username, password, remember),
                session -> {
                    AuthUI.wipe(password);
                    setBusy(false);
                    txtPassword.setText("");
                    parentFrame.onLoginSuccess(session);
                    MainFrame mainframe = new MainFrame();
                    mainframe.setVisible(true);

                },
                message -> {
                    AuthUI.wipe(password);
                    setBusy(false);
                    txtPassword.setText("");
                    AuthUI.error(this, message);
                    txtPassword.requestFocusInWindow();
                });
    }

    private void setBusy(boolean busy) {
        btnLogin.setEnabled(!busy);
        btnLogin.setText(busy ? "Logging in..." : "Log In");
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    /** Called by the frame to come back to a clean login screen (after logout or a password reset). */
    public void resetForm(String usernameToShow) {
        txtUsername.setText(usernameToShow == null ? "" : usernameToShow);
        txtPassword.setText("");
        chkRemember.setSelected(false);
        if (usernameToShow == null || usernameToShow.isEmpty()) {
            txtUsername.requestFocusInWindow();
        } else {
            txtPassword.requestFocusInWindow();
        }
    }
}