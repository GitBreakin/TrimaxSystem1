package com.trimaxsystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Writes a {@link ReportTable} as a landscape A4 PDF (PDFBox 2.x, the same library as the receipts).
 * The header row repeats on every page and pages are numbered. Characters the built-in Helvetica font
 * cannot show are replaced with "?", and long cells are cut with ".." instead of overflowing.
 */
public final class PdfExport {

    private static final PDFont REG = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final float MARGIN = 36f;
    private static final float ROW_H = 15f;
    private static final float FONT = 9f;

    private PdfExport() {
    }

    /** Keeps only characters the standard Helvetica font can draw; anything else becomes "?". */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\t') sb.append(' ');
            else if (c >= 32 && c <= 255) sb.append(c);
            else sb.append('?');
        }
        return sb.toString();
    }

    public static void write(Path file, ReportTable t) throws IOException {
        PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());   // landscape
        float usable = size.getWidth() - 2 * MARGIN;
        float[] width = widths(t.header, t.rows, usable);
        // The footer (totals, or a trend table) can have more columns than the header: its own widths.
        List<String> footerHead = t.footer.isEmpty() ? List.of() : t.footer.get(0);
        int footerCols = 0;
        for (List<String> f : t.footer) footerCols = Math.max(footerCols, f.size());
        java.util.List<String> fh = new java.util.ArrayList<>(footerHead);
        while (fh.size() < footerCols) fh.add("");
        float[] footerWidth = t.footer.isEmpty() ? width
                : footerCols <= t.header.size() ? width : widths(fh, t.footer, usable);

        try (PDDocument doc = new PDDocument()) {
            int pageNo = 0;
            int rowIdx = 0;
            int footIdx = 0;
            boolean firstPage = true;
            while (firstPage || rowIdx < t.rows.size() || footIdx < t.footer.size()) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                pageNo++;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = size.getHeight() - MARGIN;
                    if (firstPage) {
                        y = text(cs, BOLD, 14, MARGIN, y, "TRI-MAX report: " + t.title) - 6;
                        for (String m : t.meta) {
                            y = text(cs, REG, 8, MARGIN, y, cut(m, REG, 8, usable)) - 2;
                        }
                        y -= 8;
                    }
                    float bottom = MARGIN + 20;
                    if (firstPage || rowIdx < t.rows.size()) {
                        y = row(cs, BOLD, t.header, width, y);
                        while (rowIdx < t.rows.size() && y - ROW_H > bottom) {
                            y = row(cs, REG, t.rows.get(rowIdx++), width, y);
                        }
                    }
                    if (rowIdx >= t.rows.size() && footIdx < t.footer.size()) {
                        if (footIdx > 0) {
                            // a footer that continues on a new page repeats its first line (its column names)
                            y = row(cs, BOLD, t.footer.get(0), footerWidth, y);
                        } else {
                            y -= 8;
                            // keep short footers (totals) together: start them on the next page if they do not fit
                            int need = Math.min(t.footer.size(), 6);
                            if (y - need * ROW_H <= bottom && y < size.getHeight() - MARGIN - 8) {
                                text(cs, REG, 8, size.getWidth() / 2 - 20, MARGIN - 10, "Page " + pageNo);
                                firstPage = false;
                                continue;
                            }
                        }
                        // at least one footer line per page, so a long footer always finishes
                        do {
                            y = row(cs, BOLD, t.footer.get(footIdx++), footerWidth, y);
                        } while (footIdx < t.footer.size() && y - ROW_H > bottom);
                    }
                    text(cs, REG, 8, size.getWidth() / 2 - 20, MARGIN - 10, "Page " + pageNo);
                }
                firstPage = false;
            }
            doc.save(file.toFile());
        }
    }

    /** Column widths by the longest text in each column (capped), filling the usable width. */
    static float[] widths(List<String> header, List<List<String>> rows, float usable) {
        int cols = header.size();
        float[] weight = new float[cols];
        float total = 0;
        for (int c = 0; c < cols; c++) {
            int len = header.get(c).length();
            for (List<String> r : rows) {
                if (c < r.size()) len = Math.max(len, Math.min(28, r.get(c).length()));
            }
            weight[c] = Math.max(8, len) + 2;
            total += weight[c];
        }
        float[] width = new float[cols];
        for (int c = 0; c < cols; c++) width[c] = usable * weight[c] / total;
        return width;
    }

    private static float row(PDPageContentStream cs, PDFont font, List<String> cells, float[] width, float y)
            throws IOException {
        float x = MARGIN;
        for (int c = 0; c < width.length; c++) {
            String v = c < cells.size() ? cells.get(c) : "";
            text(cs, font, FONT, x + 2, y - 10, cut(v, font, FONT, width[c] - 4));
            x += width[c];
        }
        return y - ROW_H;
    }

    private static float text(PDPageContentStream cs, PDFont font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(clean(s));
        cs.endText();
        return y - size - 3;
    }

    private static String cut(String s, PDFont font, float size, float maxWidth) throws IOException {
        String v = clean(s);
        if (font.getStringWidth(v) / 1000f * size <= maxWidth) return v;
        while (v.length() > 1 && font.getStringWidth(v + "..") / 1000f * size > maxWidth) {
            v = v.substring(0, v.length() - 1);
        }
        return v + "..";
    }
}
