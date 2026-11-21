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

    /**
     * The seed a level generates from (decisions.md RB-37): {@code seed} to use,
     * whether to {@code write} it as the level's record, and a {@code warning}
     * to log once, or null.
     */
    public record SeedChoice(long seed, boolean write, String warning) {}

    /**
     * Chooses a level's seed. A region folder without chunks takes the
     * definition's seed ({@code wanted}). One with chunks keeps the seed they
     * generated from, so its terrain stays continuous: the {@code recorded}
     * seed, or, for chunks older than any record, {@code unrecorded} (the
     * seed the level had before records: the world seed for a base world,
     * the definition's for a lazy one). Keeping a seed other than the
     * definition's warns, naming both and the remedy.
     */
    public static SeedChoice chooseSeed(String level, long wanted, Long recorded, boolean holdsChunks, long unrecorded) {
        if (!holdsChunks) return new SeedChoice(wanted, recorded == null || recorded != wanted, null);
        long kept = recorded != null ? recorded : unrecorded;
        String warning = kept == wanted ? null : level + ": its region folder holds chunks generated from seed " + kept
                + ", and its definition's seed is " + wanted + "; it keeps generating from " + kept
                + " so its terrain stays continuous. To generate from " + wanted + ", wipe the dimension"
                + " (./ops reset-seed for a base world, /customdim delete for a lazy one); to silence this, set the definition's seed to "
                + kept + ".";
        return new SeedChoice(kept, recorded == null, warning);
    }

    /** The chunk holding {@code spawn}, or 0,0 without one. */
    public static ChunkPos spawnChunk(BlockPos spawn) {
        return spawn == null ? new ChunkPos(0, 0) : new ChunkPos(Math.floorDiv(spawn.x(), 16), Math.floorDiv(spawn.z(), 16));
    }

    /**
     * How far a level's terrain is pre-generated at full detail: the larger of
     * its player and generation borders, each falling back to
     * {@code settings.json}'s default; null when neither is set anywhere.
     */
    public static Integer lodReach(Borders borders, JsonObject defaults) {
        Integer player = borders != null && borders.player() != null ? borders.player() : defaultBorder(defaults, "player");
        Integer generation = borders != null && borders.generation() != null ? borders.generation() : defaultBorder(defaults, "generation");
        if (player == null) return generation;
        return generation == null ? player : Math.max(player, generation);
    }

    private static Integer defaultBorder(JsonObject defaults, String name) {
        if (defaults == null || !defaults.has("borders") || !defaults.get("borders").isJsonObject()) return null;
        JsonElement value = defaults.getAsJsonObject("borders").get(name);
        return value == null || value.isJsonNull() ? null : value.getAsInt();
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
