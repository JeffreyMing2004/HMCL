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
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// 下载并自动安装 NorthStar 服务器客户端整合包。
///
/// <p>流程:从 [Metadata.NORTHSTAR_CLIENT_URL] 拉取清单 → 下载 zip(可选 SHA-1 校验)→
/// 无 UI 自动安装新实例 → 记录新版并删除旧客户端实例 → 选中新实例。安装完成后在
/// {@code northstar-client/installed.json} 记录版本与实例 ID,供启动时检测更新。</p>
///
/// <p>[checkAndPromptUpdate] 在主页加载时比对服务端清单与本地已装版本,发现新版本
 /// 弹窗提示一键更新。</p>
@NotNullByDefault
public final class NorthStarClientInstaller {
    /// 整合包未提供合法名称时使用的实例名称。
    private static final String DEFAULT_INSTANCE_NAME = "NorthStar-Client";

    /// 已安装客户端的版本记录:{version, instanceId}。
    private static Path installedMarkerFile() {
        return Metadata.HMCL_LOCAL_HOME.resolve("northstar-client").resolve("installed.json");
    }

    /// 避免同一会话内重复弹更新提示。
    private static final AtomicBoolean UPDATE_PROMPT_SHOWN = new AtomicBoolean(false);

    /// 禁止实例化静态安装工具。
    private NorthStarClientInstaller() {
    }

    /// Fetches the client manifest, downloads the modpack while showing a progress dialog, then
    /// installs it automatically into the selected game directory — no manual wizard steps. When
    /// a previous NorthStar client instance is recorded, it is retained until the new instance
    /// has installed successfully and its version marker has been saved. The
    /// installed instance is selected, the new version recorded, and a toast confirms completion.
    public static void installLatest() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        @Nullable InstalledClient previous = readInstalledMarker();

        Task<Path> download = Task.supplyAsync(NorthStarClientInstaller::fetchManifest)
                .thenComposeAsync(NorthStarClientInstaller::downloadClient);

        Task<InstallResult> whole = download
                .thenComposeAsync(path -> installHeadless(repository, path))
                .thenApplyAsync(result -> {
                    // Only retire the old instance after installation and repository registration succeed.
                    repository.getInstance(result.instanceId());
                    writeInstalledMarker(result.version(), result.instanceId());
                    removePreviousClient(repository, previous, result.instanceId());
                    return result;
                });
        whole.whenComplete(Schedulers.javafx(), (result, exception) -> {
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
            if (result != null) {
                try {
                    repository.setSelectedInstance(repository.getInstance(result.instanceId()));
                } catch (NoSuchGameInstanceException e) {
                    LOG.warning("Installed instance disappeared right after installation", e);
                }
                Controllers.showToast(i18n("northstar.client.installed"));
            }
        });

        Controllers.taskDialog(whole, i18n("northstar.fetching_client"), TaskCancellationAction.NORMAL);
    }

    /// 启动时的更新检测:服务端清单版本与本地已装版本不一致时弹窗提示更新。
    /// 从未安装过客户端的新用户跳过(他们走「下载」按钮或首次引导)。
    public static void checkAndPromptUpdate() {
        if (!UPDATE_PROMPT_SHOWN.compareAndSet(false, true)) {
            return;
        }
        Task.supplyAsync(() -> {
            ClientManifest manifest = fetchManifest();
            InstalledClient installed = readInstalledMarker();
            if (installed == null || installed.version().equals(manifest.version())) {
                return null; // 未安装过或已是最新:静默
            }
            return manifest;
        }).whenComplete(Schedulers.javafx(), (manifest, exception) -> {
            if (exception != null || manifest == null || manifest.version() == null) {
                return; // 接口不可用或无更新:静默跳过
            }
            InstalledClient installed = readInstalledMarker();
            Controllers.confirm(
                    i18n("northstar.update.available", manifest.version(),
                            installed == null ? "-" : installed.version()),
                    i18n("northstar.update.title"),
                    MessageDialogPane.MessageType.INFO,
                    NorthStarClientInstaller::installLatest,
                    () -> {
                    });
        }).start();
    }

    /// 解析下载好的压缩包并构建无 UI 安装任务;实例名取自整合包清单,
    /// 非法时回退到 NorthStar-Client。同名目录存在时添加数字后缀，保留旧实例直到安装成功。
    private static Task<InstallResult> installHeadless(HMCLGameRepository repository, Path modpackFile) {
        return Task.supplyAsync(() -> {
            Charset charset = CompressingUtils.findSuitableEncoding(modpackFile);
            Modpack manifest = ModpackHelper.readModpackManifest(modpackFile, charset);
            String name = manifest.getName().trim();
            if (name.isEmpty() || !HMCLGameRepository.isValidInstanceId(name)) {
                name = DEFAULT_INSTANCE_NAME;
            }
            return new ModpackPlan(manifest, name, manifest.getVersion());
        }).thenComposeAsync(plan -> {
            GameInstanceID instanceId = new GameInstanceID(plan.name());
            int suffix = 1;
            while (Files.exists(repository.getLayout().getInstanceRoot(instanceId))) {
                instanceId = new GameInstanceID(plan.name() + "-" + suffix++);
            }
            GameInstanceID targetId = instanceId;
            return ModpackHelper.getInstallTask(repository, modpackFile, targetId, plan.manifest(), null)
                    .thenApplyAsync(ignored -> new InstallResult(targetId, plan.modpackVersion()));
        });
    }

    /// 删除安装开始时记录的旧实例，始终保留刚安装完成的新实例。
    private static void removePreviousClient(HMCLGameRepository repository,
                                             @Nullable InstalledClient installed, GameInstanceID newId) {
        if (installed == null || installed.instanceId().equals(newId.toString())) {
            return;
        }
        try {
            GameInstanceID oldId = new GameInstanceID(installed.instanceId());
            boolean removed = repository.removeInstanceFromDisk(oldId);
            LOG.info("Previous NorthStar client instance " + installed.instanceId()
                    + (removed ? " removed" : " was not present"));
        } catch (Exception e) {
            LOG.warning("Failed to remove previous NorthStar client instance", e);
        }
    }

    /// 已安装客户端的版本及实例标识。
    private record InstalledClient(String version, String instanceId) {
    }

    /// 已解析的整合包及计划实例名称。
    private record ModpackPlan(Modpack manifest, String name, String modpackVersion) {
    }

    /// 完整安装成功后的实例及整合包版本。
    private record InstallResult(GameInstanceID instanceId, String version) {
    }

    /// 写入新版记录；写入失败时终止更新收尾，保留旧实例。
    private static void writeInstalledMarker(String version, GameInstanceID instanceId) throws IOException {
        Path file = installedMarkerFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, JsonUtils.GSON.toJson(Map.of(
                "version", version,
                "instanceId", instanceId.toString())));
    }

    /// 读取已安装客户端的版本记录;缺失或损坏时返回 null(视为未安装)。
    private static @Nullable InstalledClient readInstalledMarker() {
        try {
            Path file = installedMarkerFile();
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonObject json = JsonUtils.fromNonNullJson(Files.readString(file), JsonObject.class);
            String version = stringField(json, "version");
            String instanceId = stringField(json, "instanceId");
            if (version == null || instanceId == null) {
                return null;
            }
            return new InstalledClient(version, instanceId);
        } catch (IOException | JsonParseException e) {
            return null;
        }
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
