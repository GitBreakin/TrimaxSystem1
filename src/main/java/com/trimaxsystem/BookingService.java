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
}