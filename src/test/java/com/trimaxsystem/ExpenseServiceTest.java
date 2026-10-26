package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Expense page logic that needs no database (sql/20261027_02_expenses.sql has the database tests). */
class ExpenseServiceTest {

    private static ExpenseService.Record rec(String amount, String status, boolean voided) {
        ExpenseService.Record r = new ExpenseService.Record();
        r.amount = new BigDecimal(amount);
        r.paymentStatus = status;
        r.date = LocalDate.of(2026, 10, 27);
        if (voided) r.voidedAt = new Timestamp(0);
        return r;
    }

    @Test
    void totalsCountPaidAndUnpaidButNeverVoided() {
        ExpenseService.Page p = new ExpenseService.Page();
        p.add(rec("1500.50", "PAID", false));
        p.add(rec("4500.00", "UNPAID", false));
        p.add(rec("300.00", "PAID", true));
        assertEquals(3, p.rows.size(), "voided rows are still listed");
        assertEquals(2, p.count);
        assertEquals(0, new BigDecimal("6000.50").compareTo(p.total));
        assertEquals(0, new BigDecimal("1500.50").compareTo(p.paid));
        assertEquals(0, new BigDecimal("4500.00").compareTo(p.unpaid));
    }

    @Test
    void statusLabels() {
        assertEquals("Paid", rec("1", "PAID", false).statusLabel());
        assertEquals("Unpaid", rec("1", "UNPAID", false).statusLabel());
        assertEquals("Voided", rec("1", "PAID", true).statusLabel());
    }

    @Test
    void categoriesMatchTheDatabaseCheck() {
        Set<String> codes = new HashSet<>();
        for (String[] c : ExpenseService.CATEGORIES) codes.add(c[0]);
        assertEquals(Set.of("ELECTRICITY", "WATER", "RENT", "FUEL", "REPAIRS", "SUPPLIES", "INTERNET_PHONE",
                "TAXES_PERMITS", "OTHER"), codes);
        assertEquals("Electricity", ExpenseService.categoryLabel("ELECTRICITY"));
        assertEquals("Taxes & permits", ExpenseService.categoryLabel("TAXES_PERMITS"));
        assertEquals("SOMETHING_NEW", ExpenseService.categoryLabel("SOMETHING_NEW"));
    }

    @Test
    void methodsMatchTheDatabaseCheck() {
        Set<String> codes = new HashSet<>();
        for (String[] m : ExpenseService.METHODS) codes.add(m[0]);
        assertEquals(Set.of("CASH", "GCASH", "BANK_TRANSFER", "CHECK", "OTHER"), codes);
        assertEquals("Check", ExpenseService.methodLabel("CHECK"));
        assertEquals("", ExpenseService.methodLabel(null));
    }

    @Test
    void historyLinesInWords() {
        String created = ExpenseService.describe("CREATE", "2026-10-27", "RENT", "Store rent", new BigDecimal("5000"),
                "UNPAID", null, null, null, null, null, null, null, null);
        assertTrue(created.startsWith("Added: 2026-10-27, Rent, \"Store rent\", "), created);
        assertTrue(created.endsWith("unpaid"), created);

        String edited = ExpenseService.describe("UPDATE", "2026-10-27", "RENT", "Store rent", new BigDecimal("4500"),
                "PAID", "2026-10-27", "RENT", "Store rent", new BigDecimal("5000.00"), "UNPAID", null, null, null);
        assertTrue(edited.startsWith("Edited: amount "), edited);
        assertTrue(edited.contains("unpaid → paid"), edited);
        assertFalse(edited.contains("date"), edited);
        assertFalse(edited.contains("category"), edited);

        String sameAmount = ExpenseService.describe("UPDATE", "2026-10-27", "RENT", "x", new BigDecimal("5000.00"),
                "UNPAID", "2026-10-27", "RENT", "x", new BigDecimal("5000"), "UNPAID", null, "a", "b");
        assertEquals("Edited: notes changed", sameAmount, "5000 and 5000.00 are the same amount");

        assertEquals("Voided: Entered twice", ExpenseService.describe("VOID", null, null, null, null, null, null, null,
                null, null, null, "Entered twice", null, null));
    }
}
