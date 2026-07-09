package com.customdimensions.command;

import com.customdimensions.config.DimensionDefinition;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Locale;

public class DimensionCommand {
    private static final SuggestionProvider<ServerCommandSource> DIMENSION_TYPES = (ctx, builder) -> {
        for (String t : new String[]{"overworld", "nether", "end", "void"}) {
            if (t.startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(t);
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<ServerCommandSource> EXISTING_DIMENSIONS = (ctx, builder) -> {
        for (String n : MultiverseConfig.getInstance().getDimensionNames()) {
            if (n.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(n);
            }
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("dimension")
                        .then(CommandManager.literal("create")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .then(CommandManager.argument("type", StringArgumentType.word())
                                                .suggests(DIMENSION_TYPES)
                                                .executes(DimensionCommand::executeCreate)
                                                .then(CommandManager.argument("seed", LongArgumentType.longArg())
                                                        .executes(DimensionCommand::executeCreate)))))
                        .then(CommandManager.literal("delete")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .suggests(EXISTING_DIMENSIONS)
                                        .executes(DimensionCommand::executeDelete)))
        );
    }

    private static int executeCreate(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        String type = StringArgumentType.getString(ctx, "type");

        if (!name.matches("[a-z0-9_/-]+")) {
            ctx.getSource().sendError(Text.literal("Invalid name. Use letters, numbers, underscores, hyphens, slashes"));
            return 0;
        }
        if (MultiverseConfig.getInstance().getDimension(name) != null) {
            ctx.getSource().sendError(Text.literal("Dimension '" + name + "' already exists"));
            return 0;
        }
        if (!(type.equals("overworld") || type.equals("nether") || type.equals("end") || type.equals("void"))) {
            ctx.getSource().sendError(Text.literal("Invalid type. Use: overworld, nether, end, void"));
            return 0;
        }

        Long seed = null;
        try {
            seed = LongArgumentType.getLong(ctx, "seed");
        } catch (Exception ignored) {
        }

        DimensionDefinition def = new DimensionDefinition(name, type, "minecraft:" + name);
        def.setSeed(seed);
        MultiverseConfig.getInstance().addDimension(def);
        DimensionManager.getInstance().registerDimension(def);
        DimensionManager.getInstance().getOrCreateDimension(name);

        String seedInfo = seed != null ? ", seed: " + seed : "";
        ctx.getSource().sendFeedback(() -> Text.literal("Created dimension '" + name + "' (type: " + type + seedInfo + ")"), true);
        return 1;
    }

    private static int executeDelete(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");

        if (!MultiverseConfig.getInstance().removeDimension(name)) {
            ctx.getSource().sendError(Text.literal("Dimension '" + name + "' not found"));
            return 0;
        }

        ctx.getSource().sendFeedback(() -> Text.literal("Deleted dimension '" + name + "'"), true);
        return 1;
    }
}
