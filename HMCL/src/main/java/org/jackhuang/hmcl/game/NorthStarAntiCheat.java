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
package org.jackhuang.hmcl.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.java.JavaInfoUtils;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.io.Unzipper;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Automatically configures the 次元反作弊 (DimensionAntiCheat) client components before launching.
///
/// Before a game process is created, the launcher requests an anti-cheat manifest from
/// [Metadata.NORTHSTAR_ANTICHEAT_URL] and installs the described artifacts into the instance.
/// The manifest is expected to be a JSON object:
///
/// ```json
/// {
///   "enabled": true,
///   "version": "7.1.95",
///   "gameVersions": ["1.20.1"],
///   "agentFile": "dac-agent.jar",
///   "javaDirectory": "jreForMinecraft",
///   "files": [
///     { "url": "https://.../dac-agent-mod.jar", "sha1": "...", "fileName": "dac-agent-mod.jar", "target": "mods" },
///     { "url": "https://.../jreForMinecraft.zip", "sha1": "...", "fileName": "jreForMinecraft.zip", "target": "archive" }
///   ]
/// }
/// ```
///
/// - `target: "mods"` installs the file into the instance's mods directory (mod mode).
/// - `target: "game"` installs the file into the game (run) directory.
/// - `target: "archive"` treats the file as a zip archive, downloads it into the launcher's cache
///   and extracts it into the game directory, e.g. to place the bundled `jreForMinecraft` Java
///   runtime next to `mods`.
/// - `agentFile` (agent mode) is attached to the game process as a `-javaagent` argument.
/// - `javaDirectory` switches the launch Java to the bundled runtime inside the game directory,
///   as required by agent mode.
/// - `gameVersions` optionally restricts the configuration to the listed Minecraft versions.
///
/// An unreachable or malformed manifest is logged and skipped so that launching keeps working
/// when the API is not deployed yet; failures while applying a fetched manifest abort the launch
/// with an error dialog instead of joining the server without the anti-cheat.
@NotNullByDefault
public final class NorthStarAntiCheat {
    private NorthStarAntiCheat() {
    }

    /// Marker file inside the game directory recording the files installed by the last
    /// successful preparation, used to remove artifacts that a newer manifest dropped.
    private static final String MARKER_FILE_NAME = ".northstar-anticheat.json";

    /// Name of the launcher-local directory that caches downloaded artifacts.
    private static final String CACHE_DIRECTORY_NAME = "northstar-anticheat";

    /// Suffix of the sidecar file next to a cached archive recording the SHA-1 that was extracted.
    private static final String EXTRACTED_SUFFIX = ".extracted";

    /// Java launcher executable inside a bundled JRE directory, relative to the JRE root.
    private static final String BUNDLED_JAVA_EXECUTABLE = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
            ? "bin/java.exe"
            : "bin/java";

    /// Fetches the anti-cheat manifest and installs it into the given instance.
    ///
    /// @param instance the instance about to be launched
    /// @return the launch configuration, or `null` when the server does not mandate one for this instance
    public static Task<@Nullable Configuration> prepare(HMCLGameInstance instance) {
        return Task.supplyAsync(() -> {
            Manifest manifest = fetchManifest();
            if (manifest == null || !manifest.appliesTo(instance.getVersion().toString()))
                return null;
            return manifest;
        }).thenComposeAsync(manifest -> manifest == null
                ? Task.<Configuration>completed(null)
                : install(instance, manifest));
    }

    /// Requests the anti-cheat manifest from the NorthStar server.
    ///
    /// @return the parsed manifest, or `null` when the server is unreachable, reports a disabled
    /// configuration, or answers with malformed content
    private static @Nullable Manifest fetchManifest() {
        String body;
        try {
            body = NetworkUtils.doGet(Metadata.NORTHSTAR_ANTICHEAT_URL);
        } catch (IOException e) {
            LOG.warning("NorthStar anti-cheat manifest is unavailable, skipping configuration", e);
            return null;
        }

        try {
            return parseManifest(JsonUtils.fromNonNullJson(body, JsonObject.class));
        } catch (JsonParseException e) {
            LOG.warning("NorthStar anti-cheat manifest is malformed, skipping configuration", e);
            return null;
        }
    }

