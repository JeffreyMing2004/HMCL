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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.jfoenix.controls.JFXButton;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Loads NorthStar server announcements and displays them on the main page.
///
/// The announcement API is expected to answer [Metadata.NORTHSTAR_ANNOUNCEMENT_URL] with:
///
/// ```json
/// {
///   "announcements": [
///     {
///       "title": "公告标题",
///       "content": "正文,支持 <a href=\"https://...\">链接</a>",
///       "date": "2026-10-07"
///     }
///   ]
/// }
/// ```
///
/// When the server is unreachable or returns no announcements (e.g. the API is not deployed
/// yet), the main page simply shows nothing.
@NotNullByDefault
public final class NorthStarAnnouncements {
    /// Bounds the persisted dismissal history so a long-lived installation does not grow indefinitely.
    private static final int MAX_DISMISSALS = 200;

    /// Prevents construction of this announcement loader.
    private NorthStarAnnouncements() {
    }

    /// Fetches announcements asynchronously and invokes [callback] on the FX thread with a
    /// node displaying them. The callback is skipped when no announcements are available.
    public static void loadAsync(Consumer<Region> callback) {
        Task<@Unmodifiable List<Announcement>> task = Task.supplyAsync(NorthStarAnnouncements::fetch);
        task.whenComplete(Schedulers.javafx(), (announcements, exception) -> {
            if (exception != null || announcements == null || announcements.isEmpty())
                return;
            if (!visibleAnnouncements(announcements, settings().getNorthstarDismissedAnnouncements()).isEmpty()) {
                callback.accept(createCard(announcements));
            }
        }).start();
    }

    /// Requests the announcement list from the NorthStar server.
    private static @Unmodifiable List<Announcement> fetch() throws IOException {
        JsonObject json;
        try {
            json = JsonUtils.fromNonNullJson(NetworkUtils.doGet(Metadata.NORTHSTAR_ANNOUNCEMENT_URL), JsonObject.class);
        } catch (IOException | JsonParseException e) {
            throw new IOException("Failed to fetch NorthStar announcements", e);
        }

        return parseAnnouncements(json);
    }

    /// Parses valid entries in server order, preserving compatibility with manifests without IDs.
    static @Unmodifiable List<Announcement> parseAnnouncements(JsonObject json) throws IOException {
        JsonArray array = Optional.ofNullable(json.get("announcements"))
                .filter(JsonElement::isJsonArray)
                .map(JsonElement::getAsJsonArray)
                .orElseThrow(() -> new IOException("NorthStar announcement response has no announcements array"));

        List<Announcement> announcements = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject())
                continue;
            JsonObject item = element.getAsJsonObject();
            @Nullable String title = stringField(item, "title");
            @Nullable String content = stringField(item, "content");
            if (title == null || content == null)
                continue;
            announcements.add(new Announcement(title, content, stringField(item, "date")));
        }
        return List.copyOf(announcements);
    }

    /// Builds a card showing the newest non-dismissed announcement and an expander for older entries.
    private static Region createCard(@Unmodifiable List<Announcement> announcements) {
        VBox card = new VBox(12);
        card.getStyleClass().addAll("card", "announcement");
        card.setMaxWidth(480);
        VBox box = new VBox(card);
        box.setPadding(new Insets(15));
        BooleanProperty expanded = new SimpleBooleanProperty(false);
        renderCard(card, box, announcements, expanded);
        return box;
    }

    /// Rebuilds the visible entries after expanding or dismissing; hides all layout space when empty.
    private static void renderCard(VBox card, VBox box, @Unmodifiable List<Announcement> announcements,
                                   BooleanProperty expanded) {
        @Unmodifiable List<Announcement> visible = visibleAnnouncements(announcements,
                settings().getNorthstarDismissedAnnouncements());
        card.getChildren().clear();
        box.setVisible(!visible.isEmpty());
        box.setManaged(!visible.isEmpty());
        int count = expanded.get() ? visible.size() : Math.min(1, visible.size());
        for (int index = 0; index < count; index++) {
            Announcement announcement = visible.get(index);
            HBox titleBar = new HBox(6);
            titleBar.setAlignment(Pos.CENTER_LEFT);
            titleBar.getStyleClass().add("title");
            Label title = new Label(announcement.date() == null
                    ? announcement.title()
                    : announcement.title() + "  ·  " + announcement.date());
            title.setWrapText(true);
            title.setMinWidth(0);
            title.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(title, Priority.ALWAYS);
            JFXButton close = new JFXButton();
            close.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            close.setGraphic(SVG.CLOSE.createIcon(10));
            close.getStyleClass().add("toggle-icon-tiny");
            close.setAccessibleText(i18n("northstar.announcement.dismiss"));
            FXUtils.installFastTooltip(close, i18n("northstar.announcement.dismiss"));
            close.setOnAction(event -> {
                rememberDismissal(settings().getNorthstarDismissedAnnouncements(), announcement);
                renderCard(card, box, announcements, expanded);
            });
            titleBar.getChildren().setAll(title, close);

            TextFlow body = FXUtils.segmentToTextFlow(announcement.content(), Controllers::onHyperlinkAction);
            body.setLineSpacing(4);

            card.getChildren().add(new VBox(6, titleBar, body));
        }
        if (visible.size() > 1) {
            JFXButton toggle = new JFXButton(expanded.get()
                    ? i18n("northstar.announcement.collapse")
                    : i18n("northstar.announcement.expand", visible.size() - 1));
            toggle.setOnAction(event -> {
                expanded.set(!expanded.get());
                renderCard(card, box, announcements, expanded);
            });
            card.getChildren().add(toggle);
        }
    }

    /// Filters dismissed content without depending on announcement order or an optional server ID.
    static @Unmodifiable List<Announcement> visibleAnnouncements(List<Announcement> announcements,
                                                                  List<String> dismissed) {
        return announcements.stream().filter(item -> !dismissed.contains(item.dismissalKey())).toList();
    }

    /// Remembers a dismissal once and discards the oldest fingerprints when the history is full.
    static void rememberDismissal(List<String> dismissed, Announcement announcement) {
        String key = announcement.dismissalKey();
        dismissed.remove(key);
        dismissed.add(key);
        while (dismissed.size() > MAX_DISMISSALS) {
            dismissed.remove(0);
        }
    }

    /// Reads an optional non-blank primitive manifest field.
    private static @Nullable String stringField(JsonObject json, String name) {
        return Optional.ofNullable(json.get(name))
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /// A single announcement; changing its title, content or date makes it visible again after dismissal.
    record Announcement(String title, String content, @Nullable String date) {
        /// Returns a stable SHA-256 fingerprint with unambiguous JSON field boundaries.
        String dismissalKey() {
            String identity = JsonUtils.GSON.toJson(List.of(title, content, Objects.requireNonNullElse(date, "")));
            return DigestUtils.digestToString("SHA-256", identity.getBytes(StandardCharsets.UTF_8));
        }
    }
}
