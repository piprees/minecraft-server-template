package com.customdimensions.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Presentation-only tuning for immersive portal previews (Phase 0 onward of
 * the immersive-portals feature — see immersive/PLAN.md). Parsed from the
 * "immersive" field on {@link DimensionConfig.Portal}:
 *
 * <pre>
 *   absent                            -&gt; enabled, all defaults (ON by default)
 *   "immersive": true                 -&gt; enabled, all defaults
 *   "immersive": false                -&gt; null (opt OUT)
 *   "immersive": {}                   -&gt; enabled, all defaults
 *   "immersive": {"enabled": false}   -&gt; null, even with other fields set
 *   "immersive": {"previewDepth": 4}  -&gt; that field clamped, rest defaults
 * </pre>
 *
 * <b>Absent means ON.</b> Immersive started as opt-in while it was risky;
 * it is now the house style for every portal this mod builds, and a
 * dimension that wants a plain vanilla portal says {@code "immersive":
 * false}. Keeping it opt-in would have meant most dimensions never
 * exercising the feature — the opposite of what catches its bugs, which
 * have every one of them been silent absence rather than a crash.
 *
 * Deliberately NOT serialised into portal_links.json zone records: it is
 * transient on {@link PortalDefinition} and re-read from dimension config
 * every boot, exactly like the rest of the portal block (unlike
 * creation-time worldgen config, this presentation feature applies to
 * existing dimensions without a wipe).
 */
public record ImmersiveSettings(
        boolean enabled,
        int previewDepth,
        int previewRadius,
        int refreshInterval,
        int activationRange,
        boolean audio,
        boolean entityPassthrough) {

    public static final int DEFAULT_PREVIEW_DEPTH = 8;
    public static final int MIN_PREVIEW_DEPTH = 1;
    public static final int MAX_PREVIEW_DEPTH = 16;

    public static final int DEFAULT_PREVIEW_RADIUS = 2;
    public static final int MIN_PREVIEW_RADIUS = 0;
    public static final int MAX_PREVIEW_RADIUS = 4;

    public static final int DEFAULT_REFRESH_INTERVAL = 4;
    public static final int MIN_REFRESH_INTERVAL = 2;

    public static final int DEFAULT_ACTIVATION_RANGE = 24;
    public static final int MIN_ACTIVATION_RANGE = 1;
    public static final int MAX_ACTIVATION_RANGE = 64;

    /** Enabled, every field at its default — "immersive": true or {}. */
    public static final ImmersiveSettings DEFAULTS = new ImmersiveSettings(
            true, DEFAULT_PREVIEW_DEPTH, DEFAULT_PREVIEW_RADIUS,
            DEFAULT_REFRESH_INTERVAL, DEFAULT_ACTIVATION_RANGE, true, true);

    /**
     * Parses the raw "immersive" JSON element (boolean or object form, the
     * same dual-form pattern as {@code Portal.shape}/{@code frameBlock}).
     * Never throws — malformed field values fall back to their default
     * rather than rejecting the whole block, matching the rest of
     * DimensionConfig's never-crash parsing policy.
     */
    public static ImmersiveSettings fromJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            // Absent = on. Opting out is an explicit "immersive": false.
            return DEFAULTS;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean() ? DEFAULTS : null;
        }
        if (!element.isJsonObject()) {
            // A malformed value is not a request to turn the feature off:
            // the rest of this parser falls back to defaults rather than
            // rejecting, and so does this.
            return DEFAULTS;
        }
        JsonObject obj = element.getAsJsonObject();
        if (isExplicitlyDisabled(obj)) {
            return null;
        }
        return new ImmersiveSettings(
                true,
                clampInt(obj, "previewDepth", DEFAULT_PREVIEW_DEPTH, MIN_PREVIEW_DEPTH, MAX_PREVIEW_DEPTH),
                clampInt(obj, "previewRadius", DEFAULT_PREVIEW_RADIUS, MIN_PREVIEW_RADIUS, MAX_PREVIEW_RADIUS),
                Math.max(MIN_REFRESH_INTERVAL, intOrDefault(obj, "refreshInterval", DEFAULT_REFRESH_INTERVAL)),
                clampInt(obj, "activationRange", DEFAULT_ACTIVATION_RANGE, MIN_ACTIVATION_RANGE, MAX_ACTIVATION_RANGE),
                boolOrDefault(obj, "audio", true),
                boolOrDefault(obj, "entityPassthrough", true));
    }

    /** An explicit "enabled": false inside the object means not immersive. */
    private static boolean isExplicitlyDisabled(JsonObject obj) {
        JsonElement enabled = obj.get("enabled");
        return enabled != null && enabled.isJsonPrimitive()
                && enabled.getAsJsonPrimitive().isBoolean() && !enabled.getAsBoolean();
    }

    private static int intOrDefault(JsonObject obj, String key, int fallback) {
        JsonElement e = obj.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt();
        }
        return fallback;
    }

    private static int clampInt(JsonObject obj, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, intOrDefault(obj, key, fallback)));
    }

    private static boolean boolOrDefault(JsonObject obj, String key, boolean fallback) {
        JsonElement e = obj.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        return fallback;
    }
}
