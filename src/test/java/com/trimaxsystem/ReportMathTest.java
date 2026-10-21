package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportMathTest {

    @Test
    void rateIsUndefinedNotZeroWhenNothingWasCreated() {
        assertNull(ReportService.rate(0, 0));
        assertEquals(25.0, ReportService.rate(1, 4), 1e-9);
    }

    @Test
    void rangeValidation() {
        LocalDate a = LocalDate.of(2026, 10, 1);
        assertNull(ReportService.validateRange(a, a));
        assertNotNull(ReportService.validateRange(a, a.minusDays(1)));
        assertNotNull(ReportService.validateRange(null, a));
        assertNotNull(ReportService.validateRange(a, a.plusDays(400)));
    }

    @Test
    void csvEscapesQuotesCommasAndFormulas() {
        assertEquals("\"a,b\"", CsvExport.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.cell("say \"hi\""));
        assertEquals("'=SUM(A1)", CsvExport.cell("=SUM(A1)"));
        assertEquals("", CsvExport.cell(null));
        assertEquals("a,b", CsvExport.row(List.of("a", "b")));
    }
}
