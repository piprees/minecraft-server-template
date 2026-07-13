package com.customdimensions.command;

import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.dimension.DimensionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class PortalCommand {
    private static final SuggestionProvider<ServerCommandSource> EXISTING_PORTALS = (ctx, builder) -> {
        for (String id : MultiverseConfig.getInstance().getPortalIds()) {
            if (id.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("portal")
                        // Admin-only, same gate as /dimension.
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("link")
                                .then(CommandManager.argument("id", StringArgumentType.string())
                                        .then(CommandManager.argument("frame", IdentifierArgumentType.identifier())
                                                .suggests(blockSuggester())
                                                .then(CommandManager.argument("igniter", IdentifierArgumentType.identifier())
                                                        .suggests(itemSuggester())
                                                        .then(CommandManager.argument("target", IdentifierArgumentType.identifier())
                                                                .suggests(dimSuggester())
                                                                .then(CommandManager.argument("color", StringArgumentType.string())
                                                                        .then(CommandManager.argument("light", IntegerArgumentType.integer(0, 15))
                                                                                .suggests(lightSuggester())
                                                                                .executes(PortalCommand::executeLink)
                                                                                .then(CommandManager.argument("scale", DoubleArgumentType.doubleArg(0.001, 1000.0))
                                                                                        .executes(PortalCommand::executeLink)
                                                                                        .then(CommandManager.argument("cooldown", IntegerArgumentType.integer(0, 200))
                                                                                                .executes(PortalCommand::executeLink)
                                                                                                .then(CommandManager.argument("particle", IdentifierArgumentType.identifier())
                                                                                                        .executes(PortalCommand::executeLink)))))))))))
                        .then(CommandManager.literal("delete")
                                .then(CommandManager.argument("id", StringArgumentType.string())
                                        .suggests(EXISTING_PORTALS)
                                        .executes(PortalCommand::executeDelete)))
        );
    }

    private static int executeLink(CommandContext<ServerCommandSource> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        String frame = IdentifierArgumentType.getIdentifier(ctx, "frame").toString();
        String igniter = IdentifierArgumentType.getIdentifier(ctx, "igniter").toString();
        String target = IdentifierArgumentType.getIdentifier(ctx, "target").toString();
        String color = StringArgumentType.getString(ctx, "color");
        int light = IntegerArgumentType.getInteger(ctx, "light");

        double scale = 1.0;
        try {
            scale = DoubleArgumentType.getDouble(ctx, "scale");
        } catch (Exception ignored) {
        }
        int cooldown = 40;
        try {
            cooldown = IntegerArgumentType.getInteger(ctx, "cooldown");
        } catch (Exception ignored) {
        }

        if (MultiverseConfig.getInstance().getPortal(id) != null) {
            ctx.getSource().sendError(Text.literal("Portal ID '" + id + "' already exists"));
            return 0;
        }
        if (!color.matches("[0-9A-Fa-f]{6}")) {
            ctx.getSource().sendError(Text.literal("Color must be a 6-digit hex (e.g., FF0000)"));
            return 0;
        }

        String particleType = null;
        try {
            particleType = IdentifierArgumentType.getIdentifier(ctx, "particle").toString();
        } catch (Exception ignored) {
        }

        PortalDefinition def = new PortalDefinition(id, frame, igniter, target, color.toUpperCase(), light);
        def.setScale(scale);
        def.setCooldown(cooldown);
        if (particleType != null) {
            def.setParticleType(particleType);
        }
        MultiverseConfig.getInstance().addPortal(def);

        // NEVER create the world synchronously here: this is the same
        // command-context deadlock DimensionCommand.executeLoad was fixed
        // for (the new world's chunk system init waits on main-thread work
        // this command is holding). setup-dimensions.sh hits this ~57 times
        // per full deploy. Queue for END_SERVER_TICK instead — a no-op for
        // vanilla targets, which need no creation.
        Identifier targetId = Identifier.tryParse(target);
        DimensionManager.getInstance().requestWorldLoad(targetId != null ? targetId.getPath() : target);

        ctx.getSource().sendFeedback(() -> Text.literal("Linked portal '" + id + "' -> " + target), true);
        return 1;
    }

    private static int executeDelete(CommandContext<ServerCommandSource> ctx) {
        String id = StringArgumentType.getString(ctx, "id");

        if (!MultiverseConfig.getInstance().removePortal(id)) {
            ctx.getSource().sendError(Text.literal("Portal '" + id + "' not found"));
            return 0;
        }

        ctx.getSource().sendFeedback(() -> Text.literal("Deleted portal '" + id + "'"), true);
        return 1;
    }

    private static SuggestionProvider<ServerCommandSource> blockSuggester() {
        return (ctx, builder) -> {
            String[] blocks = {"minecraft:obsidian", "minecraft:iron_block", "minecraft:gold_block",
                    "minecraft:diamond_block", "minecraft:netherite_block", "minecraft:stone",
                    "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:stone_bricks",
                    "minecraft:quartz_block"};
            String rem = builder.getRemainingLowerCase();
            for (String b : blocks) {
                if (b.toLowerCase().startsWith(rem)) {
                    builder.suggest(b);
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<ServerCommandSource> itemSuggester() {
        return (ctx, builder) -> {
            String[] items = {"minecraft:flint_and_steel", "minecraft:fire_charge", "minecraft:stick",
                    "minecraft:blaze_rod", "minecraft:gold_ingot", "minecraft:diamond",
                    "minecraft:ender_pearl", "minecraft:stone"};
            String rem = builder.getRemainingLowerCase();
            for (String i : items) {
                if (i.toLowerCase().startsWith(rem)) {
                    builder.suggest(i);
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<ServerCommandSource> dimSuggester() {
        return (ctx, builder) -> {
            String rem = builder.getRemainingLowerCase();
            String[] vanilla = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
            for (String v : vanilla) {
                if (v.toLowerCase().startsWith(rem)) {
                    builder.suggest(v);
                }
            }
            for (String name : MultiverseConfig.getInstance().getDimensionNames()) {
                String full = com.customdimensions.config.DimensionDefinition.NAMESPACE + ":" + name;
                if (full.toLowerCase().startsWith(rem)) {
                    builder.suggest(full);
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<ServerCommandSource> lightSuggester() {
        return (ctx, builder) -> {
            String rem = builder.getRemaining();
            for (int i = 0; i <= 15; i++) {
                String s = String.valueOf(i);
                if (s.startsWith(rem)) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        };
    }
}
