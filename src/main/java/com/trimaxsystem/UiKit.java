package com.trimaxsystem;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Small building blocks shared by the newer screens (buttons, labels, fields), so they look the same.
 * Everything stays keyboard-focusable.
 */
public final class UiKit {

    private UiKit() {
    }

    private static JButton base(String text, Color background, Color foreground, Color border, String flatStyle) {
        JButton b = new JButton(text);
        b.setFont(Theme.font(Font.BOLD, 13));
        b.setBackground(background);
        b.setForeground(foreground);
        if (Laf.isFlat()) {
            // Rounded, with hover/pressed colours and a focus ring drawn by FlatLaf.
            b.putClientProperty("FlatLaf.style", "arc: 10; margin: 6,16,6,16; borderWidth: 1; focusWidth: 1; "
                    + "font: bold; " + flatStyle);
        } else {
            b.setOpaque(true);
            b.setContentAreaFilled(true);
            b.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(border), new EmptyBorder(6, 14, 6, 14)));
        }
        return b;
    }

    public static JButton primaryButton(String text) {
        return base(text, Theme.BLUE, Color.WHITE, Theme.BLUE_DARK,
                "background: #2563EB; foreground: #FFFFFF; borderColor: #2563EB; hoverBackground: #1D4ED8; "
                        + "hoverBorderColor: #1D4ED8; pressedBackground: #1E40AF; focusedBorderColor: #1E40AF; "
                        + "disabledBackground: #BFD3F9; disabledText: #FFFFFF; disabledBorderColor: #BFD3F9");
    }

    public static JButton dangerButton(String text) {
        return base(text, Theme.DANGER, Color.WHITE, Theme.DANGER,
                "background: #FFFFFF; foreground: #DC2626; borderColor: #FCA5A5; hoverBackground: #FEF2F2; "
                        + "hoverBorderColor: #F87171; pressedBackground: #FEE2E2; focusedBorderColor: #DC2626");
    }

    public static JButton secondaryButton(String text) {
        return base(text, Theme.CARD, Theme.TEXT, Theme.BORDER,
                "background: #FFFFFF; foreground: #0F172A; borderColor: #CBD5E1; hoverBackground: #F1F5F9; "
                        + "pressedBackground: #E2E8F0; focusedBorderColor: #2563EB");
    }

    // =====================================================================================
    // Cards, titles, fields and tables (the redesigned pages use these)
    // =====================================================================================

    /** A white card with rounded corners and a hairline border; content inset by the given padding. */
    public static JPanel card(LayoutManager layout, int padV, int padH) {
        JPanel p = new JPanel(layout);
        p.setBackground(Theme.CARD);
        p.setBorder(cardBorder(padV, padH));
        return p;
    }

    public static javax.swing.border.Border cardBorder(int padV, int padH) {
        if (Laf.isFlat()) {
            return new com.formdev.flatlaf.ui.FlatLineBorder(new Insets(padV, padH, padV, padH), Theme.BORDER, 1f, 14);
        }
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(padV, padH, padV, padH));
    }

    /** Card title (15 px bold) with an optional muted line under it. */
    public static JComponent cardHeading(String title, String subtitle) {
        JPanel heading = new JPanel(new java.awt.BorderLayout(0, 2));
        heading.setOpaque(false);
        JLabel t = new JLabel(title);
        t.setFont(Theme.SECTION);
        t.setForeground(Theme.TEXT);
        heading.add(t, java.awt.BorderLayout.NORTH);
        if (subtitle != null) {
            JLabel sub = new JLabel(subtitle);
            sub.setFont(Theme.SMALL);
            sub.setForeground(Theme.MUTED);
            heading.add(sub, java.awt.BorderLayout.SOUTH);
        }
        return heading;
    }

    /** Grey text shown inside an empty field. */
    public static void placeholder(JTextField f, String text) {
        f.putClientProperty("JTextField.placeholderText", text);
    }

    /** A search box: magnifier at the start, a clear (x) button when it has text, and a placeholder. */
    public static void searchField(JTextField f, String placeholder) {
        styleField(f);
        placeholder(f, placeholder);
        if (Laf.isFlat()) {
            f.putClientProperty("JTextField.leadingIcon", new com.formdev.flatlaf.icons.FlatSearchIcon());
            f.putClientProperty("JTextField.showClearButton", true);
        }
    }

    /** A scroll pane for a table inside a card: no border of its own, white viewport. */
    public static JScrollPane tableScroll(JTable table) {
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Theme.CARD);
        return sp;
    }

    /** Common table look: tall rows, row lines only, soft selection, single selection. */
    public static void styleTable(JTable table, int rowHeight) {
        table.setRowHeight(rowHeight);
        table.setFont(Theme.SUBTLE);
        table.setForeground(Theme.TEXT);
        table.setBackground(Theme.CARD);
        table.setSelectionBackground(Theme.SELECTION);
        table.setSelectionForeground(Theme.TEXT);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(Theme.ROW_LINE);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(Theme.LABEL);
        table.getTableHeader().setForeground(Theme.MUTED);
        table.getTableHeader().setBackground(Theme.HEADER_BG);
        table.getTableHeader().setPreferredSize(new Dimension(100, 38));
    }

    /**
     * A table cell shown as a rounded status chip: the status text in its colour on a light tint of it.
     * The text always names the status; the colour is only a second cue.
     */
    public static final class PillRenderer extends DefaultTableCellRenderer {
        private final Function<String, Color> colorOf;
        private Color pill = Theme.SUBTLE_BG;
        private boolean selectedRow;

        public PillRenderer(Function<String, Color> colorOf) {
            this.colorOf = colorOf;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, false, row, column);
            String text = value == null ? "" : value.toString();
            Color c = text.isEmpty() ? Theme.MUTED : colorOf.apply(text);
            setText(text);
            setFont(Theme.font(Font.BOLD, 12));
            setForeground(c);
            pill = Theme.tint(c);
            selectedRow = selected;
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(new EmptyBorder(0, 22, 0, 12));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            String text = getText();
            if (text != null && !text.isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (isOpaque()) {
                    g2.setColor(getBackground());
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
                int w = Math.min(getFontMetrics(getFont()).stringWidth(text) + 20, getWidth() - 18);
                int h = 22;
                int y = (getHeight() - h) / 2;
                g2.setColor(selectedRow ? Color.WHITE : pill);
                g2.fillRoundRect(12, y, w, h, h, h);
                g2.dispose();
                setOpaque(false);
                super.paintComponent(g);
                setOpaque(true);
                return;
            }
            super.paintComponent(g);
        }
    }

    /**
     * A table cell with a small coloured dot before the text (for a second status column, so it stays
     * quieter than a pill). When {@code strong} says so, the text is bold and in the dot's colour.
     */
    public static final class DotRenderer extends DefaultTableCellRenderer {
        private final Function<String, Color> colorOf;
        private final java.util.function.Predicate<String> strong;
        private Color dot;

        public DotRenderer(Function<String, Color> colorOf, java.util.function.Predicate<String> strong) {
            this.colorOf = colorOf;
            this.strong = strong;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, false, row, column);
            String text = value == null ? "" : value.toString();
            dot = text.isEmpty() ? null : colorOf.apply(text);
            boolean emphasis = !text.isEmpty() && strong.test(text);
            setText(text);
            setFont(emphasis ? Theme.font(Font.BOLD, 13) : Theme.SUBTLE);
            setForeground(emphasis && dot != null ? dot : Theme.TEXT);
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(new EmptyBorder(0, dot == null ? 12 : 26, 0, 12));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (dot == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(dot);
            g2.fillOval(12, getHeight() / 2 - 4, 8, 8);
            g2.dispose();
        }
    }

    /**
     * Header text aligned like the cells under it (numbers on the right). Every column gets its own
     * wrapper, so the shared header renderer never keeps one column's alignment for the next.
     */
    public static void alignHeaders(JTable table, int... rightAligned) {
        javax.swing.table.TableCellRenderer base = table.getTableHeader().getDefaultRenderer();
        for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
            boolean right = false;
            for (int r : rightAligned) right |= r == i;
            final int align = right ? SwingConstants.RIGHT : SwingConstants.LEFT;
            table.getColumnModel().getColumn(i).setHeaderRenderer((t, v, sel, foc, row, col) -> {
                Component c = base.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (c instanceof JLabel) ((JLabel) c).setHorizontalAlignment(align);
                return c;
            });
        }
    }

    /** A caption above a control (filters and form fields). */
    public static JPanel group(String caption, JComponent control) {
        JPanel g = new JPanel(new java.awt.BorderLayout(0, 5));
        g.setOpaque(false);
        g.add(caption(caption), java.awt.BorderLayout.NORTH);
        g.add(control, java.awt.BorderLayout.CENTER);
        return g;
    }

    /** A plain table cell with comfortable padding; numbers can be right-aligned. */
    public static final class CellRenderer extends DefaultTableCellRenderer {
        private final int align;
        private final boolean bold;
        private final boolean muted;

        public CellRenderer(int align, boolean bold, boolean muted) {
            this.align = align;
            this.bold = bold;
            this.muted = muted;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, false, row, column);
            setFont(bold ? Theme.font(Font.BOLD, 13) : Theme.SUBTLE);
            setForeground(muted ? Theme.MUTED : Theme.TEXT);
            setHorizontalAlignment(align);
            setBorder(new EmptyBorder(0, 12, 0, 12));
            return this;
        }
    }

    /** Small grey caption placed in front of a filter control. */
    public static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.LABEL);
        l.setForeground(Theme.MUTED);
        return l;
    }

    /** A transparent left-aligned row for filter controls. */
    public static JPanel flowRow() {
        JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /**
     * A FlowLayout whose preferred height includes the lines it wraps onto. A plain FlowLayout reports the
     * height of ONE line, so in a BoxLayout the controls that wrap on a narrower window were cut off and
     * could not be reached (e.g. "Create Employee Account" on Agents & Drivers at 1280-1920 px wide).
     */
    static final class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return size(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension d = size(target, false);
            d.width -= getHgap() + 1;
            return d;
        }

        @Override
        public void layoutContainer(Container target) {
            super.layoutContainer(target);
            // The height needed depends on the width just given; ask the parent again if it changed.
            int needed = size(target, true).height;
            if (target.getHeight() > 0 && needed != target.getHeight()) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (size(target, true).height != target.getHeight()) {
                        target.invalidate();
                        Container top = target.getParent() != null ? target.getParent() : target;
                        top.revalidate();
                        top.repaint();
                    }
                });
            }
        }

        private Dimension size(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                Container c = target;
                while (c.getWidth() == 0 && c.getParent() != null) c = c.getParent();
                int width = c.getWidth() > 0 ? c.getWidth() : Integer.MAX_VALUE;
                Insets in = target.getInsets();
                int max = width - (in.left + in.right + getHgap() * 2);
                int lineWidth = 0, lineHeight = 0, totalWidth = 0, totalHeight = 0;
                for (Component m : target.getComponents()) {
                    if (!m.isVisible()) continue;
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (lineWidth > 0 && lineWidth + getHgap() + d.width > max) {
                        totalWidth = Math.max(totalWidth, lineWidth);
                        totalHeight += lineHeight + getVgap();
                        lineWidth = 0;
                        lineHeight = 0;
                    }
                    lineWidth += (lineWidth > 0 ? getHgap() : 0) + d.width;
                    lineHeight = Math.max(lineHeight, d.height);
                }
                totalWidth = Math.max(totalWidth, lineWidth);
                totalHeight += lineHeight;
                return new Dimension(totalWidth + in.left + in.right + getHgap() * 2,
                        totalHeight + in.top + in.bottom + getVgap() * 2);
            }
        }
    }

    public static void styleField(JTextField f) {
        f.setFont(Theme.SUBTLE);
        if (Laf.isFlat()) {
            f.putClientProperty("FlatLaf.style", "arc: 8; margin: 5,9,5,9");   // rounded, FlatLaf focus ring
        } else {
            f.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(4, 8, 4, 8)));
        }
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
        k.setFont(Theme.font(Font.BOLD, 13));
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