    /// Ensures every manifest artifact is present with its declared SHA-1 and applies the
    /// installation, downloading missing or outdated artifacts first.
    private static Task<@Nullable Configuration> install(HMCLGameInstance instance, Manifest manifest) {
        return Task.composeAsync(() -> {
            Path gameDir = instance.getRunDirectory();
            Path modsDir = instance.getModsDirectory();

            List<Task<Void>> downloads = new ArrayList<>();
            List<PlannedFile> planned = new ArrayList<>();
            for (Artifact artifact : manifest.files()) {
                Path target = targetPath(gameDir, modsDir, artifact);
                Files.createDirectories(target.getParent());

                // Artifacts without a declared hash cannot be verified, so they are refreshed every launch.
                if (artifact.sha1() == null || !matchesSha1(target, artifact.sha1())) {
                    downloads.add(new FileDownloadTask(artifact.url(), target,
                            artifact.sha1() == null ? null : new FileDownloadTask.IntegrityCheck("SHA-1", artifact.sha1())));
                }
                if (artifact.target() != Target.ARCHIVE)
                    planned.add(new PlannedFile(artifact.target(), artifact.fileName()));
            }

            Task<?> downloadAll = downloads.isEmpty()
                    ? Task.completed(null)
                    : Task.allOf(downloads);
            return downloadAll.thenComposeAsync(() -> Task.supplyAsync(() -> apply(gameDir, modsDir, manifest, planned)));
        });
    }

    /// Extracts archives, replaces files dropped by a previous manifest, records the managed
    /// files and assembles the launch configuration.
    private static Configuration apply(Path gameDir, Path modsDir, Manifest manifest, List<PlannedFile> planned) {
        try {
            for (Artifact artifact : manifest.files()) {
                if (artifact.target() == Target.ARCHIVE)
                    extractArchive(artifact, gameDir);
            }

            replaceDroppedFiles(gameDir, modsDir, planned);
            writeManagedFiles(gameDir, manifest, planned);

            return new Configuration(resolveAgentArgument(gameDir, manifest), resolveBundledJava(gameDir, manifest));
        } catch (IOException e) {
            throw new ConfigurationException("Failed to apply the NorthStar anti-cheat configuration", e);
        }
    }

    /// Extracts a cached archive into the game directory when its content changed since the last
    /// extraction, tracked by a `.extracted` sidecar next to the cached archive.
    private static void extractArchive(Artifact artifact, Path gameDir) throws IOException {
        Path zip = cachePath(artifact.fileName());
        String sha1 = artifact.sha1() != null
                ? artifact.sha1()
                : HexFormat.of().formatHex(DigestUtils.digest("SHA-1", zip));
        Path extractedMarker = zip.resolveSibling(zip.getFileName() + EXTRACTED_SUFFIX);
        if (Files.isRegularFile(zip) && Files.isRegularFile(extractedMarker)
                && sha1.equalsIgnoreCase(Files.readString(extractedMarker).trim()))
            return;

        new Unzipper(zip, gameDir).setReplaceExistentFile(true).unzip();
        Files.writeString(extractedMarker, sha1);
    }

    /// Deletes files installed by a previous manifest that the current manifest no longer lists.
    private static void replaceDroppedFiles(Path gameDir, Path modsDir, List<PlannedFile> planned) throws IOException {
        for (PlannedFile file : readManagedFiles(gameDir)) {
            if (planned.contains(file))
                continue;
            Path target = file.target() == Target.MODS
                    ? modsDir.resolve(file.fileName())
                    : gameDir.resolve(file.fileName());
            LOG.info("Removing outdated NorthStar anti-cheat file: " + target);
            Files.deleteIfExists(target);
        }
    }

    /// Reads the marker file listing the files installed by the last successful preparation.
    ///
    /// @return the recorded managed files, or an empty list when the marker is missing or unusable
    private static @Unmodifiable List<PlannedFile> readManagedFiles(Path gameDir) {
        Path marker = gameDir.resolve(MARKER_FILE_NAME);
        if (!Files.isRegularFile(marker))
            return List.of();

        try {
            JsonObject json = JsonUtils.fromJsonFile(marker, JsonObject.class);
            if (json == null)
                return List.of();
            JsonArray array = Optional.ofNullable(json.get("files"))
                    .filter(JsonElement::isJsonArray)
                    .map(JsonElement::getAsJsonArray)
                    .orElse(null);
            if (array == null)
                return List.of();

            List<PlannedFile> files = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject())
                    continue;
                JsonObject item = element.getAsJsonObject();
                String target = stringField(item, "target");
                String fileName = stringField(item, "fileName");
                if (target == null || fileName == null || !isSafeFileName(fileName))
                    continue;
                try {
                    files.add(new PlannedFile(Target.valueOf(target), fileName));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return List.copyOf(files);
        } catch (IOException | JsonParseException e) {
            LOG.warning("Failed to read the NorthStar anti-cheat marker file: " + marker, e);
            return List.of();
        }
    }

