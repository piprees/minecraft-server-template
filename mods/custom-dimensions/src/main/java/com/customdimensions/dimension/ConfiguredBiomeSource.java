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

import java.util.ArrayList;
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
 *
 * <p>A dimension with {@code biomePatches} carries its multi-noise core inside
 * one or more {@link PatchedBiomeSource} layers. The rebuild reads the core and
 * puts every layer back, so restoring a palette never costs the dimension its
 * patches. A layer this mod cannot rebuild — Lithostitched's injector exposes
 * no way to re-wrap one — refuses the rebuild rather than dropping it.
 */
public final class ConfiguredBiomeSource {

    private ConfiguredBiomeSource() {
    }

    /**
     * A biome source seen as layers, so every decision in {@link #restored} is
     * testable. Implemented over {@code BiomeSource} in production and over a
     * record in tests: {@code BiomeSource} initialises {@code Registries}, which
     * that suite cannot bootstrap.
     */
    interface Layers<S> {

        /** The multi-noise core under every layer, or null when none is reachable. */
        S core(S source);

        /** Whether every layer between {@code source} and {@code core} can be put back. */
        boolean rewrappable(S source, S core);

        /** Distinct biomes the core reports. */
        int reported(S core);

        /** Distinct biomes the core's own parameter entries name. */
        int palette(S core);

        /** The core rebuilt from its own parameter entries. */
        S rebuild(S core);

        /** A rebuilt core back inside {@code source}'s layers. */
        S rewrap(S source, S rebuiltCore);

        /** Whether {@code rebuilt} carries the same layer configuration as {@code source}. */
        boolean preserved(S source, S rebuilt);

        /** Reports why a rebuild was refused, or that one happened. */
        void report(Refusal reason, S source, S core);
    }

    /** Why {@link #restored} handed back what it was given. */
    enum Refusal {
        /** Nothing widened the palette; the source is already the dimension's own. */
        NOT_WIDENED,
        /** A layer between the source and its core cannot be put back. */
        LAYER_NOT_REWRAPPABLE,
        /** The rebuild came back without the layers that went into it. */
        PATCHES_LOST,
        /** A rebuild happened. */
        REBUILT
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
        BiomeSource result = restored(outer, new BiomeSourceLayers(def));
        if (result == outer) {
            return options;
        }
        return new DimensionOptions(options.dimensionTypeEntry(),
                new NoiseChunkGenerator(result, noiseGen.getSettings()));
    }

    /**
     * The source with its palette restored, or {@code source} itself when the
     * rebuild is unnecessary, impossible, or would lose a layer.
     */
    static <S> S restored(S source, Layers<S> layers) {
        S core = layers.core(source);
        if (core == null) {
            return source;
        }
        if (!layers.rewrappable(source, core)) {
            layers.report(Refusal.LAYER_NOT_REWRAPPABLE, source, core);
            return source;
        }
        if (layers.reported(core) <= layers.palette(core)) {
            return source;
        }
        S rebuilt = layers.rewrap(source, layers.rebuild(core));
        if (!layers.preserved(source, rebuilt)) {
            layers.report(Refusal.PATCHES_LOST, source, core);
            return source;
        }
        layers.report(Refusal.REBUILT, source, core);
        return rebuilt;
    }

    /**
     * Whether a rebuild handed back the layers it was given. {@code Patch} is a
     * record and {@code List.equals} is ordered, so this compares every field of
     * every patch, in order, layer by layer.
     */
    static <C> boolean preserved(List<C> before, List<C> after) {
        return before.equals(after);
    }

    /** The {@link PatchedBiomeSource} layers over a source, outermost first. */
    static List<PatchedBiomeSource> wrappersOf(BiomeSource source) {
        List<PatchedBiomeSource> out = new ArrayList<>();
        BiomeSource walk = source;
        while (walk instanceof PatchedBiomeSource patched
                && out.size() < DimensionManager.MAX_UNWRAP_DEPTH) {
            out.add(patched);
            walk = patched.delegate();
        }
        return out;
    }

