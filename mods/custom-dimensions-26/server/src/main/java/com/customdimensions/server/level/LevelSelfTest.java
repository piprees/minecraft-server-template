package com.customdimensions.server.level;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The runtime-level part of the start-up self-test. It runs across ticks:
 * every lazy dimension is activated through the queue and unloaded, one at a
 * time, so it completes a future with its report lines rather than returning
 * them. {@code -Dcustomdimensions.selftest.persist=write|verify} adds the
 * two-boot persistence check.
 */
public final class LevelSelfTest {
    public static final String PERSIST_PROPERTY = "customdimensions.selftest.persist";
    static final String PERSIST_FILE = "customdimensions-persist.txt";
    static final String PERSIST_SLUG = "the_red_monument";
    /** Columns sampled for the persistence check, relative to the spawn chunk's corner. */
    private static final int[][] COLUMNS = {{0, 0}, {15, 15}, {0, 15}, {15, 0}, {7, 7}, {3, 11}, {11, 3}, {8, 13}};
    private static final long MIB = 1024 * 1024;

    private final MinecraftServer server;
    private final LevelManager manager;
    private final List<String> lines = new ArrayList<>();
    private boolean sampling;
    private long peakHeap;
    private int peakLoaded;
    private long lastTickNanos;
    private long worstTickNanos;
    private long worstClosingTickNanos;
    private boolean wasClosing;

    private LevelSelfTest(MinecraftServer server, LevelManager manager) {
        this.server = server;
        this.manager = manager;
    }

