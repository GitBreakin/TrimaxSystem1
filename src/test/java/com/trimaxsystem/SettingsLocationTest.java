package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Where the Desktop looks for trimax.properties, and what it says when there is none. */
class SettingsLocationTest {

    @Test
    void looksInTheStartFolderFirstAndTheUserFolderLast() {
        List<Path> places = DatabaseConnection.settingsLocations();
        assertEquals(Paths.get("trimax.properties").toAbsolutePath().normalize(), places.get(0));
        assertEquals(DatabaseConnection.userSettingsFile(), places.get(places.size() - 1));
        assertEquals(places.size(), places.stream().distinct().count(), "each place once");
        for (Path p : places) {
            assertTrue(p.isAbsolute());
            assertEquals("trimax.properties", p.getFileName().toString());
        }
    }

    @Test
    void theUserFolderIsTheDotTrimaxFolderInHome() {
        Path p = DatabaseConnection.userSettingsFile();
        assertEquals(Paths.get(System.getProperty("user.home"), ".trimax", "trimax.properties").toAbsolutePath().normalize(), p);
    }

    @Test
    void theLoginMessageSaysWhereToPutTheFile() {
        String m = LoginPanel.friendly("Database error: Database is not configured: set TRIMAX_DB_USER or configure \"db.user\" in trimax.properties.");
        assertTrue(m.startsWith("This computer is not connected to the TRI-MAX database yet."), m);
        assertTrue(m.contains(DatabaseConnection.userSettingsFile().toString()), m);
        assertTrue(m.contains("same folder as the TRIMAX app"), m);
        assertFalse(m.toLowerCase().contains("password="), "never shows a setting value");
    }
}
