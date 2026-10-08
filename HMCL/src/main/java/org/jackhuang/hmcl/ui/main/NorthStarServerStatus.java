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

import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.ServerAddress;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Queries the NorthStar server's online status and exposes it as a self-refreshing main page
/// node.
///
/// Address resolution follows Minecraft's own rules: an explicit port in
/// [Metadata.NORTHSTAR_SERVER_ADDRESS] is used as is, otherwise the SRV record
/// `_minecraft._tcp.<host>` is looked up through JNDI DNS, and without a record the default port
/// 25565 applies. The status ping speaks the standard Java Edition handshake/status packet
/// protocol. The node refreshes on startup, every five minutes, and when clicked; the latest
/// result is also exposed through [isOnline] so the one-click join button can warn about an
/// offline server.
@NotNullByDefault
public final class NorthStarServerStatus {
    private static final int DEFAULT_PORT = 25565;
    private static final int TIMEOUT_MILLIS = 5000;
    private static final long REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000;
    private static final int HANDSHAKE_PROTOCOL_VERSION = 767; // 1.21; servers accept any value

    /// Result of the most recent completed query, or `null` before the first query settles.
    private static final AtomicReference<@Nullable StatusResult> lastResult = new AtomicReference<>();

    private NorthStarServerStatus() {
    }

    /// Whether the last completed query found the server online, or `null` when unknown.
    public static @Nullable Boolean isOnline() {
        @Nullable StatusResult result = lastResult.get();
        return result != null ? result.online() : null;
    }

    /// Builds the main page status node. It refreshes immediately, then periodically in a daemon
    /// thread; clicking the node triggers an immediate refresh as well.
    public static Node createNode() {
        StatusNode node = new StatusNode();

        Thread refresher = new Thread(() -> {
            while (true) {
                node.refreshAsync();
                try {
                    Thread.sleep(REFRESH_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "northstar-server-status");
        refresher.setDaemon(true);
        refresher.start();

        return node;
    }

    /// Resolves the configured server address, pings it, and stores the result. Runs entirely on
    /// the calling thread; callers are expected to use a background thread.
    private static @Nullable StatusResult query() {
        try {
            ServerAddress address = resolveAddress();
            StatusResult result = ping(address.host(), address.port());
            lastResult.set(result);
            return result;
        } catch (IOException e) {
            LOG.warning("Failed to query NorthStar server status", e);
            lastResult.set(null);
            return null;
        }
    }

    /// Resolves [Metadata.NORTHSTAR_SERVER_ADDRESS]: an explicit port wins; otherwise the SRV
    /// record decides the target and port; without any record the default port applies.
    private static ServerAddress resolveAddress() throws IOException {
        ServerAddress parsed = ServerAddress.parse(Metadata.NORTHSTAR_SERVER_ADDRESS);
        if (parsed.port() != -1) {
            return parsed;
        }

        @Nullable ServerAddress srv = resolveSrv(parsed.host());
        if (srv != null) {
            return srv;
        }
        return new ServerAddress(parsed.host(), DEFAULT_PORT);
    }

    /// Looks up the `_minecraft._tcp.<host>` SRV record, returning the resolved target and port,
    /// or `null` when the record is missing or malformed. Uses the system DNS resolvers.
    private static @Nullable ServerAddress resolveSrv(String host) {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "2000");
        environment.put("com.sun.jndi.dns.timeout.retries", "2");
        try {
            DirContext context = new InitialDirContext(environment);
            Attributes attributes = context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
            Attribute record = attributes.get("SRV");
            if (record == null || record.size() == 0) {
                return null;
            }
            String value = record.get(0).toString();
            String[] parts = value.split("\\s+");
            if (parts.length < 4) {
                LOG.warning("Malformed SRV record for " + host + ": " + value);
                return null;
            }
            String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
            return new ServerAddress(target, Integer.parseInt(parts[2]));
        } catch (javax.naming.NamingException | NumberFormatException e) {
            LOG.info("No usable SRV record for " + host + ": " + e.getMessage());
            return null;
        }
    }

    /// Performs a Minecraft status ping and returns the parsed result, measuring the round trip.
    private static StatusResult ping(String host, int port) throws IOException {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Handshake: packet id 0, protocol version, host, port, next state 1 (status).
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBuffer);
            writeVarint(payload, 0x00);
            writeVarint(payload, HANDSHAKE_PROTOCOL_VERSION);
            writeVarint(payload, hostBytes.length);
            payload.write(hostBytes);
            payload.writeShort(port);
            writeVarint(payload, 1);

            writePacket(out, payloadBuffer.toByteArray());
            writePacket(out, new byte[]{0x00}); // Status request: packet id 0, no payload.

            readVarint(in); // Packet length; the fields below all belong to this packet.
            int packetId = readVarint(in);
            if (packetId != 0x00) {
                throw new IOException("Unexpected status response packet id: " + packetId);
            }
            int jsonLength = readVarint(in);
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            long latency = System.currentTimeMillis() - start;

            JsonObject response = JsonUtils.fromNonNullJson(
                    new String(jsonBytes, StandardCharsets.UTF_8), JsonObject.class);
            if (!response.has("players")) {
                throw new IOException("Malformed status response");
            }
            JsonObject players = response.getAsJsonObject("players");
            int online = players.get("online").getAsInt();
            int max = players.get("max").getAsInt();

            @Nullable String versionName = null;
            if (response.has("version") && response.getAsJsonObject("version").has("name")) {
                versionName = response.getAsJsonObject("version").get("name").getAsString();
            }

            return new StatusResult(true, online, max, latency, versionName);
        } catch (SocketTimeoutException e) {
            throw new IOException("Timed out querying server status", e);
        } catch (com.google.gson.JsonParseException e) {
            throw new IOException("Malformed status response", e);
        }
    }

    /// Writes one length-prefixed packet.
    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        writeVarint(out, payload.length);
        out.write(payload);
        out.flush();
    }

