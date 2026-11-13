package com.customdimensions.server.render.fast;

import com.customdimensions.server.mixin.NoiseBasedChunkGeneratorAccessor;
import com.customdimensions.server.render.Sampler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * One level's terrain as level-of-detail columns from the fast-render core,
 * for a distant-terrain store: a square of {@link #WIDTH} columns a side, one
 * every {@code 2^detail} blocks, each column's runs dressed, its biomes and
 * its {@link LodColumn} segments. The runs come from the level's own random
 * state, noise settings, biome source and fluid picker (one
 * {@link ColumnModel} per thread), sampled at the request's spacing.
 *
 * <p>Detail levels up to {@link #FINE_DETAIL} keep every run of the whole
 * noise column, roof included. Coarser ones take a shortcut from a pre-scan of
 * whole columns over the level's reach ({@link Shape}): a roofed level is
 * searched down from one height under its ceiling (the highest block open
 * between the ceiling and the floor under it in the most pre-scan columns,
 * when that is {@link #ROOF_START_SHARE} of them; whole columns otherwise),
 * and the roof is left out; a level of floating runs from
 * {@link ScanWindow#MARGIN} over its topmost tier band to as far under its
 * lowest run; any other from {@link ScanWindow#DEPTH} under its lowest
 * surface to {@link ScanWindow#MARGIN} over its highest. A tile whose ground
 * reaches its window's top (a floor or roof above a roofed level's start) or
 * that loses ground at its floor is scanned again to the noise top or floor,
 * so every column keeps the runs a whole column has in the window; a roof is
 * dropped after, and ground cut off at a surface window's floor is carried
 * down to the noise floor. Which shortcut applies
 * follows from the pre-scan's runs, never from the level's name. Structures,
 * features (trees) and carvers are not in the runs.
 */
public final class LodSource implements AutoCloseable {
    /** Columns a side of one request: a Distant Horizons data source. */
    public static final int WIDTH = 64;
    /** The coarsest detail level (2^detail blocks a column) answered with whole columns, every run kept. */
    public static final int FINE_DETAIL = 1;
    /** The pre-scan grid's most columns a side. */
    public static final int PRESCAN_SIDE = 128;
    /** The pre-scan's least spacing, in blocks. */
    public static final int PRESCAN_SPACING = 64;
    /**
     * A roofed level is searched from a fixed height under its ceiling only when
     * that height is open in at least this share of the pre-scan's floored
     * columns; otherwise most tiles would be scanned twice, and whole columns
     * cost less.
     */
    public static final double ROOF_START_SHARE = 0.9;
    /** The reach of a level that sets no border. */
    public static final int DEFAULT_REACH = 1024;

    /** Which coarse shortcut a level takes. */
    public enum Mode { SURFACE, ROOFED, TIERS }

    /** The pre-scan's answer: the shortcut and its window {@code from..to}. */
    public record Shape(Mode mode, int from, int to, int columns, int roofed, int floating, int spacing, String rule) {}

    /**
     * One column: its runs, their dress, each run's biome (one entry, the
     * column's, when it has none), and whether it is roofed (also when the
     * shortcut left its roof out: no sky light reaches its floor).
     */
    public record Column(ColumnRuns runs, RunDress<BlockState> dress, List<Holder<Biome>> biomes, boolean roofed) {}

    /** The coarse runs of a tile, the window they were scanned over, and which columns had their roof left out. */
    record Scan(ColumnRuns[] runs, int from, int to, boolean[] unroofed) {}

    private final String level;
    private final ThreadLocal<ColumnModel> models;
    private final ConcurrentLinkedQueue<ColumnModel> made = new ConcurrentLinkedQueue<>();
    private final int lo;
    private final int hi;
    private final int minY;
    private final int top;
    private final int seaLevel;
    private final java.util.function.IntSupplier reach;
    private final boolean skylit;
    private final LodColumn.Palette<BlockState> palette;
    private volatile Shape shape;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong columns = new AtomicLong();
    private final AtomicLongArray byDetail = new AtomicLongArray(16);

    /**
     * @param lo     the lowest block the noise fills
     * @param hi     the highest block the noise fills
     * @param reach  the radius round 0,0 the pre-scan covers, in blocks; read on the first coarse request
     */
    public LodSource(String level, Supplier<ColumnModel> factory, int lo, int hi, int minY, int height, int seaLevel, boolean skylit,
                     java.util.function.IntSupplier reach, BlockState body) {
        this.level = level;
        this.models = ThreadLocal.withInitial(() -> {
            ColumnModel m = factory.get();
            made.add(m);
            return m;
        });
        this.lo = lo;
        this.hi = hi;
        this.minY = minY;
        this.top = minY + height;
        this.seaLevel = seaLevel;
        this.skylit = skylit;
        this.reach = reach;
        this.palette = new LodColumn.Palette<>(Blocks.AIR.defaultBlockState(), body, BlockState::getLightEmission);
    }

    /** The source for a running level, from the random state and generator it generates with. */
    public static LodSource forLevel(ServerLevel level, String name, java.util.function.IntSupplier reach) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        boolean skylit = level.dimensionType().hasSkyLight();
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            var settings = noise.generatorSettings().value();
            var clamped = settings.noiseSettings().clampToHeightAccessor(level);
            int lo = clamped.minY();
            int hi = lo + clamped.height() - 1;
            var random = level.getChunkSource().randomState();
            var picker = ((NoiseBasedChunkGeneratorAccessor) (Object) noise).customdimensions$globalFluidPicker().get();
            return new LodSource(name, () -> new TileSampler(random, settings, noise, level, picker, lo, hi), lo, hi, level.getMinY(),
                    level.getHeight(), generator.getSeaLevel(), skylit, reach, settings.defaultBlock());
        }
        Sampler sampler = Sampler.of(name, generator, level.getMinY(), level.getHeight(), level.getSeed(), "level", level.registryAccess());
        return new LodSource(name, FastRender.models(sampler), sampler.noiseBottom(), sampler.noiseTop(), sampler.minY(),
                sampler.maxY() - sampler.minY() + 1, sampler.seaLevel(), skylit, reach, Blocks.STONE.defaultBlockState());
    }

    /** The source for a {@link Sampler}'s generator under its seed. */
    public static LodSource forSampler(Sampler sampler, int reach, boolean skylit) {
        BlockState body = sampler.generator() instanceof NoiseBasedChunkGenerator noise ? noise.generatorSettings().value().defaultBlock()
                : Blocks.STONE.defaultBlockState();
        return new LodSource(sampler.dimension(), FastRender.models(sampler), sampler.noiseBottom(), sampler.noiseTop(), sampler.minY(),
                sampler.maxY() - sampler.minY() + 1, sampler.seaLevel(), skylit, () -> reach, body);
    }

    /** Whether {@code detail} is answered with whole columns. */
    public static boolean fine(int detail) {
        return detail <= FINE_DETAIL;
    }

    /**
     * The {@link #WIDTH} × {@link #WIDTH} columns from block {@code (x0, z0)},
     * one every {@code 2^detail} blocks, index {@code i + WIDTH * j}.
     */
    public Column[] columns(int x0, int z0, int detail) {
        int step = 1 << detail;
        boolean coarse = !fine(detail);
        Shape s = coarse ? shape() : null;
        ColumnModel model = models.get();
        RunDress.Materials<BlockState> materials = model.materials();
        RunDress.Fluids<BlockState> fluids = model.fluids();
        BlockState fallback = model.defaultBlock();
        Column[] out = new Column[WIDTH * WIDTH];
        for (int tz = 0; tz < WIDTH; tz += FastRender.TILE) {
            for (int tx = 0; tx < WIDTH; tx += FastRender.TILE) {
                int x = x0 + tx * step;
                int z = z0 + tz * step;
                int n = FastRender.TILE;
                Scan scan = coarse ? coarse(model, s, x, z, step) : null;
                ColumnRuns[] runs = scan != null ? scan.runs() : model.scan(x, z, step, n, n);
                int[] surfaceTop = new int[runs.length];
                for (int c = 0; c < runs.length; c++) {
                    int r = runs[c].surfaceRun(hi, minY);
                    surfaceTop[c] = r == -2 ? runs[c].top(0) : r < 0 ? minY : runs[c].top(r);
                }
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        int c = i + n * j;
                        ColumnRuns r = runs[c];
                        int bx = x + i * step;
                        int bz = z + j * step;
                        int gx = (surfaceTop[Math.min(i + 1, n - 1) + n * j] - surfaceTop[Math.max(i - 1, 0) + n * j]) / step;
                        int gz = (surfaceTop[i + n * Math.min(j + 1, n - 1)] - surfaceTop[i + n * Math.max(j - 1, 0)]) / step;
                        materials.column(bx, bz, gx, gz);
                        RunDress<BlockState> d = RunDress.of(r, bx, bz, lo, hi, materials, fluids, fallback, true);
                        List<Holder<Biome>> biomes = new ArrayList<>(Math.max(1, r.count()));
                        for (int k = 0; k < r.count(); k++) biomes.add(model.biome(bx, r.top(k) + 1, bz));
                        if (r.count() == 0) biomes.add(model.biome(bx, seaLevel, bz));
                        boolean roofed = r.roofed(hi) || scan != null && scan.unroofed()[c];
                        out[(tx + i) + WIDTH * (tz + j)] = new Column(r, d, biomes, roofed);
                    }
                }
            }
        }
        if (model instanceof TileSampler tiles) tiles.trim();
        requests.incrementAndGet();
        columns.addAndGet((long) WIDTH * WIDTH);
        byDetail.incrementAndGet(Math.clamp(detail, 0, 15));
        return out;
    }

    /** A column's segments, topmost first, from the level's top to its floor. */
    public List<LodColumn.Segment<BlockState, Holder<Biome>>> segments(Column c) {
        List<LodColumn.Segment<BlockState, Holder<Biome>>> out = new ArrayList<>(4 + 4 * c.runs().count());
        LodColumn.segments(c.runs(), c.dress(), c.biomes(), minY, top, c.roofed(), skylit, palette, out);
        return out;
    }

    /** One tile's runs by the level's coarse shortcut. */
    Scan coarse(ColumnModel model, Shape s, int x, int z, int step) {
        int n = FastRender.TILE;
        int from = s.from();
        int to = s.to();
        ColumnRuns[] runs = model.scan(x, z, step, n, n, from, to);
        boolean up = false;
        boolean down = false;
        for (ColumnRuns r : runs) {
            if (to < hi && r.count() > 0 && r.top(0) >= to) up = true;
            if (from > lo && s.mode() == Mode.SURFACE && r.count() == 0) down = true;
            if (from > lo && s.mode() == Mode.TIERS && r.count() > 0 && r.bottom(r.count() - 1) <= from) down = true;
        }
        if (up || down) {
            from = down ? lo : from;
            to = up ? hi : to;
            runs = model.scan(x, z, step, n, n, from, to);
        }
        boolean[] unroofed = new boolean[runs.length];
        for (int c = 0; c < runs.length; c++) {
            ColumnRuns r = runs[c].clip(to);
            if (from > lo) r = r.withFloor(from, lo);
            if (s.mode() == Mode.ROOFED && r.roofed(hi) && r.count() > 1) {
                r = r.dropTop();
                unroofed[c] = true;
            } else if (s.mode() == Mode.ROOFED && to < hi) {
                unroofed[c] = true;
            }
            runs[c] = r;
        }
        return new Scan(runs, from, to, unroofed);
    }

    /** The pre-scan's shape, computed once, on the first coarse request. */
    public Shape shape() {
        Shape s = shape;
        if (s != null) return s;
        synchronized (this) {
            if (shape == null) shape = prescan(models.get());
            return shape;
        }
    }

    private Shape prescan(ColumnModel model) {
        int reach = reach();
        int spacing = Math.max(PRESCAN_SPACING, Math.ceilDiv(Math.ceilDiv(2 * reach, PRESCAN_SIDE), 16) * 16);
        int side = Math.min(PRESCAN_SIDE, 2 * reach / spacing + 1);
        int origin = -(side / 2) * spacing;
        int roofed = 0;
        int floating = 0;
        int count = 0;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        int lowestSurface = Integer.MAX_VALUE;
        long[] open = new long[hi - lo + 2];
        int floored = 0;
        for (int tz = 0; tz < side; tz += FastRender.TILE) {
            for (int tx = 0; tx < side; tx += FastRender.TILE) {
                int nx = Math.min(FastRender.TILE, side - tx);
                int nz = Math.min(FastRender.TILE, side - tz);
                for (ColumnRuns r : model.scan(origin + tx * spacing, origin + tz * spacing, spacing, nx, nz)) {
                    count++;
                    if (r.count() == 0) continue;
                    int bottom = r.bottom(r.count() - 1);
                    lowest = Math.min(lowest, bottom);
                    if (bottom > lo) floating++;
                    if (r.roofed(hi)) {
                        roofed++;
                        if (r.count() > 1 && r.top(1) + 1 <= r.bottom(0) - 1) {
                            open[r.top(1) + 1 - lo]++;
                            open[r.bottom(0) - lo]--;
                            floored++;
                        }
                        continue;
                    }
                    highest = Math.max(highest, r.top(0));
                    lowestSurface = Math.min(lowestSurface, r.top(0));
                }
            }
        }
        int align = Math.max(1, model.align());
        String mode = ScanWindow.mode(count, roofed, floating);
        if (ScanWindow.FULL.equals(mode)) {
            long run = 0;
            long best = 0;
            int to = hi;
            for (int y = lo; y <= hi; y++) {
                run += open[y - lo];
                if (run > 0 && run >= best) {
                    best = run;
                    to = y;
                }
            }
            String openAt = "block " + to + " is open between the ceiling and the floor under it in " + best + " of " + floored
                    + " roofed pre-scan columns with a floor";
            if (best < ROOF_START_SHARE * floored) {
                return new Shape(Mode.ROOFED, lo, hi, count, roofed, floating, spacing,
                        "roofed in " + roofed + " of " + count + " pre-scan columns: whole columns, the roof left out; no fixed start, as "
                                + openAt + ", under " + ROOF_START_SHARE);
            }
            return new Shape(Mode.ROOFED, lo, to, count, roofed, floating, spacing,
                    "roofed in " + roofed + " of " + count + " pre-scan columns: searched from block " + to + " down to the noise floor, "
                            + "the roof left out; " + openAt);
        }
        if (highest == Integer.MIN_VALUE) {
            return new Shape(Mode.SURFACE, lo, hi, count, roofed, floating, spacing, "no ground in the pre-scan: whole columns");
        }
        if (ScanWindow.TIERS.equals(mode)) {
            int from = Math.max(lo, Math.floorDiv(lowest - ScanWindow.MARGIN, align) * align);
            int to = Math.min(hi, highest + ScanWindow.MARGIN);
            return new Shape(Mode.TIERS, from, to, count, roofed, floating, spacing,
                    "floating runs in " + floating + " of " + count + " pre-scan columns: searched from block " + to + ", "
                            + ScanWindow.MARGIN + " over the topmost tier band, to " + from + ", " + ScanWindow.MARGIN + " under the lowest run");
        }
        int from = Math.max(lo, Math.floorDiv(lowestSurface - ScanWindow.DEPTH, align) * align);
        int to = Math.min(hi, highest + ScanWindow.MARGIN);
        return new Shape(Mode.SURFACE, from, to, count, roofed, floating, spacing,
                "surface: searched from block " + to + ", " + ScanWindow.MARGIN + " over the highest surface, to " + from + ", "
                        + ScanWindow.DEPTH + " under the lowest; ground at the floor carried down to the noise floor");
    }

    /** The coarse shortcut against whole columns on the same tiles. */
    public record Comparison(Mode mode, int detail, int columns, double surfaceWithinOne, double surfaceExact, double runsKept,
                             double meanRunsFull, double meanRunsCoarse, long fullNanos, long coarseNanos) {}

    /**
     * Compares the shortcut at {@code detail} with whole columns on
     * {@code tiles × tiles} tiles spread over the reach: the surface (the
     * floor under a roof on the whole column, the topmost run of the
     * shortcut's), within one block and exact, and the runs both hold inside
     * the shortcut's window, equal.
     */
    public Comparison compare(int detail, int tiles) {
        Shape s = shape();
        ColumnModel model = models.get();
        int reach = reach();
        int step = 1 << detail;
        int span = FastRender.TILE * step;
        int gap = Math.max(span, Math.floorDiv(2 * reach - span, Math.max(1, tiles - 1)));
        long within = 0;
        long exact = 0;
        long kept = 0;
        long n = 0;
        long runsFull = 0;
        long runsCoarse = 0;
        long fullNanos = 0;
        long coarseNanos = 0;
        for (int a = 0; a < tiles; a++) {
            for (int b = 0; b < tiles; b++) {
                int x = Math.floorDiv(-reach + a * gap, 16) * 16;
                int z = Math.floorDiv(-reach + b * gap, 16) * 16;
                long t = System.nanoTime();
                ColumnRuns[] full = model.scan(x, z, step, FastRender.TILE, FastRender.TILE);
                fullNanos += System.nanoTime() - t;
                t = System.nanoTime();
                Scan coarse = coarse(model, s, x, z, step);
                coarseNanos += System.nanoTime() - t;
                for (int c = 0; c < full.length; c++) {
                    ColumnRuns f = full[c];
                    ColumnRuns k = coarse.runs()[c];
                    int expected = f.surface(hi, minY);
                    int got = k.count() == 0 ? minY : k.top(0) + 1;
                    if (s.mode() == Mode.ROOFED && f.roofed(hi) && f.count() > 1) f = f.dropTop();
                    if (Math.abs(expected - got) <= 1) within++;
                    if (expected == got) exact++;
                    int floor = coarse.from();
                    if (f.within(floor, coarse.to()).sameAs(k.within(floor, coarse.to()))) kept++;
                    runsFull += f.count();
                    runsCoarse += k.count();
                    n++;
                }
            }
        }
        double d = Math.max(1, n);
        return new Comparison(s.mode(), detail, (int) n, within / d, exact / d, kept / d, runsFull / d, runsCoarse / d, fullNanos, coarseNanos);
    }

    public String level() {
        return level;
    }

    /** The radius round 0,0 the pre-scan covers, in blocks. */
    public int reach() {
        return Math.max(PRESCAN_SPACING, reach.getAsInt());
    }

    /** Requests answered, columns returned, and requests per detail level, so far. */
    public long requests() {
        return requests.get();
    }

    public long columnsServed() {
        return columns.get();
    }

    public long requests(int detail) {
        return byDetail.get(Math.clamp(detail, 0, 15));
    }

    public int minY() {
        return minY;
    }

    public int noiseTop() {
        return hi;
    }

    @Override
    public void close() {
        for (ColumnModel m; (m = made.poll()) != null; ) m.close();
    }
}
