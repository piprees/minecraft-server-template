package com.customdimensions.server.render.fast;

import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.PackInstaller;
import com.customdimensions.server.level.RuntimeRegistry;
import com.customdimensions.server.render.Sampler;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.validation.DirectoryValidator;

/**
 * A headless benchmark of {@link FastRender} against {@link Sampler#sample},
 * for the dev run {@code runFastBench}. Asked for by
 * {@code -Dcustomdimensions.fastbench=<slug>:<seed>:<radius>:<step>:<threads>[,...]},
 * it runs at mod initialisation, after the pack is compiled: it loads the world
 * and dimension registries from vanilla's data, the mods' data and the compiled
 * pack as a start loads them, binds the runtime registry, builds each named
 * dimension's generator under its seed, renders it as {@code /customdim
 * fastrender} does (comparison included) into
 * {@code -Dcustomdimensions.fastbench.out}, and exits. No server, level,
 * world or network. The structure footprints need a server's templates and
 * are left out; the worldgen runtime (lava levels, basins, dry zones) is not
 * applied, so both sides see the noise settings' own fluid picker. A case
 * whose step is {@code wave} runs {@link FastWave} instead: a warm-up of pass
 * 1, pass 1 without slices, the wave with its slices, and on a roofed world
 * pass 1 scanned from the floor ({@link ScanWindow#FLOOR}) against whole
 * columns; {@value #FINAL_SIDE_PROPERTY} sets the last pass's side. With
 * {@code -Dcustomdimensions.fastbench.lod=<detail>[,...]} it measures each
 * case's LOD source instead ({@link LodBench}); the case's radius is its reach
 * and its step and threads are unused.
 */
public final class FastBench {
    public static final String PROPERTY = "customdimensions.fastbench";
    public static final String OUT_PROPERTY = "customdimensions.fastbench.out";
    /** Times each case is rendered; the last is reported, the first warms the JIT. */
    public static final String REPEAT_PROPERTY = "customdimensions.fastbench.repeat";
    public static final String FINAL_SIDE_PROPERTY = "customdimensions.fastbench.finalside";
    /** The step a case gives to ask for a wave. */
    public static final String WAVE = "wave";
    /** The step that runs the web map's generated export ({@link MapExport}) instead, twice: the second finds its fingerprint and skips. */
    public static final String MAP = "map";
    /** With {@link #MAP}: a folder holding {@code <slug>/region/}, whose saved chunks the explored layer is drawn from. */
    public static final String REGIONS_PROPERTY = "customdimensions.fastbench.regions";

    /** One dimension to render; a step of 0 is a wave. */
    public record Case(String slug, long seed, int radius, int step, int threads) {}

    private FastBench() {}

    public static boolean requested() {
        String spec = System.getProperty(PROPERTY);
        return spec != null && !spec.isBlank();
    }