    private static void writeVarint(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int readVarint(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 32) {
                throw new IOException("Varint is too long");
            }
        }
    }

    /// One completed server status query.
    private record StatusResult(boolean online, int playersOnline, int playersMax,
                                long latencyMillis, @Nullable String versionName) {
    }

    /// The status display: a colored dot plus a text line, refreshed in the background.
    private static final class StatusNode extends HBox {
        private final Region dot;
        private final Label label;
        private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

        StatusNode() {
            super(6);

            this.dot = new Region();
            this.label = new Label(i18n("northstar.server.status.checking"));

            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(4, 8, 4, 8));
            setStyle("-fx-background-color: rgba(0, 0, 0, 0.35); -fx-background-radius: 8;");

            dot.setMinSize(8, 8);
            dot.setMaxSize(8, 8);
            dot.setStyle("-fx-background-color: #9E9E9E; -fx-background-radius: 4;");
            label.setStyle("-fx-opacity: 0.85;");
            getChildren().setAll(dot, label);

            FXUtils.onClicked(this, this::refreshAsync);
        }

        /// Queries the server on an IO thread and applies the result on the FX thread; coalesces
        /// overlapping refreshes.
        void refreshAsync() {
            if (!refreshInFlight.compareAndSet(false, true)) {
                return;
            }
            Schedulers.io().execute(() -> {
                @Nullable StatusResult result = query();
                Platform.runLater(() -> {
                    refreshInFlight.set(false);
                    apply(result);
                });
            });
        }

        private void apply(@Nullable StatusResult result) {
            if (result == null) {
                dot.setStyle("-fx-background-color: #F44336; -fx-background-radius: 4;");
                label.setText(i18n("northstar.server.status.offline"));
                FXUtils.installFastTooltip(this, i18n("northstar.server.status.click_refresh"));
                return;
            }
            dot.setStyle("-fx-background-color: #4CAF50; -fx-background-radius: 4;");
            label.setText(i18n("northstar.server.status.online",
                    result.playersOnline(), result.playersMax(), result.latencyMillis()));
            if (result.versionName() != null) {
                FXUtils.installFastTooltip(this, i18n("northstar.server.status.tooltip", result.versionName()));
            }
        }
    }
}
