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
 * applied, so both sides see the noise settings' own fluid picker. With
 * {@code -Dcustomdimensions.fastbench.lod=<detail>[,...]} it measures each
 * case's LOD source instead ({@link LodBench}); the case's radius is its reach
 * and its step and threads are unused.
 */
public final class FastBench {
    public static final String PROPERTY = "customdimensions.fastbench";
    public static final String OUT_PROPERTY = "customdimensions.fastbench.out";
    /** Times each case is rendered; the last is reported, the first warms the JIT. */
    public static final String REPEAT_PROPERTY = "customdimensions.fastbench.repeat";

    /** One dimension to render. */
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
            out.add(new Case(p[0], Long.parseLong(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])));
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
        Files.write(out.resolve("summary.txt"), summary);
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
