package com.trimaxsystem;

import java.awt.Color;
import java.awt.Font;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Shared TRI-MAX colours and fonts for the admin window, so every screen can look the same.
 * New or reworked screens should take their colours from here instead of writing their own.
 */
public final class Theme {

    private Theme() {
    }

    // Brand
    public static final Color NAVY        = Color.decode("#0B2A6F");   // sidebar
    public static final Color NAVY_HOVER  = Color.decode("#14398F");
    public static final Color BLUE        = Color.decode("#2563EB");   // primary / selected
    public static final Color BLUE_DARK   = Color.decode("#1D4ED8");

    // Workspace
    public static final Color BACKGROUND  = Color.decode("#F3F6FB");
    public static final Color CARD        = Color.WHITE;
    public static final Color BORDER      = Color.decode("#E2E8F0");
    public static final Color TEXT        = Color.decode("#0F172A");
    public static final Color MUTED       = Color.decode("#64748B");
    public static final Color ON_NAVY     = Color.decode("#E6ECFA");   // text on the sidebar
    public static final Color ON_NAVY_DIM = Color.decode("#9FB3E0");
    public static final Color FOCUS       = Color.decode("#FBBF24");   // visible keyboard-focus ring

    // Status colours (always shown together with the status text, never colour alone)
    public static final Color OK          = Color.decode("#16A34A");
    public static final Color WARN        = Color.decode("#D97706");
    public static final Color DANGER      = Color.decode("#DC2626");

    // Fonts
    public static final Font TITLE   = new Font("SansSerif", Font.BOLD, 24);
    public static final Font SUBTLE  = new Font("SansSerif", Font.PLAIN, 13);
    public static final Font NAV     = new Font("SansSerif", Font.PLAIN, 14);
    public static final Font NAV_ON  = new Font("SansSerif", Font.BOLD, 14);
    public static final Font BRAND   = new Font("SansSerif", Font.BOLD, 22);

    /** Colour for a canonical booking status value, as stored in bookings.status. */
    public static Color statusColor(String status) {
        if (status == null) return MUTED;
        switch (status) {
            case "Pending":     return WARN;
            case "Confirmed":   return BLUE;
            case "In Progress": return BLUE_DARK;
            case "Completed":   return OK;
            case "Cancelled":   return DANGER;
            default:            return MUTED;
        }
    }

    // Formatting shared by screens
    /** "Today" and every displayed date/time follow this zone, not the database server's clock. */
    public static final ZoneId BUSINESS_TZ = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");

    /** A database timestamp as local business time, e.g. "Oct 17, 2026 15:02". Empty if null. */
    public static String formatWhen(Timestamp ts) {
        return ts == null ? "" : WHEN.format(ts.toInstant().atZone(BUSINESS_TZ));
    }

    /** Money with the peso sign, e.g. "\u20B1 1,250.00". Empty if null. */
    public static String money(BigDecimal v) {
        return v == null ? "" : "\u20B1 " + new DecimalFormat("#,##0.00").format(v);
    }
}
