package com.trimaxsystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class DatabaseConnection {

    private static final String[] FILE_LOCATIONS = {
            "trimax.properties",
            Paths.get(System.getProperty("user.home"), ".trimax", "trimax.properties").toString()
    };

    private static volatile Properties fileProps;

    public static Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", require("TRIMAX_DB_USER", "db.user"));
        props.setProperty("password", require("TRIMAX_DB_PASSWORD", "db.password"));
        props.setProperty("sslmode", "require");
        props.setProperty("connectTimeout", "10");

        return DriverManager.getConnection(require("TRIMAX_DB_URL", "db.url"), props);
    }

    /** Environment variable wins; otherwise the properties file. Never logs the value. */
    private static String require(String envName, String fileKey) throws SQLException {
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            value = loadFile().getProperty(fileKey);
        }
        if (value == null || value.isBlank()) {
            throw new SQLException("Database is not configured: set " + envName
                    + " or configure \"" + fileKey + "\" in trimax.properties. "
                    + "Copy trimax.properties.example to trimax.properties and fill in your database settings.");
        }
        return value.trim();
    }

    private static Properties loadFile() {
        Properties p = fileProps;
        if (p != null) return p;
        synchronized (DatabaseConnection.class) {
            if (fileProps != null) return fileProps;
            p = new Properties();
            for (String location : FILE_LOCATIONS) {
                Path path = Paths.get(location);
                if (Files.isRegularFile(path)) {
                    try (InputStream in = Files.newInputStream(path)) {
                        p.load(in);
                        break;
                    } catch (IOException ignored) {
                        // unreadable file: treated the same as a missing one
                    }
                }
            }
            fileProps = p;
            return p;
        }
    }
}