    /// Writes the marker file recording the files managed by the given manifest.
    private static void writeManagedFiles(Path gameDir, Manifest manifest, List<PlannedFile> planned) throws IOException {
        JsonObject json = new JsonObject();
        if (manifest.version() != null)
            json.addProperty("version", manifest.version());
        JsonArray files = new JsonArray();
        for (PlannedFile file : planned) {
            JsonObject item = new JsonObject();
            item.addProperty("target", file.target().name());
            item.addProperty("fileName", file.fileName());
            files.add(item);
        }
        json.add("files", files);
        Files.writeString(gameDir.resolve(MARKER_FILE_NAME), JsonUtils.GSON.toJson(json));
    }

    /// Builds the `-javaagent` argument for agent mode.
    ///
    /// @return the argument, or `null` when the manifest does not declare an agent file or the
    /// agent path cannot be attached unambiguously
    /// @throws ConfigurationException when the declared agent file is missing
    private static @Nullable String resolveAgentArgument(Path gameDir, Manifest manifest) throws IOException {
        if (manifest.agentFile() == null)
            return null;
        Path agent = gameDir.resolve(manifest.agentFile());
        if (!Files.isRegularFile(agent))
            throw new ConfigurationException("The NorthStar anti-cheat agent file is missing: " + agent);

        String absolute = FileUtils.getAbsolutePath(agent);
        if (absolute.indexOf('=') >= 0) {
            // '=' would be parsed as the separator between the agent path and its options.
            LOG.warning("Skipped attaching the NorthStar anti-cheat agent, '=' in the agent path: " + absolute);
            return null;
        }
        return "-javaagent:" + absolute;
    }

    /// Resolves the bundled Java runtime declared by the manifest.
    ///
    /// @return the runtime, or `null` when the manifest declares none, the runtime is missing, or
    /// it cannot be inspected, in which case the launch keeps the selected Java
    private static @Nullable JavaRuntime resolveBundledJava(Path gameDir, Manifest manifest) {
        if (manifest.javaDirectory() == null)
            return null;
        Path binary = gameDir.resolve(manifest.javaDirectory()).resolve(BUNDLED_JAVA_EXECUTABLE);
        if (!Files.isRegularFile(binary)) {
            LOG.warning("The bundled NorthStar anti-cheat Java is missing: " + binary);
            return null;
        }

        try {
            return JavaRuntime.of(binary.toRealPath(), JavaInfoUtils.fromExecutable(binary), false);
        } catch (IOException e) {
            LOG.warning("Failed to inspect the bundled NorthStar anti-cheat Java: " + binary, e);
            return null;
        }
    }

    /// Parses and validates the manifest JSON. Unsafe or unrecognized entries are dropped.
    private static Manifest parseManifest(JsonObject json) {
        boolean enabled = Optional.ofNullable(json.get("enabled"))
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsBoolean)
                .orElse(true);

        List<String> gameVersions = new ArrayList<>();
        Optional.ofNullable(json.get("gameVersions"))
                .filter(JsonElement::isJsonArray)
                .map(JsonElement::getAsJsonArray)
                .ifPresent(array -> {
                    for (JsonElement element : array) {
                        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
                            gameVersions.add(element.getAsString());
                    }
                });

        List<Artifact> files = new ArrayList<>();
        Optional.ofNullable(json.get("files"))
                .filter(JsonElement::isJsonArray)
                .map(JsonElement::getAsJsonArray)
                .ifPresent(array -> {
                    for (JsonElement element : array) {
                        if (!element.isJsonObject())
                            continue;
                        parseArtifact(element.getAsJsonObject()).ifPresent(files::add);
                    }
                });

