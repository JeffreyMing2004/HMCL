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
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

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
    private NorthStarAnnouncements() {
    }

    /// Fetches announcements asynchronously and invokes [callback] on the FX thread with a
    /// node displaying them. The callback is skipped when no announcements are available.
    public static void loadAsync(Consumer<Region> callback) {
        Task<List<Announcement>> task = Task.supplyAsync(NorthStarAnnouncements::fetch);
        task.whenComplete(Schedulers.javafx(), (announcements, exception) -> {
            if (exception != null || announcements == null || announcements.isEmpty())
                return;
            callback.accept(createCard(announcements));
        }).start();
    }

    /// Requests the announcement list from the NorthStar server.
    private static List<Announcement> fetch() throws IOException {
        JsonObject json;
        try {
            json = JsonUtils.fromNonNullJson(NetworkUtils.doGet(Metadata.NORTHSTAR_ANNOUNCEMENT_URL), JsonObject.class);
        } catch (IOException | JsonParseException e) {
            throw new IOException("Failed to fetch NorthStar announcements", e);
        }

        JsonArray array = Optional.ofNullable(json.get("announcements"))
                .filter(JsonElement::isJsonArray)
                .map(JsonElement::getAsJsonArray)
                .orElseThrow(() -> new IOException("NorthStar announcement response has no announcements array"));

        List<Announcement> announcements = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject())
                continue;
            JsonObject item = element.getAsJsonObject();
            String title = stringField(item, "title");
            String content = stringField(item, "content");
            if (title == null || content == null)
                continue;
            announcements.add(new Announcement(title, content, stringField(item, "date")));
        }
        return announcements;
    }

    /// Builds the announcement card shown at the top of the main page.
    private static Region createCard(List<Announcement> announcements) {
        VBox card = new VBox(12);
        card.getStyleClass().addAll("card", "announcement");

        for (Announcement announcement : announcements) {
            BorderPane titleBar = new BorderPane();
            titleBar.getStyleClass().add("title");
            titleBar.setLeft(new Label(announcement.date() == null
                    ? announcement.title()
                    : announcement.title() + "  ·  " + announcement.date()));

            TextFlow body = FXUtils.segmentToTextFlow(announcement.content(), Controllers::onHyperlinkAction);
            body.setLineSpacing(4);

            card.getChildren().add(new VBox(6, titleBar, body));
        }

        VBox box = new VBox(card);
        box.setPadding(new Insets(15));
        return box;
    }

    private static @Nullable String stringField(JsonObject json, String name) {
        return Optional.ofNullable(json.get(name))
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /// A single announcement served by the NorthStar server.
    private record Announcement(String title, String content, @Nullable String date) {
    }
}
