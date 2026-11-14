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
 * {@code /customdim starts <dimension> [radius]}: every structure start within
 * {@code radius} blocks of 0, 0 (the dimension's border by default), counted by
 * structure, confirmed as generation confirms them ({@link StructureStarts})
 * without generating a chunk, under the server's registry and the dimension's
 * structure control. The count runs off the server thread.
 */
final class StartsCommand {
    static final int DEFAULT_RADIUS = 4096;

    private StartsCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("starts").then(CustomDimCommands.dimensionArgument()
                .executes(c -> starts(c.getSource(), c.getArgument("dimension", String.class), null))
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 65536))
                        .executes(c -> starts(c.getSource(), c.getArgument("dimension", String.class),
                                IntegerArgumentType.getInteger(c, "radius"))))));
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
