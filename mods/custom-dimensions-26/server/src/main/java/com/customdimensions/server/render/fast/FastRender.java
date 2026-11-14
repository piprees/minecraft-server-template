package com.customdimensions.server.render.fast;

import com.customdimensions.core.render.ColumnSamples;
import com.customdimensions.core.render.RenderFiles;
import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.core.render.RenderLayers;
import com.customdimensions.core.render.SliceChoice;
import com.customdimensions.server.mixin.NoiseBasedChunkGeneratorAccessor;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.render.Sampler;
import com.customdimensions.server.render.StructureStarts;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.IntConsumer;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * {@code /customdim fastrender}: a whole dimension, border to border, from
 * its density's solid runs ({@link ColumnModel}) instead of noise columns.
 * Writes the standard render's biome and height images and sidecar shape,
 * with {@code kind} {@code fastrender}, plus hillshade, map colour, the floor
 * under a roof, a horizontal cut and the second to fourth runs where enough
 * columns have them, structure footprints, a gzipped run list of every
 * column, and a comparison against {@link Sampler#sample} on a sparse grid.
 * The layers drawn follow from the runs found, never from the dimension's
 * name.
 */
public final class FastRender {
    /** Columns per tile side: one batch of the scout. */
    public static final int TILE = 16;
    /** {@value #BATCH_PROPERTY}={@code n} scans batches of n × n columns, for a measurement against {@link #TILE}. */
    public static final String BATCH_PROPERTY = "customdimensions.fastrender.batch";

    /** A step at or over which columns share no interpolation corners, so smaller batches only tighten windows. */
    static final int COARSE_STEP = 16;

    /**
     * Columns per batch side: half a {@link #TILE} from {@value #COARSE_STEP}
     * blocks a step (a tile's scan window spans less relief), else a tile;
     * {@value #BATCH_PROPERTY} when set.
     */
    static int batch(int step) {
        Integer set = Integer.getInteger(BATCH_PROPERTY);
        if (set != null) return Math.clamp(set, 1, 64);
        return step >= COARSE_STEP ? TILE / 2 : TILE;
    }
    public static final int MAX_STEP = 16;
    /** The image side the default step aims at. */
    public static final int TARGET_SIDE = 2048;
    /** Spacing of the comparison grid and the pre-scan that picks the cut, in blocks. */
    public static final int COMPARE_SPACING = 64;
    /** A roof, cut or tier layer is drawn when at least this share of columns has one. */
    public static final double LAYER_SHARE = 0.02;
    public static final String KIND = "fastrender";
    /** The run list's magic number, "CDR1". */
    public static final int RUNS_MAGIC = 0x43445231;
    public static final String FLUIDS = "the level's global fluid picker (sea level, lava level, dry zones) at the lowest open block "
            + "over each run; an aquifer's local water tables need a noise chunk and are not sampled";
    public static final String RUNS_FORMAT = "gzip members, big-endian: int magic 0x43445231, int side, int step, int x0, int z0, "
            + "int minY, int noiseTop; then per pixel, rows north to south, west to east: u8 count, and per run topmost first "
            + "i16 top, i16 bottom, u16 topBlock, u16 undersideBlock, u16 fluidBlock, i16 fluidLevel (block indices into palette, "
            + "65535 for none; fluidLevel is the block above the fluid's surface)";

    private FastRender() {}

    /** What a fast render reports back. */
    public record Result(String dimension, long seed, RenderGrid grid, Path sidecar, long sampleMillis, long totalMillis,
                         long columnsPerSecond, double speedup, double heightsWithinOne, double biomesEqual, int biomes,
                         int threads) {}

    /** The step that keeps a border-to-border image near {@link #TARGET_SIDE} pixels. */
    public static int defaultStep(int radius) {
        return Math.clamp(Math.ceilDiv(2 * radius, TARGET_SIDE), 1, MAX_STEP);
    }

    /** Whether {@code step} is allowed for {@code radius}. */
    public static boolean stepAllowed(int radius, int step) {
        return step >= 1 && step <= MAX_STEP && Math.ceilDiv(2 * radius, step) <= RenderGrid.WIDE_MAX_SIDE;
    }

    /** {@code fast-<seed>-r<radius>}. */
    public static String baseName(long seed, RenderGrid grid) {
        return "fast-" + RenderFiles.baseName(seed, grid);
    }

    /** Builds a model per thread for {@code sampler}'s generator. */
    static java.util.function.Supplier<ColumnModel> models(Sampler sampler) {
        if (sampler.generator() instanceof NoiseBasedChunkGenerator noise) {
            var settings = noise.generatorSettings().value();
            var picker = ((NoiseBasedChunkGeneratorAccessor) (Object) noise).customdimensions$globalFluidPicker().get();
            return () -> new TileSampler(sampler.random(), settings, noise, sampler.heights(), picker, sampler.noiseBottom(),
                    sampler.noiseTop());
        }
        return () -> new NoiseColumns(sampler);
    }

    /**
     * What a render draws besides the terrain: {@code base} names its files
     * (null for {@link #baseName}); the comparison, structure footprints and
     * run list each on or off; the fixed heights to slice; a pre-scan to
     * reuse (null to make one); and {@code floorFirst}, which scans a roofed
     * world from its floor to under its roof ({@link ScanWindow#FLOOR}) and
     * draws the floor in place of the roof; {@code images} off paints and
     * writes no layer, for a caller that draws its own from the pixels.
     */
    record Options(String base, boolean compare, boolean structures, boolean runs, List<SliceChoice.Slice> slices,
                   Prescan prescan, boolean floorFirst, boolean images) {
        Options(String base, boolean compare, boolean structures, boolean runs, List<SliceChoice.Slice> slices, Prescan prescan,
                boolean floorFirst) {
            this(base, compare, structures, runs, slices, prescan, floorFirst, true);
        }

        static Options full() {
            return new Options(null, true, true, true, List.of(), null, false);
        }
    }

    /** A render's result with what it drew, for a wave's sheet and a bench's comparisons. */
    record Rendered(Result result, ColumnSamples samples, FastPixels pixels, FastSlices slices, Map<String, int[]> rgb,
                    Map<String, Path> images, int[] colours, Prescan prescan, String windowMode, Map<String, Long> timings,
                    List<BlockState> blocks, List<FastLayers.Footprint> footprints) {}

    /** Renders {@code grid} for {@code sampler} on {@code pool} into {@code dir}. */
    public static Result run(Sampler sampler, RenderGrid grid, ExecutorService pool, int threads, Path dir, RenderJob job,
                             IntConsumer progress, long started) throws IOException {
        return render(sampler, grid, pool, threads, dir, job, progress, started, Options.full()).result();
    }

    static Rendered render(Sampler sampler, RenderGrid grid, ExecutorService pool, int threads, Path dir, RenderJob job,
                           IntConsumer progress, long started, Options options) throws IOException {
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("prepare", (System.nanoTime() - started) / 1_000_000);
        int side = grid.side();
        int step = grid.step();
        int lo = sampler.noiseBottom();
        int hi = sampler.noiseTop();
        int minY = sampler.minY();
        var factory = models(sampler);
        ConcurrentLinkedQueue<ColumnModel> made = new ConcurrentLinkedQueue<>();
        ThreadLocal<ColumnModel> models = ThreadLocal.withInitial(() -> {
            ColumnModel m = factory.get();
            made.add(m);
            return m;
        });
        Palette palette = new Palette();
        try {
            long t = System.nanoTime();
            Prescan pre = options.prescan() != null ? options.prescan() : prescan(grid, pool, models, lo, hi, minY, sampler.seaLevel());
            timings.put("prescan", (System.nanoTime() - t) / 1_000_000);
            boolean floorFirst = options.floorFirst() && pre.floorFirstHolds() && pre.window().align() > 0;
            ScanWindow window = pre.window().as(floorFirst ? ScanWindow.FLOOR : pre.window().mode());

            ColumnSamples samples = new ColumnSamples(grid, minY, sampler.maxY(), sampler.seaLevel());
            FastPixels pixels = new FastPixels(side);
            FastSlices slices = new FastSlices(side, options.slices());
            int batch = batch(step);
            List<RenderGrid.Band> bands = grid.bands(batch);
            byte[][] runData = new byte[bands.size()][];
            t = System.nanoTime();
            List<CompletableFuture<Void>> work = new ArrayList<>();
            java.util.concurrent.atomic.LongAdder scanNanos = new java.util.concurrent.atomic.LongAdder();
            for (int b = 0; b < bands.size(); b++) {
                int index = b;
                RenderGrid.Band band = bands.get(b);
                work.add(CompletableFuture.runAsync(() -> {
                    runData[index] = band(sampler, grid, band, batch, models.get(), samples, pixels, palette, pre.cutY(), window,
                            floorFirst, slices, options.runs(), lo, hi, minY, scanNanos);
                    if (job != null) job.advance((long) band.rows() * side, progress);
                }, pool));
            }
            CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join();
            long sampleMs = Math.max(1, (System.nanoTime() - t) / 1_000_000);
            timings.put("sample", sampleMs);
            // Thread time summed over workers: finding runs, and everything else a band does (faces, fluids, biomes, zones, run list).
            long scanMs = scanNanos.sum() / 1_000_000;
            timings.put("sampleScanThreadMillis", scanMs);
            timings.put("sampleDressThreadMillis", Math.max(0, sampleMs * threads - scanMs));

            long[] counts = new long[3];
            int[] cell = null;
            String mode = null;
            for (ColumnModel m : made) {
                long[] c = m.counts();
                for (int i = 0; i < 3; i++) counts[i] += c[i];
                if (cell == null) cell = m.cell();
                if (mode == null) mode = m.mode();
            }

            timings.put("slicesRowThreadMillis", slices.rowNanos.sum() / 1_000_000);
            timings.put("slicesColumnThreadMillis", slices.columnNanos.sum() / 1_000_000);

            t = System.nanoTime();
            List<FastLayers.Footprint> footprints = Collections.synchronizedList(new ArrayList<>());
            String structureError = null;
            if (options.structures()) try {
                int reach = (int) Math.ceil(grid.radius() * Math.sqrt(2));
                StructureStarts.find(sampler, sampler.structureState(), id -> true, grid.centreX(), grid.centreZ(), reach, pool,
                        id -> StructureStarts.CAP, id -> reach, null, (start, box) -> {
                            if (box != null) footprints.add(new FastLayers.Footprint(start.structure(), box.minX(), box.minZ(), box.maxX(), box.maxZ()));
                        });
            } catch (RuntimeException e) {
                structureError = String.valueOf(e);
            }
            timings.put("structures", (System.nanoTime() - t) / 1_000_000);

            t = System.nanoTime();
            Comparison comparison = options.compare() ? compare(sampler, grid, samples, pool) : Comparison.NONE;
            timings.put("compare", (System.nanoTime() - t) / 1_000_000);

            t = System.nanoTime();
            Files.createDirectories(dir);
            String base = options.base() != null ? options.base() : baseName(sampler.seed(), grid);
            int[] colours = palette.colours();
            Map<String, Path> images = new LinkedHashMap<>();
            Map<String, int[]> rgb = new LinkedHashMap<>();
            long columns = grid.columns();
            int roofed = pixels.roofedCount();
            boolean roofLayers = !floorFirst && roofed >= LAYER_SHARE * columns;
            int[] floor = options.images() && (roofLayers || floorFirst) ? FastLayers.floor(pixels, colours, samples) : null;
            int[] map = !options.images() ? null : floorFirst ? floor : FastLayers.map(pixels, colours);
            List<CompletableFuture<Void>> writes = new ArrayList<>();
            java.util.function.BiConsumer<String, java.util.function.Supplier<int[]>> layer = (name, paint) -> {
                if (!options.images()) return;
                synchronized (rgb) {
                    rgb.put(name, null);
                    images.put(name, dir.resolve(base + "-" + name + ".png"));
                }
                writes.add(CompletableFuture.runAsync(() -> {
                    int[] image = paint.get();
                    synchronized (rgb) {
                        rgb.put(name, image);
                    }
                    try {
                        RenderFiles.png(dir.resolve(base + "-" + name + ".png"), side, image);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }, pool));
            };
            if (floorFirst) {
                layer.accept("roofoff", () -> floor);
            } else {
                layer.accept("map", () -> map);
                if (roofLayers) {
                    layer.accept("roofoff", () -> floor);
                    layer.accept("cut", () -> FastLayers.cut(pixels, colours, pre.cutY()));
                }
            }
            layer.accept("biome", () -> RenderLayers.BIOME.paint(samples));
            layer.accept("height", () -> RenderLayers.HEIGHT.paint(samples));
            if (samples.hasZones()) layer.accept("zones", () -> RenderLayers.ZONES.paint(samples));
            layer.accept("hillshade", () -> FastLayers.hillshade(samples));
            List<Integer> tiers = new ArrayList<>();
            for (int tier = 0; tier < FastPixels.TIERS && !floorFirst && options.images(); tier++) {
                if (pixels.withRun(tier + 1) < LAYER_SHARE * columns) break;
                tiers.add(tier + 1);
                int index = tier;
                layer.accept("tier" + (tier + 1), () -> FastLayers.tier(pixels, colours, index));
            }
            List<String> slicesDrawn = new ArrayList<>();
            for (int s = 0; s < slices.size(); s++) {
                int index = s;
                String name = "slice-" + slices.slices.get(s).name();
                layer.accept(name, () -> slices.paint(index, colours));
                if (slices.differShare(s) > 0) {
                    layer.accept(name + "-biome", () -> slices.paintBiome(index));
                    slicesDrawn.add(name + "-biome");
                }
            }
            if (options.structures()) {
                layer.accept("structures", () -> FastLayers.structures(roofLayers ? floor : map, grid, footprints));
            }
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
            timings.put("images", (System.nanoTime() - t) / 1_000_000);

            t = System.nanoTime();
            Path runsFile = options.runs() ? dir.resolve(base + "-runs.bin.gz") : null;
            if (runsFile != null) writeRuns(runsFile, grid, minY, hi, runData);
            timings.put("runs", (System.nanoTime() - t) / 1_000_000);

            long total = (System.nanoTime() - started) / 1_000_000;
            timings.put("total", total);
            RenderFiles.Meta meta = new RenderFiles.Meta(sampler.dimension(), sampler.seed(), sampler.seedSource(),
                    RenderJob.SURFACE, RenderJob.BIOME_Y, timings, threads);
            JsonObject root = RenderFiles.sidecar(samples, meta, images);
            root.addProperty("kind", KIND);
            long rate = columns * 1000 / sampleMs;
            double speedup = comparison.referenceRate() <= 0 ? 0 : (double) rate / comparison.referenceRate();
            JsonObject fast = fastBlock(grid, threads, lo, hi, cell, mode, counts, pixels, roofLayers, roofed, tiers, pre, window, palette,
                    footprints, structureError, comparison, rate, speedup, runsFile);
            fast.addProperty("floorFirst", floorFirst);
            fast.add("slices", slicesBlock(slices));
            root.add("fast", fast);
            Path sidecar = dir.resolve(base + ".json");
            Path tmp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n");
            Files.move(tmp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Result result = new Result(sampler.dimension(), sampler.seed(), grid, sidecar, sampleMs, total, rate, speedup,
                    comparison.heightsWithinOne(), comparison.biomesEqual(), samples.biomeCounts().size(), threads);
            return new Rendered(result, samples, pixels, slices, rgb, images, colours, pre, window.mode(), timings, palette.states(),
                    List.copyOf(footprints));
        } finally {
            for (ColumnModel m : made) m.close();
        }
    }

    private static JsonObject slicesBlock(FastSlices slices) {
        JsonObject out = new JsonObject();
        out.addProperty("rule", "each fixed height top-down: solid ground by the material rule's block (the run's depths where the "
                + "scan window holds the height, " + FastSlices.ASSUMED_DEPTH + " blocks either side otherwise), a fluid by the "
                + "level's global fluid picker where nothing is solid over the height or the level has no aquifers, open space under "
                + "ground black and under open sky slate; the biome at that height where it differs from the surface biome");
        out.addProperty("rowsSampled", slices.rowsSampled.sum());
        out.addProperty("rowsFromRuns", slices.rowsFromRuns.sum());
        JsonArray list = new JsonArray();
        for (int s = 0; s < slices.size(); s++) {
            SliceChoice.Slice slice = slices.slices.get(s);
            JsonObject o = new JsonObject();
            o.addProperty("name", slice.name());
            o.addProperty("y", slice.y());
            o.addProperty("rule", slice.rule());
            o.addProperty("solidShare", Math.round(slices.solidShare(s) * 10000) / 10000.0);
            o.addProperty("biomeDiffersShare", Math.round(slices.differShare(s) * 10000) / 10000.0);
            list.add(o);
        }
        out.add("heights", list);
        return out;
    }

    /** One band of rows: its pixels filled, its run list returned as one gzip member. */
    static byte[] band(Sampler sampler, RenderGrid grid, RenderGrid.Band band, int batch, ColumnModel model, ColumnSamples samples,
                       FastPixels px, Palette palette, int cutY, ScanWindow scanWindow, boolean floorFirst, FastSlices slices,
                       boolean keepRuns, int lo, int hi, int minY, java.util.concurrent.atomic.LongAdder scanNanos) {
        int side = grid.side();
        int step = grid.step();
        int rows = band.rows();
        ColumnRuns[] bandRuns = new ColumnRuns[rows * side];
        @SuppressWarnings("unchecked")
        RunDress<BlockState>[] bandDress = new RunDress[rows * side];
        RunDress.Materials<BlockState> materials = model.materials();
        RunDress.Fluids<BlockState> fluids = model.fluids();
        BlockState fallback = model.defaultBlock();
        for (int tx = 0; tx < side; tx += batch) {
            int nx = Math.min(batch, side - tx);
            int x0 = grid.x(tx);
            int z0 = grid.z(band.firstRow());
            long t = System.nanoTime();
            int[] window = scanWindow.windowAt(x0, z0, grid.x(tx + nx - 1), grid.z(band.firstRow() + rows - 1));
            ColumnRuns[] runs = model.scan(x0, z0, step, nx, rows, window[0], window[1]);
            for (int[] wider = scanWindow.widen(runs, window[0], window[1]); wider != null;
                 wider = scanWindow.widen(runs, wider[0], wider[1])) {
                window = wider;
                runs = model.scan(x0, z0, step, nx, rows, wider[0], wider[1]);
            }
            ColumnRuns[] scanned = runs;
            if (floorFirst) {
                runs = runs.clone();
                for (int i = 0; i < runs.length; i++) {
                    if (runs[i].count() == 0 || runs[i].top(0) < window[1]) continue;
                    // Solid from the window's top to the noise floor: the roof is taken to reach the noise top, as a whole column reads it.
                    runs[i] = runs[i].bottom(0) <= lo ? new ColumnRuns(new int[] {lo}, new int[] {hi}, false) : runs[i].from(1);
                }
            }
            scanNanos.add(System.nanoTime() - t);
            boolean[][] sliceRows = new boolean[slices.size()][];
            long ts = System.nanoTime();
            for (int s = 0; s < slices.size(); s++) {
                int y = slices.slices.get(s).y();
                if (y >= window[0] && y <= window[1] || model.align() <= 0) {
                    slices.rowsFromRuns.increment();
                    continue;
                }
                sliceRows[s] = y < lo || y > hi ? new boolean[nx * rows] : model.solidRow(x0, z0, step, nx, rows, y);
                slices.rowsSampled.increment();
            }
            slices.rowNanos.add(System.nanoTime() - ts);
            int[] surfaceTop = new int[runs.length];
            for (int i = 0; i < runs.length; i++) {
                int r = runs[i].surfaceRun(hi, minY);
                surfaceTop[i] = r == -2 ? runs[i].top(0) : r < 0 ? minY : runs[i].top(r);
            }
            for (int j = 0; j < rows; j++) {
                for (int i = 0; i < nx; i++) {
                    int c = i + nx * j;
                    ColumnRuns r = runs[c];
                    int x = x0 + i * step;
                    int z = z0 + j * step;
                    int gx = (surfaceTop[Math.min(i + 1, nx - 1) + nx * j] - surfaceTop[Math.max(i - 1, 0) + nx * j]) / step;
                    int gz = (surfaceTop[i + nx * Math.min(j + 1, rows - 1)] - surfaceTop[i + nx * Math.max(j - 1, 0)]) / step;
                    materials.column(x, z, gx, gz);
                    boolean roofed = r.roofed(hi);
                    RunDress<BlockState> d = RunDress.of(r, x, z, lo, hi, materials, fluids, fallback, roofed);
                    int pxl = tx + i;
                    int pyl = band.firstRow() + j;
                    fill(sampler, model, samples, px, palette, r, d, x, z, pxl, pyl, cutY, hi, minY);
                    if (slices.size() > 0) {
                        long tc = System.nanoTime();
                        slice(model, samples, slices, palette, materials, fluids, fallback, scanned[c], sliceRows, c, floorFirst, x, z,
                                pyl * side + pxl, pxl, pyl);
                        slices.columnNanos.add(System.nanoTime() - tc);
                    }
                    if (keepRuns) {
                        bandRuns[j * side + pxl] = r;
                        bandDress[j * side + pxl] = d;
                    }
                }
            }
        }
        if (model instanceof TileSampler tiles) tiles.trim();
        return keepRuns ? serialise(bandRuns, bandDress, palette) : null;
    }

    /**
     * Pixel {@code i} at each slice: solid from the scanned runs where they
     * hold the height, else from {@code rows}; a solid block dressed by the
     * material rule, open space filled by the fluid picker where nothing
     * solid is over it or the level has no aquifers.
     */
    private static void slice(ColumnModel model, ColumnSamples samples, FastSlices slices, Palette palette,
                              RunDress.Materials<BlockState> materials, RunDress.Fluids<BlockState> fluids, BlockState fallback,
                              ColumnRuns runs, boolean[][] rows, int c, boolean floorFirst, int x, int z, int i, int px, int py) {
        int top = runs.count() > 0 ? runs.top(0) : Integer.MIN_VALUE;
        String surfaceBiome = samples.biome(px, py);
        for (int s = 0; s < slices.size(); s++) {
            int y = slices.slices.get(s).y();
            int run = rows[s] == null ? runs.runAt(y) : -1;
            boolean solid = rows[s] == null ? run >= 0 : rows[s][c];
            byte kind;
            short block;
            if (solid) {
                int above = run >= 0 ? runs.top(run) - y + 1 : FastSlices.ASSUMED_DEPTH;
                int below = run >= 0 ? y - runs.bottom(run) + 1 : FastSlices.ASSUMED_DEPTH;
                BlockState state = materials.at(x, y, z, above, below, Integer.MIN_VALUE);
                kind = FastSlices.SOLID;
                block = palette.index(state != null ? state : fallback);
            } else {
                boolean sky = !floorFirst && y > top;
                RunDress.Fluid<BlockState> fluid = sky || !model.aquifers() ? fluids.at(x, y, z) : null;
                if (fluid != null) {
                    kind = FastSlices.FLUID;
                    block = palette.index(fluid.state());
                } else {
                    kind = sky ? FastSlices.SKY : FastSlices.CAVE;
                    block = FastPixels.NONE;
                }
            }
            String id = Sampler.id(model.biome(x, y, z));
            slices.set(s, i, kind, block, id, !id.equals(surfaceBiome));
        }
    }

    private static void fill(Sampler sampler, ColumnModel model, ColumnSamples samples, FastPixels p, Palette palette,
                             ColumnRuns r, RunDress<BlockState> d, int x, int z, int px, int py, int cutY, int hi, int minY) {
        int i = py * p.side + px;
        int n = r.count();
        p.runs[i] = (byte) Math.min(n, 255);
        p.roofed[i] = r.roofed(hi);
        int surfaceRun = r.surfaceRun(hi, minY);
        int h = r.surface(hi, minY);
        ColumnSamples.Fluid fluid = ColumnSamples.Fluid.NONE;
        if (surfaceRun >= 0 && d.fluid(surfaceRun) != null) fluid = Sampler.fluid(d.fluid(surfaceRun));
        Holder<Biome> biome = model.biome(x, h, z);
        samples.set(px, py, h, fluid, Sampler.id(biome));
        if (sampler.hasZones()) {
            Sampler.ZoneHit zone = sampler.topZone(x, z);
            samples.setZone(px, py, zone.index(), zone.weight());
        }
        if (n == 0) return;
        p.top[i] = (short) r.top(0);
        p.topBlock[i] = face(palette, d, 0);
        p.topGround[i] = palette.index(d.top(0));
        p.topDepth[i] = depth(d, r, 0);
        int floor = surfaceRun == -2 ? 0 : surfaceRun;
        if (floor >= 0) {
            p.floorBlock[i] = face(palette, d, floor);
            p.floorGround[i] = palette.index(d.top(floor));
            p.floorDepth[i] = depth(d, r, floor);
        }
        for (int t = 0; t < FastPixels.TIERS && t + 1 < n; t++) {
            p.tierTop[t][i] = (short) r.top(t + 1);
            p.tierBlock[t][i] = face(palette, d, t + 1);
        }
        int cut = r.runAtOrBelow(cutY);
        if (cut >= 0) {
            p.cutTop[i] = (short) Math.min(r.top(cut), cutY);
            p.cutBlock[i] = face(palette, d, cut);
        }
    }

    /** The block seen from above at run {@code i}: the fluid over it, else its top. */
    private static short face(Palette palette, RunDress<BlockState> d, int i) {
        BlockState f = d.fluid(i);
        return palette.index(f != null ? f : d.top(i));
    }

    private static byte depth(RunDress<BlockState> d, ColumnRuns r, int i) {
        if (d.fluid(i) == null) return 0;
        return (byte) Math.clamp(d.fluidLevel(i) - r.top(i) - 1, 1, 255);
    }

    private static byte[] serialise(ColumnRuns[] runs, RunDress<BlockState>[] dress, Palette palette) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(runs.length * 16);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes, 1 << 16) {
            {
                def.setLevel(java.util.zip.Deflater.BEST_SPEED);
            }
        })) {
            for (int c = 0; c < runs.length; c++) {
                ColumnRuns r = runs[c];
                RunDress<BlockState> d = dress[c];
                out.writeByte(r.count());
                for (int i = 0; i < r.count(); i++) {
                    out.writeShort(r.top(i));
                    out.writeShort(r.bottom(i));
                    out.writeShort(palette.index(d.top(i)));
                    out.writeShort(d.bottom(i) == null ? -1 : palette.index(d.bottom(i)));
                    out.writeShort(d.fluid(i) == null ? -1 : palette.index(d.fluid(i)));
                    out.writeShort(d.fluid(i) == null ? 0 : d.fluidLevel(i));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void writeRuns(Path file, RenderGrid grid, int minY, int noiseTop, byte[][] bands) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            try (DataOutputStream h = new DataOutputStream(new GZIPOutputStream(header))) {
                h.writeInt(RUNS_MAGIC);
                h.writeInt(grid.side());
                h.writeInt(grid.step());
                h.writeInt(grid.x(0));
                h.writeInt(grid.z(0));
                h.writeInt(minY);
                h.writeInt(noiseTop);
            }
            out.write(header.toByteArray());
            for (byte[] band : bands) out.write(band);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The cut height, the coarse grid's roof share, its floors (the top of the
     * run under a roof, else the top run; {@link Integer#MIN_VALUE} for none),
     * the profile slices are chosen from, and the roof's thickness (blocks from
     * the noise top to its underside) at the {@value #ROOF_QUANTILE} quantile.
     * A floor window's top is that thickness and {@value #ROOF_MARGIN} under the
     * noise top, or {@value #ROOF_MARGIN} over the floors' {@value #FLOOR_QUANTILE}
     * quantile when that is higher.
     */
    record Prescan(int cutY, String rule, int columns, int roofed, int floating, ScanWindow window, int[] floors,
                   SliceChoice.Profile profile, int roofThickness, double floorAgreement) {
        /** Whether a roofed world's floor-first scan holds on the pre-scan's own columns ({@value #FLOOR_FIRST_AGREEMENT}). */
        boolean floorFirstHolds() {
            return roofedWorld() && floorAgreement >= FLOOR_FIRST_AGREEMENT;
        }

        /** Whether a roof covers {@link #LAYER_SHARE} of the coarse columns. */
        boolean roofedWorld() {
            return floors != null && roofed > 0 && roofed >= LAYER_SHARE * columns;
        }
    }

    /** The quantile of roof thickness a floor window starts under. */
    static final double ROOF_QUANTILE = 0.5;
    /** Blocks between the measured roof and the top of a floor window. */
    static final int ROOF_MARGIN = 8;
    /** The share of pre-scan columns whose floor a floor window must find within one block before a pass scans from the floor. */
    static final double FLOOR_FIRST_AGREEMENT = 0.95;

    /**
     * The share of {@code runs} (whole columns) whose surface a floor window
     * topped at {@code start} reads within one block of the whole column's: a
     * run reaching {@code start} dropped as the roof's, or, reaching the noise
     * floor too, read as solid to the noise top.
     */
    static double floorAgreement(ColumnRuns[] runs, int start, int lo, int hi, int minY) {
        long n = 0;
        long within = 0;
        for (ColumnRuns r : runs) {
            if (r == null) continue;
            n++;
            int i = 0;
            while (i < r.count() && r.bottom(i) > start) i++;
            ColumnRuns clipped = r.from(i);
            int floor;
            if (clipped.count() > 0 && clipped.top(0) >= start) {
                floor = clipped.bottom(0) <= lo ? hi + 1 : clipped.from(1).surface(hi, minY);
            } else {
                floor = clipped.surface(hi, minY);
            }
            if (Math.abs(floor - r.surface(hi, minY)) <= 1) within++;
        }
        return n == 0 ? 0 : (double) within / n;
    }

    /** The quantile of floor heights a floor window's top stays over. */
    static final double FLOOR_QUANTILE = 0.99;

    /** {@value #WINDOW_PROPERTY}={@code off} scans whole columns, for a measurement against windows. */
    public static final String WINDOW_PROPERTY = "customdimensions.fastrender.window";

    /**
     * A coarse pass every {@link #COMPARE_SPACING} blocks that picks the cut:
     * the height open in the most roofed columns, between a roof and the
     * floor under it. The middle of the noise when nothing is roofed.
     */
    static Prescan prescan(RenderGrid grid, ExecutorService pool, ThreadLocal<ColumnModel> models, int lo, int hi, int minY,
                           int seaLevel) {
        return prescan(grid, pool, models, lo, hi, minY, seaLevel, COMPARE_SPACING);
    }

    /** As above on a coarse grid about {@code spacing} blocks apart (a whole number of {@code grid}'s steps). */
    static Prescan prescan(RenderGrid grid, ExecutorService pool, ThreadLocal<ColumnModel> models, int lo, int hi, int minY,
                           int seaLevel, int spacing) {
        int k = Math.max(1, spacing / grid.step());
        int n = Math.ceilDiv(grid.side(), k);
        int coarse = grid.step() * k;
        AtomicLongArray open = new AtomicLongArray(hi - lo + 2);
        long[] roofed = new long[1];
        int[] tops = new int[n * n];
        java.util.Arrays.fill(tops, Integer.MIN_VALUE);
        int[] floors = new int[n * n];
        java.util.Arrays.fill(floors, Integer.MIN_VALUE);
        ColumnRuns[] all = new ColumnRuns[n * n];
        AtomicLongArray solid = new AtomicLongArray(hi - lo + 2);
        AtomicLongArray middles = new AtomicLongArray(hi - lo + 1);
        java.util.concurrent.ConcurrentLinkedQueue<Integer> undersides = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicInteger solidColumns = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger lowest = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        java.util.concurrent.atomic.AtomicInteger floating = new java.util.concurrent.atomic.AtomicInteger();
        int[] align = {0};
        List<CompletableFuture<Integer>> work = new ArrayList<>();
        for (int tz = 0; tz < n; tz += TILE) {
            int z0 = tz;
            work.add(CompletableFuture.supplyAsync(() -> {
                int count = 0;
                ColumnModel model = models.get();
                align[0] = model.align();
                for (int tx = 0; tx < n; tx += TILE) {
                    int nx = Math.min(TILE, n - tx);
                    int nz = Math.min(TILE, n - z0);
                    ColumnRuns[] scanned = model.scan(grid.x(tx * k), grid.z(z0 * k), coarse, nx, nz);
                    for (int c = 0; c < scanned.length; c++) {
                        ColumnRuns r = scanned[c];
                        all[(z0 + c / nx) * n + tx + c % nx] = r;
                        if (r.count() > 0) {
                            int at = (z0 + c / nx) * n + tx + c % nx;
                            tops[at] = r.top(0);
                            int sr = r.surfaceRun(hi, minY);
                            floors[at] = sr == -2 ? r.top(0) : sr >= 0 ? r.top(sr) : Integer.MIN_VALUE;
                            solidColumns.incrementAndGet();
                            for (int i = 0; i < r.count(); i++) {
                                solid.incrementAndGet(r.bottom(i) - lo);
                                solid.decrementAndGet(r.top(i) - lo + 1);
                                middles.incrementAndGet((r.bottom(i) + r.top(i)) / 2 - lo);
                            }
                            if (r.roofed(hi)) undersides.add(r.bottom(0));
                            int bottom = r.bottom(r.count() - 1);
                            lowest.accumulateAndGet(bottom, Math::min);
                            if (bottom > lo) floating.incrementAndGet();
                        }
                        if (!r.roofed(hi)) continue;
                        count++;
                        for (int i = 0; i + 1 < r.count(); i++) {
                            int from = r.top(i + 1) + 1;
                            int to = r.bottom(i) - 1;
                            if (from > to) continue;
                            open.incrementAndGet(from - lo);
                            open.decrementAndGet(to - lo + 1);
                        }
                    }
                }
                return count;
            }, pool));
        }
        for (CompletableFuture<Integer> f : work) roofed[0] += f.join();
        String mode = "off".equals(System.getProperty(WINDOW_PROPERTY)) ? ScanWindow.FULL
                : ScanWindow.mode((long) n * n, roofed[0], floating.get());
        long[] middleCounts = new long[hi - lo + 1];
        for (int y = 0; y < middleCounts.length; y++) middleCounts[y] = middles.get(y);
        long[] histogram = new long[hi - lo + 1];
        long running = 0;
        for (int y = 0; y < histogram.length; y++) {
            running += solid.get(y);
            histogram[y] = running;
        }
        List<Integer> floorList = new ArrayList<>();
        for (int f : floors) if (f != Integer.MIN_VALUE) floorList.add(f);
        List<Integer> under = new ArrayList<>(undersides);
        Collections.sort(floorList);
        Collections.sort(under);
        int thickness = under.isEmpty() ? 0 : hi - under.get((int) Math.floor((under.size() - 1) * (1 - ROOF_QUANTILE))) + 1;
        int floorTop = floorList.isEmpty() ? lo : floorList.get((int) Math.floor((floorList.size() - 1) * FLOOR_QUANTILE));
        int start = Math.clamp(Math.max(hi - thickness - ROOF_MARGIN, floorTop + ROOF_MARGIN), lo, hi - 1);
        double columns = Math.max(1, (long) n * n);
        SliceChoice.Profile profile = new SliceChoice.Profile(lo, hi, seaLevel, n * n, solidColumns.get(),
                floorList.isEmpty() ? null : floorList.get(floorList.size() / 2), roofed[0] / columns, floating.get() / columns,
                under.isEmpty() ? null : under.get(under.size() / 2), histogram, middleCounts);
        ScanWindow window = new ScanWindow(mode, lo, hi, align[0], grid.x(0), grid.z(0), coarse, n, tops, lowest.get(), floors, start);
        if (roofed[0] == 0) {
            return new Prescan((lo + hi) / 2, "the middle of the noise: no roofed column on the coarse grid", n * n, 0, floating.get(),
                    window, floors, profile, 0, 0);
        }
        long run = 0;
        long best = -1;
        int bestY = (lo + hi) / 2;
        for (int y = lo; y <= hi; y++) {
            run += open.get(y - lo);
            if (run > best) {
                best = run;
                bestY = y;
            }
        }
        return new Prescan(bestY, "the height open in the most roofed columns of a grid every " + coarse + " blocks",
                n * n, (int) roofed[0], floating.get(), window, floors, profile, thickness, floorAgreement(all, start, lo, hi, minY));
    }

    /** Agreement with {@link Sampler#sample} on the comparison grid. */
    record Comparison(int columns, double heightsWithinOne, double heightsExact, double biomesEqual, double fluidsEqual,
                      long referenceMillis, long referenceRate, List<String> examples) {
        static final Comparison NONE = new Comparison(0, 0, 0, 0, 0, 0, 0, List.of());
    }

    static Comparison compare(Sampler sampler, RenderGrid grid, ColumnSamples samples, ExecutorService pool) {
        int k = Math.max(1, COMPARE_SPACING / grid.step());
        List<int[]> pixels = new ArrayList<>();
        for (int py = 0; py < grid.side(); py += k) {
            for (int px = 0; px < grid.side(); px += k) pixels.add(new int[] {px, py});
        }
        int parts = 64;
        long[][] tally = new long[parts][4];
        List<List<String>> examples = new ArrayList<>();
        for (int p = 0; p < parts; p++) examples.add(new ArrayList<>());
        long t = System.nanoTime();
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (int p = 0; p < parts; p++) {
            int part = p;
            work.add(CompletableFuture.runAsync(() -> {
                for (int i = part; i < pixels.size(); i += parts) {
                    int px = pixels.get(i)[0];
                    int py = pixels.get(i)[1];
                    Sampler.Column ref = sampler.sample(grid.x(px), grid.z(py));
                    int dh = Math.abs(ref.height() - samples.height(px, py));
                    boolean biome = ref.biomeId().equals(samples.biome(px, py));
                    if (dh <= 1) tally[part][0]++;
                    if (dh == 0) tally[part][1]++;
                    if (biome) tally[part][2]++;
                    if (ref.fluid() == samples.fluid(px, py)) tally[part][3]++;
                    if ((dh > 1 || !biome) && examples.get(part).size() < 4) {
                        examples.get(part).add(grid.x(px) + "," + grid.z(py) + ": height " + samples.height(px, py) + " against "
                                + ref.height() + ", biome " + samples.biome(px, py) + " against " + ref.biomeId());
                    }
                }
            }, pool));
        }
        CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join();
        long ms = Math.max(1, (System.nanoTime() - t) / 1_000_000);
        long[] sum = new long[4];
        for (long[] a : tally) for (int i = 0; i < 4; i++) sum[i] += a[i];
        int n = pixels.size();
        List<String> shown = new ArrayList<>();
        for (List<String> e : examples) for (String s : e) if (shown.size() < 16) shown.add(s);
        return new Comparison(n, share(sum[0], n), share(sum[1], n), share(sum[2], n), share(sum[3], n), ms, n * 1000L / ms, shown);
    }

    private static double share(long k, int n) {
        return n == 0 ? 0 : Math.round(k * 10000.0 / n) / 10000.0;
    }

    private static JsonObject fastBlock(RenderGrid grid, int threads, int lo, int hi, int[] cell, String mode, long[] counts,
                                        FastPixels pixels,
                                        boolean roofLayers, int roofed, List<Integer> tiers, Prescan pre, ScanWindow scanWindow,
                                        Palette palette,
                                        List<FastLayers.Footprint> footprints, String structureError, Comparison c, long rate,
                                        double speedup, Path runsFile) {
        JsonObject fast = new JsonObject();
        fast.addProperty("stride", grid.step());
        fast.addProperty("threads", threads);
        fast.addProperty("tile", TILE);
        fast.addProperty("columnsPerSecond", rate);
        fast.addProperty("referenceColumnsPerSecond", c.referenceRate());
        fast.addProperty("speedup", Math.round(speedup * 100) / 100.0);
        JsonArray noise = new JsonArray();
        noise.add(lo);
        noise.add(hi);
        fast.add("noiseRange", noise);
        JsonObject scout = new JsonObject();
        scout.addProperty("cellXz", cell == null ? 0 : cell[0]);
        scout.addProperty("cellY", cell == null ? 0 : cell[1]);
        scout.addProperty("mode", mode);
        long columns = Math.max(1, counts[2]);
        if (TileSampler.LATTICE.equals(mode)) {
            scout.addProperty("rule", "every block of a tile in one batch: each interpolated term's input sampled on the cell corners "
                    + "under the tile's columns and filled as vanilla interpolates it, the terms outside it applied per block; "
                    + "the density each block of a noise column gets");
        } else {
            scout.addProperty("rule", "the final density on rows one interpolation cell apart (8 blocks when it is not interpolated); "
                    + "each cell whose rows differ in sign sampled block by block; a layer thinner than a cell between two rows is missed");
            scout.addProperty("scoutPointsPerColumn", Math.round(counts[0] * 100.0 / columns) / 100.0);
            scout.addProperty("crossingsPerColumn", Math.round(counts[1] * 100.0 / columns) / 100.0);
        }
        scout.addProperty("evaluationsPerColumn", Math.round(counts[0] * 100.0 / columns) / 100.0);
        scout.addProperty("evaluationsRule", "density evaluations below the outermost interpolation per column: scout points, or corners "
                + "summed over the interpolated terms; a block refined in refine mode costs its cell's two corner rows more");
        fast.add("scout", scout);
        JsonObject runs = new JsonObject();
        int max = 0;
        long total = 0;
        int truncatedAtCap = 0;
        for (byte r : pixels.runs) {
            int v = r & 0xFF;
            max = Math.max(max, v);
            total += v;
            if (v >= ColumnRuns.MAX_RUNS) truncatedAtCap++;
        }
        runs.addProperty("cap", ColumnRuns.MAX_RUNS);
        runs.addProperty("max", max);
        runs.addProperty("mean", Math.round(total * 100.0 / pixels.runs.length) / 100.0);
        runs.addProperty("atCap", truncatedAtCap);
        if (runsFile != null) runs.addProperty("file", runsFile.getFileName().toString());
        JsonObject window = new JsonObject();
        window.addProperty("mode", scanWindow.mode());
        window.addProperty("rule", scanWindow.rule());
        window.addProperty("tiles", scanWindow.tiles());
        window.addProperty("tilesScannedAgain", scanWindow.rescanned());
        window.addProperty("meanBlocks", Math.round(scanWindow.meanRows() * 10) / 10.0);
        runs.add("window", window);
        runs.addProperty("format", RUNS_FORMAT);
        fast.add("runs", runs);
        fast.addProperty("roofedShare", Math.round(roofed * 10000.0 / pixels.runs.length) / 10000.0);
        fast.addProperty("roofLayers", roofLayers);
        JsonObject cut = new JsonObject();
        cut.addProperty("y", pre.cutY());
        cut.addProperty("rule", pre.rule());
        cut.addProperty("prescanColumns", pre.columns());
        cut.addProperty("prescanRoofed", pre.roofed());
        cut.addProperty("prescanFloating", pre.floating());
        cut.addProperty("roofThickness", pre.roofThickness());
        cut.addProperty("roofThicknessRule", "blocks from the noise top to the roof's underside in roofed pre-scan columns, at the "
                + ROOF_QUANTILE + " quantile; a floor window's top is " + ROOF_MARGIN + " blocks under it, or " + ROOF_MARGIN
                + " over the floors' " + FLOOR_QUANTILE + " quantile when higher");
        cut.addProperty("floorWindowTop", scanWindow.start());
        cut.addProperty("floorAgreement", Math.round(pre.floorAgreement() * 10000) / 10000.0);
        fast.add("cut", cut);
        JsonArray tierList = new JsonArray();
        tiers.forEach(tierList::add);
        fast.add("tiers", tierList);
        fast.addProperty("tierRule", "tierN shows run N+1 from the top, drawn when " + LAYER_SHARE + " of columns have it");
        fast.addProperty("fluids", FLUIDS);
        fast.addProperty("surfaceBlocks", "the level's compiled material rule at each run's top, and at a roof's underside");
        JsonObject structures = new JsonObject();
        structures.addProperty("footprints", footprints.size());
        structures.addProperty("rule", "starts from the level's placement state with its structure control (Sampler.starts), at most "
                + StructureStarts.CAP + " of each structure, outlined by the start's bounding box");
        if (structureError != null) structures.addProperty("error", structureError);
        JsonObject legend = new JsonObject();
        synchronized (footprints) {
            for (FastLayers.Footprint f : footprints) {
                legend.addProperty(f.structure(), com.customdimensions.core.render.BiomePalette.hex(FastLayers.colour(f.structure())));
            }
        }
        structures.add("legend", legend);
        fast.add("structures", structures);
        JsonObject comparison = new JsonObject();
        comparison.addProperty("spacing", COMPARE_SPACING);
        comparison.addProperty("columns", c.columns());
        comparison.addProperty("heightsWithinOne", c.heightsWithinOne());
        comparison.addProperty("heightsExact", c.heightsExact());
        comparison.addProperty("biomesEqual", c.biomesEqual());
        comparison.addProperty("fluidsEqual", c.fluidsEqual());
        comparison.addProperty("referenceMillis", c.referenceMillis());
        JsonArray examples = new JsonArray();
        c.examples().forEach(examples::add);
        comparison.add("examples", examples);
        fast.add("comparison", comparison);
        fast.add("palette", palette.json());
        return fast;
    }

    /** Block states seen, by first sight; index fits a u16. */
    static final class Palette {
        private final Map<BlockState, Short> index = new ConcurrentHashMap<>();
        private final List<BlockState> states = new ArrayList<>();

        short index(BlockState state) {
            Short i = index.get(state);
            if (i != null) return i;
            synchronized (this) {
                return index.computeIfAbsent(state, s -> {
                    if (states.size() >= 0xFFFF) throw new IllegalStateException("more than 65535 block states");
                    states.add(s);
                    return (short) (states.size() - 1);
                });
            }
        }

        synchronized List<BlockState> states() {
            return List.copyOf(states);
        }

        synchronized int[] colours() {
            int[] out = new int[states.size()];
            for (int i = 0; i < out.length; i++) out[i] = states.get(i).getMapColor(null, null).col;
            return out;
        }

        synchronized JsonArray json() {
            JsonArray out = new JsonArray();
            for (BlockState s : states) {
                JsonObject o = new JsonObject();
                o.addProperty("block", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
                o.addProperty("state", s.toString());
                o.addProperty("mapColour", com.customdimensions.core.render.BiomePalette.hex(s.getMapColor(null, null).col));
                out.add(o);
            }
            return out;
        }
    }

    /**
     * Runs from the generator's own noise columns, for a generator that is
     * not noise based: the column's blocks are its faces and fluids.
     */
    static final class NoiseColumns implements ColumnModel {
        private final Sampler sampler;
        private final Map<Long, NoiseColumn> columns = new java.util.HashMap<>();
        private long count;

        NoiseColumns(Sampler sampler) {
            this.sampler = sampler;
        }

        @Override
        public ColumnRuns[] scan(int x0, int z0, int step, int nx, int nz) {
            columns.clear();
            int lo = sampler.noiseBottom();
            int hi = sampler.noiseTop();
            boolean[] solid = new boolean[hi - lo + 1];
            ColumnRuns[] out = new ColumnRuns[nx * nz];
            for (int j = 0; j < nz; j++) {
                for (int i = 0; i < nx; i++) {
                    int x = x0 + i * step;
                    int z = z0 + j * step;
                    NoiseColumn column = sampler.noiseColumn(x, z);
                    columns.put(key(x, z), column);
                    for (int y = lo; y <= hi; y++) solid[y - lo] = Sampler.solid(column.getBlock(y));
                    out[i + nx * j] = ColumnRuns.of(solid, lo, solid.length);
                }
            }
            count += (long) nx * nz;
            return out;
        }

        private static long key(int x, int z) {
            return (long) x << 32 | (z & 0xFFFFFFFFL);
        }

        @Override
        public Holder<Biome> biome(int x, int y, int z) {
            return sampler.biome(x, y, z);
        }

        @Override
        public RunDress.Materials<BlockState> materials() {
            return (x, y, z, above, below, water) -> columns.get(key(x, z)).getBlock(y);
        }

        @Override
        public RunDress.Fluids<BlockState> fluids() {
            return (x, y, z) -> {
                NoiseColumn column = columns.get(key(x, z));
                BlockState state = column.getBlock(y);
                if (state.getFluidState().isEmpty()) return null;
                int level = y;
                while (level <= sampler.noiseTop() && !column.getBlock(level).getFluidState().isEmpty()) level++;
                return new RunDress.Fluid<>(state, level);
            };
        }

        @Override
        public BlockState defaultBlock() {
            return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        }

        @Override
        public int[] cell() {
            return new int[] {0, 0};
        }

        @Override
        public long[] counts() {
            return new long[] {0, 0, count};
        }

        @Override
        public void close() {}
    }
}
