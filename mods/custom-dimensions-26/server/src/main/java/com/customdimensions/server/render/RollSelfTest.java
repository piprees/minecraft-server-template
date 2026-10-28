package com.customdimensions.server.render;

import com.customdimensions.core.score.RollFacts;
import com.customdimensions.core.score.RollSpec;
import com.customdimensions.server.command.RollCommandAccess;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.level.PackInstaller;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * The roll part of the self-test (roller.md § Gates): the agreement gate for
 * three reference dimensions, structure-start candidates against the starts a
 * level records, a five-seed roll and its contact sheet, {@code pick} by seed
 * and by rank on a copy of a definition, the refusals, and (only with
 * {@code -PselfTestOnly=rollperf}) 100 overworld seeds timed against the
 * server's ticks.
 */
public final class RollSelfTest {
    /** Reference dimensions for the agreement gate: variant and slug. */
    static final List<String[]> REFERENCES = List.of(new String[] {"overworld", "overworld"},
            new String[] {"nether", "the_nether"}, new String[] {"floating islands", "the_aviary"});
    static final int AGREEMENT_SALT = 0x5EED;
    /** Blocks round 0,0 in which structure candidates are checked against recorded starts. */
    static final int STARTS_RADIUS = 384;
    static final int SMALL_ROLL = 5;
    static final int PERF_SEEDS = 100;
    static final int SETTLE_TICKS = 200;
    static final double PERF_LIMIT_SECONDS = 600;

    private final MinecraftServer server;
    private final LevelManager manager;
    private final List<String> lines = new ArrayList<>();

    private static volatile Meter meter;
    private static boolean registered;
    private static final List<Object[]> WAITING = new ArrayList<>();

    /** Server ticks seen while a roll runs and the longest of them. */
    private static final class Meter {
        long tickStart;
        boolean started;
        long count;
        long totalNanos;
        long longestNanos;
    }

