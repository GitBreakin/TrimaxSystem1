package com.trimaxsystem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExportDirectories {
    private static final Path RECEIPTS = Path.of("C:\\Users\\Gigabyte\\Documents\\TRIMAX\\Receipts");
    private static final Path REPORTS = Path.of("C:\\Users\\Gigabyte\\Documents\\TRIMAX\\Reports");

    private ExportDirectories() {
    }

    static File receiptsDirectory() throws IOException {
        return Files.createDirectories(RECEIPTS).toFile();
    }

    static File reportsDirectory() throws IOException {
        return Files.createDirectories(REPORTS).toFile();
    }
}
