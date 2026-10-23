package com.trimaxsystem;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;

final class BrandLogo {

    private static final Color BLUE = Color.decode("#2563EB");
    private static final Color BLUE_DARK = Color.decode("#1D4ED8");
    private static final Color BLUE_LIGHT = Color.decode("#3B82F6");
    private static final Color NAVY = Color.decode("#1E40AF");

    private BrandLogo() {
    }

    static Icon createIcon(int width, int height) {
        return new Icon() {
            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                try {
                    g2.translate(x, y);
                    g2.scale(width / 100.0, height / 80.0);
                    drawPath(g2, BLUE, 10, 10, 90, 10, 75, 26, 25, 26);
                    drawPath(g2, BLUE_DARK, 10, 10, 25, 26, 25, 50, 10, 35);
                    drawPath(g2, BLUE_DARK, 90, 10, 90, 35, 75, 50, 75, 26);
                    drawPath(g2, BLUE, 25, 26, 50, 55, 38, 70, 25, 50);
                    drawPath(g2, BLUE_LIGHT, 75, 26, 75, 50, 62, 70, 50, 55);
                    drawPath(g2, NAVY, 50, 26, 62, 40, 50, 55, 38, 40);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return width;
            }

            @Override
            public int getIconHeight() {
                return height;
            }
        };
    }

    static List<Image> createWindowIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 32, 64, 128}) {
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                int logoHeight = (int) (size * 0.8);
                createIcon(size, logoHeight).paintIcon(null, graphics, 0, (size - logoHeight) / 2);
            } finally {
                graphics.dispose();
            }
            icons.add(image);
        }
        return icons;
    }

    private static void drawPath(Graphics2D graphics, Color color, double... coordinates) {
        Path2D path = new Path2D.Double();
        path.moveTo(coordinates[0], coordinates[1]);
        for (int i = 2; i < coordinates.length; i += 2) {
            path.lineTo(coordinates[i], coordinates[i + 1]);
        }
        path.closePath();
        graphics.setColor(color);
        graphics.fill(path);
    }
}
