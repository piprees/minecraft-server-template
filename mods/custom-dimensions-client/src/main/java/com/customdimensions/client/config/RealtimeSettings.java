package com.customdimensions.client.config;

import com.customdimensions.client.dev.Json;
import com.customdimensions.client.dev.JsonReader;

import java.util.Map;

/**
 * The player's controls over the portal view, as held on disk.
 *
 * <p>No Minecraft types on purpose: everything here is parsed, clamped and
 * rendered without a bootstrapped game, so the rules are unit-testable.
 *
 * <p>Two toggles describe the same far side: {@code renderClientSidePortals} is
 * the enhanced local render, {@code renderServerSidePortals} is the block slab
 * the server streams. They are redundant, so the local render wins and
 * {@link #effectiveServerSide()} is the single value that goes on the wire.
 * The other fields bound the local render and never leave this client.
 */
public record RealtimeSettings(
        boolean renderClientSidePortals,
        int maxRenderDistance,
        boolean distantHorizons,
        boolean renderServerSidePortals,
        boolean spectatorPass,
        boolean apertureBackdrop,
        boolean apertureTerrain,
        boolean apertureFarStamp,
        boolean apertureFarStampEarly) {

    /** The schema this version writes. A file below it is migrated on read. */
    public static final int CONFIG_VERSION = 1;

    /**
     * The enhanced portal is what installing the mod gets you. Turning it off
     * is a choice the player makes.
     */
    public static final boolean DEFAULT_RENDER_CLIENT_SIDE_PORTALS = true;

    /**
     * On, so it is already in effect the moment the local render stops:
     * nothing has to invent a fallback, the slab simply resumes.
     */
    public static final boolean DEFAULT_RENDER_SERVER_SIDE_PORTALS = true;

    /** Chunks of the destination the local view is allowed to reach. */
    public static final int DEFAULT_RENDER_DISTANCE = 16;
    public static final int MIN_RENDER_DISTANCE = 2;
    public static final int MAX_RENDER_DISTANCE = 32;

    /** Extend the far side past the render distance where DH is installed. */
    public static final boolean DEFAULT_DISTANT_HORIZONS = true;

    /**
     * Off. The spectator pass draws the far side through a second
     * {@code WorldRenderer}, which calls {@code WorldRenderer.render} a second
     * time in one frame and so drives a shader pack's whole pipeline twice
     * ({@code TROUBLESHOOTING.md#t97}). The aperture path draws the same far
     * side inside the source frame's own pass.
     */
    public static final boolean DEFAULT_SPECTATOR_PASS = false;

    /**
     * The two halves of what the opening draws, separable so a portal showing
     * the wrong thing can be bisected without a rebuild: the fog-coloured quad
     * behind the far side, and the meshed destination in front of it.
     */
    public static final boolean DEFAULT_APERTURE_BACKDROP = true;
    public static final boolean DEFAULT_APERTURE_TERRAIN = true;

    /**
     * Off. The far stamp rewrites the opening's depth once more at the end of
     * the frame, after every draw that depth-tests, so a shader pack's
     * composite passes read the far end of the captured volume instead of the
     * portal surface a couple of blocks away.
     */
    public static final boolean DEFAULT_APERTURE_FAR_STAMP = false;

    /**
     * Off. The same far depth written at the portal's own draw instead, where
     * a shader pack's deferred programs and its pre-translucent depth copy read
     * it. The source world's own depth is put back before anything tests
     * against it, which needs a mixin because no render phase sits in that
     * window.
     */
    public static final boolean DEFAULT_APERTURE_FAR_STAMP_EARLY = false;

    public static final RealtimeSettings DEFAULTS = new RealtimeSettings(
            DEFAULT_RENDER_CLIENT_SIDE_PORTALS, DEFAULT_RENDER_DISTANCE,
            DEFAULT_DISTANT_HORIZONS, DEFAULT_RENDER_SERVER_SIDE_PORTALS,
            DEFAULT_SPECTATOR_PASS, DEFAULT_APERTURE_BACKDROP, DEFAULT_APERTURE_TERRAIN,
            DEFAULT_APERTURE_FAR_STAMP, DEFAULT_APERTURE_FAR_STAMP_EARLY);

    public RealtimeSettings {
        maxRenderDistance = Math.max(MIN_RENDER_DISTANCE,
                Math.min(MAX_RENDER_DISTANCE, maxRenderDistance));
    }

    /**
     * Whether the server still has to describe the far side. The two views are
     * redundant, so a client rendering its own is never sent the slab however
     * the player has set it.
     */
    public boolean effectiveServerSide() {
        return !this.renderClientSidePortals && this.renderServerSidePortals;
    }

    public RealtimeSettings withRenderClientSidePortals(boolean value) {
        return new RealtimeSettings(value, this.maxRenderDistance, this.distantHorizons,
                this.renderServerSidePortals, this.spectatorPass, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly);
    }

    public RealtimeSettings withMaxRenderDistance(int value) {
        return new RealtimeSettings(this.renderClientSidePortals, value, this.distantHorizons,
                this.renderServerSidePortals, this.spectatorPass, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly);
    }

    public RealtimeSettings withDistantHorizons(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance, value,
                this.renderServerSidePortals, this.spectatorPass, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly);
    }

    public RealtimeSettings withRenderServerSidePortals(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, value, this.spectatorPass, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly);
    }

    public RealtimeSettings withSpectatorPass(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, value, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly);
    }

    /**
     * What the key flips. Only the local render: the player's server-side
     * setting is left alone so it takes effect on its own the moment the
     * local one goes off.
     */
    public RealtimeSettings toggled() {
        return withRenderClientSidePortals(!this.renderClientSidePortals);
    }

    public RealtimeSettings withApertureBackdrop(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass, value,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly);
    }

    public RealtimeSettings withApertureTerrain(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, value, this.apertureFarStamp, this.apertureFarStampEarly);
    }

    public RealtimeSettings withApertureFarStamp(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, value, this.apertureFarStampEarly);
    }

    public RealtimeSettings withApertureFarStampEarly(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, this.apertureFarStamp, value);
    }

    public String toJson() {
        return Json.obj()
                .num("configVersion", CONFIG_VERSION)
                .bool("renderClientSidePortals", this.renderClientSidePortals)
                .num("maxRenderDistance", this.maxRenderDistance)
                .bool("distantHorizons", this.distantHorizons)
                .bool("renderServerSidePortals", this.renderServerSidePortals)
                .bool("spectatorPass", this.spectatorPass)
                .bool("apertureBackdrop", this.apertureBackdrop)
                .bool("apertureTerrain", this.apertureTerrain)
                .bool("apertureFarStamp", this.apertureFarStamp)
                .bool("apertureFarStampEarly", this.apertureFarStampEarly)
                .toString();
    }

    /**
     * Reads a config file. Anything unreadable — malformed, absent, an array,
     * a field of the wrong type — falls back to the default for that field
     * rather than throwing: a config file cannot be allowed to stop the mod
     * loading, because the symptom is a client with no portals and no error.
     */
    public static RealtimeSettings parse(String text) {
        Map<String, Object> raw;
        try {
            raw = JsonReader.object(text);
        } catch (JsonReader.Malformed e) {
            return DEFAULTS;
        }
        if (version(raw) < CONFIG_VERSION) {
            return migrate(raw);
        }
        return new RealtimeSettings(
                bool(raw, "renderClientSidePortals", DEFAULT_RENDER_CLIENT_SIDE_PORTALS),
                integer(raw, "maxRenderDistance", DEFAULT_RENDER_DISTANCE),
                bool(raw, "distantHorizons", DEFAULT_DISTANT_HORIZONS),
                bool(raw, "renderServerSidePortals", DEFAULT_RENDER_SERVER_SIDE_PORTALS),
                bool(raw, "spectatorPass", DEFAULT_SPECTATOR_PASS),
                bool(raw, "apertureBackdrop", DEFAULT_APERTURE_BACKDROP),
                bool(raw, "apertureTerrain", DEFAULT_APERTURE_TERRAIN),
                bool(raw, "apertureFarStamp", DEFAULT_APERTURE_FAR_STAMP),
                bool(raw, "apertureFarStampEarly", DEFAULT_APERTURE_FAR_STAMP_EARLY));
    }

    /**
     * Whether reading this text migrated it, so the store rewrites the file and
     * the stamp lands. A file that will not parse is left exactly as it is.
     */
    public static boolean needsMigration(String text) {
        try {
            return version(JsonReader.object(text)) < CONFIG_VERSION;
        } catch (JsonReader.Malformed e) {
            return false;
        }
    }

    /**
     * An unstamped file, written when the fields were {@code enabled} and
     * {@code fallbackToSlab}. {@code enabled} is the player's own choice and
     * carries over; {@code fallbackToSlab} was written false by a default
     * nobody chose, and an unstamped file was served the slab whenever the
     * local render was off, so server-side comes back on either way.
     */
    private static RealtimeSettings migrate(Map<String, Object> raw) {
        return new RealtimeSettings(
                bool(raw, "enabled", DEFAULT_RENDER_CLIENT_SIDE_PORTALS),
                integer(raw, "maxRenderDistance", DEFAULT_RENDER_DISTANCE),
                bool(raw, "distantHorizons", DEFAULT_DISTANT_HORIZONS),
                DEFAULT_RENDER_SERVER_SIDE_PORTALS,
                DEFAULT_SPECTATOR_PASS,
                DEFAULT_APERTURE_BACKDROP,
                DEFAULT_APERTURE_TERRAIN,
                DEFAULT_APERTURE_FAR_STAMP,
                DEFAULT_APERTURE_FAR_STAMP_EARLY);
    }

    private static int version(Map<String, Object> raw) {
        return integer(raw, "configVersion", 0);
    }

    private static boolean bool(Map<String, Object> raw, String key, boolean fallback) {
        Object value = raw.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    private static int integer(Map<String, Object> raw, String key, int fallback) {
        Object value = raw.get(key);
        return value instanceof Number n ? (int) n.doubleValue() : fallback;
    }
}
