package com.trimaxsystem;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * One shared look for the non-data states of a screen: loading, empty, and error (with an optional
 * Retry button). Each state has a text icon-word as well as colour, so nothing relies on colour alone.
 * Call the show methods on the Swing thread.
 */
public final class StatePanel extends JPanel {

    private final JLabel badge = new JLabel("", SwingConstants.CENTER);
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final JLabel detail = new JLabel("", SwingConstants.CENTER);
    private final JPanel actionRow = new JPanel();

    public StatePanel() {
        super(new BorderLayout());
        setOpaque(false);
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(40, 20, 40, 20));

        badge.setFont(new Font("SansSerif", Font.BOLD, 12));
        message.setFont(new Font("SansSerif", Font.BOLD, 16));
        message.setForeground(Theme.TEXT);
        detail.setFont(Theme.SUBTLE);
        detail.setForeground(Theme.MUTED);
        actionRow.setOpaque(false);
        for (JLabel l : new JLabel[]{badge, message, detail}) l.setAlignmentX(Component.CENTER_ALIGNMENT);
        actionRow.setAlignmentX(Component.CENTER_ALIGNMENT);

        box.add(badge);
        box.add(Box.createVerticalStrut(6));
        box.add(message);
        box.add(Box.createVerticalStrut(4));
        box.add(detail);
        box.add(Box.createVerticalStrut(12));
        box.add(actionRow);
        add(box, BorderLayout.CENTER);
    }

    public void showLoading(String what) {
        set("LOADING", Theme.BLUE, what == null ? "Loading..." : what, "", null);
    }

    public void showEmpty(String title, String hint) {
        set("NOTHING HERE", Theme.MUTED, title, hint, null);
    }

    /** Shows an error with the real reason; onRetry may be null to hide the Retry button. */
    public void showError(String title, String reason, Runnable onRetry) {
        set("ERROR", Theme.DANGER, title, reason, onRetry);
    }

    private void set(String tag, java.awt.Color colour, String title, String hint, Runnable retry) {
        badge.setText("[ " + tag + " ]");
        badge.setForeground(colour);
        message.setText(title);
        detail.setText(hint == null ? "" : "<html><body style='text-align:center;width:360px'>"
                + hint.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>");
        actionRow.removeAll();
        if (retry != null) {
            JButton b = UiKit.primaryButton("Retry");
            b.addActionListener(e -> retry.run());
            actionRow.add(b);
        }
        revalidate();
        repaint();
    }
}
