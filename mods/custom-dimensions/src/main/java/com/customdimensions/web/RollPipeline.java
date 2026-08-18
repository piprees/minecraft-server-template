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
 * <p>Tier-1 measurement is budgeted per SEED, not per dimension: every
 * dimension's {@link Roller#screenShortlist} call shares one {@code
 * ExecutorService} sized by {@link #workers()}, so one dimension in flight
 * gets the whole budget and eighty share it, rather than each dimension
 * claiming a core of its own regardless of how many others are running.
 * Dimension orchestration itself is cheap — each of its threads mostly
 * waits on that shared pool — so {@link #run} gives every target dimension
 * its own orchestration thread rather than capping that count too. CANCEL
 * and a FOCUS-yield are checked once per tier-1 batch and once per
 * shortlisted seed in the tier-2 loop below it, where a single measurement
 * still runs to about a hundred core-seconds on a modded dimension.
 * {@code generation} is bumped each time a dimension finishes, which is the
 * browser's cue to refetch the grid.
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
     * Scored candidates a dimension needs before a roll leaves it alone,
     * matched to {@link RenderQueue#KEEP} — a dimension shows only that many
     * ranked seeds beside its named ones, so a sixth costs seeds and shows
     * nobody anything.
     *
     * <p>Rank decides what counts, not an absolute score: {@link #banked}
     * returns every seed that cleared this dimension's gates, ranked
     * descending by {@link com.customdimensions.roll.SeedBank#leaderboard}, so
     * a roll stops once it holds its best {@code WANTED} rather than spending
     * its whole per-dimension seed budget chasing {@link #SCORE_THRESHOLD} on
     * every roll.
     */
    private static final int WANTED = RenderQueue.KEEP;

    /** Dimensions that spent their seeds without reaching {@link #WANTED}. */
    private static final java.util.Set<String> STARVED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
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
    private static final AtomicInteger ROLLED = new AtomicInteger();
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
        // the run they are starting now. Left set, it makes every OTHER
        // dimension yield or abandon on its first check.
        FOCUS.set("");
        UNSERVED_FOCUS.set("");
        STARVED.clear();
        // A ceiling, not a plan: a full sweep skips a dimension that already
        // holds WANTED candidates, so it rolls far fewer seeds than this. A
        // top-up rolls every dimension it was given and comes closer.
        TARGET.set(count * targets.size());
        DIMENSIONS.set(targets.size());
        ROLLED.set(0);
        SURVEYED.set(0);
        STAGE.set("rolling");
        com.customdimensions.roll.CandidateRender.rolling(true);
        // Naming dimensions — one, or a filtered list of them — is a request
        // for MORE candidates, so each rolls whatever it already holds. A
        // sweep over the whole pack is a resume, and skips the boards that
        // are already full.
        boolean topUp = ordered || (dimension != null && !dimension.isBlank());
        Thread worker = new Thread(() -> run(server, targets, count, topUp), "customdim-roll");
        worker.setDaemon(true);
        worker.start();
        return null;
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
        Thread starter = new Thread(() -> {
            List<DimensionConfig> targets = BankView.rollTargets();
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
            } finally {
                pool.shutdownNow();
                GENERATION.incrementAndGet();
            }
        }, "customdim-roll");
        starter.setDaemon(true);
        starter.start();
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

    private static void run(MinecraftServer server, List<DimensionConfig> targets, int count,
                            boolean topUp) {
        int budget = workers();
        // One thread per target dimension. These threads mostly block on
        // measurePool below rather than doing CPU work themselves, so their
        // count is not the CPU budget and does not need to be capped by it.
        int orchestrators = targets.size();
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
                        rollOne(server, def, count, topUp, measurePool, budget);
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
     * The focused dimension while it is still waiting for a worker — what a
     * yield is actually for, and the only thing a worker should step aside
     * over.
     *
     * <p>Yielding on {@link #FOCUS} itself makes EVERY worker abandon, when
     * one stepping aside is enough to serve the focus: the other nine then
     * cycle the queue abandoning each dimension in turn, and a focus on a
     * dimension this run will never hand out (already surveyed, or not a roll
     * target) never stops. {@link #nextTarget} clears this the moment the
     * focused dimension is handed out or found absent, so a yield is bounded
     * to the handful of workers that check before the claim lands.
     */
    private static final java.util.concurrent.atomic.AtomicReference<String> UNSERVED_FOCUS =
            new java.util.concurrent.atomic.AtomicReference<>("");

    /** Whether this dimension should step aside for one somebody has just opened. */
    private static boolean yieldTo(DimensionConfig def) {
        String want = UNSERVED_FOCUS.get();
        return !want.isEmpty() && !want.equals(def.getName());
    }

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
        UNSERVED_FOCUS.set(want);
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
     * The next dimension to roll: the focused one if it is still waiting,
     * otherwise the one at the head.
     *
     * <p>Checked at each pull rather than fixed at submission, so opening a
     * dimension mid-roll promotes it. A seed in flight is never abandoned —
     * one measurement is a second or so, and abandoning it would throw away
     * work to save less than it cost.
     */
    /** Dimensions that stepped aside for a focused one, to be picked up again. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<DimensionConfig> YIELDED =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static DimensionConfig nextTarget(java.util.Deque<DimensionConfig> pending) {
        synchronized (pending) {
            for (DimensionConfig back = YIELDED.poll(); back != null; back = YIELDED.poll()) {
                pending.addLast(back);
            }
            if (pending.isEmpty() || CANCEL.get()) {
                return null;
            }
            String want = FOCUS.get();
            if (!want.isEmpty()) {
                for (java.util.Iterator<DimensionConfig> it = pending.iterator(); it.hasNext();) {
                    DimensionConfig def = it.next();
                    if (want.equals(def.getName())) {
                        it.remove();
                        UNSERVED_FOCUS.compareAndSet(want, "");
                        return def;
                    }
                }
                // Nothing here can serve it: it is already surveyed, in flight
                // with another worker, or not a target of this run. Every case
                // means stepping aside for it achieves nothing.
                UNSERVED_FOCUS.compareAndSet(want, "");
            }
            return pending.poll();
        }
    }

    /**
     * Rolls one dimension until it holds {@link #WANTED} candidates, or its
     * pool of seeds is spent.
     *
     * <p>Two tiers, not a flat search. {@link Roller#screenShortlist} sweeps
     * {@code count} seeds cheaply — no per-seed terrain router — and ranks
     * the best {@link Roller#SHORTLIST}, measuring on {@code measurePool}
     * (up to {@code measureParallelism} seeds at once, shared with every
     * other dimension currently screening — see {@link #run}); the loop
     * below, over the shortlist alone, calls {@link Roller#measureOne} — a
     * full measurement, ~a hundred core-seconds on a modded dimension — one
     * at a time, exactly as a roll always has, and stops the moment
     * {@link #WANTED} is banked rather than spending the rest of the
     * shortlist. A dimension that already has enough is skipped before
     * either tier runs.
     *
     * <p>{@code count} is now the tier-1 POOL size, not a ceiling that never
     * bound: the sweep is a search over the whole pool, not a stream that
     * stopped at the first few survivors, so a dimension whose gates reject
     * nearly everything still gets a fair shortlist to draw from.
     * {@link #STARVED} records a dimension that fell short even so.
     */
    private static void rollOne(MinecraftServer server, DimensionConfig def, int count,
                                boolean topUp, java.util.concurrent.ExecutorService measurePool,
                                int measureParallelism) {
        Identifier id = def.getDimensionIdentifier();
        String hash = com.customdimensions.command.InputHash.of(def, server);
        String dimension = id.toString();
        // The seeds this dimension already has, before any it might get. They
        // are what the rest of the board is compared against, so an unscored
        // one makes the whole comparison unavailable — and a full board is no
        // reason to skip it, since the configured seed is not a candidate and
        // never counted toward WANTED.
        CURRENT.set(id.getPath());
        STAGE.set("scoring the named seeds for " + id.getPath());
        measureNamed(server, def);
        if (!topUp && banked(hash, dimension) >= WANTED) {
            RenderQueue.reconcile(server, def);
            SURVEYED.incrementAndGet();
            return;
        }
        STAGE.set("screening " + id.getPath());
        long tier1Start = System.nanoTime();
        java.util.List<Long> shortlist;
        int screened;
        // The screen is thousands of seeds and minutes long, so it watches the
        // same two signals the tier-2 loop below does rather than running to
        // the end of the pool regardless.
        java.util.function.BooleanSupplier abandonScreen = () -> CANCEL.get() || yieldTo(def);
        try {
            Roller.Screen screen = Roller.screenShortlist(server, id, def, count, abandonScreen,
                    measurePool, measureParallelism);
            shortlist = screen.shortlist();
            screened = screen.screened();
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
        // ran, because the run was cancelled or the queue was told to serve a
        // different dimension first. Re-queue rather than record a starved
        // board: an empty shortlist skips the tier-2 loop entirely, so the
        // yield inside it would never be reached. Its seeds are not counted
        // either; a run that reported them would show progress it never made.
        if (screened == 0 && count > 0 && shortlist.isEmpty()) {
            if (CANCEL.get()) {
                return;
            }
            YIELDED.offer(def);
            return;
        }
        ROLLED.addAndGet(count);
        STAGE.set("rolling " + id.getPath());
        long tier2Start = System.nanoTime();
        int measured = 0;
        // EVERY shortlisted seed, not the first WANTED of them. Tier 1 ranks on
        // structures and biome alone, so its order is not the order the full
        // scorecard produces — stopping once the board is full would bank the
        // first five of the shortlist rather than its best five, and the
        // difference is exactly what tier 2 exists to find. SeedBank keeps
        // every card and leaderboard sorts descending, so the board is the top
        // WANTED by FINAL score once all ten are in.
        for (long seed : shortlist) {
            if (CANCEL.get()) {
                break;
            }
            // Yield to a dimension somebody has just opened. Re-queued, never
            // dropped: a focus that resumes this dimension later re-screens a
            // fresh pool rather than resuming the part-drawn shortlist — tier
            // 1 is cheap enough that the redraw costs little, and carrying a
            // shortlist across a yield would need state this method has no
            // other reason to keep.
            if (yieldTo(def)) {
                YIELDED.offer(def);
                return;
            }
            try {
                Roller.measureOne(server, id, def, seed);
            } catch (RuntimeException e) {
                MultiverseServer.LOGGER.error("Roll failed for {}", id, e);
                ERROR.set(id.getPath() + ": " + e);
                break;
            }
            measured++;
            // The board moves while this runs, so the shortlist is redrawn as
            // it goes rather than only at the end — the top ten are lookable
            // long before the roll finishes.
            if (measured % RECONCILE_EVERY == 0) {
                RenderQueue.reconcile(server, def);
            }
        }
        long tier2Ms = (System.nanoTime() - tier2Start) / 1_000_000;
        MultiverseServer.LOGGER.info(
                "roll: {} tier 2 measured {} of {} shortlisted seed(s) in {} ms ({} ms/seed)",
                id.getPath(), measured, shortlist.size(), tier2Ms,
                measured == 0 ? 0 : tier2Ms / (double) measured);
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
        b.append(", \"dimensions\": ").append(DIMENSIONS.get());
        b.append(", \"generation\": ").append(GENERATION.get());
        b.append(", \"stage\": ").append(Json.quote(STAGE.get()));
        b.append(", \"current\": ").append(Json.quote(CURRENT.get()));
        b.append(", \"render_pending\": ").append(RenderQueue.pending());
        // Split out because the two are not interchangeable: thumbnails are
        // what makes the page reviewable, and a detail render yields to them.
        b.append(", \"thumbnails_pending\": ").append(RenderQueue.thumbnailsPending());
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
