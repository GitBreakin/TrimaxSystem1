package com.trimaxsystem;

import java.awt.Component;
import java.awt.Desktop;
import java.awt.print.PrinterJob;
import java.io.File;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPageable;

/** Save, open or print a receipt PDF (used by sales and bookings). */
final class ReceiptActions {

    private ReceiptActions() {
    }

    /** Asks where to save, saves, and offers to open it. */
    static void save(Component parent, ReceiptPdfGenerator.ReceiptData data, String fileName) {
        try {
            ExportDirectories.initialize();
            JFileChooser chooser = new JFileChooser(ExportDirectories.receiptsDirectory().toFile());
            chooser.setDialogTitle("Save receipt");
            chooser.setSelectedFile(new File(fileName));
            if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
            File output = chooser.getSelectedFile();
            if (!output.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                output = new File(output.getParentFile(), output.getName() + ".pdf");
            }
            File pdf = ReceiptPdfGenerator.generate(data, output);
            int open = JOptionPane.showConfirmDialog(parent, "Receipt saved to:\n" + pdf.getAbsolutePath() + "\n\nOpen it now?",
                    "Receipt saved", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (open == JOptionPane.YES_OPTION) open(parent, pdf);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Could not save the receipt PDF: " + ex.getMessage(),
                    "Receipt", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Sends the receipt to a printer chosen in the print dialog. */
    static void print(Component parent, ReceiptPdfGenerator.ReceiptData data, String jobName) {
        File pdf = null;
        try {
            pdf = File.createTempFile("trimax_receipt_", ".pdf");
            ReceiptPdfGenerator.generate(data, pdf);
            try (PDDocument document = PDDocument.load(pdf)) {
                PrinterJob job = PrinterJob.getPrinterJob();
                job.setJobName(jobName);
                job.setPageable(new PDFPageable(document));
                if (!job.printDialog()) return;
                job.print();
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Could not print the receipt: " + ex.getMessage(),
                    "Print receipt", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (pdf != null && pdf.exists() && !pdf.delete()) pdf.deleteOnExit();
        }
    }

    static void open(Component parent, File pdf) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(pdf);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "The receipt was saved but could not be opened: " + ex.getMessage(),
                    "Receipt", JOptionPane.WARNING_MESSAGE);
        }
    }
}
