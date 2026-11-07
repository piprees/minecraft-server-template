package com.customdimensions.server.command;

import com.customdimensions.core.BlockPos;
import com.customdimensions.server.command.CustomDimCommands.Dimension;
import com.customdimensions.server.level.LevelManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /customdim tp <dimension> [player]}: to the dimension's spawn,
 * opening a lazy level first. The console names the player.
 */
final class TeleportCommand {
    private TeleportCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("tp").then(CustomDimCommands.dimensionArgument()
                .executes(c -> teleport(c.getSource(), StringArgumentType.getString(c, "dimension"), null))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> teleport(c.getSource(), StringArgumentType.getString(c, "dimension"),
                                EntityArgument.getPlayer(c, "player"))))));
    }

    static int teleport(CommandSourceStack source, String slug, ServerPlayer named) {
        ServerPlayer player = named != null ? named : source.getPlayer();
        if (player == null) {
            CustomDimCommands.fail(source, "customdim.tp.console");
            return 0;
        }
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return 0;
        Optional<Dimension> d = CustomDimCommands.dimension(source, manager.get(), slug);
        if (d.isEmpty()) return 0;
        send(source, manager.get(), d.get(), player);
        return 1;
    }

    /** Opens the level when lazy, then moves {@code player} to its arrival point; completes with the level. */
    static CompletableFuture<ServerLevel> send(CommandSourceStack source, LevelManager manager, Dimension d, ServerPlayer player) {
        CompletableFuture<ServerLevel> level = d.base()
                ? CompletableFuture.completedFuture(source.getServer().getLevel(d.key()))
                : manager.activate(d.slug()).thenApply(LevelManager.Activation::level);
        return level.whenComplete((l, error) -> {
            if (error != null || l == null) {
                CustomDimCommands.fail(source, "customdim.activate.failed", "dimension", d.slug(),
                        "reason", error == null ? "not loaded" : CustomDimCommands.reason(error));
                return;
            }
            if (player.isRemoved()) return;
            Vec3 target = arrival(l, d.definition() == null ? null : d.definition().spawn());
            player.teleport(new TeleportTransition(l, target, Vec3.ZERO, player.getYRot(), player.getXRot(),
                    TeleportTransition.DO_NOTHING));
            CustomDimCommands.ok(source, "customdim.tp.done", "player", player.getName().getString(), "dimension", d.slug());
        });
    }

    /**
     * The spawn column's surface (0,0 without a spawn). A level with a ceiling,
     * or an empty column, keeps the spawn's own y.
     */
    static Vec3 arrival(ServerLevel level, BlockPos spawn) {
        int x = spawn == null ? 0 : spawn.x();
        int z = spawn == null ? 0 : spawn.z();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        boolean keepY = spawn != null && (level.dimensionType().hasCeiling() || surface <= level.getMinY());
        return new Vec3(x + 0.5, keepY ? spawn.y() : surface, z + 0.5);
    }
}
