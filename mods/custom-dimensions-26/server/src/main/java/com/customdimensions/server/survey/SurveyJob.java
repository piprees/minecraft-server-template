package com.customdimensions.server.survey;

import com.customdimensions.core.definition.BiomeEntry;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.render.ColumnSamples;
import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.core.survey.Landforms;
import com.customdimensions.core.survey.PatchAnalysis;
import com.customdimensions.core.survey.PatchGrid;
import com.customdimensions.core.survey.Survey;
import com.customdimensions.core.survey.SurveyPlan;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.render.Sampler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.FluidState;

/**
 * {@code /customdim survey}'s work, as the one running {@link RenderJob}: the
 * noise render's coarse map over the generation border (landforms and the
 * adaptive picks), then every planned chunk generated one at a time on the
 * server thread (a forced ticket, polled each tick, never two at once: K6),
 * its sections copied there, scanned and analysed on the job's thread, and the
 * render cross-checked at every eighth column. Writes
 * {@code <game dir>/customdim-survey/<dimension>/}.
 */
public final class SurveyJob {
    /** Ticks a chunk may take to generate before the survey gives up on it. */
    static final int CHUNK_CAP_TICKS = 6000;
    /** Coarse map cells a side at most. */
    static final int COARSE_SIDE = 256;
    static final int CHECK_STRIDE = 8;

    private record Pending(ServerLevel level, ChunkPos pos, CompletableFuture<LevelChunk> done, int[] ticks) {}

    private static final List<Pending> PENDING = new ArrayList<>();
    private static boolean registered;
    private static final Map<BlockState, PatchGrid.BlockInfo> INFO = new ConcurrentHashMap<>();

    /** The survey's request. */
    public record Request(String slug, int patches, int size, Path outputDir) {}

    /** What a finished survey reports. */
    public record Result(String slug, Path dossier, int patches, long columns, long millis, int placesSurface, int landmarks) {}

    private SurveyJob() {}

    public static Path outputDir(String slug) {
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().resolve("customdim-survey").resolve(slug);
    }

