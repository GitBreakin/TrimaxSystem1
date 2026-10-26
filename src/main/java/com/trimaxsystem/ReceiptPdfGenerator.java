package com.trimaxsystem;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Receipt PDF: landscape A4 with the Cashier's copy on the left half and the Client's copy on the right
 * half, so one sheet gives both. Used for bookings and for Walk-In / In-House sales.
 *
 * The layout is compact and tries to keep every receipt on ONE page: long product names wrap (they are
 * never cut), and for longer orders the line spacing and type get a little tighter (never below 8 pt)
 * before a second page is used. Totals, discounts and payment are printed once, at the end.
 */
public class ReceiptPdfGenerator {

    /** Everything printed on the receipt. Fields without a value are left out. */
    public static class ReceiptData {
        // Bookings (the original fields; still used as they were)
        public int bookingId;
        public Timestamp date;
        public String clientId, clientName, address, contact;
        public String driverName, driverUsername, vehicleId;
        public List<BookForClientPanel.OrderItem> items;
        public BigDecimal total;
        public BigDecimal amountPaid;   // optional (null = not printed)
        public BigDecimal change;       // optional

        // Added for sales and discounts (all optional)
        public String title;            // default "BOOKING RECEIPT"
        public String numberLabel;      // default "Booking No."
        public String number;           // default the booking id
        public String typeLabel;        // e.g. "Walk-In", "In-House"
        public BigDecimal subtotal;     // before discounts; default = total + discount
        public BigDecimal discount;     // total discount (order discount, or the sum of line discounts)
        public String discountNote;     // e.g. "10%" or "by agent_ana"
        public List<BigDecimal> lineDiscounts;   // one per item, or null
        public String paymentStatus;    // "Paid" / "Unpaid"
        public String paymentMethod;    // "Cash", "GCash", ...
        public String note;
        public String voidedText;       // e.g. "VOIDED Oct 27, 2026 by owen: wrong item"
        public boolean showDelivery = true;
    }

    private static final float MARGIN = 22f;
    private static final PDFont REG = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final int MAX_NAME_LINES = 4;

    /** Type size, line spacing and gap between item rows, from roomy to compact (8 pt is the smallest). */
    private static final float[][] DENSITIES = {{9f, 11.5f, 2.5f}, {8.5f, 10.5f, 1.8f}, {8f, 9.6f, 1f}};

