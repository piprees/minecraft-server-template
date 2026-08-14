package com.customdimensions.web;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.InputHash;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.Roller;
import com.customdimensions.roll.SeedBank;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Keeps the top of every dimension's board drawn, thumbnails first.
 *
 * <p>A map costs orders of magnitude more than a measurement and only the
 * handful at the top of a board is ever opened, so rendering is not part of
 * the search — it runs beside it, reconciling against the board.
 *
 * <p>Work is ordered by <em>kind before age</em>, across the whole pack:
 * every low-res map anywhere outranks every high-res map anywhere. Eighty-two
 * dimensions each showing ten thumbnails is a page you can review; one
 * dimension showing ten perfect maps while the rest show nothing is not. A
 * board that moves mid-roll puts a new thumbnail straight to the front, ahead
 * of high-res work already queued.
 *
 * <p>That ordering is not enough on its own, because it only decides what is
 * taken NEXT. With one consumer, a detail map already running holds the cores
 * until it finishes, and a detail map of a big world is minutes — so a
 * thumbnail queued behind one waits for all of it. A detail render therefore
 * abandons itself as soon as any thumbnail is owed and re-queues at the back
 * of its own class, writing nothing on the way out. It cannot livelock: it is
 * only ever taken when no thumbnail is queued, and that is exactly when its
 * abandon condition is false.
 *
 * <p>A seed pushed out of the top has its files deleted, so the bank never
 * accumulates maps nobody will open.
 */
public final class RenderQueue {

    /** How many of a dimension's candidates stay drawn. */
    public static final int KEEP = 10;

    /** Thumbnails everywhere before detail anywhere. */
    private static final int PRIORITY_LOWRES = 0;
    private static final int PRIORITY_HIGHRES = 1;

    private record Job(int priority, long sequence, String key, String dimension, Runnable work) {
    }

    /**
     * The dimension a viewer has open, or empty. Its maps are drawn first —
     * a render nobody is looking at can wait for one somebody is.
     */
    private static final AtomicReference<String> FOCUS = new AtomicReference<>("");

    /** 0 for the focused dimension, 1 for everything else. */
    private static int focusRank(Job job) {
        String want = FOCUS.get();
        return !want.isEmpty() && want.equals(job.dimension()) ? 0 : 1;
    }

    private static final PriorityBlockingQueue<Job> QUEUE = new PriorityBlockingQueue<>(64,
            Comparator.comparingInt(RenderQueue::focusRank)
                    .thenComparingInt(Job::priority).thenComparingLong(Job::sequence));
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final AtomicInteger THUMBNAILS_PENDING = new AtomicInteger();
    private static final AtomicReference<String> CURRENT = new AtomicReference<>("");
    /** Queued or in flight, so a reconcile during a roll never queues the same map twice. */
    private static final Set<String> QUEUED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private RenderQueue() {
    }

    public static int pending() {
        return PENDING.get();
    }

    /** How many thumbnails are still owed — the number that decides whether the page is reviewable. */
    public static int thumbnailsPending() {
        return THUMBNAILS_PENDING.get();
    }

    /**
     * Draws this dimension's maps first, or with a blank slug goes back to
     * ordinary order.
     *
     * <p>The heap is rebuilt rather than left to settle: a
     * {@link PriorityBlockingQueue} orders on insert, so jobs already queued
     * keep the order they were filed under and the change would only affect
     * new arrivals. Draining and re-adding re-sorts everything against the
     * new focus, which is the whole point of setting it.
     *
     * <p>Nothing is cancelled. Closing a dimension leaves its queued maps
     * exactly where they are — they finish as though they had simply been
     * filed earlier, and the ordinary order resumes behind them.
     */
    public static void focus(String slug) {
        FOCUS.set(slug == null ? "" : slug.trim());
        java.util.List<Job> drained = new java.util.ArrayList<>();
        QUEUE.drainTo(drained);
        QUEUE.addAll(drained);
    }

