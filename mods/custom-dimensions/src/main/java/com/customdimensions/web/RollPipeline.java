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
 * <p>A run is a background thread calling {@link Roller#rollDimension} — the
 * same search the bank was always built by. It measures headlessly (no
 * {@code ServerWorld}, no chunk load), so it never touches the tick loop; the
 * server stays playable and a try-out running beside it is unaffected.
 *
 * <p>Seeds are rolled in small batches so a stop is honoured within seconds
 * and the counter moves while a long run is going. {@code generation} is
 * bumped each time a dimension finishes, which is the browser's cue to
 * refetch the grid — a batch kicked off up front fills in as it goes rather
 * than appearing all at once at the end.
 *
 * <p>One run at a time. Two concurrent searches would compete for the same
 * candidate directory and neither would be faster.
 */
public final class RollPipeline {

    /**
     * Seeds per {@link Roller#rollDimension} call: the stop and progress
     * granularity. One, because a single measurement runs from under a second
     * to half a minute depending on the dimension — the re-read of the
     * already-tried set each call costs is nothing beside that, and a counter
     * that only moves every fifth seed reads as a stall.
     */
    private static final int BATCH = 1;

    /**
     * Seeds between shortlist reconciles. Cheap (a directory read) but not
     * free, and the board rarely changes on every single seed.
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
     * Starts a run. {@code dimension} names one dimension (bare slug or full
     * id) or is null for every rollable dimension in the pack.
     *
     * @return null on success, or why it refused
     */
    public static String start(MinecraftServer server, String dimension, int count) {
        if (count <= 0) {
            return "count must be at least 1";
        }
        List<DimensionConfig> targets = resolve(dimension);
        if (targets.isEmpty()) {
            return dimension == null ? "nothing rollable in this pack"
                    : "no rollable dimension named " + dimension;
        }
        // Emptiest first. Workers take from the head of this list, so the
        // dimensions with nothing to show start immediately rather than
        // waiting behind eighty others — and a run stopped halfway has spent
        // its time on the boards that needed it.
        targets.sort(java.util.Comparator.comparingInt(
                def -> banked(com.customdimensions.command.InputHash.of(def, server),
                        def.getDimensionIdentifier().toString())));
        if (!RUNNING.compareAndSet(false, true)) {
            return "a roll is already running";
        }
        CANCEL.set(false);
        ERROR.set("");
        STARVED.clear();
        // A ceiling, not a plan: a dimension stops at WANTED candidates, so a
        // run that finds them early rolls far fewer seeds than this.
        TARGET.set(count * targets.size());
        DIMENSIONS.set(targets.size());
        ROLLED.set(0);
        SURVEYED.set(0);
        STAGE.set("rolling");
        Thread worker = new Thread(() -> run(server, targets, count), "customdim-roll");
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
                        RenderQueue.reconcile(server, def, false);
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
            if (dimension == null || dimension.isBlank()
                    || dimension.equals(def.getName())
                    || dimension.equals(def.getDimensionIdentifier().toString())) {
                out.add(def);
            }
        }
        return out;
    }

    /**
     * How many dimensions are measured at once.
     *
     * <p>A measurement is pure CPU with no {@code ServerWorld} and no shared
     * mutable state — each dimension reads its own candidate directory and
     * writes only into it — so the search is embarrassingly parallel across
     * dimensions. It takes what the renderer is not using: two cores are left
     * for the server thread and everything else, and
     * {@link com.customdimensions.roll.CandidateRender#RENDER_CORES} for the
     * maps, which run beside the search rather than after it.
     */
    private static int workers() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2
                - com.customdimensions.roll.CandidateRender.RENDER_CORES);
    }

    private static void run(MinecraftServer server, List<DimensionConfig> targets, int count) {
        int workers = Math.min(workers(), targets.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                workers, r -> {
                    Thread t = new Thread(r, "customdim-roll");
                    t.setDaemon(true);
                    return t;
                });
        MultiverseServer.LOGGER.info("roll: {} dimension(s) x {} seed(s) across {} worker(s)",
                targets.size(), count, workers);
        // Pulled, not pre-submitted. Submitting every dimension up front fixes
        // the order at start, and the order has to be able to change: opening a
        // dimension in the viewer is a statement that it is the one being
        // looked at, and it should be the one being rolled.
        final java.util.Deque<DimensionConfig> pending = new java.util.ArrayDeque<>(targets);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    DimensionConfig def;
                    while ((def = nextTarget(pending)) != null) {
                        rollOne(server, def, count);
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
            RUNNING.set(false);
            // The boards have stopped moving, so the detail maps held back
            // during the run can now be drawn without being abandoned. Skipped
            // on a stop: somebody who stopped a roll wants the machine back.
            if (!CANCEL.get()) {
                STAGE.set("drawing detail maps");
                for (DimensionConfig def : targets) {
                    RenderQueue.reconcile(server, def, true);
                }
            }
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
     * <p>Opening one also queues its detail maps: those are not queued during
     * a roll, because a detail map cannot survive a board that keeps moving —
     * so the act of opening a dimension is what asks for them.
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
            RenderQueue.reconcile(server, def, true);
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
                        return def;
                    }
                }
            }
            return pending.poll();
        }
    }

    /**
     * Rolls one dimension until it holds {@link #WANTED} candidates, or its
     * share of seeds runs out.
     *
     * <p>Seeds go where they are NEEDED, not equally. Yields differ by two
     * orders of magnitude across the pack — {@code the_burning_archipelago}
     * banks a candidate from nearly every seed while {@code the_abyssal_shrine}
     * clears its gates about once in 250 — so an equal split spends most of a
     * run topping up boards that were already full and leaves the empty ones
     * empty. A dimension that already has enough is skipped outright.
     *
     * <p>{@code count} is the per-dimension seed budget, which is what bounds
     * a dimension whose gates reject nearly everything: it stops, and
     * {@link #STARVED} records that it stopped short so the page can say so
     * rather than looking merely unlucky.
     */
    private static void rollOne(MinecraftServer server, DimensionConfig def, int count) {
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
        if (banked(hash, dimension) >= WANTED) {
            RenderQueue.reconcile(server, def);
            SURVEYED.incrementAndGet();
            return;
        }
        STAGE.set("rolling " + id.getPath());
        int done = 0;
        // Stops at WANTED candidates, and that is what makes a roll finish. A
        // seed measurement costs around a hundred core-seconds on a modded
        // dimension, so `count` is a ceiling that never binds in practice —
        // spending it would be 9 seeds a minute against a 5000-seed budget.
        // The board is therefore the first WANTED that clear the gates, ranked
        // by SeedBank's descending sort rather than chosen by a search.
        while (done < count && !CANCEL.get() && banked(hash, dimension) < WANTED) {
            // Yield to a dimension somebody has just opened. Checked between
            // batches, so a worker grinding a slow dimension frees up in
            // seconds rather than when its whole budget is spent — a focus
            // that waits minutes for a slot is not "it starts rolling".
            // Re-queued, never dropped: it resumes with the seeds it has
            // left once the focused one is served. Same shape as a detail
            // render abandoning itself the moment a thumbnail is owed.
            String want = FOCUS.get();
            if (!want.isEmpty() && !want.equals(def.getName())) {
                YIELDED.offer(def);
                return;
            }
            int batch = Math.min(BATCH, count - done);
            try {
                Roller.rollDimension(server, id, def, batch);
            } catch (RuntimeException e) {
                MultiverseServer.LOGGER.error("Roll failed for {}", id, e);
                ERROR.set(id.getPath() + ": " + e);
                break;
            }
            done += batch;
            ROLLED.addAndGet(batch);
            // The board moves while this runs, so the shortlist is redrawn as
            // it goes rather than only at the end — the top ten are lookable
            // long before the roll finishes.
            if (done % RECONCILE_EVERY == 0) {
                RenderQueue.reconcile(server, def);
            }
        }
        int got = banked(hash, dimension);
        if (got < WANTED && !CANCEL.get()) {
            STARVED.add(id.getPath() + " (" + got + "/" + WANTED + " from " + done + " seeds)");
            MultiverseServer.LOGGER.warn(
                    "roll: {} kept only {}/{} candidates from {} seeds — its gates reject nearly everything",
                    id.getPath(), got, WANTED, done);
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
