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
import java.util.function.Predicate;
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
 * <p>A dimension with {@code biomePatches} carries its multi-noise core inside
 * one or more {@link PatchedBiomeSource} layers. The rebuild reads the core and
 * puts every layer back, wherever in the stack it sat, so restoring a palette
 * never costs the dimension its patches.
 *
 * <p>Another mod's layer is DROPPED by a rebuild — Lithostitched's injector
 * exposes no way to re-wrap one — so its injections reach a managed dimension
 * only by being named in the dimension's {@code biomes} list, which is where
 * {@link DimensionManager#multiNoiseOf} has always put them. A palette nobody
 * widened is never rebuilt, so no foreign layer is dropped without a [T34]
 * injection to undo.
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

        /** Layers between {@code source} and its core belonging to another mod, by class name. */
        List<String> foreignLayers(S source);

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

        /** Reports what happened: a rebuild, what it dropped, or why one was refused. */
        void report(Outcome outcome, S source, S core);
    }

    /** What {@link #restored} did with the source it was given. */
    enum Outcome {
        /** Nothing widened the palette; the source is already the dimension's own. */
        NOT_WIDENED,
        /** The rebuild came back without the layers that went into it; refused. */
        PATCHES_LOST,
        /** A rebuild happened. */
        REBUILT,
        /** A rebuild happened and dropped another mod's layer, which cannot be put back. */
        FOREIGN_LAYER_DROPPED
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
     * The source with its palette restored, or {@code source} itself when nothing
     * widened it, no core is reachable, or the rebuild would lose one of this
     * dimension's patch layers. Another mod's layer is dropped, not refused.
     */
    static <S> S restored(S source, Layers<S> layers) {
        S core = layers.core(source);
        if (core == null) {
            return source;
        }
        // Widening is asked FIRST: without an injection to undo there is no
        // rebuild, and so no foreign layer is dropped.
        if (layers.reported(core) <= layers.palette(core)) {
            return source;
        }
        boolean foreign = !layers.foreignLayers(source).isEmpty();
        S rebuilt = layers.rewrap(source, layers.rebuild(core));
        if (!layers.preserved(source, rebuilt)) {
            layers.report(Outcome.PATCHES_LOST, source, core);
            return source;
        }
        layers.report(foreign ? Outcome.FOREIGN_LAYER_DROPPED : Outcome.REBUILT, source, core);
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

    /**
     * Every layer over {@code start}'s core, outermost first, stepping THROUGH
     * layers it does not own — a patch layer under another mod's wrapper is
     * still this dimension's and still has to come back. Bounded by
     * {@link DimensionManager#MAX_UNWRAP_DEPTH}; a step that returns its own
     * input ends the walk.
     */
    static <T> List<T> layersOf(T start, Predicate<T> atCore, UnaryOperator<T> step) {
        List<T> out = new ArrayList<>();
        T walk = start;
        for (int i = 0; i < DimensionManager.MAX_UNWRAP_DEPTH && !atCore.test(walk); i++) {
            out.add(walk);
            T next = step.apply(walk);
            if (next == walk) {
                break;
            }
            walk = next;
        }
        return out;
    }

    private static List<BiomeSource> layersOver(BiomeSource source) {
        return layersOf(source, s -> s instanceof MultiNoiseBiomeSource,
                DimensionManager::unwrapOneLayer);
    }

    /** This mod's {@link PatchedBiomeSource} layers over a source, outermost first. */
    static List<PatchedBiomeSource> wrappersOf(BiomeSource source) {
        List<PatchedBiomeSource> out = new ArrayList<>();
        for (BiomeSource layer : layersOver(source)) {
            if (layer instanceof PatchedBiomeSource patched) {
                out.add(patched);
            }
        }
        return out;
    }

    /**
     * The live layer chain over a source, outermost first and ending at what the
     * unwrap lands on: {@code PatchedBiomeSource>InjectorBiomeSource>class_4766}.
     * The same walk the rebuild uses, so the probe cannot disagree with it.
     */
    public static String layerChain(BiomeSource source) {
        StringBuilder out = new StringBuilder();
        for (BiomeSource layer : layersOver(source)) {
            out.append(layer.getClass().getSimpleName()).append('>');
        }
        return out.append(DimensionManager.unwrapToMultiNoise(source)
                .getClass().getSimpleName()).toString();
    }

    /** Other mods' layers over a source, by class name, outermost first. */
    static List<String> foreignLayersOf(BiomeSource source) {
        List<String> out = new ArrayList<>();
        for (BiomeSource layer : layersOver(source)) {
            if (!(layer instanceof PatchedBiomeSource)) {
                out.add(layer.getClass().getName());
            }
        }
        return out;
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
        public List<String> foreignLayers(BiomeSource source) {
            return foreignLayersOf(source);
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
        public void report(Outcome outcome, BiomeSource source, BiomeSource core) {
            int layers = wrappersOf(source).size();
            String inside = layers == 0 ? "" : " inside its " + layers + " patch layer(s)";
            switch (outcome) {
                case PATCHES_LOST -> MultiverseServer.LOGGER.error(
                        "Dimension {}: the biome-source rebuild lost this dimension's biomePatches "
                        + "— {} layer(s) went in; keeping the un-rebuilt source",
                        this.def.getName(), layers);
                case REBUILT -> MultiverseServer.LOGGER.warn(
                        "Dimension {}: biome source reported {} biomes over a {}-biome palette — "
                        + "another mod injected regions into the persisted source; rebuilt from its "
                        + "own {} parameter point(s){}",
                        this.def.getName(), reported(core), palette(core), entriesOf(core).size(),
                        inside);
                case FOREIGN_LAYER_DROPPED -> MultiverseServer.LOGGER.warn(
                        "Dimension {}: biome source reported {} biomes over a {}-biome palette — "
                        + "rebuilt from its own {} parameter point(s){}, DROPPING {}, which exposes "
                        + "no way to re-wrap it; name that mod's biomes in this dimension's biomes "
                        + "list to keep them",
                        this.def.getName(), reported(core), palette(core), entriesOf(core).size(),
                        inside, String.join(", ", foreignLayersOf(source)));
                default -> { }
            }
        }

        private static List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> entriesOf(
                BiomeSource core) {
            return ((MultiNoiseBiomeSourceAccessor) core).invokeGetBiomeEntries().getEntries();
        }
    }
}
