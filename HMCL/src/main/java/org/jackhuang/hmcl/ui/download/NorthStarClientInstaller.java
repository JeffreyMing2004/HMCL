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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.game.NoSuchGameInstanceException;
import org.jackhuang.hmcl.modpack.Modpack;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Fetches the latest NorthStar client modpack from [Metadata.NORTHSTAR_CLIENT_URL] and opens
/// the modpack installation wizard.
///
/// The server is expected to answer with a JSON object:
///
/// ```json
/// {
///   "version": "1.0.0",
///   "url": "https://.../client.zip",
///   "sha1": "sha-1 of the file, optional"
/// }
/// ```
///
/// When the server is unreachable or reports no client, a toast is shown instead.
@NotNullByDefault
public final class NorthStarClientInstaller {
    private NorthStarClientInstaller() {
    }

    /// Fetches the client manifest, downloads the modpack while showing a progress dialog, then
    /// installs it automatically into the selected game directory — no manual wizard steps. The
    /// installed instance is selected and a toast confirms completion.
    public static void installLatest() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();

        Task<Path> download = Task.supplyAsync(NorthStarClientInstaller::fetchManifest)
                .thenComposeAsync(NorthStarClientInstaller::downloadClient);

        Task<GameInstanceID> whole = download
                .thenComposeAsync(path -> installHeadless(repository, path));
        // whenComplete 仅用于在 FX 线程收尾；whole 本身保持 GameInstanceID 类型交给任务对话框
        whole.whenComplete(Schedulers.javafx(), (instanceId, exception) -> {
            if (exception instanceof CancellationException)
                return; // cancelled by the user
            if (exception != null) {
                if (findCause(exception, ClientUnavailableException.class) != null) {
                    Controllers.showToast(i18n("northstar.no_client"));
                } else {
                    LOG.warning("Failed to download NorthStar client", exception);
                    Controllers.dialog(exception.toString(), i18n("message.error"));
                }
                return;
            }
            if (instanceId != null) {
                try {
                    repository.setSelectedInstance(repository.getInstance(instanceId));
                } catch (NoSuchGameInstanceException e) {
                    LOG.warning("Installed instance disappeared right after installation", e);
                }
                Controllers.showToast(i18n("northstar.client.installed"));
            }
        });

        Controllers.taskDialog(whole, i18n("northstar.fetching_client"), TaskCancellationAction.NORMAL);
    }

    /// Parses the downloaded archive and builds the installation task for a new instance named
    /// after the modpack manifest, all without UI. Runs the manifest parse on a background
    /// thread because it decompresses the archive.
    private static Task<GameInstanceID> installHeadless(HMCLGameRepository repository, Path modpackFile) {
        return Task.supplyAsync(() -> {
            Charset charset = CompressingUtils.findSuitableEncoding(modpackFile);
            Modpack manifest = ModpackHelper.readModpackManifest(modpackFile, charset);
            String name = manifest.getName().trim();
            if (name.isEmpty() || !HMCLGameRepository.isValidInstanceId(name)) {
                name = "NorthStar-Client";
            }
            return new ModpackPlan(manifest, name);
        }).thenComposeAsync(plan -> {
            GameInstanceID instanceId = new GameInstanceID(plan.name());
            return ModpackHelper.getInstallTask(repository, modpackFile, instanceId, plan.manifest(), null)
                    .thenApplyAsync(ignored -> instanceId);
        });
    }

    /// The parsed manifest plus the resolved instance name.
    private record ModpackPlan(Modpack manifest, String name) {
    }

    /// Requests the client manifest from the NorthStar server.
    ///
    /// @throws ClientUnavailableException when the server is unreachable or reports no client
    private static ClientManifest fetchManifest() throws IOException {
        JsonObject json;
        try {
            json = JsonUtils.fromNonNullJson(NetworkUtils.doGet(Metadata.NORTHSTAR_CLIENT_URL), JsonObject.class);
        } catch (IOException | JsonParseException e) {
            throw new ClientUnavailableException("Failed to fetch NorthStar client manifest", e);
        }

        String url = stringField(json, "url");
        if (url == null)
            throw new ClientUnavailableException("NorthStar client manifest has no url");

        return new ClientManifest(url, stringField(json, "sha1"), stringField(json, "version"));
    }

    /// Downloads the modpack into the launcher's local cache directory.
    private static Task<Path> downloadClient(ClientManifest manifest) throws IOException {
        String fileName = (manifest.version() == null ? "client" : "client-" + manifest.version()) + ".zip";
        Path target = Metadata.HMCL_LOCAL_HOME.resolve("northstar-client").resolve(fileName);
        Files.createDirectories(target.getParent());

        FileDownloadTask task = new FileDownloadTask(manifest.url(), target,
                manifest.sha1() == null ? null : new FileDownloadTask.IntegrityCheck("SHA-1", manifest.sha1()));
        return task.thenApplyAsync(value -> target);
    }

    private static @Nullable String stringField(JsonObject json, String name) {
        return Optional.ofNullable(json.get(name))
                .map(JsonElement::getAsString)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    private static @Nullable Throwable findCause(@Nullable Throwable exception, Class<? extends Throwable> type) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (type.isInstance(t))
                return t;
        }
        return null;
    }

    /// The client manifest returned by the NorthStar server.
    private record ClientManifest(String url, @Nullable String sha1, @Nullable String version) {
    }

    /// Thrown when the NorthStar server has no downloadable client.
    private static final class ClientUnavailableException extends RuntimeException {
        ClientUnavailableException(String message) {
            super(message);
        }

        ClientUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
