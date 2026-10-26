package com.trimaxsystem;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;

/**
 * Left half of the sign-in window: navy-to-blue gradient with the TRI-MAX logo, name and what the
 * Admin Console is for. Purely decorative; hidden by {@link AuthFrame} when the window is narrow.
 */
final class LoginBrandPanel extends JPanel {

    private static final Color TOP    = Color.decode("#0A2463");
    private static final Color BOTTOM = Color.decode("#1D4ED8");
    private static final Color SOFT   = Color.decode("#C7D7FE");
    private static final Color DIM    = Color.decode("#9FB3E0");
    private static final Color ACCENT = Color.decode("#60A5FA");

    LoginBrandPanel() {
        super(new GridBagLayout());
        setOpaque(true);
        getAccessibleContext().setAccessibleName("TRI-MAX Goods Trading, Admin Console");

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        column.add(left(new LogoTile()));
        column.add(Box.createVerticalStrut(28));

        JLabel name = new JLabel("TRI-MAX");
        name.setFont(Theme.BRAND.deriveFont(46f));
        name.setForeground(Color.WHITE);
        column.add(left(name));

        JLabel trading = new JLabel("Goods Trading");
        trading.setFont(Theme.BRAND.deriveFont(22f));
        trading.setForeground(SOFT);
        column.add(left(trading));
        column.add(Box.createVerticalStrut(22));

        column.add(left(new Rule()));
        column.add(Box.createVerticalStrut(22));

        JLabel console = new JLabel("Admin Console");
        console.setFont(new Font("SansSerif", Font.BOLD, 17));
        console.setForeground(Color.WHITE);
        column.add(left(console));
        column.add(Box.createVerticalStrut(6));

        JLabel blurb = new JLabel("<html><div style='width:330px'>Bookings, drivers, inventory and reports for "
                + "the whole TRI-MAX team, shared live with the mobile app.</div></html>");
        blurb.setFont(new Font("SansSerif", Font.PLAIN, 14));
        blurb.setForeground(SOFT);
        column.add(left(blurb));
        column.add(Box.createVerticalStrut(26));

        column.add(left(feature("Review and approve bookings from agents")));
        column.add(Box.createVerticalStrut(12));
        column.add(left(feature("Assign drivers and follow deliveries live")));
        column.add(Box.createVerticalStrut(12));
        column.add(left(feature("Inventory, reports and a full audit trail")));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weighty = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(40, 64, 40, 64);
        add(column, gbc);

        JLabel notice = new JLabel("Authorized personnel only. Sign-ins are recorded in the audit log.");
        notice.setFont(new Font("SansSerif", Font.PLAIN, 12));
        notice.setForeground(DIM);
        gbc.gridy = 1;
        gbc.weighty = 0;
        gbc.anchor = GridBagConstraints.SOUTHWEST;
        gbc.insets = new Insets(0, 64, 32, 32);
        add(notice, gbc);
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static JComponent feature(String text) {
        JLabel l = new JLabel(text, new CheckIcon(), SwingConstants.LEFT);
        l.setIconTextGap(12);
        l.setFont(new Font("SansSerif", Font.PLAIN, 14));
        l.setForeground(Color.WHITE);
        return l;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, TOP, w * 0.6f, h, BOTTOM));
            g2.fillRect(0, 0, w, h);
            // Two soft light circles give depth without competing with the text.
            g2.setColor(new Color(255, 255, 255, 14));
            g2.fill(new Ellipse2D.Double(w * 0.55, -h * 0.18, h * 0.62, h * 0.62));
            g2.setColor(new Color(255, 255, 255, 10));
            g2.fill(new Ellipse2D.Double(-h * 0.22, h * 0.68, h * 0.5, h * 0.5));
        } finally {
            g2.dispose();
        }
    }

    /** The logo on a white rounded tile, so the brand blues keep their contrast on navy. */
    private static final class LogoTile extends JComponent {
        private final Icon logo = BrandLogo.createIcon(100, 80);

        LogoTile() {
            Dimension d = new Dimension(132, 112);
            setPreferredSize(d);
            setMaximumSize(d);
            setMinimumSize(d);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, 40));
                g2.fill(new RoundRectangle2D.Double(2, 6, 124, 104, 24, 24));
                g2.setColor(Color.WHITE);
                g2.fill(new RoundRectangle2D.Double(0, 0, 124, 104, 24, 24));
                logo.paintIcon(this, g2, 12, 14);
            } finally {
                g2.dispose();
            }
        }
    }

    /** A short accent line between the name and the description. */
    private static final class Rule extends JComponent {
        Rule() {
            Dimension d = new Dimension(56, 4);
            setPreferredSize(d);
            setMaximumSize(d);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ACCENT);
            g2.fillRoundRect(0, 0, 56, 4, 4, 4);
            g2.dispose();
        }
    }

    private static final class CheckIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(96, 165, 250, 70));
                g2.fill(new Ellipse2D.Double(x, y, 20, 20));
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawPolyline(new int[]{x + 6, x + 9, x + 14}, new int[]{y + 10, y + 13, y + 7}, 3);
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
            return 20;
        }
    }
}
