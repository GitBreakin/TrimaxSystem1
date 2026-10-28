package com.trimaxsystem;

/**
 * Which step comes next for a booking on the Booking details window, and whether its receipt may be
 * printed. One place for these rules so the window and its tests agree.
 *
 * <ul>
 *   <li>Pending without a driver: <b>Confirm &amp; assign driver</b> (one transaction, see
 *       {@link BookingService#confirmAndAssignDriver}). Only the Admin uses this window, and the database
 *       still refuses a driver's START until the booking is Confirmed, so the Admin approval cannot be skipped.</li>
 *   <li>Pending with a driver (assigned before this version): <b>Confirm booking</b>.</li>
 *   <li>Confirmed without a driver: <b>Assign driver</b>.</li>
 *   <li>Any booking with a saved driver: <b>Print receipt</b>.</li>
 * </ul>
 * The receipt needs a driver that is saved in the database: it is the delivery document the driver takes
 * along, so it is never offered before the assignment has succeeded.
 */
public final class BookingActions {

    /** The highlighted action of the Booking details window. */
    public enum Primary { CONFIRM_AND_ASSIGN, CONFIRM, ASSIGN, PRINT, NONE }

    private BookingActions() {
    }

    public static boolean hasDriver(String driverUsername) {
        return driverUsername != null && !driverUsername.trim().isEmpty();
    }

    public static Primary primary(String status, String driverUsername) {
        boolean driver = hasDriver(driverUsername);
        if (BookingStatus.PENDING.equals(status)) return driver ? Primary.CONFIRM : Primary.CONFIRM_AND_ASSIGN;
        if (BookingStatus.CONFIRMED.equals(status) && !driver) return Primary.ASSIGN;
        return driver ? Primary.PRINT : Primary.NONE;
    }

    /** The receipt may be saved or printed only for a booking whose driver is saved. */
    public static boolean canPrint(String driverUsername) {
        return hasDriver(driverUsername);
    }

    /** Why there is no receipt yet, or null when it can be printed. */
    public static String whyNoReceipt(String status, String driverUsername) {
        if (canPrint(driverUsername)) return null;
        if (BookingService.canAssignDriver(status)) return "The receipt can be printed once a driver is assigned.";
        return "No driver was assigned to this booking, so there is no delivery receipt.";
    }

    /**
     * A driver may be changed while the booking is Pending or Confirmed and the driver has not started
     * (the database refuses it after the start as well).
     */
    public static boolean canChangeDriver(String status, String driverUsername, String deliveryStatus) {
        return hasDriver(driverUsername) && BookingService.canAssignDriver(status)
                && (deliveryStatus == null || DeliveryStatus.ASSIGNED.equals(deliveryStatus));
    }

    /** One sentence for the "Next step" box. */
    public static String nextStepText(String status, String driverUsername) {
        switch (primary(status, driverUsername)) {
            case CONFIRM_AND_ASSIGN:
                return "Next: confirm this booking and choose its driver (one step).";
            case CONFIRM:
                return "A driver is assigned. Confirm the booking so the driver can start the delivery.";
            case ASSIGN:
                return "Next: assign a driver. The receipt can be printed after that.";
            case PRINT:
                return BookingStatus.CANCELLED.equals(status)
                        ? "This booking was cancelled. Its receipt can still be printed for the record."
                        : "Driver assigned. Print the receipt for the delivery.";
            default:
                return whyNoReceipt(status, driverUsername);
        }
    }
}
