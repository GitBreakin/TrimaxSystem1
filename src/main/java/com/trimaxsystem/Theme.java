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

    // Tables, fields and states (redesign)
    public static final Color HEADER_BG    = Color.decode("#F8FAFC");   // table header
    public static final Color ROW_LINE     = Color.decode("#EEF2F7");   // line between table rows
    public static final Color SELECTION    = Color.decode("#EAF2FF");   // selected row
    public static final Color FIELD_BORDER = Color.decode("#CBD5E1");
    public static final Color PLACEHOLDER  = Color.decode("#94A3B8");
    public static final Color SUBTLE_BG    = Color.decode("#F1F5F9");   // neutral chip / empty picture

    // Fonts: Segoe UI on Windows (Inter where installed), else the Java default sans-serif.
    public static final String FAMILY = pickFamily("Segoe UI", "Inter");
    public static final Font TITLE   = font(Font.BOLD, 24);
    public static final Font SUBTLE  = font(Font.PLAIN, 13);
    public static final Font NAV     = font(Font.PLAIN, 14);
    public static final Font NAV_ON  = font(Font.BOLD, 14);
    public static final Font BRAND   = font(Font.BOLD, 22);
    public static final Font SECTION = font(Font.BOLD, 15);   // card titles
    public static final Font SMALL   = font(Font.PLAIN, 12);
    public static final Font LABEL   = font(Font.BOLD, 12);   // captions above fields, table headers

    /** The app font in a style and size. */
    public static Font font(int style, int size) {
        return new Font(FAMILY, style, size);
    }

    private static String pickFamily(String... preferred) {
        try {
            java.util.Set<String> installed = new java.util.HashSet<>(java.util.Arrays.asList(
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String f : preferred) if (installed.contains(f)) return f;
        } catch (Throwable ignored) {
            // headless or no font list: fall through
        }
        return "SansSerif";
    }

    /** Background tint for a status chip: the status colour at low strength on white. */
    public static Color tint(Color c) {
        return new Color(mix(c.getRed()), mix(c.getGreen()), mix(c.getBlue()));
    }

    private static int mix(int v) {
        return 255 - Math.round((255 - v) * 0.13f);
    }

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
