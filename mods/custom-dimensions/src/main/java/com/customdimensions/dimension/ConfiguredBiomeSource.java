package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.mixin.MultiNoiseBiomeSourceAccessor;
import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A managed dimension generates the biome source this mod built for it.
 *
 * <p>The DIMENSION registry is decoded from {@code level.dat} before this mod
 * runs, so a region-injecting mod reaches every managed dimension in it and
 * mutates the persisted source in place ([T34]). The parameter entry LIST
 * survives that untouched, so a source rebuilt from it carries the dimension's
 * own palette and nothing else. The four reserved worlds have no config here
 * and keep whatever the pack's biome mods give them.
 */
public final class ConfiguredBiomeSource {

    private ConfiguredBiomeSource() {
    }

    /**
     * The options with the dimension's own biome palette restored, or the input
     * when nothing widened it. Call before building a {@code ServerWorld}: the
     * world, its structure placement calculator and the headless facts engine
     * must all read one biome source.
     */
    public static DimensionOptions restore(DimensionOptions options, DimensionConfig def) {
        if (options == null || def == null) {
            return options;
        }
        if (!(options.chunkGenerator() instanceof NoiseChunkGenerator noiseGen)
                || !(noiseGen.getBiomeSource() instanceof MultiNoiseBiomeSource source)) {
            return options;
        }
        List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> entries =
                ((MultiNoiseBiomeSourceAccessor) source).invokeGetBiomeEntries().getEntries();
        Set<Identifier> own = new HashSet<>();
        for (Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>> pair : entries) {
            pair.getSecond().getKey().map(RegistryKey::getValue).ifPresent(own::add);
        }
        int reported = source.getBiomes().size();
        if (reported <= own.size()) {
            return options;
        }
        BiomeSource rebuilt = MultiNoiseBiomeSource.create(new MultiNoiseUtil.Entries<>(entries));
        MultiverseServer.LOGGER.warn(
                "Dimension {}: biome source reported {} biomes over a {}-biome palette — "
                + "another mod injected regions into the persisted source; rebuilt from its "
                + "own {} parameter point(s)",
                def.getName(), reported, own.size(), entries.size());
        return new DimensionOptions(options.dimensionTypeEntry(),
                new NoiseChunkGenerator(rebuilt, noiseGen.getSettings()));
    }
}
