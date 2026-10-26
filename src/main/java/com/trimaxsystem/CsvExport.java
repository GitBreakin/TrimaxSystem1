package com.trimaxsystem;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Minimal CSV writer. UTF-8 with a byte-order mark so Excel opens it correctly. Cells that start with
 * = + - @ (or a tab/CR) get a leading apostrophe so a spreadsheet cannot run them as a formula.
 * Exports are built only from fields the report already shows on screen; never put secrets here.
 */
public final class CsvExport {

    private CsvExport() {
    }

    public static String cell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    public static String row(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        return sb.toString();
    }

    /** Writes the lines to a new/overwritten file (the caller has already confirmed any overwrite). */
    public static void write(Path file, List<List<String>> rows) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write('\uFEFF');
            for (List<String> r : rows) {
                w.write(row(r));
                w.write("\r\n");
            }
        }
    }
}
