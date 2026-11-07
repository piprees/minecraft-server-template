package com.customdimensions.server.level;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.ChunkPos;
import com.customdimensions.core.definition.Borders;
import com.customdimensions.core.definition.Seed;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The definition-to-level arithmetic that needs no running game. */
public final class LevelMaths {
    /** Vanilla's largest world-border diameter, {@code WorldBorder.MAX_SIZE}. */
    public static final double MAX_BORDER_SIZE = 5.9999968E7;

    private LevelMaths() {}

    /** A border of radius {@code r} around 0,0 is {@code 2r} across, capped at vanilla's maximum. */
    public static double borderSize(int radius) {
        if (radius <= 0) throw new IllegalArgumentException("border radius must be positive, got " + radius);
        return Math.min(2.0 * radius, MAX_BORDER_SIZE);
    }

    /** The level seed: the definition's own, or the world seed for {@code "env"} or none. */
    public static long resolveSeed(Seed seed, long worldSeed) {
        return seed == null || seed.fromEnv() ? worldSeed : seed.value();
    }

    /** The chunk holding {@code spawn}, or 0,0 without one. */
    public static ChunkPos spawnChunk(BlockPos spawn) {
        return spawn == null ? new ChunkPos(0, 0) : new ChunkPos(Math.floorDiv(spawn.x(), 16), Math.floorDiv(spawn.z(), 16));
    }

    /**
     * The player border radius: the definition's {@code borders.player}, else
     * {@code settings.json}'s {@code defaults.borders.player}, else null (no border set).
     */
    public static Integer playerBorderRadius(Borders borders, JsonObject defaults) {
        if (borders != null && borders.player() != null) return borders.player();
        if (defaults == null || !defaults.has("borders") || !defaults.get("borders").isJsonObject()) return null;
        JsonElement player = defaults.getAsJsonObject("borders").get("player");
        return player == null || player.isJsonNull() ? null : player.getAsInt();
    }
}
