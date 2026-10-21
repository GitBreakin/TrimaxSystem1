package com.trimaxsystem;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writes a {@link ReportTable} as a one-sheet .xlsx file (Apache POI). Text is always written as a
 * text cell, never as a formula, so values such as "=1+1" stay inert. Only columns from
 * firstNumericCol on that look like plain numbers become numeric cells.
 */
public final class XlsxExport {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private XlsxExport() {
    }

    public static void write(Path file, ReportTable t) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = wb.createSheet("Report");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle boldStyle = wb.createCellStyle();
            boldStyle.setFont(bold);

            int r = 0;
            r = line(sheet, r, List.of("TRI-MAX report", t.title), -1, boldStyle);
            for (String m : t.meta) {
                int i = m.indexOf(": ");
                r = line(sheet, r, i < 0 ? List.of(m) : List.of(m.substring(0, i), m.substring(i + 2)), -1, null);
            }
            r++;
            int headerRow = r;
            r = line(sheet, r, t.header, -1, boldStyle);
            for (List<String> row : t.rows) r = line(sheet, r, row, t.firstNumericCol, null);
            if (!t.footer.isEmpty()) {
                r++;
                for (List<String> row : t.footer) r = line(sheet, r, row, t.firstNumericCol, boldStyle);
            }
            sheet.createFreezePane(0, headerRow + 1);
            for (int c = 0; c < t.header.size(); c++) sheet.autoSizeColumn(c);
            wb.write(out);
        }
    }

    private static int line(Sheet sheet, int rowIndex, List<String> cells, int firstNumericCol, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        for (int c = 0; c < cells.size(); c++) {
            String v = cells.get(c) == null ? "" : cells.get(c);
            Cell cell = row.createCell(c);
            if (firstNumericCol >= 0 && c >= firstNumericCol && NUMBER.matcher(v).matches()) {
                cell.setCellValue(Double.parseDouble(v));
            } else {
                cell.setCellValue(v);   // string cell: never evaluated as a formula
            }
            if (style != null) cell.setCellStyle(style);
        }
        return rowIndex + 1;
    }
}
