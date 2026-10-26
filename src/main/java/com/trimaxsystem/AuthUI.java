package com.trimaxsystem;

import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.AbstractBorder;

/**
 * Shared look and behaviour for the sign-in, forgot-password and register screens: rounded fields
 * with a visible focus ring, rounded buttons with hover / pressed / disabled states, inline error
 * messages, and the background-task helper. Every control stays keyboard-reachable.
 */
final class AuthUI {

    // Surfaces
    static final Color PAGE_BG  = Theme.BACKGROUND;             // area around the card
    static final Color PANEL_BG = Color.WHITE;                   // the card itself
    // Brand
    static final Color NAVY     = Theme.NAVY;
    static final Color BLUE     = Theme.BLUE;
    static final Color BLUE_HOVER   = Theme.BLUE_DARK;
    static final Color BLUE_PRESSED = Color.decode("#1E40AF");
    static final Color BLUE_DISABLED = Color.decode("#93B4F5");
    // Text
    static final Color TITLE    = Theme.TEXT;
    static final Color TEXT     = Color.decode("#1E293B");
    static final Color MUTED    = Color.decode("#5B6B82");      // 5.3:1 on white
    static final Color BORDER   = Color.decode("#CBD5E1");
    static final Color HINT     = Color.decode("#8A99AE");
    // Feedback
    static final Color ERROR    = Color.decode("#B91C1C");      // 6.5:1 on its background
    static final Color ERROR_BG = Color.decode("#FEF2F2");
    static final Color ERROR_LINE = Color.decode("#FCA5A5");

    static final Font FONT       = new Font("SansSerif", Font.PLAIN, 14);
    static final Font FONT_SMALL = new Font("SansSerif", Font.PLAIN, 12);
    static final Font FONT_LABEL = new Font("SansSerif", Font.BOLD, 12);

    /** Set on a field (Boolean.TRUE) to draw its outline in the error colour. */
    static final String INVALID = "trimax.invalid";

    private static final int RADIUS = 10;

    private AuthUI() {
    }

    // ---------- FIELDS (rounded, focus ring, hint that disappears when you type) ----------

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

    /**
     * Rounded outline: grey normally, brand blue with a soft halo while {@code focusSource} has the
     * keyboard focus, red when the field is marked {@link #INVALID}.
     */
    static final class RoundBorder extends AbstractBorder {
        private final JComponent focusSource;
        private final Insets pad;

        RoundBorder(JComponent focusSource, Insets pad) {
            this.focusSource = focusSource;
            this.pad = pad;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            boolean focused = focusSource != null && focusSource.isFocusOwner();
            boolean invalid = focusSource != null && Boolean.TRUE.equals(focusSource.getClientProperty(INVALID));
            paintOutline(g, x, y, w, h, focused, invalid, c.isEnabled());
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(pad.top, pad.left, pad.bottom, pad.right);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(pad.top, pad.left, pad.bottom, pad.right);
            return insets;
        }
    }

