package com.trimaxsystem;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** Database work for creating a booking. */
public class BookingService {

    /** One selectable driver (a driver user together with their Available vehicle). */
    public static class DriverOption {
        public final String username;
        public final String name;
        public final String vehicleId;
        public final String vehicleType;

        public DriverOption(String username, String name, String vehicleId, String vehicleType) {
            this.username = username;
            this.name = name;
            this.vehicleId = vehicleId;
            this.vehicleType = vehicleType;
        }

        @Override
        public String toString() {
            return username + " - " + name + " (" + vehicleId + ")";
        }
    }

    /** What the database produced for the new booking. */
    public static class BookingResult {
        public final int bookingId;
        public final Timestamp transcDate;
        public final BigDecimal totalAmount;

        public BookingResult(int bookingId, Timestamp transcDate, BigDecimal totalAmount) {
            this.bookingId = bookingId;
            this.transcDate = transcDate;
            this.totalAmount = totalAmount;
        }
    }

    /** Active drivers whose vehicle status is 'Available'. */
    public static List<DriverOption> loadAvailableDrivers() throws SQLException {
        String sql = "SELECT u.username, u.name, v.vehicle_id, v.vehicle_type "
                + "FROM vehicles v "
                + "JOIN users u ON u.username = v.username "
                + "JOIN roles r ON r.role_id = u.role_id "
                + "WHERE v.status = 'Available' AND u.account_status = 'Active' "
                + "AND r.role_name = 'Driver' "
                + "ORDER BY u.username";

        List<DriverOption> list = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pst = conn.prepareStatement(sql);
             ResultSet rs = pst.executeQuery()) {
            while (rs.next()) {
                list.add(new DriverOption(
                        rs.getString("username"),
                        rs.getString("name"),
                        rs.getString("vehicle_id"),
                        rs.getString("vehicle_type")));
            }
        }
        return list;
    }

    /**
     * Inserts the booking, its items, and deducts stock, all in ONE transaction.
     * If anything fails, nothing is saved.
     * bookings.total_amount is filled in by the trigger on booking_items.
     */
    public static BookingResult createBooking(int clientId, DriverOption driver,
                                              List<BookForClientPanel.OrderItem> items) throws SQLException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Make sure the vehicle is still Available (lock the row)
                try (PreparedStatement chk = conn.prepareStatement(
                        "SELECT status FROM vehicles WHERE vehicle_id = ? FOR UPDATE")) {
                    chk.setString(1, driver.vehicleId);
                    try (ResultSet rs = chk.executeQuery()) {
                        if (!rs.next() || !"Available".equals(rs.getString(1))) {
                            throw new SQLException("The selected driver/vehicle is no longer Available.");
                        }
                    }
                }

                // 2. Insert the booking header
                int bookingId;
                Timestamp date;
                try (PreparedStatement pst = conn.prepareStatement(
                        "INSERT INTO bookings (client_id, driver_username, vehicle_id) "
                                + "VALUES (?, ?, ?) RETURNING booking_id, transc_date")) {
                    pst.setInt(1, clientId);
                    pst.setString(2, driver.username);
                    pst.setString(3, driver.vehicleId);
                    try (ResultSet rs = pst.executeQuery()) {
                        rs.next();
                        bookingId = rs.getInt(1);
                        date = rs.getTimestamp(2);
                    }
                }

                // 3. Insert each item under that booking_id and deduct the stock
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO booking_items (booking_id, product_id, quantity, unit_price, subtotal) "
                                + "VALUES (?, ?, ?, ?, ?)");
                     PreparedStatement stock = conn.prepareStatement(
                             "UPDATE products SET stock = stock - ? WHERE product_id = ? AND stock >= ?")) {

                    for (BookForClientPanel.OrderItem it : items) {
                        int productId = Integer.parseInt(it.productId);
                        BigDecimal unitPrice = BigDecimal.valueOf(it.price).setScale(2, java.math.RoundingMode.HALF_UP);
                        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(it.amount)); // matches the CHECK

                        ins.setInt(1, bookingId);
                        ins.setInt(2, productId);
                        ins.setInt(3, it.amount);
                        ins.setBigDecimal(4, unitPrice);
                        ins.setBigDecimal(5, subtotal);
                        ins.executeUpdate();

                        stock.setInt(1, it.amount);
                        stock.setInt(2, productId);
                        stock.setInt(3, it.amount);
                        if (stock.executeUpdate() != 1) {
                            throw new SQLException("Not enough stock for \"" + it.productName + "\".");
                        }
                    }
                }

                // 4. Read back the total the trigger calculated
                BigDecimal total;
                try (PreparedStatement pst = conn.prepareStatement(
                        "SELECT total_amount FROM bookings WHERE booking_id = ?")) {
                    pst.setInt(1, bookingId);
                    try (ResultSet rs = pst.executeQuery()) {
                        rs.next();
                        total = rs.getBigDecimal(1);
                    }
                }

                conn.commit();
                return new BookingResult(bookingId, date, total);

            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // =====================================================
    // DETAILS AND STATUS CHANGES (admin booking screens)
    // =====================================================

    /** One product line of a booking. */
    public static class Line {
        public String product;
        public int quantity;
        public BigDecimal unitPrice;
        public BigDecimal subtotal;
    }

    /** Everything the booking detail window shows. */
    public static class Detail {
        public int bookingId;
        public String clientId;
        public Timestamp date;
        public BigDecimal total;
        public String status;
        public String clientName, address, contact;
        public String driverName, driverUsername, vehicleId, vehicleType;
        public List<Line> items = new ArrayList<>();
        /** Audit entries for this booking; null when the audit table does not exist yet or could not be read. */
        public List<AuditService.Entry> history;
    }

    /** The booking was changed by someone else since the admin opened it. */
    public static class ConflictException extends Exception {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** Loads one booking with its customer, driver, vehicle and items. Returns null if it no longer exists. */
    public static Detail loadDetail(int bookingId) throws SQLException {
        String head = "SELECT b.booking_id, b.client_id, b.transc_date, b.total_amount, b.status, "
                + "b.driver_username, b.vehicle_id, "
                + "c.client_name, c.address, c.contact_num, u.name AS driver_name, v.vehicle_type "
                + "FROM bookings b "
                + "LEFT JOIN clients c  ON c.client_id = b.client_id "
                + "LEFT JOIN users u    ON u.username = b.driver_username "
                + "LEFT JOIN vehicles v ON v.vehicle_id = b.vehicle_id "
                + "WHERE b.booking_id = ?";
        String lines = "SELECT p.product_name, bi.quantity, bi.unit_price, bi.subtotal "
                + "FROM booking_items bi LEFT JOIN products p ON p.product_id = bi.product_id "
                + "WHERE bi.booking_id = ? ORDER BY bi.item_id";

        try (Connection conn = DatabaseConnection.getConnection()) {
            Detail d = new Detail();
            try (PreparedStatement ps = conn.prepareStatement(head)) {
                ps.setInt(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    d.bookingId = rs.getInt("booking_id");
                    d.clientId = rs.getString("client_id");
                    d.date = rs.getTimestamp("transc_date");
                    d.total = rs.getBigDecimal("total_amount");
                    d.status = rs.getString("status");
                    d.driverUsername = rs.getString("driver_username");
                    d.vehicleId = rs.getString("vehicle_id");
                    d.clientName = rs.getString("client_name");
                    d.address = rs.getString("address");
                    d.contact = rs.getString("contact_num");
                    d.driverName = rs.getString("driver_name");
                    d.vehicleType = rs.getString("vehicle_type");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(lines)) {
                ps.setInt(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Line l = new Line();
                        l.product = rs.getString(1);
                        l.quantity = rs.getInt(2);
                        l.unitPrice = rs.getBigDecimal(3);
                        l.subtotal = rs.getBigDecimal(4);
                        d.items.add(l);
                    }
                }
            }
            try {
                d.history = AuditService.load(conn, "booking", String.valueOf(bookingId));
            } catch (SQLException e) {
                d.history = null;   // history is optional; the booking itself still loads
            }
            return d;
        }
    }

    /**
     * Moves a booking to a new status. The admin role is re-checked in the database first, the move
     * must be allowed by {@link BookingStatus}, and the update only happens if the booking still has
     * the status the admin saw (otherwise ConflictException). Cancelling also returns the booking's
     * stock to inventory, in the same transaction, because creating a booking took it out.
     */
    public static boolean changeStatus(int bookingId, String expectedCurrent, String newStatus)
            throws SQLException, AuthService.AuthException, ConflictException {
        AuthService.requireAdmin();
        if (!BookingStatus.canTransition(expectedCurrent, newStatus)) {
            throw new IllegalStateException("A booking cannot move from " + expectedCurrent + " to " + newStatus + ".");
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE bookings SET status = ? WHERE booking_id = ? AND status = ?")) {
                    ps.setString(1, newStatus);
                    ps.setInt(2, bookingId);
                    ps.setString(3, expectedCurrent);
                    if (ps.executeUpdate() != 1) {
                        throw new ConflictException("This booking was changed by someone else. "
                                + "It has been reloaded; please check its current status.");
                    }
                }
                if (BookingStatus.CANCELLED.equals(newStatus)) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE products p SET stock = p.stock + s.qty FROM ("
                                    + "SELECT product_id, SUM(quantity) AS qty FROM booking_items "
                                    + "WHERE booking_id = ? GROUP BY product_id) s "
                                    + "WHERE p.product_id = s.product_id")) {
                        ps.setInt(1, bookingId);
                        ps.executeUpdate();
                    }
                }
                boolean audited = AuditService.record(conn, "booking.status_change", "booking",
                        String.valueOf(bookingId), "success", expectedCurrent + " -> " + newStatus);
                conn.commit();
                return audited;
            } catch (SQLException | ConflictException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** A driver may only be assigned or changed while the booking is Pending or Confirmed. */
    public static boolean canAssignDriver(String status) {
        return BookingStatus.PENDING.equals(status) || BookingStatus.CONFIRMED.equals(status);
    }

    /**
     * Assigns or reassigns the driver (and that driver's vehicle) of a booking. Same safety rules as the
     * status change: admin re-checked in the database, only while Pending/Confirmed, and the update only
     * happens if the booking still has the status and driver the admin saw (otherwise ConflictException).
     * The new vehicle must still be Available and belong to an Active Driver; it is locked while checked.
     * Returns true if the change was written to the audit log.
     */
    public static boolean assignDriver(int bookingId, String expectedStatus, String expectedDriver,
                                       DriverOption newDriver)
            throws SQLException, AuthService.AuthException, ConflictException {
        AuthService.requireAdmin();
        if (!canAssignDriver(expectedStatus)) {
            throw new IllegalStateException("The driver can only be changed while the booking is Pending or Confirmed.");
        }
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement chk = conn.prepareStatement(
                        "SELECT v.status FROM vehicles v JOIN users u ON u.username = v.username "
                                + "JOIN roles r ON r.role_id = u.role_id "
                                + "WHERE v.vehicle_id = ? AND v.username = ? AND u.account_status = 'Active' "
                                + "AND r.role_name = 'Driver' FOR UPDATE OF v")) {
                    chk.setString(1, newDriver.vehicleId);
                    chk.setString(2, newDriver.username);
                    try (ResultSet rs = chk.executeQuery()) {
                        if (!rs.next() || !"Available".equals(rs.getString(1))) {
                            throw new IllegalStateException("The selected driver/vehicle is no longer available.");
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE bookings SET driver_username = ?, vehicle_id = ? "
                                + "WHERE booking_id = ? AND status = ? AND driver_username IS NOT DISTINCT FROM ?")) {
                    ps.setString(1, newDriver.username);
                    ps.setString(2, newDriver.vehicleId);
                    ps.setInt(3, bookingId);
                    ps.setString(4, expectedStatus);
                    ps.setString(5, expectedDriver);
                    if (ps.executeUpdate() != 1) {
                        throw new ConflictException("This booking was changed by someone else. "
                                + "It has been reloaded; please check it and try again.");
                    }
                }
                boolean audited = AuditService.record(conn, "booking.assign_driver", "booking",
                        String.valueOf(bookingId), "success",
                        (expectedDriver == null ? "unassigned" : expectedDriver) + " -> " + newDriver.username
                                + " (vehicle " + newDriver.vehicleId + ")");
                conn.commit();
                return audited;
            } catch (SQLException | ConflictException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }
}
