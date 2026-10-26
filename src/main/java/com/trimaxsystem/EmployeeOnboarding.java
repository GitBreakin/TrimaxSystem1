package com.trimaxsystem;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.sql.SQLException;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * New Agent/Driver: the TRIMAX account, its Supabase sign-in for the phone with a temporary password
 * ({@link TemporaryPassword}), and the email that tells the person how to sign in. The app asks for a new
 * password at the first sign-in. The temporary password is shown to the admin once and never stored.
 */
public final class EmployeeOnboarding {

    private EmployeeOnboarding() {
    }

    /** What happened, for the admin. */
    public static final class Outcome {
        public final String username, email;
        public final MobileProvisioning.Result mobile;
        /** The first phone password; null when no sign-in was made now. */
        public final String temporaryPassword;
        /** Null when the email was sent. */
        public final String emailProblem;

        Outcome(String username, String email, MobileProvisioning.Result mobile, String temporaryPassword,
                String emailProblem) {
            this.username = username;
            this.email = email;
            this.mobile = mobile;
            this.temporaryPassword = temporaryPassword;
            this.emailProblem = emailProblem;
        }
    }

    /** Creates the account and its phone sign-in. Call off the Swing thread. */
    public static Outcome createEmployee(String fullName, String email, String contact, String roleName)
            throws SQLException, AuthService.AuthException {
        String username = AccountService.createEmployee(fullName, email, contact, roleName);
        return createSignIn(username, email, fullName);
    }

    /** The phone sign-in for an account that has none ("Create mobile sign-in"). Call off the Swing thread. */
    public static Outcome createSignIn(String username, String email, String fullName) {
        String to = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String temporary = TemporaryPassword.generate(fullName);
        MobileProvisioning.Result mobile = MobileProvisioning.create(username, temporary);
        if (!mobile.ready()) return new Outcome(username, to, mobile, null, null);
        String problem = null;
        try {
            EmailService.sendTemporaryPassword(to, fullName, username, temporary);
        } catch (Exception e) {
            problem = EmailService.explain(e);
            System.err.println("Temporary password email not sent: " + problem);
        }
        return new Outcome(username, to, mobile, temporary, problem);
    }

    /** Shows the result; the temporary password can be copied from the window. */
    public static void show(Component parent, Outcome o, boolean newAccount) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        if (newAccount) p.add(line("Employee account created. Username: " + o.username));
        if (o.temporaryPassword == null) {
            p.add(line(newAccount ? "Mobile sign-in: NOT created yet." : "The mobile sign-in was not created."));
            p.add(line(o.mobile.describe()));
            p.add(Box.createVerticalStrut(6));
            p.add(line("Open the account and press \"Create mobile sign-in\" to try again."));
            JOptionPane.showMessageDialog(parent, p, newAccount ? "Account Created - mobile sign-in pending"
                    : "Create mobile sign-in", JOptionPane.WARNING_MESSAGE);
            return;
        }
        p.add(line("Mobile sign-in: ready (" + o.email + ")."));
        p.add(Box.createVerticalStrut(8));
        p.add(line("Temporary password (shown only now):"));
        JTextField field = new JTextField(o.temporaryPassword, 18);
        field.setEditable(false);
        field.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        JButton copy = UiKit.secondaryButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(o.temporaryPassword), null));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(field);
        row.add(copy);
        p.add(row);
        p.add(Box.createVerticalStrut(8));
        if (o.emailProblem == null) {
            p.add(line("It was emailed to " + o.email + "."));
        } else {
            JLabel warn = line("The email was NOT sent: " + o.emailProblem);
            warn.setForeground(Theme.DANGER);
            p.add(warn);
            p.add(line("Give the temporary password to the person yourself."));
        }
        p.add(line("On the phone: sign in with the email and this password; the app then asks for a new one."));
        JOptionPane.showMessageDialog(parent, p, newAccount ? "Account Created" : "Mobile sign-in created",
                o.emailProblem == null ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    private static JLabel line(String text) {
        JLabel l = new JLabel("<html><div style='width:420px'>" + text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;") + "</div></html>");
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        return l;
    }
}
