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
    private final CompletableFuture<Object> finished = new CompletableFuture<>();
    private volatile long seed;
    private final AtomicInteger reported = new AtomicInteger();
    private volatile long units;

    private RenderJob(Request request, int threads) {
        this.request = request;
        this.threads = threads;
        this.units = request.grid() == null ? 1 : request.grid().columns();
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
        return (int) Math.min(100, done.get() * 100 / Math.max(1, units));
    }

    /** Completes on the server thread, after the listener has heard. */
    public CompletableFuture<Result> future() {
        return future;
    }

    /** Completes on the server thread with whatever the job's work returned, after its listener ran. */
    public CompletableFuture<Object> finished() {
        return finished;
    }

    private void run(MinecraftServer server, Supplier<Sampler> samplerSupplier, Listener listener) {
        RenderGrid grid = request.grid();
        long start = System.nanoTime();
        execute(server, (self, pool) -> {
            Sampler sampler = samplerSupplier.get();
            seed = sampler.seed();
            long prepared = System.nanoTime();
            ColumnSamples samples = sample(sampler, grid, pool, percent -> server.execute(() -> listener.progress(this, percent)));
            long sampleMs = (System.nanoTime() - prepared) / 1_000_000;
            Map<String, Long> timings = new LinkedHashMap<>();
            timings.put("prepare", (prepared - start) / 1_000_000);
            timings.put("sample", sampleMs);
            RenderFiles.Written files = RenderFiles.write(request.outputDir(), samples, RenderLayers.standard(),
                    new RenderFiles.Meta(sampler.dimension(), sampler.seed(), sampler.seedSource(), SURFACE, BIOME_Y,
                            timings, threads));
            long total = (System.nanoTime() - start) / 1_000_000;
            Result result = new Result(sampler.dimension(), sampler.seed(), grid, files, sampleMs, total,
                    sampleMs <= 0 ? 0 : grid.columns() * 1000 / sampleMs, samples.biomeCounts().size(), threads);
            CustomDimensions.LOG.info("rendered {} seed {}: {} columns in {} ms ({} columns/s on {} threads), {} biomes -> {}",
                    result.dimension(), result.seed(), grid.columns(), result.sampleMillis(), result.columnsPerSecond(),
                    threads, result.biomes(), request.outputDir());
            return result;
        }, result -> {
            listener.finished(this, result);
            future.complete(result);
        }, error -> {
            listener.failed(this, error);
            future.completeExceptionally(error);
        });
    }

    /** Work a job runs on its pool; it may fan out onto the pool and wait on what it submits. */
    public interface Work<T> {
        T run(RenderJob job, ExecutorService pool) throws Exception;
    }

    /**
     * Starts {@code work} as the one running job, measured in {@code units}
     * for {@link #percent()}; {@code finished} or {@code failed} runs on the
     * server thread.
     */
    public static <T> Optional<RenderJob> startWork(MinecraftServer server, Request request, long units, Work<T> work,
                                                    java.util.function.Consumer<T> finished,
                                                    java.util.function.Consumer<Throwable> failed) {
        RenderJob job = new RenderJob(request, defaultThreads());
        job.units = units;
        if (!RUNNING.compareAndSet(null, job)) return Optional.empty();
        job.execute(server, work, finished, failed);
        return Optional.of(job);
    }

    private <T> void execute(MinecraftServer server, Work<T> work, java.util.function.Consumer<T> finished,
                             java.util.function.Consumer<Throwable> failed) {
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "customdim-render-job");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ExecutorService workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "customdim-render-" + counter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        CompletableFuture.supplyAsync(() -> {
            try {
                return work.run(this, workers);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, pool).whenComplete((result, error) -> {
            pool.shutdown();
            workers.shutdown();
            RUNNING.compareAndSet(this, null);
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                CustomDimensions.LOG.warn("{} of {} failed", request.grid() == null ? "roll" : "render", request.dimension(), cause);
                server.execute(() -> {
                    failed.accept(cause);
                    this.finished.completeExceptionally(cause);
                });
            } else {
                server.execute(() -> {
                    finished.accept(result);
                    this.finished.complete(result);
                });
            }
        });
    }

    /**
     * Samples {@code grid} in bands of rows on {@code pool} and counts its
     * columns towards {@link #percent()}; {@code progress} hears each new
     * quarter of the job, from a worker thread.
     */
    public ColumnSamples sample(Sampler sampler, RenderGrid grid, ExecutorService pool,
                                java.util.function.IntConsumer progress) {
        return sample(sampler, grid, pool, progress, (px, py) -> true, null);
    }

    /** Which pixels of a grid to sample. */
    public interface Mask {
        boolean wanted(int px, int py);
    }

    /**
     * Samples the pixels {@code mask} wants into {@code into}, or into new
     * samples when it is null; the others keep what they hold.
     */
    public ColumnSamples sample(Sampler sampler, RenderGrid grid, ExecutorService pool,
                                java.util.function.IntConsumer progress, Mask mask, ColumnSamples into) {
        ColumnSamples samples = into != null ? into : new ColumnSamples(grid, sampler.minY(), sampler.maxY(), sampler.seaLevel());
        List<CompletableFuture<Void>> bands = new ArrayList<>();
        for (RenderGrid.Band band : grid.bands(BAND_ROWS)) {
            bands.add(CompletableFuture.runAsync(() -> {
                for (int py = band.firstRow(); py < band.firstRow() + band.rows(); py++) {
                    for (int px = 0; px < grid.side(); px++) {
                        if (!mask.wanted(px, py)) continue;
                        Sampler.Column c = sampler.sample(grid.x(px), grid.z(py));
                        samples.set(px, py, c.height(), c.fluid(), c.biomeId());
                        if (sampler.hasZones()) {
                            Sampler.ZoneHit zone = sampler.topZone(grid.x(px), grid.z(py));
                            samples.setZone(px, py, zone.index(), zone.weight());
                        }
                    }
                    advance(grid.side(), progress);
                }
            }, pool));
        }
        CompletableFuture.allOf(bands.toArray(CompletableFuture[]::new)).join();
        return samples;
    }

    /** Counts {@code n} units done and tells {@code progress} when the job enters a new quarter. */
    public void advance(long n, java.util.function.IntConsumer progress) {
        done.addAndGet(n);
        int quarter = percent() / 25;
        int previous = reported.get();
        if (quarter > previous && quarter < 4 && reported.compareAndSet(previous, quarter)) progress.accept(quarter * 25);
    }

    public long seed() {
        return seed;
    }
}