    /** Runs the checks; the future completes on the server thread with one line per check. */
    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) {
            return CompletableFuture.completedFuture(List.of("FAIL lazy level index: the level manager did not start"));
        }
        LevelSelfTest test = new LevelSelfTest(server, manager);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.sample());
        return test.run();
    }

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        lines.add(indexCheck());
        lines.add(borderCheck());
        String mode = System.getProperty(PERSIST_PROPERTY, "");
        CompletableFuture<Void> chain = mode.isEmpty() ? CompletableFuture.completedFuture(null) : persistence(mode);
        return chain
                .thenCompose(v -> activateAll())
                .thenRun(() -> lines.add(noneActiveCheck()))
                .thenRun(() -> lines.add(worldGenSettingsCheck()))
                .thenCompose(v -> deleteCheck())
                .thenCompose(v -> commandCheck())
                .handle((v, error) -> {
                    if (error != null) lines.add("FAIL runtime levels: the check chain broke: " + cause(error));
                    restorePause(pause);
                    return List.copyOf(lines);
                });
    }

    private void sample() {
        long now = System.nanoTime();
        if (!sampling) {
            lastTickNanos = 0;
            return;
        }
        if (lastTickNanos != 0) {
            long interval = now - lastTickNanos;
            if (wasClosing || manager.closing()) {
                worstClosingTickNanos = Math.max(worstClosingTickNanos, interval);
            } else {
                worstTickNanos = Math.max(worstTickNanos, interval);
            }
        }
        wasClosing = manager.closing();
        lastTickNanos = now;
        Runtime rt = Runtime.getRuntime();
        peakHeap = Math.max(peakHeap, rt.totalMemory() - rt.freeMemory());
        peakLoaded = Math.max(peakLoaded, manager.loadedLazyCount());
    }

    /** An empty server stops ticking after {@code pause-when-empty-seconds}, which would stall the run. */
    private int holdAwake() {
        if (!(server instanceof DedicatedServer dedicated)) return -1;
        int seconds = dedicated.pauseWhenEmptySeconds();
        if (seconds > 0) dedicated.setPauseWhenEmptySeconds(0);
        return seconds;
    }

    private void restorePause(int seconds) {
        if (seconds > 0 && server instanceof DedicatedServer dedicated) dedicated.setPauseWhenEmptySeconds(seconds);
    }

    private String indexCheck() {
        LevelIndex index = manager.index();
        int count = index.lazy().size();
        if (count == 0) return "FAIL lazy level index: no lazy stems in the server's resources";
        if (!index.problems().isEmpty() || index.paired() != count) {
            return "FAIL lazy level index: " + index.paired() + "/" + count + " decoded and paired; "
                    + String.join(" | ", index.problems());
        }
        return "PASS lazy level index: " + count + " stems decoded and paired with their definitions";
    }

    private String borderCheck() {
        List<String> worlds = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        for (LevelIndex.BaseWorld world : manager.index().baseWorlds()) {
            ServerLevel level = server.getLevel(world.key());
            if (world.borderRadius() == null) {
                wrong.add(world.slug() + ": no borders.player");
            } else if (level == null) {
                wrong.add(world.slug() + ": not loaded");
            } else {
                double want = 2.0 * world.borderRadius();
                double size = level.getWorldBorder().getSize();
                if (size != want) {
                    wrong.add(world.slug() + ": size " + size + ", want " + want);
                } else {
                    worlds.add(world.slug() + " " + (long) size);
                }
            }
        }
        return wrong.isEmpty()
                ? "PASS base world borders are 2 x borders.player: " + String.join(", ", worlds)
                : "FAIL base world borders are 2 x borders.player: " + String.join(" | ", wrong);
    }

    private record Cycle(String slug, long millis, boolean firstTime, int unloadTicks, String failure) {}

    private CompletableFuture<Void> activateAll() {
        List<String> slugs = List.copyOf(manager.index().lazy().keySet());
        List<Cycle> cycles = new ArrayList<>();
        int threadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();
        long filesBefore = openFiles();
        long wallStart = System.nanoTime();
        sampling = true;
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String slug : slugs) {
            chain = chain.thenCompose(v -> cycle(slug)).thenAccept(cycles::add);
        }
        return chain.thenRun(() -> {
            sampling = false;
            long wall = (System.nanoTime() - wallStart) / 1_000_000;
            System.gc();
            Runtime rt = Runtime.getRuntime();
            long finalHeap = rt.totalMemory() - rt.freeMemory();
            List<Cycle> ok = cycles.stream().filter(c -> c.failure == null).toList();
            List<String> failed = cycles.stream().filter(c -> c.failure != null).map(c -> c.slug + ": " + c.failure).toList();
            long total = ok.stream().mapToLong(Cycle::millis).sum();
            Cycle worst = ok.stream().max(java.util.Comparator.comparingLong(Cycle::millis)).orElse(null);
            int worstUnload = ok.stream().mapToInt(Cycle::unloadTicks).max().orElse(0);
            long firstTime = ok.stream().filter(Cycle::firstTime).count();
            String figures = ok.size() + "/" + slugs.size() + " activated and unloaded one at a time (" + firstTime
                    + " first-time); spawn chunk total " + String.format("%.1f", total / 1000.0) + " s, worst "
                    + (worst == null ? "-" : worst.millis + " ms (" + worst.slug + ")") + ", mean "
                    + (ok.isEmpty() ? "-" : total / ok.size() + " ms") + "; unload worst " + worstUnload
                    + " ticks; longest tick interval " + worstTickNanos / 1_000_000 + " ms opening, " + worstClosingTickNanos / 1_000_000
                    + " ms unloading; at most " + peakLoaded + " lazy level(s) loaded at once; heap peak " + peakHeap / MIB
                    + " MiB, final " + finalHeap / MIB + " MiB after System.gc(); threads " + threadsBefore + " -> "
                    + ManagementFactory.getThreadMXBean().getThreadCount() + ", open files " + filesBefore + " -> "
                    + openFiles() + "; wall " + String.format("%.1f", wall / 1000.0) + " s";
            if (failed.isEmpty() && peakLoaded <= 1 && !slugs.isEmpty()) {
                lines.add("PASS lazy activation of every dimension: " + figures);
            } else {
                lines.add("FAIL lazy activation of every dimension: " + figures
                        + (failed.isEmpty() ? "" : "; failed: " + String.join(" | ", failed)));
            }
        });
    }

    /** Activates one dimension, unloads it, and confirms it left {@code levelKeys()}. */
    private CompletableFuture<Cycle> cycle(String slug) {
        LevelIndex.Entry entry = manager.index().lazy(slug).orElseThrow();
        return manager.activate(slug).handle((a, error) -> a == null ? cause(error) : a)
                .thenCompose(result -> {
                    if (!(result instanceof LevelManager.Activation a)) {
                        return CompletableFuture.completedFuture(new Cycle(slug, 0, false, 0, "activation: " + result));
                    }
                    return manager.deactivate(slug).handle((ticks, error) -> {
                        if (error != null) return new Cycle(slug, a.millis(), a.firstTime(), 0, "unload: " + cause(error));
                        if (server.levelKeys().contains(entry.key())) {
                            return new Cycle(slug, a.millis(), a.firstTime(), ticks, "unload: still in levelKeys()");
                        }
                        return new Cycle(slug, a.millis(), a.firstTime(), ticks, null);
                    });
                });
    }

    private String noneActiveCheck() {
        List<String> active = new ArrayList<>();
        for (LevelIndex.Entry entry : manager.index().lazy().values()) {
            LevelManager.State state = manager.state(entry.slug());
            if (server.levelKeys().contains(entry.key()) || state != LevelManager.State.LAZY) {
                active.add(entry.slug() + " (" + state + ")");
            }
        }
        return active.isEmpty()
                ? "PASS no lazy level is active after the run: levelKeys() holds " + server.levelKeys().size() + " levels"
                : "FAIL no lazy level is active after the run: " + String.join(", ", active);
    }

    /** Fantasy marks its level stems as not saved; none may reach {@code world_gen_settings.dat}. */
    private String worldGenSettingsCheck() {
        String name = "world_gen_settings.dat holds no lazy level";
        try {
            server.saveEverything(true, true, true);
            Path file = server.getWorldPath(LevelResource.ROOT).resolve("data/minecraft/world_gen_settings.dat");
            String nbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).toString();
            List<String> found = manager.index().lazy().values().stream()
                    .map(e -> e.key().identifier().toString()).filter(nbt::contains).toList();
            int stems = nbt.split("generator", -1).length - 1;
            return found.isEmpty()
                    ? "PASS " + name + ": " + stems + " stems saved, none lazy"
                    : "FAIL " + name + ": " + String.join(", ", found);
        } catch (Throwable t) {
            return "FAIL " + name + ": " + t;
        }
    }

    /** Deletes the last lazy dimension other than the persistence one and checks nothing of it is left. */
    private CompletableFuture<Void> deleteCheck() {
        String name = "delete leaves no level, stem or folder";
        List<String> slugs = List.copyOf(manager.index().lazy().keySet());
        String slug = slugs.reversed().stream().filter(s -> !s.equals(persistSlug())).findFirst().orElse(null);
        if (slug == null) {
            lines.add("FAIL " + name + ": no lazy dimension to delete");
            return CompletableFuture.completedFuture(null);
        }
        LevelIndex.Entry entry = manager.index().lazy(slug).orElseThrow();
        return manager.activate(slug)
                .thenCompose(a -> manager.delete(slug))
                .handle((ticks, error) -> {
                    if (error != null) {
                        lines.add("FAIL " + name + ": " + slug + ": " + cause(error));
                        return null;
                    }
                    List<String> left = new ArrayList<>();
                    if (server.levelKeys().contains(entry.key())) left.add("still in levelKeys()");
                    if (Files.exists(manager.dimensionPath(entry.key()))) left.add("folder remains");
                    boolean stem = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM)
                            .containsKey(entry.key().identifier());
                    if (stem) left.add("level stem still registered");
                    lines.add(left.isEmpty()
                            ? "PASS " + name + ": " + slug + " deleted after " + ticks + " ticks"
                            : "FAIL " + name + ": " + slug + ": " + String.join(", ", left));
                    return null;
                });
    }

    /** {@code /customdim list|activate|unload|tp} from the console, checked against the manager. */
    private CompletableFuture<Void> commandCheck() {
        String name = "/customdim list, activate, unload and tp from the console";
        String slug = manager.index().lazy().isEmpty() ? null : manager.index().lazy().firstKey();
        if (slug == null) {
            lines.add("FAIL " + name + ": no lazy dimension");
            return CompletableFuture.completedFuture(null);
        }
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack console = server.createCommandSourceStack();
        try {
            int listed = dispatcher.execute("customdim list", console);
            if (listed != manager.index().lazy().size()) throw new IllegalStateException("list returned " + listed);
            dispatcher.execute("customdim activate " + slug, console);
            if (manager.state(slug) != LevelManager.State.QUEUED) throw new IllegalStateException("activate left " + manager.state(slug));
        } catch (Exception e) {
            lines.add("FAIL " + name + ": " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
        return manager.activate(slug).thenCompose(a -> {
            try {
                dispatcher.execute("customdim unload " + slug, console);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
            return manager.deactivate(slug);
        }).handle((ticks, error) -> {
            try {
                if (error != null) throw new IllegalStateException(cause(error));
                if (manager.state(slug) != LevelManager.State.LAZY) throw new IllegalStateException("unload left " + manager.state(slug));
                int tp = dispatcher.execute("customdim tp " + slug, console);
                if (tp != 0) throw new IllegalStateException("tp from the console returned " + tp);
                lines.add("PASS " + name + ": list " + manager.index().lazy().size() + ", " + slug
                        + " activated and unloaded, tp refused without a player");
            } catch (Exception e) {
                lines.add("FAIL " + name + ": " + e.getMessage());
            }
            return null;
        });
    }

    private String persistSlug() {
        return manager.index().lazy().containsKey(PERSIST_SLUG) ? PERSIST_SLUG
                : manager.index().lazy().isEmpty() ? null : manager.index().lazy().firstKey();
    }

    /**
     * {@code write} places a marker block, records the surface height at eight
     * columns of one dimension and unloads it; {@code verify}, on the next boot, reopens it and compares.
     */
    private CompletableFuture<Void> persistence(String mode) {
        String name = "persistence " + mode;
        String slug = persistSlug();
        if (slug == null || !(mode.equals("write") || mode.equals("verify"))) {
            lines.add("FAIL " + name + ": " + (slug == null ? "no lazy dimension" : "mode must be write or verify"));
            return CompletableFuture.completedFuture(null);
        }
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(PERSIST_FILE);
        return manager.activate(slug).thenApply(a -> {
            if (mode.equals("write")) placeMarker(a.level(), slug);
            return heights(a.level(), slug);
        }).thenCompose(heights -> {
            String line;
            try {
                if (mode.equals("write")) {
                    Files.write(file, heights);
                    line = "PASS " + name + ": " + slug + ", " + heights.size() + " columns recorded: "
                            + String.join("; ", heights);
                } else {
                    if (!Files.exists(file)) throw new IllegalStateException(file + " is missing; run write first");
                    List<String> recorded = Files.readAllLines(file);
                    long matching = heights.stream().filter(recorded::contains).count();
                    line = recorded.equals(heights)
                            ? "PASS " + name + ": " + slug + ", " + matching + "/" + heights.size() + " columns match"
                            : "FAIL " + name + ": " + slug + ", " + matching + "/" + heights.size()
                                    + " columns match; recorded " + recorded + ", now " + heights;
                }
            } catch (IOException | RuntimeException e) {
                line = "FAIL " + name + ": " + e;
            }
            lines.add(line);
            return manager.deactivate(slug).thenAccept(t -> {});
        }).exceptionally(error -> {
            lines.add("FAIL " + name + ": " + slug + ": " + cause(error));
            return null;
        });
    }

    /**
     * Places a block in the first sampled column, so the check also holds for a
     * void dimension, whose regenerated chunks would match without being saved.
     */
    private void placeMarker(ServerLevel level, String slug) {
        var chunk = LevelMaths.spawnChunk(manager.spawn(slug).orElse(null));
        int y = manager.spawn(slug).map(p -> p.y()).orElse(64);
        y = Math.clamp(y, level.getMinY(), level.getMaxY() - 1);
        level.setBlockAndUpdate(new BlockPos(chunk.x() * 16 + COLUMNS[0][0], y, chunk.z() * 16 + COLUMNS[0][1]),
                Blocks.GOLD_BLOCK.defaultBlockState());
    }

    /** {@code x z surface} at each sampled column of the spawn chunk. */
    private List<String> heights(ServerLevel level, String slug) {
        var chunk = LevelMaths.spawnChunk(manager.spawn(slug).orElse(null));
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (loaded == null) throw new IllegalStateException("spawn chunk not loaded");
        return Stream.of(COLUMNS).map(c -> {
            int x = chunk.x() * 16 + c[0];
            int z = chunk.z() * 16 + c[1];
            return x + " " + z + " " + loaded.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        }).toList();
    }

    private static long openFiles() {
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            return fds.count();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static String cause(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