    /** Builds the PDF and saves it to {@code out}. */
    public static File generate(ReceiptData d, File out) throws IOException {
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()); // landscape
        float half = size.getWidth() / 2f;
        Layout layout = plan(d, half, size.getHeight());

        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < layout.pages.size(); p++) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    drawCopy(cs, 0, half, size.getHeight(), "CASHIER'S COPY", d, layout, p);
                    drawCopy(cs, half, half, size.getHeight(), "CLIENT'S COPY", d, layout, p);
                    // dashed cut line between the two copies
                    cs.setLineDashPattern(new float[]{5, 4}, 0);
                    cs.setLineWidth(0.6f);
                    cs.moveTo(half, 16);
                    cs.lineTo(half, size.getHeight() - 16);
                    cs.stroke();
                    cs.setLineDashPattern(new float[]{}, 0);
                }
            }
            doc.save(out);
        }
        return out;
    }

    /** Number of pages the receipt needs (for tests and previews). */
    public static int pageCount(ReceiptData d) throws IOException {
        PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        return plan(d, size.getWidth() / 2f, size.getHeight()).pages.size();
    }

    // ------------------------------------------------------------------------------------ planning

    private static final class Row {
        List<String> nameLines;
        String qty, price, disc, amount;
    }

    private static final class Layout {
        float fontSize, leading, gap;
        boolean discColumn;
        final List<List<Row>> pages = new ArrayList<>();
    }

    private static Layout plan(ReceiptData d, float half, float height) throws IOException {
        boolean discColumn = d.lineDiscounts != null && d.lineDiscounts.stream().anyMatch(x -> x != null && x.signum() > 0);
        float width = half - 2 * MARGIN;
        float nameWidth = width - columnsWidth(discColumn) - 8;
        float firstTop = height - topBlockHeight(d, width);
        float contTop = height - 82;                    // continuation pages: short header
        float bottom = 44;                              // signature line area
        float totals = totalsHeight(d);

        Layout best = null;
        for (float[] density : DENSITIES) {
            Layout l = new Layout();
            l.fontSize = density[0];
            l.leading = density[1];
            l.gap = density[2];
            l.discColumn = discColumn;
            List<Row> rows = rows(d, nameWidth, l.fontSize);
            float need = 0;
            for (Row r : rows) need += r.nameLines.size() * l.leading + l.gap;
            float room = firstTop - 20 - totals - bottom;   // 20 = table header
            if (need <= room) {
                l.pages.add(rows);
                return l;
            }
            best = l;
        }
        // Too long for one page even when compact: continue on more pages, totals on the last one.
        Layout l = best;
        List<Row> rows = rows(d, nameWidth, l.fontSize);
        List<Row> page = new ArrayList<>();
        float y = firstTop - 20;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float h = r.nameLines.size() * l.leading + l.gap;
            boolean last = i == rows.size() - 1;
            float limit = bottom + (last ? totals : 14);
            if (y - h < limit && !page.isEmpty()) {
                l.pages.add(page);
                page = new ArrayList<>();
                y = contTop - 20;
            }
            page.add(r);
            y -= h;
        }
        if (y < bottom + totals) {        // the totals do not fit under the last rows: own page
            l.pages.add(page);
            page = new ArrayList<>();
        }
        l.pages.add(page);
        return l;
    }

    private static float columnsWidth(boolean disc) {
        return 30 + 58 + (disc ? 52 : 0) + 64;
    }

    private static List<Row> rows(ReceiptData d, float nameWidth, float fs) throws IOException {
        List<Row> out = new ArrayList<>();
        List<BookForClientPanel.OrderItem> items = d.items == null ? List.of() : d.items;
        for (int i = 0; i < items.size(); i++) {
            BookForClientPanel.OrderItem it = items.get(i);
            Row r = new Row();
            List<String> lines = wrap(REG, fs, it.productName, nameWidth);
            if (lines.size() > MAX_NAME_LINES) {
                lines = new ArrayList<>(lines.subList(0, MAX_NAME_LINES));
            }
            r.nameLines = lines.isEmpty() ? List.of("") : lines;
            r.qty = String.valueOf(it.amount);
            r.price = MONEY.format(it.price);
            BigDecimal disc = d.lineDiscounts != null && i < d.lineDiscounts.size() ? d.lineDiscounts.get(i) : null;
            r.disc = disc != null && disc.signum() > 0 ? "-" + MONEY.format(disc) : "";
            double amount = it.subtotal - (disc == null ? 0 : disc.doubleValue());
            r.amount = MONEY.format(amount);
            out.add(r);
        }
        return out;
    }

    /** Height of the header, numbers and customer/delivery block on the first page. */
    private static float topBlockHeight(ReceiptData d, float width) throws IOException {
        float h = 36 + 13 + 6 + 11;           // top margin and brand line, title, rule
        h += 11 * 2;                          // number/date, type/payment
        if (d.voidedText != null) h += 11;
        h += 11 * customerLines(d, width).size();
        if (d.showDelivery) h += 11;
        return h + 8;
    }

    private static float totalsHeight(ReceiptData d) {
        float h = 10 + 13;                                       // rule + TOTAL
        if (discount(d).signum() > 0) h += 11 * 2;               // subtotal + discount
        if (d.paymentStatus != null || d.amountPaid != null) h += 11;
        if (d.amountPaid != null) h += 11;
        if (d.note != null && !d.note.isEmpty()) h += 11;
        return h + 6;
    }

    private static BigDecimal discount(ReceiptData d) {
        return d.discount == null ? BigDecimal.ZERO : d.discount;
    }

    private static List<String> customerLines(ReceiptData d, float width) throws IOException {
        List<String> out = new ArrayList<>();
        String name = blank(d.clientName) ? null : d.clientName
                + (blank(d.contact) || "-".equals(d.contact) ? "" : "  ·  " + d.contact);
        if (name != null) out.addAll(limit(wrap(REG, 8.5f, "Customer: " + name, width), 2));
        if (!blank(d.address) && !"-".equals(d.address)) out.addAll(limit(wrap(REG, 8.5f, "Address: " + d.address, width), 2));
        return out;
    }

    // ------------------------------------------------------------------------------------ drawing

    private static void drawCopy(PDPageContentStream cs, float x0, float width, float height, String copyLabel,
                                 ReceiptData d, Layout l, int pageIndex) throws IOException {
        float left = x0 + MARGIN;
        float right = x0 + width - MARGIN;
        float inner = right - left;
        int pageCount = l.pages.size();
        boolean first = pageIndex == 0, last = pageIndex == pageCount - 1;
        float y = height - 26 - 10;

        // ----- header
        text(cs, BOLD, 13, left, y, "TRI-MAX Goods Trading");
        textRight(cs, BOLD, 8, right, y + 2, copyLabel);
        y -= 13;
        String title = d.title == null ? "BOOKING RECEIPT" : d.title;
        text(cs, BOLD, 8.5f, left, y, title + (d.typeLabel == null ? "" : "  ·  " + d.typeLabel.toUpperCase()));
        if (pageCount > 1) textRight(cs, REG, 7.5f, right, y, "Page " + (pageIndex + 1) + " of " + pageCount);
        y -= 6;
        line(cs, left, right, y);
        y -= 11;

        String number = d.number != null ? d.number : String.valueOf(d.bookingId);
        String numberLabel = d.numberLabel != null ? d.numberLabel : "Booking No.";
        if (first) {
            text(cs, BOLD, 8.5f, left, y, numberLabel + " " + number);
            textRight(cs, REG, 8.5f, right, y, "Date: " + when(d.date));
            y -= 11;
            String pay = d.paymentStatus == null ? null
                    : d.paymentStatus + (blank(d.paymentMethod) ? "" : " · " + d.paymentMethod);
            text(cs, REG, 8.5f, left, y, pay == null ? "" : "Payment: " + pay);
            if (!blank(d.clientId)) textRight(cs, REG, 8.5f, right, y, "Customer ID: " + d.clientId);
            y -= 11;
            if (d.voidedText != null) {
                text(cs, BOLD, 8.5f, left, y, truncate(BOLD, 8.5f, d.voidedText, inner));
                y -= 11;
            }
            for (String s : customerLines(d, inner)) {
                text(cs, REG, 8.5f, left, y, s);
                y -= 11;
            }
            if (d.showDelivery) {
                String driver = blank(d.driverName) ? "Unassigned" : d.driverName
                        + (blank(d.driverUsername) || d.driverUsername.equals(d.driverName) ? "" : " (" + d.driverUsername + ")");
                text(cs, REG, 8.5f, left, y, truncate(REG, 8.5f, "Delivery: " + driver
                        + (blank(d.vehicleId) ? "" : "  ·  Vehicle " + d.vehicleId), inner));
                y -= 11;
            }
            y -= 8;
        } else {
            text(cs, REG, 8.5f, left, y, numberLabel + " " + number + " (continued)");
            y -= 16;
        }

        // ----- items
        float fs = l.fontSize, ld = l.leading;
        float colAmount = right, colDisc = right - 64, colPrice = colDisc - (l.discColumn ? 52 : 0),
                colQty = colPrice - 58;
        line(cs, left, right, y + 9);
        text(cs, BOLD, 8, left, y, "Item");
        textRight(cs, BOLD, 8, colQty, y, "Qty");
        textRight(cs, BOLD, 8, colPrice, y, "Price");
        if (l.discColumn) textRight(cs, BOLD, 8, colDisc, y, "Disc.");
        textRight(cs, BOLD, 8, colAmount, y, "Amount");
        y -= 4;
        line(cs, left, right, y);
        y -= ld - 1;
        for (Row r : l.pages.get(pageIndex)) {
            for (int i = 0; i < r.nameLines.size(); i++) {
                text(cs, REG, fs, left, y, r.nameLines.get(i));
                if (i == 0) {
                    textRight(cs, REG, fs, colQty, y, r.qty);
                    textRight(cs, REG, fs, colPrice, y, r.price);
                    if (l.discColumn) textRight(cs, REG, fs, colDisc, y, r.disc);
                    textRight(cs, REG, fs, colAmount, y, r.amount);
                }
                y -= ld;
            }
            y -= l.gap;
        }

        // ----- totals (last page only)
        if (last) {
            y += 2;
            line(cs, left, right, y);
            y -= 11;
            float labelX = right - 150;
            BigDecimal disc = discount(d);
            BigDecimal total = d.total == null ? BigDecimal.ZERO : d.total;
            if (disc.signum() > 0) {
                BigDecimal sub = d.subtotal != null ? d.subtotal : total.add(disc);
                text(cs, REG, 8.5f, labelX, y, "Subtotal");
                textRight(cs, REG, 8.5f, right, y, "PHP " + MONEY.format(sub));
                y -= 11;
                text(cs, REG, 8.5f, labelX, y, "Discount" + (blank(d.discountNote) ? "" : " (" + d.discountNote + ")"));
                textRight(cs, REG, 8.5f, right, y, "-PHP " + MONEY.format(disc));
                y -= 11;
            }
            text(cs, BOLD, 11, labelX, y - 1, "TOTAL");
            textRight(cs, BOLD, 11, right, y - 1, "PHP " + MONEY.format(total));
            y -= 14;
            if (d.amountPaid != null) {
                text(cs, REG, 8.5f, labelX, y, "Amount received");
                textRight(cs, REG, 8.5f, right, y, "PHP " + MONEY.format(d.amountPaid));
                y -= 11;
                text(cs, REG, 8.5f, labelX, y, "Change");
                textRight(cs, REG, 8.5f, right, y, "PHP " + MONEY.format(d.change == null ? BigDecimal.ZERO : d.change));
                y -= 11;
            }
            if ("Unpaid".equalsIgnoreCase(d.paymentStatus)) {
                text(cs, BOLD, 8.5f, left, y, "UNPAID: balance due PHP " + MONEY.format(total));
                y -= 11;
            }
            if (!blank(d.note)) {
                text(cs, REG, 8, left, y, truncate(REG, 8, "Note: " + d.note, inner));
            }
        }

        // ----- signature
        float sy = 30;
        cs.setLineWidth(0.6f);
        cs.moveTo(left, sy + 9);
        cs.lineTo(left + 150, sy + 9);
        cs.stroke();
        text(cs, REG, 7.5f, left, sy, copyLabel.startsWith("CASHIER") ? "Cashier / Admin signature" : "Received by (customer)");
        textRight(cs, REG, 7.5f, right, sy, "Thank you!");
    }

    // ---------- drawing helpers ----------
    private static void text(PDPageContentStream cs, PDFont f, float size, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(f, size);
        cs.newLineAtOffset(x, y);
        cs.showText(clean(s));
        cs.endText();
    }

    private static void textRight(PDPageContentStream cs, PDFont f, float size, float xRight, float y, String s) throws IOException {
        s = clean(s);
        text(cs, f, size, xRight - width(f, size, s), y, s);
    }

    private static void line(PDPageContentStream cs, float x1, float x2, float y) throws IOException {
        cs.setLineWidth(0.6f);
        cs.moveTo(x1, y);
        cs.lineTo(x2, y);
        cs.stroke();
    }

    private static float width(PDFont f, float size, String s) throws IOException {
        return f.getStringWidth(clean(s)) / 1000f * size;
    }

    /** Word wrap to the width; a word longer than the width is broken. */
    static List<String> wrap(PDFont f, float size, String s, float maxWidth) throws IOException {
        List<String> out = new ArrayList<>();
        String text = clean(s).trim();
        if (text.isEmpty()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (width(f, size, candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            String w = word;
            while (width(f, size, w) > maxWidth && w.length() > 1) {
                int cut = w.length() - 1;
                while (cut > 1 && width(f, size, w.substring(0, cut)) > maxWidth) cut--;
                out.add(w.substring(0, cut));
                w = w.substring(cut);
            }
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static List<String> limit(List<String> lines, int max) {
        if (lines.size() <= max) return lines;
        List<String> out = new ArrayList<>(lines.subList(0, max));
        out.set(max - 1, out.get(max - 1) + "...");
        return out;
    }

    private static String truncate(PDFont f, float size, String s, float maxWidth) throws IOException {
        s = clean(s);
        if (width(f, size, s) <= maxWidth) return s;
        while (s.length() > 1 && width(f, size, s + "...") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String when(Timestamp ts) {
        if (ts == null) return "";
        SimpleDateFormat f = new SimpleDateFormat("MMM dd, yyyy HH:mm");
        f.setTimeZone(TimeZone.getTimeZone(Theme.BUSINESS_TZ));
        return f.format(ts);
    }

    /** Standard PDF fonts can't draw every character; replace unsupported ones. */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '₱') sb.append("PHP ");
            else if (c == '—' || c == '–') sb.append('-');
            else if (c == '·') sb.append('·');
            else if (c == '→') sb.append("->");
            else sb.append((c >= 32 && c <= 126) || (c >= 160 && c <= 255) ? c : '?');
        }
        return sb.toString();
    }
}
