package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.mixin.MultiNoiseBiomeSourceAccessor;
import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The global biome suppress list (settings.json {@code "suppress":
 * {"biomes": [...]}}, consumer overlay merged) applied to biome sources.
 *
 * <p>Every world in the system is mod-controlled, so one filter covers all
 * three shapes: listed/mixed sources strip suppressed ids from their allow
 * list in {@code buildMixedSource}, every custom dimension's built options
 * pass through {@link #filterOptions} before biome patches, and the base
 * worlds (overworld, nether, end, paradise_lost) pass through the same
 * filter at the {@code CreateWorldsMixin} redirect — the one definition of
 * which worlds boot eagerly.
 *
 * <p>Removing a parameter point from a multi-noise source is clean: the
 * nearest-neighbour search resolves to the next-closest biome, no holes.
 * Non-noise sources (the end's, flat/void layouts) pass through unchanged.
 * Worldgen is creation-time per chunk — existing chunks keep their biomes;
 * suppression governs newly generated chunks from the next boot.
 *
 * <p>Per-dimension {@code biomePatches} run AFTER this filter, so an
 * author's explicit patch can still stamp a suppressed biome — specific
 * beats general, the same precedence as {@code structures.include}.
 */
public final class BiomeSuppression {

    private static boolean warnedUnknown = false;

    private BiomeSuppression() {
    }

    /** Suppressed biome ids as parsed Identifiers (invalid entries dropped). */
    public static Set<Identifier> suppressedIds() {
        List<String> raw = MultiverseConfig.getInstance().getSuppressedBiomes();
        if (raw.isEmpty()) {
            return Set.of();
        }
        Set<Identifier> ids = new LinkedHashSet<>();
        for (String s : raw) {
            Identifier id = Identifier.tryParse(s.trim().toLowerCase());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * The source with suppressed biomes' parameter points removed, or the
     * source unchanged when there is nothing to do. Refuses to empty a
     * source (WARN + keep) — a world with zero biomes cannot generate.
     */
    public static BiomeSource filter(BiomeSource source, String worldName) {
        Set<Identifier> suppressed = suppressedIds();
        if (suppressed.isEmpty() || !(source instanceof MultiNoiseBiomeSource base)) {
            return source;
        }
        MultiNoiseUtil.Entries<RegistryEntry<Biome>> entries =
                ((MultiNoiseBiomeSourceAccessor) base).invokeGetBiomeEntries();
        List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> kept = new ArrayList<>();
        Set<Identifier> droppedIds = new HashSet<>();
        for (Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>> pair : entries.getEntries()) {
            Identifier id = pair.getSecond().getKey().map(RegistryKey::getValue).orElse(null);
            if (id != null && suppressed.contains(id)) {
                droppedIds.add(id);
                continue;
            }
            kept.add(pair);
        }
        if (droppedIds.isEmpty()) {
            return source;
        }
        if (kept.isEmpty()) {
            MultiverseServer.LOGGER.warn(
                    "World {}: suppress.biomes would remove every biome — source kept unfiltered",
                    worldName);
            return source;
        }
        MultiverseServer.LOGGER.info(
                "World {}: {} biome parameter point(s) suppressed ({} biome(s): {})",
                worldName, entries.getEntries().size() - kept.size(), droppedIds.size(), droppedIds);
        return MultiNoiseBiomeSource.create(new MultiNoiseUtil.Entries<>(kept));
    }

    /**
     * {@link #filter} lifted to DimensionOptions: rebuilds the noise
     * generator around the filtered source, returns the input unchanged for
     * non-noise generators or when nothing was suppressed.
     */
    public static DimensionOptions filterOptions(DimensionOptions options, String worldName) {
        if (suppressedIds().isEmpty()
                || !(options.chunkGenerator() instanceof NoiseChunkGenerator noiseGen)) {
            return options;
        }
        BiomeSource filtered = filter(noiseGen.getBiomeSource(), worldName);
        if (filtered == noiseGen.getBiomeSource()) {
            return options;
        }
        return new DimensionOptions(options.dimensionTypeEntry(),
                new NoiseChunkGenerator(filtered, noiseGen.getSettings()));
    }

    /** Once per boot: WARN for suppress ids that match no registered biome. */
    public static void warnUnknownSuppressedBiomes(Registry<Biome> biomeRegistry) {
        List<String> suppressed = MultiverseConfig.getInstance().getSuppressedBiomes();
        if (warnedUnknown || suppressed.isEmpty()) {
            return;
        }
        warnedUnknown = true;
        for (String id : suppressed) {
            Identifier parsed = Identifier.tryParse(id.trim().toLowerCase());
            if (parsed == null
                    || biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, parsed)).isEmpty()) {
                MultiverseServer.LOGGER.warn(
                        "settings.json suppress.biomes id '{}' matches no registered biome — "
                        + "it suppresses nothing (typo, or the mod that owns it is not installed)",
                        id);
            }
        }
    }
}
