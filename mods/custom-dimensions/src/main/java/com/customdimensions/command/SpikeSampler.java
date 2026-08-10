package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.mixin.MinecraftServerAccessor;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;

/**
 * Phase 1 spike: can a seeded biome source and terrain sampler be built for a
 * dimension CONFIG, with no {@link ServerWorld} in existence, and does it agree
 * with the running server?
 *
 * Two sides, one shape:
 *
 * <ul>
 *   <li>{@link #headless} rebuilds the dimension's {@link DimensionOptions}
 *       from config through the same {@code DimensionManager} path world
 *       creation uses, then builds a {@link NoiseConfig} for an arbitrary
 *       seed. No world is touched, created or read.</li>
 *   <li>{@link #live} asks a loaded {@link ServerWorld}'s own chunk generator
 *       the same three questions. When the requested seed is the world's own,
 *       the world's live {@code NoiseConfig} answers, so the comparison covers
 *       NoiseConfig construction and not merely the generator.</li>
 * </ul>
 *
 * Measurements are exact or absent — but absent only when the fact genuinely
 * cannot be had. A flat generator has no router of its own, and reporting its
 * biome absent on that basis mismatched every column of every superflat
 * dimension against a live server that answers one; it gets a zeroed-router
 * NoiseConfig instead, which is what the live side effectively has.
 */
public final class SpikeSampler {

    private SpikeSampler() {
    }

    /** One column's worth of facts, or the reason a fact could not be had. */
    public record Sample(
            int x,
            int z,
            String biome,
            String biomeAbsent,
            Integer surfaceHeight,
            String heightAbsent,
            double[] climate,
            String climateAbsent) {

        /** True when every fact this sample carries equals the other's. */
        public boolean matches(Sample other) {
            if (other == null || x != other.x || z != other.z) {
                return false;
            }
            if (!java.util.Objects.equals(biome, other.biome)
                    || !java.util.Objects.equals(biomeAbsent, other.biomeAbsent)) {
                return false;
            }
            if (!java.util.Objects.equals(surfaceHeight, other.surfaceHeight)
                    || !java.util.Objects.equals(heightAbsent, other.heightAbsent)) {
                return false;
            }
            if (!java.util.Objects.equals(climateAbsent, other.climateAbsent)) {
                return false;
            }
            if (climate == null || other.climate == null) {
                return climate == other.climate;
            }
            // Zero tolerance, and bit-exact: Arrays.equals on doubles compares
            // by Double.valueOf semantics, which is what "equal or the spike
            // has failed" has to mean for a value carried as a double.
            return java.util.Arrays.equals(climate, other.climate);
        }
    }