        String agentFile = safeNameField(json, "agentFile");
        String javaDirectory = safeNameField(json, "javaDirectory");
        return new Manifest(enabled, List.copyOf(gameVersions), List.copyOf(files),
                agentFile, javaDirectory, stringField(json, "version"));
    }

    /// Parses a single manifest artifact, skipping entries with missing or unsafe fields.
    private static Optional<Artifact> parseArtifact(JsonObject item) {
        String url = stringField(item, "url");
        String fileName = stringField(item, "fileName");
        String target = stringField(item, "target");
        if (url == null || fileName == null || target == null || !isSafeFileName(fileName))
            return Optional.empty();

        @Nullable String sha1 = stringField(item, "sha1");
        if (sha1 != null) {
            sha1 = sha1.toLowerCase(Locale.ROOT);
            if (!DigestUtils.isSha1Digest(sha1)) {
                LOG.warning("Ignored NorthStar anti-cheat artifact with a malformed SHA-1: " + fileName);
                return Optional.empty();
            }
        }

        try {
            return Optional.of(new Artifact(url, sha1, fileName, Target.valueOf(target)));
        } catch (IllegalArgumentException e) {
            LOG.warning("Ignored NorthStar anti-cheat artifact with an unknown target: " + target);
            return Optional.empty();
        }
    }

    /// Reads a single-level file or directory name field, rejecting names that could escape
    /// their target directory.
    private static @Nullable String safeNameField(JsonObject json, String name) {
        String value = stringField(json, name);
        if (value != null && !isSafeFileName(value)) {
            LOG.warning("Ignored unsafe NorthStar anti-cheat manifest field " + name + ": " + value);
            return null;
        }
        return value;
    }

    /// Returns whether the name is a plain file or directory name without path separators.
    private static boolean isSafeFileName(String name) {
        return StringUtils.isNotBlank(name)
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && !".".equals(name)
                && !"..".equals(name);
    }

    /// Returns the download or cache location of an artifact.
    private static Path targetPath(Path gameDir, Path modsDir, Artifact artifact) {
        return switch (artifact.target()) {
            case MODS -> modsDir.resolve(artifact.fileName());
            case GAME -> gameDir.resolve(artifact.fileName());
            case ARCHIVE -> cachePath(artifact.fileName());
        };
    }

    /// Returns the launcher-local cache path of an artifact file.
    private static Path cachePath(String fileName) {
        return Metadata.HMCL_LOCAL_HOME.resolve(CACHE_DIRECTORY_NAME).resolve(fileName);
    }

    /// Returns whether the file exists and matches the given SHA-1 digest.
    private static boolean matchesSha1(Path file, String sha1) {
        if (!Files.isRegularFile(file))
            return false;
        try {
            return sha1.equalsIgnoreCase(HexFormat.of().formatHex(DigestUtils.digest("SHA-1", file)));
        } catch (IOException e) {
            LOG.warning("Failed to hash " + file, e);
            return false;
        }
    }

    private static @Nullable String stringField(JsonObject json, String name) {
        return Optional.ofNullable(json.get(name))
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /// The launch configuration produced by a successful preparation.
    ///
    /// @param agentArgument the `-javaagent` argument to attach, or `null` for mod mode
    /// @param java          the bundled Java to launch the game with, or `null` to keep the selected Java
    public record Configuration(@Nullable String agentArgument, @Nullable JavaRuntime java) {
    }

    /// A file the manifest installs into the instance.
    ///
    /// @param url      the download URL
    /// @param sha1     the expected SHA-1 digest, or `null` when the artifact cannot be verified
    /// @param fileName the plain file name to install or cache the artifact as
    /// @param target   where the artifact is installed
    private record Artifact(String url, @Nullable String sha1, String fileName, Target target) {
    }

    /// Where a manifest artifact is installed.
    private enum Target {
        /// The instance's mods directory.
        MODS,
        /// The game (run) directory.
        GAME,
        /// A zip archive extracted into the game directory, cached in the launcher's local directory.
        ARCHIVE
    }

    /// A managed file recorded in the marker file, identified by its target and file name.
    private record PlannedFile(Target target, String fileName) {
    }

    /// The anti-cheat manifest served by the NorthStar server.
    ///
    /// @param enabled       whether the anti-cheat configuration is active
    /// @param gameVersions  Minecraft versions the configuration applies to; empty applies to all
    /// @param files         artifacts to install into the instance
    /// @param agentFile     agent-mode file relative to the game directory, or `null` for mod mode
    /// @param javaDirectory bundled JRE directory relative to the game directory, or `null` to keep the selected Java
    /// @param version       the anti-cheat version, or `null` when not reported
    private record Manifest(boolean enabled, @Unmodifiable List<String> gameVersions,
                            @Unmodifiable List<Artifact> files, @Nullable String agentFile,
                            @Nullable String javaDirectory, @Nullable String version) {
        /// Returns whether the configuration applies to the given Minecraft version.
        boolean appliesTo(String gameVersion) {
            return gameVersions.isEmpty() || gameVersions.contains(gameVersion);
        }
    }

    /// Thrown when applying a fetched anti-cheat manifest fails; aborts the launch so that the
    /// user is not left joining the server without the required anti-cheat.
    public static final class ConfigurationException extends RuntimeException {
        /// Creates the exception with a technical message.
        public ConfigurationException(String message) {
            super(message);
        }

        /// Creates the exception with a technical message and cause.
        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
