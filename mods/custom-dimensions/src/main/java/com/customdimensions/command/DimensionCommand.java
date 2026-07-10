package com.customdimensions.command;

import com.customdimensions.config.DimensionDefinition;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.Set;

public class DimensionCommand {
    private static final Set<String> VALID_TYPES = Set.of(
            "overworld", "nether", "end", "void", "superflat", "amplified", "large_biomes", "single_biome", "multi_biome", "sky_islands", "nether_islands"
    );

    private static final SuggestionProvider<ServerCommandSource> DIMENSION_TYPES = (ctx, builder) -> {
        for (String t : VALID_TYPES) {
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
                                                // word(), not longArg(): Minecraft seeds are raw 64-bit
                                                // values often written as unsigned decimals above
                                                // Long.MAX_VALUE, which longArg() rejects. Parsed in
                                                // executeCreate with parseUnsignedLong fallback.
                                                .then(CommandManager.argument("seed", StringArgumentType.word())
                                                        .executes(DimensionCommand::executeCreate)
                                                        // Quoted string, not an identifier: multi_biome and the
                                                        // island types take comma-separated biome lists
                                                        // (e.g. "minecraft:plains,terralith:shield"), and commas
                                                        // are illegal in identifiers.
                                                        .then(CommandManager.argument("biome", StringArgumentType.string())
                                                                .executes(DimensionCommand::executeCreate)
                                                                .then(CommandManager.argument("peaceful", BoolArgumentType.bool())
                                                                        .executes(DimensionCommand::executeCreate)))))))
                        .then(CommandManager.literal("load")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .suggests(EXISTING_DIMENSIONS)
                                        .executes(DimensionCommand::executeLoad)))
                        .then(CommandManager.literal("delete")
                                .then(CommandManager.argument("name", StringArgumentType.string())
                                        .suggests(EXISTING_DIMENSIONS)
                                        .executes(DimensionCommand::executeDelete)))
        );
    }

    private static int executeLoad(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        if (MultiverseConfig.getInstance().getDimension(name) == null) {
            ctx.getSource().sendError(Text.literal("Dimension '" + name + "' not found"));
            return 0;
        }
        // NEVER create the world synchronously here: world creation from a
        // command deadlocked the production main thread (the new world's
        // chunk system init waits on main-thread work that can't run while
        // this command holds it). Queue it for END_SERVER_TICK like the
        // portal path — it loads within a tick of the command returning.
        DimensionManager.getInstance().requestWorldLoad(name);
        ctx.getSource().sendFeedback(() -> Text.literal("Queued load of dimension '" + name + "' (" + DimensionDefinition.NAMESPACE + ":" + name + ") — ready next tick"), true);
        return 1;
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
        if (!VALID_TYPES.contains(type)) {
            ctx.getSource().sendError(Text.literal("Invalid type. Use: overworld, nether, end, void, superflat, amplified, large_biomes, single_biome"));
            return 0;
        }

        Long seed = null;
        String seedStr = null;
        try {
            seedStr = StringArgumentType.getString(ctx, "seed");
        } catch (Exception ignored) {
        }
        if (seedStr != null) {
            try {
                seed = Long.parseLong(seedStr);
            } catch (NumberFormatException e) {
                try {
                    // Unsigned decimals above Long.MAX_VALUE map to the same
                    // 64-bit pattern vanilla uses for seeds.
                    seed = Long.parseUnsignedLong(seedStr);
                } catch (NumberFormatException e2) {
                    ctx.getSource().sendError(Text.literal("Invalid seed '" + seedStr + "' — must be a 64-bit number"));
                    return 0;
                }
            }
        }

        String biome = null;
        try {
            biome = StringArgumentType.getString(ctx, "biome");
        } catch (Exception ignored) {
        }
        if (biome != null && biome.isEmpty()) {
            biome = null;
        }
        if (biome != null) {
            for (String token : biome.split(",")) {
                if (Identifier.tryParse(token.trim()) == null) {
                    ctx.getSource().sendError(Text.literal("Invalid biome id '" + token.trim() + "' in biome list"));
                    return 0;
                }
            }
        }

        if ((type.equals("single_biome") || type.equals("multi_biome")) && biome == null) {
            ctx.getSource().sendError(Text.literal(type + " type requires a biome argument (e.g., \"minecraft:cherry_grove\" or \"cherry_grove,meadow,flower_forest\")"));
            return 0;
        }

        boolean peaceful = false;
        try {
            peaceful = BoolArgumentType.getBool(ctx, "peaceful");
        } catch (Exception ignored) {
        }

        DimensionDefinition def = new DimensionDefinition(name, type, DimensionDefinition.NAMESPACE + ":" + name);
        def.setSeed(seed);
        def.setBiome(biome);
        if (peaceful) {
            def.setHostileSpawning(false);
        }
        MultiverseConfig.getInstance().addDimension(def);
        DimensionManager.getInstance().registerDimension(def);
        // World materialises at END_SERVER_TICK — synchronous creation from
        // a command deadlocked production under load (see executeLoad).
        DimensionManager.getInstance().requestWorldLoad(name);

        String extra = (seed != null ? ", seed: " + seed : "") + (biome != null ? ", biome: " + biome : "") + (peaceful ? ", peaceful" : "");
        ctx.getSource().sendFeedback(() -> Text.literal("Created dimension '" + name + "' (type: " + type + extra + ")"), true);
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
