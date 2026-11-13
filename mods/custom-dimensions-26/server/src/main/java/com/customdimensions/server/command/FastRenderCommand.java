package com.customdimensions.server.command;

import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.render.Sampler;
import com.customdimensions.server.render.fast.FastRender;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
 */
final class FastRenderCommand {
    private FastRenderCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("fastrender").then(CustomDimCommands.dimensionArgument()
                .executes(c -> start(c.getSource(), c.getArgument("dimension", String.class), null, null).isPresent() ? 1 : 0)
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
