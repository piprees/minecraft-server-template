package com.customdimensions.server.command;

import com.customdimensions.compiler.convert.JsonWriter;
import com.customdimensions.core.BlockPos;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.score.RollSpec;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.level.PackInstaller;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.render.Roll;
import com.customdimensions.server.render.Sampler;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /customdim roll <dimension> [count]} and {@code /customdim pick
 * <dimension> <seed>} or {@code pick <dimension> rank <n>}. Both are refused
 * for a dimension whose region folder holds chunks: worldgen is creation-time
 * (D2, RB-20).
 */
final class RollCommand {
    private static final Random RANDOM = new SecureRandom();

    private RollCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("roll").then(CustomDimCommands.dimensionArgument()
                .executes(c -> roll(c, Roll.DEFAULT_COUNT))
                .then(Commands.argument("count", IntegerArgumentType.integer())
                        .executes(c -> roll(c, IntegerArgumentType.getInteger(c, "count"))))));
        root.then(Commands.literal("pick").then(CustomDimCommands.dimensionArgument()
                .then(Commands.literal("rank").then(Commands.argument("rank", IntegerArgumentType.integer(1, Roll.KEEP))
                        .executes(c -> pickRank(c.getSource(), c.getArgument("dimension", String.class),
                                IntegerArgumentType.getInteger(c, "rank")))))
                .then(Commands.argument("seed", LongArgumentType.longArg())
                        .executes(c -> pick(c.getSource(), c.getArgument("dimension", String.class),
                                LongArgumentType.getLong(c, "seed")) ? 1 : 0))));
    }

    private static int roll(CommandContext<CommandSourceStack> c, int count) {
        return roll(c.getSource(), c.getArgument("dimension", String.class), count, null).isPresent() ? 1 : 0;
    }

    /** Starts a roll of {@code count} seeds, or of {@code seeds} when given. */
    static Optional<RenderJob> roll(CommandSourceStack source, String slug, int count, List<Long> seeds) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return Optional.empty();
        Optional<CustomDimCommands.Dimension> dimension = CustomDimCommands.dimension(source, manager.get(), slug);
        if (dimension.isEmpty()) return Optional.empty();
        if (seeds == null && (count < 1 || count > Roll.MAX_COUNT)) {
            CustomDimCommands.fail(source, "customdim.roll.count", "count", String.valueOf(count), "max", String.valueOf(Roll.MAX_COUNT));
            return Optional.empty();
        }
        if (refusedForChunks(source, manager.get(), dimension.get())) return Optional.empty();
        Definition def = dimension.get().definition();
        if (RollSpec.of(def).skip()) {
            CustomDimCommands.fail(source, "customdim.roll.skip", "dimension", slug);
            return Optional.empty();
        }
        List<Long> chosen = seeds != null ? List.copyOf(seeds) : seeds(def, count);
        BlockPos spawn = def == null ? null : def.spawn();
        Roll.Plan plan = new Roll.Plan(slug, def, spawn == null ? 0 : spawn.x(), spawn == null ? 0 : spawn.z(), chosen,
                RenderJob.outputDir(slug));
        var server = source.getServer();
        LevelManager levels = manager.get();
        Optional<RenderJob> job = RenderJob.startWork(server, new RenderJob.Request(slug, null, null, plan.outputDir()), plan.units(),
                (self, pool) -> Roll.run(self, plan, seed -> Sampler.forDimension(server, levels, slug, seed), pool,
                        percent -> server.execute(() -> CustomDimCommands.ok(source, "customdim.roll.progress",
                                "dimension", slug, "percent", String.valueOf(percent)))),
                outcome -> {
                    Roll.Rolled top = outcome.best().getFirst();
                    CustomDimCommands.ok(source, "customdim.roll.done", "dimension", slug, "count", String.valueOf(outcome.rolled()),
                            "seconds", String.format(Locale.ROOT, "%.1f", outcome.millis() / 1000.0),
                            "rate", String.valueOf(outcome.columnsPerSecond()), "seed", String.valueOf(top.seed()),
                            "score", top.card().percentage() == null ? top.card().verdict().name().toLowerCase(Locale.ROOT)
                                    : String.format(Locale.ROOT, "%.1f%%", top.card().percentage()),
                            "path", outcome.sheet().toString());
                },
                error -> CustomDimCommands.fail(source, "customdim.roll.failed", "dimension", slug, "reason", CustomDimCommands.reason(error)));
        if (job.isEmpty()) {
            RenderJob other = RenderJob.running().orElse(null);
            CustomDimCommands.fail(source, "customdim.roll.busy", "dimension", other == null ? "?" : other.request().dimension(),
                    "percent", other == null ? "0" : String.valueOf(other.percent()));
            return Optional.empty();
        }
        CustomDimCommands.ok(source, "customdim.roll.started", "dimension", slug, "count", String.valueOf(chosen.size()),
                "radius", String.valueOf(plan.disc().radius()), "step", String.valueOf(plan.disc().step()),
                "side", String.valueOf(plan.disc().side()), "structureRadius", String.valueOf(plan.structureRadius()),
                "threads", String.valueOf(job.get().threads()));
        return job;
    }

    /** The definition's own fixed seed first, so the roll shows where it stands, then fresh ones. */
    static List<Long> seeds(Definition def, int count) {
        List<Long> out = new ArrayList<>();
        if (def != null && def.seed() != null && !def.seed().fromEnv()) out.add(def.seed().value());
        while (out.size() < count) {
            long seed = RANDOM.nextLong();
            if (!out.contains(seed)) out.add(seed);
        }
        return out;
    }

    private static int pickRank(CommandSourceStack source, String slug, int rank) {
        Optional<Long> seed;
        try {
            seed = Roll.ranked(RenderJob.outputDir(slug), rank);
        } catch (IOException | RuntimeException e) {
            seed = Optional.empty();
        }
        if (seed.isEmpty()) {
            CustomDimCommands.fail(source, "customdim.pick.no_rank", "dimension", slug, "rank", String.valueOf(rank));
            return 0;
        }
        return pick(source, slug, seed.get()) ? 1 : 0;
    }

    /** Writes {@code seed} into the dimension's definition file. */
    static boolean pick(CommandSourceStack source, String slug, long seed) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return false;
        Optional<CustomDimCommands.Dimension> dimension = CustomDimCommands.dimension(source, manager.get(), slug);
        if (dimension.isEmpty() || refusedForChunks(source, manager.get(), dimension.get())) return false;
        Path file = CreateCommand.definitionsDir().resolve(slug + ".json");
        if (!Files.isRegularFile(file)) {
            CustomDimCommands.fail(source, "customdim.pick.no_file", "dimension", slug, "path", file.toString());
            return false;
        }
        try {
            writeSeed(file, seed);
        } catch (IOException | RuntimeException e) {
            CustomDimCommands.fail(source, "customdim.write_failed", "path", file.toString(), "reason", CustomDimCommands.reason(e));
            return false;
        }
        CustomDimCommands.ok(source, "customdim.pick.done", "dimension", slug, "seed", String.valueOf(seed), "path", file.toString());
        return true;
    }

    /** Replaces the definition's {@code seed} in place, through the converter's writer, and checks the result reads back. */
    static void writeSeed(Path file, long seed) throws IOException {
        JsonObject root = CreateCommand.read(file);
        root.add("seed", new JsonPrimitive(seed));
        String text = JsonWriter.write(root);
        Definition read = DefinitionReader.read(text, file.toString());
        if (read.seed() == null || read.seed().value() != seed) throw new IllegalStateException("the written seed did not read back");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** True, with the refusal sent, when the dimension's region folder holds chunks. */
    static boolean refusedForChunks(CommandSourceStack source, LevelManager manager, CustomDimCommands.Dimension d) {
        Path region = manager.dimensionPath(d.key()).resolve("region");
        if (!PackInstaller.holdsChunks(region)) return false;
        CustomDimCommands.fail(source, "customdim.roll.has_chunks", "dimension", d.slug(), "path", region.getParent().toString());
        return true;
    }
}
