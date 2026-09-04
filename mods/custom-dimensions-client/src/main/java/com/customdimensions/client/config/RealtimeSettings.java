package com.customdimensions.client.config;

import com.customdimensions.client.dev.Json;
import com.customdimensions.client.dev.JsonReader;

import java.util.Map;

/**
 * The player's controls over the real-time portal view, as held on disk.
 *
 * <p>No Minecraft types on purpose: everything here is parsed, clamped and
 * rendered without a bootstrapped game, so the rules are unit-testable.
 *
 * <p>{@code enabled} is the whole feature's opt-in and the only field the
 * server is told about (through {@link #fallbackToSlab}); the other three
 * bound the local render and never leave this client.
 */
public record RealtimeSettings(
        boolean enabled,
        int maxRenderDistance,
        boolean distantHorizons,
        boolean fallbackToSlab) {

    /**
     * The enhanced portal is what installing the mod gets you. Turning it off
     * is a choice the player makes.
     */
    public static final boolean DEFAULT_ENABLED = true;

    /** Chunks of the destination the local view is allowed to reach. */
    public static final int DEFAULT_RENDER_DISTANCE = 16;
    public static final int MIN_RENDER_DISTANCE = 2;
    public static final int MAX_RENDER_DISTANCE = 32;

    /** Extend the far side past the render distance where DH is installed. */
    public static final boolean DEFAULT_DISTANT_HORIZONS = true;

    /**
     * Hand the far side back to the server. The two paths are exclusive: a
     * client asking for the slab is not sent a frame or a destination chunk
     * either, so this turns the local view off however {@code enabled} is set.
     * An opt-out, so it is off unless the player asks.
     */
    public static final boolean DEFAULT_FALLBACK_TO_SLAB = false;

    public static final RealtimeSettings DEFAULTS = new RealtimeSettings(
            DEFAULT_ENABLED, DEFAULT_RENDER_DISTANCE, DEFAULT_DISTANT_HORIZONS,
            DEFAULT_FALLBACK_TO_SLAB);

    public RealtimeSettings {
        maxRenderDistance = Math.max(MIN_RENDER_DISTANCE,
                Math.min(MAX_RENDER_DISTANCE, maxRenderDistance));
    }

    public RealtimeSettings withEnabled(boolean value) {
        return new RealtimeSettings(value, this.maxRenderDistance, this.distantHorizons,
                this.fallbackToSlab);
    }

    public RealtimeSettings withMaxRenderDistance(int value) {
        return new RealtimeSettings(this.enabled, value, this.distantHorizons,
                this.fallbackToSlab);
    }

    public RealtimeSettings withDistantHorizons(boolean value) {
        return new RealtimeSettings(this.enabled, this.maxRenderDistance, value,
                this.fallbackToSlab);
    }

    public RealtimeSettings withFallbackToSlab(boolean value) {
        return new RealtimeSettings(this.enabled, this.maxRenderDistance, this.distantHorizons,
                value);
    }

    public RealtimeSettings toggled() {
        return withEnabled(!this.enabled);
    }

    public String toJson() {
        return Json.obj()
                .bool("enabled", this.enabled)
                .num("maxRenderDistance", this.maxRenderDistance)
                .bool("distantHorizons", this.distantHorizons)
                .bool("fallbackToSlab", this.fallbackToSlab)
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
        return new RealtimeSettings(
                bool(raw, "enabled", DEFAULT_ENABLED),
                integer(raw, "maxRenderDistance", DEFAULT_RENDER_DISTANCE),
                bool(raw, "distantHorizons", DEFAULT_DISTANT_HORIZONS),
                bool(raw, "fallbackToSlab", DEFAULT_FALLBACK_TO_SLAB));
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