    /** What the {@link #wrappersOf} walk lands on. */
    private static BiomeSource beneathPatches(BiomeSource source) {
        BiomeSource walk = source;
        for (int i = 0; i < DimensionManager.MAX_UNWRAP_DEPTH
                && walk instanceof PatchedBiomeSource patched; i++) {
            walk = patched.delegate();
        }
        return walk;
    }

    private static List<List<PatchedBiomeSource.Patch>> patchesOf(BiomeSource source) {
        List<List<PatchedBiomeSource.Patch>> out = new ArrayList<>();
        for (PatchedBiomeSource patched : wrappersOf(source)) {
            out.add(patched.patches());
        }
        return out;
    }

    /** {@link Layers} over the real thing. */
    private record BiomeSourceLayers(DimensionConfig def) implements Layers<BiomeSource> {

        @Override
        public BiomeSource core(BiomeSource source) {
            BiomeSource unwrapped = DimensionManager.unwrapToMultiNoise(source);
            return unwrapped instanceof MultiNoiseBiomeSource ? unwrapped : null;
        }

        @Override
        public boolean rewrappable(BiomeSource source, BiomeSource core) {
            return beneathPatches(source) == core;
        }

        @Override
        public int reported(BiomeSource core) {
            return core.getBiomes().size();
        }

        @Override
        public int palette(BiomeSource core) {
            Set<Identifier> own = new HashSet<>();
            for (Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>> pair : entriesOf(core)) {
                pair.getSecond().getKey().map(RegistryKey::getValue).ifPresent(own::add);
            }
            return own.size();
        }

        @Override
        public BiomeSource rebuild(BiomeSource core) {
            return MultiNoiseBiomeSource.create(new MultiNoiseUtil.Entries<>(entriesOf(core)));
        }

        @Override
        public BiomeSource rewrap(BiomeSource source, BiomeSource rebuiltCore) {
            List<PatchedBiomeSource> layers = wrappersOf(source);
            BiomeSource out = rebuiltCore;
            for (int i = layers.size() - 1; i >= 0; i--) {
                out = layers.get(i).withDelegate(out);
            }
            return out;
        }

        @Override
        public boolean preserved(BiomeSource source, BiomeSource rebuilt) {
            return ConfiguredBiomeSource.preserved(patchesOf(source), patchesOf(rebuilt));
        }

        @Override
        public void report(Refusal reason, BiomeSource source, BiomeSource core) {
            int layers = wrappersOf(source).size();
            switch (reason) {
                case LAYER_NOT_REWRAPPABLE -> MultiverseServer.LOGGER.warn(
                        "Dimension {}: biome source palette NOT restored — {} sits between this "
                        + "dimension's patches and its multi-noise source and cannot be rebuilt, "
                        + "and dropping it would cost the dimension that mod's biomes",
                        this.def.getName(), beneathPatches(source).getClass().getName());
                case PATCHES_LOST -> MultiverseServer.LOGGER.error(
                        "Dimension {}: the biome-source rebuild lost this dimension's biomePatches "
                        + "— {} layer(s) went in; keeping the un-rebuilt source",
                        this.def.getName(), layers);
                case REBUILT -> MultiverseServer.LOGGER.warn(
                        "Dimension {}: biome source reported {} biomes over a {}-biome palette — "
                        + "another mod injected regions into the persisted source; rebuilt from its "
                        + "own {} parameter point(s){}",
                        this.def.getName(), reported(core), palette(core), entriesOf(core).size(),
                        layers == 0 ? "" : " inside its " + layers + " patch layer(s)");
                default -> { }
            }
        }

        private static List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> entriesOf(
                BiomeSource core) {
            return ((MultiNoiseBiomeSourceAccessor) core).invokeGetBiomeEntries().getEntries();
        }
    }
}
