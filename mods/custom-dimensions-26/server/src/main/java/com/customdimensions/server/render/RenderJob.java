package com.customdimensions.server.render;

import com.customdimensions.core.render.ColumnSamples;
import com.customdimensions.core.render.RenderFiles;
import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.core.render.RenderLayers;
import com.customdimensions.server.CustomDimensions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * One render at a time, off the server thread (RB-28): the {@link Sampler} is
 * built, the grid sampled in bands of rows and the files written on a pool of
 * {@link #threads()} low-priority daemon threads. The listener is called on
 * the server thread.
 */
public final class RenderJob {
    /** Rows per band, the unit a worker takes. */
    static final int BAND_ROWS = 8;
    /** {@code -Dcustomdimensions.render.threads}; at most 2 by default, fewer on a machine with under 4 cores. */
    public static final String THREADS_PROPERTY = "customdimensions.render.threads";
    public static final String SURFACE = "OCEAN_FLOOR_WG: one above the top motion-blocking block; "
            + "under a roof, one above the first motion-blocking block below the roof's underside";
    public static final String BIOME_Y = "the surface height, clamped into the dimension (the generator's noise biome there)";

    private static final AtomicReference<RenderJob> RUNNING = new AtomicReference<>();

    /** What to render; a null seed takes the dimension's own, a null step the smallest that fits. */
    public record Request(String dimension, Long seed, RenderGrid grid, Path outputDir) {}

    public record Result(String dimension, long seed, RenderGrid grid, RenderFiles.Written files, long sampleMillis,
                         long totalMillis, long columnsPerSecond, int biomes, int threads) {}

    public interface Listener {
        void progress(RenderJob job, int percent);

        void finished(RenderJob job, Result result);

        void failed(RenderJob job, Throwable error);
    }

    private final Request request;
    private final int threads;
    private final AtomicLong done = new AtomicLong();
    private final CompletableFuture<Result> future = new CompletableFuture<>();
    private volatile long seed;

    private RenderJob(Request request, int threads) {
        this.request = request;
        this.threads = threads;
    }

    /** The render in progress, if any. */
    public static Optional<RenderJob> running() {
        return Optional.ofNullable(RUNNING.get());
    }

    public static int defaultThreads() {
        Integer set = Integer.getInteger(THREADS_PROPERTY);
        if (set != null && set > 0) return set;
        return Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 2));
    }

    /** {@code <game dir>/renders/<dimension>/}. */
    public static Path outputDir(String dimension) {
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().resolve("renders").resolve(dimension);
    }

    /**
     * Starts {@code request} unless a render is running, in which case it
     * returns empty. {@code sampler} runs on a worker thread.
     */
    public static Optional<RenderJob> start(MinecraftServer server, Request request, Supplier<Sampler> sampler,
                                            Listener listener) {
        RenderJob job = new RenderJob(request, defaultThreads());
        if (!RUNNING.compareAndSet(null, job)) return Optional.empty();
        job.run(server, sampler, listener);
        return Optional.of(job);
    }

    public Request request() {
        return request;
    }

    public int threads() {
        return threads;
    }

    public int percent() {
        return (int) (done.get() * 100 / Math.max(1, request.grid().columns()));
    }

    /** Completes on the server thread, after the listener has heard. */
    public CompletableFuture<Result> future() {
        return future;
    }

    private void run(MinecraftServer server, Supplier<Sampler> samplerSupplier, Listener listener) {
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "customdim-render-" + counter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        long start = System.nanoTime();
        RenderGrid grid = request.grid();
        CompletableFuture.supplyAsync(samplerSupplier, pool).thenCompose(sampler -> {
            seed = sampler.seed();
            long prepared = System.nanoTime();
            ColumnSamples samples = new ColumnSamples(grid, sampler.minY(), sampler.maxY(), sampler.seaLevel());
            AtomicInteger reported = new AtomicInteger();
            List<CompletableFuture<Void>> bands = new ArrayList<>();
            for (RenderGrid.Band band : grid.bands(BAND_ROWS)) {
                bands.add(CompletableFuture.runAsync(() -> {
                    for (int py = band.firstRow(); py < band.firstRow() + band.rows(); py++) {
                        for (int px = 0; px < grid.side(); px++) {
                            Sampler.Column c = sampler.sample(grid.x(px), grid.z(py));
                            samples.set(px, py, c.height(), c.fluid(), c.biomeId());
                            if (sampler.hasZones()) {
                                Sampler.ZoneHit zone = sampler.topZone(grid.x(px), grid.z(py));
                                samples.setZone(px, py, zone.index(), zone.weight());
                            }
                        }
                        done.addAndGet(grid.side());
                    }
                    int quarter = percent() / 25;
                    int previous = reported.get();
                    if (quarter > previous && quarter < 4 && reported.compareAndSet(previous, quarter)) {
                        server.execute(() -> listener.progress(this, quarter * 25));
                    }
                }, pool));
            }
            return CompletableFuture.allOf(bands.toArray(CompletableFuture[]::new)).thenApplyAsync(v -> {
                long sampled = System.nanoTime();
                long sampleMs = (sampled - prepared) / 1_000_000;
                Map<String, Long> timings = new LinkedHashMap<>();
                timings.put("prepare", (prepared - start) / 1_000_000);
                timings.put("sample", sampleMs);
                RenderFiles.Written files;
                try {
                    files = RenderFiles.write(request.outputDir(), samples, RenderLayers.forSamples(samples),
                            new RenderFiles.Meta(sampler.dimension(), sampler.seed(), sampler.seedSource(), SURFACE, BIOME_Y,
                                    timings, threads));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                long total = (System.nanoTime() - start) / 1_000_000;
                return new Result(sampler.dimension(), sampler.seed(), grid, files, sampleMs, total,
                        sampleMs <= 0 ? 0 : grid.columns() * 1000 / sampleMs, samples.biomeCounts().size(), threads);
            }, pool);
        }).whenComplete((result, error) -> {
            pool.shutdown();
            RUNNING.compareAndSet(this, null);
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                CustomDimensions.LOG.warn("render of {} failed", request.dimension(), cause);
                server.execute(() -> {
                    listener.failed(this, cause);
                    future.completeExceptionally(cause);
                });
            } else {
                CustomDimensions.LOG.info("rendered {} seed {}: {} columns in {} ms ({} columns/s on {} threads), {} biomes -> {}",
                        result.dimension(), result.seed(), grid.columns(), result.sampleMillis(), result.columnsPerSecond(),
                        threads, result.biomes(), request.outputDir());
                server.execute(() -> {
                    listener.finished(this, result);
                    future.complete(result);
                });
            }
        });
    }

    /** The seed being rendered, once the sampler is built. */
    public long seed() {
        return seed;
    }
}
