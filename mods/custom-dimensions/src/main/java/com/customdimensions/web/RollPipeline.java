package com.customdimensions.web;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.Json;
import com.customdimensions.roll.Roller;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rolling driven from the browser, with progress while it works.
 *
 * <p>A run is a background thread calling {@link Roller#screenShortlist} then
 * {@link Roller#measureOne} — the same two-tier search the bank is now built
 * by. Both measure headlessly (no {@code ServerWorld}, no chunk load), so a
 * run never touches the tick loop; the server stays playable and a try-out
 * running beside it is unaffected.
 *
 * <p>Measurement is budgeted per SEED, not per dimension, and TIER 1 AND
 * TIER 2 SHARE ONE POOL: every dimension's {@link Roller#screenShortlist}
 * and {@link Roller#measureShortlist} call the same {@code ExecutorService}
 * sized by {@link #workers()}, so total concurrent measurement work never
 * exceeds that bound whatever mix of dimensions is screening or measuring
 * at once — a single tier-2 measurement alone runs to about a hundred
 * core-seconds on a modded dimension, so leaving it unbounded per
 * orchestrator thread would oversubscribe the machine by as many times as
 * there are dimensions in flight. Dimension orchestration itself is cheap
 * — each of its threads mostly waits on that shared pool — so {@link #run}
 * gives every target dimension its own orchestration thread rather than
 * capping that count too. CANCEL stops new submissions to the pool (never
 * abandons a candidate mid-write) and is re-checked once the current batch
 * drains. Opening a dimension in the viewer does NOT stop any other
 * dimension's in-flight work — {@link #nextTarget} just serves the focused
 * one first once a worker is free (see {@link #focus}). {@code generation}
 * is bumped each time a dimension finishes, which is the browser's cue to
 * refetch the grid.
 *
 * <p>One run at a time. Two concurrent searches would compete for the same
 * candidate directory and neither would be faster.
 */
public final class RollPipeline {

    /**
     * Tier-2 measurements between shortlist reconciles. Cheap (a directory
     * read) but not free, and {@link Roller#SHORTLIST} is small enough that
     * this mostly fires once, partway through.
     */
    private static final int RECONCILE_EVERY = 5;

    /**
     * The score below which a banked candidate is flagged in the viewer as
     * the best available rather than a good seed, as a percentage. Display
     * only: it gates nothing here. {@link #banked} counts every scored
     * candidate regardless of where it sits against this number, because the
     * score is not comparable BETWEEN dimensions — {@link
     * com.customdimensions.score.Criterion#applicable} scores each dimension
     * out of its own ceiling, and of the winners already picked by hand across
     * the bank, most sit under this bar.
     *
     * <p>One definition, read by the STARVED log line here and by the card's
     * flagged state in {@link ViewerPage} — two numbers would let a board look
     * flagged on the page while the log told a different story.
     */
    public static final double SCORE_THRESHOLD = 80.0;

    /**
     * How many candidates a healthy board should hold, matched to {@link
     * RenderQueue#KEEP} — a dimension shows only that many ranked seeds
     * beside its named ones. Gates nothing: every roll asks for {@code count}
     * MORE candidates for every dimension it targets, whatever it already
     * holds. Only read afterwards, to report a dimension that ended below
     * this via {@link #STARVED} — its gates reject too much to fill it, not
     * that nothing was asked of it.
     */
    private static final int WANTED = RenderQueue.KEEP;

    /**
     * How many candidates a dimension's bank keeps on disk. A roll that
     * measures new seeds re-ranks the whole bank afterwards and deletes
     * anything beyond this — matched to {@link Roller#SHORTLIST}, since a
     * board is never asked to hold more than tier 1 could shortlist in one
     * pass. {@link #protectedSeeds} survive regardless of rank.
     */
    private static final int BOARD_LIMIT = Roller.SHORTLIST;

    /** Dimensions that spent their seeds without reaching {@link #WANTED}. */
    private static final java.util.Set<String> STARVED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /**
     * True while priming is SCORING the configured seeds — not while it is
     * drawing them. A roll needs the baseline those measurements establish,
     * but it does not need the pictures: the render cores are reserved
     * separately and sit idle through a screen, so making a roll wait on them
     * spends a quarter of the machine on nothing.
     */
    private static final AtomicBoolean PRIMING_MEASURE = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL = new AtomicBoolean(false);
    private static final AtomicBoolean RENDERING = new AtomicBoolean(false);

    /**
     * The candidate whose detail map is being drawn, as {@code <slug>/<seed>},
     * or empty when none is.
     *
     * <p>Named rather than counted because a detail render belongs to ONE
     * card: the modal that asked for it matches this against the candidate it
     * is showing and reports progress there. A bare "rendering" flag on the
     * roller's status line is what made the page look frozen — the work was
     * reported nowhere near the thing that started it.
     */
    private static final AtomicReference<String> RENDERING_DETAIL =
            new AtomicReference<>("");

    /** When the in-flight detail render started, for the "how long so far" the modal shows. */
    private static final java.util.concurrent.atomic.AtomicLong RENDER_STARTED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final AtomicInteger TARGET = new AtomicInteger();
    /** Dimensions this run will visit — what progress is actually measured against. */
    private static final AtomicInteger DIMENSIONS = new AtomicInteger();
    /** Seeds measured so far, counted as each one lands rather than per dimension. */
    private static final AtomicInteger ROLLED = new AtomicInteger();
    /** Shortlisted seeds this run, and how many are measured. Run-wide totals:
     * dimensions roll concurrently, so a per-dimension ratio would be stomped. */
    private static final AtomicInteger SHORTLISTED = new AtomicInteger();
    private static final AtomicInteger SHORTLIST_DONE = new AtomicInteger();
    /** Seeds that cleared tier 1's gates — the pool the shortlist was taken from. */
    private static final AtomicInteger PASSED = new AtomicInteger();
    private static final AtomicInteger SURVEYED = new AtomicInteger();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final AtomicReference<String> STAGE = new AtomicReference<>("idle");
    private static final AtomicReference<String> CURRENT = new AtomicReference<>("");
    private static final AtomicReference<String> ERROR = new AtomicReference<>("");

    private RollPipeline() {
    }

    /**
     * Starts a run.
     *
     * <p>{@code order} is an explicit list of dimensions to roll, in the order
     * to roll them — what the viewer's Filtered option sends, which is the
     * grid as the person is looking at it. Sorted by score ascending, that is
     * "top up my worst boards first", so the order IS the instruction and
     * nothing here may re-sort it. Empty or null falls back to
     * {@code dimension}, which names one dimension (bare slug, id path or full
     * id) or is null for every rollable dimension in the pack.
     *
     * @return null on success, or why it refused
     */
    public static String start(MinecraftServer server, String dimension, List<String> order,
                               int count) {
        if (count <= 0) {
            return "count must be at least 1";
        }
        boolean ordered = order != null && !order.isEmpty();
        List<DimensionConfig> targets = ordered ? resolveOrdered(order) : resolve(dimension);
        if (targets.isEmpty()) {
            if (ordered) {
                return "none of those " + order.size() + " dimension(s) is rollable";
            }
            return dimension == null ? "nothing rollable in this pack"
                    : "no rollable dimension named " + dimension;
        }
        if (!ordered) {
            // Emptiest first. Workers take from the head of this list, so the
            // dimensions with nothing to show start immediately rather than
            // waiting behind eighty others — and a run stopped halfway has
            // spent its time on the boards that needed it. A given order is
            // already a statement about which boards matter, so it stands.
            targets.sort(java.util.Comparator.comparingInt(
                    def -> banked(com.customdimensions.command.InputHash.of(def, server),
                            def.getDimensionIdentifier().toString())));
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return "a roll is already running";
        }
        CANCEL.set(false);
        ERROR.set("");
        // A focus is a statement about the page somebody had open, not about
        // the run they are starting now.
        FOCUS.set("");
        STARVED.clear();
        // count MORE seeds per targeted dimension: every dimension in
        // targets is rolled, whatever its board already holds.
        TARGET.set(count * targets.size());
        DIMENSIONS.set(targets.size());
        ROLLED.set(0);
        SURVEYED.set(0);
        PASSED.set(0);
        SHORTLISTED.set(0);
        SHORTLIST_DONE.set(0);
        STAGE.set(PRIMING_MEASURE.get() ? "waiting for the configured seeds" : "rolling");
        com.customdimensions.roll.CandidateRender.rolling(true);
        Thread worker = new Thread(() -> {
            if (awaitPriming()) {
                run(server, targets, count);
            } else {
                finishCancelledBeforeStart();
            }
        }, "customdim-roll");
        worker.setDaemon(true);
        worker.start();
        return null;
    }

    /**
     * Blocks the roll thread until the configured seeds are scored.
     *
     * <p>The boards a roll ranks against are meaningless without that
     * baseline, so the wait is real — but it belongs on this thread rather
     * than on the caller, who otherwise has to poll a refusal and guess when
     * to retry. Renders are NOT waited on: the render cores are reserved from
     * the measure budget, so drawing continues alongside.
     *
     * @return false when {@link #stop()} was called while waiting
     */
    private static boolean awaitPriming() {
        boolean logged = false;
        while (PRIMING_MEASURE.get()) {
            if (CANCEL.get()) {
                return false;
            }
            if (!logged) {
                MultiverseServer.LOGGER.info(
                        "Roll queued behind the configured-seed scoring; it starts when that finishes");
                logged = true;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        STAGE.set("rolling");
        return true;
    }

    /** Releases the run flags for a roll cancelled before it began. */
    private static void finishCancelledBeforeStart() {
        STAGE.set("idle");
        com.customdimensions.roll.CandidateRender.rolling(false);
        RUNNING.set(false);
    }

    public static void stop() {
        CANCEL.set(true);
    }

    /**
     * Scores and draws every dimension's named seeds, without rolling
     * anything.
     *
     * <p>The point of the page before a roll is "what have I got" — every
     * dimension has a configured world whether or not a search ever ran, and
     * on a fresh bank that seed is the only one there is. Nothing else would
     * ever measure or draw it: the search draws at random, and a reconcile
     * only happens during a roll, which a bank with no candidates never has.
     *
     * <p>Measured BEFORE the map is queued. A render says what the world
     * looks like; the scorecard says whether it is any good, and the whole
     * reason to show the configured seed beside the candidates is to compare
     * the two — a card with a picture and no score cannot be compared with
     * anything.
     *
     * <p>Runs on the roll pool so eighty-odd measurements go at the width of
     * the machine, and skips anything already banked, so a restart costs
     * nothing.
     */
    public static void primeNamedSeeds(MinecraftServer server) {
        // Raised HERE, not inside the thread: between this call and the
        // thread being scheduled the gate would otherwise be open, and a roll
        // admitted in that window ranks against boards nothing has scored.
        PRIMING_MEASURE.set(true);
        Thread starter = new Thread(() -> {
            RenderQueue.priming(true);
            // EVERY dimension is measured. A committed thumbnail says what a
            // world looks like, never whether it is any good — gating the
            // measurement on one leaves the page a wall of pictures with no
            // scores, which is the one thing it exists to show. measureNamed
            // skips what the bank already holds, so this is free on a restart.
            List<DimensionConfig> targets = BankView.rollTargets();
            if (targets.isEmpty()) {
                PRIMING_MEASURE.set(false);
                RenderQueue.priming(false);
                GENERATION.incrementAndGet();
                return;
            }
            // Everything is drawn too. A committed pair is the MAP's card
            // (downscaled, published beside the JSON by exportMissingThumbnails);
            // the viewer's card is the bank render this queues, and one cannot
            // stand in for the other. Gating the draw on a committed pair left
            // every card reading "render queued" forever. What protects a
            // roll's pick is exportMissingThumbnails writing only the size that
            // is absent — never this. enqueue skips whatever the bank already
            // holds, so this is free once a hash has been drawn.
            int committed = 0;
            for (DimensionConfig def : targets) {
                if (Picker.thumbnailsPresent(server, def, def.getDimensionIdentifier().getPath())) {
                    committed++;
                }
            }
            MultiverseServer.LOGGER.info("Priming {} dimension(s); {} already have a committed pair",
                    targets.size(), committed);
            int workers = Math.max(1, Math.min(workers(), targets.size()));
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(workers, r -> {
                        Thread t = new Thread(r, "customdim-roll");
                        t.setDaemon(true);
                        return t;
                    });
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (DimensionConfig def : targets) {
                    futures.add(pool.submit(() -> {
                        measureNamed(server, def);
                        RenderQueue.reconcile(server, def);
                    }));
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (java.util.concurrent.ExecutionException e) {
                        MultiverseServer.LOGGER.warn("Priming named seeds failed", e.getCause());
                    }
                }
                // Scoring is done, so a roll may start from here: it has the
                // baseline it ranks against. Measuring only QUEUES the maps,
                // and drawing them runs on the reserved render cores beside
                // whatever comes next.
                PRIMING_MEASURE.set(false);
                awaitRenders(server, targets);
                // A last pass for whatever landed after the final sweep.
                exportReady(server, targets);
            } finally {
                PRIMING_MEASURE.set(false);
                RenderQueue.priming(false);
                pool.shutdownNow();
                GENERATION.incrementAndGet();
            }
        }, "customdim-roll");
        starter.setDaemon(true);
        starter.start();
    }

    /**
     * Publishes every target whose renders have landed. Safe to call
     * repeatedly: each size is written only when absent, so a dimension
     * already complete costs two stat calls.
     */
    private static void exportReady(MinecraftServer server, List<DimensionConfig> targets) {
        for (DimensionConfig def : targets) {
            String slug = def.getDimensionIdentifier().getPath();
            if (Picker.thumbnailsPresent(server, def, slug)) {
                continue;
            }
            Long seed = BankView.currentSeed(def, def.getSeed());
            if (seed != null) {
                Picker.exportMissingThumbnails(server, def, slug, seed);
            }
        }
    }

    /**
     * Blocks until the render queue drains, this is cancelled, or it stalls,
     * publishing whatever has landed as it goes. Exporting only at the end
     * would mean a JVM that dies part-way through leaves nothing behind,
     * however many hours of rendering it did first.
     */
    private static void awaitRenders(MinecraftServer server, List<DimensionConfig> targets) {
        int lastPending = -1;
        long unchangedFor = 0;
        int tick = 0;
        // RenderQueue.pending() is the WHOLE queue, so once a roll is banking
        // candidates this never reaches zero. The roll drives its own
        // reconcile and export from rollOne, and leaving PRIMING set would
        // stop a detail map yielding to the candidate thumbnails that fill
        // the page — so hand the queue over instead of waiting it out.
        while (!CANCEL.get() && !RUNNING.get()) {
            int pending = RenderQueue.pending();
            if (pending == 0) {
                return;
            }
            // Every 30s, not every second: the sweep stats two files per
            // target and the queue does not empty that fast.
            if (++tick % 30 == 0) {
                exportReady(server, targets);
            }
            // A queue that has not moved in ten minutes is stuck or paused;
            // holding the roll shut forever on it would be worse than letting
            // one through.
            unchangedFor = pending == lastPending ? unchangedFor + 1 : 0;
            if (unchangedFor > 600) {
                MultiverseServer.LOGGER.warn(
                        "Priming gave up waiting on {} unfinished render(s)", pending);
                return;
            }
            lastPending = pending;
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Measures this dimension's named seeds that the bank does not already
     * hold. Never throws: a seed that cannot be measured must not take the
     * roll down with it.
     */
    private static void measureNamed(MinecraftServer server, DimensionConfig def) {
        Identifier id = def.getDimensionIdentifier();
        String hash = com.customdimensions.command.InputHash.of(def, server);
        for (SeedRoster.Slot slot : RenderQueue.roster(def, hash, id.toString())) {
            if (!slot.role().pinned() || CANCEL.get()) {
                continue;
            }
            try {
                if (Roller.measureNamed(server, id, def, slot.seed())) {
                    MultiverseServer.LOGGER.debug("scored the {} seed for {}",
                            slot.role().id(), id.getPath());
                }
            } catch (java.io.IOException | RuntimeException e) {
                MultiverseServer.LOGGER.warn("Could not score the {} seed {} for {}: {}",
                        slot.role().id(), slot.seed(), id.getPath(), e.toString());
            }
        }
    }

    private static List<DimensionConfig> resolve(String dimension) {
        List<DimensionConfig> out = new ArrayList<>();
        for (DimensionConfig def : BankView.rollTargets()) {
            if (!Roller.rollable(def)) {
                continue;
            }
            if (dimension == null || dimension.isBlank() || names(def, dimension)) {
                out.add(def);
            }
        }
        return out;
    }

    /**
     * Whether {@code key} names this dimension.
     *
     * <p>Three spellings, because three are in circulation and a caller has no
     * way to know which it holds: the config's own name, the identifier's path
     * — which is what the viewer puts in every card's {@code data-dim} and
     * therefore what every button on the page sends back — and the full id.
     */
    private static boolean names(DimensionConfig def, String key) {
        Identifier id = def.getDimensionIdentifier();
        return key.equals(def.getName()) || key.equals(id.getPath()) || key.equals(id.toString());
    }

    /**
     * The named dimensions, in the order named. Anything that does not resolve
     * or is not rollable is dropped rather than failing the run: the list comes
     * from a grid that may have moved since it was read, and losing one board
     * must not cost the other seventeen.
     */
    private static List<DimensionConfig> resolveOrdered(List<String> order) {
        List<DimensionConfig> pool = new ArrayList<>();
        for (DimensionConfig def : BankView.rollTargets()) {
            if (Roller.rollable(def)) {
                pool.add(def);
            }
        }
        List<DimensionConfig> out = new ArrayList<>();
        java.util.Set<String> taken = new java.util.LinkedHashSet<>();
        for (String key : order) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String want = key.trim();
            for (DimensionConfig def : pool) {
                if (names(def, want) && taken.add(def.getDimensionIdentifier().toString())) {
                    out.add(def);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * The CPU budget for tier-1 seed measurement — one number shared by
     * every dimension currently screening, via the single {@code
     * ExecutorService} {@link #run} builds. Each measurement is pure CPU
     * with no {@code ServerWorld} and no state shared between seeds, so the
     * pool can run any of them at once whatever dimension they belong to.
     * It takes what the renderer is not using: two cores are left for the
     * server thread and everything else, and
     * {@link com.customdimensions.roll.CandidateRender#renderCores()} for the
     * maps, which run beside the search rather than after it.
     */
    private static int workers() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2
                - com.customdimensions.roll.CandidateRender.renderCores());
    }

    /**
     * How many dimensions roll at once, given the measure budget.
     *
     * <p>Never one per dimension: a dimension that finishes tier 2 banks
     * candidates and queues thumbnails, and drawing those is what the roll's
     * reserved render cores are for. All-at-once completes every board at the
     * same moment, so nothing is drawable until the end and those cores idle
     * through the entire screen.
     *
     * <p>Twice the measure budget rather than the budget itself, because an
     * orchestrator does its own work between tiers and a 1:1 ratio leaves
     * {@code measurePool} idle whenever one is between dimensions. Pure, so
     * the ratio is pinned without a server.
     */
    static int orchestratorCount(int targets, int budget) {
        return Math.max(1, Math.min(targets, Math.max(2, budget * 2)));
    }

    private static void run(MinecraftServer server, List<DimensionConfig> targets, int count) {
        int budget = workers();
        // Fewer orchestrators than dimensions, so dimensions FINISH in waves
        // rather than all at the end. A dimension that has finished tier 2
        // banks candidates and queues its thumbnails, which is what the
        // reserved render cores (renderCores(), a quarter of the machine
        // while rolling) exist to draw — with one orchestrator per dimension
        // every board completed at once, nothing was drawable until then, and
        // those cores idled through the whole screen.
        //
        // Twice the measure budget, not the budget itself: an orchestrator
        // does its own work between tiers (cullToTop, promoteBest,
        // reconcile), so a 1:1 ratio leaves measurePool starving whenever one
        // is between dimensions.
        int orchestrators = orchestratorCount(targets.size(), budget);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                orchestrators, r -> {
                    Thread t = new Thread(r, "customdim-roll");
                    t.setDaemon(true);
                    return t;
                });
        // The actual CPU budget: every dimension's tier-1 sweep measures its
        // seeds here, so one dimension in flight can use all of it and eighty
        // share it, whatever orchestrators is.
        java.util.concurrent.ExecutorService measurePool =
                java.util.concurrent.Executors.newFixedThreadPool(budget, r -> {
                    Thread t = new Thread(r, "customdim-measure");
                    t.setDaemon(true);
                    return t;
                });
        MultiverseServer.LOGGER.info("roll: {} dimension(s) x {} seed(s), {} measure worker(s)",
                targets.size(), count, budget);
        // Pulled, not pre-submitted. Submitting every dimension up front fixes
        // the order at start, and the order has to be able to change: opening a
        // dimension in the viewer is a statement that it is the one being
        // looked at, and it should be the one being rolled.
        final java.util.Deque<DimensionConfig> pending = new java.util.ArrayDeque<>(targets);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < orchestrators; i++) {
                futures.add(pool.submit(() -> {
                    DimensionConfig def;
                    while ((def = nextTarget(pending)) != null) {
                        rollOne(server, def, count, measurePool, budget);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (java.util.concurrent.ExecutionException e) {
                    MultiverseServer.LOGGER.error("Roll worker failed", e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            measurePool.shutdownNow();
            RUNNING.set(false);
            com.customdimensions.roll.CandidateRender.rolling(false);
            // No detail maps at the end of a run. One covers the world edge to
            // edge and runs the generator at every sample — minutes apiece,
            // eighty-odd of them — and almost none are ever looked at. A
            // detail map is drawn when somebody opens the card that shows it,
            // and not before.
            STAGE.set(CANCEL.get() ? "stopped" : "done");
            CURRENT.set("");
            GENERATION.incrementAndGet();
        }
    }

    /**
     * The dimension a viewer currently has open, or empty for none.
     *
     * <p>Opening one is a statement about attention, so it is the one worth
     * spending seeds and cores on. Closing it does NOT cancel anything: the
     * work already started finishes as though it had simply been earlier in
     * the queue, and the ordinary order resumes behind it.
     */
    private static final java.util.concurrent.atomic.AtomicReference<String> FOCUS =
            new java.util.concurrent.atomic.AtomicReference<>("");

    /**
     * Sets (or with a blank slug clears) the dimension that jumps the queue.
     *
     * <p>Opening a dimension queues its THUMBNAILS — that is what the list of
     * cards shows. It does not queue a detail map: the detail map belongs to
     * one candidate's own card, so the card asks for it when it opens.
     */
    public static void focus(MinecraftServer server, String slug) {
        String want = slug == null ? "" : slug.trim();
        FOCUS.set(want);
        RenderQueue.focus(want);
        if (want.isEmpty()) {
            return;
        }
        DimensionConfig def = BankView.resolve(want);
        if (def != null) {
            RenderQueue.reconcile(server, def);
        }
    }

    public static String focused() {
        return FOCUS.get();
    }

    /**
     * The next dimension to roll: the focused one if it is still WAITING,
     * otherwise the one at the head. This is the whole of focus priority —
     * ordering, never abandonment: a dimension already being worked on by
     * another orchestrator thread is not preempted, it simply is not in
     * {@code pending} for this to find, and finishes its shortlist exactly
     * as if nobody had opened anything.
     *
     * <p>Checked at each pull rather than fixed at submission, so opening a
     * dimension mid-roll promotes it.
     */
    private static DimensionConfig nextTarget(java.util.Deque<DimensionConfig> pending) {
        synchronized (pending) {
            if (pending.isEmpty() || CANCEL.get()) {
                return null;
            }
            String want = FOCUS.get();
            if (!want.isEmpty()) {
                for (java.util.Iterator<DimensionConfig> it = pending.iterator(); it.hasNext();) {
                    DimensionConfig def = it.next();
                    if (want.equals(def.getName())) {
                        it.remove();
                        return def;
                    }
                }
            }
            return pending.poll();
        }
    }

    /**
     * Rolls one dimension for {@code count} MORE seeds, until its pool is
     * spent. Never skipped for already holding candidates — asking for a
     * roll means N more for every targeted dimension, whatever its board
     * already holds.
     *
     * <p>Two tiers, not a flat search, and both measure on {@code
     * measurePool} — up to {@code measureParallelism} seeds at once, shared
     * with every other dimension currently screening OR measuring tier 2
     * (see {@link #run}). {@link Roller#screenShortlist} sweeps {@code
     * count} seeds cheaply — no per-seed terrain router — and ranks the
     * best {@link Roller#SHORTLIST}; {@link Roller#measureShortlist} then
     * runs {@link Roller#measureOne} — a full measurement, ~a hundred
     * core-seconds on a modded dimension — over EVERY shortlisted seed,
     * never stopping early: tier 1's cheap rank is not the final score, so
     * stopping early would bank the first few of the shortlist rather than
     * its best few. The bank is culled to {@link #BOARD_LIMIT} afterwards,
     * and its new best is promoted to current (see {@link #promoteBest}).
     *
     * <p>{@code count} is now the tier-1 POOL size, not a ceiling that never
     * bound: the sweep is a search over the whole pool, not a stream that
     * stopped at the first few survivors, so a dimension whose gates reject
     * nearly everything still gets a fair shortlist to draw from.
     * {@link #STARVED} records a dimension that fell short of {@link #WANTED}
     * even so.
     */
    private static void rollOne(MinecraftServer server, DimensionConfig def, int count,
                                java.util.concurrent.ExecutorService measurePool,
                                int measureParallelism) {
        Identifier id = def.getDimensionIdentifier();
        String hash = com.customdimensions.command.InputHash.of(def, server);
        String dimension = id.toString();
        // The seeds this dimension already has, before any it might get —
        // what the rest of the board is compared against, so an unscored one
        // makes the whole comparison unavailable. The configured seed itself
        // is never a candidate and never counts toward WANTED.
        CURRENT.set(id.getPath());
        STAGE.set("scoring the named seeds for " + id.getPath());
        measureNamed(server, def);
        STAGE.set("screening " + id.getPath());
        long tier1Start = System.nanoTime();
        java.util.List<Long> shortlist;
        int screened;
        // The screen is thousands of seeds and minutes long, so it watches
        // CANCEL rather than running to the end of the pool regardless.
        // Shared with tier 2 below — the same signal either way. Opening a
        // different dimension in the viewer does NOT stop this: focus only
        // orders what a free worker takes next (see nextTarget), it never
        // preempts work already in flight.
        java.util.function.BooleanSupplier abandon = CANCEL::get;
        try {
            Roller.Screen screen = Roller.screenShortlist(server, id, def, count, abandon,
                    measurePool, measureParallelism, ROLLED::incrementAndGet);
            shortlist = screen.shortlist();
            screened = screen.screened();
            PASSED.addAndGet(screen.survivors());
        } catch (RuntimeException e) {
            MultiverseServer.LOGGER.error("Roll failed for {}", id, e);
            ERROR.set(id.getPath() + ": " + e);
            shortlist = java.util.List.of();
            screened = 0;
        }
        long tier1Ms = (System.nanoTime() - tier1Start) / 1_000_000;
        MultiverseServer.LOGGER.info(
                "roll: {} tier 1 screened {} of {} seed(s) in {} ms ({} ms/seed), {} shortlisted",
                id.getPath(), screened, count, tier1Ms,
                screened == 0 ? 0 : tier1Ms / (double) screened, shortlist.size());
        // A screen that measured nothing did not find a bad pool — it never
        // ran, because the run was already cancelled before this dimension's
        // screen started. Its seeds are not counted; a run that reported
        // them would show progress it never made.
        if (screened == 0 && count > 0 && shortlist.isEmpty()) {
            return;
        }
        STAGE.set("rolling " + id.getPath());
        SHORTLISTED.addAndGet(shortlist.size());
        long tier2Start = System.nanoTime();
        // EVERY shortlisted seed, not the first WANTED of them. Tier 1 ranks on
        // structures and biome alone, so its order is not the order the full
        // scorecard produces — stopping once the board is full would bank the
        // first five of the shortlist rather than its best five, and the
        // difference is exactly what tier 2 exists to find. SeedBank keeps
        // every card and leaderboard sorts descending, so the board is the top
        // WANTED by FINAL score once all ten are in.
        //
        // Measured on measurePool — the SAME pool tier 1 screens on, up to
        // measureParallelism at once — rather than one measurement per
        // orchestrator thread: unbounded, a full sweep could run as many
        // ~100-core-second measurements at once as there are dimensions,
        // oversubscribing the machine many times over.
        int measured = Roller.measureShortlist(server, id, def, shortlist, abandon,
                measurePool, measureParallelism, () -> {
                    int done = SHORTLIST_DONE.incrementAndGet();
                    // The board moves while this runs, so the shortlist is
                    // redrawn as it goes rather than only at the end.
                    if (done % RECONCILE_EVERY == 0) {
                        RenderQueue.reconcile(server, def);
                    }
                });
        long tier2Ms = (System.nanoTime() - tier2Start) / 1_000_000;
        MultiverseServer.LOGGER.info(
                "roll: {} tier 2 measured {} of {} shortlisted seed(s) in {} ms ({} ms/seed)",
                id.getPath(), measured, shortlist.size(), tier2Ms,
                measured == 0 ? 0 : tier2Ms / (double) measured);
        // The new seeds are already written (measureShortlist banks each as
        // it completes, and every future it submitted has been drained
        // before it returns), so this leaderboard read is the CURRENT bank
        // interleaved with what this roll just added — culling it to
        // BOARD_LIMIT is exactly "top N of new, merged with current,
        // trimmed" in one step.
        if (measured > 0) {
            com.customdimensions.roll.SeedBank.cullToTop(hash, dimension, BOARD_LIMIT,
                    protectedSeeds(def, dimension));
            promoteBest(server, id, hash, dimension, def);
        }
        warnIfSeedInvariant(hash, dimension, id);
        int got = banked(hash, dimension);
        if (got < WANTED && !CANCEL.get()) {
            STARVED.add(id.getPath() + " (" + got + "/" + WANTED + " from " + count
                    + " screened, " + shortlist.size() + " shortlisted)");
            MultiverseServer.LOGGER.warn(
                    "roll: {} kept only {}/{} candidates from {} screened seeds ({} shortlisted) "
                    + "— its gates reject nearly everything",
                    id.getPath(), got, WANTED, count, shortlist.size());
        }
        RenderQueue.reconcile(server, def);
        SURVEYED.incrementAndGet();
        // Each finished dimension is a new thing to look at, so the page is
        // told to refresh now rather than at the end of the run.
        GENERATION.incrementAndGet();
    }

    /**
     * Whether every candidate describes the SAME biome layout.
     *
     * <p>Two seeds of a working dimension differ. A whole board that does not
     * is the signature of a biome source the seed never reaches: the roll
     * measures thousands of seeds, ranks them, and is choosing between
     * copies. It reads as a healthy roll from every counter — seeds screened,
     * seeds banked, scores assigned — because each of those is true.
     *
     * <p>Two non-empty layouts are the minimum that can mean anything. One
     * candidate is not a comparison, and an empty share map is a measurement
     * that did not happen rather than a layout that is identical.
     */
    static boolean allLayoutsIdentical(List<java.util.Map<String, Double>> layouts) {
        if (layouts == null) {
            return false;
        }
        List<java.util.Map<String, Double>> usable = layouts.stream()
                .filter(m -> m != null && !m.isEmpty())
                .toList();
        if (usable.size() < 2) {
            return false;
        }
        java.util.Map<String, Double> first = usable.get(0);
        return usable.stream().allMatch(first::equals);
    }

    /**
     * Says so when a dimension's whole board is biome-identical.
     *
     * <p>Diagnostic only — it never fails a roll or changes what is banked.
     * A detector that can end a four-hour sweep is worse than the fault it
     * looks for, and its own failure must not either, so the gather is
     * wrapped whole.
     */
    private static void warnIfSeedInvariant(String hash, String dimension, Identifier id) {
        try {
            List<java.util.Map<String, Double>> layouts = new java.util.ArrayList<>();
            for (com.customdimensions.roll.SeedBank.CandidateSummary c
                    : com.customdimensions.roll.SeedBank.leaderboard(hash, dimension)) {
                com.customdimensions.facts.SeedFacts facts =
                        com.customdimensions.roll.SeedBank.candidateFacts(hash, dimension, c.seed());
                if (facts != null && facts.biomes() != null) {
                    layouts.add(facts.biomes().shares().value());
                }
            }
            if (allLayoutsIdentical(layouts)) {
                MultiverseServer.LOGGER.warn(
                        "roll: {} banked {} candidates that all share ONE biome layout ({} biome(s)) "
                        + "— the seed is not reaching this dimension's biome source, so every "
                        + "seed it rolls is the same world",
                        id.getPath(), layouts.size(), layouts.get(0).size());
            }
        } catch (RuntimeException e) {
            MultiverseServer.LOGGER.warn(
                    "roll: {} seed-invariance check did not run ({})", id.getPath(), e.toString());
        }
    }

    /**
     * Every scored candidate for this dimension — what a roll stops against,
     * by rank rather than by {@link #SCORE_THRESHOLD}. A gate-rejected seed
     * never reaches {@link com.customdimensions.roll.SeedBank#leaderboard}
     * (it lands in {@code rejected.json} instead), so this counts only what
     * cleared every gate; nothing here weakens a gate.
     */
    private static int banked(String hash, String dimension) {
        return com.customdimensions.roll.SeedBank.leaderboard(hash, dimension).size();
    }

    /**
     * Seeds {@link #BOARD_LIMIT} must never cull, whatever their rank: the
     * world the server actually booted with ({@link SeedRoster.Role#STARTING}),
     * what the config names right now ({@link SeedRoster.Role#CURRENT}), and
     * every seed kept by hand ({@link com.customdimensions.roll.Shortlist}).
     */
    static java.util.Set<Long> protectedSeeds(DimensionConfig def, String dimension) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>(
                com.customdimensions.roll.Shortlist.of(dimension));
        Long starting = def.getSeed();
        if (starting != null) {
            out.add(starting);
        }
        Long current = BankView.currentSeed(def, starting);
        if (current != null) {
            out.add(current);
        }
        return out;
    }

    /**
     * Writes this roll's best banked candidate into the overlay as the
     * dimension's current seed, with the SPAWN THAT CANDIDATE ITSELF
     * MEASURED — never a try-out position (nobody is standing anywhere
     * during an unattended roll) and never the previous overlay's spawn,
     * which would describe a different seed's terrain. Uses {@link
     * Picker#pickWithSpawn}, the same overlay write {@link Picker#pick}
     * ("Use this seed") uses, so it persists across a restart and is what
     * {@link BankView#currentSeed} then reports. Overwrites unconditionally,
     * a manual pick included: the straightforward reading of "the best seed
     * is current" rather than one that has to know how the previous current
     * seed was chosen. Never throws: a failed promote must not take the roll
     * down with it.
     */
    private static void promoteBest(MinecraftServer server, Identifier id, String hash, String dimension,
                                    DimensionConfig def) {
        Long best = bestToPromote(com.customdimensions.roll.SeedBank.leaderboard(hash, dimension));
        if (best == null) {
            return;
        }
        int[] spawn = spawnToPromote(
                com.customdimensions.roll.SeedBank.candidateFacts(hash, dimension, best), def);
        if (spawn == null) {
            MultiverseServer.LOGGER.info(
                    "roll: {} promoting seed {} to current with no spawn — none was recorded for it",
                    id.getPath(), best);
        }
        Picker.Result result = Picker.pickWithSpawn(server, id.getPath(), best, spawn);
        if (!result.ok()) {
            MultiverseServer.LOGGER.warn("Could not auto-promote seed {} for {}: {}",
                    best, id.getPath(), result.message());
        }
    }

    /**
     * The seed a roll should promote to current, given what it banked — the
     * top of the leaderboard, or nothing when the bank is empty. Pure, so
     * the decision is pinned with no server or filesystem.
     */
    static Long bestToPromote(List<com.customdimensions.roll.SeedBank.CandidateSummary> ranked) {
        return ranked.isEmpty() ? null : ranked.get(0).seed();
    }

    /**
     * The spawn a promoted candidate's own measurement recorded, as
     * {@code [x, y, z]} — {@code surfaceHeight} IS the player's feet, not
     * the ground beneath them ({@code FactsEngine}'s own hazard check reads
     * it that way). Null when the candidate is unreadable or either half is
     * unmeasured: an auto-promote must never write half a spawn, or one
     * built from something other than this seed's own facts.
     *
     * <p>The declared column is one column, and a dimension whose void
     * between islands is the point has no ground under it. It fails in two
     * different ways and BOTH reach {@link #spawnFromGrid}, which searches
     * the same candidate's banked grid for a cell that does have ground:
     *
     * <ul>
     *   <li>the generator answers no surface at all, so the height is
     *       absent — the void case;</li>
     *   <li>it answers the dimension's minimum Y rather than nothing
     *       ({@code sampleGrid}'s own comment on this), which is a real
     *       reading but not a place to stand, and writing it verbatim
     *       plants the spawn at bedrock.</li>
     * </ul>
     *
     * <p>Null only when the grid has no ground either. Pure, so the decision
     * is pinned with no server or filesystem.
     */
    static int[] spawnToPromote(com.customdimensions.facts.SeedFacts facts, DimensionConfig def) {
        if (facts == null) {
            return null;
        }
        com.customdimensions.facts.SeedFacts.SpawnFacts spawn = facts.spawn();
        int floorY = assumedFloorY(def);
        if (spawn.column().isPresent() && spawn.surfaceHeight().isPresent()) {
            com.customdimensions.facts.SeedFacts.Column column = spawn.column().value();
            int height = spawn.surfaceHeight().value();
            if (height > floorY) {
                return new int[]{column.x(), height, column.z()};
            }
            MultiverseServer.LOGGER.info(
                    "roll: {} declared spawn column has no real surface for seed {} (height {} at or "
                    + "below floor {}) — searching the banked grid for one",
                    facts.dimension(), facts.seed(), height, floorY);
        } else {
            MultiverseServer.LOGGER.info(
                    "roll: {} declared spawn column was not measured for seed {} ({}) — searching "
                    + "the banked grid for one",
                    facts.dimension(), facts.seed(),
                    spawn.surfaceHeight().isPresent() ? spawn.column().reason()
                            : spawn.surfaceHeight().reason());
        }
        int[] fromGrid = spawnFromGrid(facts, def, floorY);
        if (fromGrid == null) {
            MultiverseServer.LOGGER.info(
                    "roll: {} promoting seed {} with no spawn — the banked grid has no real "
                    + "surface either", facts.dimension(), facts.seed());
        }
        return fromGrid;
    }

    /**
     * The dimension's own floor: {@code environment.minY} when the config
     * sets one, else the vanilla default for the base type {@code
     * DimensionManager#createDimensionOptions} clones (0 for {@code
     * nether}/{@code end}, -64 for every other type — they all clone the
     * overworld's). A live datapack (another mod's End override, say) can
     * still set a REAL floor lower than this guess; {@link #spawnToPromote}
     * tests "at or below", so a low guess here only ever misses a grid
     * search, never triggers a wrong one.
     */
    static int assumedFloorY(DimensionConfig def) {
        DimensionConfig.Environment env = def == null ? null : def.getEnvironment();
        if (env != null && env.minY != null) {
            return env.minY;
        }
        String type = def == null ? null : def.getType();
        return "nether".equalsIgnoreCase(type) || "end".equalsIgnoreCase(type) ? 0 : -64;
    }

    /** Worth walking this many times farther than the nearest cell for a namesake-biome spawn. */
    private static final double NAMESAKE_DISTANCE_FACTOR = 2.0;

    /**
     * A real-ground cell from the candidate's own banked grid, nearest the
     * world origin — the same centre {@code FactsEngine#sampleGrid} samples
     * around — preferring a {@code seedRoll.spawnFilter} biome within {@link
     * #NAMESAKE_DISTANCE_FACTOR} times that distance: a spawn already in a
     * namesake biome is what {@code spawn_reads_as_namesake} awards full
     * marks for. Null when no cell in the grid has ground either. Pure, so
     * the search and the world-coordinate conversion (matched exactly to
     * {@code sampleGrid}'s own formula) are pinned with no server.
     */
    static int[] spawnFromGrid(com.customdimensions.facts.SeedFacts facts, DimensionConfig def, int floorY) {
        if (!facts.grid().isPresent()) {
            return null;
        }
        com.customdimensions.facts.SeedFacts.Grid grid = facts.grid().value();
        int side = grid.side();
        if (side < 2) {
            return null;
        }
        int step = Math.max(1, (facts.playableRadius() * 2) / (side - 1));
        int half = side / 2;
        java.util.Set<String> namesake = namesakeBiomes(def);

        List<Integer> heights = grid.height();
        List<Integer> biomeIdx = grid.biome();
        List<String> biomeIds = grid.biomeIds();

        int[] bestAny = null;
        double bestAnyDist2 = Double.MAX_VALUE;
        int[] bestNamesake = null;
        double bestNamesakeDist2 = Double.MAX_VALUE;
        for (int i = 0; i < heights.size(); i++) {
            Integer h = heights.get(i);
            if (h == null || h <= floorY) {
                continue;
            }
            int x = (i % side - half) * step;
            int z = (i / side - half) * step;
            double dist2 = (double) x * x + (double) z * z;
            if (dist2 < bestAnyDist2) {
                bestAnyDist2 = dist2;
                bestAny = new int[]{x, h, z};
            }
            Integer bi = i < biomeIdx.size() ? biomeIdx.get(i) : null;
            String biomeId = bi != null && bi < biomeIds.size() ? biomeIds.get(bi) : null;
            if (biomeId != null && namesake.contains(biomeId) && dist2 < bestNamesakeDist2) {
                bestNamesakeDist2 = dist2;
                bestNamesake = new int[]{x, h, z};
            }
        }
        if (bestAny == null) {
            return null;
        }
        return bestNamesake != null && bestNamesakeDist2 <= bestAnyDist2 * NAMESAKE_DISTANCE_FACTOR
                ? bestNamesake : bestAny;
    }

    private static java.util.Set<String> namesakeBiomes(DimensionConfig def) {
        DimensionConfig.SeedRoll sr = def == null ? null : def.getSeedRoll();
        return sr == null || sr.spawnFilter == null
                ? java.util.Set.of() : new java.util.LinkedHashSet<>(sr.spawnFilter);
    }

    /**
     * Draws one candidate's map. A low-res render reads the candidate's own
     * persisted grid; a high-res one samples a fresh, wider grid and can run
     * past a minute — both go on a background thread, which is what makes the
     * expensive one safe to ask for at all (on the server thread the tick
     * watchdog could act on it).
     *
     * @return null when the render was queued, or why it refused
     */
    public static String render(MinecraftServer server, String dimensionSlug, long seed,
                                boolean highres) {
        DimensionConfig def = BankView.resolve(dimensionSlug);
        if (def == null) {
            return "no configured dimension " + dimensionSlug;
        }
        if (!RENDERING.compareAndSet(false, true)) {
            return "a render is already running";
        }
        Identifier id = def.getDimensionIdentifier();
        // The identifier's path, not the config's name: the card spells its
        // own slug that way ({@link BankView.DimensionView#slug}), and this
        // is compared against it character for character in the browser.
        // Set before the thread starts, so the poll that lands between the
        // claim and the first sample already names the card being drawn.
        RENDERING_DETAIL.set(highres ? id.getPath() + "/" + seed : "");
        RENDER_STARTED.set(System.currentTimeMillis());
        Thread worker = new Thread(() -> {
            try {
                com.customdimensions.roll.CandidateRender.Resolution resolution = highres
                        ? com.customdimensions.roll.CandidateRender.Resolution.HIGHRES
                        : com.customdimensions.roll.CandidateRender.Resolution.LOWRES;
                render(server, def, id, seed, resolution);
                GENERATION.incrementAndGet();
            } catch (Exception e) {
                MultiverseServer.LOGGER.error("Render failed for {} seed {}", id, seed, e);
                ERROR.set("render " + id.getPath() + ": " + e);
            } finally {
                RENDERING_DETAIL.set("");
                RENDERING.set(false);
            }
        }, "customdim-render");
        worker.setDaemon(true);
        worker.start();
        return null;
    }

    private static void render(MinecraftServer server, DimensionConfig def, Identifier id, long seed,
                               com.customdimensions.roll.CandidateRender.Resolution resolution)
            throws java.io.IOException {
        java.nio.file.Path path = com.customdimensions.roll.SeedBank.candidateImagePath(
                com.customdimensions.command.InputHash.of(def, server), id.toString(), seed, resolution);
        com.customdimensions.roll.CandidateRender.render(server, id, def, seed, resolution, path);
    }

    /** The status shape the viewer's roller controls poll. */
    public static String statusJson() {
        StringBuilder b = new StringBuilder("{");
        b.append("\"running\": ").append(RUNNING.get());
        b.append(", \"target\": ").append(TARGET.get());
        b.append(", \"rolled\": ").append(ROLLED.get());
        b.append(", \"surveyed\": ").append(SURVEYED.get());
        b.append(", \"passed\": ").append(PASSED.get());
        b.append(", \"shortlisted\": ").append(SHORTLISTED.get());
        b.append(", \"shortlist_done\": ").append(SHORTLIST_DONE.get());
        b.append(", \"dimensions\": ").append(DIMENSIONS.get());
        b.append(", \"generation\": ").append(GENERATION.get());
        b.append(", \"stage\": ").append(Json.quote(STAGE.get()));
        b.append(", \"current\": ").append(Json.quote(CURRENT.get()));
        b.append(", \"render_pending\": ").append(RenderQueue.pending());
        // Split out because the two are not interchangeable: thumbnails are
        // what makes the page reviewable, and a detail render yields to them.
        b.append(", \"thumbnails_pending\": ").append(RenderQueue.thumbnailsPending());
        b.append(", \"render_paused_low\": ").append(RenderQueue.lowPaused());
        b.append(", \"render_paused_high\": ").append(RenderQueue.highPaused());
        b.append(", \"rendering_low\": [").append(RenderQueue.current().isEmpty()
                ? "" : Json.quote(RenderQueue.current())).append("]");
        // The one candidate whose detail map is being drawn, spelled exactly
        // as its card identifies itself (<slug>/<seed>) so the modal can tell
        // ITS render from somebody else's and report only its own.
        String detail = RENDERING_DETAIL.get();
        b.append(", \"rendering_high\": [")
                .append(detail.isEmpty() ? "" : Json.quote(detail)).append("]");
        b.append(", \"rendering_high_seconds\": ").append(detail.isEmpty() ? 0
                : Math.max(0, (System.currentTimeMillis() - RENDER_STARTED.get()) / 1000));
        // Named, not counted: "12 dimensions came up short" is not something
        // anyone can act on, and a dimension that never yields a candidate is
        // the one thing a roll must not report as merely finished.
        b.append(", \"starved\": [");
        int i = 0;
        for (String s : STARVED) {
            b.append(i++ > 0 ? ", " : "").append(Json.quote(s));
        }
        b.append("]");
        b.append(", \"error\": ").append(Json.quote(ERROR.get()));
        return b.append("}\n").toString();
    }
}
