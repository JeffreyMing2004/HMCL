/*
 * NorthStar Client, based on Hello Minecraft! Launcher
 * Copyright (C) 2026 NorthStar & the HMCL contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.main;

import com.google.gson.JsonParser;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies announcement parsing and persistent, content-sensitive dismissal behavior.
@NotNullByDefault
final class NorthStarAnnouncementsTest {
    /// Preserves server order and skips invalid entries without requiring announcement IDs or dates.
    @Test
    void parsesLegacyManifest() throws IOException {
        var announcements = NorthStarAnnouncements.parseAnnouncements(JsonParser.parseString("""
                {"announcements":[null,{},
                  {"title":"First","content":"<a href='https://example.com'>Link</a>"},
                  {"title":"Second","content":"Body","date":"2026-10-15"}]}
                """).getAsJsonObject());
        assertEquals(2, announcements.size());
        assertEquals("First", announcements.get(0).title());
        assertNull(announcements.get(0).date());
        assertEquals("2026-10-15", announcements.get(1).date());
        assertThrows(IOException.class, () -> NorthStarAnnouncements.parseAnnouncements(
                JsonParser.parseString("{}").getAsJsonObject()));
    }

    /// Closing one entry hides only that content and retains the server order of remaining entries.
    @Test
    void filtersDismissedEntriesAndReshowsEditedContent() {
        var first = new NorthStarAnnouncements.Announcement("First", "Body", null);
        var second = new NorthStarAnnouncements.Announcement("Second", "Body", "2026-10-15");
        List<String> dismissed = new ArrayList<>();
        NorthStarAnnouncements.rememberDismissal(dismissed, first);
        assertEquals(List.of(second), NorthStarAnnouncements.visibleAnnouncements(List.of(first, second), dismissed));
        var edited = new NorthStarAnnouncements.Announcement("First", "Edited", null);
        assertEquals(List.of(edited, second), NorthStarAnnouncements.visibleAnnouncements(List.of(edited, second), dismissed));
        assertNotEquals(first.dismissalKey(), new NorthStarAnnouncements.Announcement("Changed", "Body", null).dismissalKey());
        assertNotEquals(first.dismissalKey(), new NorthStarAnnouncements.Announcement("First", "Body", "2026-10-16").dismissalKey());
        NorthStarAnnouncements.rememberDismissal(dismissed, second);
        assertTrue(NorthStarAnnouncements.visibleAnnouncements(List.of(first, second), dismissed).isEmpty());
    }

    /// Dismissals survive settings serialization and still match freshly parsed announcements.
    @Test
    void persistsDismissalsAcrossLauncherSettingsReload() {
        LauncherSettings original = new LauncherSettings();
        var announcement = new NorthStarAnnouncements.Announcement("Title", "Body", null);
        NorthStarAnnouncements.rememberDismissal(original.getNorthstarDismissedAnnouncements(), announcement);
        LauncherSettings reloaded = JsonUtils.GSON.fromJson(original.toJson(), LauncherSettings.class);
        assertEquals(original.getNorthstarDismissedAnnouncements(), reloaded.getNorthstarDismissedAnnouncements());
        assertTrue(NorthStarAnnouncements.visibleAnnouncements(List.of(announcement),
                reloaded.getNorthstarDismissedAnnouncements()).isEmpty());
    }

    /// Repeated dismissals do not duplicate entries and the oldest records expire at the configured bound.
    @Test
    void boundsDismissalHistory() {
        List<String> dismissed = new ArrayList<>();
        var first = new NorthStarAnnouncements.Announcement("First", "Body", null);
        NorthStarAnnouncements.rememberDismissal(dismissed, first);
        NorthStarAnnouncements.rememberDismissal(dismissed, first);
        assertEquals(1, dismissed.size());
        for (int index = 0; index < 200; index++) {
            NorthStarAnnouncements.rememberDismissal(dismissed,
                    new NorthStarAnnouncements.Announcement("Title " + index, "Body", null));
        }
        assertEquals(200, dismissed.size());
        assertFalse(dismissed.contains(first.dismissalKey()));
    }
}