    /** What a build produced, or why it could not be built. */
    public record Rig(
            ChunkGenerator generator,
            NoiseConfig noiseConfig,
            HeightLimitView heightLimit,
            boolean biomeSourceAcceptsWithSeed,
            boolean climateOnly,
            String error) {

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * The seed-independent half of a rig: the generator and its height limit.
     *
     * <p>Split out because it is the half a search must NOT pay for per seed.
     * Nothing in 1.21.1's biome source or chunk generator carries a seed —
     * {@code biomeSourceAcceptsWithSeed} records whether some mod has added
     * one, because if anything ever answers true this split stops being
     * sound and the artefact should say so rather than a comment.
     */
    public record Base(
            ChunkGenerator generator,
            HeightLimitView heightLimit,
            boolean biomeSourceAcceptsWithSeed,
            boolean fromConfig,
            ChunkGeneratorSettings climateSettings,
            String error) {

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * The dimension's generator settings with every non-climate density
     * function replaced by zero.
     *
     * <p>Screening asks for a biome, and a biome needs six climate chains —
     * but {@code NoiseConfig.create} builds the whole router eagerly, and on
     * a Tectonic + Terralith overworld that is ~100 ms of terrain, aquifer and
     * ore samplers per seed for an answer that reads none of them.
     *
     * <p>This is not a reimplementation of the noise pipeline: vanilla's own
     * constructor runs, with the same seed, the same random deriver and the
     * same per-noise-id seeding — it is simply handed less to build. The
     * climate values it produces must therefore equal the full router's
     * exactly, and {@code spike-compare} asserts that per column rather than
     * asserting it here in prose.
     */
    public static ChunkGeneratorSettings climateOnly(ChunkGeneratorSettings base) {
        NoiseRouter r = base.noiseRouter();
        DensityFunction zero = DensityFunctionTypes.zero();
        return withRouter(base, new NoiseRouter(
                zero, zero, zero, zero,
                r.temperature(), r.vegetation(), r.continents(),
                r.erosion(), r.depth(), r.ridges(),
                zero, zero, zero, zero, zero));
    }

    /**
     * Settings with EVERY density function zeroed — the control for the
     * throughput measurement. Whatever a NoiseConfig built from this still
     * costs is the fixed price of construction itself (the surface builder,
     * the random derivers) rather than of any router the dimension configured,
     * and there is no point hunting for savings below it.
     */
    public static ChunkGeneratorSettings emptyRouter(ChunkGeneratorSettings base) {
        DensityFunction zero = DensityFunctionTypes.zero();
        return withRouter(base, new NoiseRouter(
                zero, zero, zero, zero, zero, zero, zero, zero,
                zero, zero, zero, zero, zero, zero, zero));
    }

    private static ChunkGeneratorSettings withRouter(
            ChunkGeneratorSettings base, NoiseRouter router) {
        return new ChunkGeneratorSettings(
                base.generationShapeConfig(), base.defaultBlock(), base.defaultFluid(),
                router, base.surfaceRule(), base.spawnTarget(), base.seaLevel(),
                base.mobGenerationDisabled(), base.aquifers(), base.oreVeins(),
                base.usesLegacyRandom());
    }

    /**
     * How many distinct noise parameter ids a router's climate half binds.
     *
     * <p>The per-seed cost is one {@code DoublePerlinNoiseSampler} per
     * distinct id, so this number is the throughput. Reporting it turns "the
     * overworld is slow" into "the overworld's climate chains bind N noises
     * and the nether's bind M", which is a fact somebody can act on.
     */
    public static int climateNoiseCount(ChunkGeneratorSettings settings) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        DensityFunction.DensityFunctionVisitor counter =
                new DensityFunction.DensityFunctionVisitor() {
            @Override
            public DensityFunction apply(DensityFunction function) {
                return function;
            }

            @Override
            public DensityFunction.Noise apply(DensityFunction.Noise noise) {
                noise.noiseData().getKey()
                        .ifPresent(k -> seen.add(k.getValue().toString()));
                return noise;
            }
        };
        NoiseRouter r = settings.noiseRouter();
        for (DensityFunction df : java.util.List.of(
                r.temperature(), r.vegetation(), r.continents(),
                r.erosion(), r.depth(), r.ridges())) {
            df.apply(counter);
        }
        return seen.size();
    }

    // ---------------------------------------------------------------- headless

    /**
     * The generator a dimension config would produce, with no ServerWorld.
     *
     * <p>It comes from {@code DimensionManager}'s own
     * {@code createDimensionOptions} — the exact code world creation runs —
     * so a divergence here is a real divergence and not two builders
     * disagreeing. Base worlds (the nether, the end, paradise_lost) have no
     * config-built generator; theirs is read from the DIMENSION registry.
     */
    public static Base base(MinecraftServer server, Identifier dimensionId) {
        DimensionManager manager = DimensionManager.getInstance();
        DimensionConfig def = MultiverseConfig.getInstance().getDimension(dimensionId.getPath());
        if (def == null || !def.getDimensionIdentifier().equals(dimensionId)) {
            def = null;
        }

        ChunkGenerator generator;
        DimensionType dimensionType;
        if (def != null) {
            DimensionOptions options;
            try {
                options = manager.buildOptionsHeadless(def);
            } catch (Exception e) {
                return new Base(null, null, false, true, null,
                        "config build failed: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
            generator = options.chunkGenerator();
            dimensionType = options.dimensionTypeEntry().value();
            return new Base(generator, heightLimit(dimensionType),
                    hasWithSeed(generator.getBiomeSource()), true,
                    climateSettingsOf(server, generator), null);
        }

        Registry<DimensionOptions> registry = (Registry<DimensionOptions>)
                server.getCombinedDynamicRegistries().getCombinedRegistryManager()
                        .get(RegistryKeys.DIMENSION);
        DimensionOptions options = registry.get(
                RegistryKey.of(RegistryKeys.DIMENSION, dimensionId));
        if (options == null) {
            return new Base(null, null, false, false, null,
                    "no dimension config and no registry entry for " + dimensionId);
        }
        generator = options.chunkGenerator();
        dimensionType = options.dimensionTypeEntry().value();
        return new Base(generator, heightLimit(dimensionType),
                hasWithSeed(generator.getBiomeSource()), false,
                climateSettingsOf(server, generator), null);
    }

    /**
     * The climate-only settings for a generator. A flat generator gets the
     * same zeroed-router fallback {@code buildNoiseConfig} uses, so the
     * screening rig and the full rig answer identically there instead of the
     * screening rig alone reporting absent — which is the same
     * unnecessary-pessimism bug one level down.
     */
    private static ChunkGeneratorSettings climateSettingsOf(
            MinecraftServer server, ChunkGenerator generator) {
        if (generator instanceof NoiseChunkGenerator noiseGen) {
            return climateOnly(noiseGen.getSettings().value());
        }
        ChunkGeneratorSettings base = overworldSettings(server);
        return base == null ? null : emptyRouter(base);
    }

    /** A base plus one seed's NoiseConfig — the per-seed half of the cost. */
    public static Rig forSeed(MinecraftServer server, Base base, long seed) {
        if (!base.ok()) {
            return new Rig(null, null, null, false, false, base.error());
        }
        return new Rig(base.generator(), buildNoiseConfig(server, base.generator(), seed),
                base.heightLimit(), base.biomeSourceAcceptsWithSeed(), false, null);
    }

    /**
     * A base plus one seed's CLIMATE-ONLY NoiseConfig. Answers biomes and the
     * six climate values; height is reported absent, because the terrain half
     * of the router is not there to answer it.
     */
    public static Rig forSeedClimate(MinecraftServer server, Base base, long seed) {
        if (!base.ok()) {
            return new Rig(null, null, null, false, true, base.error());
        }
        if (base.climateSettings() == null) {
            return new Rig(base.generator(), null, base.heightLimit(),
                    base.biomeSourceAcceptsWithSeed(), true, null);
        }
        var lookup = server.getRegistryManager()
                .get(RegistryKeys.NOISE_PARAMETERS).getReadOnlyWrapper();
        return new Rig(base.generator(),
                NoiseConfig.create(base.climateSettings(), lookup, seed),
                base.heightLimit(), base.biomeSourceAcceptsWithSeed(), true, null);
    }

    /** Build both halves. Convenience for one-shot callers only. */
    public static Rig headless(MinecraftServer server, Identifier dimensionId, long seed) {
        return forSeed(server, base(server, dimensionId), seed);
    }

    // -------------------------------------------------------------------- live

    /**
     * The same rig from a loaded world. When {@code seed} is the world's own,
     * the world's live NoiseConfig is used — otherwise one is built for the
     * requested seed, which is the only way to ask a live world about a seed
     * no world was created with.
     */
    public static Rig live(ServerWorld world, long seed) {
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        NoiseConfig noiseConfig = seed == world.getSeed()
                ? world.getChunkManager().getNoiseConfig()
                : buildNoiseConfig(world.getServer(), generator, seed);
        return new Rig(generator, noiseConfig, world,
                hasWithSeed(generator.getBiomeSource()), false, null);
    }

    /** True when the live NoiseConfig answered, rather than a rebuilt one. */
    public static boolean usesLiveNoiseConfig(ServerWorld world, long seed) {
        return seed == world.getSeed();
    }

    // ----------------------------------------------------------------- sampling

    /**
     * Biome, structure-generation surface height and the six climate values at
     * one column. Quart coordinates and sampling heights match the existing
     * oracles exactly: biome at quart y=16 (the height
     * {@code sample-biome-grid} uses), climate at quart y=0 (the height
     * {@code sample-noise} uses).
     */
    public static Sample sample(Rig rig, int x, int z) {
        if (!rig.ok()) {
            return new Sample(x, z, null, rig.error(), null, rig.error(), null, rig.error());
        }
        int qx = x >> 2;
        int qz = z >> 2;

        String biome = null;
        String biomeAbsent = null;
        Integer height = null;
        String heightAbsent = null;
        double[] climate = null;
        String climateAbsent = null;

        if (rig.noiseConfig() == null) {
            biomeAbsent = "no noise config: generator is "
                    + rig.generator().getClass().getSimpleName();
            climateAbsent = biomeAbsent;
        } else {
            MultiNoiseUtil.MultiNoiseSampler sampler = rig.noiseConfig().getMultiNoiseSampler();
            BiomeSource source = rig.generator().getBiomeSource();
            var entry = source.getBiome(qx, 16, qz, sampler);
            biome = entry.getKey().map(k -> k.getValue().toString()).orElse(null);
            if (biome == null) {
                biomeAbsent = "biome entry carries no registry key";
            }
            MultiNoiseUtil.NoiseValuePoint point = sampler.sample(qx, 0, qz);
            climate = new double[] {
                point.temperatureNoise() / 10000.0,
                point.humidityNoise() / 10000.0,
                point.continentalnessNoise() / 10000.0,
                point.erosionNoise() / 10000.0,
                point.depth() / 10000.0,
                point.weirdnessNoise() / 10000.0,
            };
        }

        if (rig.climateOnly()) {
            heightAbsent = "climate-only rig: the terrain router is stripped";
        } else {
            try {
                height = rig.generator().getHeight(x, z, Heightmap.Type.WORLD_SURFACE_WG,
                        rig.heightLimit(), rig.noiseConfig());
            } catch (Exception e) {
                heightAbsent = "getHeight threw " + e.getClass().getSimpleName();
            }
        }

        return new Sample(x, z, biome, biomeAbsent, height, heightAbsent, climate, climateAbsent);
    }

    /**
     * Biome only, at the given column — the cheapest question the search stage
     * asks, and therefore the one the throughput floor is measured on.
     */
    public static String spawnBiome(Rig rig, int x, int z) {
        if (!rig.ok() || rig.noiseConfig() == null) {
            return null;
        }
        return rig.generator().getBiomeSource()
                .getBiome(x >> 2, 16, z >> 2, rig.noiseConfig().getMultiNoiseSampler())
                .getKey().map(k -> k.getValue().toString()).orElse(null);
    }

    // -------------------------------------------------------------- coordinates

    /**
     * A deterministic, off-lattice coordinate spread. Grid-aligned probes are
     * the classic way to make two samplers agree for the wrong reason — Perlin
     * is exactly zero on its lattice, so a lattice-aligned comparison can pass
     * with the noise field entirely unread.
     */
    public static int[] probe(int index, int spanBlocks) {
        return probe(index, spanBlocks, 0L);
    }

    /**
     * As above, with the seed folded in so two comparisons do not sample the
     * same twenty points.
     *
     * <p>Without this the coordinates depend on the index alone, so every
     * dimension and every seed probes the identical twenty offsets forever — a
     * divergence confined to a region those twenty points never land in could
     * never surface however many pairs are compared. Reproducibility is kept:
     * the same (index, span, salt) always gives the same point.
     */
    public static int[] probe(int index, int spanBlocks, long salt) {
        long h = mix64(index * 0x9E3779B97F4A7C15L + 0x243F6A8885A308D3L
                + mix64(salt));
        int x = (int) Math.floorMod(h, (long) spanBlocks * 2) - spanBlocks;
        long h2 = mix64(h ^ 0xBF58476D1CE4E5B9L);
        int z = (int) Math.floorMod(h2, (long) spanBlocks * 2) - spanBlocks;
        return new int[] {x, z};
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The NoiseConfig for a generator at a seed.
     *
     * <p>A flat generator has no {@link ChunkGeneratorSettings} of its own, but
     * the live server still has a NoiseConfig for its world — vanilla builds
     * one regardless of generator type, because structures and features need
     * it. Returning null here made the headless side report biome and climate
     * ABSENT while the live side answered a real biome, which is a 100%
     * mismatch on every column of every superflat and flat-fallback void
     * dimension. Absent is only honest when the fact genuinely cannot be had,
     * and here it can.
     *
     * <p>The fallback settings carry a fully zeroed router: a flat world's
     * biome source ignores the sampler entirely (it is fixed or a checkerboard
     * grid), so the biome is unaffected, and a zeroed router is what reproduces
     * the live server's all-zero climate point rather than inventing an
     * overworld climate for a world that has none.
     */
    private static NoiseConfig buildNoiseConfig(
            MinecraftServer server, ChunkGenerator generator, long seed) {
        var lookup = server.getRegistryManager()
                .get(RegistryKeys.NOISE_PARAMETERS).getReadOnlyWrapper();
        if (generator instanceof NoiseChunkGenerator noiseGen) {
            return NoiseConfig.create(noiseGen.getSettings().value(), lookup, seed);
        }
        ChunkGeneratorSettings base = overworldSettings(server);
        if (base == null) {
            return null;
        }
        return NoiseConfig.create(emptyRouter(base), lookup, seed);
    }

    /** The overworld's generator settings, as a shape to build a router into. */
    private static ChunkGeneratorSettings overworldSettings(MinecraftServer server) {
        var registry = server.getRegistryManager()
                .get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
        return registry.get(RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS,
                Identifier.of("minecraft", "overworld")));
    }

    private static HeightLimitView heightLimit(DimensionType type) {
        final int bottomY = type.minY();
        final int height = type.height();
        return new HeightLimitView() {
            @Override
            public int getHeight() {
                return height;
            }

            @Override
            public int getBottomY() {
                return bottomY;
            }
        };
    }

    /**
     * Whether the biome source carries a {@code withSeed(long)} — the one path
     * by which a per-seed rig would need rebuilding from config rather than
     * only a fresh NoiseConfig. Vanilla 1.21.1 has none; a mod could add one,
     * and the artefact records the answer rather than assuming it.
     */
    private static boolean hasWithSeed(BiomeSource source) {
        if (source == null) {
            return false;
        }
        try {
            source.getClass().getMethod("withSeed", long.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** How many ServerWorlds the server currently holds. */
    public static int worldCount(MinecraftServer server) {
        return ((MinecraftServerAccessor) server).getWorlds().size();
    }

    /** Resolve a dimension id to a loaded world, or null. */
    public static ServerWorld loadedWorld(MinecraftServer server, Identifier id) {
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
    }

    // --------------------------------------------------------------------- json

    public static void appendSample(StringBuilder json, Sample s) {
        json.append("{\"x\": ").append(s.x()).append(", \"z\": ").append(s.z());
        appendOptionalString(json, "biome", s.biome());
        appendOptionalString(json, "biomeAbsent", s.biomeAbsent());
        json.append(", \"surfaceHeight\": ")
                .append(s.surfaceHeight() == null ? "null" : s.surfaceHeight());
        appendOptionalString(json, "heightAbsent", s.heightAbsent());
        if (s.climate() == null) {
            json.append(", \"climate\": null");
        } else {
            json.append(", \"climate\": [");
            for (int i = 0; i < s.climate().length; i++) {
                if (i > 0) {
                    json.append(", ");
                }
                // Repr, not a rounded rendering: a %.6f comparison would call
                // two different numbers equal, which is the failure mode the
                // zero-tolerance gate exists to catch.
                json.append(Double.toString(s.climate()[i]));
            }
            json.append(']');
        }
        appendOptionalString(json, "climateAbsent", s.climateAbsent());
        json.append('}');
    }

    private static void appendOptionalString(StringBuilder json, String key, String value) {
        json.append(", \"").append(key).append("\": ");
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
    }

    /** Log a build failure once, with the dimension that caused it. */
    public static void logBuildFailure(Identifier id, Rig rig) {
        if (!rig.ok()) {
            MultiverseServer.LOGGER.warn("spike: {} could not be built headlessly: {}",
                    id, rig.error());
        }
    }

    /** The world key for an id, for the no-new-worlds assertion. */
    public static RegistryKey<World> worldKey(Identifier id) {
        return RegistryKey.of(RegistryKeys.WORLD, id);
    }
}
