// Derived from seedlod's tile generator (dev.winter.seedlod.gen.TileGen, MIT, (c) 2026 Derped;
// THIRD_PARTY_NOTICES.md): the per-thread sampler set over a density buffer pool, interpolation
// stripped for lattice sampling, the interpolation cell read from the router, and the material rule
// compiled into a context outside a chunk.
package com.customdimensions.server.render.fast;

import com.customdimensions.server.mixin.MaterialRuleContextAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/**
 * One thread's view of a noise generator for {@link FastRender}: its full
 * final density in batches (through a {@link RunScanner}), its biome source
 * per quart, its compiled material rule at run faces and its global fluid
 * picker at open blocks. Built from the random state, settings and biome
 * source the level generates with, so runtime registry entries, zones and
 * fluid rules are the level's own. Not thread-safe; close it when done.
 */
public final class TileSampler implements ColumnModel {
    /** Every block from the cell corners under the tile ({@link LatticeScanner}). */
    public static final String LATTICE = "lattice";
    /** A scout on cell rows, crossings refined block by block ({@link RunScanner}). */
    public static final String REFINE = "refine";
    /** Picks {@value #REFINE} for a measurement against it; a density without {@code interpolated} always refines. */
    public static final String SCAN_PROPERTY = "customdimensions.fastrender.scan";
    private final RandomState random;
    private final LatticeScanner corners;
    private final DensityBufferPool pool;
    private final DensitySampler.Bound exact;
    private final DensitySampler.Bound lattice;
    private final RunScanner scanner;
    private final BiomeResolver biomes;
    private final Long2ObjectOpenHashMap<Holder<Biome>> quarts = new Long2ObjectOpenHashMap<>();
    private final MaterialRuleContext rules;
    private final RuleEvaluator rule;
    private final Aquifer.FluidPicker picker;
    private final BlockState defaultBlock;
    private final int lo;
    private final int hi;
    private final int minQuartY;
    private final int maxQuartY;
    private final int[] cell;

    /**
     * @param lo the lowest block the noise fills
     * @param hi the highest block the noise fills
     */
    public TileSampler(RandomState random, NoiseGeneratorSettings settings, ChunkGenerator generator, LevelHeightAccessor heights,
                       Aquifer.FluidPicker picker, int lo, int hi) {
        this.random = random;
        this.pool = random.acquireDensityBufferPool();
        SamplerContext context = SamplerContext.builder().useBufferArena(pool).enableCaches().build();
        DensitySamplerSet samplers = random.samplersWithContext(context);
        DensityFunction density = settings.noiseRouter().finalDensity();
        this.cell = interpolationCell(density);
        this.exact = samplers.get(density);
        this.lattice = cell[0] == 0 ? exact : samplers.get(stripInterpolation(density));
        this.lo = lo;
        this.hi = hi;
        this.scanner = new RunScanner(volume(lattice), volume(exact), lo, hi, cell[0], cell[1]);
        this.corners = scan().equals(LATTICE) && LatticeInterpolation.interpolates(density)
                ? new LatticeScanner(samplers.get(LatticeInterpolation.rewrite(density)), density, lo, hi) : null;
        this.biomes = generator.getBiomeSource().createResolver(random.createClimateSampler(context));
        this.minQuartY = QuartPos.fromBlock(heights.getMinY());
        this.maxQuartY = minQuartY + QuartPos.fromBlock(heights.getHeight()) - 1;
        // A one-block expected volume keeps a rule that prefills a chunk (ore veins) to the blocks it is asked about.
        this.rules = MaterialRuleContextAccessor.customdimensions$create(random.surfaceSystem(), random,
                new DensityVolume(1, 1, 1, 0, 0, 0), samplers, pos -> biome(pos.getX(), pos.getY(), pos.getZ()),
                new WorldGenerationContext(generator, heights), generator.getBiomeSource().possibleBiomes());
        this.rule = settings.materialRule().value().compile(rules);
        this.picker = picker;
        this.defaultBlock = settings.defaultBlock();
    }

    private static RunScanner.Density volume(DensitySampler.Bound sampler) {
        return (x0, y0, z0, nx, ny, nz, sxz, sy, out) -> {
            DensityVolume v = new DensityVolume(nx, ny, nz, x0, y0, z0, sxz, sy, sxz);
            try (ScopedDensityBuffer buffer = sampler.sampleVolume(v)) {
                for (int z = 0; z < nz; z++) {
                    for (int x = 0; x < nx; x++) {
                        int o = (z * nx + x) * ny;
                        for (int y = 0; y < ny; y++) out[o + y] = buffer.get(v.indexUnchecked(x, y, z));
                    }
                }
            }
        };
    }

