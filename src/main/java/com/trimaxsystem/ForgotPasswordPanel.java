package com.trimaxsystem;

import java.awt.*;
import java.util.Arrays;
import javax.swing.*;

/**
 * Two steps inside one card:
 *   REQUEST - enter your email or username and ask for a reset code
 *   RESET   - enter the code and choose a new password
 */
public class ForgotPasswordPanel extends JPanel {

    private static final String STEP_REQUEST = "REQUEST";
    private static final String STEP_RESET   = "RESET";

    private final AuthFrame parentFrame;
    private final CardLayout steps = new CardLayout();

    // Step 1
    private final JTextField txtEmailOrUser;
    private final JButton btnSendCode;

    // Step 2
    private final JTextField txtCode;
    private final JPasswordField txtNewPassword;
    private final JPasswordField txtConfirmPassword;
    private final JButton btnReset;

    // The account typed in step 1, reused in step 2
    private String pendingIdentifier = "";

    public ForgotPasswordPanel(AuthFrame parentFrame) {
        this.parentFrame = parentFrame;

        setBackground(AuthUI.PANEL_BG);
        setLayout(steps);

        // ---------- STEP 1: REQUEST A CODE ----------
        JPanel request = newStepPanel();
        GridBagConstraints gbc = newConstraints();

        JLabel titleLabel = new JLabel("Forgot Password?");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 26));
        titleLabel.setForeground(AuthUI.TITLE);
        gbc.gridy = 0;
        request.add(titleLabel, gbc);

        String descText = "<html><body style='width: 250px;'>Enter your email address or username and we'll "
                + "send you a code to reset your password</body></html>";
        JLabel subtitleLabel = new JLabel(descText);
        subtitleLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subtitleLabel.setForeground(Color.decode("#334155"));
        gbc.gridy = 1;
        gbc.insets = new Insets(2, 25, 20, 25);
        request.add(subtitleLabel, gbc);

        txtEmailOrUser = AuthUI.textField("Email or Username", 38);
        txtEmailOrUser.setPreferredSize(new Dimension(0, 38));
        gbc.gridy = 2;
        gbc.insets = new Insets(6, 25, 6, 25);
        request.add(txtEmailOrUser, gbc);

        btnSendCode = AuthUI.button("Send Reset Code", 0, 42, 13, Font.BOLD);
        gbc.gridy = 3;
        gbc.insets = new Insets(16, 25, 60, 25);   // larger bottom inset pushes the navigation down
        request.add(btnSendCode, gbc);

        JPanel requestNav = new JPanel(new BorderLayout());
        requestNav.setOpaque(false);
        JLabel lblRemember = new JLabel("Remember your password?");
        lblRemember.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblRemember.setForeground(Color.decode("#334155"));
        JButton btnReturn1 = AuthUI.button("Return to Login", 140, 36, 12, Font.PLAIN);
        btnReturn1.addActionListener(e -> parentFrame.showCard("LOGIN"));
        requestNav.add(lblRemember, BorderLayout.WEST);
        requestNav.add(btnReturn1, BorderLayout.EAST);
        gbc.gridy = 4;
        gbc.insets = new Insets(0, 25, 15, 25);
        request.add(requestNav, gbc);

        // ---------- STEP 2: ENTER CODE + NEW PASSWORD ----------
        JPanel reset = newStepPanel();
        GridBagConstraints g2 = newConstraints();

        JLabel resetTitle = new JLabel("Reset Password");
        resetTitle.setFont(new Font("SansSerif", Font.BOLD, 26));
        resetTitle.setForeground(AuthUI.TITLE);
        g2.gridy = 0;
        reset.add(resetTitle, g2);

        JLabel resetDesc = new JLabel("<html><body style='width: 250px;'>Enter the code you received and "
                + "choose a new password. The code works for 15 minutes.</body></html>");
        resetDesc.setFont(new Font("SansSerif", Font.PLAIN, 12));
        resetDesc.setForeground(Color.decode("#334155"));
        g2.gridy = 1;
        g2.insets = new Insets(2, 25, 14, 25);
        reset.add(resetDesc, g2);

        txtCode = AuthUI.textField("Reset code", 38);
        txtCode.setPreferredSize(new Dimension(0, 38));
        g2.gridy = 2;
        g2.insets = new Insets(4, 25, 6, 25);
        reset.add(txtCode, g2);

        txtNewPassword = AuthUI.passwordField("New password", 38);
        txtNewPassword.setPreferredSize(new Dimension(0, 38));
        g2.gridy = 3;
        reset.add(txtNewPassword, g2);

        txtConfirmPassword = AuthUI.passwordField("Confirm new password", 38);
        txtConfirmPassword.setPreferredSize(new Dimension(0, 38));
        g2.gridy = 4;
        reset.add(txtConfirmPassword, g2);

        JCheckBox chkShow = AuthUI.showPasswordCheck(txtNewPassword, txtConfirmPassword);
        g2.gridy = 5;
        g2.insets = new Insets(0, 25, 0, 25);
        reset.add(chkShow, g2);

        btnReset = AuthUI.button("Reset Password", 0, 42, 13, Font.BOLD);
        g2.gridy = 6;
        g2.insets = new Insets(14, 25, 24, 25);
        reset.add(btnReset, g2);

        JPanel resetNav = new JPanel(new BorderLayout());
        resetNav.setOpaque(false);
        JButton btnStartOver = AuthUI.button("Start over", 110, 36, 12, Font.PLAIN);
        btnStartOver.addActionListener(e -> showStep(STEP_REQUEST));
        JButton btnReturn2 = AuthUI.button("Return to Login", 140, 36, 12, Font.PLAIN);
        btnReturn2.addActionListener(e -> parentFrame.showCard("LOGIN"));
        resetNav.add(btnStartOver, BorderLayout.WEST);
        resetNav.add(btnReturn2, BorderLayout.EAST);
        g2.gridy = 7;
        g2.insets = new Insets(0, 25, 15, 25);
        reset.add(resetNav, g2);

        add(request, STEP_REQUEST);
        add(reset, STEP_RESET);

        // ---------- ACTIONS ----------
        btnSendCode.addActionListener(e -> sendCode());
        txtEmailOrUser.addActionListener(e -> sendCode());
        btnReset.addActionListener(e -> resetPassword());
        txtConfirmPassword.addActionListener(e -> resetPassword());
    }

    private JPanel newStepPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(AuthUI.PANEL_BG);
        return p;
    }

    private GridBagConstraints newConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 25, 6, 25);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        return gbc;
    }

    private void showStep(String step) {
        steps.show(this, step);
    }

    // ---------- STEP 1 ----------
    private void sendCode() {
        final String id = txtEmailOrUser.getText().trim();

        btnSendCode.setEnabled(false);
        btnSendCode.setText("Sending...");
        AuthUI.async(
                () -> AuthService.requestPasswordReset(id),
                devCode -> {
                    btnSendCode.setEnabled(true);
                    btnSendCode.setText("Send Reset Code");
                    pendingIdentifier = id;

                    // Same message whether or not an account matched
                    AuthUI.info(this, "If an account matches \"" + id + "\", a reset code has been sent.\n"
                            + "The code is valid for 15 minutes.", "Reset Code Sent");

                    if (devCode != null) {   // only while AuthService.DEV_SHOW_RESET_CODE is true
                        AuthUI.info(this, "DEVELOPMENT MODE\n\nEmail sending is not set up yet, so the code is "
                                + "shown here instead:\n\n        " + devCode, "Your Reset Code");
                    }
                    showStep(STEP_RESET);
                    txtCode.requestFocusInWindow();
                },
                message -> {
                    btnSendCode.setEnabled(true);
                    btnSendCode.setText("Send Reset Code");
                    AuthUI.warn(this, message);
                });
    }

    // ---------- STEP 2 ----------
    private void resetPassword() {
        final String code = txtCode.getText().trim();
        final char[] newPassword = txtNewPassword.getPassword();
        final char[] confirm = txtConfirmPassword.getPassword();

        if (!Arrays.equals(newPassword, confirm)) {
            AuthUI.wipe(newPassword, confirm);
            AuthUI.warn(this, "The passwords do not match.");
            txtConfirmPassword.setText("");
            txtConfirmPassword.requestFocusInWindow();
            return;
        }
        AuthUI.wipe(confirm);

        btnReset.setEnabled(false);
        btnReset.setText("Saving...");
        AuthUI.async(
                () -> AuthService.resetPassword(pendingIdentifier, code, newPassword),
                username -> {
                    AuthUI.wipe(newPassword);
                    btnReset.setEnabled(true);
                    btnReset.setText("Reset Password");
                    AuthUI.info(this, "Your password was updated. You can now log in with the new password.",
                            "Password Updated");
                    clearForm();
                    parentFrame.showLogin(username);
                },
                message -> {
                    AuthUI.wipe(newPassword);
                    btnReset.setEnabled(true);
                    btnReset.setText("Reset Password");
                    AuthUI.warn(this, message);
                });
    }

    /** Back to step 1 with empty fields. */
    public void clearForm() {
        txtEmailOrUser.setText("");
        txtCode.setText("");
        txtNewPassword.setText("");
        txtConfirmPassword.setText("");
        pendingIdentifier = "";
        showStep(STEP_REQUEST);
    }
}