package com.customdimensions.core.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A definition's {@code difficulty} block over {@code settings.json}'s
 * {@code defaults.difficulty}, field by field. A mob's multiplier is
 * {@code mobMultiplier × depthFactor(y)}; a {@code mobMultiplier} of 0 or less
 * leaves mobs unscaled. Player luck is an additive {@code playerLuck − 1}.
 */
public record Difficulty(boolean hostileSpawning, double mobMultiplier, double playerLuck, DepthScaling depthScaling,
                         boolean health, boolean damage, boolean armor, boolean speed, boolean knockback) {

    public static final Difficulty NONE = new Difficulty(true, 1.0, 1.0, null, true, true, true, false, false);

    private static final double EPSILON = 1e-6;

    /** Linear {@code minMultiplier..maxMultiplier} from {@code startY} down to {@code endY}. */
    public record DepthScaling(int startY, int endY, double minMultiplier, double maxMultiplier) {
        public double factor(double y) {
            if (startY == endY || y >= startY) return minMultiplier;
            if (y <= endY) return maxMultiplier;
            double t = (startY - y) / (double) (startY - endY);
            return minMultiplier + (maxMultiplier - minMultiplier) * t;
        }
    }

    /** {@code difficulty} over {@code defaults} (either may be null). */
    public static Difficulty of(JsonObject difficulty, JsonObject defaults) {
        JsonObject d = difficulty == null ? new JsonObject() : difficulty;
        JsonObject f = defaults == null ? new JsonObject() : defaults;
        JsonObject attrs = object(d, "attributes");
        JsonObject attrDefaults = object(f, "attributes");
        JsonObject depth = d.has("depthScaling") ? object(d, "depthScaling") : object(f, "depthScaling");
        DepthScaling scaling = depth != null && bool(depth, null, "enabled", false)
                ? new DepthScaling(integer(depth, "startY", 64), integer(depth, "endY", -64),
                        number(depth, null, "minMultiplier", 1.0), number(depth, null, "maxMultiplier", 1.5))
                : null;
        return new Difficulty(
                bool(d, f, "hostileSpawning", true),
                number(d, f, "mobMultiplier", 1.0),
                number(d, f, "playerLuck", 1.0),
                scaling,
                bool(attrs, attrDefaults, "health", true),
                bool(attrs, attrDefaults, "damage", true),
                bool(attrs, attrDefaults, "armor", true),
                bool(attrs, attrDefaults, "speed", false),
                bool(attrs, attrDefaults, "knockback", false));
    }

    public double depthFactor(double y) {
        return depthScaling == null ? 1.0 : depthScaling.factor(y);
    }

    /** The multiplier for a mob that spawns at height {@code y}; 1.0 when unscaled. */
    public double mobFactor(double y) {
        return mobMultiplier <= 0.0 ? 1.0 : mobMultiplier * depthFactor(y);
    }

    /** True when a mob spawning at {@code y} takes modifiers at all. */
    public boolean scales(double y) {
        return Math.abs(mobFactor(y) - 1.0) >= EPSILON;
    }

    /** Movement speed scales by a quarter of the multiplier's excess. */
    public static double speedFactor(double factor) {
        return 1.0 + (factor - 1.0) * 0.25;
    }

    /** The additive luck a player holds inside the dimension; 0 means no modifier. */
    public double luckBonus() {
        return Math.abs(playerLuck - 1.0) < EPSILON ? 0.0 : playerLuck - 1.0;
    }

    private static JsonObject object(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static boolean bool(JsonObject o, JsonObject fallback, String key, boolean otherwise) {
        if (o != null && o.has(key) && !o.get(key).isJsonNull()) return o.get(key).getAsBoolean();
        if (fallback != null && fallback.has(key) && !fallback.get(key).isJsonNull()) return fallback.get(key).getAsBoolean();
        return otherwise;
    }

    private static double number(JsonObject o, JsonObject fallback, String key, double otherwise) {
        if (o != null && o.has(key) && !o.get(key).isJsonNull()) return o.get(key).getAsDouble();
        if (fallback != null && fallback.has(key) && !fallback.get(key).isJsonNull()) return fallback.get(key).getAsDouble();
        return otherwise;
    }

    private static int integer(JsonObject o, String key, int otherwise) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : otherwise;
    }
}
