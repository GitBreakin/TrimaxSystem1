package com.trimaxsystem;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

/**
 * Small building blocks shared by the newer screens (buttons, labels, fields), so they look the same.
 * Everything stays keyboard-focusable.
 */
public final class UiKit {

    private UiKit() {
    }

    private static JButton base(String text, Color background, Color foreground, Color border) {
        JButton b = new JButton(text);
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBackground(background);
        b.setForeground(foreground);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border), new EmptyBorder(6, 14, 6, 14)));
        return b;
    }

    public static JButton primaryButton(String text) {
        return base(text, Theme.BLUE, Color.WHITE, Theme.BLUE_DARK);
    }

    public static JButton dangerButton(String text) {
        return base(text, Theme.DANGER, Color.WHITE, Theme.DANGER);
    }

    public static JButton secondaryButton(String text) {
        return base(text, Theme.CARD, Theme.TEXT, Theme.BORDER);
    }

    /** Small grey caption placed in front of a filter control. */
    public static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", Font.BOLD, 12));
        l.setForeground(Theme.MUTED);
        return l;
    }

    /** A transparent left-aligned row for filter controls. */
    public static JPanel flowRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    public static void styleField(JTextField f) {
        f.setFont(Theme.SUBTLE);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(4, 8, 4, 8)));
    }

    /** One "label  value" line in a GridBagLayout details panel (value wraps at 300 px). */
    public static void addDetailRow(JPanel grid, int row, String label, String value) {
        addDetailRow(grid, row, label, value, 300);
    }

    /**
     * One "label  value" line. The value wraps at valueWidthPx, so long text grows downwards instead of
     * being cut off; callers pass a width that fits their panel.
     */
    public static void addDetailRow(JPanel grid, int row, String label, String value, int valueWidthPx) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = row;
        g.anchor = GridBagConstraints.NORTHWEST;
        g.insets = new Insets(3, 0, 3, 16);
        JLabel k = new JLabel(label);
        k.setFont(new Font("SansSerif", Font.BOLD, 13));
        k.setForeground(Theme.MUTED);
        g.gridx = 0;
        grid.add(k, g);

        String safe = value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        JLabel v = new JLabel("<html><body style='width:" + Math.max(120, valueWidthPx) + "px'>" + safe + "</body></html>");
        v.setFont(Theme.SUBTLE);
        v.setForeground(Theme.TEXT);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(3, 0, 3, 0);
        grid.add(v, g);
    }
}
