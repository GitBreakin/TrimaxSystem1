package com.trimaxsystem;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.Map;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/**
 * The app's look: FlatLaf Light (already a dependency) tuned to the TRI-MAX colours, so standard Swing
 * controls (fields, drop-downs, tables, scroll bars, dialogs) look modern and the same on every page.
 * Called once at start-up, before any window is created. If FlatLaf cannot load, the app keeps the
 * default Swing look and every screen still works.
 */
public final class Laf {

    private static boolean flat;

    private Laf() {
    }

    /** True when FlatLaf is active (screens may then use its rounded styles). */
    public static boolean isFlat() {
        return flat;
    }

    public static synchronized void install() {
        if (flat) return;
        try {
            com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(Map.of(
                    "@accentColor", hex(Theme.BLUE),
                    "@background", hex(Theme.CARD)));
            flat = com.formdev.flatlaf.FlatLightLaf.setup();
        } catch (Throwable e) {   // FlatLaf missing or failing: keep the default look
            flat = false;
        }
        if (!flat) return;

        UIManager.put("defaultFont", new FontUIResource(Theme.FAMILY, Font.PLAIN, 13));
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.focusColor", new Color(0x93C5FD));
        UIManager.put("Component.focusedBorderColor", Theme.BLUE);
        UIManager.put("Component.borderColor", Theme.FIELD_BORDER);
        UIManager.put("TextField.margin", new Insets(5, 9, 5, 9));
        UIManager.put("PasswordField.margin", new Insets(5, 9, 5, 9));
        UIManager.put("ComboBox.padding", new Insets(4, 8, 4, 6));
        UIManager.put("TextComponent.placeholderForeground", Theme.PLACEHOLDER);
        UIManager.put("TextField.placeholderForeground", Theme.PLACEHOLDER);

        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.track", Theme.CARD);
        UIManager.put("ScrollPane.border", javax.swing.BorderFactory.createEmptyBorder());

        UIManager.put("Table.rowHeight", 40);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
        UIManager.put("Table.gridColor", Theme.ROW_LINE);
        UIManager.put("Table.selectionBackground", Theme.SELECTION);
        UIManager.put("Table.selectionForeground", Theme.TEXT);
        UIManager.put("Table.selectionInactiveBackground", Theme.SELECTION);
        UIManager.put("Table.selectionInactiveForeground", Theme.TEXT);
        UIManager.put("Table.cellFocusColor", new Color(0, 0, 0, 0));
        UIManager.put("TableHeader.background", Theme.HEADER_BG);
        UIManager.put("TableHeader.foreground", Theme.MUTED);
        UIManager.put("TableHeader.height", 38);
        UIManager.put("TableHeader.separatorColor", Theme.HEADER_BG);
        UIManager.put("TableHeader.bottomSeparatorColor", Theme.BORDER);
        UIManager.put("TableHeader.font", new FontUIResource(Theme.FAMILY, Font.BOLD, 12));
        UIManager.put("TableHeader.cellMargins", new Insets(2, 12, 2, 12));
        UIManager.put("Table.cellMargins", new Insets(2, 12, 2, 12));

        UIManager.put("TabbedPane.selectedBackground", Theme.CARD);
        UIManager.put("TabbedPane.underlineColor", Theme.BLUE);
        UIManager.put("TabbedPane.tabHeight", 36);
        UIManager.put("ToolTip.background", Theme.TEXT);
        UIManager.put("ToolTip.foreground", Color.WHITE);
        UIManager.put("OptionPane.buttonAreaBorder", javax.swing.BorderFactory.createEmptyBorder(10, 0, 0, 0));
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