    private RollSelfTest(MinecraftServer server, LevelManager manager) {
        this.server = server;
        this.manager = manager;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server, boolean performance) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) return CompletableFuture.completedFuture(List.of("FAIL roll: the level manager did not start"));
        register();
        RollSelfTest test = new RollSelfTest(server, manager);
        int pause = test.holdAwake();
        CompletableFuture<Void> chain = RenderSelfTest.agreement(server, "roll agreement", AGREEMENT_SALT, "agreement.tsv", REFERENCES)
                .thenAccept(test.lines::addAll)
                .thenCompose(v -> test.starts("overworld"))
                .thenCompose(v -> test.starts("the_nether"))
                .thenRun(test::refusals)
                .thenCompose(v -> test.smallRoll());
        if (performance) chain = chain.thenCompose(v -> test.settle()).thenCompose(v -> test.performance());
        return chain.handle((v, error) -> {
            if (error != null) test.lines.add("FAIL roll: the check chain broke: " + cause(error));
            test.restorePause(pause);
            return List.copyOf(test.lines);
        });
    }

    private static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.START_SERVER_TICK.register(s -> {
            Meter m = meter;
            if (m != null) {
                m.tickStart = System.nanoTime();
                m.started = true;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            Meter m = meter;
            if (m != null && m.started) {
                long nanos = System.nanoTime() - m.tickStart;
                m.count++;
                m.totalNanos += nanos;
                m.longestNanos = Math.max(m.longestNanos, nanos);
            }
            for (Object[] w : List.copyOf(WAITING)) {
                int[] left = (int[]) w[0];
                if (--left[0] <= 0) {
                    WAITING.remove(w);
                    @SuppressWarnings("unchecked") CompletableFuture<Void> done = (CompletableFuture<Void>) w[1];
                    done.complete(null);
                }
            }
        });
    }

    // ---- structure starts -----------------------------------------------------------------------

    /**
     * The starts the roller scores within {@link #STARTS_RADIUS} of 0,0
     * ({@link StructureStarts#find}, each confirmed by the structure's own
     * generation) against the starts the level records once those chunks
     * reach {@code structure_starts}: they agree when every recorded start is
     * scored and every scored start is recorded. A recorded start with no
     * scored one is listed as missed ({@code point} when the structure's biomes
     * exclude the chunk middle's: it picks its own point in the chunk); a scored
     * start the level does not record is listed as dropped, by structure.
     */
    private CompletableFuture<Void> starts(String slug) {
        String name = "roll structure starts " + slug;
        ServerLevel level = level(slug);
        if (level == null) {
            lines.add("FAIL " + name + ": " + slug + " is not loaded");
            return CompletableFuture.completedFuture(null);
        }
        Sampler sampler = Sampler.forDimension(server, manager, slug, null);
        return CompletableFuture.supplyAsync(() -> StructureStarts.find(sampler, sampler.structureState(), id -> true, 0, 0, STARTS_RADIUS))
                .thenComposeAsync(candidates -> {
            int r = Math.floorDiv(STARTS_RADIUS, 16) + 1;
            List<CompletableFuture<ChunkResult<ChunkAccess>>> futures = new ArrayList<>();
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) futures.add(level.getChunkSource().getChunkFuture(x, z, ChunkStatus.STRUCTURE_STARTS, true));
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApplyAsync(v -> {
                Map<String, net.minecraft.world.level.levelgen.structure.Structure> recorded = new TreeMap<>();
                var registry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
                for (var f : futures) {
                    ChunkAccess chunk = f.join().orElse(null);
                    if (chunk == null) continue;
                    for (Map.Entry<net.minecraft.world.level.levelgen.structure.Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
                        if (!e.getValue().isValid()) continue;
                        ChunkPos pos = chunk.getPos();
                        if (Math.hypot(pos.getMiddleBlockX(), pos.getMiddleBlockZ()) > STARTS_RADIUS) continue;
                        recorded.put(registry.getKey(e.getKey()) + "@" + pos.x() + "," + pos.z(), e.getKey());
                    }
                }
                Set<String> candidate = new HashSet<>();
                for (RollFacts.Start s : candidates) candidate.add(s.structure() + "@" + (s.x() >> 4) + "," + (s.z() >> 4));
                Map<String, Integer> dropped = new TreeMap<>();
                for (String c : candidate) if (!recorded.containsKey(c)) dropped.merge(c.substring(0, c.indexOf('@')), 1, Integer::sum);
                List<String> point = new ArrayList<>();
                List<String> missed = new ArrayList<>();
                for (Map.Entry<String, net.minecraft.world.level.levelgen.structure.Structure> e : recorded.entrySet()) {
                    if (candidate.contains(e.getKey())) continue;
                    String at = e.getKey().substring(e.getKey().indexOf('@') + 1);
                    ChunkPos pos = new ChunkPos(Integer.parseInt(at.split(",")[0]), Integer.parseInt(at.split(",")[1]));
                    boolean middleAllowed = e.getValue().biomes().contains(sampler.sample(pos.getMiddleBlockX(), pos.getMiddleBlockZ()).biome());
                    (middleAllowed ? missed : point).add(e.getKey());
                }
                int confirmed = candidate.size() - dropped.values().stream().mapToInt(Integer::intValue).sum();
                String summary = candidate.size() + " scored starts, " + recorded.size() + " recorded by generation, " + confirmed
                        + " in both (needs all of each)"
                        + (dropped.isEmpty() ? "" : ", scored but not recorded " + dropped)
                        + (point.isEmpty() ? "" : ", recorded off the chunk middle's biome (point) " + point)
                        + (missed.isEmpty() ? "" : ", recorded but not scored " + missed);
                boolean agree = missed.isEmpty() && point.isEmpty() && dropped.isEmpty() && !recorded.isEmpty();
                return (agree ? "PASS " : "FAIL ") + name + ": " + summary;
            }, server);
        }, server).handle((line, error) -> {
            lines.add(error == null ? line : "FAIL " + name + ": " + cause(error));
            return null;
        });
    }

    // ---- commands -------------------------------------------------------------------------------

    private void refusals() {
        List<String> roll = command("customdim roll overworld 5");
        List<String> pick = command("customdim pick overworld 1");
        List<String> count = command("customdim roll the_nether 0");
        List<String> unknown = command("customdim roll no_such_dimension");
        boolean chunks = roll.size() == 1 && roll.getFirst().contains("already has generated chunks")
                && pick.size() == 1 && pick.getFirst().contains("already has generated chunks");
        boolean ok = chunks && count.size() == 1 && count.getFirst().contains("outside 1 to " + Roll.MAX_COUNT)
                && unknown.size() == 1 && unknown.getFirst().contains("no_such_dimension") && RenderJob.running().isEmpty();
        lines.add((ok ? "PASS" : "FAIL") + " roll refusals: roll " + roll + ", pick " + pick + ", count " + count + ", unknown " + unknown);
    }

    /**
     * Five seeds through {@code /customdim roll} on a lazy dimension with
     * wants and no chunks; when the level section has opened every one, the
     * same five through {@link Roll} on the render job, which is the command's
     * path after its refusals.
     */
    private CompletableFuture<Void> smallRoll() {
        String free = rollable();
        String slug = free != null ? free : wanting();
        if (slug == null) {
            lines.add("FAIL roll small: no lazy dimension wants structures");
            return CompletableFuture.completedFuture(null);
        }
        Capture capture = new Capture();
        RenderJob job;
        if (free != null) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture),
                    "customdim roll " + slug + " " + SMALL_ROLL);
            job = RenderJob.running().orElse(null);
        } else {
            var def = manager.index().lazy(slug).orElseThrow().definition();
            List<Long> seeds = new ArrayList<>();
            for (long i = 1; i <= SMALL_ROLL; i++) seeds.add(i * 7_919L);
            Roll.Plan plan = new Roll.Plan(slug, def, def.spawn() == null ? 0 : def.spawn().x(), def.spawn() == null ? 0 : def.spawn().z(),
                    seeds, RenderJob.outputDir(slug));
            job = RenderJob.startWork(server, new RenderJob.Request(slug, null, null, plan.outputDir()), plan.units(),
                    (self, pool) -> Roll.run(self, plan, seed -> Sampler.forDimension(server, manager, slug, seed), pool, p -> {}),
                    outcome -> {}, error -> {}).orElse(null);
        }
        if (job == null) {
            lines.add("FAIL roll small " + slug + ": the roll did not start: " + capture.lines);
            return CompletableFuture.completedFuture(null);
        }
        String idle = free != null ? free : chunkFree();
        if (idle == null) {
            lines.add("FAIL roll refused while another runs: every lazy dimension holds chunks, so the chunk refusal answers first");
        } else {
            List<String> busy = command("customdim roll " + idle + " 3");
            lines.add((busy.size() == 1 && busy.getFirst().contains("one runs at a time") ? "PASS" : "FAIL")
                    + " roll refused while another runs: roll " + idle + " (no chunks) while " + slug + " rolls: " + busy);
        }
        String how = free != null ? "command" : "direct, every lazy dimension holds chunks";
        return job.finished().handleAsync((result, error) -> {
            if (error != null) {
                lines.add("FAIL roll small " + slug + ": " + cause(error) + " " + capture.lines);
                return null;
            }
            Roll.Outcome outcome = (Roll.Outcome) result;
            String problems = sheetProblems(outcome, SMALL_ROLL);
            boolean replied = free == null
                    || capture.lines.stream().anyMatch(l -> l.startsWith("Rolling " + SMALL_ROLL + " seeds of " + slug))
                    && capture.lines.stream().anyMatch(l -> l.startsWith("Rolled " + SMALL_ROLL + " seeds of " + slug));
            Roll.Rolled top = outcome.best().getFirst();
            lines.add((problems.isEmpty() && replied ? "PASS" : "FAIL") + " roll small " + slug + " (" + how + "): " + outcome.rolled()
                    + " seeds in " + outcome.millis() + " ms (" + outcome.columnsPerSecond() + " columns/s), best " + top.seed() + " "
                    + top.card().verdict() + " " + fmt(top.card().percentage()) + " -> " + outcome.sheet()
                    + (problems.isEmpty() ? "" : "; " + problems) + "; replies " + capture.lines);
            lines.add(pickRoundTrip(slug, outcome, free != null));
            return null;
        }, server);
    }

    /** The first lazy dimension, by slug, that wants structures. */
    private String wanting() {
        return manager.index().lazy().values().stream()
                .filter(e -> e.definition() != null && !RollSpec.of(e.definition()).wants().isEmpty() && !RollSpec.of(e.definition()).skip())
                .map(LevelIndex.Entry::slug).sorted().findFirst().orElse(null);
    }

    /** What is wrong with a finished roll's sheet, rankings and files, or empty. */
    static String sheetProblems(Roll.Outcome outcome, int expected) {
        List<String> problems = new ArrayList<>();
        try {
            Path dir = outcome.sheet().getParent();
            String html = Files.readString(outcome.sheet());
            int sections = html.split("<section ", -1).length - 1;
            if (sections != Math.min(expected, Roll.KEEP)) problems.add(sections + " sections");
            Matcher m = Pattern.compile("(?:src|href)=\"([^\"]+)\"").matcher(html);
            int linked = 0;
            while (m.find()) {
                linked++;
                String ref = m.group(1);
                if (ref.contains("://") || ref.startsWith("/")) problems.add("absolute link " + ref);
                else if (!Files.isRegularFile(dir.resolve(ref))) problems.add("missing " + ref);
            }
            if (linked < sections * 4) problems.add("only " + linked + " links");
            JsonObject ranking = JsonParser.parseString(Files.readString(dir.resolve(Roll.RANKING_FILE))).getAsJsonObject();
            if (ranking.getAsJsonArray("best").size() != sections) problems.add("roll.json lists " + ranking.getAsJsonArray("best").size());
            if (ranking.get("rolled").getAsInt() != expected) problems.add("roll.json rolled " + ranking.get("rolled"));
        } catch (IOException | RuntimeException e) {
            problems.add(e.toString());
        }
        return String.join("; ", problems);
    }

    /**
     * {@code pick} by seed and by rank into a copy of the definitions folder,
     * then the file read back; without {@code viaCommand}, pick's writer and
     * the ranking file directly.
     */
    private String pickRoundTrip(String slug, Roll.Outcome outcome, boolean viaCommand) {
        String name = "roll pick round trip " + slug;
        Path copy = null;
        try {
            copy = Files.createTempDirectory("customdim-pick");
            Path source = RollCommandAccess.definitionsDir().resolve(slug + ".json");
            Path target = copy.resolve(slug + ".json");
            Files.copy(source, target);
            JsonObject before = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
            RollCommandAccess.override(copy);
            long seed = outcome.best().getLast().seed();
            List<String> bySeed;
            List<String> byRank;
            List<String> noRank;
            JsonObject afterSeed;
            long top = outcome.best().getFirst().seed();
            if (viaCommand) {
                bySeed = command("customdim pick " + slug + " " + seed);
                afterSeed = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
                byRank = command("customdim pick " + slug + " rank 1");
                noRank = command("customdim pick " + slug + " rank " + Roll.KEEP);
            } else {
                RollCommandAccess.writeSeed(target, seed);
                afterSeed = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
                long ranked = Roll.ranked(outcome.sheet().getParent(), 1).orElseThrow();
                RollCommandAccess.writeSeed(target, ranked);
                bySeed = List.of("written after the next restart");
                byRank = List.of(String.valueOf(ranked));
                noRank = Roll.ranked(outcome.sheet().getParent(), Roll.KEEP).isEmpty() ? List.of("no rank") : List.of("rank found");
            }
            JsonObject afterRank = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
            JsonObject rest = afterRank.deepCopy();
            rest.remove("seed");
            JsonObject was = before.deepCopy();
            was.remove("seed");
            boolean ok = afterSeed.get("seed").getAsLong() == seed && afterRank.get("seed").getAsLong() == top && rest.equals(was)
                    && bySeed.size() == 1 && bySeed.getFirst().contains("after the next restart")
                    && byRank.size() == 1 && byRank.getFirst().contains(String.valueOf(top))
                    && List.copyOf(before.keySet()).equals(List.copyOf(afterRank.keySet()))
                    && (outcome.best().size() >= Roll.KEEP || noRank.size() == 1 && noRank.getFirst().contains("no rank"));
            return (ok ? "PASS " : "FAIL ") + name + (viaCommand ? " (command)" : " (direct)") + ": seed " + seed + " then rank 1 = " + top + ", every other field unchanged "
                    + rest.equals(was) + ", key order kept " + List.copyOf(before.keySet()).equals(List.copyOf(afterRank.keySet()))
                    + "; replies " + bySeed + " " + byRank + " " + noRank;
        } catch (IOException | RuntimeException e) {
            return "FAIL " + name + ": " + e;
        } finally {
            RollCommandAccess.override(null);
            if (copy != null) {
                try (Stream<Path> walk = Files.walk(copy)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a temporary folder left behind is harmless
                }
            }
        }
    }

    /** The first lazy dimension, by slug, that a roll does not skip and whose region folder holds no chunks. */
    private String chunkFree() {
        List<String> candidates = new ArrayList<>();
        for (LevelIndex.Entry e : manager.index().lazy().values()) {
            if (e.definition() == null || RollSpec.of(e.definition()).skip()) continue;
            if (PackInstaller.holdsChunks(manager.dimensionPath(e.key()).resolve("region"))) continue;
            candidates.add(e.slug());
        }
        candidates.sort(null);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    /** The first lazy dimension, by slug, that wants structures and whose region folder holds no chunks. */
    private String rollable() {
        List<String> candidates = new ArrayList<>();
        for (LevelIndex.Entry e : manager.index().lazy().values()) {
            if (e.definition() == null || RollSpec.of(e.definition()).wants().isEmpty() || RollSpec.of(e.definition()).skip()) continue;
            if (PackInstaller.holdsChunks(manager.dimensionPath(e.key()).resolve("region"))) continue;
            candidates.add(e.slug());
        }
        candidates.sort(null);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    // ---- performance ----------------------------------------------------------------------------

    private CompletableFuture<Void> settle() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        WAITING.add(new Object[] {new int[] {SETTLE_TICKS}, done});
        return done;
    }

    /** {@link #PERF_SEEDS} overworld seeds through {@link Roll} on the render pool, with the server's ticks measured. */
    private CompletableFuture<Void> performance() {
        String slug = "overworld";
        var base = manager.index().baseWorlds().stream().filter(w -> w.slug().equals(slug)).findFirst().orElse(null);
        if (base == null) {
            lines.add("FAIL roll performance: no overworld");
            return CompletableFuture.completedFuture(null);
        }
        var def = base.definition();
        int sx = def == null || def.spawn() == null ? 0 : def.spawn().x();
        int sz = def == null || def.spawn() == null ? 0 : def.spawn().z();
        List<Long> seeds = new ArrayList<>();
        java.util.Random random = new java.util.Random(PERF_SEEDS);
        while (seeds.size() < PERF_SEEDS) seeds.add(random.nextLong());
        Roll.Plan plan = new Roll.Plan(slug, def, sx, sz, seeds, RenderJob.outputDir(slug).resolve("rollperf"));
        Meter m = new Meter();
        long start = System.nanoTime();
        meter = m;
        var job = RenderJob.startWork(server, new RenderJob.Request(slug, null, null, plan.outputDir()), plan.units(),
                (self, pool) -> Roll.run(self, plan, seed -> Sampler.forDimension(server, manager, slug, seed), pool, p -> {}),
                outcome -> {}, error -> {});
        if (job.isEmpty()) {
            meter = null;
            lines.add("FAIL roll performance: another job is running");
            return CompletableFuture.completedFuture(null);
        }
        return job.get().finished().handleAsync((result, error) -> {
            meter = null;
            double seconds = (System.nanoTime() - start) / 1e9;
            if (error != null) {
                lines.add("FAIL roll performance: " + cause(error));
                return null;
            }
            Roll.Outcome outcome = (Roll.Outcome) result;
            double ticksPerSecond = m.count / seconds;
            double meanMillis = m.count == 0 ? 0 : m.totalNanos / 1e6 / m.count;
            double tps = Math.min(20, 1000 / Math.max(50, meanMillis));
            Map<String, Integer> verdicts = new TreeMap<>();
            outcome.best().forEach(r -> verdicts.merge(r.card().verdict().name(), 1, Integer::sum));
            String problems = sheetProblems(outcome, PERF_SEEDS);
            String summary = String.format(Locale.ROOT,
                    "%d seeds in %.1f s (limit %.0f s), %.2f s a seed, %d columns/s on %d threads, disc radius %d step %d, "
                            + "structures out to %d; %d ticks (%.2f a second), mean tick %.2f ms, TPS %.2f, longest tick %.1f ms; "
                            + "time split: sampling %.1f s, scored structure starts %.1f s, sheet %.1f s; %d start confirmations "
                            + "(%.1f s of worker time), longest %s; best %d %s; best ten verdicts %s -> %s",
                    outcome.rolled(), seconds, PERF_LIMIT_SECONDS, seconds / outcome.rolled(), outcome.columnsPerSecond(), outcome.threads(),
                    plan.disc().radius(), plan.disc().step(), plan.structureRadius(), m.count, ticksPerSecond, meanMillis, tps,
                    m.longestNanos / 1e6, outcome.sampleMillis() / 1e3, outcome.structureMillis() / 1e3, outcome.sheetMillis() / 1e3,
                    outcome.starts().count(), outcome.starts().nanos() / 1e9, outcome.starts().top(5), outcome.best().getFirst().seed(), fmt(outcome.best().getFirst().card().percentage()),
                    verdicts, outcome.sheet());
            boolean ok = seconds < PERF_LIMIT_SECONDS && tps >= 19 && ticksPerSecond >= 19 && problems.isEmpty();
            lines.add((ok ? "PASS" : "FAIL") + " roll performance: " + summary + (problems.isEmpty() ? "" : "; " + problems));
            return null;
        }, server);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ServerLevel level(String slug) {
        return manager.index().baseWorlds().stream().filter(w -> w.slug().equals(slug)).findFirst()
                .map(w -> server.getLevel(w.key())).orElse(null);
    }

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
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture), command);
        return capture.lines;
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

    private static String fmt(Double pct) {
        return pct == null ? "-" : String.format(Locale.ROOT, "%.1f%%", pct);
    }

    private static String cause(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
