package com.customdimensions.server.render;

import com.customdimensions.core.render.ColumnSamples;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.level.LevelMaths;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.material.FluidState;

/**
 * A dimension's generator under one seed, sampled column by column without a
 * level and without generating chunks: the generator's own noise column, its
 * biome source and a {@link RandomState} built for the seed as the level
 * builds its own. Thread-safe; each thread keeps its own biome resolver.
 *
 * <p>The surface is the {@code OCEAN_FLOOR_WG} heightmap's: one above the top
 * block that blocks motion, so water and lava lie over it. A column whose
 * noise is solid at its top has a roof (the Nether, caves): its surface is the
 * first floor under the roof. The biome is the generator's noise biome at the
 * surface height, clamped into the dimension as a chunk clamps it.
 */
public final class Sampler {
    private static final Predicate<BlockState> SOLID = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();

    /** One column: its surface height, whether a roof is over it, the fluid on the surface and its surface biome. */
    public record Column(int x, int z, int height, boolean roofed, ColumnSamples.Fluid fluid, boolean waterAtSeaLevel,
                         Holder<Biome> biome) {
        public String biomeId() {
            return id(biome);
        }
    }

    /** The last zone in order whose compiled mask is above zero at a column, or index -1. */
    public record ZoneHit(int index, double weight) {}

    /** A surface found by {@link #surface}. */
    public record Surface(int height, boolean roofed) {}

    private final String dimension;
    private final ChunkGenerator generator;
    private final LevelHeightAccessor heights;
    private final RandomState random;
    private final long seed;
    private final String seedSource;
    private final int noiseTop;
    private final ThreadLocal<BiomeResolver> resolvers;
    private final List<DensitySampler> zoneMasks;
    private final RegistryAccess access;

