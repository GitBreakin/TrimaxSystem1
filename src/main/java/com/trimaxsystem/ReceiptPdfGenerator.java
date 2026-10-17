package com.trimaxsystem;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Creates a receipt PDF: ONE landscape A4 page per set of items, with the
 * Cashier's Copy on the left and the Client's Copy on the right.
 * (If an order has more than ROWS_PER_PAGE items it continues on another page.)
 *
 * Needs the Apache PDFBox 2.0.x library.
 * Formatting is intentionally basic: adjust positions/fonts in drawCopy().
 */
public class ReceiptPdfGenerator {

    /** Everything printed on the receipt. */
    public static class ReceiptData {
        public int bookingId;
        public Timestamp date;
        public String clientId, clientName, address, contact;
        public String driverName, driverUsername, vehicleId;
        public List<BookForClientPanel.OrderItem> items;
        public BigDecimal total;
        public BigDecimal amountPaid;   // optional (null = not printed)
        public BigDecimal change;       // optional
    }

    private static final int ROWS_PER_PAGE = 16;
    private static final float MARGIN = 24f;
    private static final float LEADING = 14f;
    private static final PDFont REG  = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    /** Builds the PDF and saves it to {@code out}. */
    public static File generate(ReceiptData d, File out) throws IOException {
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }

        PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()); // landscape
        int pageCount = Math.max(1, (int) Math.ceil(d.items.size() / (double) ROWS_PER_PAGE));

        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < pageCount; p++) {
                PDPage page = new PDPage(size);
                doc.addPage(page);

                int from = p * ROWS_PER_PAGE;
                int to = Math.min(from + ROWS_PER_PAGE, d.items.size());
                List<BookForClientPanel.OrderItem> chunk = d.items.subList(from, to);
                boolean last = (p == pageCount - 1);
                float half = size.getWidth() / 2f;

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    drawCopy(cs, 0, half, size.getHeight(), "CASHIER'S COPY", d, chunk, last, p + 1, pageCount);
                    drawCopy(cs, half, half, size.getHeight(), "CLIENT'S COPY", d, chunk, last, p + 1, pageCount);

                    // dashed cut line between the two copies
                    cs.setLineDashPattern(new float[]{5, 4}, 0);
                    cs.setLineWidth(0.6f);
                    cs.moveTo(half, 20);
                    cs.lineTo(half, size.getHeight() - 20);
                    cs.stroke();
                    cs.setLineDashPattern(new float[]{}, 0);
                }
            }
            doc.save(out);
        }
        return out;
    }

    private static void drawCopy(PDPageContentStream cs, float x0, float width, float height,
                                 String copyLabel, ReceiptData d,
                                 List<BookForClientPanel.OrderItem> rows,
                                 boolean last, int pageNo, int pageCount) throws IOException {

        float left = x0 + MARGIN;
        float right = x0 + width - MARGIN;
        float y = height - 40;

        // ----- header -----
        text(cs, BOLD, 15, left, y, "TRI-MAX Goods Trading");
        textRight(cs, BOLD, 10, right, y, copyLabel);
        y -= LEADING + 2;
        text(cs, REG, 10, left, y, "SALES / BOOKING RECEIPT");
        if (pageCount > 1) {
            textRight(cs, REG, 8, right, y, "Page " + pageNo + " of " + pageCount);
        }
        y -= LEADING;
        line(cs, left, right, y + 6);

        // ----- booking info -----
        String date = d.date == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(d.date);
        y -= 4;
        text(cs, BOLD, 10, left, y, "Booking No.: " + d.bookingId);
        textRight(cs, REG, 10, right, y, "Date: " + date);
        y -= LEADING + 4;

        // ----- client -----
        text(cs, BOLD, 10, left, y, "CLIENT");
        y -= LEADING;
        text(cs, REG, 10, left, y, "ID: " + d.clientId + "   Name: " + d.clientName);
        y -= LEADING;
        text(cs, REG, 10, left, y, "Address: " + truncate(REG, 10, d.address, right - left - 45));
        y -= LEADING;
        text(cs, REG, 10, left, y, "Contact: " + d.contact);
        y -= LEADING + 4;

        // ----- driver -----
        text(cs, BOLD, 10, left, y, "DELIVERY");
        y -= LEADING;
        text(cs, REG, 10, left, y, "Driver: " + d.driverName + " (" + d.driverUsername + ")");
        y -= LEADING;
        text(cs, REG, 10, left, y, "Vehicle: " + d.vehicleId);
        y -= LEADING + 4;

        // ----- items table -----
        float colQty = right - 120, colPrice = right - 60, colSub = right;
        line(cs, left, right, y + 10);
        text(cs, BOLD, 9, left, y, "Product");
        textRight(cs, BOLD, 9, colQty, y, "Qty");
        textRight(cs, BOLD, 9, colPrice, y, "Price");
        textRight(cs, BOLD, 9, colSub, y, "Subtotal");
        y -= 4;
        line(cs, left, right, y);
        y -= LEADING - 2;

        float nameMax = colQty - 35 - left;
        for (BookForClientPanel.OrderItem it : rows) {
            text(cs, REG, 9, left, y, truncate(REG, 9, it.productName, nameMax));
            textRight(cs, REG, 9, colQty, y, String.valueOf(it.amount));
            textRight(cs, REG, 9, colPrice, y, MONEY.format(it.price));
            textRight(cs, REG, 9, colSub, y, MONEY.format(it.subtotal));
            y -= LEADING - 1;
        }

        // ----- totals (only on the last page of the order) -----
        if (last) {
            float ty = 120;
            line(cs, left, right, ty + 14);
            textRight(cs, BOLD, 11, right, ty, "TOTAL: PHP " + MONEY.format(d.total));
            if (d.amountPaid != null) {
                ty -= LEADING;
                textRight(cs, REG, 10, right, ty, "Amount Paid: PHP " + MONEY.format(d.amountPaid));
                ty -= LEADING;
                textRight(cs, REG, 10, right, ty, "Change: PHP " + MONEY.format(d.change));
            }
        }

        // ----- signature -----
        float sy = 45;
        cs.setLineWidth(0.6f);
        cs.moveTo(left, sy + 10);
        cs.lineTo(left + 150, sy + 10);
        cs.stroke();
        text(cs, REG, 8, left, sy, copyLabel.startsWith("CASHIER") ? "Cashier / Admin signature" : "Client signature");
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
        float w = f.getStringWidth(s) / 1000f * size;
        text(cs, f, size, xRight - w, y, s);
    }

    private static void line(PDPageContentStream cs, float x1, float x2, float y) throws IOException {
        cs.setLineWidth(0.6f);
        cs.moveTo(x1, y);
        cs.lineTo(x2, y);
        cs.stroke();
    }

    private static String truncate(PDFont f, float size, String s, float maxWidth) throws IOException {
        s = clean(s);
        while (s.length() > 1 && f.getStringWidth(s) / 1000f * size > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Standard PDF fonts can't draw every character; replace unsupported ones. */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append((c >= 32 && c <= 126) || (c >= 160 && c <= 255) ? c : '?');
        }
        return sb.toString();
    }
}