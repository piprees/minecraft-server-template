package com.customdimensions.server.level;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Column scans of the materials, liquids and ores fixtures
 * ({@code server/src/selftest/definitions/selftest_{dry,flooded,palette,ores}.json})
 * over a 64x64 area around 0,0, with the overworld's same area as the control
 * where one is needed. Each line carries its counts.
 */
public final class MaterialsSelfTest {

    static final int LOAD_CAP_TICKS = 6000;
    /** Share of land columns whose top and filler blocks must match the palette; features and structures take the rest. */
    static final double SURFACE_SHARE = 0.95;
    /** Share for the block 20 below the top: ore veins and blobs replace some of it. */
    static final double DEEP_SHARE = 0.90;
    /** Share of open cells that must hold the expected fluid: geodes and dungeons keep air pockets. */
    static final double FLUID_SHARE = 0.95;
    static final int DEEP_OFFSET = 20;

    private record Load(ServerLevel level, List<ChunkPos> chunks, CompletableFuture<List<LevelChunk>> done, int[] ticks) {}

    private final MinecraftServer server;
    private final LevelManager manager;
    private final List<Load> loading = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();

    private MaterialsSelfTest(MinecraftServer server, LevelManager manager) {
        this.server = server;
        this.manager = manager;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) {
            return CompletableFuture.completedFuture(List.of("FAIL materials: the level manager did not start"));
        }
        MaterialsSelfTest test = new MaterialsSelfTest(server, manager);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.tick());
        return test.run();
    }

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        chain = step(chain, "materials dry", "selftest_dry", this::dry);
        chain = step(chain, "materials lava level", "selftest_dry", this::lavaLevel);
        chain = step(chain, "materials flooded", "selftest_flooded", this::flooded);
        chain = step(chain, "materials palette", "selftest_palette", this::palette);
        chain = step(chain, "materials per-biome palette", "selftest_palette", this::perBiome);
        chain = step(chain, "materials ore set", "selftest_ores", this::ores);
        return chain.handle((v, error) -> {
            if (error != null) lines.add("FAIL materials: the check chain broke: " + cause(error));
            restorePause(pause);
            return List.copyOf(lines);
        });
    }

    private CompletableFuture<Void> step(CompletableFuture<Void> chain, String name, String slug,
                                         Function<List<LevelChunk>, String> check) {
        String label = name + " (" + slug + ")";
        return chain.thenCompose(v -> manager.activate(slug).thenApply(LevelManager.Activation::level)
                .thenCompose(level -> load(level, area()).handle((chunks, error) -> {
                    String line;
                    try {
                        if (error != null) throw new IllegalStateException(cause(error));
                        line = check.apply(chunks);
                    } catch (RuntimeException e) {
                        line = "FAIL " + e;
                    }
                    area().forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                    lines.add(line.replaceFirst("^(PASS|FAIL) ", "$1 " + label + ": "));
                    return null;
                }))
                .thenCompose(x -> manager.deactivate(slug).thenAccept(t -> {}))
                .exceptionally(error -> {
                    lines.add("FAIL " + label + ": " + cause(error));
                    return null;
                }));
    }

    // ---- checks ---------------------------------------------------------------------------------

    /** No water, source or flowing or waterlogged, anywhere below sea level. */
    private String dry(List<LevelChunk> chunks) {
        ServerLevel level = (ServerLevel) chunks.getFirst().getLevel();
        int sea = level.getSeaLevel();
        long below = 0;
        long above = 0;
        long cells = 0;
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (LevelChunk chunk : chunks) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
                pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                if (chunk.getFluidState(pos).is(FluidTags.WATER)) {
                    if (y < sea) below++; else above++;
                    kinds.merge(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).getPath() + "@" + y + "/" + pos.getX() + "," + pos.getZ(), 1, Integer::sum);
                }
                if (y < sea) cells++;
            }
        }
        String counts = below + " water block(s) below sea level " + sea + " in " + cells + " cells of 64x64 columns; "
                + above + " at or above it" + (kinds.isEmpty() ? "" : "; " + kinds.keySet().stream().limit(12).toList());
        return below == 0 ? "PASS " + counts : "FAIL " + counts;
    }

    /** Open cells below the lava level are lava; those just above it are not. */
    private String lavaLevel(List<LevelChunk> chunks) {
        int lavaLevel = WorldgenRuntime.spec("adventure:selftest_dry").map(WorldgenRuntime.Spec::lavaLevel).orElse(Integer.MIN_VALUE);
        if (lavaLevel == Integer.MIN_VALUE) {
            return "FAIL no runtime lava level for adventure:selftest_dry";
        }
        long[] below = openCells(chunks, lavaLevel - 10, lavaLevel - 1, FluidTags.LAVA);
        long[] above = openCells(chunks, lavaLevel, lavaLevel + 10, FluidTags.LAVA);
        double share = below[1] == 0 ? 0 : (double) below[0] / below[1];
        double aboveShare = above[1] == 0 ? 0 : (double) above[0] / above[1];
        String counts = "lava in " + below[0] + "/" + below[1] + " open cells at y " + (lavaLevel - 10) + " to " + (lavaLevel - 1)
                + " (" + pct(share) + "), " + above[0] + "/" + above[1] + " at y " + lavaLevel + " to " + (lavaLevel + 10)
                + " (" + pct(aboveShare) + "); want at least " + pct(FLUID_SHARE) + " below and at most " + pct(1 - FLUID_SHARE) + " above";
        return below[1] > 0 && share >= FLUID_SHARE && aboveShare <= 1 - FLUID_SHARE ? "PASS " + counts : "FAIL " + counts;
    }

    /** Open cells from y -50 to two below sea level are water, against the overworld's aquifers. */
    private String flooded(List<LevelChunk> chunks) {
        ServerLevel level = (ServerLevel) chunks.getFirst().getLevel();
        int top = level.getSeaLevel() - 2;
        long[] ours = openCells(chunks, -50, top, FluidTags.WATER);
        long[] control = openCells(overworldChunks(), -50, top, FluidTags.WATER);
        double share = ours[1] == 0 ? 0 : (double) ours[0] / ours[1];
        String counts = "water in " + ours[0] + "/" + ours[1] + " open cells at y -50 to " + top + " (" + pct(share)
                + "); the overworld's aquifers: " + control[0] + "/" + control[1] + " ("
                + pct(control[1] == 0 ? 0 : (double) control[0] / control[1]) + "); want at least " + pct(FLUID_SHARE);
        return ours[1] > 0 && share >= FLUID_SHARE ? "PASS " + counts : "FAIL " + counts;
    }

    /** Top, filler and deep blocks of land columns in plains match the palette. */
    private String palette(List<LevelChunk> chunks) {
        int[] land = new int[1];
        int[] top = new int[1];
        int[] filler = new int[1];
        int[] deep = new int[2];
        java.util.Map<String, Integer> misses = new java.util.TreeMap<>();
        forLandColumns(chunks, (chunk, pos, biome) -> {
            if (!biome.equals("minecraft:plains")) return;
            land[0]++;
            if (chunk.getBlockState(pos).is(Blocks.MOSS_BLOCK)) top[0]++;
            else misses.merge(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).getPath(), 1, Integer::sum);
            if (chunk.getBlockState(pos.below()).is(Blocks.PACKED_MUD)) filler[0]++;
            BlockState d = chunk.getBlockState(pos.below(DEEP_OFFSET));
            if (d.isSolid()) {
                deep[1]++;
                if (d.is(Blocks.SMOOTH_BASALT)) deep[0]++;
            }
        });
        double t = share(top[0], land[0]);
        double f = share(filler[0], land[0]);
        double d = share(deep[0], deep[1]);
        String counts = land[0] + " plains land columns: moss top " + top[0] + " (" + pct(t) + "), packed mud below "
                + filler[0] + " (" + pct(f) + "), smooth basalt " + DEEP_OFFSET + " below " + deep[0] + "/" + deep[1]
                + " solid (" + pct(d) + "); want " + pct(SURFACE_SHARE) + ", " + pct(SURFACE_SHARE) + ", " + pct(DEEP_SHARE)
                + "; other tops " + misses;
        return land[0] > 0 && t >= SURFACE_SHARE && f >= SURFACE_SHARE && d >= DEEP_SHARE ? "PASS " + counts : "FAIL " + counts;
    }

    /** Red sand tops the desert's land columns and no plains column. */
    private String perBiome(List<LevelChunk> chunks) {
        int[] desert = new int[2];
        int[] plains = new int[2];
        java.util.Map<String, Integer> misses = new java.util.TreeMap<>();
        forLandColumns(chunks, (chunk, pos, biome) -> {
            boolean red = chunk.getBlockState(pos).is(Blocks.RED_SAND);
            if (biome.equals("minecraft:desert")) {
                desert[1]++;
                if (red) desert[0]++;
                else misses.merge(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).getPath(), 1, Integer::sum);
            } else if (biome.equals("minecraft:plains")) {
                plains[1]++;
                if (red) plains[0]++;
            }
        });
        double share = share(desert[0], desert[1]);
        String counts = "red sand tops " + desert[0] + "/" + desert[1] + " desert land columns (" + pct(share) + ") and "
                + plains[0] + "/" + plains[1] + " plains ones; want at least " + pct(SURFACE_SHARE) + " and none; other desert tops " + misses;
        return desert[1] > 0 && plains[1] > 0 && share >= SURFACE_SHARE && plains[0] == 0 ? "PASS " + counts : "FAIL " + counts;
    }

    /** The added ore is found; removed diamonds and the vein blocks are not, against the overworld's same area. */
    private String ores(List<LevelChunk> chunks) {
        long[] ours = oreCounts(chunks);
        long[] control = oreCounts(overworldChunks());
        String counts = "nether gold ore " + ours[0] + ", diamond ore " + ours[1] + ", raw iron or copper blocks " + ours[2]
                + " in 64x64 full columns; the overworld's same area: " + control[0] + ", " + control[1] + ", " + control[2];
        return ours[0] > 0 && ours[1] == 0 && ours[2] == 0 && control[1] > 0 ? "PASS " + counts : "FAIL " + counts;
    }

    // ---- scanning -------------------------------------------------------------------------------

    private interface ColumnVisitor {
        void visit(LevelChunk chunk, BlockPos top, String biome);
    }

    /** Each column whose highest solid block (past plants, leaves and logs) is dry land, with its biome there. */
    private static void forLandColumns(List<LevelChunk> chunks, ColumnVisitor visitor) {
        for (LevelChunk chunk : chunks) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int bx = chunk.getPos().getMinBlockX() + x;
                int bz = chunk.getPos().getMinBlockZ() + z;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx, y, bz);
                boolean wet = false;
                for (; y > chunk.getMinY(); y--) {
                    pos.setY(y);
                    BlockState s = chunk.getBlockState(pos);
                    if (!s.getFluidState().isEmpty()) {
                        wet = true;
                        break;
                    }
                    if (s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || !s.isSolid()) continue;
                    break;
                }
                if (wet || y <= chunk.getMinY()) continue;
                String biome = chunk.getLevel().getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("");
                visitor.visit(chunk, pos.immutable(), biome);
            }
        }
    }

    /** {matching, open} cell counts from {@code minY} to {@code maxY}: open is air or any fluid. */
    private static long[] openCells(List<LevelChunk> chunks, int minY, int maxY, net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> fluid) {
        long matching = 0;
        long open = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (LevelChunk chunk : chunks) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = minY; y <= maxY; y++) {
                pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                BlockState s = chunk.getBlockState(pos);
                if (s.isAir() || !s.getFluidState().isEmpty()) {
                    open++;
                    if (s.getFluidState().is(fluid)) matching++;
                }
            }
        }
        return new long[]{matching, open};
    }

    /** {nether gold ore, diamond ore of either kind, raw iron or copper blocks}. */
    private static long[] oreCounts(List<LevelChunk> chunks) {
        long[] out = new long[3];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (LevelChunk chunk : chunks) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) {
                pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                BlockState s = chunk.getBlockState(pos);
                if (s.is(Blocks.NETHER_GOLD_ORE)) out[0]++;
                else if (s.is(Blocks.DIAMOND_ORE) || s.is(Blocks.DEEPSLATE_DIAMOND_ORE)) out[1]++;
                else if (s.is(Blocks.RAW_IRON_BLOCK) || s.is(Blocks.RAW_COPPER_BLOCK)) out[2]++;
            }
        }
        return out;
    }

    /** The overworld's chunks of the same area, generated on the spot when absent. */
    private List<LevelChunk> overworldChunks() {
        ServerLevel overworld = server.overworld();
        List<LevelChunk> out = new ArrayList<>();
        for (ChunkPos c : area()) {
            out.add(overworld.getChunk(c.x(), c.z()));
        }
        return out;
    }

    private static List<ChunkPos> area() {
        List<ChunkPos> out = new ArrayList<>();
        for (int x = -2; x < 2; x++) for (int z = -2; z < 2; z++) out.add(new ChunkPos(x, z));
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

    private static double share(long part, long whole) {
        return whole == 0 ? 0 : (double) part / whole;
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
