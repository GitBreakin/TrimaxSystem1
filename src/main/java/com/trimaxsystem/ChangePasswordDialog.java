package com.trimaxsystem;

import java.awt.*;
import java.util.Arrays;
import javax.swing.*;

/**
 * "Change Password" window for the signed-in administrator. Open it from your main window:
 *
 *     ChangePasswordDialog.open(this);     // "this" = the main window
 */
public class ChangePasswordDialog extends JDialog {

    private final JPasswordField txtCurrent;
    private final JPasswordField txtNew;
    private final JPasswordField txtConfirm;
    private final JButton btnSave;

    public static void open(Window owner) {
        new ChangePasswordDialog(owner).setVisible(true);
    }

    private ChangePasswordDialog(Window owner) {
        super(owner, "Change Password", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(AuthUI.PANEL_BG);
        setContentPane(content);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        int y = 0;

        JLabel title = new JLabel("Change Password");
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        title.setForeground(AuthUI.TITLE);
        gbc.gridy = y++;
        gbc.insets = new Insets(18, 25, 4, 25);
        content.add(title, gbc);

        AuthService.Session session = AuthService.current();
        JLabel who = new JLabel(session == null ? "Not logged in" : "Signed in as " + session.username);
        who.setFont(new Font("SansSerif", Font.PLAIN, 12));
        who.setForeground(AuthUI.MUTED);
        gbc.gridy = y++;
        gbc.insets = new Insets(0, 25, 12, 25);
        content.add(who, gbc);

        txtCurrent = AuthUI.passwordField("Current password", 36);
        txtNew     = AuthUI.passwordField("New password", 36);
        txtConfirm = AuthUI.passwordField("Confirm new password", 36);

        String[] labels = {"Current password:", "New password:", "Confirm new password:"};
        JComponent[] fields = {txtCurrent, txtNew, txtConfirm};
        for (int i = 0; i < labels.length; i++) {
            gbc.gridy = y++;
            gbc.insets = new Insets(4, 25, 2, 25);
            content.add(AuthUI.label(labels[i]), gbc);

            gbc.gridy = y++;
            gbc.insets = new Insets(0, 25, 6, 25);
            content.add(fields[i], gbc);
        }

        JCheckBox chkShow = AuthUI.showPasswordCheck(txtCurrent, txtNew, txtConfirm);
        gbc.gridy = y++;
        gbc.insets = new Insets(0, 25, 0, 25);
        content.add(chkShow, gbc);

        JLabel rule = new JLabel("At least 8 characters, with a letter and a number.");
        rule.setFont(new Font("SansSerif", Font.PLAIN, 11));
        rule.setForeground(AuthUI.MUTED);
        gbc.gridy = y++;
        gbc.insets = new Insets(6, 25, 0, 25);
        content.add(rule, gbc);

        JPanel buttons = new JPanel(new GridLayout(1, 2, 12, 0));
        buttons.setOpaque(false);
        JButton btnCancel = AuthUI.button("Cancel", 0, 40, 13, Font.PLAIN);
        btnSave = AuthUI.button("Save Password", 0, 40, 13, Font.BOLD);
        buttons.add(btnCancel);
        buttons.add(btnSave);
        gbc.gridy = y;
        gbc.insets = new Insets(16, 25, 20, 25);
        content.add(buttons, gbc);

        btnCancel.addActionListener(e -> dispose());
        btnSave.addActionListener(e -> save());
        txtConfirm.addActionListener(e -> save());

        setResizable(false);
        pack();
        setLocationRelativeTo(owner);
    }

    private void save() {
        final char[] current = txtCurrent.getPassword();
        final char[] newPassword = txtNew.getPassword();
        final char[] confirm = txtConfirm.getPassword();

        if (!Arrays.equals(newPassword, confirm)) {
            AuthUI.wipe(current, newPassword, confirm);
            AuthUI.warn(this, "The new passwords do not match.");
            txtConfirm.setText("");
            txtConfirm.requestFocusInWindow();
            return;
        }
        AuthUI.wipe(confirm);

        btnSave.setEnabled(false);
        btnSave.setText("Saving...");
        AuthUI.async(
                () -> {
                    AuthService.changePassword(current, newPassword);
                    return Boolean.TRUE;
                },
                ok -> {
                    AuthUI.wipe(current, newPassword);
                    AuthUI.info(this, "Your password was changed.", "Password Changed");
                    dispose();
                },
                message -> {
                    AuthUI.wipe(current, newPassword);
                    btnSave.setEnabled(true);
                    btnSave.setText("Save Password");
                    AuthUI.warn(this, message);
                });
    }
}