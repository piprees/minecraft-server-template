package com.customdimensions.web;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.InputHash;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.SeedBank;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Keeps the top of each dimension's board drawn, and nothing else.
 *
 * <p>A map costs orders of magnitude more than a measurement, and only the
 * handful of candidates at the top is ever opened — so rendering is not part
 * of the search. It runs beside it, reconciling against the board: the best
 * {@link #KEEP} seeds get a low-res map first so the whole shortlist is
 * lookable quickly, then a high-res one each.
 *
 * <p>The board moves while a roll runs. A seed pushed out of the top has its
 * files deleted, so the bank never accumulates renders nobody will open, and
 * a seed pushed into it is drawn on the next pass.
 *
 * <p>One render at a time. A single render already fans out across the
 * machine's cores internally; running two would only make both slower.
 */
public final class RenderQueue {

    /** How many of a dimension's candidates stay drawn. */
    public static final int KEEP = 10;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "customdim-render-queue");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final AtomicReference<String> CURRENT = new AtomicReference<>("");
    /** In-flight or queued jobs, so a reconcile during a roll never queues the same map twice. */
    private static final Set<String> QUEUED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private RenderQueue() {
    }

    public static int pending() {
        return PENDING.get();
    }

    public static String current() {
        return CURRENT.get();
    }

    /**
     * Brings one dimension's renders in line with its board: deletes what has
     * dropped out of the top {@link #KEEP}, queues what is missing — every
     * low-res first, then the high-res ones.
     */
    public static void reconcile(MinecraftServer server, DimensionConfig def) {
        String hash = InputHash.of(def, server);
        Identifier id = def.getDimensionIdentifier();
        String dimension = id.toString();

        List<Long> top = new ArrayList<>();
        for (SeedBank.CandidateSummary c : SeedBank.leaderboard(hash, dimension)) {
            if (top.size() >= KEEP) {
                break;
            }
            top.add(c.seed());
        }
        if (top.isEmpty()) {
            return;
        }
        Set<Long> keep = new LinkedHashSet<>(top);
        sweep(SeedBank.dimensionDir(hash, dimension), keep);

        // Low-res for the whole shortlist before any high-res: a person
        // scanning ten thumbnails is served long before one perfect map is.
        for (long seed : top) {
            enqueue(server, def, id, hash, dimension, seed, CandidateRender.Resolution.LOWRES);
        }
        for (long seed : top) {
            enqueue(server, def, id, hash, dimension, seed, CandidateRender.Resolution.HIGHRES);
        }
    }

    /** Deletes renders for seeds no longer on the shortlist. */
    private static void sweep(Path dir, Set<Long> keep) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".png")) {
                    continue;
                }
                int dot = name.indexOf('.');
                if (dot <= 0) {
                    continue;
                }
                try {
                    if (!keep.contains(Long.parseLong(name.substring(0, dot)))) {
                        Files.deleteIfExists(p);
                    }
                } catch (NumberFormatException ignored) {
                    // Not a candidate render; leave it alone.
                }
            }
        } catch (IOException e) {
            MultiverseServer.LOGGER.debug("Render sweep skipped {}: {}", dir, e.getMessage());
        }
    }

    private static void enqueue(MinecraftServer server, DimensionConfig def, Identifier id,
                                String hash, String dimension, long seed,
                                CandidateRender.Resolution resolution) {
        Path target = SeedBank.candidateImagePath(hash, dimension, seed, resolution);
        if (Files.isRegularFile(target)) {
            return;
        }
        String key = dimension + "/" + seed + "/" + resolution;
        if (!QUEUED.add(key)) {
            return;
        }
        PENDING.incrementAndGet();
        POOL.submit(() -> {
            CURRENT.set(id.getPath() + " " + seed);
            try {
                // The board may have moved on since this was queued; a map
                // nobody is going to open is not worth the cores.
                if (Files.isRegularFile(target) || !stillOnBoard(server, def, hash, dimension, seed)) {
                    return;
                }
                CandidateRender.render(server, id, def, seed, resolution, target);
            } catch (IOException | RuntimeException e) {
                MultiverseServer.LOGGER.error("Render failed for {} seed {}", id, seed, e);
            } finally {
                QUEUED.remove(key);
                PENDING.decrementAndGet();
                CURRENT.set("");
            }
        });
    }

    private static boolean stillOnBoard(MinecraftServer server, DimensionConfig def,
                                        String hash, String dimension, long seed) {
        int rank = 0;
        for (SeedBank.CandidateSummary c : SeedBank.leaderboard(hash, dimension)) {
            if (c.seed() == seed) {
                return true;
            }
            if (++rank >= KEEP) {
                return false;
            }
        }
        return false;
    }
}
