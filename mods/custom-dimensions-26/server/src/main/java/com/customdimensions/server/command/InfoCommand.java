package com.customdimensions.server.command;

import com.customdimensions.compiler.datapack.BedrockHeights;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.Difficulty;
import com.customdimensions.core.definition.Generator;
import com.customdimensions.server.command.CustomDimCommands.Dimension;
import com.customdimensions.server.difficulty.DimensionDifficulty;
import com.customdimensions.server.level.LevelManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * {@code /customdim info <dimension>}: generator, seed, height, difficulty,
 * peaceful, portals, exits, wins, borders, state and any Bedrock height warning.
 */
final class InfoCommand {
    private InfoCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("info").then(CustomDimCommands.dimensionArgument()
                .executes(c -> info(c.getSource(), StringArgumentType.getString(c, "dimension")))));
    }

    static int info(CommandSourceStack source, String slug) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return 0;
        Optional<Dimension> found = CustomDimCommands.dimension(source, manager.get(), slug);
        if (found.isEmpty()) return 0;
        Dimension d = found.get();
        MinecraftServer server = source.getServer();
        Definition def = d.definition();
        Optional<ServerLevel> level = CustomDimCommands.level(server, manager.get(), d);
        CustomDimCommands.ok(source, "customdim.info.header", "dimension", d.slug(), "state",
                CustomDimCommands.state(manager.get(), d));
        CustomDimCommands.ok(source, "customdim.info.generator", "generator", generator(def));
        CustomDimCommands.ok(source, "customdim.info.seed", "seed", level.map(l -> String.valueOf(l.getSeed()))
                .orElse(def == null || def.seed() == null ? "?" : String.valueOf(def.seed().value())));
        Optional<Holder<DimensionType>> type = type(server, manager.get(), d);
        type.ifPresent(t -> CustomDimCommands.ok(source, "customdim.info.height", "minY", String.valueOf(t.value().minY()),
                "maxY", String.valueOf(t.value().minY() + t.value().height()), "height", String.valueOf(t.value().height())));
        Difficulty difficulty = DimensionDifficulty.of(d.key());
        CustomDimCommands.ok(source, "customdim.info.difficulty", "mobs", number(difficulty.mobMultiplier()),
                "luck", number(difficulty.playerLuck()), "depth", depth(difficulty.depthScaling()),
                "hostile", difficulty.hostileSpawning() ? "on" : "off");
        CustomDimCommands.ok(source, "customdim.info.peaceful", "peaceful",
                DimensionDifficulty.isPeaceful(d.key()) ? "yes" : "no");
        List<JsonObject> portals = def == null ? List.of() : def.portals();
        CustomDimCommands.ok(source, "customdim.info.portals", "count", String.valueOf(portals.size()),
                "portals", portals(portals));
        CustomDimCommands.ok(source, "customdim.info.exits", "exits", exits(def));
        CustomDimCommands.ok(source, "customdim.info.wins", "wins", String.valueOf(def == null ? 0 : def.wins().size()));
        Integer generation = def == null || def.borders() == null ? null : def.borders().generation();
        if (generation == null) generation = defaultGeneration(manager.get());
        CustomDimCommands.ok(source, "customdim.info.borders", "player", d.borderRadius() == null ? "none" : String.valueOf(d.borderRadius()),
                "generation", generation == null ? "none" : String.valueOf(generation));
        type.flatMap(t -> bedrock(server, t)).ifPresent(w -> CustomDimCommands.ok(source, "customdim.info.bedrock", "warning", w));
        return 1;
    }

    static String generator(Definition def) {
        if (def == null || def.generator() == null) return "no definition";
        Generator g = def.generator();
        if ("clone".equals(g.type())) return "clone of " + g.of();
        return g.type() + (g.preset() == null ? "" : " (" + g.preset() + ")");
    }

    /** The dimension type the level runs, or its stem names. */
    static Optional<Holder<DimensionType>> type(MinecraftServer server, LevelManager manager, Dimension d) {
        Optional<ServerLevel> level = CustomDimCommands.level(server, manager, d);
        if (level.isPresent()) return Optional.of(level.get().dimensionTypeRegistration());
        if (d.lazy() == null) return Optional.empty();
        LevelStem stem = manager.index().decode(server, d.lazy());
        return Optional.of(stem.type());
    }

    /** {@link BedrockHeights}' warning for a pack dimension type; vanilla's types are left alone. */
    static Optional<String> bedrock(MinecraftServer server, Holder<DimensionType> type) {
        Optional<ResourceKey<DimensionType>> key = type.unwrapKey();
        if (key.isEmpty() || key.get().identifier().getNamespace().equals("minecraft")) return Optional.empty();
        JsonElement json = DimensionType.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()),
                type.value()).getOrThrow();
        return Optional.ofNullable(BedrockHeights.check(json.getAsJsonObject()));
    }

    private static Integer defaultGeneration(LevelManager manager) {
        JsonObject defaults = manager.index().defaults();
        if (!defaults.has("borders") || !defaults.getAsJsonObject("borders").has("generation")) return null;
        return defaults.getAsJsonObject("borders").get("generation").getAsInt();
    }

    private static String portals(List<JsonObject> portals) {
        List<String> out = new ArrayList<>();
        for (JsonObject p : portals) {
            out.add("[frame " + string(p.get("frameBlock")) + ", igniter " + string(p.get("igniterItem")) + "]");
        }
        return String.join(" ", out);
    }

    private static String exits(Definition def) {
        if (def == null) return "none";
        List<String> out = new ArrayList<>();
        if (def.exits() != null) out.addAll(def.exits().keySet());
        if (def.exitPortal() != null) out.add("exit portal");
        if (def.exitShrines() != null) out.add("exit shrines");
        return out.isEmpty() ? "none" : String.join(", ", out);
    }

    private static String depth(Difficulty.DepthScaling depth) {
        if (depth == null) return "off";
        return "x" + number(depth.minMultiplier()) + " at y " + depth.startY() + " to x" + number(depth.maxMultiplier())
                + " at y " + depth.endY();
    }

    private static String string(JsonElement e) {
        return e == null ? "default" : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
