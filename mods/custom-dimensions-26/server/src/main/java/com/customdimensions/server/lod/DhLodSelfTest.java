package com.customdimensions.server.lod;

import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.render.Sampler;
import com.customdimensions.server.render.fast.LodSource;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The self-test's LOD section, run when Distant Horizons is installed
 * ({@code -Pdh}): in each named dimension ({@value #DIMENSIONS_PROPERTY},
 * by default a surface world, a roofed one and a sky one) it checks the
 * generator is bound, compares the coarse shortcut with whole columns,
 * pre-generates a {@value #PREGEN_CHUNKS}-chunk radius with {@code /dh pregen}
 * as {@code idle-tasks.sh} does and counts the requests the generator served,
 * then reads DH's stored LOD columns on a grid and generates the real chunks
 * there: surface heights (trees left out), the block under the surface and the
 * biome at it, compared. Every column is written to
 * {@code lod-check-<slug>.tsv} in the server directory. Runs on its own
 * thread, the game's work handed to the server thread.
 */
final class DhLodSelfTest {
    static final String DIMENSIONS_PROPERTY = "customdimensions.selftest.lod";
    static final String DEFAULT_DIMENSIONS = "overworld,the_nether,the_aviary";
    static final int PREGEN_CHUNKS = 32;
    /** Pass bars: heights within one block, the block under the surface, the biome. */
    static final double HEIGHTS_BAR = 0.90;
    static final double BLOCKS_BAR = 0.60;
    static final double BIOMES_BAR = 0.90;
    /** The coarse shortcut against whole columns: surface heights within one block, runs kept inside its window. */
    static final double SHORTCUT_HEIGHTS_BAR = 0.95;
    static final double SHORTCUT_RUNS_BAR = 0.90;
    static final long PREGEN_TIMEOUT_MS = 900_000;
    /** How long the comparison waits for the first pre-generated column to reach Distant Horizons' store. */
    static final long STORE_TIMEOUT_MS = 120_000;
    private static final Pattern DONE = Pattern.compile("(?i)complete|finished|not running|no pregen");

    private DhLodSelfTest() {}

    static CompletableFuture<List<String>> run(MinecraftServer server) {
        CompletableFuture<List<String>> out = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                out.complete(body(server));
            } catch (Throwable e) {
                out.complete(List.of("FAIL lod: " + e));
            }
        }, "customdimensions-lod-selftest");
        t.setDaemon(true);
        t.start();
        return out;
    }

    private static List<String> body(MinecraftServer server) throws Exception {
        List<String> lines = new ArrayList<>();
        LevelManager manager = LevelManager.get().orElseThrow(() -> new IllegalStateException("no level manager"));
        String named = System.getProperty(DIMENSIONS_PROPERTY, "");
        for (String slug : (named.isBlank() ? DEFAULT_DIMENSIONS : named).split(",")) {
            slug = slug.trim();
            if (slug.isEmpty()) continue;
            String s = slug;
            boolean lazy = manager.index().lazy().containsKey(s);
            ServerLevel level;
            if (lazy) {
                level = server.submit(() -> manager.activate(s)).join().join().level();
                server.submit(() -> manager.hold(s)).join();
            } else {
                level = server.getLevel(manager.index().baseWorlds().stream().filter(w -> w.slug().equals(s)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("no dimension " + s)).key());
            }
            try {
                lines.addAll(dimension(server, s, level));
            } finally {
                if (lazy) server.submit(() -> manager.release(s)).join();
            }
        }
        return lines;
    }

    private static List<String> dimension(MinecraftServer server, String slug, ServerLevel level) throws Exception {
        List<String> lines = new ArrayList<>();
        Optional<DhLodGenerators.Bound> bound = Optional.empty();
        for (long until = System.currentTimeMillis() + 60_000; bound.isEmpty() && System.currentTimeMillis() < until; ) {
            bound = DhLodGenerators.bound(level.dimension());
            if (bound.isEmpty()) Thread.sleep(500);
        }
        if (bound.isEmpty()) {
            lines.add("FAIL lod generator bound to " + slug + ": Distant Horizons loaded no level for it within 60 s");
            return lines;
        }
        DhLodGenerators.Bound b = bound.get();
        LodSource source = b.generator().source();
        lines.add("PASS lod generator bound to " + slug + " (reach " + source.reach() + " blocks)");

        LodSource.Shape shape = source.shape();
        for (int detail : new int[] {2, 4, 6}) {
            LodSource.Comparison c = source.compare(detail, 4);
            boolean ok = c.surfaceWithinOne() >= SHORTCUT_HEIGHTS_BAR && c.runsKept() >= SHORTCUT_RUNS_BAR;
            lines.add((ok ? "PASS" : "FAIL") + String.format(Locale.ROOT, " lod shortcut %s detail %d (%s): heights within 1 %.1f%%, exact %.1f%%, "
                            + "runs kept %.1f%%, runs per column %.2f whole / %.2f shortcut, %d columns; %s", slug, detail, c.mode(),
                    c.surfaceWithinOne() * 100, c.surfaceExact() * 100, c.runsKept() * 100, c.meanRunsFull(), c.meanRunsCoarse(), c.columns(),
                    shape.rule()));
        }

        String id = level.dimension().identifier().toString();
        long before = source.requests(0);
        say(server, "dh pregen stop");
        String started = say(server, "dh pregen start " + id + " 0 0 " + PREGEN_CHUNKS);
        String status = "";
        long until = System.currentTimeMillis() + PREGEN_TIMEOUT_MS;
        boolean done = false;
        while (!done && System.currentTimeMillis() < until) {
            Thread.sleep(5_000);
            status = say(server, "dh pregen status");
            done = DONE.matcher(status).find();
        }
        long served = source.requests(0) - before;
        boolean pregenOk = done && served > 0 && b.generator().failures() == 0;
        lines.add((pregenOk ? "PASS" : "FAIL") + " lod pregen " + slug + ": " + served + " full-detail requests served by customdimensions, "
                + b.generator().failures() + " failed, " + source.columnsServed() + " columns in all; start: " + started + "; status: " + status);

        lines.add(compareWithChunks(server, slug, level, b, source));
        return lines;
    }

    /** Whether Distant Horizons' store holds a column at {@code x}, {@code z}: it writes what the generator returns after the pre-generation ends. */
    private static boolean stored(DhLodGenerators.Bound b, int x, int z, IDhApiTerrainDataCache cache) {
        DhApiResult<DhApiTerrainDataPoint[]> lod = DhApi.Delayed.terrainRepo.getColumnDataAtBlockPos(b.wrapper(), x, z, cache);
        return lod.success && lod.payload != null && lod.payload.length > 0;
    }

    private static String compareWithChunks(MinecraftServer server, String slug, ServerLevel level, DhLodGenerators.Bound b,
                                            LodSource source) throws IOException {
        int minY = level.getMinY();
        int top = source.noiseTop();
        StringBuilder tsv = new StringBuilder("x\tz\tlodHeight\trealHeight\tlodBlock\trealBlock\tlodBiome\trealBiome\n");
        int n = 0;
        int heights = 0;
        int blocks = 0;
        int biomes = 0;
        int missing = 0;
        int surfaced = 0;
        int radius = PREGEN_CHUNKS * 16 - 72;
        int step = 2 * radius / 5;
        IDhApiTerrainDataCache cache = DhApi.Delayed.terrainRepo.createSoftCache();
        long until = System.currentTimeMillis() + STORE_TIMEOUT_MS;
        while (!stored(b, -radius, -radius, cache) && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (int x = -radius; x <= radius; x += step) {
            for (int z = -radius; z <= radius; z += step) {
                if (Math.abs(x) < 96 && Math.abs(z) < 96) continue;
                n++;
                DhApiResult<DhApiTerrainDataPoint[]> lod = DhApi.Delayed.terrainRepo.getColumnDataAtBlockPos(b.wrapper(), x, z, cache);
                if (!lod.success || lod.payload == null || lod.payload.length == 0) {
                    missing++;
                    tsv.append(x).append('\t').append(z).append("\tnone\t").append(lod.message).append('\n');
                    continue;
                }
                DhApiTerrainDataPoint[] points = lod.payload;
                IntFunction<BlockState> lodBlock = y -> {
                    for (DhApiTerrainDataPoint p : points) {
                        if (y >= p.bottomYBlockPos && y < p.topYBlockPos) {
                            return p.blockStateWrapper.getWrappedMcObject() instanceof BlockState s ? s : Blocks.AIR.defaultBlockState();
                        }
                    }
                    return Blocks.AIR.defaultBlockState();
                };
                int bx = x;
                int bz = z;
                LevelChunk chunk = server.submit(() -> level.getChunk(bx >> 4, bz >> 4)).join();
                IntFunction<BlockState> realBlock = y -> {
                    BlockState s = chunk.getBlockState(new BlockPos(bx, y, bz));
                    return tree(s) ? Blocks.AIR.defaultBlockState() : s;
                };
                int lodHeight = Sampler.surface(lodBlock, top, minY).height();
                int realHeight = Sampler.surface(realBlock, top, minY).height();
                String lodTop = name(lodBlock.apply(lodHeight - 1));
                String realTop = name(realBlock.apply(realHeight - 1));
                String lodBiome = biomeAt(points, lodHeight - 1);
                String realBiome = Sampler.id(level.getBiome(new BlockPos(bx, realHeight, bz)));
                if (Math.abs(lodHeight - realHeight) <= 1) heights++;
                if (lodTop.equals(realTop)) blocks++;
                if (realHeight > minY) {
                    surfaced++;
                    if (lodBiome.equals(realBiome)) biomes++;
                }
                tsv.append(x).append('\t').append(z).append('\t').append(lodHeight).append('\t').append(realHeight).append('\t')
                        .append(lodTop).append('\t').append(realTop).append('\t').append(lodBiome).append('\t').append(realBiome).append('\n');
            }
        }
        Path file = Path.of("lod-check-" + slug + ".tsv");
        Files.writeString(file, tsv);
        double d = Math.max(1, n), ds = Math.max(1, surfaced);
        boolean ok = missing == 0 && heights / d >= HEIGHTS_BAR && blocks / d >= BLOCKS_BAR && biomes / ds >= BIOMES_BAR;
        CustomDimensions.LOG.info("lod check {}: {} columns -> {}", slug, n, file.toAbsolutePath());
        return (ok ? "PASS" : "FAIL") + String.format(Locale.ROOT, " lod vs chunks %s: %d columns, %d without LOD data, heights within 1 %.1f%%, "
                        + "block under the surface %.1f%%, biome %.1f%% of %d columns with a surface (bars %.0f/%.0f/%.0f%%) -> %s", slug, n, missing, heights * 100 / d,
                blocks * 100 / d, biomes * 100 / ds, surfaced, HEIGHTS_BAR * 100, BLOCKS_BAR * 100, BIOMES_BAR * 100, file.toAbsolutePath());
    }

    /** A tree's or a huge fungus's block, which the noise does not draw: logs, stems, leaves, wart blocks and shroomlight. */
    private static boolean tree(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.WART_BLOCKS) || s.is(Blocks.SHROOMLIGHT);
    }

    private static String name(BlockState state) {
        return state.getBlock().builtInRegistryHolder().key().identifier().toString();
    }

    /** The biome of the data point holding block {@code y}, as an id. */
    private static String biomeAt(DhApiTerrainDataPoint[] points, int y) {
        for (DhApiTerrainDataPoint p : points) {
            if (y >= p.bottomYBlockPos && y < p.topYBlockPos) {
                Object biome = p.biomeWrapper.getWrappedMcObject();
                if (biome instanceof Holder<?> holder && holder.value() instanceof Biome) {
                    @SuppressWarnings("unchecked")
                    Holder<Biome> h = (Holder<Biome>) holder;
                    return Sampler.id(h);
                }
                return p.biomeWrapper.getSerialString();
            }
        }
        return "none";
    }

    /** What {@code command} said, its lines joined; run on the server thread. */
    private static String say(MinecraftServer server, String command) {
        return server.submit(() -> {
            List<String> out = new ArrayList<>();
            CommandSource capture = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    out.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture), command);
            return String.join(" | ", out);
        }).join();
    }
}
