package com.customdimensions.command;

import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.noise.DoublePerlinNoiseSampler;
import net.minecraft.util.math.noise.InterpolatedNoiseSampler;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds a raw density function from the DENSITY_FUNCTION registry using a
 * freshly built NoiseConfig, then samples it. The binding visitor mirrors
 * vanilla's LegacyNoiseDensityFunctionVisitor: it instantiates noise
 * samplers and copies InterpolatedNoiseSampler with the seeded random.
 *
 * Caveat: c2me's DFC module (MixinNoiseConfig) replaces the noise router
 * after construction with JIT-compiled DFs. The compiled router's noise
 * samplers may differ from manually instantiated ones despite identical
 * deriver seeds and params. Values from this command match what the live
 * server's NoiseConfig produces, which is the correct oracle for parity.
 */
public final class DensityFunctionEvaluator {

    /**
     * Looks up a density function by registry id, binds it through a fresh
     * NoiseConfig built from the dimension's generator settings and seed,
     * and samples it at (x, y, z).
     *
     * @return the sampled value, or NaN if the DF cannot be resolved
     */
    public static EvalResult evaluate(ServerWorld world, Identifier dfId, int x, int y, int z) {
        var chunkGen = world.getChunkManager().getChunkGenerator();
        if (!(chunkGen instanceof NoiseChunkGenerator noiseGen)) {
            return EvalResult.error("Not a NoiseChunkGenerator");
        }

        var settingsEntry = noiseGen.getSettings();
        ChunkGeneratorSettings settings = settingsEntry.value();
        long seed = world.getSeed();

        var noiseParamsLookup = world.getRegistryManager()
                .get(RegistryKeys.NOISE_PARAMETERS).getReadOnlyWrapper();
        NoiseConfig freshConfig = NoiseConfig.create(settings, noiseParamsLookup, seed);

        var dfRegistry = world.getRegistryManager().get(RegistryKeys.DENSITY_FUNCTION);
        DensityFunction rawDf = dfRegistry.get(dfId);
        if (rawDf == null) {
            return EvalResult.error("No density function registered as " + dfId);
        }

        BindingVisitor visitor = new BindingVisitor(freshConfig, seed, settings.usesLegacyRandom());
        DensityFunction bound = rawDf.apply(visitor);

        var pos = new DensityFunction.UnblendedNoisePos(x, y, z);
        double value = bound.sample(pos);
        return EvalResult.success(value, "fresh NoiseConfig from " + settingsEntry.getKey()
                .map(k -> k.getValue().toString()).orElse("?") + " + seed " + seed);
    }

    public record EvalResult(boolean ok, double value, String binding, String errorMsg) {
        static EvalResult success(double value, String binding) {
            return new EvalResult(true, value, binding, null);
        }

        static EvalResult error(String msg) {
            return new EvalResult(false, Double.NaN, null, msg);
        }
    }

    /**
     * Re-implements the binding logic of NoiseConfig's internal
     * LegacyNoiseDensityFunctionVisitor. Delegates noise instantiation to
     * NoiseConfig.getOrCreateSampler (public API), which uses the same
     * seeded splitter as the original visitor.
     */
    private static final class BindingVisitor implements DensityFunction.DensityFunctionVisitor {
        private final NoiseConfig config;
        private final long seed;
        private final boolean legacy;
        private final Map<DensityFunction, DensityFunction> cache = new HashMap<>();

        BindingVisitor(NoiseConfig config, long seed, boolean legacy) {
            this.config = config;
            this.seed = seed;
            this.legacy = legacy;
        }

        @Override
        public DensityFunction apply(DensityFunction df) {
            return cache.computeIfAbsent(df, this::applyUncached);
        }

        private DensityFunction applyUncached(DensityFunction df) {
            if (df instanceof InterpolatedNoiseSampler ins) {
                net.minecraft.util.math.random.Random rng;
                if (legacy) {
                    rng = new net.minecraft.util.math.random.CheckedRandom(seed);
                } else {
                    rng = config.getOrCreateRandomDeriver(
                            Identifier.ofVanilla("terrain")).split(
                            Identifier.ofVanilla("terrain"));
                }
                return ins.copyWithRandom(rng);
            }
            return df;
        }

        @Override
        public DensityFunction.Noise apply(DensityFunction.Noise noise) {
            var noiseData = noise.noiseData();
            var key = noiseData.getKey().orElse(null);
            if (key == null) {
                return noise;
            }

            if (legacy) {
                var id = key.getValue();
                if (id.equals(Identifier.ofVanilla("temperature"))) {
                    return legacyNoise(noiseData, 0L, -7, 1.0, 1.0);
                }
                if (id.equals(Identifier.ofVanilla("vegetation"))) {
                    return legacyNoise(noiseData, 1L, -7, 1.0, 1.0);
                }
                if (id.equals(Identifier.ofVanilla("offset"))) {
                    DoublePerlinNoiseSampler sampler = DoublePerlinNoiseSampler.create(
                            config.getOrCreateRandomDeriver(key.getValue())
                                    .split(key.getValue()),
                            new DoublePerlinNoiseSampler.NoiseParameters(0, 0.0));
                    return new DensityFunction.Noise(noiseData, sampler);
                }
            }

            DoublePerlinNoiseSampler sampler = config.getOrCreateSampler(key);
            return new DensityFunction.Noise(noiseData, sampler);
        }

        private DensityFunction.Noise legacyNoise(
                net.minecraft.registry.entry.RegistryEntry<DoublePerlinNoiseSampler.NoiseParameters> noiseData,
                long seedOffset, int firstOctave, double... amplitudes) {
            var rng = new net.minecraft.util.math.random.CheckedRandom(seed + seedOffset);
            var params = new DoublePerlinNoiseSampler.NoiseParameters(
                    firstOctave, new it.unimi.dsi.fastutil.doubles.DoubleArrayList(amplitudes));
            DoublePerlinNoiseSampler sampler = DoublePerlinNoiseSampler.createLegacy(rng, params);
            return new DensityFunction.Noise(noiseData, sampler);
        }
    }
}
