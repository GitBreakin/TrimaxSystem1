package com.trimaxsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

class NotificationRulesTest {

    private static NotificationService.Item alert() {
        NotificationService.Item it = new NotificationService.Item();
        it.id = 7;
        it.kind = "Alert";
        it.category = "SOS";
        it.title = "SOS from Dee Driver";
        it.body = "Engine overheating";
        it.reportedBy = "driver.dee";
        it.createdAt = Timestamp.valueOf("2026-10-25 21:40:00");
        return it;
    }

    @Test
    void aResolutionNeedsANoteOfAtMost1000Characters() {
        assertNotNull(NotificationService.checkNote(null));
        assertNotNull(NotificationService.checkNote("   \n "));
        assertNotNull(NotificationService.checkNote("x".repeat(1001)));
        assertNull(NotificationService.checkNote("  " + "x".repeat(1000) + "  "));   // counted after trimming
        assertNull(NotificationService.checkNote("Tow truck sent"));
    }

    @Test
    void statusWordsFollowTheAlertLifecycle() {
        NotificationService.Item it = alert();
        assertEquals("UNREAD", it.stateText());
        it.readAt = Timestamp.valueOf("2026-10-25 21:45:00");
        it.readBy = "marc.admin";
        assertEquals("Acknowledged", it.stateText());
        it.resolvedAt = Timestamp.valueOf("2026-10-25 22:30:00");
        it.resolvedBy = "marc.admin";
        it.resolutionNote = "Tow truck sent";
        assertEquals("Resolved", it.stateText());
        NotificationService.Item ann = new NotificationService.Item();
        ann.kind = "Announcement";
        ann.readAt = it.readAt;
        assertEquals("Read", ann.stateText());
        assertFalse(ann.isAlert());
    }

    @Test
    void detailsShowWhoAcknowledgedAndResolvedAndTheNote() {
        NotificationService.Item it = alert();
        String open = NotificationsPanel.details(it);
        assertTrue(open.contains("by driver.dee"));
        assertTrue(open.contains("Not acknowledged yet."));
        assertTrue(open.contains("Not resolved yet."));
        it.readAt = Timestamp.valueOf("2026-10-25 21:45:00");
        it.readBy = "agent.ben";
        it.resolvedAt = Timestamp.valueOf("2026-10-25 22:30:00");
        it.resolvedBy = "marc.admin";
        it.resolutionNote = "Tow truck sent";
        String done = NotificationsPanel.details(it);
        assertTrue(done.contains("Acknowledged") && done.contains("by agent.ben"));
        assertTrue(done.contains("by marc.admin") && done.endsWith("Tow truck sent"));
    }
}
