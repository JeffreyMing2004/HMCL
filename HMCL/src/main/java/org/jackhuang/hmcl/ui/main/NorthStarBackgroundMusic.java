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

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Plays the bundled NorthStar background music on a loop.
///
/// The OGG track is decoded through the vorbisspi service provider registered with
/// [javax.sound.sampled]. Playback streams from the extracted audio file, so memory use stays
/// constant regardless of track length. Whether music plays at all is controlled by
/// [SettingsManager] settings; when enabled, the launcher shows a reminder toast on every start
/// so users know the music can be turned off.
@NotNullByDefault
public final class NorthStarBackgroundMusic {
    private static final String RESOURCE = "/assets/audio/background.ogg";
    private static final String CACHE_FILE_NAME = "background.ogg";

    /// Guards interruptions of the wait between track restarts.
    private static final Object LOCK = new Object();

    /// Whether music playback is currently desired; cleared by [stop].
    private static volatile boolean running = false;

    /// The line currently streaming audio, or `null` between tracks; used by [applyVolume].
    private static volatile @Nullable SourceDataLine currentLine;

    private NorthStarBackgroundMusic() {
    }

    /// Starts the looping player on a daemon thread when background music is enabled in settings.
    /// Does nothing when music is disabled or already playing.
    public static void startIfEnabled() {
        if (running || !SettingsManager.settings().northstarBackgroundMusicProperty().get()) {
            return;
        }
        running = true;
        Thread thread = new Thread(NorthStarBackgroundMusic::playLoop, "northstar-background-music");
        thread.setDaemon(true);
        thread.start();
    }

    /// Stops playback. Has no effect when nothing is playing; the player can be restarted later
    /// through [startIfEnabled].
    public static void stop() {
        running = false;
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }

    /// Applies the configured volume ([SettingsManager] `northstarMusicVolume`, a linear factor
    /// in `[0, 1]`) to the playing line, if any. Called when the volume slider changes so the
    /// adjustment is heard immediately, not just on the next track restart.
    public static void applyVolume() {
        @Nullable SourceDataLine line = currentLine;
        if (line != null) {
            setVolume(line);
        }
    }

    /// Opens the track in a loop until [stop] is requested. Any playback error disables further
    /// attempts instead of spamming logs from a tight loop.
    private static void playLoop() {
        try {
            Path audioFile = extractAudio();
            while (running) {
                playOnce(audioFile);
                if (running) {
                    synchronized (LOCK) {
                        if (running) {
                            LOCK.wait(500);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.warning("Background music is unavailable", e);
        }
    }

    /// Streams the whole track into an output line once, then closes it.
    private static void playOnce(Path audioFile) throws IOException {
        SourceDataLine line = null;
        // The Vorbis SPI reports the encoded source format, so an explicit conversion to a fully
        // specified PCM format (derived from the source) is required before streaming to a line.
        try (AudioInputStream encodedIn = AudioSystem.getAudioInputStream(audioFile.toFile())) {
            AudioFormat sourceFormat = encodedIn.getFormat();
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2,
                    sourceFormat.getSampleRate(),
                    false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(pcmFormat, encodedIn)) {
                line = AudioSystem.getSourceDataLine(in.getFormat());
                line.open(in.getFormat(), 1 << 16);
                setVolume(line);
                line.start();
                currentLine = line;

                byte[] buffer = new byte[1 << 16];
                int read;
                while (running && (read = in.read(buffer)) != -1) {
                    line.write(buffer, 0, read);
                }
                line.drain();
            }
        } catch (UnsupportedAudioFileException | LineUnavailableException | IllegalArgumentException e) {
            LOG.warning("Failed to play background music", e);
            running = false;
        } finally {
            currentLine = null;
            if (line != null) {
                line.close();
            }
        }
    }

    /// Applies the configured linear volume (`0` silent, `1` full volume) to the line, converted
    /// to decibels and clamped to the mixer's supported gain range.
    private static void setVolume(SourceDataLine line) {
        if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        double linear = Math.min(1.0, Math.max(0.0,
                SettingsManager.settings().northstarMusicVolumeProperty().get()));
        FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        float decibel = linear > 0 ? (float) (20.0 * Math.log10(linear)) : gain.getMinimum();
        gain.setValue(Math.max(gain.getMinimum(), Math.min(0.0f, decibel)));
    }

    /// Copies the bundled track next to the launcher data directory once and returns its path,
    /// because the audio system cannot read entries inside the launcher jar.
    private static Path extractAudio() throws IOException {
        Path target = Metadata.HMCL_LOCAL_HOME.resolve("cache").resolve(CACHE_FILE_NAME);
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(target.getParent());
            try (InputStream in = NorthStarBackgroundMusic.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IOException("Missing background music resource " + RESOURCE);
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }
}
