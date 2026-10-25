package com.customdimensions.server.render;

import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.server.level.LevelManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The render part of the self-test. The accuracy gate samples 50 columns
 * within 256 blocks of 0,0 in five dimensions with the {@link Sampler}, then
 * generates those chunks in the real level and compares surface height and
 * surface biome column by column; every mismatch is put in a class, and a
 * column no class explains fails the line. Then the command's refusals, a
 * radius-1024 step-8 render of the overworld timed against the server's own
 * ticks, and a render under another seed.
 */
public final class RenderSelfTest {
    static final int COLUMNS = 50;
    static final int RADIUS = 256;
    static final int LOAD_CAP_TICKS = 6000;
    /** Variant and dimension of each accuracy check. */
    static final List<String[]> GATE = List.of(new String[] {"overworld", "overworld"},
            new String[] {"nether", "the_nether"}, new String[] {"end", "the_end"},
            new String[] {"floating islands", "the_aviary"}, new String[] {"caves", "the_underdark"});

    private record Load(ServerLevel level, List<ChunkPos> chunks, CompletableFuture<Map<ChunkPos, LevelChunk>> done, int[] ticks) {}

    private static final List<Load> LOADING = new ArrayList<>();
    private static long tickStart;
    private static volatile Ticks measuring;
    private static boolean registered;

    /** Server ticks seen while a render runs and the longest of them. */
    private static final class Ticks {
        boolean started;
        long count;
        long totalNanos;
        long longestNanos;
    }

    /** Ticks still to wait, and the future to complete when they have passed. */
    private record Wait(int[] left, CompletableFuture<Void> done) {}

    private static final List<Wait> WAITING = new ArrayList<>();
    /** Ticks to let the server catch up on before the timed render, so a backlog is not counted. */
    static final int SETTLE_TICKS = 200;

