package com.trimaxsystem;

import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Locale;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The administrator sign-in form. Authentication is unchanged: {@link AuthService#login} checks the
 * password, lockout, Active status and the Admin role in the database. This class only handles the
 * form: inline validation and errors, a busy state that blocks double submits, password reveal,
 * Caps Lock hint and full keyboard use (Tab order, Enter to sign in).
 */
public class LoginPanel extends JPanel {

    private final AuthFrame parentFrame;
    private final JTextField txtUsername;
    private final AuthUI.PasswordInput passwordInput;
    private final JPasswordField txtPassword;
    private final JCheckBox chkRemember;
    private final JButton btnLogin;
    private final JButton btnForgot;
    private final AuthUI.MessageBox message = new AuthUI.MessageBox();
    private final JLabel capsLock = new JLabel("Caps Lock is on.");
    private boolean busy;

    public LoginPanel(AuthFrame parentFrame) {
        this.parentFrame = parentFrame;

        setBackground(AuthUI.PANEL_BG);
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.weightx = 1;

        // Eyebrow: small logo + product name (keeps the brand visible when the side panel is hidden)
        JLabel eyebrow = new JLabel("TRI-MAX ADMIN CONSOLE", BrandLogo.createIcon(30, 24), SwingConstants.LEFT);
        eyebrow.setIconTextGap(10);
        eyebrow.setFont(new Font("SansSerif", Font.BOLD, 11));
        eyebrow.setForeground(AuthUI.BLUE);
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 30, 18, 30);
        add(eyebrow, gbc);

        JLabel titleLabel = new JLabel("Welcome back");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 28));
        titleLabel.setForeground(AuthUI.TITLE);
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 30, 4, 30);
        add(titleLabel, gbc);

        JLabel subtitleLabel = new JLabel("Sign in with your administrator account to continue.");
        subtitleLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        subtitleLabel.setForeground(AuthUI.MUTED);
        gbc.gridy = 2;
        gbc.insets = new Insets(0, 30, 20, 30);
        add(subtitleLabel, gbc);

        // Inline error (hidden until needed)
        gbc.gridy = 3;
        gbc.insets = new Insets(0, 30, 14, 30);
        add(message, gbc);

        // Username
        JLabel userLabel = AuthUI.label("Username");
        gbc.gridy = 4;
        gbc.insets = new Insets(0, 30, 6, 30);
        add(userLabel, gbc);

        txtUsername = AuthUI.textField("Enter your username", 44);
        userLabel.setLabelFor(txtUsername);
        userLabel.setDisplayedMnemonic(KeyEvent.VK_U);
        gbc.gridy = 5;
        gbc.insets = new Insets(0, 30, 14, 30);
        add(txtUsername, gbc);

        // Password with the eye toggle inside the field
        JLabel passLabel = AuthUI.label("Password");
        gbc.gridy = 6;
        gbc.insets = new Insets(0, 30, 6, 30);
        add(passLabel, gbc);

        passwordInput = new AuthUI.PasswordInput("Enter your password");
        txtPassword = passwordInput.field;
        passLabel.setLabelFor(txtPassword);
        passLabel.setDisplayedMnemonic(KeyEvent.VK_P);
        gbc.gridy = 7;
        gbc.insets = new Insets(0, 30, 4, 30);
        add(passwordInput, gbc);

        capsLock.setFont(AuthUI.FONT_SMALL);
        capsLock.setForeground(Theme.WARN.darker());
        capsLock.setVisible(false);
        gbc.gridy = 8;
        gbc.insets = new Insets(2, 32, 0, 30);
        add(capsLock, gbc);

        // Remember (left) and Forgot password (right)
        chkRemember = new JCheckBox("Keep me signed in on this computer");
        AuthUI.styleCheck(chkRemember);
        chkRemember.setMnemonic(KeyEvent.VK_K);

        btnForgot = AuthUI.link("Forgot password?");
        btnForgot.setHorizontalAlignment(SwingConstants.RIGHT);
        btnForgot.addActionListener(e -> {
            if (!busy) parentFrame.showCard("FORGOT");
        });

        JPanel options = new JPanel(new BorderLayout(12, 0));
        options.setOpaque(false);
        options.add(chkRemember, BorderLayout.WEST);
        options.add(btnForgot, BorderLayout.EAST);
        gbc.gridy = 9;
        gbc.insets = new Insets(10, 26, 18, 30);
        add(options, gbc);

        btnLogin = AuthUI.button("Sign in", 320, 46, 15, Font.BOLD);
        btnLogin.getAccessibleContext().setAccessibleDescription("Signs in to the TRI-MAX Admin Console");
        gbc.gridy = 10;
        gbc.insets = new Insets(0, 30, 16, 30);
        add(btnLogin, gbc);

        JLabel note = new JLabel("<html><div style='text-align:center;width:270px'>Need an account? "
                + "Staff accounts are created by an administrator.</div></html>", SwingConstants.CENTER);
        note.setFont(AuthUI.FONT_SMALL);
        note.setForeground(AuthUI.MUTED);
        gbc.gridy = 11;
        gbc.insets = new Insets(4, 30, 0, 30);
        add(note, gbc);

        // Keyboard: Enter moves username -> password -> sign in. Tab order follows the layout.
        txtUsername.addActionListener(e -> txtPassword.requestFocusInWindow());
        txtPassword.addActionListener(e -> doLogin());
        btnLogin.addActionListener(e -> doLogin());

        // Typing again clears the red outline of that field.
        clearInvalidOnType(txtUsername, txtUsername);
        clearInvalidOnType(txtPassword, txtPassword);

        // Caps Lock hint while typing a password.
        txtPassword.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                updateCapsLock();
            }
        });
        txtPassword.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                updateCapsLock();
            }

            @Override
            public void focusLost(FocusEvent e) {
                capsLock.setVisible(false);
            }
        });
    }

    private void clearInvalidOnType(JTextField field, JComponent marked) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (Boolean.TRUE.equals(marked.getClientProperty(AuthUI.INVALID))) AuthUI.markInvalid(marked, false);
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
    }

    private void updateCapsLock() {
        boolean on;
        try {
            on = Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK);
        } catch (UnsupportedOperationException e) {
            on = false;   // some platforms cannot tell; then the hint simply never shows
        }
        if (capsLock.isVisible() != on) {
            capsLock.setVisible(on);
            revalidate();
        }
    }

    private void doLogin() {
        if (busy) return;   // one sign-in at a time (Enter held down, double click)

        final String username = txtUsername.getText().trim();
        final char[] password = txtPassword.getPassword();

        // Same rule the server applies, checked here first so the message can point at the field.
        if (username.isEmpty() || password.length == 0) {
            AuthUI.wipe(password);
            AuthUI.markInvalid(txtUsername, username.isEmpty());
            AuthUI.markInvalid(txtPassword, password.length == 0);
            message.show(username.isEmpty() && password.length == 0 ? "Enter your username and password."
                    : username.isEmpty() ? "Enter your username." : "Enter your password.");
            (username.isEmpty() ? txtUsername : txtPassword).requestFocusInWindow();
            return;
        }

        final boolean remember = chkRemember.isSelected();
        message.clear();
        setBusy(true);
        AuthUI.async(
                () -> AuthService.login(username, password, remember),
                session -> {
                    AuthUI.wipe(password);
                    setBusy(false);
                    txtPassword.setText("");
                    parentFrame.onLoginSuccess(session);   // shows the loading screen, opens the main window
                },
                error -> {
                    AuthUI.wipe(password);
                    setBusy(false);
                    txtPassword.setText("");
                    passwordInput.setRevealed(false);
                    message.show(friendly(error));
                    if (error != null && error.startsWith("Invalid username or password")) {
                        AuthUI.markInvalid(txtUsername, true);
                        AuthUI.markInvalid(txtPassword, true);
                    }
                    txtPassword.requestFocusInWindow();
                });
    }

    /**
     * Turns technical database failures into a sentence an administrator can act on. Messages from
     * the sign-in rules (wrong password, locked, not an admin) are already written for people and
     * pass through unchanged. Nothing here reveals whether an account exists.
     */
    static String friendly(String error) {
        if (error == null || error.trim().isEmpty()) return "Sign-in failed. Please try again.";
        boolean technical = error.startsWith("Database error:") || error.startsWith("Unexpected error:");
        if (!technical) return error;
        String detail = error.substring(error.indexOf(':') + 1).trim();
        String d = detail.toLowerCase(Locale.ROOT);
        if (d.contains("not configured")) {
            return "This computer is not connected to the TRI-MAX database yet. Put the trimax.properties file "
                    + "(a filled-in copy of trimax.properties.example) in " + DatabaseConnection.userSettingsFile()
                    + " or in the same folder as the TRIMAX app, then open the app again.";
        }
        if (d.contains("password authentication failed")) {
            return "The database password in trimax.properties is not accepted. Ask the system owner for the "
                    + "current database password.";
        }
        if (d.contains("no suitable driver")) {
            return "This build is missing the PostgreSQL driver. Rebuild the app with Maven.";
        }
        if (d.contains("connect") || d.contains("timed out") || d.contains("unknownhost") || d.contains("unknown host")) {
            return "Cannot reach the TRI-MAX database. Check the internet connection and try again.";
        }
        return "Cannot sign in because of a database problem: "
                + (detail.length() > 160 ? detail.substring(0, 157) + "..." : detail);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        btnLogin.setEnabled(!busy);
        btnLogin.setText(busy ? "Signing in..." : "Sign in");
        txtUsername.setEnabled(!busy);
        passwordInput.setEnabled(!busy);
        chkRemember.setEnabled(!busy);
        btnForgot.setEnabled(!busy);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    /** Called by the frame to come back to a clean login screen (after logout or a password reset). */
    public void resetForm(String usernameToShow) {
        txtUsername.setText(usernameToShow == null ? "" : usernameToShow);
        txtPassword.setText("");
        passwordInput.setRevealed(false);
        chkRemember.setSelected(false);
        message.clear();
        AuthUI.markInvalid(txtUsername, false);
        AuthUI.markInvalid(txtPassword, false);
        if (usernameToShow == null || usernameToShow.isEmpty()) {
            txtUsername.requestFocusInWindow();
        } else {
            txtPassword.requestFocusInWindow();
        }
    }
}
