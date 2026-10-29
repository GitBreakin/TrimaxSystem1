package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * "Visit photo" in the booking window: the photo the agent took at the customer's place when the order was
 * taken by a visit (sql/20261029_02). Click it to see it large, with the agent, the time and the place on a
 * map. Private like the other photos: fetched with a one-time ticket (MediaService), audited as "media.view".
 */
final class VisitPhotoPanel extends JPanel {

    VisitPhotoPanel(BookingService.Detail d) {
        super(new BorderLayout());
        setOpaque(false);

        JLabel heading = new JLabel("Visit photo");
        heading.setFont(new Font("SansSerif", Font.BOLD, 15));
        heading.setForeground(Theme.TEXT);
        heading.setBorder(new EmptyBorder(0, 0, 8, 0));
        add(heading, BorderLayout.NORTH);

        int w = ProofPhotosPanel.THUMB_W, h = ProofPhotosPanel.THUMB_H;
        JLabel photo = new JLabel("Loading photo...", SwingConstants.CENTER);
        photo.setFont(Theme.SUBTLE);
        photo.setForeground(Theme.MUTED);
        photo.setOpaque(true);
        photo.setBackground(Theme.BACKGROUND);
        photo.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        photo.setPreferredSize(new Dimension(w, h));
        photo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        photo.setToolTipText("Click to view larger");

        JLabel who = new JLabel(d.createdBy == null ? "Taken by the agent" : "Taken by " + d.createdBy);
        who.setFont(new Font("SansSerif", Font.BOLD, 12));
        who.setForeground(Theme.TEXT);
        JLabel when = new JLabel((d.visitTakenAt == null ? "" : Theme.formatWhen(d.visitTakenAt))
                + (d.visitHasGps() ? "" : (d.visitTakenAt == null ? "" : " · ") + "no location"));
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
        tile.setPreferredSize(new Dimension(w, h + 42));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(tile);
        add(left, BorderLayout.CENTER);

        final BufferedImage[] loaded = {null};
        Runnable load = () -> {
            photo.setIcon(null);
            photo.setText("Loading photo...");
            new SwingWorker<BufferedImage, Void>() {
                @Override
                protected BufferedImage doInBackground() throws Exception {
                    return MediaService.load(MediaService.CUSTOMER_PHOTOS, d.visitPhotoPath);
                }

                @Override
                protected void done() {
                    try {
                        loaded[0] = get();
                        photo.setText(null);
                        photo.setIcon(new ImageIcon(ProofPhotosPanel.cover(loaded[0], w - 2, h - 2)));
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        photo.setText("<html><body style='text-align:center;width:" + (w - 48) + "px'>"
                                + ProofPhotosPanel.esc(c.getMessage() == null ? "The photo could not be loaded." : c.getMessage())
                                + "<br><u>Click to try again</u></body></html>");
                    }
                }
            }.execute();
        };
        photo.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (loaded[0] == null) load.run();
                else showLarge(SwingUtilities.getWindowAncestor(VisitPhotoPanel.this), d, loaded[0]);
            }
        });
        BufferedImage cached = MediaService.cached(MediaService.CUSTOMER_PHOTOS, d.visitPhotoPath);
        if (cached != null) {
            loaded[0] = cached;
            photo.setText(null);
            photo.setIcon(new ImageIcon(ProofPhotosPanel.cover(cached, w - 2, h - 2)));
        } else {
            load.run();
        }
    }

    /** The photo large, with the agent, customer, time and (if sent) the place on a map. */
    static void showLarge(Window owner, BookingService.Detail d, BufferedImage img) {
        JDialog dlg = new JDialog(owner, "Visit photo - Booking #" + d.bookingId, Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBackground(Theme.BACKGROUND);
        body.setBorder(new EmptyBorder(14, 16, 14, 16));

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int maxW = Math.min(900, screen.width - 160);
        int maxH = Math.min(640, screen.height - 260);
        JLabel photo = new JLabel(new ImageIcon(ProofPhotosPanel.fit(img, maxW, maxH)));
        photo.setHorizontalAlignment(SwingConstants.CENTER);
        body.add(photo, BorderLayout.CENTER);

        JLabel facts = new JLabel("<html><b>Agent:</b> " + ProofPhotosPanel.esc(d.createdBy == null ? "-" : d.createdBy)
                + " &nbsp;&nbsp; <b>Customer:</b> " + ProofPhotosPanel.esc(d.clientName == null ? "-" : d.clientName)
                + " &nbsp;&nbsp; <b>Taken:</b> " + ProofPhotosPanel.esc(d.visitTakenAt == null ? "-" : Theme.formatWhen(d.visitTakenAt))
                + "</html>");
        facts.setFont(Theme.SUBTLE);
        facts.setForeground(Theme.TEXT);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(facts, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton map = UiKit.secondaryButton("Open location in map");
        map.setEnabled(d.visitHasGps());
        if (!d.visitHasGps()) map.setToolTipText("The phone had no location when the photo was taken");
        map.addActionListener(e -> ProofPhotosPanel.openMap(dlg, d.visitLatitude, d.visitLongitude));
        JButton close = UiKit.primaryButton("Close");
        close.addActionListener(e -> dlg.dispose());
        buttons.add(map);
        buttons.add(close);
        bottom.add(buttons, BorderLayout.EAST);
        body.add(bottom, BorderLayout.SOUTH);

        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dlg.setContentPane(body);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }
}