    public static String current() {
        return CURRENT.get();
    }

    /**
     * Brings one dimension's renders in line with its board: deletes what has
     * dropped out of the top {@link #KEEP}, queues what is missing.
     */
    public static void reconcile(MinecraftServer server, DimensionConfig def) {
        ensureWorker();
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
            // A dimension that never rolls still HAS a world. `the_canvas` is
            // superflat and opts out with `seedRoll.skip`, so no seed is ever
            // banked for it — but its config names the seed it will always
            // generate, and that is the place. Draw it, so the card shows the
            // world rather than "not rolled" forever.
            Long fixed = def.getSeed();
            if (fixed != null && !Roller.rollable(def)) {
                enqueue(server, def, id, hash, dimension, fixed,
                        CandidateRender.Resolution.LOWRES);
            }
            return;
        }
        sweep(SeedBank.dimensionDir(hash, dimension), new LinkedHashSet<>(top));

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
        boolean thumbnail = resolution == CandidateRender.Resolution.LOWRES;
        PENDING.incrementAndGet();
        if (thumbnail) {
            THUMBNAILS_PENDING.incrementAndGet();
        }
        // A thumbnail runs to completion; a detail map yields the moment any
        // thumbnail is owed. Priority orders the QUEUE, but the job already
        // running cannot be preempted by it, and a detail map of a big world is
        // minutes — long enough that every thumbnail behind it waits.
        java.util.function.BooleanSupplier abandonIf = thumbnail
                ? () -> false
                : () -> THUMBNAILS_PENDING.get() > 0;

        Runnable work = new Runnable() {
            @Override
            public void run() {
                CURRENT.set(id.getPath() + " " + seed + (thumbnail ? "" : " (detail)"));
                boolean requeue = false;
                try {
                    // The board may have moved since this was queued; a map nobody
                    // is going to open is not worth the cores.
                    if (Files.isRegularFile(target)
                            || !stillOnBoard(server, def, hash, dimension, seed)) {
                        return;
                    }
                    CandidateRender.render(server, id, def, seed, resolution, target, abandonIf);
                } catch (CandidateRender.Abandoned e) {
                    requeue = true;
                } catch (IOException | RuntimeException e) {
                    MultiverseServer.LOGGER.error("Render failed for {} seed {}", id, seed, e);
                } finally {
                    CURRENT.set("");
                    if (requeue) {
                        // Back of its own class, so the thumbnails that displaced
                        // it go first and it is not retried until they are done.
                        // Still QUEUED and still PENDING — it is deferred, not
                        // dropped, and the counters must not say otherwise.
                        QUEUE.add(new Job(PRIORITY_HIGHRES, SEQUENCE.incrementAndGet(),
                                key, dimension, this));
                    } else {
                        QUEUED.remove(key);
                        PENDING.decrementAndGet();
                        if (thumbnail) {
                            THUMBNAILS_PENDING.decrementAndGet();
                        }
                    }
                }
            }
        };
        QUEUE.add(new Job(thumbnail ? PRIORITY_LOWRES : PRIORITY_HIGHRES,
                SEQUENCE.incrementAndGet(), key, dimension, work));
    }

    /**
     * One consumer. Each render already fans out across its own share of the
     * machine, so a second consumer would only make both of them slower.
     */
    private static void ensureWorker() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            while (true) {
                try {
                    QUEUE.take().work().run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    MultiverseServer.LOGGER.error("Render queue job failed", e);
                }
            }
        }, "customdim-render-queue");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean stillOnBoard(MinecraftServer server, DimensionConfig def,
                                        String hash, String dimension, long seed) {
        // The fixed world of a dimension that never rolls is not on any board
        // and never drops off one. It is the only world that dimension has.
        Long fixed = def.getSeed();
        if (fixed != null && fixed == seed && !Roller.rollable(def)) {
            return true;
        }
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
