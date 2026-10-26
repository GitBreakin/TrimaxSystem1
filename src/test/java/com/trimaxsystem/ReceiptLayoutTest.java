package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ReceiptLayoutTest {

    private static ReceiptPdfGenerator.ReceiptData sale(int items, String name) {
        ReceiptPdfGenerator.ReceiptData d = new ReceiptPdfGenerator.ReceiptData();
        d.title = "SALES RECEIPT";
        d.numberLabel = "Receipt No.";
        d.number = "TRX-20261027-00001";
        d.typeLabel = "Walk-In";
        d.date = Timestamp.valueOf("2026-10-27 10:00:00");
        d.showDelivery = false;
        d.items = new ArrayList<>();
        d.lineDiscounts = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < items; i++) {
            d.items.add(new BookForClientPanel.OrderItem("", name + " " + i, 10, 2, 20));
            d.lineDiscounts.add(i == 0 ? new BigDecimal("1.00") : BigDecimal.ZERO);
            total = total.add(new BigDecimal("20"));
        }
        d.discount = new BigDecimal("1.00");
        d.subtotal = total;
        d.total = total.subtract(d.discount);
        d.paymentStatus = "Paid";
        d.paymentMethod = "Cash";
        return d;
    }

    @Test
    void ordinaryReceiptsFitOnOnePage() throws Exception {
        assertEquals(1, ReceiptPdfGenerator.pageCount(sale(1, "Cola")));
        assertEquals(1, ReceiptPdfGenerator.pageCount(sale(16, "Cola")));   // the old layout's limit per page
        assertEquals(1, ReceiptPdfGenerator.pageCount(sale(30, "Cola")));   // compact spacing before a second page
    }

    @Test
    void veryLongOrdersContinueOnAnotherPage() throws Exception {
        assertTrue(ReceiptPdfGenerator.pageCount(sale(80, "Cola")) > 1);
    }

    @Test
    void longProductNamesWrapInsteadOfBeingCut() throws Exception {
        String name = "Mineral water 500 ml x24 with the full supplier description and packaging details";
        List<String> lines = ReceiptPdfGenerator.wrap(PDType1Font.HELVETICA, 9f, name, 200f);
        assertTrue(lines.size() > 1);
        assertEquals(name, String.join(" ", lines));
    }

    @Test
    void theReceiptShowsDiscountsTotalAndPayment() throws Exception {
        File f = File.createTempFile("receipt", ".pdf");
        try {
            ReceiptPdfGenerator.generate(sale(3, "Cola"), f);
            try (PDDocument doc = PDDocument.load(f)) {
                String text = new PDFTextStripper().getText(doc);
                assertEquals(1, doc.getNumberOfPages());
                assertTrue(text.contains("TRX-20261027-00001"));
                assertTrue(text.contains("CASHIER'S COPY") && text.contains("CLIENT'S COPY"));
                assertTrue(text.contains("Discount"));
                assertTrue(text.contains("PHP 59.00"));        // 3 x 20 - 1
                assertTrue(text.contains("Paid"));
            }
        } finally {
            f.delete();
        }
    }
}
