package com.customdimensions.server.level;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.Generator;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * The generator part of the start-up self-test: one line per generator
 * variant, each read from real chunks of a shipped definition's dimension.
 * A lazy dimension is activated through the {@link LevelManager}, one at a
 * time; a fixed area is force-loaded, read from its heightmaps, blocks and
 * biomes, released, and the dimension unloaded.
 */
public final class GeneratorSelfTest {
    /** Ticks to wait for a sample area to finish generating. */
    static final int LOAD_CAP_TICKS = 6000;
    /** Each preset's own sea level, to find the definition whose {@code liquids.seaLevel} departs furthest from it. */
    static final Map<String, Integer> PRESET_SEA_LEVELS = Map.of("overworld", 63, "large_biomes", 63, "amplified", 63,
            "caves", 32, "floating_islands", -64, "nether", 32, "end", 0);

    private record Load(ServerLevel level, List<ChunkPos> chunks, CompletableFuture<List<LevelChunk>> done, int[] ticks) {}

    private final MinecraftServer server;
    private final LevelManager manager;
    private final List<String> lines = new ArrayList<>();
    private final List<Load> loading = new ArrayList<>();
    private Columns overworld;
    private Columns aviary;
    private ChunkGenerator aviaryGenerator;

    private GeneratorSelfTest(MinecraftServer server, LevelManager manager) {
        this.server = server;
        this.manager = manager;
    }

