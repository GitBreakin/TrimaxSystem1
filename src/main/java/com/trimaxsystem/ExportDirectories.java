
package com.trimaxsystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ExportDirectories {

    private static final Path TRIMAX_DIR = Paths.get(
            System.getProperty("user.home"),
            "Documents",
            "TRIMAX"
    );

    private static final Path RECEIPTS_DIR =
            TRIMAX_DIR.resolve("Receipts");

    private static final Path REPORTS_DIR =
            TRIMAX_DIR.resolve("Reports");

    private ExportDirectories() {
        // Utility class; no instances needed.
    }

    public static void initialize() throws IOException {
        Files.createDirectories(RECEIPTS_DIR);
        Files.createDirectories(REPORTS_DIR);
    }

    public static Path receiptsDirectory() {
        return RECEIPTS_DIR;
    }

    public static Path reportsDirectory() {
        return REPORTS_DIR;
    }
}
