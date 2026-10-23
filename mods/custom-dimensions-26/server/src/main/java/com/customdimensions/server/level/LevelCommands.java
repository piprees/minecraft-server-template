package com.customdimensions.server.level;

import com.customdimensions.core.BlockPos;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Optional;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /customdim list|activate|tp|unload}, permission level 2: operator
 * access to the lazy levels until the full command set (M9).
 */
public final class LevelCommands {
    private static final SuggestionProvider<CommandSourceStack> SLUGS = (context, builder) ->
            SharedSuggestionProvider.suggest(LevelManager.get().map(m -> m.index().lazy().keySet().stream())
                    .orElseGet(java.util.stream.Stream::empty), builder);

    private LevelCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("customdim")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list").executes(LevelCommands::list))
                .then(Commands.literal("activate").then(slug().executes(LevelCommands::activate)))
                .then(Commands.literal("tp").then(slug().executes(LevelCommands::teleport)))
                .then(Commands.literal("unload").then(slug().executes(LevelCommands::unload))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> slug() {
        return Commands.argument("slug", StringArgumentType.word()).suggests(SLUGS);
    }

    private static Optional<LevelManager> manager(CommandSourceStack source) {
        Optional<LevelManager> manager = LevelManager.get();
        if (manager.isEmpty()) source.sendFailure(Component.literal("The level manager has not started."));
        return manager;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<LevelManager> manager = manager(source);
        if (manager.isEmpty()) return 0;
        LevelManager m = manager.get();
        int active = 0;
        StringBuilder out = new StringBuilder();
        for (String slug : m.index().lazy().keySet()) {
            LevelManager.State state = m.state(slug);
            int players = m.level(slug).map(l -> l.players().size()).orElse(0);
            if (state != LevelManager.State.LAZY) active++;
            out.append('\n').append(slug).append(": ").append(state.name().toLowerCase(java.util.Locale.ROOT))
                    .append(", ").append(players).append(players == 1 ? " player" : " players");
        }
        int count = m.index().lazy().size();
        int open = active;
        source.sendSuccess(() -> Component.literal(count + " lazy dimensions, " + open + " not lazy:" + out), false);
        return count;
    }

    private static int activate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String slug = StringArgumentType.getString(context, "slug");
        Optional<LevelManager> manager = manager(source);
        if (manager.isEmpty()) return 0;
        source.sendSuccess(() -> Component.literal("Activating " + slug + "…"), true);
        manager.get().activate(slug).whenComplete((a, error) -> {
            if (error != null) {
                source.sendFailure(Component.literal("Could not activate " + slug + ": " + reason(error)));
            } else {
                source.sendSuccess(() -> Component.literal(slug + " is active (" + a.millis() + " ms)."), true);
            }
        });
        return 1;
    }

    private static int teleport(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String slug = StringArgumentType.getString(context, "slug");
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can be teleported."));
            return 0;
        }
        Optional<LevelManager> manager = manager(source);
        if (manager.isEmpty()) return 0;
        LevelManager m = manager.get();
        m.activate(slug).whenComplete((a, error) -> {
            if (error != null) {
                source.sendFailure(Component.literal("Could not activate " + slug + ": " + reason(error)));
                return;
            }
            if (player.isRemoved()) return;
            Vec3 target = arrival(a.level(), m.spawn(slug).orElse(null));
            player.teleport(new TeleportTransition(a.level(), target, Vec3.ZERO, player.getYRot(), player.getXRot(),
                    TeleportTransition.DO_NOTHING));
            source.sendSuccess(() -> Component.literal("Teleported to " + slug + "."), true);
        });
        return 1;
    }

    /** The definition's spawn, or the surface at 0,0 when it has none. */
    static Vec3 arrival(ServerLevel level, BlockPos spawn) {
        if (spawn != null) return new Vec3(spawn.x() + 0.5, spawn.y(), spawn.z() + 0.5);
        return new Vec3(0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0), 0.5);
    }

    private static int unload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String slug = StringArgumentType.getString(context, "slug");
        Optional<LevelManager> manager = manager(source);
        if (manager.isEmpty()) return 0;
        manager.get().deactivate(slug).whenComplete((ticks, error) -> {
            if (error != null) {
                source.sendFailure(Component.literal("Could not unload " + slug + ": " + reason(error)));
            } else {
                source.sendSuccess(() -> Component.literal(slug + " unloaded after " + ticks + " ticks."), true);
            }
        });
        return 1;
    }

    private static String reason(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
