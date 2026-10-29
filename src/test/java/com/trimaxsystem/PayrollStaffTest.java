package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Employees without an app account in payroll (sql/20261029_01). */
class PayrollStaffTest {

    @Test
    void detailsAreCheckedBeforeSaving() {
        assertNull(PayrollService.staffProblem("Juan Dela Cruz", "Helper", ""));
        assertNull(PayrollService.staffProblem("  Juan   Dela Cruz ", null, "0917-123-4567"), "spaces and dashes are cleaned up");
        assertNotNull(PayrollService.staffProblem("J", "Helper", ""), "a name needs 2 letters");
        assertNotNull(PayrollService.staffProblem("Juan", "x".repeat(51), ""), "position up to 50 characters");
        assertNotNull(PayrollService.staffProblem("Juan", null, "12345"), "a wrong contact number");
        assertNotNull(PayrollService.staffProblem("Juan", null, "08171234567"), "must start with 09");
    }

    @Test
    void anEmployeeWithoutAnAccountInTheLists() {
        PayrollService.Staff s = new PayrollService.Staff();
        s.id = 7;
        s.name = "Maria Santos";
        s.position = "Cashier";
        s.active = true;
        PayrollService.Employee e = s.asEmployee();
        assertTrue(e.isStaff());
        assertNull(e.username);
        assertEquals("Maria Santos  (Cashier)", e.toString());
        s.position = null;
        s.active = false;
        assertEquals("Maria Santos  (no app account, No longer working here)", s.asEmployee().toString());

        PayrollService.Employee account = new PayrollService.Employee();
        account.username = "driver_ken";
        account.name = "Ken";
        account.role = "Driver";
        account.status = "Active";
        assertFalse(account.isStaff());
        assertTrue(e.same(s.asEmployee()), "the same employee without an account");
        assertFalse(e.same(account));
        assertFalse(account.same(e), "an account is never the same as an employee without one");
    }
}
