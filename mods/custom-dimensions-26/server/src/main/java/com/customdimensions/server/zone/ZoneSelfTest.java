package com.customdimensions.server.zone;

import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.core.zone.ZoneQuery;
import com.customdimensions.core.zone.ZoneSpec;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.render.Sampler;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Zones, measured in the fixtures {@code selftest_stamps}, {@code selftest_sky_ring}
 * and {@code selftest_canyon_belt}: stamped biomes at their centres and a swapped
 * biome absent, read from generated chunks and from the generator's biome source;
 * the ring's sky terrain inside it and base terrain beyond its blend; a dry ring;
 * the belt's biomes; and across each showcase edge, the mean change in surface
 * height per block in the blend against inside and outside it.
 */
public final class ZoneSelfTest {
    static final int LOAD_CAP_TICKS = 6000;

    private record Load(ServerLevel level, List<ChunkPos> chunks, CompletableFuture<List<LevelChunk>> done, int[] ticks) {}

    private final MinecraftServer server;
    private final LevelManager manager;
    private final List<String> lines = new ArrayList<>();
    private final List<Load> loading = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "customdimensions-zone-selftest");
        t.setDaemon(true);
        return t;
    });

    private ZoneSelfTest(MinecraftServer server, LevelManager manager) {
        this.server = server;
        this.manager = manager;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) return CompletableFuture.completedFuture(List.of("FAIL zones: the level manager did not start"));
        ZoneSelfTest test = new ZoneSelfTest(server, manager);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.tick());
        int pause = holdAwake(server);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        chain = test.in(chain, "selftest_stamps", test::stamps);
        chain = test.in(chain, "selftest_sky_ring", test::ring);
        chain = test.in(chain, "selftest_canyon_belt", test::belt);
        return chain.handle((v, error) -> {
            if (error != null) test.lines.add("FAIL zones: the check chain broke: " + error);
            test.worker.shutdown();
            if (pause > 0 && server instanceof net.minecraft.server.dedicated.DedicatedServer dedicated) {
                dedicated.setPauseWhenEmptySeconds(pause);
            }
            return List.copyOf(test.lines);
        });
    }

    /** Keeps an empty server from pausing while the section runs; the setting it replaced, or -1. */
    private static int holdAwake(MinecraftServer server) {
        if (!(server instanceof net.minecraft.server.dedicated.DedicatedServer dedicated)) return -1;
        int seconds = dedicated.pauseWhenEmptySeconds();
        if (seconds > 0) dedicated.setPauseWhenEmptySeconds(0);
        return seconds;
    }

    private record Fixture(String slug, ServerLevel level, ZoneQuery zones, Sampler sampler) {}

    private CompletableFuture<Void> in(CompletableFuture<Void> chain, String slug, Function<Fixture, CompletableFuture<Void>> body) {
        return chain.thenCompose(v -> {
            LevelIndex.Entry entry = manager.index().lazy(slug).orElse(null);
            if (entry == null) {
                lines.add("FAIL zones " + slug + ": no fixture in the definitions");
                return CompletableFuture.completedFuture(null);
            }
            return manager.activate(slug).thenCompose(a -> {
                ServerLevel level = a.level();
                Sampler sampler = Sampler.of(slug, level.getChunkSource().getGenerator(), level.getMinY(), level.getHeight(),
                        level.getSeed(), "level", server.registryAccess());
                return body.apply(new Fixture(slug, level, ZoneQuery.of(slug, entry.definition()), sampler));
            }).thenCompose(x -> manager.deactivate(slug).thenAccept(t -> {})).exceptionally(error -> {
                lines.add("FAIL zones " + slug + ": " + error);
                return null;
            });
        });
    }

    // ---- stamps and swaps ---------------------------------------------------------------------

    private CompletableFuture<Void> stamps(Fixture f) {
        Map<String, int[]> centres = new java.util.LinkedHashMap<>();
        centres.put("minecraft:mushroom_fields", new int[] {400, 0});
        centres.put("minecraft:ice_spikes", new int[] {(int) Math.round(750 * Math.cos(Math.toRadians(135))), (int) Math.round(750 * Math.sin(Math.toRadians(135)))});
        centres.put("minecraft:bamboo_jungle", new int[] {-500 + 120, -500 + 73});
        List<ChunkPos> chunks = new ArrayList<>();
        centres.values().forEach(c -> chunks.add(new ChunkPos(c[0] >> 4, c[1] >> 4)));
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) chunks.add(new ChunkPos(dx, dz));
        return CompletableFuture.supplyAsync(() -> biomeCounts(f.sampler(), 0, 0, 1536, 32), worker).thenCompose(counts ->
                load(f.level(), chunks).thenAccept(loaded -> {
                    for (Map.Entry<String, int[]> c : centres.entrySet()) {
                        int x = c.getValue()[0];
                        int z = c.getValue()[1];
                        LevelChunk chunk = f.level().getChunk(x >> 4, z >> 4);
                        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
                        String chunkBiome = id(f.level().getBiome(new BlockPos(x, y, z)));
                        String sampled = f.sampler().biome(x, y, z).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                        lines.add(verdict(chunkBiome.equals(c.getKey()) && sampled.equals(c.getKey()), "zones stamp " + c.getKey()
                                + " at its centre " + x + ", " + z + " (y " + y + "): generated chunk " + chunkBiome + ", biome source " + sampled));
                    }
                    int plains = 0;
                    int quarts = 0;
                    Map<String, Integer> chunkBiomes = new HashMap<>();
                    for (LevelChunk chunk : loaded) {
                        for (int qx = 0; qx < 4; qx++) for (int qz = 0; qz < 4; qz++) for (int qy = 12; qy < 36; qy++) {
                            String b = id(chunk.getNoiseBiome((chunk.getPos().getMinBlockX() >> 2) + qx, qy, (chunk.getPos().getMinBlockZ() >> 2) + qz));
                            chunkBiomes.merge(b, 1, Integer::sum);
                            quarts++;
                            if (b.equals("minecraft:plains")) plains++;
                        }
                    }
                    int sampledPlains = counts.getOrDefault("minecraft:plains", 0);
                    int sunflower = counts.getOrDefault("minecraft:sunflower_plains", 0) + chunkBiomes.getOrDefault("minecraft:sunflower_plains", 0);
                    lines.add(verdict(plains == 0 && sampledPlains == 0 && sunflower > 0, "zones global swap: minecraft:plains in 0 of "
                            + quarts + " biome cells of " + loaded.size() + " generated chunks (y 48 to 143) and " + sampledPlains + " of "
                            + total(counts) + " biome-source columns over a 3,072-block square; minecraft:sunflower_plains, which replaces it, in "
                            + sunflower + (plains == 0 && sampledPlains == 0 ? "" : "; plains found in " + plains + " cells")));
                    loaded.forEach(c -> f.level().setChunkForced(c.getPos().x(), c.getPos().z(), false));
                }));
    }

    // ---- the sky-island ring -------------------------------------------------------------------

    private CompletableFuture<Void> ring(Fixture f) {
        ZoneSpec zone = f.zones().zones().getFirst();
        ZoneMask mask = zone.mask();
        double margin = 2 * mask.jitter();
        int cx = (int) mask.centreX();
        int cz = (int) mask.centreZ();
        CompletableFuture<Void> scan = CompletableFuture.runAsync(() -> {
            int inside = 0;
            int insideSky = 0;
            int insideLand = 0;
            int far = 0;
            int farSky = 0;
            Map<String, Integer> insideBiomes = new HashMap<>();
            for (int deg = 0; deg < 360; deg += 5) {
                for (int r = 1000; r <= 4000; r += 50) {
                    int x = cx + (int) Math.round(r * Math.cos(Math.toRadians(deg)));
                    int z = cz + (int) Math.round(r * Math.sin(Math.toRadians(deg)));
                    double outside = mask.shape().outside(x - cx, z - cz);
                    if (outside < -margin) {
                        var column = f.sampler().noiseColumn(x, z);
                        inside++;
                        if (open(column, f.sampler().minY())) insideSky++;
                        Sampler.Column c = f.sampler().sample(x, z);
                        if (c.height() > f.sampler().minY()) insideLand++;
                        insideBiomes.merge(c.biomeId(), 1, Integer::sum);
                    } else if (outside > mask.rampWidth() + margin) {
                        var column = f.sampler().noiseColumn(x, z);
                        far++;
                        if (open(column, f.sampler().minY())) farSky++;
                    }
                }
            }
            Set<String> wanted = Set.of("minecraft:cherry_grove", "minecraft:meadow");
            int zoneBiomes = insideBiomes.entrySet().stream().filter(e -> wanted.contains(e.getKey())).mapToInt(Map.Entry::getValue).sum();
            synchronized (lines) {
                lines.add(verdict(insideSky == inside && insideLand * 20 >= inside && farSky == 0, "zones ring terrain: open floor (no solid block from y "
                        + (f.sampler().minY() + 4) + " to " + OPEN_TOP + ") in " + insideSky + " of " + inside + " noise columns 2,128-2,872 blocks from spawn, island land in "
                        + insideLand + "; open floor in " + farSky + " of " + far + " columns under 1,672 or over 3,328 blocks (beyond blend + jitter)"));
                lines.add(verdict(zoneBiomes == inside, "zones ring biomes: " + zoneBiomes + " of " + inside + " columns inside the ring in its biomes "
                        + insideBiomes));
            }
        }, worker);
        double inner = ((com.customdimensions.core.zone.ZoneShape.Ring) mask.shape()).inner();
        double outer = ((com.customdimensions.core.zone.ZoneShape.Ring) mask.shape()).outer();
        CompletableFuture<Void> profiles = scan.thenRunAsync(() -> {
            String in = landEdge("ring inner edge", f, mask, inner, -1);
            String out = landEdge("ring outer edge", f, mask, outer, 1);
            synchronized (lines) {
                lines.add(in);
                lines.add(out);
            }
        }, worker);
        int stripZ = cz >> 4;
        List<ChunkPos> strip = new ArrayList<>();
        for (int x = cx + (int) inner - 400; x <= cx + (int) inner + 100; x += 16) strip.add(new ChunkPos(x >> 4, stripZ));
        for (int x = cx + (int) outer - 100; x <= cx + (int) outer + 400; x += 16) strip.add(new ChunkPos(x >> 4, stripZ));
        ChunkPos dry = new ChunkPos((cx + 2500) >> 4, stripZ);
        if (!strip.contains(dry)) strip.add(dry);
        return profiles.thenCompose(v -> load(f.level(), strip)).thenAccept(loaded -> {
            lines.add(spills(f, mask, loaded));
            LevelChunk chunk = f.level().getChunk(dry.x(), dry.z());
            int water = 0;
            int lava = 0;
            int floorBlocks = 0;
            int sea = f.level().getSeaLevel();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                for (int y = f.level().getMinY(); y < sea; y++) {
                    BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                    if (chunk.getFluidState(pos).is(FluidTags.WATER)) water++;
                    if (chunk.getFluidState(pos).is(FluidTags.LAVA)) lava++;
                }
                if (Sampler.solid(chunk.getBlockState(new BlockPos(chunk.getPos().getMinBlockX() + x, f.level().getMinY() + 8, chunk.getPos().getMinBlockZ() + z)))) floorBlocks++;
            }
            lines.add(verdict(water == 0 && lava == 0 && floorBlocks == 0, "zones ring dry: generated chunk " + dry + " inside the ring holds "
                    + water + " water and " + lava + " lava blocks below sea level " + sea + " and " + floorBlocks + " solid blocks at y "
                    + (f.level().getMinY() + 8)));
            loaded.forEach(c -> f.level().setChunkForced(c.getPos().x(), c.getPos().z(), false));
        });
    }

    /** Band width, in blocks of still-edge distance, of the land-share profile across an airborne edge. */
    static final int BAND = 16;
    /** The most the land share may fall from one band to the next toward the zone; see {@link #landEdge}. */
    static final double MAX_BAND_DROP = 0.25;

    /**
     * An airborne zone's edge breaks up gradually. Along transects every 2
     * degrees across the edge at radius {@code edge}, a column has land when
     * its noise column holds a solid block between four above the floor and
     * {@link #OPEN_TOP}, the base's ground. In {@link #BAND}-block bands of
     * still-edge distance, the land share falls from the base's (beyond the
     * blend and two jitters) to the zone's (two jitters inside the edge) and
     * no band falls more than {@link #MAX_BAND_DROP} below the one before it:
     * a hard edge drops the whole share in one band. The edge wanders: the
     * spread (10th to 90th percentile) of where each transect's ground ends is
     * at least the mask's jitter. {@code side} is -1 where the base lies at
     * smaller radii (the inner edge), 1 where it lies beyond.
     */
    private String landEdge(String name, Fixture f, ZoneMask mask, double edge, int side) {
        double margin = 2 * mask.jitter();
        int from = (int) (-margin - BAND * 2);
        int to = (int) (mask.rampWidth() + margin + BAND * 2);
        int bands = (to - from + BAND - 1) / BAND;
        int[] land = new int[bands];
        int[] total = new int[bands];
        List<Integer> ends = new ArrayList<>();
        int minY = f.sampler().minY();
        for (int deg = 0; deg < 360; deg += 2) {
            double cos = Math.cos(Math.toRadians(deg));
            double sin = Math.sin(Math.toRadians(deg));
            Integer end = null;
            for (int d = to - 1; d >= from; d -= 8) {
                double r = edge + side * d;
                int x = (int) Math.round(mask.centreX() + r * cos);
                int z = (int) Math.round(mask.centreZ() + r * sin);
                boolean ground = !open(f.sampler().noiseColumn(x, z), minY);
                int band = (d - from) / BAND;
                total[band]++;
                if (ground) land[band]++;
                if (!ground && end == null) end = d;
            }
            ends.add(end == null ? from : end);
        }
        double[] share = new double[bands];
        for (int b = 0; b < bands; b++) share[b] = total[b] == 0 ? 0 : land[b] / (double) total[b];
        double worst = 0;
        int worstBand = 0;
        for (int b = bands - 1; b > 0; b--) {
            double drop = share[b] - share[b - 1];
            if (drop > worst) {
                worst = drop;
                worstBand = b - 1;
            }
        }
        ends.sort(null);
        int p10 = ends.get(ends.size() / 10);
        int p90 = ends.get(ends.size() * 9 / 10);
        double base = share[bands - 1];
        double zone = share[0];
        StringBuilder profile = new StringBuilder();
        for (int b = bands - 1; b >= 0; b--) {
            profile.append(profile.isEmpty() ? "" : " ").append(from + b * BAND).append(':').append(Math.round(share[b] * 100));
        }
        boolean pass = base >= 0.95 && zone <= 0.05 && worst <= MAX_BAND_DROP && p90 - p10 >= mask.jitter();
        return verdict(pass, "zones " + name + " (" + f.slug() + ", radius " + (int) edge + "): land share from the base's " + pct(base)
                + " to the zone's " + pct(zone) + " (needs 95% and 5%), largest fall between " + BAND + "-block bands " + pct(worst)
                + " at " + (from + worstBand * BAND) + " blocks beyond the still edge (needs " + pct(MAX_BAND_DROP)
                + " or less); where the ground ends wanders " + p10 + " to " + p90 + " blocks beyond the still edge, 10th to 90th percentile over "
                + ends.size() + " transects (needs a spread of the jitter, " + (int) mask.jitter() + "); land % by band (blocks beyond the edge, base first) "
                + profile);
    }

    /**
     * Water and lava in generated chunks wherever the compiled (jittered) mask
     * is above zero: inside the zone (two jitters within the still edge or
     * deeper) and across its blends. Where the mask is zero the base's own
     * seas and lava are counted apart, not judged.
     */
    private String spills(Fixture f, ZoneMask mask, List<LevelChunk> chunks) {
        double margin = 2 * mask.jitter();
        int[] water = new int[3];
        int[] lava = new int[3];
        int[] columns = new int[3];
        List<String> where = new ArrayList<>();
        for (LevelChunk chunk : chunks) {
            for (int a = 0; a < 16; a++) for (int b = 0; b < 16; b++) {
                int x = chunk.getPos().getMinBlockX() + a;
                int z = chunk.getPos().getMinBlockZ() + b;
                double outside = mask.shape().outside(x - mask.centreX(), z - mask.centreZ());
                double compiled = f.sampler().zoneWeights(x, z)[0];
                int w = compiled <= 0 ? 2 : outside < -margin ? 0 : 1;
                columns[w]++;
                for (int y = f.level().getMinY(); y < f.level().getMaxY(); y++) {
                    var fluid = chunk.getFluidState(new BlockPos(x, y, z));
                    boolean isWater = fluid.is(FluidTags.WATER);
                    if (isWater) water[w]++;
                    else if (fluid.is(FluidTags.LAVA)) lava[w]++;
                    else continue;
                    if (w < 2 && where.size() < 8) where.add((isWater ? "water " : "lava ") + x + "," + y + "," + z + " mask "
                            + String.format(Locale.ROOT, "%.4f", compiled));
                }
            }
        }
        return verdict(columns[1] > 0 && water[0] + water[1] + lava[0] + lava[1] == 0, "zones ring no spill: " + water[1] + " water and "
                + lava[1] + " lava blocks in " + columns[1] + " generated columns of both blends (compiled mask above zero), " + water[0]
                + " and " + lava[0] + " in " + columns[0] + " inside the zone; the base's own, where the compiled mask is zero: " + water[2]
                + " and " + lava[2] + " in " + columns[2] + " columns; over " + chunks.size() + " chunks" + (where.isEmpty() ? "" : "; at " + where));
    }

    private static String pct(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    // ---- the canyon belt ----------------------------------------------------------------------

    /**
     * How many strips of generated chunks cross the belt's north edge, and
     * how far apart: one canyon wall running along a single strip would
     * decide its percentiles alone.
     */
    static final int BELT_STRIPS = 4;
    static final int BELT_STRIP_SPACING = 640;

    private CompletableFuture<Void> belt(Fixture f) {
        ZoneMask mask = f.zones().zones().getFirst().mask();
        double margin = 2 * mask.jitter();
        CompletableFuture<Void> scan = CompletableFuture.runAsync(() -> {
            Set<String> wanted = Set.of("minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands", "minecraft:desert");
            int inside = 0;
            int ok = 0;
            Map<String, Integer> seen = new HashMap<>();
            for (int x = -2400; x <= 2400; x += 32) {
                for (int z = 700 - 180; z <= 700 + 180; z += 24) {
                    if (mask.shape().outside(x - mask.centreX(), z - mask.centreZ()) >= -margin) continue;
                    String b = f.sampler().sample(x, z).biomeId();
                    inside++;
                    seen.merge(b, 1, Integer::sum);
                    if (wanted.contains(b)) ok++;
                }
            }
            synchronized (lines) {
                lines.add(verdict(ok == inside && inside > 0, "zones belt biomes: " + ok + " of " + inside + " columns inside the belt in its biomes " + seen));
            }
        }, worker);
        List<ChunkPos> strip = new ArrayList<>();
        for (int x = 0; x < BELT_STRIPS * BELT_STRIP_SPACING; x += BELT_STRIP_SPACING) {
            for (int z = -160; z <= 720; z += 16) strip.add(new ChunkPos(x >> 4, z >> 4));
        }
        return scan.thenCompose(v -> load(f.level(), strip)).thenAccept(loaded -> {
            lines.add(edge("belt north edge", f, mask, loaded, false));
            loaded.forEach(c -> f.level().setChunkForced(c.getPos().x(), c.getPos().z(), false));
        });
    }

    /**
     * The height difference between neighbouring columns along transects of
     * generated chunks (x for the ring, z for the belt), over every row of the
     * strips, in three windows by the compiled (jittered) mask at both columns:
     * inside the zone (1 at both), outside it (0 at both) and the blend (any
     * other pair), so the zone's own terrain where the jitter pushes the edge
     * out counts as the zone's, not the blend's. A seam or cliff is a step in the blend larger
     * than the steps on both sides: its 99th and 99.9th percentiles must not
     * exceed the rougher side's. The largest steps are reported, not judged:
     * the windows hold different numbers of pairs, and the largest of more
     * pairs is larger for the same terrain. The mean includes the ramp between
     * two surface heights, so it is reported, not judged. Surface:
     * MOTION_BLOCKING_NO_LEAVES.
     */
    private String edge(String name, Fixture f, ZoneMask mask, List<LevelChunk> chunks, boolean alongX) {
        Map<Long, Integer> heights = new HashMap<>();
        Map<Long, Double> weights = new HashMap<>();
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (LevelChunk chunk : chunks) {
            for (int a = 0; a < 16; a++) for (int b = 0; b < 16; b++) {
                int x = chunk.getPos().getMinBlockX() + a;
                int z = chunk.getPos().getMinBlockZ() + b;
                heights.put(key(x, z), chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a, b));
                weights.put(key(x, z), f.sampler().zoneWeights(x, z)[0]);
                int along = alongX ? x : z;
                lo = Math.min(lo, along);
                hi = Math.max(hi, along);
            }
        }
        double margin = 2 * mask.jitter();
        List<List<Integer>> steps = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        java.util.SortedSet<Integer> rows = new java.util.TreeSet<>();
        for (LevelChunk chunk : chunks) {
            int first = alongX ? chunk.getPos().getMinBlockZ() : chunk.getPos().getMinBlockX();
            for (int r = 0; r < 16; r++) rows.add(first + r);
        }
        int[] largestAt = {0, 0, 0, -1, 0, 0};
        List<int[]> blendSteps = new ArrayList<>();
        List<int[]> byWeight = new ArrayList<>();
        int[] zoneNearEdge = new int[2];
        for (int along = lo; along < hi; along++) {
            for (int row : rows) {
                int x = alongX ? along : row;
                int z = alongX ? row : along;
                Integer h0 = heights.get(key(x, z));
                Integer h1 = heights.get(alongX ? key(x + 1, z) : key(x, z + 1));
                if (h0 == null || h1 == null) continue;
                double outside = mask.shape().outside(x - mask.centreX(), z - mask.centreZ());
                double m0 = weights.get(key(x, z));
                double m1 = weights.get(alongX ? key(x + 1, z) : key(x, z + 1));
                int w = m0 >= 1 && m1 >= 1 ? 0 : m0 <= 0 && m1 <= 0 ? 2 : 1;
                steps.get(w).add(Math.abs(h1 - h0));
                if (w == 0 && outside >= -margin) {
                    zoneNearEdge[1]++;
                    if (Math.abs(h1 - h0) > 10) zoneNearEdge[0]++;
                }
                if (w == 1) {
                    blendSteps.add(new int[] {(int) Math.floor(outside), Math.abs(h1 - h0)});
                    byWeight.add(new int[] {(int) Math.floor(Math.min(m0, m1) * 80), Math.abs(h1 - h0)});
                }
                if (w == 1 && Math.abs(h1 - h0) > largestAt[3]) largestAt = new int[] {x, z, (int) Math.round(outside), Math.abs(h1 - h0), h0, h1};
            }
        }
        double[] mean = new double[3];
        int[] p99 = new int[3];
        int[] p999 = new int[3];
        int[] max = new int[3];
        for (int w = 0; w < 3; w++) {
            List<Integer> s = steps.get(w);
            s.sort(null);
            mean[w] = s.stream().mapToInt(Integer::intValue).average().orElse(0);
            p99[w] = s.isEmpty() ? 0 : s.get((int) ((s.size() - 1) * 0.99));
            p999[w] = s.isEmpty() ? 0 : s.get((int) ((s.size() - 1) * 0.999));
            max[w] = s.isEmpty() ? 0 : s.getLast();
        }
        boolean measured = !steps.get(0).isEmpty() && !steps.get(1).isEmpty() && !steps.get(2).isEmpty();
        boolean noCliff = p99[1] <= Math.max(p99[0], p99[2]) && p999[1] <= Math.max(p999[0], p999[2]);
        return verdict(measured && noCliff, "zones " + name + " (" + f.slug() + "): |surface step| between neighbouring columns, "
                + "99th / 99.9th percentile / largest / mean: blend " + p99[1] + " / " + p999[1] + " / " + max[1] + " / " + fmt(mean[1])
                + ", inside the zone " + p99[0] + " / " + p999[0] + " / " + max[0] + " / " + fmt(mean[0]) + ", outside " + p99[2] + " / "
                + p999[2] + " / " + max[2] + " / " + fmt(mean[2])
                + " blocks, over " + steps.get(1).size() + ", " + steps.get(0).size() + " and " + steps.get(2).size() + " pairs of " + chunks.size()
                + " generated chunks; mean surface inside " + fmt(meanHeight(heights, mask, -1e9, -margin)) + ", outside "
                + fmt(meanHeight(heights, mask, mask.rampWidth() + margin, 1e9)) + "; largest blend step at " + largestAt[0] + ","
                + largestAt[1] + ", " + largestAt[2] + " blocks beyond the still edge, " + largestAt[4] + " to " + largestAt[5]
                + " (" + biomeAt(f, largestAt[0], largestAt[4], largestAt[1]) + ", mask " + fmt(weights.getOrDefault(key(largestAt[0], largestAt[1]), -1.0))
                + " to " + biomeAt(f, alongX ? largestAt[0] + 1 : largestAt[0], largestAt[5], alongX ? largestAt[1] : largestAt[1] + 1) + ", mask "
                + fmt(weights.getOrDefault(alongX ? key(largestAt[0] + 1, largestAt[1]) : key(largestAt[0], largestAt[1] + 1), -1.0))
                + "; the noise alone: " + f.sampler().sample(largestAt[0], largestAt[1]).height() + " to "
                + f.sampler().sample(alongX ? largestAt[0] + 1 : largestAt[0], alongX ? largestAt[1] : largestAt[1] + 1).height() + ")"
                + "; blend steps over the rougher side's 99th percentile by 8-block band beyond the still edge (band:count/pairs) "
                + bands(blendSteps, Math.max(p99[0], p99[2])) + "; by the pair's lesser compiled mask (bin start x 80, 72 = 0.9 to 1; count/pairs) "
                + bands(byWeight, Math.max(p99[0], p99[2])) + "; the zone's own steps over 10 within two jitters of the still edge "
                + zoneNearEdge[0] + "/" + zoneNearEdge[1]);
    }

    private static String biomeAt(Fixture f, int x, int y, int z) {
        return f.level().getBiome(new BlockPos(x, y - 1, z)).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
    }

    private static String bands(List<int[]> steps, int over) {
        java.util.TreeMap<Integer, int[]> by = new java.util.TreeMap<>();
        for (int[] st : steps) {
            int[] c = by.computeIfAbsent(Math.floorDiv(st[0], 8) * 8, k -> new int[2]);
            c[1]++;
            if (st[1] > over) c[0]++;
        }
        StringBuilder sb = new StringBuilder();
        by.forEach((b, c) -> sb.append(sb.isEmpty() ? "" : " ").append(b).append(':').append(c[0]).append('/').append(c[1]));
        return sb.toString();
    }

    private static double meanHeight(Map<Long, Integer> heights, ZoneMask mask, double from, double to) {
        double sum = 0;
        int n = 0;
        for (Map.Entry<Long, Integer> e : heights.entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (long) e.getKey();
            double outside = mask.shape().outside(x - mask.centreX(), z - mask.centreZ());
            if (outside >= from && outside <= to) {
                sum += e.getValue();
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** The top of the band an open floor leaves empty: below any floating island, above most caves' reach. */
    static final int OPEN_TOP = 40;

    /** Whether a noise column holds no solid block from four above the floor to {@link #OPEN_TOP}. */
    private static boolean open(net.minecraft.world.level.NoiseColumn column, int minY) {
        for (int y = minY + 4; y <= OPEN_TOP; y++) {
            if (Sampler.solid(column.getBlock(y))) return false;
        }
        return true;
    }

    private static Map<String, Integer> biomeCounts(Sampler sampler, int cx, int cz, int radius, int step) {
        Map<String, Integer> counts = new HashMap<>();
        for (int x = cx - radius; x <= cx + radius; x += step) {
            for (int z = cz - radius; z <= cz + radius; z += step) {
                counts.merge(sampler.biome(x, 64, z).unwrapKey().map(k -> k.identifier().toString()).orElse("?"), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String id(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome) {
        return biome.unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered");
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private CompletableFuture<List<LevelChunk>> load(ServerLevel level, List<ChunkPos> chunks) {
        Load l = new Load(level, chunks, new CompletableFuture<>(), new int[1]);
        server.execute(() -> {
            chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), true));
            loading.add(l);
        });
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
                l.done.completeExceptionally(new IllegalStateException(got.size() + "/" + l.chunks.size() + " chunks loaded after " + LOAD_CAP_TICKS + " ticks"));
            }
        }
    }

    private static String verdict(boolean pass, String detail) {
        return (pass ? "PASS " : "FAIL ") + detail;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
