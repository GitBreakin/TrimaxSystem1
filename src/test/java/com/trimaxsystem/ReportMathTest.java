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
    void periodsStartLikePostgresDateTrunc() {
        LocalDate sunday = LocalDate.of(2026, 10, 25);
        assertEquals(LocalDate.of(2026, 10, 19), ReportService.Grouping.WEEK.startOf(sunday));      // ISO week: Monday
        assertEquals(LocalDate.of(2026, 10, 19), ReportService.Grouping.WEEK.startOf(LocalDate.of(2026, 10, 19)));
        assertEquals(LocalDate.of(2026, 10, 1), ReportService.Grouping.MONTH.startOf(sunday));
        assertEquals(sunday, ReportService.Grouping.DAY.startOf(sunday));
        assertEquals(LocalDate.of(2025, 12, 29), ReportService.Grouping.WEEK.startOf(LocalDate.of(2026, 1, 1)));   // across the year
    }

    @Test
    void periodLabels() {
        assertEquals("2026-10-25", ReportService.Grouping.DAY.label(LocalDate.of(2026, 10, 25)));
        assertEquals("Week 43 (from 2026-10-19)", ReportService.Grouping.WEEK.label(LocalDate.of(2026, 10, 19)));
        assertEquals("October 2026", ReportService.Grouping.MONTH.label(LocalDate.of(2026, 10, 1)));
    }

    @Test
    void chartShowsEveryPeriodOfTheRangeIncludingEmptyOnes() {
        ReportService.Report r = new ReportService.Report();
        r.from = LocalDate.of(2026, 9, 26);
        r.to = LocalDate.of(2026, 10, 25);
        r.grouping = ReportService.Grouping.DAY;
        assertEquals(30, AnalyticsPanel.BarChart.periods(r).size());
        r.grouping = ReportService.Grouping.WEEK;
        assertEquals(List.of(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19)), AnalyticsPanel.BarChart.periods(r));
        r.grouping = ReportService.Grouping.MONTH;
        assertEquals(List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)), AnalyticsPanel.BarChart.periods(r));
    }

    @Test
    void everyReportQueryIsLimitedToTheDateRange() {
        for (ReportService.Grouping g : ReportService.Grouping.values()) {
            String[] sql = ReportService.sql(g);
            assertEquals(8, sql.length);
            int[] params = {2, 2, 3, 2, 3, 3, 2, 2};   // from, to [, limit]
            for (int i = 0; i < sql.length; i++) {
                assertTrue(sql[i].contains("BETWEEN ? AND ?"), "query " + i + " has the date range");
                assertEquals(params[i], (int) sql[i].chars().filter(c -> c == '?').count(), "parameters of query " + i);
                assertTrue(sql[i].contains("'Asia/Manila'"), "query " + i + " uses Manila days");
            }
        }
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