    private static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(s -> poll());
    }

    private static void poll() {
        for (Pending p : List.copyOf(PENDING)) {
            LevelChunk chunk = p.level.getChunkSource().getChunkNow(p.pos.x(), p.pos.z());
            if (chunk != null) {
                PENDING.remove(p);
                p.done.complete(chunk);
            } else if (++p.ticks[0] >= CHUNK_CAP_TICKS) {
                PENDING.remove(p);
                p.level.setChunkForced(p.pos.x(), p.pos.z(), false);
                p.done.completeExceptionally(new IllegalStateException("chunk " + p.pos + " not generated after " + CHUNK_CAP_TICKS + " ticks"));
            }
        }
    }

    /**
     * Starts the survey of {@code slug} once {@code level} is open; empty when
     * another render or survey is running.
     */
    public static Optional<RenderJob> start(MinecraftServer server, LevelManager manager, String slug, Definition definition, Integer border,
                                            CompletableFuture<ServerLevel> level, Request request, Consumer<Integer> progress,
                                            Consumer<Result> finished, Consumer<Throwable> failed, Runnable close) {
        register();
        long units = (long) request.patches() * 4 / 3 + 1;
        return RenderJob.startWork(server, new RenderJob.Request(slug, null, null, request.outputDir()), units,
                (job, pool) -> run(server, manager, slug, definition, border, level.get(10, TimeUnit.MINUTES), request, job, progress),
                r -> {
                    close.run();
                    finished.accept(r);
                }, e -> {
                    close.run();
                    failed.accept(e);
                });
    }

    private static Result run(MinecraftServer server, LevelManager manager, String slug, Definition definition, Integer border, ServerLevel level,
                              Request request, RenderJob job, Consumer<Integer> progress) throws Exception {
        long start = System.nanoTime();
        Map<String, Long> timings = new LinkedHashMap<>();
        Sampler sampler = Sampler.forDimension(server, manager, slug, null);
        int radius = Math.max(64, Math.min(4096, border != null ? border : definition != null && definition.borders() != null
                && definition.borders().generation() != null ? definition.borders().generation() : 1024));
        int step = Math.max(8, (int) Math.ceil(2.0 * radius / COARSE_SIDE));
        RenderGrid grid = RenderGrid.of(0, 0, radius, step);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(RenderJob.defaultThreads(), r -> {
            Thread t = new Thread(r, "customdim-survey-coarse");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ColumnSamples coarse;
        try {
            coarse = job.sample(sampler, grid, pool, p -> {}, (px, py) -> true, null);
        } finally {
            pool.shutdown();
        }
        int side = grid.side();
        int[] heights = new int[side * side];
        byte[] kind = new byte[side * side];
        List<Double> land = new ArrayList<>();
        for (int pz = 0; pz < side; pz++) {
            for (int px = 0; px < side; px++) {
                int i = pz * side + px;
                heights[i] = coarse.height(px, pz);
                kind[i] = coarse.empty(px, pz) ? (byte) 2 : coarse.fluid(px, pz) != ColumnSamples.Fluid.NONE ? (byte) 1 : 0;
                if (kind[i] == 0) land.add((double) heights[i]);
            }
        }
        Landforms landforms = new Landforms(side, step, grid.x(0), grid.z(0), heights, kind);
        if (definition != null) landforms.withZones(com.customdimensions.core.zone.ZoneQuery.of(slug, definition).zones());
        timings.put("coarse", (System.nanoTime() - start) / 1_000_000);
        double[] lh = land.stream().mapToDouble(Double::doubleValue).toArray();
        int p50 = lh.length == 0 ? sampler.seaLevel() : (int) com.customdimensions.core.survey.Stats.percentile(lh, 50);
        int p95 = lh.length == 0 ? sampler.seaLevel() : (int) com.customdimensions.core.survey.Stats.percentile(lh, 95);
        int minY = level.getMinY(), height = level.getHeight();
        int[] slices = java.util.stream.IntStream.of(p50 - 40, p50 - 12, p50 + 6, p95 + 24)
                .map(y -> Math.max(minY + 1, Math.min(minY + height - 2, y))).distinct().toArray();

        long seed = Sampler.defaultSeed(server, manager, slug) ^ slug.hashCode();
        List<SurveyPlan.Patch> plan = new ArrayList<>(SurveyPlan.stratified(seed, radius, request.patches(), request.size()));
        SurveyPlan.addAdaptive(plan, landforms.picks(), radius, request.size(), Math.max(2, request.patches() / 3));

        DimensionType type = level.dimensionType();
        boolean ceiling = type.hasCeiling();
        int seaLevel = sampler.seaLevel();
        List<PatchAnalysis> analysed = new ArrayList<>();
        List<Survey.Check> checks = new ArrayList<>();
        long generateMillis = 0, scanMillis = 0;
        int done = 0;
        for (SurveyPlan.Patch patch : plan) {
            long t0 = System.nanoTime();
            PatchGrid g = new PatchGrid(patch, minY, height, ceiling);
            Map<String, Holder<Biome>> biomeHolders = new LinkedHashMap<>();
            for (int cz = 0; cz < patch.size(); cz++) {
                for (int cx = 0; cx < patch.size(); cx++) {
                    ChunkPos pos = new ChunkPos(patch.chunkX() + cx, patch.chunkZ() + cz);
                    Snapshot snap = generate(server, level, pos);
                    long t1 = System.nanoTime();
                    scan(g, snap, cx * 16, cz * 16, biomeHolders, level);
                    scanMillis += (System.nanoTime() - t1) / 1_000_000;
                }
            }
            for (int z = 0; z < g.side; z++) {
                for (int x = 0; x < g.side; x++) {
                    int y = g.topSolid(x, z);
                    if (y < 0) continue;
                    Holder<Biome> b = biomeHolders.get(g.biomeAt(x, Math.min(height - 1, y + 1), z));
                    if (b == null) continue;
                    Biome.Precipitation p = b.value().getPrecipitationAt(new BlockPos(g.originX + x, minY + y + 1, g.originZ + z), seaLevel);
                    g.setPrecipitation(x, z, p == Biome.Precipitation.SNOW ? 2 : p == Biome.Precipitation.RAIN ? 1 : 0);
                }
            }
            long t2 = System.nanoTime();
            PatchAnalysis a = PatchAnalysis.of(g, Survey.COMMON, slices);
            analysed.add(a);
            for (int z = CHECK_STRIDE / 2; z < a.side; z += CHECK_STRIDE) {
                for (int x = CHECK_STRIDE / 2; x < a.side; x += CHECK_STRIDE) {
                    int c = a.column(x, z);
                    if (a.renderSurface[c] == PatchAnalysis.NONE) continue;
                    Sampler.Column s = sampler.sample(g.originX + x, g.originZ + z);
                    checks.add(new Survey.Check(g.originX + x, g.originZ + z, s.height(), s.biomeId(), a.renderSurface[c] , a.renderBiome[c]));
                }
            }
            scanMillis += (System.nanoTime() - t2) / 1_000_000;
            generateMillis += (System.nanoTime() - t0) / 1_000_000;
            done++;
            job.advance(1, progress::accept);
            if (done % 6 == 0) {
                CustomDimensions.LOG.info("survey {}: {}/{} patches in {} ms", slug, done, plan.size(), (System.nanoTime() - start) / 1_000_000);
            }
        }
        timings.put("patches", generateMillis - scanMillis);
        timings.put("scanAndAnalyse", scanMillis);
        Survey.Meta meta = new Survey.Meta("adventure:" + slug, Sampler.defaultSeed(server, manager, slug), minY, height, seaLevel, ceiling,
                atmosphere(server, type, definition), listed(definition), treeBiomes(server), plan, timings);
        long t3 = System.nanoTime();
        JsonObject dossier = Survey.dossier(meta, landforms, analysed, checks);
        dossier.addProperty("dimension", level.dimension().identifier().toString());
        timings.put("dossier", (System.nanoTime() - t3) / 1_000_000);
        long total = (System.nanoTime() - start) / 1_000_000;
        timings.put("total", total);
        dossier.add("timingsMillis", new Gson().toJsonTree(timings));
        Path path = Survey.write(request.outputDir(), dossier, analysed, landforms);
        long columns = analysed.stream().mapToLong(p -> (long) p.side * p.side).sum();
        int types = dossier.getAsJsonObject("places").getAsJsonObject("surface").getAsJsonArray("types").size();
        int landmarks = 0;
        for (String l : List.of("surface", "sky", "underground", "roof")) {
            landmarks += dossier.getAsJsonObject("places").getAsJsonObject(l).getAsJsonArray("landmarks").size();
        }
        CustomDimensions.LOG.info("surveyed {}: {} patches, {} columns in {} ms (coarse {} ms) -> {}", slug, analysed.size(), columns, total,
                timings.get("coarse"), path);
        return new Result(slug, path, analysed.size(), columns, total, types, landmarks);
    }

    /** A generated chunk's copied sections and structure starts, taken on the server thread. */
    private record Snapshot(List<PalettedContainer<BlockState>> states, List<PalettedContainer<Holder<Biome>>> biomes, List<String> starts) {}

    private static Snapshot generate(MinecraftServer server, ServerLevel level, ChunkPos pos) throws Exception {
        CompletableFuture<LevelChunk> done = new CompletableFuture<>();
        server.execute(() -> {
            level.setChunkForced(pos.x(), pos.z(), true);
            PENDING.add(new Pending(level, pos, done, new int[1]));
        });
        LevelChunk chunk = done.get(10, TimeUnit.MINUTES);
        return CompletableFuture.supplyAsync(() -> {
            List<PalettedContainer<BlockState>> states = new ArrayList<>();
            List<PalettedContainer<Holder<Biome>>> biomes = new ArrayList<>();
            for (LevelChunkSection s : chunk.getSections()) {
                states.add(s.getStates().copy());
                PalettedContainerRO<Holder<Biome>> b = s.getBiomes();
                biomes.add(b.copy());
            }
            List<String> starts = new ArrayList<>();
            var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            chunk.getAllStarts().forEach((structure, startPiece) -> {
                if (startPiece != null && startPiece.isValid()) {
                    starts.add(registry.getResourceKey(structure).map(k -> k.identifier().toString()).orElse("unregistered"));
                }
            });
            level.setChunkForced(pos.x(), pos.z(), false);
            return new Snapshot(states, biomes, starts);
        }, server).get(10, TimeUnit.MINUTES);
    }

    private static void scan(PatchGrid g, Snapshot snap, int ox, int oz, Map<String, Holder<Biome>> holders, ServerLevel level) {
        IdentityHashMap<BlockState, Integer> local = new IdentityHashMap<>();
        IdentityHashMap<Holder<Biome>, Integer> localBiome = new IdentityHashMap<>();
        for (int i = 0; i < snap.states().size(); i++) {
            PalettedContainer<BlockState> states = snap.states().get(i);
            int baseY = i * 16;
            for (int sy = 0; sy < 16; sy++) {
                int y = baseY + sy;
                if (y >= g.height) break;
                for (int sz = 0; sz < 16; sz++) {
                    for (int sx = 0; sx < 16; sx++) {
                        BlockState state = states.get(sx, sy, sz);
                        if (state.isAir()) continue;
                        Integer idx = local.get(state);
                        if (idx == null) {
                            idx = g.block(info(state));
                            local.put(state, idx);
                        }
                        g.set(ox + sx, y, oz + sz, idx);
                    }
                }
            }
            PalettedContainer<Holder<Biome>> biomes = snap.biomes().get(i);
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        Holder<Biome> b = biomes.get(qx, qy, qz);
                        Integer idx = localBiome.get(b);
                        if (idx == null) {
                            String id = Sampler.id(b);
                            holders.putIfAbsent(id, b);
                            idx = g.biome(id);
                            localBiome.put(b, idx);
                        }
                        g.setBiome((ox >> 2) + qx, i * 4 + qy, (oz >> 2) + qz, idx);
                    }
                }
            }
        }
        snap.starts().forEach(g::addStructure);
    }

    static PatchGrid.BlockInfo info(BlockState state) {
        return INFO.computeIfAbsent(state, SurveyJob::describe);
    }

    private static PatchGrid.BlockInfo describe(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        FluidState fluid = state.getFluidState();
        String fluidId = fluid.isEmpty() ? null : fluid.is(FluidTags.WATER) ? "water" : fluid.is(FluidTags.LAVA) ? "lava" : "other";
        boolean foliage = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.WART_BLOCKS);
        boolean collision = !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        PatchGrid.Kind kind = state.isAir() ? PatchGrid.Kind.AIR : foliage ? PatchGrid.Kind.FOLIAGE
                : state.getBlock() instanceof LiquidBlock ? PatchGrid.Kind.FLUID : collision ? PatchGrid.Kind.SOLID
                : fluidId != null ? PatchGrid.Kind.FLUID : PatchGrid.Kind.PLANT;
        boolean snowIce = state.is(BlockTags.SNOW) || state.is(BlockTags.ICE);
        String plant = null;
        if (kind == PatchGrid.Kind.PLANT && !snowIce) {
            String path = id.substring(id.indexOf(':') + 1);
            if (state.is(BlockTags.FLOWERS) || state.is(BlockTags.SMALL_FLOWERS)) plant = "flower";
            else if (state.is(BlockTags.CROPS)) plant = "crop";
            else if (state.is(BlockTags.SAPLINGS)) plant = "sapling";
            else if (path.contains("mushroom") || path.contains("fungus")) plant = "mushroom";
            else if (path.contains("bush")) plant = "bush";
            else if (path.contains("grass") || path.contains("fern") || path.contains("roots")) plant = "grass";
            else plant = "other";
        }
        boolean bare = state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || id.equals("minecraft:end_stone");
        return new PatchGrid.BlockInfo(id, kind, state.is(BlockTags.LOGS), plant, snowIce, bare, fluidId);
    }

    private static JsonObject atmosphere(MinecraftServer server, DimensionType type, Definition definition) {
        JsonObject o = new JsonObject();
        o.addProperty("hasSkyLight", type.hasSkyLight());
        o.addProperty("hasCeiling", type.hasCeiling());
        o.addProperty("hasFixedTime", type.hasFixedTime());
        o.addProperty("ambientLight", type.ambientLight());
        try {
            o.add("registeredType", DimensionType.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), type)
                    .getOrThrow());
        } catch (RuntimeException e) {
            o.addProperty("registeredTypeError", e.toString());
        }
        if (definition != null) {
            Gson gson = new Gson();
            try {
                if (definition.atmosphere() != null) o.add("definitionAtmosphere", gson.toJsonTree(definition.atmosphere()));
                if (definition.dimensionType() != null) o.add("definitionDimensionType", gson.toJsonTree(definition.dimensionType()));
            } catch (RuntimeException e) {
                o.addProperty("definitionError", e.toString());
            }
        }
        o.addProperty("weather", "per biome: see surface.precipitationPercent");
        return o;
    }

    private static List<String> listed(Definition definition) {
        List<String> out = new ArrayList<>();
        if (definition != null && definition.biomes() != null) for (BiomeEntry b : definition.biomes()) out.add(b.id());
        return out;
    }

    /** Biomes one of whose placed features is named for trees or a forest. */
    private static Set<String> treeBiomes(MinecraftServer server) {
        Set<String> out = new HashSet<>();
        var biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        biomes.listElements().forEach(holder -> {
            for (HolderSet<PlacedFeature> step : holder.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> f : step) {
                    String key = f.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
                    if (key.contains("tree") || key.contains("forest")) {
                        out.add(holder.key().identifier().toString());
                        return;
                    }
                }
            }
        });
        return out;
    }
}
