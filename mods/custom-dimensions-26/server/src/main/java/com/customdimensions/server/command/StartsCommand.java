package com.customdimensions.server.command;

import com.customdimensions.core.score.RollFacts;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.render.Sampler;
import com.customdimensions.server.render.StructureStarts;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.util.Util;

/**
 * {@code /customdim starts <dimension> [radius] [structure]}: every structure
 * start within {@code radius} blocks of 0, 0 (the dimension's border by
 * default), counted by structure, confirmed as generation confirms them
 * ({@link StructureStarts}) without generating a chunk, under the server's
 * registry and the dimension's structure control. With a structure, each of
 * its starts instead: its chunk centre, its box's floor and the surface a site
 * filter reads there. The count runs off the server thread.
 */
final class StartsCommand {
    static final int DEFAULT_RADIUS = 4096;

    private StartsCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("starts").then(CustomDimCommands.dimensionArgument()
                .executes(c -> starts(c.getSource(), c.getArgument("dimension", String.class), null))
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 65536))
                        .executes(c -> starts(c.getSource(), c.getArgument("dimension", String.class),
                                IntegerArgumentType.getInteger(c, "radius")))
                        .then(Commands.argument("structure", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(c -> each(c.getSource(), c.getArgument("dimension", String.class),
                                        IntegerArgumentType.getInteger(c, "radius"),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "structure")))))));
    }

    private static int each(CommandSourceStack source, String slug, int radius, String structure) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return 0;
        if (CustomDimCommands.dimension(source, manager.get(), slug).isEmpty()) return 0;
        var server = source.getServer();
        Sampler sampler;
        try {
            sampler = Sampler.forDimension(server, manager.get(), slug, null);
        } catch (RuntimeException e) {
            CustomDimCommands.fail(source, "customdim.starts.failed", "dimension", slug, "reason", CustomDimCommands.reason(e));
            return 0;
        }
        CustomDimCommands.ok(source, "customdim.starts.started", "dimension", slug, "radius", String.valueOf(radius));
        CompletableFuture.supplyAsync(() -> {
            List<String> rows = new java.util.ArrayList<>();
            StructureStarts.find(sampler, sampler.structureState(), structure::equals, 0, 0, radius, null, id -> Integer.MAX_VALUE,
                    id -> radius, null, (start, box) -> rows.add(start.x() + "," + start.z() + " floor " + box.minY() + " surface "
                            + com.customdimensions.server.structure.StructureRuntime.siteSurface(sampler.generator(), sampler.random(),
                            start.x(), start.z())));
            return rows;
        }, Util.backgroundExecutor()).whenComplete((rows, error) -> server.execute(() -> {
            if (error != null) {
                CustomDimCommands.fail(source, "customdim.starts.failed", "dimension", slug, "reason", CustomDimCommands.reason(error));
                return;
            }
            CustomDimCommands.ok(source, "customdim.starts.each", "structure", structure, "dimension", slug, "radius", String.valueOf(radius),
                    "total", String.valueOf(rows.size()), "each", String.join("; ", rows));
        }));
        return 1;
    }

    private static int starts(CommandSourceStack source, String slug, Integer radius) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return 0;
        Optional<CustomDimCommands.Dimension> dimension = CustomDimCommands.dimension(source, manager.get(), slug);
        if (dimension.isEmpty()) return 0;
        int reach = radius != null ? radius : dimension.get().borderRadius() != null ? dimension.get().borderRadius() : DEFAULT_RADIUS;
        var server = source.getServer();
        Sampler sampler;
        try {
            sampler = Sampler.forDimension(server, manager.get(), slug, null);
        } catch (RuntimeException e) {
            CustomDimCommands.fail(source, "customdim.starts.failed", "dimension", slug, "reason", CustomDimCommands.reason(e));
            return 0;
        }
        CustomDimCommands.ok(source, "customdim.starts.started", "dimension", slug, "radius", String.valueOf(reach));
        long t0 = System.nanoTime();
        CompletableFuture.supplyAsync(() -> StructureStarts.find(sampler, sampler.structureState(), id -> true, 0, 0, reach, null,
                id -> Integer.MAX_VALUE), Util.backgroundExecutor()).whenComplete((found, error) -> server.execute(() -> {
            if (error != null) {
                CustomDimCommands.fail(source, "customdim.starts.failed", "dimension", slug, "reason", CustomDimCommands.reason(error));
                return;
            }
            CustomDimCommands.ok(source, "customdim.starts.done", "dimension", slug, "radius", String.valueOf(reach),
                    "total", String.valueOf(found.size()), "seconds", String.format(Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1e9),
                    "counts", counts(found));
        }));
        return 1;
    }

    /** {@code id=n} for each structure, most first, then by id. */
    static String counts(List<RollFacts.Start> found) {
        Map<String, Long> by = found.stream().collect(Collectors.groupingBy(RollFacts.Start::structure, LinkedHashMap::new, Collectors.counting()));
        return by.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "));
    }
}
