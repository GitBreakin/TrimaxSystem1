package com.trimaxsystem;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.*;

/** Pieces shared by the Dashboard and the Sales & Profit page. */
final class ProfitWidgets {

    private ProfitWidgets() {
    }

    /**
     * Five cards: Today, Yesterday, Last 7 days, This month, This year, each with net sales, gross
     * profit and net profit. A click on a card calls {@code onPick} with its name.
     */
    static final class PeriodStrip extends JPanel {
        private final Map<String, JLabel[]> cells = new java.util.LinkedHashMap<>();
        private final JLabel status = new JLabel(" ");
        private int seq;

        PeriodStrip(Consumer<String> onPick) {
            super(new BorderLayout(0, 6));
            setOpaque(false);
            JPanel grid = new JPanel(new GridLayout(1, 5, 10, 0));
            grid.setOpaque(false);
            for (String name : ProfitService.presets(LocalDate.now(Theme.BUSINESS_TZ)).keySet()) {
                JLabel sales = value(17, true), gross = value(12, false), net = value(12, true), hint = new JLabel(" ");
                hint.setFont(Theme.SMALL);
                hint.setForeground(Theme.MUTED);
                JPanel card = UiKit.card(new GridLayout(5, 1, 0, 1), 10, 12);
                JLabel title = UiKit.caption(name.toUpperCase());
                card.add(title);
                card.add(sales);
                card.add(gross);
                card.add(net);
                card.add(hint);
                card.setToolTipText("Net sales, gross profit and net profit for " + name.toLowerCase()
                        + (onPick == null ? "" : ". Click for the details."));
                if (onPick != null) {
                    card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    card.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseClicked(MouseEvent e) {
                            onPick.accept(name);
                        }
                    });
                }
                cells.put(name, new JLabel[]{sales, gross, net, hint});
                grid.add(card);
            }
            add(grid, BorderLayout.CENTER);
            status.setFont(Theme.SMALL);
            status.setForeground(Theme.MUTED);
            add(status, BorderLayout.SOUTH);
        }

        private static JLabel value(int size, boolean bold) {
            JLabel l = new JLabel("—");
            l.setFont(Theme.font(bold ? Font.BOLD : Font.PLAIN, size));
            l.setForeground(Theme.TEXT);
            return l;
        }

        void reload() {
            final int mySeq = ++seq;
            final LocalDate today = LocalDate.now(Theme.BUSINESS_TZ);
            new SwingWorker<Map<String, ProfitService.Summary>, Void>() {
                @Override
                protected Map<String, ProfitService.Summary> doInBackground() throws Exception {
                    if (!Db.salesReady()) throw new Db.Failure(Db.notReadyMessage("Sales and profit"));
                    return ProfitService.periods(today);
                }

                @Override
                protected void done() {
                    if (mySeq != seq) return;
                    try {
                        Map<String, ProfitService.Summary> m = get();
                        for (Map.Entry<String, ProfitService.Summary> e : m.entrySet()) {
                            JLabel[] c = cells.get(e.getKey());
                            ProfitService.Summary s = e.getValue();
                            c[0].setText(Money.peso(s.revenueNet));
                            c[1].setText("Gross profit " + Money.peso(s.grossProfit));
                            c[2].setText("Net profit " + Money.peso(s.netProfit));
                            c[2].setForeground(s.netProfit.signum() < 0 ? Theme.DANGER : Theme.OK);
                            c[3].setText(s.bookingsCount + " booking(s) · " + s.salesCount + " sale(s)");
                        }
                        status.setForeground(Theme.MUTED);
                        status.setText("Net sales = completed bookings + paid Walk-In / In-House sales, after discounts. "
                                + "Net profit = gross profit − payroll.");
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        for (JLabel[] l : cells.values()) {
                            l[0].setText("—");
                            l[1].setText(" ");
                            l[2].setText(" ");
                            l[3].setText(" ");
                        }
                        status.setForeground(Theme.DANGER);
                        status.setText(c.getMessage());
                    }
                }
            }.execute();
        }
    }

    /** Bars = net sales per day (or month); line = net profit. */
    static final class TrendChart extends JComponent {
        private List<ProfitService.Point> points = new ArrayList<>();
        private boolean monthly;
        private String message = "Choose a period";

        TrendChart() {
            setPreferredSize(new Dimension(600, 240));
            setFont(Theme.SMALL);
        }

        void setData(List<ProfitService.Point> points, boolean monthly) {
            this.points = points;
            this.monthly = monthly;
            this.message = null;
            repaint();
        }

        void setMessage(String message) {
            this.message = message;
            this.points = new ArrayList<>();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(Theme.CARD);
            g.fillRect(0, 0, w, h);
            g.setFont(Theme.SMALL);
            FontMetrics fm = g.getFontMetrics();
            boolean allZero = points.stream().allMatch(p -> p.revenue.signum() == 0 && p.netProfit.signum() == 0);
            if (message != null || points.isEmpty() || allZero) {
                g.setColor(Theme.MUTED);
                String m = message != null ? message : "No sales, costs or payroll in this period";
                g.drawString(m, (w - fm.stringWidth(m)) / 2, h / 2);
                g.dispose();
                return;
            }
            double max = 0, min = 0;
            for (ProfitService.Point p : points) {
                max = Math.max(max, Math.max(p.revenue.doubleValue(), p.netProfit.doubleValue()));
                min = Math.min(min, p.netProfit.doubleValue());
            }
            if (max == min) max = min + 1;
            double span = niceStep((max - min) / 4);
            max = Math.ceil(max / span) * span;
            min = Math.floor(min / span) * span;
            int left = 70, right = 16, top = 26, bottom = 30;
            int plotW = w - left - right, plotH = h - top - bottom;
            // grid + y labels
            g.setColor(Theme.ROW_LINE);
            for (double v = min; v <= max + span / 2; v += span) {
                int y = (int) Math.round(top + plotH - (v - min) / (max - min) * plotH);
                g.setColor(Math.abs(v) < span / 1000 ? Theme.BORDER : Theme.ROW_LINE);
                g.drawLine(left, y, w - right, y);
                g.setColor(Theme.MUTED);
                String label = shortMoney(v);
                g.drawString(label, left - 8 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
            }
            int n = points.size();
            double slot = plotW / (double) n;
            int barW = (int) Math.max(2, Math.min(36, slot * 0.6));
            int zeroY = (int) Math.round(top + plotH - (0 - min) / (max - min) * plotH);
            // bars: net sales
            for (int i = 0; i < n; i++) {
                ProfitService.Point p = points.get(i);
                int x = (int) Math.round(left + slot * i + (slot - barW) / 2);
                int y = (int) Math.round(top + plotH - (p.revenue.doubleValue() - min) / (max - min) * plotH);
                g.setColor(new Color(0x93C5FD));
                g.fillRoundRect(x, Math.min(y, zeroY), barW, Math.abs(zeroY - y), 4, 4);
            }
            // line: net profit
            Path2D path = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                double x = left + slot * i + slot / 2;
                double y = top + plotH - (points.get(i).netProfit.doubleValue() - min) / (max - min) * plotH;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            g.setColor(Theme.NAVY);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(path);
            for (int i = 0; i < n; i++) {
                double v = points.get(i).netProfit.doubleValue();
                int x = (int) Math.round(left + slot * i + slot / 2);
                int y = (int) Math.round(top + plotH - (v - min) / (max - min) * plotH);
                g.setColor(v < 0 ? Theme.DANGER : Theme.NAVY);
                g.fillOval(x - 3, y - 3, 6, 6);
            }
            // x labels
            g.setColor(Theme.MUTED);
            DateTimeFormatter f = DateTimeFormatter.ofPattern(monthly ? "MMM yyyy" : "MMM d");
            int every = Math.max(1, (int) Math.ceil(n / Math.max(1.0, plotW / 70.0)));
            for (int i = 0; i < n; i += every) {
                String s = f.format(points.get(i).bucket);
                int x = (int) Math.round(left + slot * i + slot / 2 - fm.stringWidth(s) / 2.0);
                g.drawString(s, x, h - 10);
            }
            // legend
            int lx = w - right - 250;
            g.setColor(new Color(0x93C5FD));
            g.fillRect(lx, 8, 12, 10);
            g.setColor(Theme.TEXT);
            g.drawString("Net sales", lx + 16, 17);
            g.setColor(Theme.NAVY);
            g.fillOval(lx + 100, 9, 8, 8);
            g.drawLine(lx + 94, 13, lx + 114, 13);
            g.setColor(Theme.TEXT);
            g.drawString("Net profit (after payroll)", lx + 120, 17);
            g.dispose();
        }

        private static double niceStep(double raw) {
            if (raw <= 0) return 1;
            double exp = Math.pow(10, Math.floor(Math.log10(raw)));
            double f = raw / exp;
            double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
            return nice * exp;
        }

        static String shortMoney(double v) {
            double a = Math.abs(v);
            String s;
            if (a >= 1_000_000) s = trim(a / 1_000_000) + "M";
            else if (a >= 1_000) s = trim(a / 1_000) + "k";
            else s = trim(a);
            return (v < 0 ? "−" : "") + "₱" + s;
        }

        private static String trim(double v) {
            String s = String.format(java.util.Locale.ROOT, "%.1f", v);
            return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
        }
    }

    /** A labelled metric card for the Sales & Profit page. */
    static JPanel metric(String title, JLabel value, JLabel sub) {
        JPanel c = UiKit.card(new BorderLayout(0, 3), 12, 14);
        c.add(UiKit.caption(title.toUpperCase()), BorderLayout.NORTH);
        value.setFont(Theme.font(Font.BOLD, 20));
        value.setForeground(Theme.TEXT);
        c.add(value, BorderLayout.CENTER);
        sub.setFont(Theme.SMALL);
        sub.setForeground(Theme.MUTED);
        c.add(sub, BorderLayout.SOUTH);
        return c;
    }

    static JLabel label() {
        return new JLabel(" ");
    }

    static String pct(BigDecimal v) {
        return v == null ? "—" : v.toPlainString() + "%";
    }
}
