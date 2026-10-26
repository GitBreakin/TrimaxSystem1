package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Sales & Profit PDF: a summary of 2 columns with a daily trend of 7 columns under it. Before the
 * fix the trend was cut to the 2 summary columns and a trend longer than one page never finished.
 */
class PdfExportTest {

    @TempDir
    Path dir;

    private static ReportTable report(int days) {
        ReportTable t = new ReportTable();
        t.title = "Sales and profit";
        t.meta.add("Period: test");
        t.header = List.of("Line", "PHP");
        t.rows.add(List.of("Net sales", "1000.00"));
        t.rows.add(List.of("Other expenses paid", "250.00"));
        t.rows.add(List.of("Net profit", "750.00"));
        t.footer.add(List.of("Date", "Net sales", "Cost of goods", "Gross profit", "Payroll", "Other expenses", "Net profit"));
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < days; i++) {
            List<String> r = new ArrayList<>();
            r.add(d.plusDays(i).toString());
            r.add("10.00");
            r.add("4.00");
            r.add("6.00");
            r.add("1.00");
            r.add("2.00");
            r.add("NP" + i);
            t.footer.add(r);
        }
        return t;
    }

    @Test
    void aLongTrendFinishesOnSeveralPagesWithEveryColumn() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            Path file = dir.resolve("long.pdf");
            PdfExport.write(file, report(93));
            try (PDDocument doc = PDDocument.load(file.toFile())) {
                assertTrue(doc.getNumberOfPages() >= 2 && doc.getNumberOfPages() <= 6, "pages: " + doc.getNumberOfPages());
                String text = new PDFTextStripper().getText(doc);
                assertTrue(text.contains("Other expenses"), "the Other expenses column is printed");
                assertTrue(text.contains("NP0") && text.contains("NP92"), "the last column of the first and last day");
            }
        });
    }

    @Test
    void aShortReportIsOnePage() throws Exception {
        Path file = dir.resolve("short.pdf");
        PdfExport.write(file, report(1));
        assertTrue(Files.size(file) > 0);
        try (PDDocument doc = PDDocument.load(file.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void widthsFillTheLineAndFollowTheText() {
        float[] w = PdfExport.widths(List.of("Date", "Net profit"), List.of(List.of("2026-01-01", "1.00")), 700f);
        assertEquals(2, w.length);
        assertEquals(700f, w[0] + w[1], 0.01f);
    }
}