    /** {@code interpolated} replaced by its input everywhere: the same value on the cell lattice, without interpolating. */
    static DensityFunction stripInterpolation(DensityFunction function) {
        DfRewriteRule rule = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction f) {
                if (f instanceof DensityFunctions.HolderHolder(var holder)) f = holder.value();
                if (f instanceof InterpolatedFunction interpolated) return this.rewrite(interpolated.input());
                return f.rewriteChildren(this);
            }
        };
        return rule.rewrite(function);
    }

    /**
     * The interpolation cell of the density as {xz, y} blocks: {0, 0} when it
     * has no {@code interpolated}, or when two of them use different cells
     * (no one lattice is exact for both).
     */
    static int[] interpolationCell(DensityFunction function) {
        Set<Long> cells = new LinkedHashSet<>();
        new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction f) {
                if (f instanceof DensityFunctions.HolderHolder(var holder)) f = holder.value();
                if (f instanceof InterpolatedFunction interpolated) {
                    cells.add((long) interpolated.cellSizeXz() << 32 | interpolated.cellSizeY());
                }
                return f.rewriteChildren(this);
            }
        }.rewrite(function);
        if (cells.size() != 1) return new int[] {0, 0};
        long only = cells.iterator().next();
        return new int[] {(int) (only >>> 32), (int) only};
    }

    /** The runs of {@code nx × nz} columns from {@code (x0, z0)} at {@code step}, index {@code i + nx * j}. */
    @Override
    public ColumnRuns[] scan(int x0, int z0, int step, int nx, int nz) {
        quarts.clear();
        return corners != null ? corners.scan(x0, z0, step, nx, nz) : scanner.scan(x0, z0, step, nx, nz);
    }

    public RunScanner scanner() {
        return scanner;
    }

    /** {@value #LATTICE} or {@value #REFINE}: how runs are found ({@value #SCAN_PROPERTY}, {@value #LATTICE} by default). */
    static String scan() {
        return System.getProperty(SCAN_PROPERTY, LATTICE);
    }

    /** {@value #LATTICE} when the runs come from a {@link LatticeScanner}, {@value #REFINE} from a {@link RunScanner}. */
    @Override
    public String mode() {
        return corners != null ? LATTICE : REFINE;
    }

    @Override
    public int[] cell() {
        return cell.clone();
    }

    @Override
    public long[] counts() {
        if (corners != null) return new long[] {corners.corners(), 0, corners.columns()};
        return new long[] {scanner.scoutPoints(), scanner.refinedCells(), scanner.columns()};
    }

    /** The noise biome at {@code (x, y, z)}, {@code y} clamped into the dimension as a chunk clamps it; cached per quart for a tile. */
    @Override
    public Holder<Biome> biome(int x, int y, int z) {
        int qx = QuartPos.fromBlock(x);
        int qy = Math.clamp(QuartPos.fromBlock(y), minQuartY, maxQuartY);
        int qz = QuartPos.fromBlock(z);
        long key = ((long) qx & 0x3FFFFFL) << 42 | ((long) qy & 0xFFFFFL) << 22 | (long) qz & 0x3FFFFFL;
        Holder<Biome> b = quarts.get(key);
        if (b == null) {
            b = biomes.getNoiseBiome(qx, qy, qz);
            quarts.put(key, b);
        }
        return b;
    }

    /** The material rule as a {@link RunDress.Materials}. */
    @Override
    public RunDress.Materials<BlockState> materials() {
        MaterialRuleContextAccessor context = (MaterialRuleContextAccessor) (Object) rules;
        return new RunDress.Materials<>() {
            @Override
            public void column(int x, int z, int gradientX, int gradientZ) {
                context.customdimensions$updateXZ(x, z, gradientX, gradientZ);
            }

            @Override
            public BlockState at(int x, int y, int z, int depthAbove, int depthBelow, int waterHeight) {
                context.customdimensions$updateY(depthAbove, depthBelow, waterHeight, y);
                return rule.tryApply(x, y, z);
            }
        };
    }

    /** The level's global fluid picker as {@link RunDress.Fluids}. */
    @Override
    public RunDress.Fluids<BlockState> fluids() {
        return (x, y, z) -> {
            Aquifer.FluidStatus status = picker.computeFluid(x, y, z);
            BlockState state = status.at(y);
            return state.getFluidState().isEmpty() ? null : new RunDress.Fluid<>(state, status.fluidLevel());
        };
    }

    @Override
    public BlockState defaultBlock() {
        return defaultBlock;
    }

    public int lo() {
        return lo;
    }

    public int hi() {
        return hi;
    }

    /** Lets go of buffers the last few tiles did not use. */
    public void trim() {
        pool.garbageCollect();
    }

    @Override
    public void close() {
        random.releaseDensityBufferPool(pool);
    }
}
