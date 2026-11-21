package com.customdimensions.server.command;

import com.customdimensions.core.survey.SurveyPlan;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.survey.SurveyJob;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /customdim survey <dimension> [patches] [size]}: samples the
 * dimension's real chunks ({@value SurveyPlan#DEFAULT_PATCHES} patches of
 * {@value SurveyPlan#DEFAULT_SIZE}×{@value SurveyPlan#DEFAULT_SIZE} chunks by
 * default, plus adaptive ones) and writes a dossier to
 * {@code <game dir>/customdim-survey/<dimension>/} through {@link SurveyJob}.
 * A lazy dimension is opened for the survey and closed after it. One render or
 * survey runs at a time.
 */
public final class SurveyCommand {
    private SurveyCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("survey").then(CustomDimCommands.dimensionArgument()
                .executes(c -> survey(c.getSource(), c.getArgument("dimension", String.class), SurveyPlan.DEFAULT_PATCHES, SurveyPlan.DEFAULT_SIZE,
                        r -> {}).isPresent() ? 1 : 0)
                .then(Commands.argument("patches", IntegerArgumentType.integer())
                        .executes(c -> survey(c.getSource(), c.getArgument("dimension", String.class), IntegerArgumentType.getInteger(c, "patches"),
                                SurveyPlan.DEFAULT_SIZE, r -> {}).isPresent() ? 1 : 0)
                        .then(Commands.argument("size", IntegerArgumentType.integer())
                                .executes(c -> survey(c.getSource(), c.getArgument("dimension", String.class),
                                        IntegerArgumentType.getInteger(c, "patches"), IntegerArgumentType.getInteger(c, "size"), r -> {}).isPresent() ? 1 : 0)))));
    }

    /** Starts a survey and replies; empty when it was refused. {@code done} hears the result on the server thread. */
    public static Optional<RenderJob> survey(CommandSourceStack source, String slug, int patches, int size, Consumer<SurveyJob.Result> done) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return Optional.empty();
        Optional<CustomDimCommands.Dimension> dimension = CustomDimCommands.dimension(source, manager.get(), slug);
        if (dimension.isEmpty()) return Optional.empty();
        if (patches < 1 || patches > SurveyPlan.MAX_PATCHES || size < 1 || size > SurveyPlan.MAX_SIZE) {
            CustomDimCommands.fail(source, "customdim.survey.range", "patches", String.valueOf(patches), "size", String.valueOf(size),
                    "maxPatches", String.valueOf(SurveyPlan.MAX_PATCHES), "maxSize", String.valueOf(SurveyPlan.MAX_SIZE));
            return Optional.empty();
        }
        Optional<RenderJob> busy = RenderJob.running();
        if (busy.isPresent()) {
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", busy.get().request().dimension(),
                    "percent", String.valueOf(busy.get().percent()));
            return Optional.empty();
        }
        var server = source.getServer();
        LevelManager levels = manager.get();
        CustomDimCommands.Dimension d = dimension.get();
        CompletableFuture<ServerLevel> level;
        boolean opened = false;
        if (d.base()) {
            level = CompletableFuture.completedFuture(server.getLevel(d.key()));
        } else if (levels.level(slug).isPresent()) {
            level = CompletableFuture.completedFuture(levels.level(slug).get());
        } else {
            level = levels.activate(slug).thenApply(LevelManager.Activation::level);
            opened = true;
        }
        boolean close = opened;
        Optional<RenderJob> job = SurveyJob.start(server, levels, slug, d.definition(), d.borderRadius(), level,
                new SurveyJob.Request(slug, patches, size, SurveyJob.outputDir(slug)),
                percent -> server.execute(() -> CustomDimCommands.ok(source, "customdim.survey.progress", "dimension", slug, "percent", String.valueOf(percent))),
                r -> {
                    CustomDimCommands.ok(source, "customdim.survey.done", "dimension", slug, "patches", String.valueOf(r.patches()),
                            "columns", String.valueOf(r.columns()), "seconds", String.format(java.util.Locale.ROOT, "%.1f", r.millis() / 1000.0),
                            "types", String.valueOf(r.placesSurface()), "landmarks", String.valueOf(r.landmarks()), "path", r.dossier().toString());
                    done.accept(r);
                },
                e -> CustomDimCommands.fail(source, "customdim.survey.failed", "dimension", slug, "reason", CustomDimCommands.reason(e)),
                () -> {
                    if (close) levels.deactivate(slug);
                });
        if (job.isEmpty()) {
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", "?", "percent", "0");
            return Optional.empty();
        }
        CustomDimCommands.ok(source, "customdim.survey.started", "dimension", slug, "patches", String.valueOf(patches), "size", String.valueOf(size));
        return job;
    }
}
