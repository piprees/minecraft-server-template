package com.customdimensions.client.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The live settings and their file. Takes a {@link Path} rather than reaching
 * for the loader's config directory, so the rules are exercised against a temp
 * directory in tests and the game supplies the real one.
 *
 * <p>Disk failure is never fatal: an unwritable file leaves the settings
 * applying for the session and logs once. The listener fires on every change
 * so the handshake can re-declare what this client will render.
 */
public final class RealtimeSettingsStore {

    /** Grepped in the client log to prove a change was applied and to what. */
    public static final String CHANGE_MARKER = "companion-client:realtime-settings";

    private static final Logger LOGGER = LoggerFactory.getLogger("customdimensionsclient");

    private final Path file;
    private volatile RealtimeSettings current = RealtimeSettings.DEFAULTS;
    private volatile Consumer<RealtimeSettings> listener = settings -> {};

    public RealtimeSettingsStore(Path file) {
        this.file = file;
    }

    public RealtimeSettings current() {
        return this.current;
    }

    /** Replaces the listener; the last one registered wins. */
    public void onChange(Consumer<RealtimeSettings> listener) {
        this.listener = listener == null ? settings -> {} : listener;
    }

    /**
     * Reads the file, writing the defaults when there is none so the player has
     * something to edit. A file below the current schema is migrated and
     * written back, so the stamp lands and the migration runs once. Does not
     * notify — nothing has changed yet.
     */
    public RealtimeSettings load() {
        if (!Files.isRegularFile(this.file)) {
            this.current = RealtimeSettings.DEFAULTS;
            write(this.current);
            return this.current;
        }
        try {
            String text = Files.readString(this.file, StandardCharsets.UTF_8);
            this.current = RealtimeSettings.parse(text);
            if (RealtimeSettings.needsMigration(text)) {
                write(this.current);
            }
        } catch (IOException e) {
            LOGGER.warn("could not read {}, using defaults", this.file, e);
            this.current = RealtimeSettings.DEFAULTS;
        }
        return this.current;
    }

    public RealtimeSettings save(RealtimeSettings settings) {
        this.current = settings;
        write(settings);
        LOGGER.info("{} renderClientSidePortals={} maxRenderDistance={} distantHorizons={} "
                        + "renderServerSidePortals={} effectiveServerSide={}",
                CHANGE_MARKER, settings.renderClientSidePortals(), settings.maxRenderDistance(),
                settings.distantHorizons(), settings.renderServerSidePortals(),
                settings.effectiveServerSide());
        this.listener.accept(settings);
        return settings;
    }

    public RealtimeSettings toggle() {
        return save(this.current.toggled());
    }

    private void write(RealtimeSettings settings) {
        try {
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(this.file, settings.toJson() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("could not write {}; the change applies for this session only",
                    this.file, e);
        }
    }
}
