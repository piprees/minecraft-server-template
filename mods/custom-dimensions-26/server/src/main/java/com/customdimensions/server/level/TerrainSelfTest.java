package com.customdimensions.server.level;

import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The terrain library's part of the self-test. For each terrain it opens the
 * gallery fixture {@code terrain_<name>_default}, scans the generator's base
 * height off the server thread (also the cost per column, against the
 * overworld's), picks the area where the terrain's feature is, force-loads
 * real chunks there and measures the claim the terrain makes. With
 * {@code -Dcustomdimensions.selftest.gallery=<dir>} it also renders every
 * {@code terrain_*} dimension as a greyscale height PNG with an index page.
 */
public final class TerrainSelfTest {
    static final String GALLERY = "customdimensions.selftest.gallery";
    static final int LOAD_CAP_TICKS = 6000;
    /** Scan: a square of this radius in blocks, one column every {@link #SCAN_STEP}. */
    static final int SCAN_RADIUS = 512;
    static final int SCAN_STEP = 16;
    static final int GALLERY_RADIUS = 1024;
    static final int GALLERY_STEP = 8;
    static final int GALLERY_THREADS = 3;
    /** Erosion's residual hills: land this many blocks above the basin floor. */
    static final int HILL_RISE = 16;
    /** Canyon floors: scanned every 8 blocks, 12+ below both points 24 blocks away on one axis, narrower than a lake. */
    static final int FLOOR_STEP = 8;
    static final int FLOOR_ACROSS = 24;
    static final int FLOOR_CUT = 12;
    /** Single precision: two terrains also rendered block by block around 30,000, -30,000 to show any banding or seam. */
    static final int FAR = 30_000;
    static final List<String> FAR_RENDERS = List.of("terrain_terraces_default", "terrain_canyons_default");
    /** Bedrock's overworld range, which every terrain here must stay inside. */
    static final int BEDROCK_MIN = -64;
    static final int BEDROCK_MAX = 320;
    /** How far a library noise's measured spread may sit from the one the compiler's coverage maths uses. */
    static final double SIGMA_TOLERANCE = 0.15;
    static final List<String> FAMILIES = List.of("continents", "erosion", "ridges", "terraces", "canyons", "rivers",
            "floating_islands", "islands", "voids", "shattered", "spires");

    private record Load(ServerLevel level, List<ChunkPos> chunks, CompletableFuture<List<LevelChunk>> done, int[] ticks) {}

    /** A base-height scan: {@code heights[i][j]} is the first air y above the top block at the grid point. */
    record Scan(int radius, int step, int[][] heights, double microsPerColumn, int minY) {
        int size() {
            return heights.length;
        }

        int x(int i) {
            return -radius + i * step;
        }
    }

    private final MinecraftServer server;
    private final LevelManager manager;
    private final List<String> lines = new ArrayList<>();
    private final List<Load> loading = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "terrain-selftest"));
    private final Map<String, Double> costs = new TreeMap<>();
    /** The control: the continents fixture measured in each area picker's area. */
    private final Map<String, Measure> controls = new TreeMap<>();
    private final Map<String, Function<Scan, Area>> pickers = new java.util.LinkedHashMap<>();
    private double overworldCost;
    /** Canyon floors of the canyons fixture and of the rivers fixture, the wet control. */
    private final Map<String, List<Floor>> floorScans = new TreeMap<>();

    private TerrainSelfTest(MinecraftServer server, LevelManager manager) {
        this.server = server;
        this.manager = manager;
        pickers.put("spaced", this::spaced);
        pickers.put("highest", this::highest);
        pickers.put("riverBed", this::riverBed);
        pickers.put("firstVoid", this::firstVoid);
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) {
            return CompletableFuture.completedFuture(List.of("FAIL terrain: the level manager did not start"));
        }
        TerrainSelfTest test = new TerrainSelfTest(server, manager);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.tick());
        return test.run();
    }

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        chain = chain.thenCompose(v -> CompletableFuture.supplyAsync(() -> {
                    scan(server.overworld(), SCAN_RADIUS, SCAN_STEP);
                    return scan(server.overworld(), SCAN_RADIUS, SCAN_STEP);
                }, worker)
                .thenAcceptAsync(scan -> {
                    overworldCost = scan.microsPerColumn();
                    lines.add("PASS terrain baseline (overworld, vanilla terrain): " + fmt(overworldCost)
                            + " µs per column (base height + surface biome, one thread, " + scan.size() * scan.size()
                            + " columns, second of two scans)");
                }, server));
        chain = chain.thenCompose(v -> noiseSpread());
        chain = chain.thenCompose(v -> controls());
        chain = family(chain, "continents", this::continents, "spaced");
        chain = family(chain, "erosion", this::erosion, "spaced");
        chain = family(chain, "ridges", this::ridges, "highest");
        chain = family(chain, "terraces", this::terraces, "spaced");
        for (String fixture : List.of("terrain_canyons_default", "terrain_rivers_default")) {
            chain = chain.thenCompose(v -> floorScan(fixture).handle((floors, error) -> {
                if (error != null) lines.add("FAIL terrain canyons floor scan (" + fixture + "): " + cause(error));
                else floorScans.put(fixture, floors);
                return null;
            }));
        }
        chain = family(chain, "canyons", this::canyons, "spaced");
        chain = family(chain, "rivers", this::rivers, "riverBed");
        chain = family(chain, "floating_islands", this::floatingIslands, "spaced");
        chain = family(chain, "islands", this::islands, "spaced");
        chain = family(chain, "voids", this::voids, "firstVoid");
        chain = family(chain, "shattered", this::shattered, "spaced");
        chain = family(chain, "spires", this::spires, "highest");
        chain = chain.thenRun(this::performance);
        String gallery = System.getProperty(GALLERY, "");
        if (!gallery.isEmpty()) {
            chain = chain.thenCompose(v -> gallery(Path.of(gallery)));
        }
        return chain.handle((v, error) -> {
            if (error != null) lines.add("FAIL terrain: the check chain broke: " + cause(error));
            restorePause(pause);
            worker.shutdown();
            return List.copyOf(lines);
        });
    }

    // ---- per family -------------------------------------------------------------------------------

    private record Area(Scan scan, List<ChunkPos> chunks, BlockPos centre) {}

    /** The continents fixture, scanned once and loaded in every picker's area, as the control each claim is set against. */
    private CompletableFuture<Void> controls() {
        String slug = "terrain_continents_default";
        if (!manager.index().lazy().containsKey(slug)) {
            lines.add("FAIL terrain control: no gallery fixture " + slug + " in the definitions");
            return CompletableFuture.completedFuture(null);
        }
        return manager.activate(slug).thenCompose(a -> {
            ServerLevel level = a.level();
            return CompletableFuture.supplyAsync(() -> scan(level, SCAN_RADIUS, SCAN_STEP), worker).thenComposeAsync(scan -> {
                CompletableFuture<Void> loads = CompletableFuture.completedFuture(null);
                for (Map.Entry<String, Function<Scan, Area>> picker : pickers.entrySet()) {
                    loads = loads.thenCompose(v -> {
                        Area area = picker.getValue().apply(scan);
                        return load(level, area.chunks()).thenAccept(chunks -> {
                            controls.put(picker.getKey(), new Measure(level, chunks, area));
                            area.chunks().forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                        });
                    });
                }
                return loads;
            }, server);
        }).thenCompose(x -> manager.deactivate(slug).thenAccept(t -> {})).exceptionally(error -> {
            lines.add("FAIL terrain control (" + slug + "): " + cause(error));
            return null;
        });
    }

    private CompletableFuture<Void> family(CompletableFuture<Void> chain, String family,
                                           java.util.function.BiFunction<Measure, Measure, String> check, String picker) {
        Function<Scan, Area> where = pickers.get(picker);
        String slug = "terrain_" + family + "_default";
        String name = "terrain " + family + " (" + slug + ")";
        return chain.thenCompose(v -> {
            if (!manager.index().lazy().containsKey(slug)) {
                lines.add("FAIL " + name + ": no gallery fixture " + slug + " in the definitions");
                return CompletableFuture.completedFuture(null);
            }
            return manager.activate(slug).thenCompose(a -> {
                ServerLevel level = a.level();
                return CompletableFuture.supplyAsync(() -> scan(level, SCAN_RADIUS, SCAN_STEP), worker)
                        .thenComposeAsync(scan -> {
                            costs.put(family, scan.microsPerColumn());
                            Area area = where.apply(scan);
                            return load(level, area.chunks()).handle((chunks, error) -> {
                                String line;
                                try {
                                    if (error != null) throw new IllegalStateException(cause(error));
                                    Measure m = new Measure(level, chunks, area);
                                    Measure control = controls.get(picker);
                                    if (control == null) throw new IllegalStateException("no control measured in the " + picker + " area");
                                    String bedrock = m.bedrock();
                                    line = check.apply(m, control);
                                    if (bedrock != null) line = "FAIL " + bedrock + "; " + line.replaceFirst("^(PASS|FAIL) ", "");
                                } catch (RuntimeException e) {
                                    line = "FAIL " + e;
                                }
                                area.chunks().forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                                lines.add(line.replaceFirst("^(PASS|FAIL) ", "$1 " + name + ": "));
                                return null;
                            });
                        }, server);
            }).thenCompose(x -> manager.deactivate(slug).thenAccept(t -> {})).exceptionally(error -> {
                lines.add("FAIL " + name + ": " + cause(error));
                return null;
            });
        });
    }

    /**
     * A fixture's canyon floors: a base-height scan every {@link #FLOOR_STEP}
     * blocks, its {@link #floorPoints} and each one's ocean floor, which lies
     * under the surface where water covers it. The fixture is open only for the scan.
     */
    private CompletableFuture<List<Floor>> floorScan(String slug) {
        if (!manager.index().lazy().containsKey(slug)) {
            return CompletableFuture.failedFuture(new IllegalStateException("no gallery fixture " + slug + " in the definitions"));
        }
        return manager.activate(slug).thenCompose(a -> CompletableFuture.supplyAsync(() -> {
            ServerLevel level = a.level();
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            RandomState random = level.getChunkSource().randomState();
            List<Floor> out = new ArrayList<>();
            for (int[] p : floorPoints(scan(level, SCAN_RADIUS, FLOOR_STEP), FLOOR_ACROSS / FLOOR_STEP)) {
                int bed = generator.getBaseHeight(p[0], p[1], Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                out.add(new Floor(p[2], bed < p[2]));
            }
            return out;
        }, worker)).thenComposeAsync(floors -> manager.deactivate(slug).thenApply(t -> floors), server);
    }

    /** Nine 2 by 2 chunk squares, 512 blocks apart over a 1,056-block square. */
    private Area spaced(Scan scan) {
        List<ChunkPos> out = new ArrayList<>();
        for (int x = -32; x <= 32; x += 32) {
            for (int z = -32; z <= 32; z += 32) {
                for (int dx = 0; dx < 2; dx++) {
                    for (int dz = 0; dz < 2; dz++) out.add(new ChunkPos(x + dx, z + dz));
                }
            }
        }
        return new Area(scan, out, BlockPos.ZERO);
    }

    /** 5 by 5 chunks around the scan point nearest the centre lying below sea level with land 48 blocks away on all four sides. */
    private Area riverBed(Scan scan) {
        int sea = 63;
        int off = 3;
        int best = Integer.MAX_VALUE;
        int bi = scan.size() / 2, bj = scan.size() / 2;
        int[][] h = scan.heights();
        for (int i = off; i < scan.size() - off; i++) {
            for (int j = off; j < scan.size() - off; j++) {
                boolean bed = h[i][j] <= sea && h[i - off][j] > sea + 2 && h[i + off][j] > sea + 2
                        && h[i][j - off] > sea + 2 && h[i][j + off] > sea + 2;
                int d = Math.abs(scan.x(i)) + Math.abs(scan.x(j));
                if (bed && d < best) {
                    best = d;
                    bi = i;
                    bj = j;
                }
            }
        }
        return around(scan, scan.x(bi), scan.x(bj));
    }

    /** 5 by 5 chunks around the first scan point with no block at all, nearest the centre. */
    private Area firstVoid(Scan scan) {
        int best = Integer.MAX_VALUE;
        int bi = scan.size() / 2, bj = scan.size() / 2;
        for (int i = 0; i < scan.size(); i++) {
            for (int j = 0; j < scan.size(); j++) {
                int d = Math.abs(scan.x(i)) + Math.abs(scan.x(j));
                if (scan.heights()[i][j] <= scan.minY() && d < best) {
                    best = d;
                    bi = i;
                    bj = j;
                }
            }
        }
        return around(scan, scan.x(bi), scan.x(bj));
    }

    /** 5 by 5 chunks around the highest scan point. */
    private Area highest(Scan scan) {
        int best = Integer.MIN_VALUE;
        int bi = 0, bj = 0;
        for (int i = 0; i < scan.size(); i++) {
            for (int j = 0; j < scan.size(); j++) {
                if (scan.heights()[i][j] > best) {
                    best = scan.heights()[i][j];
                    bi = i;
                    bj = j;
                }
            }
        }
        return around(scan, scan.x(bi), scan.x(bj));
    }

    private static Area around(Scan scan, int x, int z) {
        List<ChunkPos> out = new ArrayList<>();
        int cx = Math.floorDiv(x, 16);
        int cz = Math.floorDiv(z, 16);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) out.add(new ChunkPos(cx + dx, cz + dz));
        }
        return new Area(scan, out, new BlockPos(x, 0, z));
    }

    // ---- claims ------------------------------------------------------------------------------------

    private String continents(Measure m, Measure c) {
        double land = m.share(col -> col.surface >= m.sea);
        double ocean = m.share(col -> col.water);
        return verdict(land >= 0.05 && ocean >= 0.05, m.area() + ": " + pct(land) + " land at or above sea level "
                + m.sea + ", " + pct(ocean) + " open water (each needs 5%); surface " + m.range()
                + "; this fixture is the control every line below is measured against");
    }

    /**
     * Worn land is two-level: a flat basin floor (within a block of the
     * commonest land height) and the hills left standing well above it.
     */
    private String erosion(Measure m, Measure c) {
        double floor = m.floorShare();
        double hills = m.landShare(col -> col.floor >= m.modalLand() + HILL_RISE);
        double controlFloor = c.floorShare();
        double controlHills = c.landShare(col -> col.floor >= c.modalLand() + HILL_RISE);
        return verdict(floor >= 0.2 && hills >= 0.2 && hills >= 2 * controlHills, m.area() + ": " + pct(floor)
                + " of land columns on the basin floor within 1 block of y " + m.modalLand() + " (needs 20%; continents "
                + pct(controlFloor) + " at y " + c.modalLand() + "), " + pct(hills) + " on hills " + HILL_RISE
                + "+ blocks above it (needs 20% and twice continents' " + pct(controlHills) + "); surface " + m.range());
    }

    private String ridges(Measure m, Measure c) {
        int rise = m.max() - m.min();
        int control = c.max() - c.min();
        return verdict(rise >= 40 && rise >= control + 20, "5x5 chunks at the highest scanned point " + m.centre() + ": surface "
                + m.min() + ".." + m.max() + ", " + rise + " blocks of relief (needs 40 and 20 more than continents' "
                + control + " at its highest point)");
    }

    /**
     * Terraced land is a stair of flat treads: over the whole scan's base
     * heights, a tread is a level whose 3-block window holds 0.5%+ of the land
     * and 2.5 times the mean of the windows 4 blocks below and above it, the
     * highest within 3 blocks. Rolling land has a tread at its plain and few
     * others.
     */
    private String terraces(Measure m, Measure c) {
        List<Integer> treads = treadLevels(m.area.scan());
        List<Integer> control = treadLevels(c.area.scan());
        return verdict(treads.size() >= 4 && treads.size() >= 2 * control.size(), "over the scan's base heights " + treads.size()
                + " treads at y " + treads + " (needs 4 and twice continents' " + control.size() + " at y " + control + ")");
    }

    static List<Integer> treadLevels(Scan scan) {
        TreeMap<Integer, Integer> count = new TreeMap<>();
        int land = 0;
        for (int[] row : scan.heights()) {
            for (int h : row) {
                if (h <= 64) continue;
                land++;
                count.merge(h, 1, Integer::sum);
            }
        }
        return levels(count, land, 0.005);
    }

    /**
     * The levels of a height count: a level's 3-block window holds
     * {@code minShare} of {@code total} and 2.5 times the mean of the windows
     * 4 blocks below and above it, and is the highest within 3 blocks.
     */
    static List<Integer> levels(TreeMap<Integer, Integer> count, int total, double minShare) {
        java.util.function.IntUnaryOperator w = y -> count.getOrDefault(y - 1, 0) + count.getOrDefault(y, 0) + count.getOrDefault(y + 1, 0);
        List<Integer> out = new ArrayList<>();
        if (count.isEmpty()) return out;
        int first = count.firstKey() - 1;
        int last = count.lastKey() + 1;
        for (int y = first; y <= last; y++) {
            int here = w.applyAsInt(y);
            boolean highest = true;
            for (int d = -3; d <= 3; d++) {
                if (d != 0 && (w.applyAsInt(y + d) > here || w.applyAsInt(y + d) == here && d < 0)) highest = false;
            }
            double side = (w.applyAsInt(y - 4) + w.applyAsInt(y + 4)) / 2.0;
            if (highest && here >= minShare * total && here >= 2.5 * side) out.add(y);
        }
        return out;
    }

    /**
     * Canyons are a network: over the whole scan, points lying 20+ below both
     * points 48 blocks away on one axis, where continents has only an
     * occasional ravine or cave mouth; and the deepest cut in the loaded areas.
     * Their floors are old river beds: of the canyon floor points in a scan
     * every 8 blocks ({@link #floorScan}), water covers few, where it covers
     * most of rivers'; the dry ones sit on distinct stepped levels; and the
     * lowest reach sea level.
     */
    private String canyons(Measure m, Measure c) {
        List<Floor> floors = floorScans.get("terrain_canyons_default");
        List<Floor> riverFloors = floorScans.get("terrain_rivers_default");
        if (floors == null || riverFloors == null) throw new IllegalStateException("no canyon floor scan");
        double share = trenchShare(m.area.scan());
        double controlShare = trenchShare(c.area.scan());
        int[] t = m.trench(12, 20);
        int[] control = c.trench(12, 20);
        double dry = dryShare(floors);
        double riverDry = dryShare(riverFloors);
        List<Integer> dryHeights = floors.stream().filter(f -> !f.wet()).map(Floor::surface).toList();
        List<Integer> levels = floorLevels(dryHeights);
        List<Integer> riverLevels = floorLevels(riverFloors.stream().filter(f -> !f.wet()).map(Floor::surface).toList());
        int lowest = dryHeights.stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
        boolean network = t[2] >= 25 && share >= 0.01 && share >= 3 * controlShare;
        boolean beds = floors.size() >= 100 && dry >= 0.8 && dry >= riverDry + 0.5 && levels.size() >= 3
                && levels.size() > riverLevels.size() && lowest <= m.sea + 2;
        String line = m.area() + ": over the scan " + pct(share)
                + " of points by land lie 20+ below both points 48 blocks away on one axis (needs 1% and three times continents' "
                + pct(controlShare) + "); deepest cut below both columns 12 blocks away " + t[2] + " blocks (needs 25; continents "
                + control[2] + ", a ravine); " + floors.size() + " canyon floor points every " + FLOOR_STEP + " blocks, " + FLOOR_CUT
                + "+ below both points " + FLOOR_ACROSS + " blocks away on one axis (needs 100; rivers " + riverFloors.size() + "), "
                + pct(dry) + " dry (needs 80% and 50 percentage points more than rivers' " + pct(riverDry) + "), " + levels.size()
                + " dry floor levels at y " + levels + " (needs 3 and more than rivers' " + riverLevels.size() + "), lowest dry floor y "
                + (dryHeights.isEmpty() ? "none" : lowest) + " (needs " + (m.sea + 2) + " or lower, at sea level " + m.sea + ")";
        return verdict(network && beds, beds ? line : line + "; dry floor heights " + histogram(dryHeights));
    }

    /** A canyon floor point's surface and whether water covers it. */
    record Floor(int surface, boolean wet) {}

    /** {@code {x, z, height}} of the scan points next to land lying {@link #FLOOR_CUT}+ below both points {@code off} steps away on one axis. */
    static List<int[]> floorPoints(Scan scan, int off) {
        int[][] h = scan.heights();
        List<int[]> out = new ArrayList<>();
        for (int i = off; i < scan.size() - off; i++) {
            for (int j = off; j < scan.size() - off; j++) {
                int p = h[i][j];
                if (Math.max(Math.max(h[i - off][j], h[i + off][j]), Math.max(h[i][j - off], h[i][j + off])) < 63) continue;
                boolean acrossX = h[i - off][j] - p >= FLOOR_CUT && h[i + off][j] - p >= FLOOR_CUT;
                boolean acrossZ = h[i][j - off] - p >= FLOOR_CUT && h[i][j + off] - p >= FLOOR_CUT;
                if (acrossX || acrossZ) out.add(new int[] {scan.x(i), scan.x(j), p});
            }
        }
        return out;
    }

    static double dryShare(List<Floor> floors) {
        return floors.isEmpty() ? 0 : floors.stream().filter(f -> !f.wet()).count() / (double) floors.size();
    }

    /** The floor heights' levels, each holding 3% of the floors (see {@link #levels}). */
    static List<Integer> floorLevels(List<Integer> floors) {
        TreeMap<Integer, Integer> count = new TreeMap<>();
        floors.forEach(h -> count.merge(h, 1, Integer::sum));
        return levels(count, floors.size(), 0.03);
    }

    /** {@code y:count} for each height, lowest first. */
    static String histogram(List<Integer> heights) {
        TreeMap<Integer, Integer> count = new TreeMap<>();
        heights.forEach(h -> count.merge(h, 1, Integer::sum));
        StringBuilder sb = new StringBuilder();
        count.forEach((y, n) -> sb.append(sb.isEmpty() ? "" : " ").append(y).append(':').append(n));
        return sb.toString();
    }

    /** Of the scan points next to land, those with both neighbours 48 blocks away on one axis 20+ above them: inside a trench. */
    private static double trenchShare(Scan scan) {
        int[][] h = scan.heights();
        int off = 3;
        int land = 0;
        int trench = 0;
        for (int i = off; i < scan.size() - off; i++) {
            for (int j = off; j < scan.size() - off; j++) {
                int p = h[i][j];
                if (Math.max(Math.max(h[i - off][j], h[i + off][j]), Math.max(h[i][j - off], h[i][j + off])) < 63) continue;
                land++;
                boolean acrossX = h[i - off][j] - p >= 20 && h[i + off][j] - p >= 20;
                boolean acrossZ = h[i][j - off] - p >= 20 && h[i][j + off] - p >= 20;
                if (acrossX || acrossZ) trench++;
            }
        }
        return land == 0 ? 0 : (double) trench / land;
    }

    private String rivers(Measure m, Measure c) {
        double water = m.share(col -> col.water && col.surface == m.sea - 1);
        double land = m.share(col -> col.surface >= m.sea);
        int floor = m.centreSurface(8);
        double controlWater = c.share(col -> col.water && col.surface == c.sea - 1);
        double controlLand = c.share(col -> col.surface >= c.sea);
        return verdict(water >= 0.01 && land >= 0.2 && floor < m.sea, "5x5 chunks at " + m.centre() + ": water at sea level on "
                + pct(water) + " of columns (needs 1%; continents " + pct(controlWater) + "), land on " + pct(land)
                + " (needs 20%; continents " + pct(controlLand) + "), lowest centre surface y " + floor
                + " (needs below sea level " + m.sea + "; continents y " + c.centreSurface(8) + ")");
    }

    private String floatingIslands(Measure m, Measure c) {
        double[] here = m.hanging();
        double[] control = c.hanging();
        return verdict(here[0] >= 0.05 && here[1] >= 0.9 && here[2] >= 0.3, m.area() + ": " + pct(here[0])
                + " of columns carry land (needs 5%; continents " + pct(control[0]) + "); " + pct(here[1])
                + " of those have only air below the island down to y " + (m.minY + 8) + " (needs 90%; continents "
                + pct(control[1]) + "); " + pct(here[2]) + " are empty to the bottom (needs 30%; continents " + pct(control[2])
                + "); lowest block y " + m.columns.stream().filter(col -> col.lowest != null).mapToInt(col -> col.lowest).min().orElse(m.minY));
    }

    private String islands(Measure m, Measure c) {
        double water = m.share(col -> col.water);
        double land = m.share(col -> col.surface >= m.sea);
        double controlWater = c.share(col -> col.water);
        return verdict(water >= 0.4 && water >= controlWater + 0.2 && land >= 0.03, m.area() + ": " + pct(water)
                + " open water (needs 40% and 20 points over continents' " + pct(controlWater) + "), " + pct(land)
                + " island land (needs 3%); surface " + m.range());
    }

    private String voids(Measure m, Measure c) {
        long empty = m.columns.stream().filter(col -> col.lowest == null).count();
        long controlEmpty = c.columns.stream().filter(col -> col.lowest == null).count();
        int rise = m.max() - m.landMedian();
        int controlRise = c.max() - c.landMedian();
        return verdict(empty >= 1 && rise >= 5, "5x5 chunks at " + m.centre() + ": " + empty
                + " columns with no block from top to bottom (needs 1; continents " + controlEmpty + "); highest rim y " + m.max()
                + ", " + rise + " above the median land y " + m.landMedian() + " (needs 5; continents " + controlRise + ")");
    }

    /** Broken land: a sharp step of 8 or more blocks in the top solid block to a column 2 blocks away, which cliffs and chasms make. */
    private String shattered(Measure m, Measure c) {
        double broken = m.brokenShare();
        double control = c.brokenShare();
        return verdict(broken >= 0.1 && broken >= 3 * control, m.area() + ": " + pct(broken)
                + " of land columns have a top solid block 8+ blocks above or below a column 2 blocks away (cliff or chasm; "
                + "needs 10% and three times continents' " + pct(control) + ")");
    }

    private String spires(Measure m, Measure c) {
        int rise = m.max() - m.median();
        int control = c.max() - c.median();
        double tall = m.share(col -> col.surface > m.median() + 20);
        return verdict(rise >= 40 && rise >= 2 * control && tall <= 0.3, "5x5 chunks at " + m.centre() + ": top y " + m.max() + ", "
                + rise + " above the area's median " + m.median() + " (needs 40 and twice continents' " + control
                + " at its highest point); " + pct(tall) + " of the area stands 20 above the median (a spire, not a plateau: needs at most 30%)");
    }

    private void performance() {
        StringBuilder sb = new StringBuilder();
        List<String> over = new ArrayList<>();
        for (String f : FAMILIES) {
            Double c = costs.get(f);
            if (c == null) continue;
            double ratio = c / overworldCost;
            sb.append(sb.isEmpty() ? "" : ", ").append(f).append(' ').append(fmt(c)).append(" (").append(fmt(ratio)).append("x)");
            if (ratio > 2) over.add(f);
        }
        lines.add((over.isEmpty() ? "PASS" : "FAIL") + " terrain performance: µs per column against the overworld's "
                + fmt(overworldCost) + ": " + sb + (over.isEmpty() ? "; none over 2x" : "; over 2x: " + over));
    }

    // ---- noise spread -------------------------------------------------------------------------------

    /** Each library noise sampled at 20,000 points out to 30,000 blocks: its standard deviation against the library's. */
    private CompletableFuture<Void> noiseSpread() {
        String slug = "terrain_islands_default";
        if (!manager.index().lazy().containsKey(slug)) {
            lines.add("FAIL terrain noise spread: no gallery fixture " + slug);
            return CompletableFuture.completedFuture(null);
        }
        return manager.activate(slug).thenCompose(a -> CompletableFuture.supplyAsync(() -> {
            RandomState random = a.level().getChunkSource().randomState();
            RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            StringBuilder sb = new StringBuilder();
            boolean pass = true;
            java.util.Random points = new java.util.Random(7);
            for (String noise : com.customdimensions.compiler.terrain.Noises.names()) {
                com.google.gson.JsonObject json = new com.google.gson.JsonObject();
                json.addProperty("type", "minecraft:noise");
                json.addProperty("noise", "adventure:terrain/" + noise);
                json.addProperty("xz_scale", 1.0);
                json.addProperty("y_scale", 1.0);
                DensityFunction f = DensityFunction.CODEC.parse(ops, json).getOrThrow();
                double sum = 0, sq = 0;
                int n = 20000;
                for (int i = 0; i < n; i++) {
                    double v = random.sampleBlockValueUncached(f, points.nextInt(60001) - 30000, points.nextInt(256) - 64,
                            points.nextInt(60001) - 30000);
                    sum += v;
                    sq += v * v;
                }
                double mean = sum / n;
                double sd = Math.sqrt(sq / n - mean * mean);
                double assumed = com.customdimensions.compiler.terrain.Noises.sigma(noise);
                pass &= Math.abs(sd - assumed) <= SIGMA_TOLERANCE * assumed;
                sb.append(sb.isEmpty() ? "" : ", ").append(noise).append(" sd ").append(String.format(Locale.ROOT, "%.3f", sd))
                        .append(" (library ").append(assumed).append(")")
                        .append(" mean ").append(String.format(Locale.ROOT, "%.3f", mean));
            }
            return (pass ? "PASS" : "FAIL") + " terrain noise spread (each within 15% of the library's sd, 20,000 points): " + sb;
        }, worker)).thenComposeAsync(line -> {
            lines.add(line);
            return manager.deactivate(slug).thenAccept(t -> {});
        }, server).exceptionally(error -> {
            lines.add("FAIL terrain noise spread: " + cause(error));
            return null;
        });
    }

    // ---- scanning and the gallery ---------------------------------------------------------------------

    /** Base heights over a square grid, timed with the surface biome per column (one thread). */
    static Scan scan(ServerLevel level, int radius, int step) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        BiomeResolver biomes = generator.getBiomeSource().createUncachedResolver(random);
        int n = 2 * radius / step + 1;
        int[][] heights = new int[n][n];
        for (int w = 0; w < 64; w++) {
            generator.getBaseHeight(w * 31, -w * 17, Heightmap.Types.WORLD_SURFACE_WG, level, random);
        }
        long begin = System.nanoTime();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int x = -radius + i * step;
                int z = -radius + j * step;
                int h = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(h), QuartPos.fromBlock(z));
                heights[i][j] = h;
            }
        }
        double micros = (System.nanoTime() - begin) / 1000.0 / (n * n);
        return new Scan(radius, step, heights, micros, level.getMinY());
    }

    /** Every {@code terrain_*} dimension rendered at radius 1,024, step 8, as a greyscale PNG; then an index page. */
    private CompletableFuture<Void> gallery(Path dir) {
        List<String> slugs = manager.index().lazy().keySet().stream().filter(s -> s.startsWith("terrain_")).sorted().toList();
        ExecutorService pool = Executors.newFixedThreadPool(GALLERY_THREADS, r -> new Thread(r, "terrain-gallery"));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        List<String> rendered = new ArrayList<>();
        List<String[]> jobs = new ArrayList<>();
        slugs.forEach(slug -> jobs.add(new String[] {slug, slug, "0", "0", String.valueOf(GALLERY_RADIUS), String.valueOf(GALLERY_STEP)}));
        for (String far : FAR_RENDERS) {
            if (slugs.contains(far)) {
                jobs.add(new String[] {far, "far_" + far, String.valueOf(FAR), String.valueOf(-FAR), "128", "1"});
            }
        }
        for (String[] job : jobs) {
            String slug = job[0];
            chain = chain.thenCompose(v -> manager.activate(slug).thenCompose(a -> render(a.level(), pool, Integer.parseInt(job[2]),
                            Integer.parseInt(job[3]), Integer.parseInt(job[4]), Integer.parseInt(job[5]))
                    .thenApplyAsync(img -> {
                        try {
                            Files.createDirectories(dir);
                            ImageIO.write(img, "png", dir.resolve(job[1] + ".png").toFile());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        rendered.add(job[1]);
                        return null;
                    }, server)).thenCompose(x -> manager.deactivate(slug).thenAccept(t -> {})));
        }
        return chain.handle((v, error) -> {
            pool.shutdown();
            if (error != null) {
                lines.add("FAIL terrain gallery: " + cause(error));
                return null;
            }
            try {
                Files.writeString(dir.resolve("index.html"), indexPage(rendered));
            } catch (IOException e) {
                lines.add("FAIL terrain gallery: index.html: " + e);
                return null;
            }
            lines.add("PASS terrain gallery: " + rendered.size() + " height maps (radius " + GALLERY_RADIUS + ", step "
                    + GALLERY_STEP + "; the far ones radius 128, step 1 at " + FAR + ", " + -FAR + ") in " + dir);
            return null;
        });
    }

    /** Rows split over the pool; the ocean floor height: blue below sea level, grey from sea level (dark) to y 256 (white), red no block. */
    private static CompletableFuture<BufferedImage> render(ServerLevel level, ExecutorService pool, int cx, int cz, int radius,
                                                           int step) {
        int n = 2 * radius / step + 1;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        List<CompletableFuture<Void>> parts = new ArrayList<>();
        for (int t = 0; t < GALLERY_THREADS; t++) {
            int first = t;
            parts.add(CompletableFuture.runAsync(() -> {
                for (int j = first; j < n; j += GALLERY_THREADS) {
                    for (int i = 0; i < n; i++) {
                        int x = cx - radius + i * step;
                        int z = cz - radius + j * step;
                        int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                        img.setRGB(i, j, colour(h, level.getMinY(), level.getSeaLevel()));
                    }
                }
            }, pool));
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(v -> img);
    }

    static int colour(int h, int minY, int sea) {
        if (h <= minY) return 0x5a1010;
        if (h <= sea && sea > minY) {
            double depth = Math.min(1, (sea - h) / 64.0);
            return (int) (20 + 30 * (1 - depth)) << 16 | (int) (40 + 60 * (1 - depth)) << 8 | (int) (120 + 100 * (1 - depth));
        }
        int base = sea > minY ? sea : BEDROCK_MIN;
        int g = (int) Math.round(Math.max(40, Math.min(255, 40 + (h - base) * 215.0 / (256 - base))));
        return g << 16 | g << 8 | g;
    }

    static String indexPage(List<String> slugs) {
        Map<String, Map<String, String>> grid = new TreeMap<>();
        for (String slug : slugs) {
            if (!slug.startsWith("terrain_")) continue;
            String rest = slug.substring("terrain_".length());
            int cut = rest.lastIndexOf('_');
            grid.computeIfAbsent(rest.substring(0, cut), k -> new TreeMap<>()).put(rest.substring(cut + 1), slug);
        }
        StringBuilder sb = new StringBuilder("<!doctype html><meta charset=utf-8><title>Terrain gallery</title>"
                + "<style>body{font-family:sans-serif;background:#111;color:#ddd}td{text-align:center;padding:4px}"
                + "img{width:257px;image-rendering:pixelated}</style><h1>Terrain gallery</h1>"
                + "<p>Ocean-floor height at radius 1,024 blocks, one pixel per 8: blue below sea level (darker is deeper), "
                + "grey from sea level (dark) to y 256 (white), red a column with no block.</p>"
                + "<table><tr><th></th><th>gentle</th><th>default</th><th>extreme</th><th>wide</th><th>compressed</th></tr>");
        for (Map.Entry<String, Map<String, String>> row : grid.entrySet()) {
            sb.append("<tr><th>").append(row.getKey()).append("</th>");
            for (String s : List.of("gentle", "default", "extreme", "wide", "compressed")) {
                String slug = row.getValue().get(s);
                sb.append("<td>").append(slug == null ? "" : "<img src=\"" + slug + ".png\" alt=\"" + slug + "\">").append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table><h2>At 30,000, -30,000</h2><p>Block by block over 257 by 257 blocks, to show any banding or seam "
                + "from single-precision evaluation.</p><p>");
        for (String slug : slugs) {
            if (slug.startsWith("far_")) sb.append("<img src=\"").append(slug).append(".png\" alt=\"").append(slug).append("\"> ");
        }
        return sb.append("</p>\n").toString();
    }

    // ---- reading chunks -----------------------------------------------------------------------------

    /** One column: world-surface top block y, whether it is water, the lowest block (null: none), the ground: the top solid block that is not a tree. */
    private record Column(int x, int z, int surface, boolean water, Integer lowest, int floor) {}

    private final class Measure {
        final ServerLevel level;
        final List<Column> columns = new ArrayList<>();
        final Area area;
        final int sea;
        final int minY;

        Measure(ServerLevel level, List<LevelChunk> chunks, Area area) {
            this.level = level;
            this.area = area;
            this.sea = level.getSeaLevel();
            this.minY = level.getMinY();
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
                        if (surface >= minY) {
                            for (int y = minY; y <= surface; y++) {
                                if (!chunk.getBlockState(new BlockPos(x, y, z)).isAir()) {
                                    lowest = y;
                                    break;
                                }
                            }
                        }
                        int ground = surface;
                        while (ground >= minY) {
                            BlockState s = chunk.getBlockState(new BlockPos(x, ground, z));
                            if (s.isSolid() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS)) break;
                            ground--;
                        }
                        columns.add(new Column(x, z, surface, top.getFluidState().is(FluidTags.WATER), lowest, ground));
                    }
                }
            }
        }

        /** Null when every surface is inside Bedrock's range, else the failure. */
        String bedrock() {
            int lo = columns.stream().filter(c -> c.lowest != null).mapToInt(c -> c.lowest).min().orElse(BEDROCK_MIN);
            int hi = max();
            return lo < BEDROCK_MIN || hi >= BEDROCK_MAX ? "blocks at y " + lo + ".." + hi + " leave Bedrock's " + BEDROCK_MIN
                    + ".." + BEDROCK_MAX : null;
        }

        double share(java.util.function.Predicate<Column> p) {
            return columns.isEmpty() ? 0 : (double) columns.stream().filter(p).count() / columns.size();
        }

        /** Share of non-ocean columns with a top solid block 8+ blocks off a column 2 blocks away on either axis. */
        double brokenShare() {
            Map<Long, Integer> byPos = new java.util.HashMap<>();
            columns.forEach(c -> byPos.put(BlockPos.asLong(c.x, 0, c.z), c.floor));
            long land = 0;
            long broken = 0;
            for (Column c : columns) {
                int[][] offsets = {{-2, 0}, {2, 0}, {0, -2}, {0, 2}};
                int highest = c.floor;
                boolean sharp = false;
                for (int[] o : offsets) {
                    Integer n = byPos.get(BlockPos.asLong(c.x + o[0], 0, c.z + o[1]));
                    if (n == null) continue;
                    highest = Math.max(highest, n);
                    sharp |= Math.abs(n - c.floor) >= 8;
                }
                if (highest < sea) continue;
                land++;
                if (sharp) broken++;
            }
            return land == 0 ? 0 : (double) broken / land;
        }

        private List<Column> dryLand() {
            return columns.stream().filter(c -> !c.water && c.surface > sea).toList();
        }

        int modalLand() {
            Map<Integer, Long> counts = dryLand().stream().collect(java.util.stream.Collectors.groupingBy(c -> c.floor,
                    java.util.stream.Collectors.counting()));
            int best = sea;
            long n = -1;
            for (Map.Entry<Integer, Long> e : counts.entrySet()) {
                long near = e.getValue() + counts.getOrDefault(e.getKey() - 1, 0L) + counts.getOrDefault(e.getKey() + 1, 0L);
                if (near > n) {
                    n = near;
                    best = e.getKey();
                }
            }
            return best;
        }

        double landShare(java.util.function.Predicate<Column> p) {
            List<Column> land = dryLand();
            return land.isEmpty() ? 0 : (double) land.stream().filter(p).count() / land.size();
        }

        /** Share of dry land with its ground within 1 block of {@link #modalLand()}, the commonest ground height. */
        double floorShare() {
            List<Column> land = dryLand();
            int mode = modalLand();
            return land.isEmpty() ? 0 : (double) land.stream().filter(c -> Math.abs(c.floor - mode) <= 1).count() / land.size();
        }

            /** The height a trench is measured on: the ground, or sea level over water. */
        int level(Column c) {
            return c.water ? Math.max(c.floor, sea - 1) : c.floor;
        }

        /** Share of columns, and the deepest, lying {@code depth}+ below both columns {@code off} blocks away on one axis. */
        int[] trench(int off, int depth) {
            Map<Long, Integer> byPos = new java.util.HashMap<>();
            columns.forEach(c -> byPos.put(BlockPos.asLong(c.x, 0, c.z), level(c)));
            int in = 0;
            int deepest = 0;
            int counted = 0;
            for (Column c : columns) {
                int h = level(c);
                int best = Integer.MIN_VALUE;
                for (int[] axis : new int[][] {{off, 0}, {0, off}}) {
                    Integer a = byPos.get(BlockPos.asLong(c.x - axis[0], 0, c.z - axis[1]));
                    Integer b = byPos.get(BlockPos.asLong(c.x + axis[0], 0, c.z + axis[1]));
                    if (a != null && b != null) best = Math.max(best, Math.min(a, b) - h);
                }
                if (best == Integer.MIN_VALUE) continue;
                counted++;
                deepest = Math.max(deepest, best);
                if (best >= depth) in++;
            }
            return new int[] {in, counted, deepest};
        }

        /** Share of columns carrying land, of those the share with only air below to minY + 8, and the share empty to the bottom. */
        double[] hanging() {
            List<Column> land = columns.stream().filter(c -> c.lowest != null).toList();
            long hung = land.stream().filter(c -> c.lowest > minY + 8).count();
            return new double[] {(double) land.size() / Math.max(1, columns.size()), land.isEmpty() ? 0 : (double) hung / land.size(),
                    share(c -> c.lowest == null)};
        }

        int min() {
            return columns.stream().mapToInt(c -> c.surface).min().orElse(minY);
        }

        int max() {
            return columns.stream().mapToInt(c -> c.surface).max().orElse(minY);
        }

        int median() {
            int[] s = columns.stream().mapToInt(c -> c.surface).sorted().toArray();
            return s.length == 0 ? minY : s[s.length / 2];
        }

        int landMedian() {
            int[] s = columns.stream().filter(c -> c.lowest != null).mapToInt(c -> c.surface).sorted().toArray();
            return s.length == 0 ? minY : s[s.length / 2];
        }

        /** The lowest surface within {@code blocks} of the area's centre on both axes. */
        int centreSurface(int blocks) {
            BlockPos c = area.centre();
            return columns.stream().filter(col -> Math.abs(col.x - c.getX()) <= blocks && Math.abs(col.z - c.getZ()) <= blocks)
                    .mapToInt(col -> col.surface).min().orElse(minY);
        }

        /** The median surface of the columns on the area's outer edge. */
        int borderMedian() {
            int x0 = columns.stream().mapToInt(c -> c.x).min().orElse(0);
            int x1 = columns.stream().mapToInt(c -> c.x).max().orElse(0);
            int z0 = columns.stream().mapToInt(c -> c.z).min().orElse(0);
            int z1 = columns.stream().mapToInt(c -> c.z).max().orElse(0);
            int[] s = columns.stream().filter(c -> c.x == x0 || c.x == x1 || c.z == z0 || c.z == z1).mapToInt(c -> c.surface)
                    .sorted().toArray();
            return s.length == 0 ? minY : s[s.length / 2];
        }

        String range() {
            return columns.stream().mapToInt(c -> c.surface).min().orElse(minY) + ".." + max() + ", median " + median();
        }

        String area() {
            return area.chunks().size() + " chunks, seed " + level.getSeed();
        }

        String centre() {
            return area.centre().getX() + "," + area.centre().getZ();
        }
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

    private static String verdict(boolean pass, String detail) {
        return (pass ? "PASS " : "FAIL ") + detail;
    }

    private static String pct(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
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
        Throwable t = error;
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
        return t.toString();
    }
}