    private final MinecraftServer server;
    private final LevelManager manager;
    private final List<String> lines = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "customdim-render-selftest");
        t.setDaemon(true);
        return t;
    });

    private RenderSelfTest(MinecraftServer server, LevelManager manager) {
        this.server = server;
        this.manager = manager;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) return CompletableFuture.completedFuture(List.of("FAIL render: the level manager did not start"));
        register();
        return new RenderSelfTest(server, manager).run();
    }

    private static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.START_SERVER_TICK.register(s -> {
            tickStart = System.nanoTime();
            Ticks t = measuring;
            if (t != null) t.started = true;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            Ticks t = measuring;
            if (t != null && t.started) {
                long nanos = System.nanoTime() - tickStart;
                t.count++;
                t.totalNanos += nanos;
                t.longestNanos = Math.max(t.longestNanos, nanos);
            }
            poll();
            for (Wait w : List.copyOf(WAITING)) {
                if (--w.left()[0] <= 0) {
                    WAITING.remove(w);
                    w.done().complete(null);
                }
            }
        });
    }

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String[] gate : GATE) chain = chain.thenCompose(v -> accuracy(gate[0], gate[1]));
        chain = chain.thenRun(this::refusals).thenCompose(v -> performance()).thenCompose(v -> otherSeed());
        return chain.handle((v, error) -> {
            if (error != null) lines.add("FAIL render: the check chain broke: " + cause(error));
            restorePause(pause);
            worker.shutdown();
            return List.copyOf(lines);
        });
    }

    // ---- accuracy -------------------------------------------------------------------------------

    /** A sampled column with the noise column it came from. */
    private record Sampled(Sampler.Column column, NoiseColumn noise) {}

    /** A sampled column and what the generated chunk holds there. */
    private record Compared(Sampler.Column sampled, int realHeight, String realBiome, String mismatch) {}

    private CompletableFuture<Void> accuracy(String variant, String slug) {
        String name = "render accuracy " + variant + " (" + slug + ")";
        boolean lazy = manager.index().lazy().containsKey(slug);
        CompletableFuture<ServerLevel> open = lazy ? manager.activate(slug).thenApply(LevelManager.Activation::level)
                : CompletableFuture.completedFuture(server.getLevel(manager.index().baseWorlds().stream()
                .filter(w -> w.slug().equals(slug)).findFirst().orElseThrow().key()));
        return open.thenCompose(level -> CompletableFuture.supplyAsync(() -> {
            Sampler sampler = Sampler.forDimension(server, manager, slug, null);
            Random random = new Random(slug.hashCode());
            List<Sampled> columns = new ArrayList<>();
            while (columns.size() < COLUMNS) {
                int x = random.nextInt(2 * RADIUS + 1) - RADIUS;
                int z = random.nextInt(2 * RADIUS + 1) - RADIUS;
                if (x * x + z * z <= RADIUS * RADIUS) columns.add(new Sampled(sampler.sample(x, z), sampler.noiseColumn(x, z)));
            }
            return Map.entry(sampler, columns);
        }, worker).thenComposeAsync(sampled -> {
            Sampler sampler = sampled.getKey();
            Set<ChunkPos> chunks = new LinkedHashSet<>();
            sampled.getValue().forEach(c -> chunks.add(new ChunkPos(c.column().x() >> 4, c.column().z() >> 4)));
            List<ChunkPos> area = List.copyOf(chunks);
            return load(level, area).handle((loaded, error) -> {
                String line;
                try {
                    if (error != null) throw new IllegalStateException(cause(error));
                    line = compare(name, slug, level, sampler, sampled.getValue(), loaded);
                } catch (RuntimeException e) {
                    line = "FAIL " + name + ": " + e;
                }
                area.forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                lines.add(line);
                return null;
            });
        }, server)).thenCompose(v -> lazy ? manager.deactivate(slug).thenAccept(t -> {}) : CompletableFuture.completedFuture(null))
                .exceptionally(error -> {
                    lines.add("FAIL " + name + ": " + cause(error));
                    return null;
                });
    }

    private String compare(String name, String slug, ServerLevel level, Sampler sampler, List<Sampled> columns,
                           Map<ChunkPos, LevelChunk> chunks) {
        if (sampler.seed() != level.getSeed()) {
            return "FAIL " + name + ": sampler seed " + sampler.seed() + " (" + sampler.seedSource() + "), level seed " + level.getSeed();
        }
        List<Compared> compared = new ArrayList<>();
        Map<String, Integer> classes = new TreeMap<>();
        int heightExact = 0;
        int biomeExact = 0;
        int unexplained = 0;
        for (Sampled s : columns) {
            Sampler.Column c = s.column();
            LevelChunk chunk = chunks.get(new ChunkPos(c.x() >> 4, c.z() >> 4));
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int real = Sampler.surface(y -> chunk.getBlockState(pos.set(c.x(), y, c.z())), sampler.noiseTop(), level.getMinY()).height();
            String realBiome = Sampler.id(chunk.getNoiseBiome(QuartPos.fromBlock(c.x()), QuartPos.fromBlock(c.height()),
                    QuartPos.fromBlock(c.z())));
            String mismatch = "";
            if (real == c.height()) {
                heightExact++;
            } else {
                mismatch = classify(level, chunk, c, real, s.noise(), sampler.noiseTop());
                classes.merge(mismatch.substring(0, mismatch.indexOf(':')), 1, Integer::sum);
                if (mismatch.startsWith("unexplained")) unexplained++;
            }
            if (realBiome.equals(c.biomeId())) {
                biomeExact++;
            } else {
                unexplained++;
                classes.merge("biome", 1, Integer::sum);
                mismatch = (mismatch.isEmpty() ? "" : mismatch + "; ") + "biome: " + realBiome;
            }
            compared.add(new Compared(c, real, realBiome, mismatch));
        }
        int sameColumn = 0;
        var generator = level.getChunkSource().getGenerator();
        var randomState = level.getChunkSource().randomState();
        for (Sampled s : columns) {
            NoiseColumn game = generator.getBaseColumn(s.column().x(), s.column().z(), level, randomState);
            boolean same = true;
            for (int y = level.getMinY(); y <= sampler.maxY() && same; y++) same = game.getBlock(y) == s.noise().getBlock(y);
            if (same) sameColumn++;
        }
        if (sameColumn != columns.size()) {
            unexplained++;
            classes.merge("noise column differs from the level's", columns.size() - sameColumn, Integer::sum);
        }
        Path report = writeReport(slug, sampler, compared);
        String summary = columns.size() + " columns, seed " + sampler.seed() + ", height exact " + heightExact
                + ", biome exact " + biomeExact + ", noise column equal to the level generator's " + sameColumn
                + (classes.isEmpty() ? "" : ", mismatches " + classes) + " -> " + report;
        return (unexplained == 0 ? "PASS " : "FAIL ") + name + ": " + summary;
    }

    /**
     * Why a column's real surface is not the sampled one, as {@code classes: detail}:
     * the runs of blocks, from the noise top down past both surfaces, where
     * the chunk's solidity differs from the noise column's. {@code removed}:
     * solid in the noise, air in the chunk (carvers, features that dig).
     * {@code flooded}: solid in the noise, fluid in the chunk (lava pools,
     * lakes). {@code added}: open in the noise, solid in the chunk with a
     * block the noise never places (trees, plants, basalt columns).
     * {@code filled}: fluid in the noise, solid in the chunk with a block the
     * noise never places (ice, the surface rules' stone over a fluid).
     * {@code structure}: an added block inside a structure piece.
     * {@code unexplained}: a block the noise itself places stands where the
     * noise is open, or no block differs at all.
     */
    private static String classify(ServerLevel level, LevelChunk chunk, Sampler.Column c, int real, NoiseColumn noise, int top) {
        int minY = level.getMinY();
        int low = Math.max(minY, Math.min(c.height(), real) - 1);
        Set<net.minecraft.world.level.block.Block> noiseSolids = new java.util.HashSet<>();
        for (int y = top; y >= minY; y--) {
            BlockState n = noise.getBlock(y);
            if (Sampler.solid(n)) noiseSolids.add(n.getBlock());
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<String> runs = new ArrayList<>();
        Set<String> kinds = new java.util.TreeSet<>();
        String run = null;
        int runTop = 0;
        int runBottom = 0;
        for (int y = top; y >= low - 1; y--) {
            String key = null;
            if (y >= low) {
                BlockState n = noise.getBlock(y);
                BlockState r = chunk.getBlockState(pos.set(c.x(), y, c.z()));
                boolean ns = Sampler.solid(n);
                boolean rs = Sampler.solid(r);
                if (ns && !rs) {
                    key = (r.getFluidState().isEmpty() ? "removed " : "flooded ") + id(n) + ">" + id(r);
                } else if (!ns && rs) {
                    String change = id(n) + ">" + id(r);
                    if (level.structureManager().getStructureWithPieceAt(pos, h -> true).isValid()) key = "structure " + change;
                    else if (noiseSolids.contains(r.getBlock())) key = "unexplained " + change;
                    else if (!n.getFluidState().isEmpty()) key = "filled " + change;
                    else key = "added " + change;
                }
            }
            if (key != null && key.equals(run)) {
                runBottom = y;
                continue;
            }
            if (run != null) {
                runs.add(run + " y" + runBottom + (runTop == runBottom ? "" : ".." + runTop));
                kinds.add(run.substring(0, run.indexOf(' ')));
            }
            run = key;
            runTop = y;
            runBottom = y;
        }
        String detail = "sampled " + c.height() + ", real " + real + (runs.isEmpty() ? "" : ", " + String.join(", ", runs));
        if (runs.isEmpty()) return "unexplained: no block differs from the noise from y " + top + " to " + low + "; " + detail;
        if (kinds.contains("unexplained")) return "unexplained: " + detail;
        return String.join("+", kinds) + ": " + detail;
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private Path writeReport(String slug, Sampler sampler, List<Compared> compared) {
        Path out = RenderJob.outputDir(slug).resolve("accuracy.tsv");
        List<String> rows = new ArrayList<>();
        rows.add("# " + slug + " seed " + sampler.seed() + " (" + sampler.seedSource() + "), surface: " + RenderJob.SURFACE);
        rows.add("x\tz\tsampled\treal\troofed\tfluid\tsampledBiome\trealBiome\tmismatch");
        for (Compared c : compared) {
            Sampler.Column s = c.sampled();
            rows.add(s.x() + "\t" + s.z() + "\t" + s.height() + "\t" + c.realHeight() + "\t" + s.roofed() + "\t" + s.fluid()
                    + "\t" + s.biomeId() + "\t" + c.realBiome() + "\t" + c.mismatch());
        }
        try {
            Files.createDirectories(out.getParent());
            Files.write(out, rows);
        } catch (IOException e) {
            lines.add("FAIL render accuracy report " + out + ": " + e);
        }
        return out;
    }

    // ---- command, performance, seed ------------------------------------------------------------

    /** Captures what a command sends to its source. */
    private static final class Capture implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            lines.add(message.getString());
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
    }

    private List<String> command(String command) {
        Capture capture = new Capture();
        CommandSourceStack source = server.createCommandSourceStack().withSource(capture);
        server.getCommands().performPrefixedCommand(source, command);
        return capture.lines;
    }

    private void refusals() {
        List<String> radius = command("customdim render overworld 1 99999");
        List<String> step = command("customdim render overworld 1 1024 1");
        List<String> unknown = command("customdim render no_such_dimension");
        boolean ok = radius.size() == 1 && radius.get(0).contains("99999") && radius.get(0).contains(String.valueOf(RenderGrid.MAX_RADIUS))
                && step.size() == 1 && step.get(0).contains("smallest is 2")
                && unknown.size() == 1 && unknown.get(0).contains("no_such_dimension") && RenderJob.running().isEmpty();
        lines.add((ok ? "PASS" : "FAIL") + " render refusals: radius " + radius + ", step " + step + ", unknown " + unknown);
    }

    private CompletableFuture<Void> performance() {
        Wait wait = new Wait(new int[] {SETTLE_TICKS}, new CompletableFuture<>());
        WAITING.add(wait);
        return wait.done().thenCompose(v -> timedRender());
    }

    private CompletableFuture<Void> timedRender() {
        long seed = server.overworld().getSeed();
        Capture capture = new Capture();
        CommandSourceStack source = server.createCommandSourceStack().withSource(capture);
        Ticks ticks = new Ticks();
        long start = System.nanoTime();
        measuring = ticks;
        server.getCommands().performPrefixedCommand(source, "customdim render overworld " + seed + " 1024 8");
        long commandMicros = (System.nanoTime() - start) / 1000;
        RenderJob job = RenderJob.running().orElse(null);
        if (job == null) {
            measuring = null;
            lines.add("FAIL render performance: the render did not start: " + capture.lines);
            return CompletableFuture.completedFuture(null);
        }
        List<String> busy = command("customdim render the_nether");
        lines.add((busy.size() == 1 && busy.get(0).contains("overworld") ? "PASS" : "FAIL")
                + " render refused while another runs: " + busy);
        return job.future().handleAsync((result, error) -> {
            measuring = null;
            double seconds = (System.nanoTime() - start) / 1e9;
            if (error != null) {
                lines.add("FAIL render performance: " + cause(error));
                return null;
            }
            double ticksPerSecond = ticks.count / seconds;
            double meanMillis = ticks.count == 0 ? 0 : ticks.totalNanos / 1e6 / ticks.count;
            double tps = Math.min(20, 1000 / Math.max(50, meanMillis));
            String problems = files(result, 256, seed);
            boolean replied = capture.lines.stream().anyMatch(l -> l.startsWith("Rendering overworld with seed " + seed))
                    && capture.lines.stream().anyMatch(l -> l.startsWith("Rendered overworld seed " + seed));
            String summary = String.format(java.util.Locale.ROOT,
                    "radius 1024 step 8, %d columns in %d ms sampling (%d ms total) on %d threads, %d columns/s; "
                            + "%d ticks in %.1f s (%.2f a second), mean tick %.2f ms, TPS %.2f, longest tick %.1f ms; "
                            + "command took %d us on the server thread; replies %s",
                    result.grid().columns(), result.sampleMillis(), result.totalMillis(), result.threads(), result.columnsPerSecond(),
                    ticks.count, seconds, ticksPerSecond, meanMillis, tps, ticks.longestNanos / 1e6, commandMicros, capture.lines);
            boolean ok = tps >= 19 && ticksPerSecond >= 19 && problems.isEmpty() && replied;
            lines.add((ok ? "PASS" : "FAIL") + " render performance: " + summary + (problems.isEmpty() ? "" : "; " + problems));
            return null;
        }, server);
    }

    /** What is wrong with a finished render's files, or empty. */
    private static String files(RenderJob.Result result, int side, long seed) {
        List<String> problems = new ArrayList<>();
        try {
            for (Path image : result.files().images().values()) {
                var read = ImageIO.read(image.toFile());
                if (read == null || read.getWidth() != side || read.getHeight() != side) problems.add(image + " is not " + side + " square");
            }
            JsonObject sidecar = JsonParser.parseString(Files.readString(result.files().sidecar())).getAsJsonObject();
            if (sidecar.get("seed").getAsLong() != seed) problems.add("sidecar seed " + sidecar.get("seed"));
            if (sidecar.get("side").getAsInt() != side) problems.add("sidecar side " + sidecar.get("side"));
            if (sidecar.getAsJsonObject("legend").getAsJsonArray("biomes").isEmpty()) problems.add("sidecar has no biomes");
        } catch (IOException | RuntimeException e) {
            problems.add(e.toString());
        }
        return String.join("; ", problems);
    }

    /** A render under another seed samples a different world: heights differ at sampled columns. */
    private CompletableFuture<Void> otherSeed() {
        long own = server.overworld().getSeed();
        Supplier<String> check = () -> {
            Sampler a = Sampler.forDimension(server, manager, "overworld", null);
            Sampler b = Sampler.forDimension(server, manager, "overworld", own + 1);
            int differ = 0;
            for (int i = 0; i < 20; i++) {
                if (a.sample(i * 97, -i * 61).height() != b.sample(i * 97, -i * 61).height()) differ++;
            }
            return (differ > 0 && b.seed() == own + 1 && "argument".equals(b.seedSource()) ? "PASS" : "FAIL")
                    + " render under another seed: " + differ + " of 20 heights differ from seed " + own;
        };
        return CompletableFuture.supplyAsync(check, worker).handle((line, error) -> {
            lines.add(error == null ? line : "FAIL render under another seed: " + cause(error));
            return null;
        });
    }

    // ---- loading --------------------------------------------------------------------------------

    private static CompletableFuture<Map<ChunkPos, LevelChunk>> load(ServerLevel level, List<ChunkPos> chunks) {
        chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), true));
        Load l = new Load(level, chunks, new CompletableFuture<>(), new int[1]);
        LOADING.add(l);
        return l.done;
    }

    private static void poll() {
        for (Load l : List.copyOf(LOADING)) {
            Map<ChunkPos, LevelChunk> got = new java.util.HashMap<>();
            for (ChunkPos c : l.chunks) {
                LevelChunk chunk = l.level.getChunkSource().getChunkNow(c.x(), c.z());
                if (chunk != null) got.put(c, chunk);
            }
            if (got.size() == l.chunks.size()) {
                LOADING.remove(l);
                l.done.complete(got);
            } else if (++l.ticks[0] >= LOAD_CAP_TICKS) {
                LOADING.remove(l);
                l.done.completeExceptionally(new IllegalStateException(got.size() + "/" + l.chunks.size()
                        + " chunks loaded after " + LOAD_CAP_TICKS + " ticks"));
            }
        }
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
