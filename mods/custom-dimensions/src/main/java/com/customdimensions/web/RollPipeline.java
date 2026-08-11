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

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL = new AtomicBoolean(false);
    private static final AtomicBoolean RENDERING = new AtomicBoolean(false);
    private static final AtomicInteger TARGET = new AtomicInteger();
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
        if (!RUNNING.compareAndSet(false, true)) {
            return "a roll is already running";
        }
        CANCEL.set(false);
        ERROR.set("");
        TARGET.set(count * targets.size());
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
     * dimensions and there is no reason to leave a machine's cores idle while
     * a pack of eighty takes hours on one. One core is left for the server
     * thread and one for everything else.
     */
    private static int workers() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
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
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (DimensionConfig def : targets) {
                futures.add(pool.submit(() -> rollOne(server, def, count)));
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
            STAGE.set(CANCEL.get() ? "stopped" : "done");
            CURRENT.set("");
            RUNNING.set(false);
            GENERATION.incrementAndGet();
        }
    }

    private static void rollOne(MinecraftServer server, DimensionConfig def, int count) {
        Identifier id = def.getDimensionIdentifier();
        CURRENT.set(id.getPath());
        STAGE.set("rolling " + id.getPath());
        int done = 0;
        while (done < count && !CANCEL.get()) {
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
        RenderQueue.reconcile(server, def);
        SURVEYED.incrementAndGet();
        // Each finished dimension is a new thing to look at, so the page is
        // told to refresh now rather than at the end of the run.
        GENERATION.incrementAndGet();
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
        b.append(", \"generation\": ").append(GENERATION.get());
        b.append(", \"stage\": ").append(Json.quote(STAGE.get()));
        b.append(", \"current\": ").append(Json.quote(CURRENT.get()));
        b.append(", \"render_pending\": ").append(RenderQueue.pending());
        b.append(", \"rendering_low\": [").append(RenderQueue.current().isEmpty()
                ? "" : Json.quote(RenderQueue.current())).append("]");
        b.append(", \"error\": ").append(Json.quote(ERROR.get()));
        return b.append("}\n").toString();
    }
}