    private Sampler(String dimension, ChunkGenerator generator, int minY, int height, long seed, String seedSource,
                    RegistryAccess access) {
        this.dimension = dimension;
        this.generator = generator;
        this.heights = LevelHeightAccessor.create(minY, height);
        this.seed = seed;
        this.seedSource = seedSource;
        this.access = access;
        var noises = access.lookupOrThrow(Registries.NOISE);
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            this.random = RandomState.create(noises, seed, noise.generatorSettings().value());
            var clamped = noise.generatorSettings().value().noiseSettings().clampToHeightAccessor(heights);
            this.noiseTop = clamped.minY() + clamped.height() - 1;
        } else {
            this.random = RandomState.create(noises, seed, false, Blocks.STONE.defaultBlockState(), 63, NoiseRouterData.none());
            this.noiseTop = minY + height - 1;
        }
        this.resolvers = ThreadLocal.withInitial(() -> generator.getBiomeSource().createUncachedResolver(random));
        this.zoneMasks = zoneMasks(access, dimension).stream().map(random::getSampler).toList();
    }

    /** {@code generator} under {@code seed} in a dimension of {@code height} blocks from {@code minY}. */
    public static Sampler of(String dimension, ChunkGenerator generator, int minY, int height, long seed, String seedSource,
                             RegistryAccess access) {
        return new Sampler(dimension, generator, minY, height, seed, seedSource, access);
    }

    /**
     * The dimension {@code slug} names, a base world or a lazy one, which need
     * not be open. A null {@code seed} takes the seed its level has or would
     * have: the world seed for a base world, the definition's for a lazy one.
     */
    public static Sampler forDimension(MinecraftServer server, LevelManager manager, String slug, Long seed) {
        for (LevelIndex.BaseWorld base : manager.index().baseWorlds()) {
            if (!base.slug().equals(slug)) continue;
            ServerLevel level = server.getLevel(base.key());
            if (level == null) throw new IllegalArgumentException(slug + " is not loaded");
            return of(slug, level.getChunkSource().getGenerator(), level.getMinY(), level.getHeight(),
                    seed == null ? level.getSeed() : seed, seed == null ? "level" : "argument", server.registryAccess());
        }
        Optional<LevelIndex.Entry> lazy = manager.index().lazy(slug);
        if (lazy.isEmpty()) throw new IllegalArgumentException("no dimension " + slug);
        LevelIndex.Entry entry = lazy.get();
        LevelStem stem = manager.index().decode(server, entry);
        var type = stem.type().value();
        long resolved = seed != null ? seed : defaultSeed(server, manager, slug);
        String source = seed != null ? "argument"
                : entry.definition() != null && entry.definition().seed() != null && !entry.definition().seed().fromEnv()
                ? "definition" : "world";
        return of(slug, stem.generator(), type.minY(), type.height(), resolved, source, server.registryAccess());
    }

    /** The seed {@code slug}'s level has or would have: the level's for a base world, the definition's (else the world's) for a lazy one. */
    public static long defaultSeed(MinecraftServer server, LevelManager manager, String slug) {
        for (LevelIndex.BaseWorld base : manager.index().baseWorlds()) {
            if (!base.slug().equals(slug)) continue;
            ServerLevel level = server.getLevel(base.key());
            return level == null ? server.overworld().getSeed() : level.getSeed();
        }
        LevelIndex.Entry entry = manager.index().lazy(slug).orElseThrow(() -> new IllegalArgumentException("no dimension " + slug));
        return LevelMaths.resolveSeed(entry.definition() == null ? null : entry.definition().seed(), server.overworld().getSeed());
    }

    /**
     * The compiled zone masks of {@code slug}, {@code <ns>:zones/<slug>/<index>/mask}, in zone order; empty when
     * it has none.
     */
    static List<DensityFunction> zoneMasks(RegistryAccess access, String slug) {
        java.util.TreeMap<Integer, DensityFunction> masks = new java.util.TreeMap<>();
        java.util.regex.Pattern path = java.util.regex.Pattern.compile("zones/" + java.util.regex.Pattern.quote(slug) + "/(\\d+)/mask");
        access.lookupOrThrow(Registries.DENSITY_FUNCTION).listElements().forEach(h -> {
            var m = path.matcher(h.key().identifier().getPath());
            if (m.matches()) masks.put(Integer.parseInt(m.group(1)), h.value());
        });
        return List.copyOf(masks.values());
    }

    public boolean hasZones() {
        return !zoneMasks.isEmpty();
    }

    /** Each zone's compiled mask at {@code (x, z)}, in zone order. */
    public double[] zoneWeights(int x, int z) {
        double[] out = new double[zoneMasks.size()];
        for (int i = 0; i < out.length; i++) out[i] = zoneMasks.get(i).sampleValue(SamplerContext.EMPTY_UNCACHED, x, 0, z);
        return out;
    }

    /** The last zone whose mask is above zero at {@code (x, z)}. */
    public ZoneHit topZone(int x, int z) {
        double[] w = zoneWeights(x, z);
        for (int i = w.length - 1; i >= 0; i--) {
            if (w[i] > 0) return new ZoneHit(i, w[i]);
        }
        return new ZoneHit(-1, 0);
    }

    /** The generator's noise column at {@code (x, z)}: every block the noise stage fills, before surface rules, carvers and features. */
    public NoiseColumn noiseColumn(int x, int z) {
        return generator.getBaseColumn(x, z, heights, random);
    }

    public Column sample(int x, int z) {
        NoiseColumn column = noiseColumn(x, z);
        Surface surface = surface(column::getBlock, noiseTop, heights.getMinY());
        int h = surface.height();
        ColumnSamples.Fluid fluid = h <= heights.getMinY() ? ColumnSamples.Fluid.NONE : fluid(column.getBlock(h));
        boolean water = column.getBlock(seaLevel() - 1).getFluidState().is(FluidTags.WATER);
        return new Column(x, z, h, surface.roofed(), fluid, water, biome(x, h, z));
    }

    /** The noise biome at {@code (x, y, z)}, {@code y} clamped into the dimension. */
    /** The generator's structure placement state for this seed, as a level with this generator builds it. */
    public ChunkGeneratorStructureState structureState() {
        return generator.createState(access.lookupOrThrow(Registries.STRUCTURE_SET), random, seed);
    }

    public RegistryAccess registryAccess() {
        return access;
    }

    public Holder<Biome> biome(int x, int y, int z) {
        int lowest = QuartPos.fromBlock(heights.getMinY());
        int highest = lowest + QuartPos.fromBlock(heights.getHeight()) - 1;
        int qy = Math.clamp(QuartPos.fromBlock(y), lowest, highest);
        return resolvers.get().getNoiseBiome(QuartPos.fromBlock(x), qy, QuartPos.fromBlock(z));
    }

    /**
     * The surface of a column read through {@code block}, from {@code top}
     * down: one above the top motion-blocking block, or, when {@code top} is
     * solid, one above the first motion-blocking block under the roof's
     * underside. {@code minY} for a column with no such block; one above the
     * roof for a column solid from the roof down.
     */
    public static Surface surface(IntFunction<BlockState> block, int top, int minY) {
        int y = top;
        boolean roofed = SOLID.test(block.apply(top));
        if (roofed) {
            while (y >= minY && SOLID.test(block.apply(y))) y--;
            if (y < minY) return new Surface(top + 1, true);
        }
        while (y >= minY && !SOLID.test(block.apply(y))) y--;
        return new Surface(y < minY ? minY : y + 1, roofed);
    }

    /** Whether {@code state} counts as solid for the surface: it blocks motion. */
    public static boolean solid(BlockState state) {
        return SOLID.test(state);
    }

    public static ColumnSamples.Fluid fluid(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return ColumnSamples.Fluid.NONE;
        if (fluid.is(FluidTags.WATER)) return ColumnSamples.Fluid.WATER;
        if (fluid.is(FluidTags.LAVA)) return ColumnSamples.Fluid.LAVA;
        return ColumnSamples.Fluid.OTHER;
    }

    public static String id(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered");
    }

    public String dimension() {
        return dimension;
    }

    public long seed() {
        return seed;
    }

    /** {@code argument}, {@code level}, {@code definition} or {@code world}: where {@link #seed()} came from. */
    public String seedSource() {
        return seedSource;
    }

    public int seaLevel() {
        return generator.getSeaLevel();
    }

    public int minY() {
        return heights.getMinY();
    }

    /** The highest block y of the dimension. */
    public int maxY() {
        return heights.getMinY() + heights.getHeight() - 1;
    }

    /** The highest y the generator's noise fills; a solid block there is a roof. */
    public int noiseTop() {
        return noiseTop;
    }
}
