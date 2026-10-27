package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * "Proof of delivery" in the booking window: the driver's drop-off photos as thumbnails, with who received
 * the order and when. Click a photo to see it large. The photos are private; each one is fetched with a
 * one-time ticket (MediaService), which also writes an audit row "media.view".
 */
final class ProofPhotosPanel extends JPanel {

    static final int THUMB_W = 180;
    static final int THUMB_H = 135;

    ProofPhotosPanel(List<BookingService.Proof> proofs, String deliveryStatus) {
        super(new BorderLayout());
        setOpaque(false);

        JLabel heading = new JLabel("Proof of delivery" + (proofs.size() > 1 ? "  (" + proofs.size() + " photos)" : ""));
        heading.setFont(new Font("SansSerif", Font.BOLD, 15));
        heading.setForeground(Theme.TEXT);
        heading.setBorder(new EmptyBorder(0, 0, 8, 0));
        add(heading, BorderLayout.NORTH);

        if (proofs.isEmpty()) {
            JLabel none = new JLabel(DeliveryStatus.DELIVERED.equals(deliveryStatus)
                    ? "Delivered, but the driver did not send a drop-off photo."
                    : "No drop-off photo yet. It appears here when the driver sends one.");
            none.setFont(Theme.SUBTLE);
            none.setForeground(Theme.MUTED);
            add(none, BorderLayout.CENTER);
            return;
        }

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        for (int i = 0; i < proofs.size(); i++) {
            if (i > 0) row.add(Box.createHorizontalStrut(10));
            row.add(tile(proofs.get(i)));
        }
        JScrollPane sp = new JScrollPane(row, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getHorizontalScrollBar().setUnitIncrement(24);
        add(sp, BorderLayout.CENTER);
    }

    /** One photo with its caption: "Received by Juan · Oct 27, 2:15 PM". */
    private JComponent tile(BookingService.Proof proof) {
        JLabel photo = new JLabel("Loading photo...", SwingConstants.CENTER);
        photo.setFont(Theme.SUBTLE);
        photo.setForeground(Theme.MUTED);
        photo.setOpaque(true);
        photo.setBackground(Theme.BACKGROUND);
        photo.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        photo.setPreferredSize(new Dimension(THUMB_W, THUMB_H));
        photo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        photo.setToolTipText("Click to view larger");

        JLabel who = new JLabel(proof.recipientName == null || proof.recipientName.isBlank()
                ? "Recipient not given" : "Received by " + proof.recipientName);
        who.setFont(new Font("SansSerif", Font.BOLD, 12));
        who.setForeground(Theme.TEXT);
        JLabel when = new JLabel(proof.when() == null ? "" : Theme.formatWhen(proof.when()));
        when.setToolTipText(when.getText());
        when.setFont(Theme.SUBTLE);
        when.setForeground(Theme.MUTED);

        JPanel caption = new JPanel();
        caption.setOpaque(false);
        caption.setLayout(new BoxLayout(caption, BoxLayout.Y_AXIS));
        caption.setBorder(new EmptyBorder(6, 2, 0, 2));
        caption.add(who);
        caption.add(when);

        JPanel tile = new JPanel(new BorderLayout());
        tile.setOpaque(false);
        tile.add(photo, BorderLayout.CENTER);
        tile.add(caption, BorderLayout.SOUTH);
        tile.setPreferredSize(new Dimension(THUMB_W, THUMB_H + 42));

        final BufferedImage[] loaded = {null};
        Runnable load = () -> {
            photo.setIcon(null);
            photo.setText("Loading photo...");
            new SwingWorker<BufferedImage, Void>() {
                @Override
                protected BufferedImage doInBackground() throws Exception {
                    return MediaService.load(MediaService.DELIVERY_PROOFS, proof.photoPath);
                }

                @Override
                protected void done() {
                    try {
                        loaded[0] = get();
                        photo.setText(null);
                        photo.setIcon(new ImageIcon(cover(loaded[0], THUMB_W - 2, THUMB_H - 2)));
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        photo.setText("<html><body style='text-align:center;width:" + (THUMB_W - 48) + "px'>"
                                + esc(c.getMessage() == null ? "The photo could not be loaded." : c.getMessage())
                                + "<br><u>Click to try again</u></body></html>");
                    }
                }
            }.execute();
        };
        photo.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (loaded[0] == null) {
                    load.run();
                } else {
                    showLarge(SwingUtilities.getWindowAncestor(ProofPhotosPanel.this), proof, loaded[0]);
                }
            }
        });
        BufferedImage cached = MediaService.cached(MediaService.DELIVERY_PROOFS, proof.photoPath);
        if (cached != null) {
            loaded[0] = cached;
            photo.setText(null);
            photo.setIcon(new ImageIcon(cover(cached, THUMB_W - 2, THUMB_H - 2)));
        } else {
            load.run();
        }
        return tile;
    }

    /** The photo large, with recipient, driver, time and (if sent) the place on a map. */
    private static void showLarge(Window owner, BookingService.Proof proof, BufferedImage img) {
        JDialog d = new JDialog(owner, "Proof of delivery", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBackground(Theme.BACKGROUND);
        body.setBorder(new EmptyBorder(14, 16, 14, 16));

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int maxW = Math.min(900, screen.width - 160);
        int maxH = Math.min(640, screen.height - 260);
        JLabel photo = new JLabel(new ImageIcon(fit(img, maxW, maxH)));
        photo.setHorizontalAlignment(SwingConstants.CENTER);
        body.add(photo, BorderLayout.CENTER);

        String driver = proof.driverName != null ? proof.driverName + " (" + proof.driverUsername + ")"
                : proof.driverUsername != null ? proof.driverUsername : "-";
        JLabel facts = new JLabel("<html><b>Received by:</b> " + esc(proof.recipientName == null || proof.recipientName.isBlank()
                ? "not given" : proof.recipientName)
                + " &nbsp;&nbsp; <b>Driver:</b> " + esc(driver)
                + " &nbsp;&nbsp; <b>Taken:</b> " + esc(proof.when() == null ? "-" : Theme.formatWhen(proof.when()))
                + "</html>");
        facts.setFont(Theme.SUBTLE);
        facts.setForeground(Theme.TEXT);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(facts, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton map = UiKit.secondaryButton("Open location in map");
        map.setEnabled(proof.hasGps());
        if (!proof.hasGps()) map.setToolTipText("The phone sent no GPS with this photo");
        map.addActionListener(e -> openMap(d, proof));
        JButton close = UiKit.primaryButton("Close");
        close.addActionListener(e -> d.dispose());
        buttons.add(map);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.EAST);
        body.add(bottom, BorderLayout.SOUTH);

        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setContentPane(body);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    private static void openMap(Component parent, BookingService.Proof proof) {
        String url = String.format(Locale.ROOT, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f",
                proof.latitude, proof.longitude);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to showing the link
        }
        JOptionPane.showMessageDialog(parent, url, "Location", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Scales the photo down to fit inside maxW x maxH (never up). */
    static Image fit(BufferedImage img, int maxW, int maxH) {
        double scale = Math.min(maxW / (double) img.getWidth(), maxH / (double) img.getHeight());
        if (scale >= 1) return img;
        return img.getScaledInstance((int) Math.round(img.getWidth() * scale),
                (int) Math.round(img.getHeight() * scale), Image.SCALE_SMOOTH);
    }

    /** Fills a w x h thumbnail with the middle of the photo (no empty bars). */
    static Image cover(BufferedImage img, int w, int h) {
        double scale = Math.max(w / (double) img.getWidth(), h / (double) img.getHeight());
        int sw = (int) Math.round(w / scale);
        int sh = (int) Math.round(h / scale);
        int x = Math.max(0, (img.getWidth() - sw) / 2);
        int y = Math.max(0, (img.getHeight() - sh) / 2);
        BufferedImage crop = img.getSubimage(x, y, Math.min(sw, img.getWidth() - x), Math.min(sh, img.getHeight() - y));
        return crop.getScaledInstance(w, h, Image.SCALE_SMOOTH);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
