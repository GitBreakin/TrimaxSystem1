package com.trimaxsystem;

import java.util.ArrayList;
import java.util.List;

/** One exportable table: what is written to CSV, Excel and PDF, so all three always match. */
public final class ReportTable {
    public String title;
    public final List<String> meta = new ArrayList<>();      // lines such as period, time zone, formulas
    public List<String> header = new ArrayList<>();
    public final List<List<String>> rows = new ArrayList<>();
    public final List<List<String>> footer = new ArrayList<>();   // totals / rates, shown under the rows
    /** Columns from this index on are written as numbers in Excel (the rest stay text). */
    public int firstNumericCol = 1;

    /** Flat rows for CSV: title, meta, blank line, header, rows, blank line, footer. */
    public List<List<String>> csvRows() {
        List<List<String>> out = new ArrayList<>();
        out.add(List.of("TRI-MAX report", title));
        for (String m : meta) {
            int i = m.indexOf(": ");
            out.add(i < 0 ? List.of(m) : List.of(m.substring(0, i), m.substring(i + 2)));
        }
        out.add(List.of());
        out.add(header);
        out.addAll(rows);
        if (!footer.isEmpty()) {
            out.add(List.of());
            out.addAll(footer);
        }
        return out;
    }
}
