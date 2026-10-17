package com.trimaxsystem;

import java.awt.*;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.Border;

/** Small shared helpers so the login, register and forgot-password screens look and behave the same. */
final class AuthUI {

    static final Color PANEL_BG = Color.decode("#F4F6F8");
    static final Color BLUE     = Color.decode("#2563EB");
    static final Color TITLE    = Color.decode("#0F172A");
    static final Color TEXT     = Color.decode("#1E293B");
    static final Color MUTED    = Color.decode("#64748B");
    static final Color BORDER   = Color.decode("#CBD5E1");
    static final Color HINT     = Color.decode("#94A3B8");

    private AuthUI() {
    }

    // ---------- FIELDS (with a hint that disappears when you type) ----------

    private static void paintHint(Graphics g, JComponent c, String hint) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(HINT);
            g2.setFont(c.getFont());
            Insets in = c.getInsets();
            FontMetrics fm = g2.getFontMetrics();
            int y = (c.getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(hint, in.left, y);
        } finally {
            g2.dispose();
        }
    }

    private static class HintTextField extends JTextField {
        private final String hint;

        HintTextField(String hint) {
            this.hint = hint;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) paintHint(g, this, hint);
        }
    }

    private static class HintPasswordField extends JPasswordField {
        private final String hint;

        HintPasswordField(String hint) {
            this.hint = hint;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getPassword().length == 0) paintHint(g, this, hint);
        }
    }

    private static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(0, 10, 0, 10));
    }

    static JTextField textField(String hint, int height) {
        JTextField f = new HintTextField(hint);
        f.setPreferredSize(new Dimension(320, height));
        f.setFont(new Font("SansSerif", Font.PLAIN, 13));
        f.setForeground(TEXT);
        f.setBorder(fieldBorder());
        return f;
    }

    static JPasswordField passwordField(String hint, int height) {
        JPasswordField f = new HintPasswordField(hint);
        f.setPreferredSize(new Dimension(320, height));
        f.setFont(new Font("SansSerif", Font.PLAIN, 13));
        f.setForeground(TEXT);
        f.setBorder(fieldBorder());
        return f;
    }

    /** A "Show password" checkbox that reveals / hides the given password fields. */
    static JCheckBox showPasswordCheck(JPasswordField... fields) {
        JCheckBox box = new JCheckBox("Show password");
        styleCheck(box);
        final char[] hidden = new char[fields.length];
        for (int i = 0; i < fields.length; i++) {
            hidden[i] = fields[i].getEchoChar();
        }
        box.addActionListener(e -> {
            for (int i = 0; i < fields.length; i++) {
                fields[i].setEchoChar(box.isSelected() ? (char) 0 : hidden[i]);
            }
        });
        return box;
    }

    static void styleCheck(JCheckBox box) {
        box.setOpaque(false);
        box.setFocusable(false);
        box.setFont(new Font("SansSerif", Font.PLAIN, 12));
        box.setForeground(TEXT);
    }

    // ---------- LABELS AND BUTTONS ----------

    static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.PLAIN, 12));
        l.setForeground(TEXT);
        return l;
    }

    static JButton button(String text, int width, int height, int fontSize, int fontStyle) {
        JButton b = new JButton(text);
        b.setPreferredSize(new Dimension(width, height));
        b.setBackground(BLUE);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", fontStyle, fontSize));
        b.setFocusable(false);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorder(BorderFactory.createLineBorder(Color.decode("#1E293B")));
        b.setMargin(new Insets(2, 2, 2, 2));
        return b;
    }

    // ---------- DIALOGS ----------

    static void error(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    static void warn(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Please check", JOptionPane.WARNING_MESSAGE);
    }

    static void info(Component parent, String message, String title) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    // ---------- BACKGROUND WORK ----------

    interface Task<T> {
        T run() throws Exception;
    }

    /**
     * Runs a database task off the screen thread (so the window never freezes), then calls
     * onSuccess or onError back on the screen thread.
     */
    static <T> void async(Task<T> task, Consumer<T> onSuccess, Consumer<String> onError) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.run();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    onError.accept(cause instanceof AuthService.AuthException
                            ? cause.getMessage()
                            : "Unexpected error: " + cause.getMessage());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    onError.accept("The operation was interrupted.");
                }
            }
        }.execute();
    }

    /** Overwrites password characters in memory once they are no longer needed. */
    static void wipe(char[]... arrays) {
        for (char[] a : arrays) {
            if (a != null) Arrays.fill(a, '\0');
        }
    }
}