package com.customdimensions.web;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.InputHash;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.SeedBank;
import com.customdimensions.roll.Shortlist;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Keeps every dimension's roster drawn, thumbnails first.
 *
 * <p>A map costs orders of magnitude more than a measurement and only the
 * handful of seeds a dimension actually shows is ever opened, so rendering is
 * not part of the search — it runs beside it, reconciling against the same
 * {@link SeedRoster} the page is built from.
 *
 * <p>Work is ordered by <em>kind before age</em>, across the whole pack: a
 * named seed's thumbnail (current, starting, best, shortlisted) before an
 * ordinary one, and every thumbnail anywhere before every detail map
 * anywhere. Eighty-two dimensions each showing their configured world plus a
 * few candidates is a page you can review; one dimension showing ten perfect
 * maps while the rest show nothing is not. A roster that moves mid-roll puts a
 * new thumbnail straight to the front, ahead of high-res work already queued.
 *
 * <p>Thumbnails for every roster seed, plus detail maps for the seeds the
 * board protects from culling — current, starting, every shortlisted seed
 * ({@link RollPipeline#protectedSeeds}). Those are the seeds a person keeps
 * coming back to, so their whole-world map is worth having ready rather than
 * drawn on demand. A detail map covers the whole playable border rather than
 * the 512 blocks around spawn — a grid hundreds of times larger and minutes
 * of work — so every OTHER candidate's is drawn only when somebody opens the
 * card that shows it, through {@code POST /render}, never queued
 * speculatively for a board nobody has looked at.
 *
 * <p>A seed pushed off the roster has its files deleted, so the bank never
 * accumulates maps nobody will open. A named seed is never pushed off — that
 * is what naming it means.
 */
public final class RenderQueue {

    /**
     * How many purely-ranked candidates stay drawn. A dimension's named seeds
     * — current, starting, best, shortlisted — are drawn on top of these and
     * are not capped; see {@link SeedRoster}.
     */
    public static final int KEEP = SeedRoster.OTHERS;

    /**
     * A named seed's thumbnail before an ordinary one, then thumbnails
     * everywhere before detail anywhere.
     *
     * <p>The seed a dimension is CONFIGURED with is the one a person opens the
     * page to see: it is the point of comparison every other card is judged
     * against, and it need never have been rolled, so it is often the one
     * thumbnail that does not exist yet. Drawn behind eighty dimensions' worth
     * of ordinary candidates it arrives last, which is the wrong way round.
     */
    private static final int PRIORITY_PINNED = -1;
    private static final int PRIORITY_LOWRES = 0;
    private static final int PRIORITY_HIGHRES = 1;

    /**
     * {@code slug} is the dimension's bare path, not its full identifier: it
     * is what the viewer sends to {@code /focus} and what
     * {@link com.customdimensions.web.RollPipeline} matches a roll target on,
     * so comparing a focus against anything else silently never matches.
     * {@code thumbnail} is which of the two independent pause switches this
     * job answers to.
     */
    private record Job(int priority, long sequence, String key, String slug, boolean thumbnail,
                       Runnable work) {
    }

    /** Independent play/pause per resolution — a stuck detail render must not block thumbnails, or the reverse. */
    private static final AtomicBoolean PAUSED_LOW = new AtomicBoolean();
    private static final AtomicBoolean PAUSED_HIGH = new AtomicBoolean();

    public static boolean lowPaused() {
        return PAUSED_LOW.get();
    }

    public static boolean highPaused() {
        return PAUSED_HIGH.get();
    }

    public static void setLowPaused(boolean paused) {
        PAUSED_LOW.set(paused);
    }

    public static void setHighPaused(boolean paused) {
        PAUSED_HIGH.set(paused);
    }

    /**
     * The dimension a viewer has open, or empty. Its maps are drawn first —
     * a render nobody is looking at can wait for one somebody is.
     */
    private static final AtomicReference<String> FOCUS = new AtomicReference<>("");

    /** 0 for the focused dimension, 1 for everything else. */
    private static int focusRank(Job job) {
        return isFocused(job.slug()) ? 0 : 1;
    }

    private static boolean isFocused(String slug) {
        String want = FOCUS.get();
        return !want.isEmpty() && want.equals(slug);
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
     * Brings one dimension's renders in line with its roster: deletes what has
     * dropped off it, queues the thumbnails that are missing, and queues detail
     * maps for the seeds {@link RollPipeline#protectedSeeds} names. Every other
     * candidate's detail map is drawn only when somebody opens that candidate's
     * card, through {@code POST /render} — never queued speculatively for a
     * board nobody has looked at.
     */
    public static void reconcile(MinecraftServer server, DimensionConfig def) {
        ensureWorker();
        String hash = InputHash.of(def, server);
        Identifier id = def.getDimensionIdentifier();
        String dimension = id.toString();

        List<SeedRoster.Slot> roster = roster(def, hash, dimension);
        if (roster.isEmpty()) {
            return;
        }
        sweep(SeedBank.dimensionDir(hash, dimension), SeedRoster.seeds(roster));

        for (SeedRoster.Slot slot : roster) {
            enqueue(server, def, id, hash, dimension, slot.seed(),
                    CandidateRender.Resolution.LOWRES, slot.role().pinned());
        }
        for (Long seed : RollPipeline.protectedSeeds(def, dimension)) {
            enqueue(server, def, id, hash, dimension, seed,
                    CandidateRender.Resolution.HIGHRES, true);
        }
    }

    /**
     * The seeds this dimension shows — the same roster the page builds, so
     * what is drawn and what is displayed can never disagree.
     *
     * <p>A dimension that never rolls still HAS a world: {@code the_canvas} is
     * superflat and opts out with {@code seedRoll.skip}, so nothing is ever
     * banked for it, but its config names the seed it will always generate.
     * That seed arrives here as {@code starting} and is drawn like any other
     * named one, so the card shows the world rather than "not rolled" forever.
     */
    static List<SeedRoster.Slot> roster(DimensionConfig def, String hash, String dimension) {
        List<Long> ranked = new ArrayList<>();
        for (SeedBank.CandidateSummary c : SeedBank.leaderboard(hash, dimension)) {
            ranked.add(c.seed());
        }
        Long starting = def.getSeed();
        return SeedRoster.of(BankView.currentSeed(def, starting), starting, ranked,
                Shortlist.of(dimension));
    }

    /** Deletes renders for seeds no longer on the roster. */
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
                                CandidateRender.Resolution resolution, boolean pinned) {
        Path target = SeedBank.candidateImagePath(hash, dimension, seed, resolution);
        if (Files.isRegularFile(target)) {
            return;
        }
        String key = dimension + "/" + seed + "/" + resolution;
        if (!QUEUED.add(key)) {
            return;
        }
        boolean thumbnail = resolution == CandidateRender.Resolution.LOWRES;
        String slug = id.getPath();
        PENDING.incrementAndGet();
        if (thumbnail) {
            THUMBNAILS_PENDING.incrementAndGet();
        }
        // A thumbnail runs to completion; a detail map for a dimension nobody
        // is looking at yields the moment any thumbnail is owed. Priority
        // orders the QUEUE, but the job already running cannot be preempted by
        // it, and a detail map of a big world is minutes — long enough that
        // every thumbnail behind it waits.
        //
        // A FOCUSED detail map does not yield: its own dimension's thumbnails
        // already outrank it in the queue, so the only work left to yield to
        // belongs to a dimension nobody asked about, and yielding to that is
        // how the map somebody is waiting on never gets drawn.
        java.util.function.BooleanSupplier abandonIf = thumbnail
                ? () -> false
                : () -> THUMBNAILS_PENDING.get() > 0 && !isFocused(slug);
        int priority = !thumbnail ? PRIORITY_HIGHRES
                : pinned ? PRIORITY_PINNED : PRIORITY_LOWRES;

        Runnable work = new Runnable() {
            @Override
            public void run() {
                CURRENT.set(id.getPath() + " " + seed + (thumbnail ? "" : " (detail)"));
                boolean requeue = false;
                try {
                    // The roster may have moved since this was queued; a map
                    // nobody is going to open is not worth the cores.
                    if (Files.isRegularFile(target)
                            || !onRoster(def, hash, dimension, seed)) {
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
                                key, slug, false, this));
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
        QUEUE.add(new Job(priority, SEQUENCE.incrementAndGet(), key, slug, thumbnail, work));
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
                    Job job = takeRunnable();
                    if (job == null) {
                        Thread.sleep(500);
                        continue;
                    }
                    job.work().run();
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

    /**
     * The next job whose resolution is not paused, or null when everything
     * currently queued is. Jobs of the paused kind are put straight back
     * rather than dropped — pausing defers work, it never discards it.
     */
    private static Job takeRunnable() throws InterruptedException {
        Job first = QUEUE.take();
        if (!isPaused(first)) {
            return first;
        }
        List<Job> skipped = new ArrayList<>();
        skipped.add(first);
        Job found = null;
        Job j;
        while ((j = QUEUE.poll()) != null) {
            if (!isPaused(j)) {
                found = j;
                break;
            }
            skipped.add(j);
        }
        QUEUE.addAll(skipped);
        return found;
    }

    private static boolean isPaused(Job job) {
        return job.thumbnail() ? PAUSED_LOW.get() : PAUSED_HIGH.get();
    }

    /**
     * Whether this seed is still one the dimension shows. Re-derived rather
     * than remembered: a roll moves the ranking under a queued job, and a seed
     * that has dropped off is a map nobody will open. A NAMED seed never drops
     * off — that is what naming it means — so the roster answers for both.
     */
    private static boolean onRoster(DimensionConfig def, String hash, String dimension, long seed) {
        return SeedRoster.seeds(roster(def, hash, dimension)).contains(seed);
    }
}
