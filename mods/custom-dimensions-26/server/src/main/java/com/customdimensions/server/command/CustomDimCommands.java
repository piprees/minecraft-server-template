package com.customdimensions.server.command;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.server.MessageFile;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * {@code /customdim}, permission level 4: {@code list}, {@code info},
 * {@code tp}, {@code activate}, {@code unload}, {@code delete},
 * {@code create}, {@code audit}, {@code catalogue} and {@code render}, one class per concern.
 * Every reply is a {@code customdim.*} template from {@code config/messages.json}.
 */
public final class CustomDimCommands {
    private static final SuggestionProvider<CommandSourceStack> DIMENSIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(LevelManager.get().map(m -> all(m).stream().map(Dimension::slug))
                    .orElseGet(Stream::empty), builder);

    /** A dimension the runtime knows: a base world ({@code lazy} null) or a lazy level. */
    record Dimension(String slug, ResourceKey<Level> key, Definition definition, Integer borderRadius,
                     LevelIndex.Entry lazy) {
        boolean base() {
            return lazy == null;
        }
    }

    private CustomDimCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> register(dispatcher));
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("customdim")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS));
        ListCommand.attach(root);
        InfoCommand.attach(root);
        TeleportCommand.attach(root);
        LifecycleCommands.attach(root);
        CreateCommand.attach(root);
        AuditCommand.attach(root);
        CatalogueCommand.attach(root);
        RenderCommand.attach(root);
        dispatcher.register(root);
    }

    static RequiredArgumentBuilder<CommandSourceStack, String> dimensionArgument() {
        return Commands.argument("dimension", StringArgumentType.word()).suggests(DIMENSIONS);
    }

    /** Base worlds in load order, then the lazy levels by slug. */
    static List<Dimension> all(LevelManager manager) {
        List<Dimension> out = new ArrayList<>();
        for (LevelIndex.BaseWorld w : manager.index().baseWorlds()) {
            out.add(new Dimension(w.slug(), w.key(), w.definition(), w.borderRadius(), null));
        }
        for (LevelIndex.Entry e : manager.index().lazy().values()) {
            out.add(new Dimension(e.slug(), e.key(), e.definition(), e.borderRadius(), e));
        }
        return out;
    }

    static Optional<Dimension> find(LevelManager manager, String slug) {
        return all(manager).stream().filter(d -> d.slug().equals(slug)).findFirst();
    }

    static Optional<LevelManager> manager(CommandSourceStack source) {
        Optional<LevelManager> manager = LevelManager.get();
        if (manager.isEmpty()) fail(source, "customdim.no_manager");
        return manager;
    }

    /** The named dimension, or a reply saying there is none. */
    static Optional<Dimension> dimension(CommandSourceStack source, LevelManager manager, String slug) {
        Optional<Dimension> found = find(manager, slug);
        if (found.isEmpty()) fail(source, "customdim.unknown", "dimension", slug);
        return found;
    }

    static String state(LevelManager manager, Dimension d) {
        return d.base() ? "base" : manager.state(d.slug()).name().toLowerCase(Locale.ROOT);
    }

    static Optional<ServerLevel> level(MinecraftServer server, LevelManager manager, Dimension d) {
        return d.base() ? Optional.ofNullable(server.getLevel(d.key())) : manager.level(d.slug());
    }

    static String text(String key, String... variables) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < variables.length; i += 2) values.put(variables[i], variables[i + 1]);
        return MessageFile.get().format(key, values);
    }

    static void ok(CommandSourceStack source, String key, String... variables) {
        String text = text(key, variables);
        source.sendSuccess(() -> Component.literal(text), false);
    }

    static void fail(CommandSourceStack source, String key, String... variables) {
        source.sendFailure(Component.literal(text(key, variables)));
    }

    static String reason(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
