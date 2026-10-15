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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/// Copies player-owned data between run directories without changing the old instance.
@NotNullByDefault
final class NorthStarPlayerDataMigration {
    /// Vanilla and common mod data to preserve, excluding mod binaries and launcher metadata.
    private static final @Unmodifiable List<String> PLAYER_PATHS = List.of(
            "saves", "screenshots", "resourcepacks", "shaderpacks", "texturepacks",
            "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat", "servers.dat_old",
            "hotbar.nbt", "usercache.json", "XaeroWaypoints", "XaeroWorldMap", "journeymap",
            "voxelmap", "replay_recordings", "recordings", "schematics", "litematica",
            ".northstar-previous-config");

    /// Prevents construction of this migration utility.
    private NorthStarPlayerDataMigration() {
    }

    /// Copies and verifies supported player files, then merges configuration files.
    /// Conflicting old configurations are retained under `.northstar-previous-config` in the new instance.
    /// Symbolic links and overlapping directories are rejected so migration cannot modify the source.
    /// Any copy or verification failure propagates to the installer, preventing removal of the old instance.
    static void migrate(Path source, Path target) throws IOException {
        Path oldRoot = source.toRealPath();
        Path newRoot = target.toRealPath();
        if (oldRoot.equals(newRoot)) {
            return;
        }
        if (oldRoot.startsWith(newRoot) || newRoot.startsWith(oldRoot)) {
            throw new IOException("Player data migration directories overlap");
        }
        for (String name : PLAYER_PATHS) {
            copy(oldRoot.resolve(name), newRoot.resolve(name), newRoot, false);
        }
        copy(oldRoot.resolve("config"), newRoot.resolve("config"), newRoot, true);
    }

    /// Recursively copies regular files without following links; missing source entries are skipped.
    /// Existing new configurations win, with conflicting old files copied to a separate backup tree.
    private static void copy(Path source, Path target, Path root, boolean configuration) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Player data migration interrupted");
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return;
        }
        if (attributes.isSymbolicLink() || (!attributes.isDirectory() && !attributes.isRegularFile())) {
            throw new IOException("Unsupported player data entry: " + source);
        }
        ensureSafeTarget(target, root);
        if (attributes.isDirectory()) {
            Files.createDirectories(target);
            try (var children = Files.newDirectoryStream(source)) {
                for (Path child : children) {
                    copy(child, target.resolve(child.getFileName().toString()), root, configuration);
                }
            }
            return;
        }
        Path destination = target;
        if (configuration && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // Preserve pack defaults while retaining the old configuration for manual recovery.
            destination = root.resolve(".northstar-previous-config")
                    .resolve(root.resolve("config").relativize(target));
            ensureSafeTarget(destination, root);
        }
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        if (Files.mismatch(source, destination) != -1) {
            throw new IOException("Player data verification failed: " + source);
        }
    }

    /// Rejects symbolic links anywhere below the destination root before writing data.
    private static void ensureSafeTarget(Path target, Path root) throws IOException {
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic link in player data destination: " + current);
            }
        }
    }
}
