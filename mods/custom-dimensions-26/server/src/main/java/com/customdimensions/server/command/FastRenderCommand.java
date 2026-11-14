package com.customdimensions.server.command;

import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.core.render.WavePlan;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.render.Sampler;
import com.customdimensions.server.render.fast.FastRender;
import com.customdimensions.server.render.fast.FastWave;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /customdim fastrender <dimension> [stride] [threads]}: the
 * dimension border to border (its world border; the generation border, or
 * {@value RenderGrid#DEFAULT_RADIUS} blocks, when it has none) from the
 * solid runs of its density, by {@link FastRender}, into
 * {@code <game dir>/renders/<dimension>/fast-*}. The stride defaults to the
 * one that keeps the image near {@value FastRender#TARGET_SIDE} pixels and
 * runs 1 to {@value FastRender#MAX_STEP}, the image at most
 * {@value RenderGrid#WIDE_MAX_SIDE} pixels a side; threads default to the
 * render's. Shares the one-render-at-a-time slot with {@code render}.
 *
 * <p>{@code /customdim fastrender <dimension> wave [threads]} renders the
 * dimension in passes by {@link FastWave} over the larger of its player and
 * generation borders, into {@code renders/<dimension>/wave-<seed>/}, replying
 * after each pass; {@code /customdim fastrender all wave [threads]} does every
 * dimension, one at a time.
 */
final class FastRenderCommand {
    private FastRenderCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("fastrender")
                .then(Commands.literal("all").then(Commands.literal("wave")
                        .executes(c -> waveAll(c.getSource(), null).isPresent() ? 1 : 0)
                        .then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                                .executes(c -> waveAll(c.getSource(), IntegerArgumentType.getInteger(c, "threads")).isPresent() ? 1 : 0))))
                .then(CustomDimCommands.dimensionArgument()
                .executes(c -> start(c.getSource(), c.getArgument("dimension", String.class), null, null).isPresent() ? 1 : 0)
                .then(Commands.literal("wave")
                        .executes(c -> wave(c.getSource(), c.getArgument("dimension", String.class), null).isPresent() ? 1 : 0)
                        .then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                                .executes(c -> wave(c.getSource(), c.getArgument("dimension", String.class),
                                        IntegerArgumentType.getInteger(c, "threads")).isPresent() ? 1 : 0)))
                .then(Commands.argument("stride", IntegerArgumentType.integer())
                        .executes(c -> start(c.getSource(), c.getArgument("dimension", String.class),
                                IntegerArgumentType.getInteger(c, "stride"), null).isPresent() ? 1 : 0)
                        .then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                                .executes(c -> start(c.getSource(), c.getArgument("dimension", String.class),
                                        IntegerArgumentType.getInteger(c, "stride"),
                                        IntegerArgumentType.getInteger(c, "threads")).isPresent() ? 1 : 0)))));
    }

    /** The border radius a fast render covers, rounded up to 16 blocks so its tiles start on the density's cell lattice. */
    static int radius(CustomDimCommands.Dimension d) {
        Integer r = d.borderRadius();
        if (r == null && d.definition() != null && d.definition().borders() != null) r = d.definition().borders().generation();
        if (r == null) r = RenderGrid.DEFAULT_RADIUS;
        return Math.clamp(Math.ceilDiv(r, 16) * 16, RenderGrid.MIN_RADIUS, RenderGrid.WIDE_MAX_RADIUS);
    }

    /** The radius a wave covers: the larger of the player border (the world border) and the generation border. */
    static int waveRadius(CustomDimCommands.Dimension d) {
        Integer player = d.borderRadius();
        Integer generation = null;
        if (d.definition() != null && d.definition().borders() != null) {
            if (player == null) player = d.definition().borders().player();
            generation = d.definition().borders().generation();
        }
        return WavePlan.radius(player, generation, RenderGrid.DEFAULT_RADIUS);
    }

    static Optional<RenderJob> wave(CommandSourceStack source, String slug, Integer threads) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return Optional.empty();
        Optional<CustomDimCommands.Dimension> dimension = CustomDimCommands.dimension(source, manager.get(), slug);
        if (dimension.isEmpty()) return Optional.empty();
        return waves(source, manager.get(), List.of(dimension.get()), threads, false);
    }

    static Optional<RenderJob> waveAll(CommandSourceStack source, Integer threads) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return Optional.empty();
        return waves(source, manager.get(), CustomDimCommands.all(manager.get()), threads, true);
    }

    /** Waves over {@code dimensions} one at a time in one job; a dimension that fails is reported and the rest go on. */
    private static Optional<RenderJob> waves(CommandSourceStack source, LevelManager levels, List<CustomDimCommands.Dimension> dimensions,
                                             Integer threads, boolean all) {
        Optional<RenderJob> busy = RenderJob.running();
        if (busy.isPresent()) {
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", busy.get().request().dimension(),
                    "percent", String.valueOf(busy.get().percent()));
            return Optional.empty();
        }
        var server = source.getServer();
        int pool = threads != null ? threads : RenderJob.defaultThreads();
        List<WavePlan> plans = new ArrayList<>();
        long columns = 0;
        for (CustomDimCommands.Dimension d : dimensions) {
            WavePlan plan = WavePlan.of(waveRadius(d), WavePlan.FINAL_SIDE);
            plans.add(plan);
            columns += plan.columns();
        }
        String label = all ? "all" : dimensions.getFirst().slug();
        WavePlan firstPlan = plans.getFirst();
        RenderJob.Request request = new RenderJob.Request(label, null,
                RenderGrid.wide(0, 0, firstPlan.radius(), firstPlan.passes().getLast().stride()), RenderJob.outputDir(label));
        Optional<RenderJob> job = RenderJob.startWork(server, request, columns, pool, (self, workers) -> {
            long started = System.nanoTime();
            int failed = 0;
            for (int i = 0; i < dimensions.size(); i++) {
                String slug = dimensions.get(i).slug();
                WavePlan plan = plans.get(i);
                try {
                    Sampler sampler = Sampler.forDimension(server, levels, slug, null);
                    int passes = plan.passes().size();
                    FastWave.Result r = FastWave.run(sampler, plan, workers, pool, FastWave.dir(RenderJob.outputDir(slug), sampler.seed()),
                            self, percent -> {}, p -> server.execute(() -> CustomDimCommands.ok(source, "customdim.fastrender.wave.pass",
                                    "dimension", slug, "pass", String.valueOf(p.pass().index()), "passes", String.valueOf(passes),
                                    "stride", String.valueOf(p.pass().stride()), "side", String.valueOf(p.pass().side()),
                                    "seconds", seconds(p.millis()), "total", seconds(p.cumulativeMillis()),
                                    "path", p.sheet().toString())));
                    CustomDimensions.LOG.info("fast wave {} seed {}: {} passes in {} ms -> {}", slug, r.seed(), passes, r.totalMillis(),
                            r.json());
                    server.execute(() -> CustomDimCommands.ok(source, "customdim.fastrender.wave.done", "dimension", slug,
                            "seed", String.valueOf(r.seed()), "passes", String.valueOf(passes), "total", seconds(r.totalMillis()),
                            "path", r.json().toString()));
                } catch (Exception e) {
                    failed++;
                    CustomDimensions.LOG.error("fast wave {} failed", slug, e);
                    server.execute(() -> CustomDimCommands.fail(source, "customdim.fastrender.failed", "dimension", slug,
                            "reason", CustomDimCommands.reason(e)));
                }
            }
            return new long[] {dimensions.size(), failed, (System.nanoTime() - started) / 1_000_000};
        }, r -> {
            if (all) {
                CustomDimCommands.ok(source, "customdim.fastrender.wave.alldone", "count", String.valueOf(r[0]),
                        "failed", String.valueOf(r[1]), "total", seconds(r[2]));
            }
        }, e -> CustomDimCommands.fail(source, "customdim.fastrender.failed", "dimension", label, "reason", CustomDimCommands.reason(e)));
        if (job.isEmpty()) {
            RenderJob other = RenderJob.running().orElse(null);
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", other == null ? "?" : other.request().dimension(),
                    "percent", other == null ? "0" : String.valueOf(other.percent()));
            return Optional.empty();
        }
        if (all) {
            CustomDimCommands.ok(source, "customdim.fastrender.wave.all", "count", String.valueOf(dimensions.size()),
                    "threads", String.valueOf(pool));
        } else {
            WavePlan plan = firstPlan;
            StringBuilder strides = new StringBuilder();
            for (WavePlan.Pass p : plan.passes()) strides.append(strides.isEmpty() ? "" : ", ").append(p.stride());
            CustomDimCommands.ok(source, "customdim.fastrender.wave.started", "dimension", label,
                    "seed", String.valueOf(Sampler.defaultSeed(server, levels, label)), "radius", String.valueOf(plan.radius()),
                    "strides", strides.toString(), "threads", String.valueOf(pool));
        }
        return job;
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }

    static Optional<RenderJob> start(CommandSourceStack source, String slug, Integer stride, Integer threads) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return Optional.empty();
        Optional<CustomDimCommands.Dimension> dimension = CustomDimCommands.dimension(source, manager.get(), slug);
        if (dimension.isEmpty()) return Optional.empty();
        int radius = radius(dimension.get());
        int step = stride != null ? stride : FastRender.defaultStep(radius);
        if (!FastRender.stepAllowed(radius, step)) {
            CustomDimCommands.fail(source, "customdim.fastrender.step", "step", String.valueOf(step), "radius", String.valueOf(radius),
                    "max", String.valueOf(FastRender.MAX_STEP), "side", String.valueOf(RenderGrid.WIDE_MAX_SIDE));
            return Optional.empty();
        }
        Optional<RenderJob> busy = RenderJob.running();
        if (busy.isPresent()) {
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", busy.get().request().dimension(),
                    "percent", String.valueOf(busy.get().percent()));
            return Optional.empty();
        }
        RenderGrid grid = RenderGrid.wide(0, 0, radius, step);
        int pool = threads != null ? threads : RenderJob.defaultThreads();
        var server = source.getServer();
        LevelManager levels = manager.get();
        RenderJob.Request request = new RenderJob.Request(slug, null, grid, RenderJob.outputDir(slug));
        Optional<RenderJob> job = RenderJob.startWork(server, request, grid.columns(), pool, (self, workers) -> {
            long started = System.nanoTime();
            Sampler sampler = Sampler.forDimension(server, levels, slug, null);
            FastRender.Result r = FastRender.run(sampler, grid, workers, pool, request.outputDir(), self,
                    percent -> server.execute(() -> CustomDimCommands.ok(source, "customdim.render.progress", "dimension", slug,
                            "percent", String.valueOf(percent))), started);
            CustomDimensions.LOG.info("fast-rendered {} seed {}: {} columns in {} ms ({} columns/s on {} threads, {}x the column sampler; "
                            + "heights within 1 block {}, biomes {}) -> {}", r.dimension(), r.seed(), grid.columns(), r.sampleMillis(),
                    r.columnsPerSecond(), r.threads(), String.format(Locale.ROOT, "%.1f", r.speedup()), r.heightsWithinOne(),
                    r.biomesEqual(), r.sidecar());
            return r;
        }, r -> CustomDimCommands.ok(source, "customdim.fastrender.done", "dimension", slug, "seed", String.valueOf(r.seed()),
                "seconds", String.format(Locale.ROOT, "%.1f", r.sampleMillis() / 1000.0),
                "total", String.format(Locale.ROOT, "%.1f", r.totalMillis() / 1000.0),
                "rate", String.valueOf(r.columnsPerSecond()), "speedup", String.format(Locale.ROOT, "%.1f", r.speedup()),
                "heights", String.format(Locale.ROOT, "%.1f%%", r.heightsWithinOne() * 100),
                "biomes", String.format(Locale.ROOT, "%.1f%%", r.biomesEqual() * 100), "path", r.sidecar().toString()),
                e -> CustomDimCommands.fail(source, "customdim.fastrender.failed", "dimension", slug, "reason", CustomDimCommands.reason(e)));
        if (job.isEmpty()) {
            RenderJob other = RenderJob.running().orElse(null);
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", other == null ? "?" : other.request().dimension(),
                    "percent", other == null ? "0" : String.valueOf(other.percent()));
            return Optional.empty();
        }
        CustomDimCommands.ok(source, "customdim.fastrender.started", "dimension", slug,
                "seed", String.valueOf(Sampler.defaultSeed(server, levels, slug)), "radius", String.valueOf(radius),
                "step", String.valueOf(step), "side", String.valueOf(grid.side()), "threads", String.valueOf(pool));
        return job;
    }
}