    /**
     * Runs the benchmark once the game's main thread returns (the run passes
     * {@code --initSettings}, so it returns right after bootstrap, with the
     * built-in registries frozen), then exits the JVM: 0 when every case rendered.
     */
    public static void startAfterMain() {
        Thread main = Thread.currentThread();
        Thread bench = new Thread(() -> {
            try {
                main.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            runAndExit();
        }, "fast-bench");
        bench.setDaemon(false);
        bench.start();
    }

    static void runAndExit() {
        int code = 0;
        try {
            run(cases(System.getProperty(PROPERTY)), Path.of(System.getProperty(OUT_PROPERTY, "fastbench")),
                    Integer.getInteger(REPEAT_PROPERTY, 2));
        } catch (Throwable t) {
            CustomDimensions.LOG.error("fast bench failed", t);
            code = 1;
        }
        System.exit(code);
    }

    static List<Case> cases(String spec) {
        List<Case> out = new ArrayList<>();
        for (String one : spec.split(",")) {
            String[] p = one.trim().split(":");
            if (p.length != 5) throw new IllegalArgumentException("a case is slug:seed:radius:step:threads, not " + one);
            int step = WAVE.equals(p[3]) ? 0 : MAP.equals(p[3]) ? -1 : Integer.parseInt(p[3]);
            out.add(new Case(p[0], Long.parseLong(p[1]), Integer.parseInt(p[2]), step, Integer.parseInt(p[4])));
        }
        return out;
    }

    static void run(List<Case> cases, Path out, int repeat) throws Exception {
        Path game = FabricLoader.getInstance().getGameDir();
        Path datapacks = game.resolve(PackInstaller.levelName(game.resolve("server.properties"))).resolve("datapacks");
        PackRepository packs = ServerPacksSource.createPackRepository(datapacks, new DirectoryValidator(path -> true));
        MinecraftServer.configurePackRepository(packs, WorldDataConfiguration.DEFAULT, true, false);
        CustomDimensions.LOG.info("fast bench: packs {}", packs.getSelectedIds());
        Files.createDirectories(out);
        List<String> summary = new ArrayList<>();
        try (CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected())) {
            long t = System.nanoTime();
            RegistryAccess access = load(resources);
            RuntimeRegistry.bind(access, resources);
            CustomDimensions.LOG.info("fast bench: registries loaded and bound in {} ms", (System.nanoTime() - t) / 1_000_000);
            for (Case c : cases) {
                LevelStem stem = stem(access, resources, c.slug());
                var type = stem.type().value();
                String lod = System.getProperty(LodBench.PROPERTY, "");
                if (!lod.isEmpty()) {
                    Sampler sampler = Sampler.of(c.slug(), stem.generator(), type.minY(), type.height(), c.seed(), "argument", access);
                    List<String> lines = LodBench.run(c.slug(), sampler, c.radius(), type.hasSkyLight(), lod);
                    lines.forEach(l -> CustomDimensions.LOG.info("fast bench: {}", l));
                    summary.addAll(lines);
                    continue;
                }
                ExecutorService pool = Executors.newFixedThreadPool(c.threads());
                try {
                    if (c.step() == -1) {
                        Sampler sampler = Sampler.of(c.slug(), stem.generator(), type.minY(), type.height(), c.seed(), "argument", access);
                        Path dir = com.customdimensions.server.map.MapSite.dimension(out.resolve("map"), c.slug());
                        for (int i = 1; i <= 2; i++) {
                            MapExport.Result r = MapExport.run(sampler, c.radius(), pool, c.threads(), dir, null, false);
                            String line = String.format(Locale.ROOT, "%s map %d: radius %d t%d %s in %d ms, %d columns, %d tiles, %d bytes, "
                                            + "%d layers %s, %d markers, timings %s", c.slug(), i, c.radius(), c.threads(),
                                    r.skipped() ? "skipped (fingerprint)" : "exported", r.millis(), r.columns(), r.tiles(), r.bytes(),
                                    r.layers().size(), r.layers().stream().map(MapExport.Layer::id).toList(), r.markers(), r.timings());
                            CustomDimensions.LOG.info("fast bench: {}", line);
                            summary.add(line);
                        }
                        String regions = System.getProperty(REGIONS_PROPERTY, "");
                        Path regionDir = regions.isBlank() ? null : Path.of(regions).resolve(c.slug()).resolve("region");
                        if (regionDir != null && Files.isDirectory(regionDir)) {
                            var colours = new com.customdimensions.server.map.MapColours(access);
                            for (int i = 1; i <= 2; i++) {
                                var e = com.customdimensions.server.map.ExploredExport.run(c.slug(), null, regionDir, colours, c.radius(), dir,
                                        pool, false);
                                String line = String.format(Locale.ROOT, "%s explored %d: %s, %d regions (%d read), %d chunks (%d unfinished, "
                                                + "%d unreadable), read %d ms at %d columns/s, %d tiles, %d bytes, %d ms", c.slug(), i,
                                        e.skipped() ? "skipped (no region changed)" : "exported", e.regions(), e.regionsRead(), e.chunks(),
                                        e.chunksUnfinished(), e.chunksUnreadable(), e.readMillis(), e.columnsPerSecond(), e.tiles(),
                                        e.bytes(), e.millis());
                                CustomDimensions.LOG.info("fast bench: {}", line);
                                summary.add(line);
                            }
                        }
                        continue;
                    }
                    if (c.step() == 0) {
                        Sampler sampler = Sampler.of(c.slug(), stem.generator(), type.minY(), type.height(), c.seed(), "argument", access);
                        List<String> lines = wave(c, sampler, pool, out);
                        lines.forEach(l -> CustomDimensions.LOG.info("fast bench: {}", l));
                        summary.addAll(lines);
                        continue;
                    }
                    for (int i = 1; i <= repeat; i++) {
                        Sampler sampler = Sampler.of(c.slug(), stem.generator(), type.minY(), type.height(), c.seed(), "argument", access);
                        RenderGrid grid = RenderGrid.wide(0, 0, c.radius(), c.step());
                        long started = System.nanoTime();
                        FastRender.Result r = FastRender.run(sampler, grid, pool, c.threads(), out.resolve(c.slug()), null, p -> {}, started);
                        long dense = referenceRate(sampler, grid, pool, c.threads());
                        var side = JsonParser.parseString(Files.readString(r.sidecar())).getAsJsonObject();
                        var fast = side.getAsJsonObject("fast");
                        var timings = side.getAsJsonObject("timings");
                        String line = String.format(Locale.ROOT, "%s pass %d: %d columns s%d t%d %s: %d col/s, reference %d col/s, %.2fx; "
                                        + "dense reference %d col/s, %.2fx; %s evaluations/column; scan %s ms, dress %s ms (thread); "
                                        + "heights within 1 %.4f, biomes %.4f, fluids %s -> %s", c.slug(), i,
                                grid.columns(), c.step(), c.threads(), fast.getAsJsonObject("scout").get("mode").getAsString(),
                                r.columnsPerSecond(), Math.round(r.columnsPerSecond() / Math.max(r.speedup(), 1e-9)), r.speedup(), dense,
                                (double) r.columnsPerSecond() / dense,
                                fast.getAsJsonObject("scout").get("evaluationsPerColumn").getAsString(),
                                timings.get("sampleScanThreadMillis").getAsString(), timings.get("sampleDressThreadMillis").getAsString(),
                                r.heightsWithinOne(), r.biomesEqual(),
                                fast.getAsJsonObject("comparison").get("fluidsEqual").getAsString(), r.sidecar());
                        CustomDimensions.LOG.info("fast bench: {}", line);
                        if (i == repeat) {
                            summary.add(line);
                            Path kept = out.resolve(c.slug() + "-s" + c.step() + "-t" + c.threads() + ".json");
                            Files.copy(r.sidecar(), kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
        }
        if (cases.stream().anyMatch(c -> c.step() == -1)) site(out.resolve("map"));
        Files.write(out.resolve("summary.txt"), summary);
    }

    /**
     * The map bench's site: the viewer shell and a manifest of every
     * definition under {@code customdimensions.definitions}, so the viewer can
     * be served from {@code root} by any static server.
     */
    static void site(Path root) throws java.io.IOException {
        List<com.customdimensions.server.map.MapSite.Entry> entries = new ArrayList<>();
        String defs = System.getProperty("customdimensions.definitions", "");
        if (!defs.isBlank() && Files.isDirectory(Path.of(defs))) {
            try (var files = Files.list(Path.of(defs))) {
                for (Path f : files.sorted().toList()) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".json") || name.equals("settings.json")) continue;
                    entries.add(new com.customdimensions.server.map.MapSite.Entry(name.substring(0, name.length() - 5), null, null));
                }
            }
        }
        com.customdimensions.server.map.MapSite.shell(root);
        com.customdimensions.server.map.MapSite.manifest(root, entries);
    }

    /** The wave bench of one case: lines for the summary. */
    static List<String> wave(Case c, Sampler sampler, ExecutorService pool, Path out) throws java.io.IOException {
        List<String> lines = new ArrayList<>();
        var plan = com.customdimensions.core.render.WavePlan.of(c.radius(),
                Integer.getInteger(FINAL_SIDE_PROPERTY, com.customdimensions.core.render.WavePlan.FINAL_SIDE));
        Path scratch = out.resolve(c.slug()).resolve("scratch");
        Path dir = out.resolve(c.slug()).resolve("wave");
        var first = plan.passes().getFirst();
        RenderGrid g1 = RenderGrid.wide(0, 0, plan.radius(), first.stride());
        long t = System.nanoTime();
        FastRender.render(sampler, g1, pool, c.threads(), scratch, null, p -> {}, t,
                new FastRender.Options("warm", false, false, false, List.of(), null, false));
        lines.add(String.format(Locale.ROOT, "%s wave warm-up pass 1 (s%d, %d px): %d ms", c.slug(), first.stride(), first.side(),
                (System.nanoTime() - t) / 1_000_000));
        FastRender.Prescan pre1 = FastWave.prescan(sampler, g1, pool, FastWave.prescanSpacing(first));
        t = System.nanoTime();
        FastRender.Rendered bare = FastRender.render(sampler, g1, pool, c.threads(), scratch, null, p -> {}, t,
                new FastRender.Options("bare", false, false, false, List.of(), pre1, false));
        int defaultBatch = FastRender.batch(first.stride());
        for (int batch : new int[] {8, 16, 32}) {
            System.setProperty(FastRender.BATCH_PROPERTY, String.valueOf(batch));
            try {
                long tb = System.nanoTime();
                FastRender.Rendered b = FastRender.render(sampler, g1, pool, c.threads(), scratch, null, p -> {}, tb,
                        new FastRender.Options("batch" + batch, false, false, false, List.of(), pre1, false));
                lines.add(String.format(Locale.ROOT, "%s pass 1 without slices in batches of %d: terrain %d ms against %d ms in the "
                                + "default %d; mean window %.1f blocks", c.slug(), batch, b.result().sampleMillis(), bare.result().sampleMillis(),
                        defaultBatch, floorMeanRows(b)));
            } finally {
                System.clearProperty(FastRender.BATCH_PROPERTY);
            }
        }
        lines.add(String.format(Locale.ROOT, "%s pass 1 without slices: terrain %d ms, %d ms in all, %d col/s, window %s, mean %.1f "
                        + "blocks; pre-scan every %d blocks", c.slug(), bare.result().sampleMillis(), (System.nanoTime() - t) / 1_000_000,
                bare.result().columnsPerSecond(), bare.windowMode(), floorMeanRows(bare), FastWave.prescanSpacing(first)));
        FastWave.Result w = FastWave.run(sampler, plan, pool, c.threads(), dir, null, p -> {}, null, FastWave.floorFirstDefault());
        StringBuilder slices = new StringBuilder();
        for (var s : w.slices()) slices.append(s.name()).append('@').append(s.y()).append(' ');
        lines.add(c.slug() + " wave slices: " + slices.toString().trim() + " -> " + w.json());
        for (FastWave.Pass p : w.passes()) {
            var side = JsonParser.parseString(Files.readString(p.sidecar())).getAsJsonObject();
            var timings = side.getAsJsonObject("timings");
            lines.add(String.format(Locale.ROOT, "%s wave pass %d s%d %d px: %d ms (terrain %d ms, slice rows %s ms, slice columns %s ms "
                            + "thread, images %s ms), %d ms from the start, %d col/s; sheet %s", c.slug(), p.pass().index(),
                    p.pass().stride(), p.pass().side(), p.millis(), p.sampleMillis(), timings.get("slicesRowThreadMillis").getAsString(),
                    timings.get("slicesColumnThreadMillis").getAsString(), timings.get("images").getAsString(), p.cumulativeMillis(),
                    p.columnsPerSecond(), p.sheet()));
        }
        lines.add(String.format(Locale.ROOT, "%s wave total %d ms over %d columns", c.slug(), w.totalMillis(), plan.columns()));
        if (bare.prescan().roofedWorld()) {
            FastRender.Prescan pre = bare.prescan();
            int start = pre.window().start();
            for (int at : new int[] {start, start - 16, start - 32, start + 16}) {
                FastRender.Prescan moved = new FastRender.Prescan(pre.cutY(), pre.rule(), pre.columns(), pre.roofed(), pre.floating(),
                        pre.window().withStart(at), pre.floors(), pre.profile(), pre.roofThickness(), 1);
                t = System.nanoTime();
                FastRender.Rendered floor = FastRender.render(sampler, g1, pool, c.threads(), scratch, null, p -> {}, t,
                        new FastRender.Options("floor", false, false, false, List.of(), moved, true));
                lines.add(String.format(Locale.ROOT, "%s pass 1 floor first from %d%s: terrain %d ms against %d ms whole columns (%.2fx); "
                                + "mean window %.1f blocks, %d of %d tiles widened; %s", c.slug(), at,
                        at == start ? " (the default: roof thickness " + pre.roofThickness() + " at q" + FastRender.ROOF_QUANTILE
                                + ", margin " + FastRender.ROOF_MARGIN + ", floors' q" + FastRender.FLOOR_QUANTILE + "; pre-scan agreement "
                                + pre.floorAgreement() + ")" : "", floor.result().sampleMillis(),
                        bare.result().sampleMillis(), (double) bare.result().sampleMillis() / Math.max(1, floor.result().sampleMillis()),
                        floorMeanRows(floor), floorRescanned(floor)[0], floorRescanned(floor)[1], agreement(bare, floor)));
            }
        } else {
            lines.add(c.slug() + " pass 1 floor first: not a roofed world (pre-scan roofed " + bare.prescan().roofed() + " of "
                    + bare.prescan().columns() + "), window " + bare.windowMode());
        }
        return lines;
    }

    private static double floorMeanRows(FastRender.Rendered r) throws java.io.IOException {
        return window(r).get("meanBlocks").getAsDouble();
    }

    private static long[] floorRescanned(FastRender.Rendered r) throws java.io.IOException {
        var w = window(r);
        return new long[] {w.get("tilesScannedAgain").getAsLong(), w.get("tiles").getAsLong()};
    }

    private static com.google.gson.JsonObject window(FastRender.Rendered r) throws java.io.IOException {
        return JsonParser.parseString(Files.readString(r.result().sidecar())).getAsJsonObject().getAsJsonObject("fast")
                .getAsJsonObject("runs").getAsJsonObject("window");
    }

    /** Floor heights of a floor-first render against whole columns, pixel by pixel, with where they part. */
    static String agreement(FastRender.Rendered whole, FastRender.Rendered floor) {
        var a = whole.samples();
        var b = floor.samples();
        int side = a.grid().side();
        long n = 0;
        long within = 0;
        long exact = 0;
        long lower = 0;
        long higher = 0;
        long lost = 0;
        long biomes = 0;
        List<String> examples = new ArrayList<>();
        for (int py = 0; py < side; py++) {
            for (int px = 0; px < side; px++) {
                n++;
                int ha = a.height(px, py);
                int hb = b.height(px, py);
                int d = hb - ha;
                if (Math.abs(d) <= 1) within++;
                if (d == 0) exact++;
                if (a.biome(px, py).equals(b.biome(px, py))) biomes++;
                if (Math.abs(d) <= 1) continue;
                if (b.empty(px, py) && !a.empty(px, py)) lost++;
                else if (d < 0) lower++;
                else higher++;
                if (examples.size() < 6) examples.add(a.grid().x(px) + "," + a.grid().z(py) + " " + hb + " vs " + ha);
            }
        }
        return String.format(Locale.ROOT, "floor heights within 1 %.4f, exact %.4f, biomes %.4f; off by more: %d lower, %d higher, %d "
                + "no floor found (of %d); e.g. %s", within / (double) n, exact / (double) n, biomes / (double) n, lower, higher, lost, n,
                examples);
    }

    /** Columns per side of the dense reference grid. */
    static final int DENSE_SIDE = 256;

    /**
     * {@link Sampler#sample}'s rate on {@code threads} workers over a
     * {@value #DENSE_SIDE}² subset of {@code grid}'s columns spread evenly
     * over it: a steadier figure than the sidecar's sparse comparison.
     */
    static long referenceRate(Sampler sampler, RenderGrid grid, ExecutorService pool, int threads) {
        int k = Math.max(1, grid.side() / DENSE_SIDE);
        int n = Math.ceilDiv(grid.side(), k);
        long t = System.nanoTime();
        List<java.util.concurrent.Future<?>> work = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            int first = w;
            work.add(pool.submit(() -> {
                for (int j = first; j < n; j += threads) {
                    for (int i = 0; i < n; i++) sampler.sample(grid.x(i * k), grid.z(j * k));
                }
            }));
        }
        for (var f : work) {
            try {
                f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return (long) n * n * 1_000_000_000L / Math.max(1, System.nanoTime() - t);
    }

    /** The world and dimension registries from {@code resources}, as a start loads them, with every tag bound. */
    static RegistryAccess load(ResourceManager resources) {
        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        List<Registry.PendingTags<?>> tags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
        RegistryAccess.Frozen context = layers.getAccessForLoading(RegistryLayer.WORLD);
        List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(context, tags);
        java.util.concurrent.Executor direct = Runnable::run;
        RegistryAccess.Frozen world = RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLD_REGISTRIES, direct).join();
        List<HolderLookup.RegistryLookup<?>> dimensionContext = Stream.concat(lookups.stream(), world.listRegistries()).toList();
        RegistryAccess.Frozen dimensions = RegistryDataLoader.load(resources, dimensionContext, RegistryDataLoader.DIMENSION_REGISTRIES,
                direct).join();
        tags.forEach(Registry.PendingTags::apply);
        return layers.replaceFrom(RegistryLayer.WORLD, world, dimensions).compositeAccess();
    }

    /** A base world's stem from the dimension registry, a lazy dimension's from the pack. */
    static LevelStem stem(RegistryAccess access, ResourceManager resources, String slug) throws Exception {
        String base = LevelIndex.baseWorldKeys(LevelIndex.DEFAULT_NAMESPACE).get(slug);
        if (base != null) {
            return access.lookupOrThrow(Registries.LEVEL_STEM).get(ResourceKey.create(Registries.LEVEL_STEM, Identifier.parse(base))).map(net.minecraft.core.Holder::value)
                    .orElseThrow(() -> new IllegalArgumentException("no level stem " + base));
        }
        Identifier file = Identifier.fromNamespaceAndPath(DatapackCompiler.RUNTIME_NAMESPACE, DatapackCompiler.LAZY_FOLDER + "/" + slug + ".json");
        Resource resource = resources.getResource(file).orElseThrow(() -> new IllegalArgumentException("no lazy stem " + file));
        JsonElement json;
        try (Reader reader = resource.openAsReader()) {
            json = JsonParser.parseReader(reader);
        }
        return LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, access), json).getOrThrow();
    }
}
