package com.trimaxsystem;

import java.awt.*;
import java.util.Arrays;
import javax.swing.*;

public class RegisterPanel extends JPanel {

    private final AuthFrame parentFrame;
    private final JTextField txtName;
    private final JTextField txtEmail;
    private final JTextField txtUsername;
    private final JPasswordField txtPassword;
    private final JPasswordField txtConfirm;
    private final JButton btnCreateAccount;

    public RegisterPanel(AuthFrame parentFrame) {
        this.parentFrame = parentFrame;

        setBackground(AuthUI.PANEL_BG);
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridwidth = 2;

        // Title
        JLabel titleLabel = new JLabel("Create Account");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 26));
        titleLabel.setForeground(AuthUI.TITLE);
        gbc.gridy = 0;
        gbc.insets = new Insets(6, 25, 2, 25);
        add(titleLabel, gbc);

        // Subtitle
        JLabel subtitleLabel = new JLabel("Sign up to get started with TRI-MAX");
        subtitleLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subtitleLabel.setForeground(Color.decode("#334155"));
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 25, 12, 25);
        add(subtitleLabel, gbc);

        int y = 2;

        txtName     = AuthUI.textField("Full Name", 36);
        txtEmail    = AuthUI.textField("Email Address", 36);
        txtUsername = AuthUI.textField("Username", 36);
        txtPassword = AuthUI.passwordField("Password", 36);
        txtConfirm  = AuthUI.passwordField("Confirm Password", 36);

        String[] labels = {"Full Name:", "Email Address:", "Username:", "Password:", "Confirm Password:"};
        JComponent[] fields = {txtName, txtEmail, txtUsername, txtPassword, txtConfirm};

        for (int i = 0; i < labels.length; i++) {
            gbc.gridy = y++;
            gbc.insets = new Insets(4, 25, 2, 25);
            add(AuthUI.label(labels[i]), gbc);

            gbc.gridy = y++;
            gbc.insets = new Insets(0, 25, 6, 25);
            add(fields[i], gbc);
        }

        // Show / hide both password fields
        JCheckBox chkShow = AuthUI.showPasswordCheck(txtPassword, txtConfirm);
        gbc.gridy = y++;
        gbc.insets = new Insets(0, 25, 0, 25);
        add(chkShow, gbc);

        // Create Account button
        btnCreateAccount = AuthUI.button("Create Account", 320, 42, 13, Font.BOLD);
        gbc.gridy = y++;
        gbc.insets = new Insets(12, 25, 14, 25);
        add(btnCreateAccount, gbc);

        // Bottom navigation (text on the left, Return button on the right)
        JPanel bottomNavPanel = new JPanel(new BorderLayout());
        bottomNavPanel.setOpaque(false);

        JLabel lblAlreadyHave = new JLabel("Already have an account?");
        lblAlreadyHave.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblAlreadyHave.setForeground(Color.decode("#334155"));

        JButton btnReturnLogin = AuthUI.button("Return to Login", 130, 34, 12, Font.PLAIN);
        btnReturnLogin.addActionListener(e -> parentFrame.showCard("LOGIN"));

        bottomNavPanel.add(lblAlreadyHave, BorderLayout.WEST);
        bottomNavPanel.add(btnReturnLogin, BorderLayout.EAST);

        gbc.gridy = y;
        gbc.insets = new Insets(0, 25, 12, 25);
        add(bottomNavPanel, gbc);

        btnCreateAccount.addActionListener(e -> doRegister());
        txtConfirm.addActionListener(e -> doRegister());
    }

    private void doRegister() {
        final String name = txtName.getText().trim();
        final String email = txtEmail.getText().trim();
        final String username = txtUsername.getText().trim();
        final char[] password = txtPassword.getPassword();
        final char[] confirm = txtConfirm.getPassword();

        if (!Arrays.equals(password, confirm)) {
            AuthUI.wipe(password, confirm);
            AuthUI.warn(this, "The passwords do not match.");
            txtConfirm.setText("");
            txtConfirm.requestFocusInWindow();
            return;
        }
        AuthUI.wipe(confirm);

        setBusy(true);
        AuthUI.async(
                () -> AuthService.register(name, email, username, password),
                status -> {
                    AuthUI.wipe(password);
                    setBusy(false);
                    if ("Active".equals(status)) {
                        AuthUI.info(this, "Your administrator account was created and is ready to use.\n"
                                        + "(It is active right away because no administrator existed yet.)",
                                "Account Created");
                    } else {
                        AuthUI.info(this, "Your account was created and is waiting for approval by an administrator.\n"
                                        + "You will be able to log in once it has been approved.",
                                "Account Created");
                    }
                    clearForm();
                    parentFrame.showLogin(username);
                },
                message -> {
                    AuthUI.wipe(password);
                    setBusy(false);
                    AuthUI.warn(this, message);
                });
    }

    private void setBusy(boolean busy) {
        btnCreateAccount.setEnabled(!busy);
        btnCreateAccount.setText(busy ? "Creating account..." : "Create Account");
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    public void clearForm() {
        txtName.setText("");
        txtEmail.setText("");
        txtUsername.setText("");
        txtPassword.setText("");
        txtConfirm.setText("");
    }
}