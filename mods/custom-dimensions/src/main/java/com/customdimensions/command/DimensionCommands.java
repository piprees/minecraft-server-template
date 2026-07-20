package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.mixin.MultiNoiseBiomeSourceAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Operator commands for runtime dimension lifecycle — the seed roller's
 * workhorse (SEED_ROLL_MODE boots skip creation; the roller then drives
 * create/measure/destroy over RCON):
 *
 *   /customdim create <name> <type> <seed> [noiseSettings] [structureDensity] [biome]
 *   /customdim destroy <name>
 *   /customdim list
 *   /customdim locate biome <dimension> <biome_id> [timeout]
 *   /customdim locate structure <dimension> <structure_id> [timeout]
 *   /customdim locate-result <uuid>
 *   /customdim dump-biome-params <dimension>
 *
 * '-' marks an optional argument as unset (noiseSettings is an Identifier
 * argument, so '-' arrives as "minecraft:-" — both spellings are treated as
 * absent; an unknown noise id falls back to the type default by design).
 *
 * The locate commands run off the server thread and return a UUID
 * immediately. Poll locate-result to get the answer. Designed for the
 * seed roller's bulk measurement — each call would otherwise block the
 * server thread for minutes with 130+ structure mods.
 */
public class DimensionCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("customdim")
                .requires(source -> source.hasPermissionLevel(4))
                .then(CommandManager.literal("create")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .then(CommandManager.argument("type", StringArgumentType.string())
                            .then(CommandManager.argument("seed", LongArgumentType.longArg())
                                .executes(ctx -> create(ctx,
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "type"),
                                    LongArgumentType.getLong(ctx, "seed"),
                                    null, null, null))
                                .then(CommandManager.argument("noiseSettings", IdentifierArgumentType.identifier())
                                    .executes(ctx -> create(ctx,
                                        StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "type"),
                                        LongArgumentType.getLong(ctx, "seed"),
                                        IdentifierArgumentType.getIdentifier(ctx, "noiseSettings").toString(),
                                        null, null))
                                    .then(CommandManager.argument("structureDensity", StringArgumentType.word())
                                        .executes(ctx -> create(ctx,
                                            StringArgumentType.getString(ctx, "name"),
                                            StringArgumentType.getString(ctx, "type"),
                                            LongArgumentType.getLong(ctx, "seed"),
                                            IdentifierArgumentType.getIdentifier(ctx, "noiseSettings").toString(),
                                            StringArgumentType.getString(ctx, "structureDensity"),
                                            null))
                                        .then(CommandManager.argument("biome", StringArgumentType.greedyString())
                                            .executes(ctx -> create(ctx,
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "type"),
                                                LongArgumentType.getLong(ctx, "seed"),
                                                IdentifierArgumentType.getIdentifier(ctx, "noiseSettings").toString(),
                                                StringArgumentType.getString(ctx, "structureDensity"),
                                                StringArgumentType.getString(ctx, "biome"))))))))))
                .then(CommandManager.literal("destroy")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> destroy(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(CommandManager.literal("list")
                    .executes(DimensionCommands::list))
                .then(CommandManager.literal("locate")
                    .then(CommandManager.literal("biome")
                        .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                            .then(CommandManager.argument("biome_id", IdentifierArgumentType.identifier())
                                .executes(ctx -> locateBiome(ctx, 120))
                                .then(CommandManager.argument("timeout", IntegerArgumentType.integer(1, 600))
                                    .executes(ctx -> locateBiome(ctx,
                                        IntegerArgumentType.getInteger(ctx, "timeout")))))))
                    .then(CommandManager.literal("structure")
                        .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                            .then(CommandManager.argument("structure_id", StringArgumentType.string())
                                .executes(ctx -> locateStructure(ctx, 120))
                                .then(CommandManager.argument("timeout", IntegerArgumentType.integer(1, 600))
                                    .executes(ctx -> locateStructure(ctx,
                                        IntegerArgumentType.getInteger(ctx, "timeout"))))))))
                .then(CommandManager.literal("locate-result")
                    .then(CommandManager.argument("uuid", StringArgumentType.string())
                        .executes(DimensionCommands::locateResult)))
                .then(CommandManager.literal("dump-biome-params")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .executes(DimensionCommands::dumpBiomeParams)))
                .then(CommandManager.literal("debug-prng")
                    .then(CommandManager.argument("seed", LongArgumentType.longArg())
                        .executes(DimensionCommands::debugPrng)))
                .then(CommandManager.literal("sample-noise")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("x", IntegerArgumentType.integer())
                            .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(DimensionCommands::sampleNoise)))))
                .then(CommandManager.literal("sample-biome-grid")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("radius", IntegerArgumentType.integer(64, 8192))
                            .then(CommandManager.argument("step", IntegerArgumentType.integer(16, 512))
                                .executes(DimensionCommands::sampleBiomeGrid)))))
        );
    }

    private static boolean unset(String value) {
        return value == null || value.equals("-") || value.equals("minecraft:-");
    }

    private static int create(CommandContext<ServerCommandSource> ctx, String name,
                              String type, long seed, String noiseSettings,
                              String structureDensity, String biome) {
        ServerCommandSource source = ctx.getSource();
        DimensionManager mgr = DimensionManager.getInstance();

        DimensionConfig def = new DimensionConfig();
        def.setName(name);
        def.setNamespace(MultiverseConfig.getInstance().getNamespace());
        def.setType(type);
        def.setSeed(seed);
        if (!unset(noiseSettings)) {
            def.setNoiseSettings(noiseSettings);
        }
        if (!unset(structureDensity)) {
            def.setStructureDensity(structureDensity);
        }
        if (!unset(biome)) {
            def.setBiome(biome);
        }

        try {
            mgr.rememberRuntimeDefinition(def);
            mgr.registerDimension(def);
            mgr.requestWorldLoadDirect(name);
            source.sendFeedback(() -> Text.literal(
                "Queued dimension '" + name + "' (type: " + type + ", seed: " + seed + ")"), true);
            return 1;
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to create dimension via command: {}", name, e);
            source.sendError(Text.literal("Failed to create dimension: " + e.getMessage()));
            return 0;
        }
    }

    private static int destroy(CommandContext<ServerCommandSource> ctx, String name) {
        ServerCommandSource source = ctx.getSource();
        DimensionManager mgr = DimensionManager.getInstance();

        mgr.requestWorldUnload(name);
        mgr.forgetRuntimeDefinition(name);

        source.sendFeedback(() -> Text.literal("Queued destruction of dimension '" + name + "'"), true);
        return 1;
    }

    private static int list(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        var worlds = ((com.customdimensions.mixin.MinecraftServerAccessor) ctx.getSource().getServer()).getWorlds();

        int count = 0;
        for (var key : worlds.keySet()) {
            if (MultiverseConfig.getInstance().isManagedNamespace(key.getValue().getNamespace())) {
                source.sendFeedback(() -> Text.literal("  " + key.getValue()), false);
                count++;
            }
        }
        int finalCount = count;
        source.sendFeedback(() -> Text.literal(finalCount + " custom dimension(s) loaded"), false);
        return count;
    }

    private static ServerWorld resolveWorld(CommandContext<ServerCommandSource> ctx) {
        Identifier dimId = IdentifierArgumentType.getIdentifier(ctx, "dimension");
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
        return ctx.getSource().getServer().getWorld(worldKey);
    }

    private static int locateBiome(CommandContext<ServerCommandSource> ctx, int timeout) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal("Dimension not loaded"));
            return 0;
        }
        Identifier biomeId = IdentifierArgumentType.getIdentifier(ctx, "biome_id");
        UUID id = LocateManager.getInstance().submitBiomeLocate(world, biomeId, timeout);
        source.sendFeedback(() -> Text.literal("locate:" + id + " pending"), false);
        return 1;
    }

    private static int locateStructure(CommandContext<ServerCommandSource> ctx, int timeout) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal("Dimension not loaded"));
            return 0;
        }
        String rawId = StringArgumentType.getString(ctx, "structure_id");
        boolean isTag = rawId.startsWith("#");
        String cleanId = isTag ? rawId.substring(1) : rawId;
        Identifier structureId = Identifier.tryParse(cleanId);
        if (structureId == null) {
            source.sendError(Text.literal("Invalid structure identifier: " + rawId));
            return 0;
        }
        UUID id = LocateManager.getInstance().submitStructureLocate(world, structureId, isTag, timeout);
        source.sendFeedback(() -> Text.literal("locate:" + id + " pending"), false);
        return 1;
    }

    private static int locateResult(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        String uuidStr = StringArgumentType.getString(ctx, "uuid");
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal("Invalid UUID: " + uuidStr));
            return 0;
        }
        String result = LocateManager.getInstance().formatResult(uuid);
        source.sendFeedback(() -> Text.literal(result), false);
        return 1;
    }

    private static int dumpBiomeParams(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal("Dimension not loaded"));
            return 0;
        }

        var biomeSource = world.getChunkManager().getChunkGenerator().getBiomeSource();
        if (!(biomeSource instanceof MultiNoiseBiomeSource mnbs)) {
            source.sendError(Text.literal("Not a MultiNoiseBiomeSource"));
            return 0;
        }

        var entries = ((MultiNoiseBiomeSourceAccessor) mnbs).invokeGetBiomeEntries();
        var entryList = entries.getEntries();

        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < entryList.size(); i++) {
            var pair = entryList.get(i);
            var cube = pair.getFirst();
            var biome = pair.getSecond();

            String biomeId = biome.getKey()
                .map(k -> k.getValue().toString())
                .orElse("unknown");

            if (i > 0) json.append(",\n");
            json.append("  {\"biome\": \"").append(biomeId).append("\"");
            appendRange(json, "temperature", cube.temperature());
            appendRange(json, "humidity", cube.humidity());
            appendRange(json, "continentalness", cube.continentalness());
            appendRange(json, "erosion", cube.erosion());
            appendRange(json, "depth", cube.depth());
            appendRange(json, "weirdness", cube.weirdness());
            json.append(", \"offset\": ").append(cube.offset() / 10000.0);
            json.append("}");
        }
        json.append("\n]\n");

        try {
            Path outputPath = FabricLoader.getInstance().getConfigDir()
                .resolve("custom-dimensions").resolve("biome_params.json");
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, json.toString());
            int count = entryList.size();
            source.sendFeedback(() -> Text.literal(
                "Dumped " + count + " biome entries to biome_params.json"), false);
            return count;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write biome params", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int debugPrng(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        long seed = LongArgumentType.getLong(ctx, "seed");

        // Test all 5 noise parameters with the same chain as Python:
        // seed → Xoroshiro → fork → split("minecraft:ID") → DoublePerlin
        String[][] noiseTests = {
            {"minecraft:temperature", "-10", "1.5,0,1,0,0,0"},
            {"minecraft:vegetation", "-8", "1,1,0,0,0,0"},
            {"minecraft:continentalness", "-9", "1,1,2,2,2,1,1,1,1"},
            {"minecraft:erosion", "-9", "1,1,0,1,1"},
            {"minecraft:ridge", "-7", "1,2,1,0,0,0"},
        };

        var rng = new net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom(seed);
        var splitter = rng.nextSplitter();

        for (String[] test : noiseTests) {
            String noiseId = test[0];
            int firstOctave = Integer.parseInt(test[1]);
            String[] ampStrs = test[2].split(",");
            double[] amps = new double[ampStrs.length];
            for (int i = 0; i < ampStrs.length; i++) amps[i] = Double.parseDouble(ampStrs[i]);

            var noiseRng = splitter.split(noiseId);
            var params = new net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters(
                firstOctave, new it.unimi.dsi.fastutil.doubles.DoubleArrayList(amps));
            var dpNoise = net.minecraft.util.math.noise.DoublePerlinNoiseSampler.create(noiseRng, params);
            double v = dpNoise.sample(0, 0, 0);
            source.sendFeedback(() -> Text.literal(String.format(
                "noise %s s(0)=%.10f", noiseId, v)), false);
        }
        return 1;
    }

    private static int sampleNoise(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal("Dimension not loaded"));
            return 0;
        }
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");

        // Sample the biome source's climate point at quarter-resolution
        var chunkGen = world.getChunkManager().getChunkGenerator();
        var biomeSource = chunkGen.getBiomeSource();
        if (!(biomeSource instanceof MultiNoiseBiomeSource mnbs)) {
            source.sendError(Text.literal("Not a MultiNoiseBiomeSource"));
            return 0;
        }

        // Use the noise config from the chunk manager to sample climate
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        int qx = x >> 2;
        int qz = z >> 2;
        MultiNoiseUtil.NoiseValuePoint point = noiseConfig.getMultiNoiseSampler()
            .sample(qx, 0, qz);

        // NoiseValuePoint stores quantized longs (×10000)
        String result = String.format("noise %d %d temp=%.6f humid=%.6f cont=%.6f eros=%.6f depth=%.6f weird=%.6f",
            x, z,
            point.temperatureNoise() / 10000.0,
            point.humidityNoise() / 10000.0,
            point.continentalnessNoise() / 10000.0,
            point.erosionNoise() / 10000.0,
            point.depth() / 10000.0,
            point.weirdnessNoise() / 10000.0);
        source.sendFeedback(() -> Text.literal(result), false);
        return 1;
    }

    private static int sampleBiomeGrid(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal("Dimension not loaded"));
            return 0;
        }
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        int step = IntegerArgumentType.getInteger(ctx, "step");

        var biomeSource = world.getChunkManager().getChunkGenerator().getBiomeSource();
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        var sampler = noiseConfig.getMultiNoiseSampler();

        StringBuilder csv = new StringBuilder();
        int count = 0;
        for (int x = -radius; x <= radius; x += step) {
            for (int z = -radius; z <= radius; z += step) {
                int qx = x >> 2;
                int qz = z >> 2;
                var biome = biomeSource.getBiome(qx, 16, qz, sampler);
                String biomeId = biome.getKey()
                    .map(k -> k.getValue().toString())
                    .orElse("unknown");
                csv.append(x).append(',').append(z).append(',').append(biomeId).append('\n');
                count++;
            }
        }

        try {
            Path outputPath = FabricLoader.getInstance().getConfigDir()
                .resolve("custom-dimensions").resolve("biome_grid.csv");
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, csv.toString());
            int finalCount = count;
            source.sendFeedback(() -> Text.literal(
                "grid " + finalCount + " points"), false);
            return count;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write biome grid", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    private static void appendRange(StringBuilder json, String name,
                                    MultiNoiseUtil.ParameterRange range) {
        json.append(", \"").append(name).append("\": [")
            .append(range.min() / 10000.0).append(", ")
            .append(range.max() / 10000.0).append("]");
    }
}
