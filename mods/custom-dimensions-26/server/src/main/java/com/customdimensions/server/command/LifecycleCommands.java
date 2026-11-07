package com.customdimensions.server.command;

import com.customdimensions.server.command.CustomDimCommands.Dimension;
import com.customdimensions.server.level.LevelManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /customdim activate|unload <dimension>} and
 * {@code /customdim delete <dimension> [confirm]}: a lazy level's lifecycle.
 * Base worlds are always loaded and are never deleted.
 */
final class LifecycleCommands {
    private LifecycleCommands() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("activate").then(CustomDimCommands.dimensionArgument()
                .executes(c -> activate(c.getSource(), StringArgumentType.getString(c, "dimension")))));
        root.then(Commands.literal("unload").then(CustomDimCommands.dimensionArgument()
                .executes(c -> unload(c.getSource(), StringArgumentType.getString(c, "dimension")))));
        root.then(Commands.literal("delete").then(CustomDimCommands.dimensionArgument()
                .executes(c -> delete(c.getSource(), StringArgumentType.getString(c, "dimension"), false))
                .then(Commands.literal("confirm")
                        .executes(c -> delete(c.getSource(), StringArgumentType.getString(c, "dimension"), true)))));
    }

    private static Optional<Dimension> lazy(CommandSourceStack source, LevelManager manager, String slug) {
        Optional<Dimension> d = CustomDimCommands.dimension(source, manager, slug);
        if (d.isPresent() && d.get().base()) {
            CustomDimCommands.ok(source, "customdim.base_world", "dimension", slug);
            return Optional.empty();
        }
        return d;
    }

    static int activate(CommandSourceStack source, String slug) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty() || lazy(source, manager.get(), slug).isEmpty()) return 0;
        CustomDimCommands.ok(source, "customdim.activate.started", "dimension", slug);
        manager.get().activate(slug).whenComplete((a, error) -> {
            if (error != null) {
                CustomDimCommands.fail(source, "customdim.activate.failed", "dimension", slug, "reason", CustomDimCommands.reason(error));
            } else {
                CustomDimCommands.ok(source, "customdim.activate.done", "dimension", slug, "millis", String.valueOf(a.millis()));
            }
        });
        return 1;
    }

    static int unload(CommandSourceStack source, String slug) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty() || lazy(source, manager.get(), slug).isEmpty()) return 0;
        manager.get().deactivate(slug).whenComplete((ticks, error) -> {
            if (error != null) {
                CustomDimCommands.fail(source, "customdim.unload.failed", "dimension", slug, "reason", CustomDimCommands.reason(error));
            } else {
                CustomDimCommands.ok(source, "customdim.unload.done", "dimension", slug, "ticks", String.valueOf(ticks));
            }
        });
        return 1;
    }

    static int delete(CommandSourceStack source, String slug, boolean confirm) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return 0;
        Optional<Dimension> d = CustomDimCommands.dimension(source, manager.get(), slug);
        if (d.isEmpty()) return 0;
        if (d.get().base()) {
            CustomDimCommands.fail(source, "customdim.delete.base", "dimension", slug);
            return 0;
        }
        if (!confirm) {
            CustomDimCommands.ok(source, "customdim.delete.preview", "dimension", slug,
                    "path", manager.get().dimensionPath(d.get().key()).toString());
            return 1;
        }
        deleteNow(source, manager.get(), slug);
        return 1;
    }

    static CompletableFuture<Integer> deleteNow(CommandSourceStack source, LevelManager manager, String slug) {
        return manager.delete(slug).whenComplete((ticks, error) -> {
            if (error != null) {
                CustomDimCommands.fail(source, "customdim.delete.failed", "dimension", slug, "reason", CustomDimCommands.reason(error));
            } else {
                CustomDimCommands.ok(source, "customdim.delete.done", "dimension", slug);
            }
        });
    }
}
