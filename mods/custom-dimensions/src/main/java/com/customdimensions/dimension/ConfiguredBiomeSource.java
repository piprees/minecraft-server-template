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
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A managed dimension generates the biome source this mod built for it.
 *
 * <p>The DIMENSION registry is decoded from {@code level.dat} before this mod
 * runs, so a region-injecting mod reaches every managed dimension in it and
 * mutates the persisted source in place ([T34]). The parameter entry LIST
 * survives that untouched, so a source rebuilt from it carries the dimension's
 * own palette and nothing else. The four reserved worlds have no config here
 * and keep whatever the pack's biome mods give them.
 *
 * <p>A dimension with {@code biomePatches} carries its multi-noise source inside
 * a {@link PatchedBiomeSource}. The rebuild reads that core and wraps its result
 * back in the same patches, so restoring a palette never costs the dimension its
 * stamps and swaps.
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
        if (!(options.chunkGenerator() instanceof NoiseChunkGenerator noiseGen)) {
            return options;
        }
        BiomeSource outer = noiseGen.getBiomeSource();
        PatchedBiomeSource patched = outer instanceof PatchedBiomeSource p ? p : null;
        // The palette is the CORE's parameter entries. A wrapper's own biome set
        // also counts its patch biomes, which would read as widening.
        BiomeSource core = patched == null ? outer : patched.delegate();
        if (!(core instanceof MultiNoiseBiomeSource source)) {
            return options;
        }
        List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> entries =
                ((MultiNoiseBiomeSourceAccessor) source).invokeGetBiomeEntries().getEntries();
        Set<Identifier> own = new HashSet<>();
        for (Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>> pair : entries) {
            pair.getSecond().getKey().map(RegistryKey::getValue).ifPresent(own::add);
        }
        int reported = source.getBiomes().size();
        BiomeSource restored = restored(outer, reported, own.size(),
                () -> MultiNoiseBiomeSource.create(new MultiNoiseUtil.Entries<>(entries)),
                patched == null ? UnaryOperator.identity() : patched::withDelegate);
        if (restored == outer) {
            return options;
        }
        // A rebuild that costs a dimension its patches is worse than no rebuild,
        // so say so and keep the un-rebuilt source ([T34]).
        List<PatchedBiomeSource.Patch> kept =
                restored instanceof PatchedBiomeSource p ? p.patches() : null;
        if (!preserved(patched == null ? null : patched.patches(), kept)) {
            MultiverseServer.LOGGER.error(
                    "Dimension {}: the biome-source rebuild lost this dimension's biomePatches — "
                    + "{} patch(es) went in, {} came back; keeping the un-rebuilt source",
                    def.getName(), patched.patches().size(), kept == null ? 0 : kept.size());
            return options;
        }
        MultiverseServer.LOGGER.warn(
                "Dimension {}: biome source reported {} biomes over a {}-biome palette — "
                + "another mod injected regions into the persisted source; rebuilt from its "
                + "own {} parameter point(s){}",
                def.getName(), reported, own.size(), entries.size(),
                patched == null ? "" : " inside its " + patched.patches().size() + " patch(es)");
        return new DimensionOptions(options.dimensionTypeEntry(),
                new NoiseChunkGenerator(restored, noiseGen.getSettings()));
    }

    /**
     * The rebuilt core back inside the wrapper it came out of, or {@code source}
     * itself when the palette was never widened.
     *
     * <p>Generic so the rebuild-then-rewrap order is unit-testable:
     * {@code BiomeSource} initialises {@code Registries}, which that suite cannot
     * bootstrap.
     */
    static <S> S restored(S source, int reported, int palette,
                          Supplier<S> rebuild, UnaryOperator<S> rewrap) {
        if (reported <= palette) {
            return source;
        }
        return rewrap.apply(rebuild.get());
    }

    /**
     * Whether a rebuild handed back the wrapper's own configuration. {@code Patch}
     * is a record and {@code List.equals} is ordered, so this compares every field
     * of every patch, in order. A source that was never wrapped has nothing to keep.
     */
    static <C> boolean preserved(List<C> before, List<C> after) {
        return before == null || before.equals(after);
    }
}
