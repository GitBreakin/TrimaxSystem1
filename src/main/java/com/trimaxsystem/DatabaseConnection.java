package com.trimaxsystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

public class DatabaseConnection {

    private static volatile Properties fileProps;
    private static volatile String filePath;   // the trimax.properties that was read, or null

    public static Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", require("TRIMAX_DB_USER", "db.user"));
        props.setProperty("password", require("TRIMAX_DB_PASSWORD", "db.password"));
        props.setProperty("sslmode", "require");
        props.setProperty("connectTimeout", "10");

        return DriverManager.getConnection(require("TRIMAX_DB_URL", "db.url"), props);
    }

    /**
     * The Supabase project URL (not a secret), e.g. https://abcd.supabase.co, used to reach Edge Functions.
     * TRIMAX_SUPABASE_URL or "supabase.url" wins; otherwise it is worked out from the pooler user name
     * "postgres.&lt;project ref&gt;". Throws when neither is available.
     */
    public static String supabaseUrl() throws SQLException {
        String value = System.getenv("TRIMAX_SUPABASE_URL");
        if (value == null || value.isBlank()) value = loadFile().getProperty("supabase.url");
        if (value != null && !value.isBlank()) {
            value = value.trim();
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            boolean local = value.startsWith("http://127.0.0.1:") || value.startsWith("http://localhost:");
            if (!value.startsWith("https://") && !local) {
                throw new SQLException("supabase.url must start with https://");
            }
            return value;
        }
        String user = require("TRIMAX_DB_USER", "db.user");
        int dot = user.indexOf('.');
        String ref = dot > 0 ? user.substring(dot + 1) : "";
        if (!ref.matches("[a-z0-9]{10,40}")) {
            throw new SQLException("Set \"supabase.url\" in trimax.properties (for example https://<project>.supabase.co).");
        }
        return "https://" + ref + ".supabase.co";
    }

    /**
     * An optional setting: the environment variable wins, otherwise the key in trimax.properties.
     * Null when neither has a value. Never logs the value.
     */
    static String setting(String envName, String fileKey) {
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) value = loadFile().getProperty(fileKey);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Full path of the trimax.properties this app read, or null when there is none. */
    static String settingsFile() {
        loadFile();
        return filePath;
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
                    + (filePath == null
                       ? "No trimax.properties was found; put a filled-in copy of trimax.properties.example at "
                         + userSettingsFile() + " (or next to the TRIMAX app)."
                       : "Add it to " + filePath + "."));
        }
        return value.trim();
    }

    private static Properties loadFile() {
        Properties p = fileProps;
        if (p != null) return p;
        synchronized (DatabaseConnection.class) {
            if (fileProps != null) return fileProps;
            p = new Properties();
            for (Path path : settingsLocations()) {
                if (Files.isRegularFile(path)) {
                    try (InputStream in = Files.newInputStream(path)) {
                        p.load(in);
                        filePath = path.toAbsolutePath().toString();
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

    /**
     * Where trimax.properties is looked for, first found wins: the folder the app was started from, the app's own
     * folder (next to the .exe or .jar, so an installed or double-clicked app finds it), then
     * C:\Users\&lt;name&gt;\.trimax\trimax.properties. Environment variables still win over all of these.
     */
    static List<Path> settingsLocations() {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        out.add(Paths.get("trimax.properties").toAbsolutePath().normalize());
        for (Path dir : appFolders()) out.add(dir.resolve("trimax.properties").toAbsolutePath().normalize());
        out.add(userSettingsFile());
        return new ArrayList<>(out);
    }

    /** C:\Users\&lt;name&gt;\.trimax\trimax.properties: the place that works however the app is started. */
    static Path userSettingsFile() {
        return Paths.get(System.getProperty("user.home"), ".trimax", "trimax.properties").toAbsolutePath().normalize();
    }

    /** The folder of the .exe (jpackage) and of the .jar or wrapped .exe this class was loaded from. */
    private static List<Path> appFolders() {
        List<Path> dirs = new ArrayList<>();
        String exe = System.getProperty("jpackage.app-path");   // set by apps packaged with jpackage
        if (exe != null && !exe.isBlank()) {
            try {
                Path dir = Paths.get(exe).toAbsolutePath().getParent();
                if (dir != null) dirs.add(dir);
            } catch (RuntimeException ignored) {
                // not a usable path: skip it
            }
        }
        try {
            CodeSource cs = DatabaseConnection.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path code = Paths.get(cs.getLocation().toURI()).toAbsolutePath();
                if (Files.isRegularFile(code) && code.getParent() != null) {   // a .jar or .exe, not an IDE's classes folder
                    Path dir = code.getParent();
                    dirs.add(dir);
                    Path name = dir.getFileName();
                    if (name != null && "app".equalsIgnoreCase(name.toString()) && dir.getParent() != null) {
                        dirs.add(dir.getParent());                      // jpackage keeps the jar in <install>\app\
                    }
                }
            }
        } catch (Exception ignored) {
            // no code location (unusual class loader): the other places still apply
        }
        return dirs;
    }
}