    /** The rounded field outline used by every input: grey, blue + halo when focused, red when invalid. */
    static void paintOutline(Graphics g, int x, int y, int w, int h, boolean focused, boolean invalid, boolean enabled) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D inner = new RoundRectangle2D.Double(x + 1.5, y + 1.5, w - 4, h - 4, RADIUS, RADIUS);
            Color line = invalid ? ERROR : focused && enabled ? BLUE : enabled ? BORDER : Color.decode("#E2E8F0");
            if (focused) {
                g2.setColor(new Color(line.getRed(), line.getGreen(), line.getBlue(), 50));
                g2.setStroke(new BasicStroke(4f));
                g2.draw(new RoundRectangle2D.Double(x + 2, y + 2, w - 5, h - 5, RADIUS + 2, RADIUS + 2));
            }
            g2.setColor(line);
            g2.setStroke(new BasicStroke(focused || invalid ? 1.6f : 1f));
            g2.draw(inner);
        } finally {
            g2.dispose();
        }
    }

    /** Repaints {@code target} whenever {@code field} gains or loses focus (so the ring follows). */
    static void repaintOnFocus(JComponent field, JComponent target) {
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                target.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                target.repaint();
            }
        });
    }

    private static void styleField(JTextField f, String hint, int height) {
        f.setPreferredSize(new Dimension(320, Math.max(height, 42)));
        f.setMinimumSize(new Dimension(120, Math.max(height, 42)));
        f.setFont(FONT);
        f.setForeground(TEXT);
        f.setCaretColor(BLUE);
        f.setOpaque(false);
        f.setBorder(new RoundBorder(f, new Insets(0, 14, 0, 14)));
        f.getAccessibleContext().setAccessibleName(hint);
        repaintOnFocus(f, f);
    }

    static JTextField textField(String hint, int height) {
        JTextField f = new HintTextField(hint);
        styleField(f, hint, height);
        return f;
    }

    static JPasswordField passwordField(String hint, int height) {
        JPasswordField f = new HintPasswordField(hint);
        styleField(f, hint, height);
        return f;
    }

    /** Marks a field valid / invalid (red outline) and repaints it. */
    static void markInvalid(JComponent field, boolean invalid) {
        field.putClientProperty(INVALID, invalid ? Boolean.TRUE : null);
        Container p = field.getParent();
        field.repaint();
        if (p instanceof JComponent) ((JComponent) p).repaint();
    }

    /**
     * A password field with an eye button inside its right edge that shows / hides the password.
     * The outline belongs to the wrapper so the button sits inside the rounded field.
     */
    static final class PasswordInput extends JPanel {
        final JPasswordField field;
        final JButton toggle;
        private final char echo;

        PasswordInput(String hint) {
            super(new BorderLayout());
            setOpaque(false);
            field = new HintPasswordField(hint);
            field.setFont(FONT);
            field.setForeground(TEXT);
            field.setCaretColor(BLUE);
            field.setOpaque(false);
            field.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 4));
            field.getAccessibleContext().setAccessibleName(hint);
            echo = field.getEchoChar();

            toggle = new JButton(new EyeIcon(false));
            toggle.setToolTipText("Show password");
            toggle.getAccessibleContext().setAccessibleName("Show password");
            toggle.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 12));
            toggle.setContentAreaFilled(false);
            toggle.setFocusPainted(false);
            toggle.setOpaque(false);
            toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            toggle.addActionListener(e -> setRevealed(field.getEchoChar() != 0));

            add(field, BorderLayout.CENTER);
            add(toggle, BorderLayout.EAST);
            setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
            setPreferredSize(new Dimension(320, 44));
            setMinimumSize(new Dimension(120, 44));
            repaintOnFocus(field, this);
            repaintOnFocus(toggle, this);
        }

        void setRevealed(boolean revealed) {
            field.setEchoChar(revealed ? (char) 0 : echo);
            toggle.setIcon(new EyeIcon(revealed));
            String label = revealed ? "Hide password" : "Show password";
            toggle.setToolTipText(label);
            toggle.getAccessibleContext().setAccessibleName(label);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            field.setEnabled(enabled);
            toggle.setEnabled(enabled);
        }

        @Override
        protected void paintBorder(Graphics g) {
            // One outline for the field and its eye button: focused when either has the keyboard focus.
            boolean focused = field.isFocusOwner() || toggle.isFocusOwner();
            boolean invalid = Boolean.TRUE.equals(field.getClientProperty(INVALID));
            paintOutline(g, 0, 0, getWidth(), getHeight(), focused, invalid, isEnabled());
        }
    }

    /** An eye (or crossed-out eye) drawn with Java2D, so it is crisp at any scale. */
    private static final class EyeIcon implements Icon {
        private final boolean crossed;

        EyeIcon(boolean crossed) {
            this.crossed = crossed;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                g2.setColor(c != null && c.isEnabled() ? MUTED : BORDER);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                java.awt.geom.Path2D eye = new java.awt.geom.Path2D.Double();
                eye.moveTo(1, 9);
                eye.quadTo(10, -1, 19, 9);
                eye.quadTo(10, 19, 1, 9);
                g2.draw(eye);
                g2.draw(new Ellipse2D.Double(7, 6, 6, 6));
                if (crossed) g2.draw(new Line2D.Double(3, 17, 17, 1));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
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
        box.setFocusable(true);
        box.setFont(FONT_SMALL);
        box.setForeground(TEXT);
    }

    // ---------- LABELS AND BUTTONS ----------

    static JLabel label(String text) {
        JLabel l = new JLabel(text.endsWith(":") ? text.substring(0, text.length() - 1) : text);
        l.setFont(FONT_LABEL);
        l.setForeground(TEXT);
        return l;
    }

    /**
     * Rounded button. Primary (filled blue) by default; {@link #secondary(JButton)} turns it into an
     * outlined button. Hover, pressed, disabled and keyboard-focus states are all visible.
     */
    static final class RoundButton extends JButton {
        private boolean outlined;
        private boolean hover;

        RoundButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
            repaintOnFocus(this, this);
        }

        void setOutlined(boolean outlined) {
            this.outlined = outlined;
            setForeground(outlined ? BLUE : Color.WHITE);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                boolean pressed = getModel().isArmed() && getModel().isPressed();
                RoundRectangle2D shape = new RoundRectangle2D.Double(1, 1, w - 2, h - 2, RADIUS, RADIUS);
                if (isFocusOwner()) {
                    g2.setColor(new Color(37, 99, 235, 70));
                    g2.setStroke(new BasicStroke(3f));
                    g2.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, RADIUS + 2, RADIUS + 2));
                }
                if (outlined) {
                    g2.setColor(!isEnabled() ? PAGE_BG : pressed ? Color.decode("#DBE7FD") : hover ? Color.decode("#EEF4FE") : Color.WHITE);
                    g2.fill(shape);
                    g2.setColor(isEnabled() ? Color.decode("#B9CBEA") : BORDER);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(shape);
                } else {
                    Color fill = !isEnabled() ? BLUE_DISABLED : pressed ? BLUE_PRESSED : hover ? BLUE_HOVER : BLUE;
                    g2.setPaint(isEnabled() && !pressed
                            ? new GradientPaint(0, 0, fill.brighter(), 0, h, fill)
                            : new GradientPaint(0, 0, fill, 0, h, fill));
                    g2.fill(shape);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    static JButton button(String text, int width, int height, int fontSize, int fontStyle) {
        RoundButton b = new RoundButton(text);
        b.setPreferredSize(new Dimension(width, Math.max(height, 40)));
        b.setMinimumSize(new Dimension(Math.min(width, 120), Math.max(height, 40)));
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", fontStyle, fontSize));
        b.setFocusable(true);
        b.setMargin(new Insets(2, 12, 2, 12));
        return b;
    }

    /** Turns a button made by {@link #button} into the outlined (secondary) style. */
    static JButton secondary(JButton b) {
        if (b instanceof RoundButton) ((RoundButton) b).setOutlined(true);
        return b;
    }

    /** A text-only button that reads like a link (underlined on hover and when focused). */
    static JButton link(String text) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getModel().isRollover() || isFocusOwner()) {
                    FontMetrics fm = g.getFontMetrics(getFont());
                    Insets in = getInsets();
                    int textW = fm.stringWidth(getText());
                    int x = getHorizontalAlignment() == SwingConstants.RIGHT ? getWidth() - in.right - textW : in.left;
                    int y = (getHeight() + fm.getAscent()) / 2 + 1;
                    g.setColor(getForeground());
                    g.drawLine(x, y, x + textW, y);
                }
            }
        };
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setForeground(BLUE);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        repaintOnFocus(b, b);
        return b;
    }

    // ---------- INLINE MESSAGES ----------

    /**
     * A message box that sits inside the form (instead of a pop-up): wraps long text, never leaves
     * the window, and is announced to screen readers through its accessible name.
     */
    static final class MessageBox extends JPanel {
        private final JLabel text = new JLabel();

        MessageBox() {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            text.setFont(new Font("SansSerif", Font.PLAIN, 13));
            text.setForeground(AuthUI.ERROR);   // qualified: Component.ERROR is an int
            add(text, BorderLayout.CENTER);
            setVisible(false);
        }

        void show(String message) {
            String safe = message == null ? "" : message
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
            text.setText("<html><div style='width:255px'>" + safe + "</div></html>");
            getAccessibleContext().setAccessibleName(message);
            text.getAccessibleContext().setAccessibleName(message);
            setVisible(true);
            revalidate();
            repaint();
        }

        void clear() {
            setVisible(false);
            text.setText("");
            revalidate();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D shape = new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS);
                g2.setColor(ERROR_BG);
                g2.fill(shape);
                g2.setColor(ERROR_LINE);
                g2.draw(shape);
                g2.setColor(AuthUI.ERROR);
                g2.fillRoundRect(0, 0, 4, getHeight(), 4, 4);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    // ---------- DIALOGS ----------

    static void error(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, wrap(message), "Error", JOptionPane.ERROR_MESSAGE);
    }

    static void warn(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, wrap(message), "Please check", JOptionPane.WARNING_MESSAGE);
    }

    static void info(Component parent, String message, String title) {
        JOptionPane.showMessageDialog(parent, wrap(message), title, JOptionPane.INFORMATION_MESSAGE);
    }

    /** Long messages wrap at ~420 px instead of growing the dialog past the screen edge. */
    private static Object wrap(String message) {
        if (message == null || message.length() < 70) return message;
        String safe = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return "<html><div style='width:420px'>" + safe + "</div></html>";
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
