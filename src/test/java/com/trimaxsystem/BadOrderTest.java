package com.trimaxsystem;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BadOrderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private static BadOrderService.Record rec(String reason, int qty, String loss, boolean undone) {
        BadOrderService.Record r = new BadOrderService.Record();
        r.reason = reason;
        r.quantity = qty;
        r.loss = loss == null ? null : new BigDecimal(loss);
        if (undone) {
            r.voidedAt = new java.sql.Timestamp(0);
            r.voidReason = "mistake";
        }
        return r;
    }

    @Test
    void labelsMatchTheDatabaseCodes() {
        assertEquals("Expired", BadOrderService.reasonLabel("EXPIRED"));
        assertEquals("Broken", BadOrderService.reasonLabel("BROKEN"));
        assertEquals("Thrown away", BadOrderService.dispositionLabel("DISPOSED"));
        assertEquals("Returned to supplier", BadOrderService.dispositionLabel("RETURNED_TO_SUPPLIER"));
        assertEquals("", BadOrderService.reasonLabel(null));
        assertEquals(6, BadOrderService.REASONS.length);
        assertEquals("Bad order", InventoryService.kindLabel("BAD_ORDER"));
        assertEquals("Bad order undone", InventoryService.kindLabel("BAD_ORDER_VOIDED"));
        InventoryService.Movement m = new InventoryService.Movement();
        m.referenceType = "bad_order";
        m.referenceId = "12";
        assertEquals("Bad order #12", InventoryService.referenceText(m));
    }

    @Test
    void lossIsPiecesTimesPuhunanAndNoneWithoutPuhunan() {
        assertEquals(new BigDecimal("100.00"), BadOrderService.loss(5, new BigDecimal("20")));
        assertEquals(new BigDecimal("37.50"), BadOrderService.loss(3, new BigDecimal("12.50")));
        assertNull(BadOrderService.loss(5, null), "no puhunan: no peso loss, not 0");
        assertTrue(BadOrderService.preview(50, 5, new BigDecimal("20")).startsWith("Stock 50 → 45"));
        assertTrue(BadOrderService.preview(50, 5, new BigDecimal("20")).contains("Loss ₱"));
        assertTrue(BadOrderService.preview(10, 2, null).contains("no puhunan"));
    }

    @Test
    void inputIsCheckedBeforeSending() {
        assertNull(BadOrderService.check(3, 10, "BROKEN", "", TODAY, TODAY));
        assertNull(BadOrderService.check(10, 10, "EXPIRED", null, TODAY.minusDays(30), TODAY), "all of the stock is allowed");
        assertNotNull(BadOrderService.check(null, 10, "BROKEN", "", TODAY, TODAY), "not a number");
        assertNotNull(BadOrderService.check(0, 10, "BROKEN", "", TODAY, TODAY));
        assertNotNull(BadOrderService.check(11, 10, "BROKEN", "", TODAY, TODAY), "more than the stock");
        assertNotNull(BadOrderService.check(1, 10, "OTHER", " ab ", TODAY, TODAY), "Other needs a note");
        assertNull(BadOrderService.check(1, 10, "OTHER", "rats in the box", TODAY, TODAY));
        assertNotNull(BadOrderService.check(1, 10, "BROKEN", "x".repeat(301), TODAY, TODAY));
        assertNotNull(BadOrderService.check(1, 10, "BROKEN", "", TODAY.plusDays(1), TODAY), "future date");
        assertNotNull(BadOrderService.check(1, 10, "BROKEN", "", TODAY.minusDays(367), TODAY), "older than a year");
        assertNotNull(BadOrderService.check(1, 10, "BROKEN", "", null, TODAY), "date not readable");
    }

    @Test
    void totalsCountOnlyBadOrdersThatAreNotUndone() {
        List<BadOrderService.Record> all = new ArrayList<>();
        all.add(rec("BROKEN", 5, "100.00", false));
        all.add(rec("EXPIRED", 3, "60.00", false));
        all.add(rec("BROKEN", 2, "40.00", true));
        all.add(rec("OTHER", 2, null, false));
        BadOrderService.Page p = new BadOrderService.Page();
        BadOrderService.summarize(p, all, false, 1000);
        assertEquals(3, p.all.count);
        assertEquals(10, p.all.units);
        assertEquals(new BigDecimal("160.00"), p.all.loss);
        assertEquals(1, p.all.noCost);
        assertEquals(1, p.undoneCount);
        assertEquals(3, p.rows.size(), "undone ones are hidden unless asked");
        assertEquals(List.of("BROKEN", "EXPIRED", "OTHER"), new ArrayList<>(p.byReason.keySet()), "in the reason order");
        assertEquals(5, p.byReason.get("BROKEN").units);
        assertTrue(BadOrderService.byReasonText(p).startsWith("Broken: 5 pcs"));

        BadOrderService.Page withUndone = new BadOrderService.Page();
        BadOrderService.summarize(withUndone, all, true, 1000);
        assertEquals(4, withUndone.rows.size());
        assertEquals(new BigDecimal("160.00"), withUndone.all.loss, "listed but still not counted");

        BadOrderService.Page cut = new BadOrderService.Page();
        BadOrderService.summarize(cut, all, true, 2);
        assertEquals(2, cut.rows.size());
        assertTrue(cut.truncated);
        assertEquals(new BigDecimal("160.00"), cut.all.loss, "totals cover everything, not only the rows shown");
    }

    @Test
    void netProfitWordingSaysWhatIsCounted() {
        ProfitService.Summary s = new ProfitService.Summary();
        assertEquals("gross profit − payroll (other expenses and bad orders not counted)", ProfitPanel.netProfitFormula(s));
        s.expensesTracked = true;
        assertEquals("gross profit − payroll − other expenses (bad orders not counted)", ProfitPanel.netProfitFormula(s));
        s.badOrdersTracked = true;
        assertEquals("gross profit − payroll − other expenses − bad orders", ProfitPanel.netProfitFormula(s));
        assertFalse(s.empty() && s.badOrdersCount > 0);
        s.bookingsGross = BigDecimal.ZERO;
        s.badOrdersCount = 1;
        assertFalse(s.empty(), "a period with only a bad order is not empty");
    }
}
