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
        /** null when the mobile integration is not installed; false = this driver cannot see deliveries on a phone. */
        public Boolean mobileLinked;

        public DriverOption(String username, String name, String vehicleId, String vehicleType) {
            this.username = username;
            this.name = name;
            this.vehicleId = vehicleId;
            this.vehicleType = vehicleType;
        }

        @Override
        public String toString() {
            return username + " - " + name + " (" + vehicleId + ")"
                    + (Boolean.FALSE.equals(mobileLinked) ? "  - no mobile app sign-in" : "");
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
            if (MobileIntegration.isInstalled(conn)) {
                java.util.Set<String> linked = MobileIntegration.linkedUsernames(conn);
                for (DriverOption d : list) d.mobileLinked = linked.contains(d.username);
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

                // 3. Insert each item under that booking_id and deduct the stock (labelled for the stock history)
                Db.stockContext(conn, "BOOKING", "booking", String.valueOf(bookingId));
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
        /** The product name; " (half case)" is added for a half case line. */
        public String product;
        /** A half case line (sql/20261028_07): the price is the half case's and it took 0.5 off the stock each. */
        public boolean half;
        public int quantity;
        public BigDecimal unitPrice;
        public BigDecimal subtotal;
        /** Pesos the agent took off each piece (20261027_04; 0 before it or for none). */
        public BigDecimal discountPerUnit = BigDecimal.ZERO;

        /** quantity x discount per piece. */
        public BigDecimal lineDiscount() {
            return discountPerUnit.multiply(BigDecimal.valueOf(quantity));
        }

        /** What the line costs after its discount. */
        public BigDecimal lineTotal() {
            return (subtotal == null ? BigDecimal.ZERO : subtotal).subtract(lineDiscount());
        }
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

        // Mobile integration (all null when sql/20261023_01_mobile_booking_integration.sql is not installed,
        // and for bookings made before it).
        public String createdVia, createdBy;           // "mobile" + the agent's username
        public String deliveryAddress, schedule, instructions;
        public Double deliveryLatitude, deliveryLongitude;
        public String deliveryStatus;                  // see DeliveryStatus
        public Timestamp deliveryStatusAt;

        // Delivery order (sql/20261028_05; all null before it). deliveryOrderKnown: the server has it.
        public boolean deliveryOrderKnown;
        public Integer deliverySeq, deliveryCount;     // #seq of count in the driver's unfinished deliveries
        public Double hubDistanceM;                    // straight line from the TRIMAX Hub; null without a pin

        // Agent discount (sql/20261027_01; null / zero before it and for bookings without a discount)
        public BigDecimal subtotal, discountAmount, discountValue;
        public String discountType, discountBy;
        public Timestamp discountAt;

        public boolean hasDiscount() {
            return discountAmount != null && discountAmount.signum() > 0;
        }

        /** "10%" or "₱ 20.00" (as the agent typed it) and who gave it. */
        public String discountText() {
            if (!hasDiscount()) return "";
            String how = "PERCENT".equals(discountType) && discountValue != null
                    ? discountValue.stripTrailingZeros().toPlainString() + "%"
                    : discountType == null ? Theme.money(discountAmount) + " on items" : Theme.money(discountAmount);
            return how + (discountBy == null ? "" : " by " + discountBy);
        }

        public boolean fromMobile() {
            return "mobile".equals(createdVia);
        }

        // How the agent took the order (sql/20261029_02; all null before it, for Desktop bookings and for
        // bookings from an older agent app). A visit always has its photo in the private customer-photos bucket.
        public String orderVia;                        // OrderVia.VISIT / OrderVia.TEXT_CALL
        public String visitPhotoPath;
        public Double visitLatitude, visitLongitude;
        public Timestamp visitTakenAt;

        public boolean hasVisitPhoto() {
            return OrderVia.VISIT.equals(orderVia) && visitPhotoPath != null;
        }

        public boolean visitHasGps() {
            return visitLatitude != null && visitLongitude != null;
        }

        /** Drop-off photos the driver sent (sql/20261025_04); empty when none or the table is missing. */
        public List<Proof> proofs = new ArrayList<>();
    }

    /** One drop-off (proof of delivery) photo from the driver app. The photo itself is private. */
    public static class Proof {
        public long proofId;
        public String photoPath;        // path in the private "delivery-proofs" bucket
        public String driverUsername, driverName, recipientName;
        public Timestamp takenAt, createdAt;
        public Double latitude, longitude;

        public Timestamp when() {
            return takenAt != null ? takenAt : createdAt;
        }

        public boolean hasGps() {
            return latitude != null && longitude != null;
        }
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
        // discount_per_unit and unit read through to_jsonb so this also works on a server without
        // 20261027_04 / 20261028_07
        String lines = "SELECT p.product_name, bi.quantity, bi.unit_price, bi.subtotal, "
                + "coalesce((to_jsonb(bi) ->> 'discount_per_unit')::numeric, 0), "
                + "coalesce(to_jsonb(bi) ->> 'unit', 'WHOLE') = 'HALF' "
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
                        l.discountPerUnit = rs.getBigDecimal(5);
                        l.half = rs.getBoolean(6);
                        if (l.half) l.product = (l.product == null ? "(deleted product)" : l.product) + " (half case)";
                        d.items.add(l);
                    }
                }
            }
            if (MobileIntegration.isInstalled(conn)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT created_via, created_by_username, delivery_address, delivery_latitude, delivery_longitude, "
                                + "requested_schedule, special_instructions, delivery_status, delivery_status_at "
                                + "FROM bookings WHERE booking_id = ?")) {
                    ps.setInt(1, bookingId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            d.createdVia = rs.getString(1);
                            d.createdBy = rs.getString(2);
                            d.deliveryAddress = rs.getString(3);
                            d.deliveryLatitude = (Double) rs.getObject(4);
                            d.deliveryLongitude = (Double) rs.getObject(5);
                            d.schedule = rs.getString(6);
                            d.instructions = rs.getString(7);
                            d.deliveryStatus = rs.getString(8);
                            d.deliveryStatusAt = rs.getTimestamp(9);
                        }
                    }
                }
                if (DeliveryOrder.isInstalled(conn)) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT " + DeliveryOrder.DISTANCE_SQL + ", ds.seq, "
                                    + "(SELECT count(*) FROM trimax_private.delivery_sequence(b.driver_username)) "
                                    + "FROM bookings b LEFT JOIN clients c ON c.client_id = b.client_id "
                                    + "LEFT JOIN trimax_private.delivery_sequence(b.driver_username) ds ON ds.booking_id = b.booking_id "
                                    + "WHERE b.booking_id = ?")) {
                        ps.setInt(1, bookingId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                d.deliveryOrderKnown = true;
                                d.hubDistanceM = (Double) rs.getObject(1);
                                d.deliverySeq = (Integer) rs.getObject(2);
                                if (d.deliverySeq != null) d.deliveryCount = rs.getInt(3);
                            }
                        }
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT (to_jsonb(b) ->> 'subtotal_amount')::numeric, (to_jsonb(b) ->> 'discount_amount')::numeric, "
                            + "to_jsonb(b) ->> 'discount_type', (to_jsonb(b) ->> 'discount_value')::numeric, "
                            + "to_jsonb(b) ->> 'discount_by', (to_jsonb(b) ->> 'discount_at')::timestamptz "
                            + "FROM bookings b WHERE b.booking_id = ?")) {
                ps.setInt(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        d.subtotal = rs.getBigDecimal(1);
                        d.discountAmount = rs.getBigDecimal(2);
                        d.discountType = rs.getString(3);
                        d.discountValue = rs.getBigDecimal(4);
                        d.discountBy = rs.getString(5);
                        d.discountAt = rs.getTimestamp(6);
                    }
                }
            }
            // Read through to_jsonb so this also works on a server without 20261029_02.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT to_jsonb(b) ->> 'order_via', to_jsonb(b) ->> 'visit_photo_path', "
                            + "(to_jsonb(b) ->> 'visit_latitude')::double precision, "
                            + "(to_jsonb(b) ->> 'visit_longitude')::double precision, "
                            + "(to_jsonb(b) ->> 'visit_taken_at')::timestamptz FROM bookings b WHERE b.booking_id = ?")) {
                ps.setInt(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        d.orderVia = rs.getString(1);
                        d.visitPhotoPath = rs.getString(2);
                        d.visitLatitude = (Double) rs.getObject(3);
                        d.visitLongitude = (Double) rs.getObject(4);
                        d.visitTakenAt = rs.getTimestamp(5);
                    }
                }
            }
            try {
                d.proofs = loadProofs(conn, bookingId);
            } catch (SQLException e) {
                d.proofs = new ArrayList<>();   // optional, like the history
            }
            try {
                d.history = AuditService.load(conn, "booking", String.valueOf(bookingId));
            } catch (SQLException e) {
                d.history = null;   // history is optional; the booking itself still loads
            }
            return d;
        }
    }

    /** The booking's drop-off photos, oldest first (empty on a server without public.delivery_proofs). */
    static List<Proof> loadProofs(Connection conn, int bookingId) throws SQLException {
        List<Proof> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT to_regclass('public.delivery_proofs') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || !rs.getBoolean(1)) return out;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT p.proof_id, p.photo_path, p.driver_username, u.name, p.recipient_name, p.taken_at, p.created_at, "
                        + "p.latitude, p.longitude FROM public.delivery_proofs p "
                        + "LEFT JOIN public.users u ON u.username = p.driver_username "
                        + "WHERE p.booking_id = ? ORDER BY coalesce(p.taken_at, p.created_at), p.proof_id")) {
            ps.setInt(1, bookingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Proof p = new Proof();
                    p.proofId = rs.getLong(1);
                    p.photoPath = rs.getString(2);
                    p.driverUsername = rs.getString(3);
                    p.driverName = rs.getString(4);
                    p.recipientName = rs.getString(5);
                    p.takenAt = rs.getTimestamp(6);
                    p.createdAt = rs.getTimestamp(7);
                    p.latitude = (Double) rs.getObject(8);
                    p.longitude = (Double) rs.getObject(9);
                    out.add(p);
                }
            }
        }
        return out;
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
                    Db.stockContext(conn, "BOOKING_CANCELLED", "booking", String.valueOf(bookingId));
                    try (PreparedStatement ps = conn.prepareStatement(
                            // stock_qty: a half case gives back 0.5 (20261028_07); before it, the quantity
                            "UPDATE products p SET stock = p.stock + s.qty FROM ("
                                    + "SELECT i.product_id, SUM(coalesce((to_jsonb(i) ->> 'stock_qty')::numeric, i.quantity)) AS qty "
                                    + "FROM booking_items i WHERE i.booking_id = ? GROUP BY i.product_id) s "
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
        return assign(bookingId, expectedStatus, expectedDriver, newDriver, false);
    }

    /**
     * Confirms a Pending booking AND assigns its driver in one transaction (one click for the Admin).
     * Same checks as {@link #assignDriver} (admin re-checked in the database, driver active with an Available
     * vehicle, the booking still Pending with the driver the admin saw), plus the status change
     * Pending -> Confirmed. Either both happen or neither: a failure leaves the booking exactly as it was.
     * Both changes are audited, as when they are done one after the other.
     */
    public static boolean confirmAndAssignDriver(int bookingId, String expectedDriver, DriverOption newDriver)
            throws SQLException, AuthService.AuthException, ConflictException {
        return assign(bookingId, BookingStatus.PENDING, expectedDriver, newDriver, true);
    }

    private static boolean assign(int bookingId, String expectedStatus, String expectedDriver,
                                  DriverOption newDriver, boolean confirm)
            throws SQLException, AuthService.AuthException, ConflictException {
        AuthService.requireAdmin();
        if (newDriver == null) {
            throw new IllegalStateException("Choose a driver.");
        }
        if (confirm && !BookingStatus.PENDING.equals(expectedStatus)) {
            throw new IllegalStateException("Only a Pending booking can be confirmed.");
        }
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
                // The status column is only written when confirming, so a plain (re)assignment fires no status trigger.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE bookings SET driver_username = ?, vehicle_id = ?"
                                + (confirm ? ", status = '" + BookingStatus.CONFIRMED + "'" : "")
                                + " WHERE booking_id = ? AND status = ? AND driver_username IS NOT DISTINCT FROM ?")) {
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
                if (confirm) {
                    AuditService.record(conn, "booking.status_change", "booking", String.valueOf(bookingId), "success",
                            BookingStatus.PENDING + " -> " + BookingStatus.CONFIRMED + " (with driver assignment)");
                }
                boolean audited = AuditService.record(conn, "booking.assign_driver", "booking",
                        String.valueOf(bookingId), "success",
                        (expectedDriver == null ? "unassigned" : expectedDriver) + " -> " + newDriver.username
                                + " (vehicle " + newDriver.vehicleId + ")");
                conn.commit();
                return audited;
            } catch (SQLException ex) {
                conn.rollback();
                String rule = MobileIntegration.friendlyMessage(ex);   // e.g. the driver already started it
                if (rule != null) throw new IllegalStateException(rule, ex);
                throw ex;
            } catch (ConflictException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }
}
