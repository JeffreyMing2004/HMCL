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
package org.jackhuang.hmcl.ui.download;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies player data preservation and failure behavior during modpack upgrades.
@NotNullByDefault
final class NorthStarPlayerDataMigrationTest {
    /// Copies nested worlds, screenshots and options while leaving source files and new mod binaries intact.
    @Test
    void migratesPlayerDataWithoutCopyingOldMods(@TempDir Path directory) throws IOException {
        Path old = Files.createDirectories(directory.resolve("old"));
        Path target = Files.createDirectories(directory.resolve("new"));
        write(old, "saves/world/level.dat", "world");
        write(old, "screenshots/image.png", "screenshot");
        write(old, "options.txt", "player-options");
        write(old, "servers.dat", "servers");
        write(old, "mods/old.jar", "old-mod");
        write(target, "options.txt", "pack-options");
        write(target, "mods/new.jar", "new-mod");

        NorthStarPlayerDataMigration.migrate(old, target);

        assertEquals("world", Files.readString(target.resolve("saves/world/level.dat")));
        assertEquals("screenshot", Files.readString(target.resolve("screenshots/image.png")));
        assertEquals("player-options", Files.readString(target.resolve("options.txt")));
        assertEquals("servers", Files.readString(target.resolve("servers.dat")));
        assertEquals("world", Files.readString(old.resolve("saves/world/level.dat")));
        assertEquals("new-mod", Files.readString(target.resolve("mods/new.jar")));
        assertFalse(Files.exists(target.resolve("mods/old.jar")));
    }

    /// Keeps new pack configurations and backs up conflicts while restoring player-only configurations.
    @Test
    void mergesConfigurationsWithoutReplacingPackDefaults(@TempDir Path directory) throws IOException {
        Path old = Files.createDirectories(directory.resolve("old"));
        Path target = Files.createDirectories(directory.resolve("new"));
        write(old, "config/mod/settings.json", "old-config");
        write(old, "config/player.json", "player-config");
        write(old, ".northstar-previous-config/older.json", "previous-backup");
        write(target, "config/mod/settings.json", "new-config");

        NorthStarPlayerDataMigration.migrate(old, target);

        assertEquals("new-config", Files.readString(target.resolve("config/mod/settings.json")));
        assertEquals("old-config", Files.readString(target.resolve(".northstar-previous-config/mod/settings.json")));
        assertEquals("player-config", Files.readString(target.resolve("config/player.json")));
        assertEquals("previous-backup", Files.readString(target.resolve(".northstar-previous-config/older.json")));
    }

    /// Propagates copy failures and leaves the source data available for retry.
    @Test
    void failsWithoutChangingSourceWhenDestinationIsInvalid(@TempDir Path directory) throws IOException {
        Path old = Files.createDirectories(directory.resolve("old"));
        Path target = Files.createDirectories(directory.resolve("new"));
        write(old, "saves/world/level.dat", "world");
        write(target, "saves", "not-a-directory");

        assertThrows(IOException.class, () -> NorthStarPlayerDataMigration.migrate(old, target));
        assertEquals("world", Files.readString(old.resolve("saves/world/level.dat")));
    }

    /// Rejects links in source and destination trees rather than writing outside the new instance.
    @Test
    void rejectsSymbolicLinks() throws IOException {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path old = Files.createDirectories(fileSystem.getPath("/old"));
            Path target = Files.createDirectories(fileSystem.getPath("/new"));
            Path outside = Files.createDirectories(fileSystem.getPath("/outside"));
            write(old, "screenshots/image.png", "screenshot");
            Files.createSymbolicLink(target.resolve("screenshots"), outside);
            assertThrows(IOException.class, () -> NorthStarPlayerDataMigration.migrate(old, target));
            assertFalse(Files.exists(outside.resolve("image.png")));

            Files.delete(target.resolve("screenshots"));
            Files.createSymbolicLink(old.resolve("saves"), outside);
            assertThrows(IOException.class, () -> NorthStarPlayerDataMigration.migrate(old, target));
        }
    }

    /// Shared run directories need no copying; nested directories must not be migrated recursively.
    @Test
    void handlesSharedAndOverlappingDirectories(@TempDir Path directory) throws IOException {
        write(directory, "options.txt", "settings");
        NorthStarPlayerDataMigration.migrate(directory, directory);
        assertEquals("settings", Files.readString(directory.resolve("options.txt")));
        Path child = Files.createDirectories(directory.resolve("child"));
        assertThrows(IOException.class, () -> NorthStarPlayerDataMigration.migrate(directory, child));
    }

    /// Creates parent directories and writes deterministic fixture contents.
    private static void write(Path root, String name, String content) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
