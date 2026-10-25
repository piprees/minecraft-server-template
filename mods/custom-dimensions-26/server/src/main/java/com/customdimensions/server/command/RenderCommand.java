package com.customdimensions.server.command;

import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.render.Sampler;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /customdim render <dimension> [seed] [radius] [step]}: a top-down
 * biome and height map of the dimension from the {@link Sampler}, drawn off
 * the server thread by {@link RenderJob} into
 * {@code <game dir>/renders/<dimension>/}. Radius 16 to
 * {@value RenderGrid#MAX_RADIUS} (default {@value RenderGrid#DEFAULT_RADIUS});
 * the step defaults to the smallest that keeps the image at
 * {@value RenderGrid#MAX_SIDE} pixels a side and is refused below it. One
 * render runs at a time.
 */
final class RenderCommand {
    private RenderCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("render").then(CustomDimCommands.dimensionArgument()
                .executes(c -> render(c, null, RenderGrid.DEFAULT_RADIUS, null))
                .then(Commands.argument("seed", LongArgumentType.longArg())
                        .executes(c -> render(c, LongArgumentType.getLong(c, "seed"), RenderGrid.DEFAULT_RADIUS, null))
                        .then(Commands.argument("radius", IntegerArgumentType.integer())
                                .executes(c -> render(c, LongArgumentType.getLong(c, "seed"),
                                        IntegerArgumentType.getInteger(c, "radius"), null))
                                .then(Commands.argument("step", IntegerArgumentType.integer())
                                        .executes(c -> render(c, LongArgumentType.getLong(c, "seed"),
                                                IntegerArgumentType.getInteger(c, "radius"),
                                                IntegerArgumentType.getInteger(c, "step"))))))));
    }

    private static int render(CommandContext<CommandSourceStack> context, Long seed, int radius, Integer step) {
        return render(context.getSource(), context.getArgument("dimension", String.class), seed, radius, step).isPresent() ? 1 : 0;
    }

    /** Starts a render and replies; empty when it was refused. */
    static Optional<RenderJob> render(CommandSourceStack source, String slug, Long seed, int radius, Integer step) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return Optional.empty();
        if (CustomDimCommands.dimension(source, manager.get(), slug).isEmpty()) return Optional.empty();
        if (radius < RenderGrid.MIN_RADIUS || radius > RenderGrid.MAX_RADIUS) {
            CustomDimCommands.fail(source, "customdim.render.radius", "radius", String.valueOf(radius),
                    "min", String.valueOf(RenderGrid.MIN_RADIUS), "max", String.valueOf(RenderGrid.MAX_RADIUS));
            return Optional.empty();
        }
        int minimum = RenderGrid.minimumStep(radius);
        if (step != null && step < minimum) {
            CustomDimCommands.fail(source, "customdim.render.step", "step", String.valueOf(step), "radius", String.valueOf(radius),
                    "min", String.valueOf(minimum), "side", String.valueOf(RenderGrid.MAX_SIDE));
            return Optional.empty();
        }
        Optional<RenderJob> busy = RenderJob.running();
        if (busy.isPresent()) {
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", busy.get().request().dimension(),
                    "percent", String.valueOf(busy.get().percent()));
            return Optional.empty();
        }
        RenderGrid grid = RenderGrid.of(0, 0, radius, step);
        var server = source.getServer();
        LevelManager levels = manager.get();
        RenderJob.Request request = new RenderJob.Request(slug, seed, grid, RenderJob.outputDir(slug));
        Optional<RenderJob> job = RenderJob.start(server, request, () -> Sampler.forDimension(server, levels, slug, seed),
                new RenderJob.Listener() {
                    @Override
                    public void progress(RenderJob j, int percent) {
                        CustomDimCommands.ok(source, "customdim.render.progress", "dimension", slug,
                                "percent", String.valueOf(percent));
                    }

                    @Override
                    public void finished(RenderJob j, RenderJob.Result r) {
                        CustomDimCommands.ok(source, "customdim.render.done", "dimension", slug, "seed", String.valueOf(r.seed()),
                                "seconds", String.format(java.util.Locale.ROOT, "%.1f", r.totalMillis() / 1000.0),
                                "rate", String.valueOf(r.columnsPerSecond()), "biomes", String.valueOf(r.biomes()),
                                "path", r.files().sidecar().getParent().toString());
                    }

                    @Override
                    public void failed(RenderJob j, Throwable error) {
                        CustomDimCommands.fail(source, "customdim.render.failed", "dimension", slug,
                                "reason", CustomDimCommands.reason(error));
                    }
                });
        if (job.isEmpty()) {
            RenderJob other = RenderJob.running().orElse(null);
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", other == null ? "?" : other.request().dimension(),
                    "percent", other == null ? "0" : String.valueOf(other.percent()));
            return Optional.empty();
        }
        CustomDimCommands.ok(source, "customdim.render.started", "dimension", slug,
                "seed", String.valueOf(seed != null ? seed : Sampler.defaultSeed(server, levels, slug)), "radius", String.valueOf(radius),
                "step", String.valueOf(grid.step()), "side", String.valueOf(grid.side()),
                "threads", String.valueOf(job.get().threads()));
        return job;
    }
}