    /** Runs the checks; the future completes on the server thread with one line per variant. */
    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) {
            return CompletableFuture.completedFuture(List.of("FAIL generators: the level manager did not start"));
        }
        GeneratorSelfTest test = new GeneratorSelfTest(server, manager);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.tick());
        return test.run();
    }

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        chain = step(chain, "overworld", "overworld", spaced(), this::overworld);
        chain = step(chain, "large_biomes", "the_endless_expanse", spaced(), this::largeBiomes);
        chain = step(chain, "amplified", "the_amplified_reaches", spaced(), this::amplified);
        chain = step(chain, "caves", "the_underdark", square(-2, -2, 4), this::caves);
        chain = step(chain, "floating_islands", "the_aviary", square(-4, -4, 8), this::floatingIslands);
        chain = step(chain, "clone", "the_violet_spire", square(-4, -4, 8), this::clone);
        chain = step(chain, "nether", "the_nether", square(-4, -4, 8), this::nether);
        chain = step(chain, "end", "the_end", endArea(), this::end);
        chain = step(chain, "flat", "the_canvas", square(-2, -2, 4), this::flat);
        chain = step(chain, "checkerboard", "the_tiled_expanse", square(-4, -4, 8), this::checkerboard);
        chain = step(chain, "void", "the_red_monument", square(-4, -4, 8), this::voidWorld);
        chain = chain.thenRun(() -> lines.add("SKIP generator single_biome: no shipped definition uses it; "
                + "GeneratorVariantsTest.singleBiomeIsAFixedSourceOnOverworldTerrain covers its compile"));
        String seaSlug = mostUnusualSeaLevel();
        if (seaSlug == null) {
            chain = chain.thenRun(() -> lines.add("FAIL generator seaLevel override: no definition sets liquids.seaLevel"));
        } else {
            chain = step(chain, "seaLevel override", seaSlug, square(-2, -2, 4), this::seaLevel);
        }
        return chain.handle((v, error) -> {
            if (error != null) lines.add("FAIL generators: the check chain broke: " + cause(error));
            restorePause(pause);
            return List.copyOf(lines);
        });
    }

    /** Opens {@code slug} (a lazy one through the manager), loads {@code area}, runs {@code check}, releases both. */
    private CompletableFuture<Void> step(CompletableFuture<Void> chain, String variant, String slug, List<ChunkPos> area,
                                         Function<Columns, String> check) {
        String name = "generator " + variant + " (" + slug + ")";
        return chain.thenCompose(v -> {
            boolean lazy = manager.index().lazy().containsKey(slug);
            CompletableFuture<ServerLevel> open;
            if (lazy) {
                open = manager.activate(slug).thenApply(LevelManager.Activation::level);
            } else {
                ServerLevel level = manager.index().baseWorlds().stream().filter(w -> w.slug().equals(slug))
                        .map(w -> server.getLevel(w.key())).findFirst().orElse(null);
                open = level == null ? CompletableFuture.failedFuture(new IllegalStateException(slug + " is not loaded"))
                        : CompletableFuture.completedFuture(level);
            }
            return open.thenCompose(level -> load(level, area).handle((chunks, error) -> {
                String line;
                try {
                    if (error != null) throw new IllegalStateException(cause(error));
                    line = check.apply(new Columns(slug, level, chunks));
                } catch (RuntimeException e) {
                    line = "FAIL " + name + ": " + e;
                }
                area.forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                lines.add(line.startsWith("PASS ") || line.startsWith("FAIL ") ? line.replaceFirst("^(PASS|FAIL) ", "$1 " + name + ": ")
                        : "FAIL " + name + ": " + line);
                return null;
            })).thenCompose(x -> lazy ? manager.deactivate(slug).thenAccept(t -> {}) : CompletableFuture.completedFuture(null))
                    .exceptionally(error -> {
                        lines.add("FAIL " + name + ": " + cause(error));
                        return null;
                    });
        });
    }

    // ---- variants -------------------------------------------------------------------------------

    private String overworld(Columns c) {
        overworld = c;
        int sea = c.level.getSeaLevel();
        double within = c.share(col -> col.surface >= 50 && col.surface <= 140);
        long water = c.count(col -> col.surface == sea - 1 && col.top.getFluidState().is(FluidTags.WATER));
        boolean pass = within >= 0.9 && water > 0;
        return verdict(pass, c.area() + ", seed " + c.level.getSeed() + ": " + pct(within) + " of surface heights in 50..140 (range "
                + c.minSurface() + ".." + c.maxSurface() + "); " + water + " columns with water at sea level " + sea + " (top y "
                + (sea - 1) + "); " + c.biomes().size() + " surface biomes, " + pct(c.biomeEdgeShare()) + " of neighbouring samples differ");
    }

    /**
     * This level's noise against the plain overworld preset at its own seed and
     * biome source, so only the climate scale differs; sampled from the biome
     * source, so no chunk is loaded.
     */
    private String largeBiomes(Columns c) {
        long seed = c.level.getSeed();
        double large = biomeChangeRate(c.level, ownSettings(c.level), seed);
        double normal = biomeChangeRate(c.level, vanillaSettings(c.level, "minecraft:overworld"), seed);
        return verdict(large > 0 && large < normal * LARGE_BIOME_RATIO, "seed " + seed + ", " + BIOME_LINE_STEPS + " samples "
                + BIOME_LINE_STEP + " blocks apart: biome changes on " + pct(large) + " of steps against "
                + pct(normal) + " with the overworld preset (needs under " + Math.round(LARGE_BIOME_RATIO * 100) + "% of it); " + c.biomes().size()
                + " surface biomes in " + c.area());
    }

    static final int BIOME_LINE_STEPS = 8192;
    /** Large biomes stretch some climate parameters, not all, so changes fall well short of a quarter; equal noise gives about 1. */
    static final double LARGE_BIOME_RATIO = 0.8;
    static final int BIOME_LINE_STEP = 16;

    /** Share of steps along a line on which the surface biome changes, for one preset's noise at {@code seed}. */
    private static net.minecraft.world.level.levelgen.NoiseGeneratorSettings ownSettings(ServerLevel level) {
        return ((NoiseBasedChunkGenerator) level.getChunkSource().getGenerator()).generatorSettings().value();
    }

    private static net.minecraft.world.level.levelgen.NoiseGeneratorSettings vanillaSettings(ServerLevel level, String id) {
        return level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
                .getValue(net.minecraft.resources.Identifier.parse(id));
    }

    private static double biomeChangeRate(ServerLevel level, net.minecraft.world.level.levelgen.NoiseGeneratorSettings settings,
                                          long seed) {
        net.minecraft.core.RegistryAccess registries = level.registryAccess();
        net.minecraft.world.level.levelgen.RandomState state = net.minecraft.world.level.levelgen.RandomState.create(
                registries.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE), seed, settings);
        net.minecraft.world.level.biome.BiomeResolver resolver = level.getChunkSource().getGenerator().getBiomeSource()
                .createUncachedResolver(state);
        int changes = 0;
        Holder<Biome> last = null;
        for (int i = 0; i < BIOME_LINE_STEPS; i++) {
            Holder<Biome> biome = resolver.getNoiseBiome(QuartPos.fromBlock(i * BIOME_LINE_STEP), QuartPos.fromBlock(64), 0);
            if (last != null && !biome.equals(last)) changes++;
            last = biome;
        }
        return (double) changes / (BIOME_LINE_STEPS - 1);
    }

    private String amplified(Columns c) {
        if (overworld == null) return "FAIL no overworld sample to compare with";
        int ours = c.maxSurface();
        int theirs = overworld.maxSurface();
        return verdict(ours >= theirs + 32, "same " + c.area() + ", seed " + c.level.getSeed() + ": max surface y " + ours
                + " vs the overworld's " + theirs + " (needs +32); mean " + Math.round(c.meanSurface()) + " vs "
                + Math.round(overworld.meanSurface()));
    }

    /** The caves preset's terrain is shorter than its dimension type; its ceiling is the noise's top layer. */
    private String caves(Columns c) {
        boolean ceiling = c.level.dimensionType().hasCeiling();
        if (!(c.level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise)) {
            return "FAIL not a noise generator";
        }
        var settings = noise.generatorSettings().value().noiseSettings();
        int top = settings.minY() + settings.height() - 1;
        double solid = c.share(col -> !col.chunk.getBlockState(new BlockPos(col.x, top, col.z)).isAir());
        double bedrock = c.share(col -> col.chunk.getBlockState(new BlockPos(col.x, top, col.z)).is(Blocks.BEDROCK));
        long above = c.count(col -> col.surface > top);
        return verdict(ceiling && solid >= 0.99 && above == 0, c.area() + ": has_ceiling " + ceiling + "; " + pct(solid)
                + " of columns solid (" + pct(bedrock) + " bedrock) at the terrain's top layer y " + top + " (noise "
                + settings.minY() + ".." + top + " in a dimension to y " + c.level.getMaxY() + "); " + above + " columns hold a block above it");
    }

    private String floatingIslands(Columns c) {
        aviary = c;
        aviaryGenerator = c.level.getChunkSource().getGenerator();
        double open = c.openShare();
        double islands = c.share(col -> col.lowest != null);
        return verdict(open >= 0.5 && islands > 0, c.area() + ", seed " + c.level.getSeed() + ": " + pct(open)
                + " of columns have nothing below the island underside down to min y " + c.level.getMinY() + "; "
                + pct(islands) + " carry an island; lowest island block y " + c.lowestIsland());
    }

    private String clone(Columns c) {
        if (aviary == null) return "FAIL no the_aviary sample to compare with";
        Definition def = manager.index().lazy(c.slug).map(LevelIndex.Entry::definition).orElse(null);
        String of = def == null ? "?" : def.generator().of();
        String ours = settingsKey(c.level.getChunkSource().getGenerator());
        String theirs = settingsKey(aviaryGenerator);
        Set<String> ourBiomes = biomeKeys(c.level.getChunkSource().getGenerator());
        Set<String> theirBiomes = biomeKeys(aviaryGenerator);
        double open = c.openShare();
        double aviaryOpen = aviary.openShare();
        boolean pass = ours.equals(theirs) && ourBiomes.equals(theirBiomes) && Math.abs(open - aviaryOpen) <= 0.25
                && c.share(col -> col.lowest != null) > 0;
        return verdict(pass, "clones `" + of + "`; seeds differ (" + c.level.getSeed() + " vs " + aviary.level.getSeed()
                + ", each definition's own), so shape is compared: settings " + ours + " vs " + theirs + ", biome source "
                + ourBiomes.size() + " vs " + theirBiomes.size() + " biomes" + (ourBiomes.equals(theirBiomes) ? " (same)" : " (differ)")
                + ", open columns " + pct(open) + " vs " + pct(aviaryOpen) + ", island columns "
                + pct(c.share(col -> col.lowest != null)) + " vs " + pct(aviary.share(col -> col.lowest != null))
                + " over the same " + c.area());
    }

    private String nether(Columns c) {
        int roof = c.level.getMinY() + c.level.dimensionType().logicalHeight() - 1;
        int sea = c.level.getSeaLevel();
        double bedrock = c.share(col -> col.chunk.getBlockState(new BlockPos(col.x, roof, col.z)).is(Blocks.BEDROCK));
        long lava = c.count(col -> col.chunk.getBlockState(new BlockPos(col.x, sea - 1, col.z)).getFluidState().is(FluidTags.LAVA));
        return verdict(bedrock >= 0.99 && lava > 0, c.area() + ": " + pct(bedrock) + " of columns bedrock at the roof y " + roof
                + "; " + lava + " columns with lava at sea level " + sea + " (y " + (sea - 1) + ")");
    }

    private String end(Columns c) {
        Columns centre = c.filter(col -> Math.abs(col.x) < 32 && Math.abs(col.z) < 32);
        Columns ring = c.filter(col -> {
            double d = Math.sqrt((double) col.x * col.x + (double) col.z * col.z);
            return d >= 200 && d <= 1000;
        });
        double island = centre.share(col -> col.surface > c.level.getMinY());
        long solid = ring.count(col -> col.surface >= c.level.getMinY());
        // Vanilla scatters small End islands through the void, so the ring is mostly empty, not empty.
        return verdict(island >= 0.9 && ring.size() > 0 && solid <= ring.size() / 100, pct(island) + " of " + centre.size()
                + " columns within 32 of 0,0 carry the main island (surface " + centre.minSurface() + ".." + centre.maxSurface()
                + "); " + solid + " of " + ring.size() + " columns 200..1000 blocks out hold any block (at most 1%)");
    }

    private String flat(Columns c) {
        Definition def = definition(c.slug);
        List<String> want = new ArrayList<>();
        for (JsonObject layer : def.generator().layers()) {
            for (int i = 0; i < layer.get("height").getAsInt(); i++) want.add(layer.get("block").getAsString());
        }
        Set<Long> built = portalColumns(c.level, 4);
        List<int[]> columns = new ArrayList<>();
        int skipped = 0;
        for (int[] xz : new int[][] {{0, 0}, {-16, -16}, {15, -9}, {-3, 12}, {-29, 25}, {27, -30}, {20, 20}, {-25, -5}}) {
            if (columns.size() == 4) break;
            if (built.contains(columnKey(xz[0], xz[1]))) skipped++;
            else columns.add(xz);
        }
        List<String> wrong = new ArrayList<>();
        List<String> stack = null;
        for (int[] xz : columns) {
            LevelChunk chunk = c.chunkAt(xz[0], xz[1]);
            List<String> got = new ArrayList<>();
            for (int y = c.level.getMinY(); y <= c.level.getMinY() + want.size(); y++) {
                got.add(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(new BlockPos(xz[0], y, xz[1])).getBlock()).toString());
            }
            List<String> expected = new ArrayList<>(want);
            expected.add("minecraft:air");
            if (!got.equals(expected)) wrong.add(xz[0] + "," + xz[1] + " " + got);
            stack = got;
        }
        return verdict(wrong.isEmpty() && columns.size() == 4, columns.size() + " columns from min y " + c.level.getMinY()
                + " read " + stack + "; definition layers " + want + " then air; " + skipped
                + " candidate columns skipped within 4 blocks of a portal" + (wrong.isEmpty() ? "" : "; wrong: " + String.join(" | ", wrong)));
    }

    /** Columns within {@code margin} blocks of a registered portal's interior or landing: built on, not generated. */
    private static Set<Long> portalColumns(ServerLevel level, int margin) {
        Set<Long> out = new HashSet<>();
        for (com.customdimensions.server.portal.PortalZone zone : com.customdimensions.server.portal.PortalRegistry.get()
                .zones(level.dimension())) {
            List<int[]> cells = new ArrayList<>();
            zone.coreInterior().forEach(p -> cells.add(new int[] {p.x(), p.z()}));
            cells.add(new int[] {zone.landing().getX(), zone.landing().getZ()});
            for (int[] cell : cells) {
                for (int dx = -margin; dx <= margin; dx++) {
                    for (int dz = -margin; dz <= margin; dz++) out.add(columnKey(cell[0] + dx, cell[1] + dz));
                }
            }
        }
        return out;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private String checkerboard(Columns c) {
        Definition def = definition(c.slug);
        int scale = def.generator().cellScale() == null ? 2 : def.generator().cellScale();
        int cell = 1 << (scale + 4);
        Map<Long, Set<String>> cells = new HashMap<>();
        for (Column col : c.columns) {
            if ((col.x & 3) != 0 || (col.z & 3) != 0) continue;
            long key = ((long) Math.floorDiv(col.x, cell) << 32) ^ (Math.floorDiv(col.z, cell) & 0xffffffffL);
            cells.computeIfAbsent(key, k -> new TreeSet<>()).add(biomeAt(col, 64));
        }
        long mixed = cells.values().stream().filter(s -> s.size() != 1).count();
        int edges = 0;
        int differing = 0;
        for (Map.Entry<Long, Set<String>> e : cells.entrySet()) {
            int cx = (int) (e.getKey() >> 32);
            int cz = e.getKey().intValue();
            for (long next : new long[] {((long) (cx + 1) << 32) ^ (cz & 0xffffffffL), ((long) cx << 32) ^ ((cz + 1) & 0xffffffffL)}) {
                Set<String> other = cells.get(next);
                if (other == null) continue;
                edges++;
                if (!other.equals(e.getValue())) differing++;
            }
        }
        return verdict(mixed == 0 && edges > 0 && differing == edges, c.area() + ", cellScale " + scale + " (" + cell + "-block cells): "
                + cells.size() + " cells, " + mixed + " with more than one biome; " + differing + "/" + edges
                + " cell boundaries change biome; biomes " + c.biomes());
    }

    private String voidWorld(Columns c) {
        long blocks = 0;
        for (LevelChunk chunk : c.chunks) {
            for (LevelChunkSection section : chunk.getSections()) {
                if (section.hasOnlyAir()) continue;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            if (!section.getBlockState(x, y, z).isAir()) blocks++;
                        }
                    }
                }
            }
        }
        return verdict(blocks == 0, c.area() + ": " + blocks + " non-air blocks (the mod builds no spawn platform)");
    }

    private String seaLevel(Columns c) {
        Definition def = definition(c.slug);
        int want = def.liquids().seaLevel();
        int sea = c.level.getSeaLevel();
        Columns open = c.filter(col -> {
            BlockState s = col.chunk.getBlockState(new BlockPos(col.x, want - 1, col.z));
            return s.isAir() || !s.getFluidState().isEmpty();
        });
        long filled = open.count(col -> col.chunk.getBlockState(new BlockPos(col.x, want - 1, col.z)).getFluidState().isSource());
        long above = c.count(col -> col.chunk.getBlockState(new BlockPos(col.x, want, col.z)).getFluidState().isSource());
        double share = open.size() == 0 ? 0 : (double) filled / open.size();
        return verdict(sea == want && open.size() > 0 && share >= 0.95 && above <= c.size() / 50, "liquids.seaLevel " + want
                + " (preset " + presetOf(c.slug) + " has " + PRESET_SEA_LEVELS.get(presetOf(c.slug)) + "): level sea level " + sea
                + "; at y " + (want - 1) + ", " + filled + "/" + open.size() + " columns not inside terrain hold a fluid source ("
                + (c.size() - open.size()) + " of " + c.size() + " are inside an island there); " + above + " hold a source at y " + want);
    }

    // ---- choosing definitions -------------------------------------------------------------------

    /** The definition whose sea level departs furthest from its preset's; the first by slug on a tie. */
    private String mostUnusualSeaLevel() {
        String best = null;
        int bestGap = -1;
        for (String slug : new TreeSet<>(allSlugs())) {
            Definition def = definition(slug);
            String preset = presetOf(slug);
            if (def == null || def.liquids() == null || def.liquids().seaLevel() == null || preset == null) continue;
            int gap = Math.abs(def.liquids().seaLevel() - PRESET_SEA_LEVELS.get(preset));
            if (gap > bestGap) {
                bestGap = gap;
                best = slug;
            }
        }
        return best;
    }

    /** The noise preset a definition generates with, through any clone chain; null for other generators. */
    private String presetOf(String slug) {
        Set<String> seen = new HashSet<>();
        Definition def = definition(slug);
        while (def != null && Generator.CLONE.equals(def.generator().type()) && seen.add(slug)) {
            String of = def.generator().of();
            slug = of.substring(of.indexOf(':') + 1);
            def = definition(slug);
        }
        return def != null && Generator.NOISE.equals(def.generator().type()) ? def.generator().preset() : null;
    }

    private Set<String> allSlugs() {
        Set<String> slugs = new HashSet<>(manager.index().lazy().keySet());
        manager.index().baseWorlds().forEach(w -> slugs.add(w.slug()));
        return slugs;
    }

    private Definition definition(String slug) {
        return manager.index().lazy(slug).map(LevelIndex.Entry::definition).orElseGet(() -> manager.index().baseWorlds().stream()
                .filter(w -> w.slug().equals(slug)).map(LevelIndex.BaseWorld::definition).findFirst().orElse(null));
    }

    // ---- loading --------------------------------------------------------------------------------

    /** 8 x 8 chunks four apart, centred on 0,0: a 512-block square read at 64 chunks. */
    private static List<ChunkPos> spaced() {
        List<ChunkPos> out = new ArrayList<>();
        for (int x = -16; x < 16; x += 4) {
            for (int z = -16; z < 16; z += 4) out.add(new ChunkPos(x, z));
        }
        return out;
    }

    private static List<ChunkPos> square(int x0, int z0, int n) {
        List<ChunkPos> out = new ArrayList<>();
        for (int x = x0; x < x0 + n; x++) {
            for (int z = z0; z < z0 + n; z++) out.add(new ChunkPos(x, z));
        }
        return out;
    }

    /** The four chunks at 0,0 and one chunk every 256 blocks out to 960 along each axis. */
    private static List<ChunkPos> endArea() {
        List<ChunkPos> out = new ArrayList<>(square(-1, -1, 2));
        for (int d : new int[] {16, 32, 48, 60}) {
            out.add(new ChunkPos(d, 0));
            out.add(new ChunkPos(-d, 0));
            out.add(new ChunkPos(0, d));
            out.add(new ChunkPos(0, -d));
        }
        return out;
    }

    private CompletableFuture<List<LevelChunk>> load(ServerLevel level, List<ChunkPos> chunks) {
        chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), true));
        Load l = new Load(level, chunks, new CompletableFuture<>(), new int[1]);
        loading.add(l);
        return l.done;
    }

    private void tick() {
        for (Load l : List.copyOf(loading)) {
            List<LevelChunk> got = new ArrayList<>();
            for (ChunkPos c : l.chunks) {
                LevelChunk chunk = l.level.getChunkSource().getChunkNow(c.x(), c.z());
                if (chunk != null) got.add(chunk);
            }
            if (got.size() == l.chunks.size()) {
                loading.remove(l);
                l.done.complete(got);
            } else if (++l.ticks[0] >= LOAD_CAP_TICKS) {
                loading.remove(l);
                l.done.completeExceptionally(new IllegalStateException(got.size() + "/" + l.chunks.size()
                        + " chunks loaded after " + LOAD_CAP_TICKS + " ticks"));
            }
        }
    }

    // ---- reading --------------------------------------------------------------------------------

    /** One column: its chunk, world-surface height, top block and lowest non-air block (null in an empty column). */
    private record Column(LevelChunk chunk, int x, int z, int surface, BlockState top, Integer lowest) {}

    /** Every column of the loaded chunks. */
    private static final class Columns {
        final String slug;
        final ServerLevel level;
        final List<LevelChunk> chunks;
        final List<Column> columns;

        Columns(String slug, ServerLevel level, List<LevelChunk> chunks) {
            this(slug, level, chunks, read(level, chunks));
        }

        private Columns(String slug, ServerLevel level, List<LevelChunk> chunks, List<Column> columns) {
            this.slug = slug;
            this.level = level;
            this.chunks = chunks;
            this.columns = columns;
        }

        private static List<Column> read(ServerLevel level, List<LevelChunk> chunks) {
            List<Column> out = new ArrayList<>();
            for (LevelChunk chunk : chunks) {
                int bx = chunk.getPos().getMinBlockX();
                int bz = chunk.getPos().getMinBlockZ();
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        int x = bx + dx;
                        int z = bz + dz;
                        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        BlockState top = chunk.getBlockState(new BlockPos(x, surface, z));
                        Integer lowest = null;
                        if (surface >= level.getMinY()) {
                            for (int y = level.getMinY(); y <= surface; y++) {
                                if (!chunk.getBlockState(new BlockPos(x, y, z)).isAir()) {
                                    lowest = y;
                                    break;
                                }
                            }
                        }
                        out.add(new Column(chunk, x, z, surface, top, lowest));
                    }
                }
            }
            return out;
        }

        Columns filter(java.util.function.Predicate<Column> test) {
            return new Columns(slug, level, chunks, columns.stream().filter(test).toList());
        }

        int size() {
            return columns.size();
        }

        long count(java.util.function.Predicate<Column> test) {
            return columns.stream().filter(test).count();
        }

        double share(java.util.function.Predicate<Column> test) {
            return columns.isEmpty() ? 0 : (double) count(test) / columns.size();
        }

        int minSurface() {
            return columns.stream().mapToInt(Column::surface).min().orElse(0);
        }

        int maxSurface() {
            return columns.stream().mapToInt(Column::surface).max().orElse(0);
        }

        double meanSurface() {
            return columns.stream().mapToInt(Column::surface).average().orElse(0);
        }

        /** Columns empty, or whose lowest block sits clear of min y: nothing below the island underside. */
        double openShare() {
            return share(col -> col.lowest == null || col.lowest > level.getMinY() + 8);
        }

        String lowestIsland() {
            return columns.stream().filter(c -> c.lowest != null).mapToInt(Column::lowest).min().stream()
                    .mapToObj(Integer::toString).findFirst().orElse("-");
        }

        /** Surface biomes at every fourth column (one per biome cell). */
        Set<String> biomes() {
            Set<String> out = new TreeSet<>();
            for (Column col : columns) {
                if ((col.x & 3) == 0 && (col.z & 3) == 0) out.add(biomeAt(col, col.surface));
            }
            return out;
        }

        /** Of neighbouring biome cells (four blocks apart, same chunk), the share whose surface biomes differ. */
        double biomeEdgeShare() {
            int pairs = 0;
            int differ = 0;
            for (Column col : columns) {
                if ((col.x & 3) != 0 || (col.z & 3) != 0 || (col.x & 15) == 12) continue;
                Column next = chunkColumn(col.chunk, col.x + 4, col.z);
                pairs++;
                if (!biomeAt(col, col.surface).equals(biomeAt(next, next.surface))) differ++;
            }
            return pairs == 0 ? 0 : (double) differ / pairs;
        }

        private Column chunkColumn(LevelChunk chunk, int x, int z) {
            int i = chunks.indexOf(chunk) * 256 + (x & 15) * 16 + (z & 15);
            return columns.get(i);
        }

        LevelChunk chunkAt(int x, int z) {
            return chunks.stream().filter(c -> c.getPos().x() == Math.floorDiv(x, 16) && c.getPos().z() == Math.floorDiv(z, 16))
                    .findFirst().orElseThrow(() -> new IllegalStateException("no loaded chunk at " + x + "," + z));
        }

        String area() {
            int minX = chunks.stream().mapToInt(c -> c.getPos().getMinBlockX()).min().orElse(0);
            int maxX = chunks.stream().mapToInt(c -> c.getPos().getMinBlockX()).max().orElse(0) + 16;
            int minZ = chunks.stream().mapToInt(c -> c.getPos().getMinBlockZ()).min().orElse(0);
            int maxZ = chunks.stream().mapToInt(c -> c.getPos().getMinBlockZ()).max().orElse(0) + 16;
            return (maxX - minX) + "x" + (maxZ - minZ) + " area (" + chunks.size() + " chunks)";
        }
    }

    private static String biomeAt(Column col, int y) {
        Holder<Biome> biome = col.chunk.getNoiseBiome(QuartPos.fromBlock(col.x), QuartPos.fromBlock(y), QuartPos.fromBlock(col.z));
        return biome.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
    }

    private static String settingsKey(ChunkGenerator generator) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return generator.getClass().getSimpleName();
        return noise.generatorSettings().unwrapKey().map(k -> k.identifier().toString()).orElse("inline");
    }

    private static Set<String> biomeKeys(ChunkGenerator generator) {
        Set<String> out = new TreeSet<>();
        generator.getBiomeSource().possibleBiomes().forEach(b -> out.add(b.unwrapKey().map(k -> k.identifier().toString()).orElse("?")));
        return out;
    }

    private static String verdict(boolean pass, String evidence) {
        return (pass ? "PASS " : "FAIL ") + evidence;
    }

    private static String pct(double share) {
        return String.format("%.1f%%", share * 100);
    }

    private int holdAwake() {
        if (!(server instanceof DedicatedServer dedicated)) return -1;
        int seconds = dedicated.pauseWhenEmptySeconds();
        if (seconds > 0) dedicated.setPauseWhenEmptySeconds(0);
        return seconds;
    }

    private void restorePause(int seconds) {
        if (seconds > 0 && server instanceof DedicatedServer dedicated) dedicated.setPauseWhenEmptySeconds(seconds);
    }

    private static String cause(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
