package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.dimension.FixedStructurePlacement;
import com.customdimensions.dimension.NoiseStructurePlacement;
import com.customdimensions.dimension.StructureGroupRegistry;
import com.customdimensions.dimension.StructurePickHelper;
import com.customdimensions.dimension.StructurePoolRecord;
import com.customdimensions.mixin.MultiNoiseBiomeSourceAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
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
 *   /customdim structure-audit [group]
 *   /customdim structure-census <dimension>
 *   /customdim occupant <dimension> <chunkX> <chunkZ>
 *   /customdim carver-draw <dimension> <chunkX> <chunkZ>
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
                .then(CommandManager.literal("load")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> load(ctx, StringArgumentType.getString(ctx, "name")))))
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
                .then(CommandManager.literal("sample-height")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("seed", LongArgumentType.longArg())
                            .then(CommandManager.argument("centerX", IntegerArgumentType.integer())
                                .then(CommandManager.argument("centerZ", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("radius", IntegerArgumentType.integer(0, 8192))
                                        .then(CommandManager.argument("step", IntegerArgumentType.integer(1, 512))
                                            .executes(DimensionCommands::sampleHeight))))))))
                .then(CommandManager.literal("structure-audit")
                    .executes(ctx -> structureAudit(ctx, null))
                    .then(CommandManager.argument("group", StringArgumentType.word())
                        .executes(ctx -> structureAudit(ctx,
                            StringArgumentType.getString(ctx, "group")))))
                .then(CommandManager.literal("structure-census")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .executes(DimensionCommands::structureCensus)))
                .then(CommandManager.literal("occupant")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("chunkX", IntegerArgumentType.integer())
                            .then(CommandManager.argument("chunkZ", IntegerArgumentType.integer())
                                .executes(DimensionCommands::occupant)))))
                .then(CommandManager.literal("carver-draw")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("chunkX", IntegerArgumentType.integer())
                            .then(CommandManager.argument("chunkZ", IntegerArgumentType.integer())
                                .executes(DimensionCommands::carverDraw)))))
                .then(CommandManager.literal("dump-structure-pools")
                    .executes(DimensionCommands::dumpStructurePools))
        );
    }

    /**
     * Writes which structures each loaded dimension's noise groups can draw
     * from, and with what weight, to
     * config/custom-dimensions/structure_pools.json.
     *
     * The seed roller needs this to tell a Village from any-old-settlement; it
     * cannot derive it, because membership depends on each structure's own biome
     * list against the dimension's biome source. See
     * {@link StructurePoolRecord} for the full reasoning and for why a partial
     * dump is safe.
     *
     * Cheap by design — pools only, no positions. A dimension whose world has
     * not loaded yet is simply absent, and the roller falls back to the
     * group-level reading for it.
     */
    private static int dumpStructurePools(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        int dimensions = StructurePoolRecord.size();
        if (dimensions == 0) {
            source.sendError(Text.literal(
                "No dimension has installed a structure pool yet — managed "
                + "dimensions record theirs as their world loads."));
            return 0;
        }
        try {
            Path outputPath = Artefacts.dir().resolve("structure_pools.json");
            // The absorption list stamps the dump: pools are only meaningful
            // under the noiseManaged() rules they were dumped with, and the
            // roller ignores a dump made under a different list (share 1.0
            // fallback) instead of scoring newly absorbed sets 0.0 forever.
            StringBuilder types = new StringBuilder(" \"placementTypes\": [");
            java.util.List<String> ids =
                    com.customdimensions.dimension.NoisePoolBuilder.noiseManagedTypeIds();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    types.append(", ");
                }
                types.append('"').append(ids.get(i)).append('"');
            }
            types.append("],\n");
            Artefacts.write(outputPath, StructurePoolRecord.toJson(
                    Artefacts.jsonHeader("structure-pools") + types));
            final String message = "dump-structure-pools: " + dimensions
                    + " dimension(s) -> " + outputPath;
            source.sendFeedback(() -> Text.literal(message), false);
            return dimensions;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write structure pools", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Dumps every noise-placed structure position in a dimension, grouped, to
     * config/custom-dimensions/census/&lt;namespace&gt;__&lt;path&gt;.json.
     *
     * A file rather than command output: a large dimension holds thousands of
     * positions per group, which no RCON response can carry. The format
     * mirrors the roller's `structure_all` so F4 can diff the two directly.
     *
     * Reads the world's LIVE StructurePlacementCalculator — the same objects
     * chunk generation and /locate consult — rather than recomputing from
     * config. Recomputing would be the one thing guaranteed to agree with
     * itself while disagreeing with the world.
     */
    private static int structureCensus(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal(
                "Dimension not loaded: "
                + IdentifierArgumentType.getIdentifier(ctx, "dimension")
                + " (visit it or use /customdim load first)"));
            return 0;
        }
        Identifier dimensionId = world.getRegistryKey().getValue();

        var calculator = world.getChunkManager().getStructurePlacementCalculator();
        StringBuilder json = new StringBuilder(Artefacts.jsonHeader("structure-census"));
        json.append(" \"schemaVersion\": 2,\n");
        json.append(" \"dimension\": \"").append(dimensionId).append("\",\n");
        json.append(" \"seed\": ").append(world.getSeed()).append(",\n");
        json.append(" \"groups\": {");

        int groupCount = 0;
        int total = 0;
        StringBuilder summary = new StringBuilder();
        for (var entry : calculator.getStructureSets()) {
            if (!(entry.value().placement() instanceof NoiseStructurePlacement noise)) {
                continue;
            }
            if (groupCount > 0) {
                json.append(',');
            }
            // The RESOLVED placement inputs, so the parity check (F4) can
            // rebuild the field directly instead of re-deriving them from
            // config. Config resolution is unit-tested on both sides
            // separately; mixing the two here would make a parity failure
            // ambiguous between "the maths diverged" and "the two sides read
            // the config differently".
            var index = noise.index();
            json.append("\n  \"").append(noise.group()).append("\": {\n");
            json.append("   \"profile\": \"").append(index.profileId()).append("\",\n");
            json.append("   \"noiseSeed\": ").append(index.noiseSeed()).append(",\n");
            json.append("   \"exclusion\": ").append(index.exclusion()).append(",\n");
            json.append("   \"radiusChunks\": ").append(index.radiusChunks()).append(",\n");
            json.append("   \"spawnChunkX\": ").append(index.spawnChunkX()).append(",\n");
            json.append("   \"spawnChunkZ\": ").append(index.spawnChunkZ()).append(",\n");
            json.append("   \"radial\": ");
            double[] radial = index.radial();
            if (radial == null) {
                json.append("null");
            } else {
                json.append('[');
                for (int i = 0; i < radial.length; i++) {
                    if (i > 0) {
                        json.append(", ");
                    }
                    json.append(radial[i]);
                }
                json.append(']');
            }
            json.append(",\n");
            json.append("   \"spacing\": ").append(noise.getSpacing()).append(",\n");
            // Per-group structures map: id -> summed weight, sorted. The
            // parity oracle for the roller's pool data. Duplicate pool
            // entries for one id MUST merge here: a JSON object cannot carry
            // duplicate keys (a parser keeps only the last), and the merged
            // map is walk-equivalent to the raw entry list because sortedPool
            // keeps duplicates adjacent (pinned by StructurePickTest).
            java.util.TreeMap<String, Long> poolMap = new java.util.TreeMap<>();
            for (var weighted : entry.value().structures()) {
                weighted.structure().getKey().ifPresent(key -> poolMap.merge(
                        key.getValue().toString(), (long) weighted.weight(), Long::sum));
            }
            json.append("   \"structures\": {");
            int n = 0;
            for (var poolEntry : poolMap.entrySet()) {
                if (n++ > 0) {
                    json.append(", ");
                }
                json.append('"').append(poolEntry.getKey())
                        .append("\": ").append(poolEntry.getValue());
            }
            json.append("},\n");

            // Build the sorted pool and emit each position with its assigned
            // structure id: [chunkX, chunkZ, "ns:structure_id"]. Uses the
            // same StructurePick code path that generation runs.
            java.util.List<com.customdimensions.dimension.StructurePick.PoolEntry> pickPool =
                    new java.util.ArrayList<>();
            for (var weighted : entry.value().structures()) {
                weighted.structure().getKey().ifPresent(key -> pickPool.add(
                        new com.customdimensions.dimension.StructurePick.PoolEntry(
                                key.getValue().toString(), weighted.weight())));
            }
            java.util.List<com.customdimensions.dimension.StructurePick.PoolEntry> sorted =
                    com.customdimensions.dimension.StructurePick.sortedPool(pickPool);
            long noiseSeed = index.noiseSeed();

            json.append("   \"positions\": [");
            int i = 0;
            for (var pos : noise.index().positions()) {
                if (i++ > 0) {
                    json.append(", ");
                }
                String assigned = com.customdimensions.dimension.StructurePick.assignedStructure(
                        noiseSeed, pos.x, pos.z, sorted);
                json.append('[').append(pos.x).append(", ").append(pos.z)
                        .append(", \"").append(assigned != null ? assigned : "").append("\"]");
            }
            json.append("]\n  }");
            groupCount++;
            total += i;
            summary.append(' ').append(noise.group()).append('=').append(i);
        }
        json.append(groupCount > 0 ? "\n }" : "}");

        // Forced placements are not noise, but a census that omitted them
        // would look like a "none + force" dimension had nothing at all.
        json.append(",\n \"forced\": {");
        int forcedGroups = 0;
        int forcedTotal = 0;
        for (var entry : calculator.getStructureSets()) {
            if (!(entry.value().placement() instanceof FixedStructurePlacement fixed)) {
                continue;
            }
            for (var weighted : entry.value().structures()) {
                if (forcedGroups++ > 0) {
                    json.append(',');
                }
                json.append("\n  \"")
                        .append(weighted.structure().getKey()
                                .map(k -> k.getValue().toString()).orElse("?"))
                        .append("\": [");
                int i = 0;
                for (var pos : fixed.positions()) {
                    if (i++ > 0) {
                        json.append(", ");
                    }
                    json.append('[').append(pos.x).append(',').append(pos.z).append(']');
                }
                json.append(']');
                forcedTotal += i;
            }
        }
        json.append(forcedGroups > 0 ? "\n }" : "}");

        // Sets that keep their own placement (custom placement types, the
        // exit-shrine set). Each entry carries the placement inputs and
        // live getStartChunk positions within the measurement horizon, so
        // the Python parity test can verify its vanilla grid maths per
        // placement type (precision-plan.md §6.3).
        int horizonChunks = 0;
        {
            var dimConfig = MultiverseConfig.getInstance().getDimension(dimensionId.getPath());
            if (dimConfig == null) {
                dimConfig = MultiverseConfig.getInstance().getWorld(dimensionId.getPath());
            }
            int playerBorder = 8192;
            if (dimConfig != null) {
                var borders = dimConfig.getBorders();
                if (borders != null && borders.player > 0) {
                    playerBorder = (int) borders.player;
                }
            }
            horizonChunks = (playerBorder + 2048) / 16;
        }
        var ptCensus = PassThroughCensus.census(
                calculator.getStructureSets(), calculator, world.getSeed(), horizonChunks);
        json.append(",\n \"passThrough\": ");
        json.append(PassThroughCensus.toJson(ptCensus));
        int passThroughCount = ptCensus.size();
        json.append("\n}\n");

        try {
            Path outputPath = Artefacts.dir("census")
                .resolve(dimensionId.getNamespace() + "__" + dimensionId.getPath() + ".json");
            Artefacts.write(outputPath, json.toString());
            final String message = "structure-census " + dimensionId + ": "
                    + groupCount + " groups, " + total + " noise positions"
                    + (forcedTotal > 0 ? ", " + forcedTotal + " forced" : "")
                    + " ->" + summary + " | " + outputPath;
            source.sendFeedback(() -> Text.literal(message), false);
            return total;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write structure census", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Reads the LIVE chunk's structure starts map to report which structures
     * occupy a chunk. The chunk must already be loaded/generated; this command
     * does NOT generate it.
     *
     * For every structure with a start whose start chunk is this chunk and
     * whose start hasChildren, answers one line "occupant ns:id"; if none,
     * answers "empty". Also writes the answer to a census/occupancy artefact.
     */
    private static int occupant(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal(
                    "Dimension not loaded: "
                    + IdentifierArgumentType.getIdentifier(ctx, "dimension")));
            return 0;
        }
        int chunkX = IntegerArgumentType.getInteger(ctx, "chunkX");
        int chunkZ = IntegerArgumentType.getInteger(ctx, "chunkZ");
        Identifier dimensionId = world.getRegistryKey().getValue();

        // The chunk must be loaded — do NOT generate it.
        net.minecraft.world.chunk.WorldChunk chunk =
                world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
        if (chunk == null) {
            source.sendError(Text.literal(
                    "Chunk [" + chunkX + ", " + chunkZ + "] is not loaded in "
                    + dimensionId + " — visit it first or use /forceload. "
                    + "This command does not generate chunks."));
            return 0;
        }

        // Read the chunk's structure starts map.
        java.util.Map<net.minecraft.world.gen.structure.Structure, StructureStart> starts =
                chunk.getStructureStarts();
        var structureRegistry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);

        java.util.List<String> occupants = new java.util.ArrayList<>();
        for (var entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (start == null || !start.hasChildren()) {
                continue;
            }
            // Only report starts whose start chunk IS this chunk
            if (start.getPos().x != chunkX || start.getPos().z != chunkZ) {
                continue;
            }
            String structureId = structureRegistry.getId(entry.getKey()) != null
                    ? structureRegistry.getId(entry.getKey()).toString()
                    : "unknown";
            occupants.add(structureId);
        }

        // Build feedback and artefact
        StringBuilder feedback = new StringBuilder("occupant " + dimensionId
                + " [" + chunkX + ", " + chunkZ + "]: ");
        if (occupants.isEmpty()) {
            feedback.append("empty");
        } else {
            java.util.Collections.sort(occupants);
            for (int i = 0; i < occupants.size(); i++) {
                if (i > 0) {
                    feedback.append(", ");
                }
                feedback.append(occupants.get(i));
            }
        }

        // Write to census/occupancy artefact (append pattern matching rejections)
        try {
            String dimPart = dimensionId.toString().replace(":", "__");
            Path artefactPath = Artefacts.dir("census")
                    .resolve("occupancy__" + dimPart + ".json");

            StringBuilder record = new StringBuilder();
            record.append("{\"chunkX\": ").append(chunkX)
                    .append(", \"chunkZ\": ").append(chunkZ)
                    .append(", \"occupants\": [");
            for (int i = 0; i < occupants.size(); i++) {
                if (i > 0) {
                    record.append(", ");
                }
                record.append('"').append(occupants.get(i)).append('"');
            }
            record.append("]}");

            StringBuilder json;
            if (Files.exists(artefactPath)) {
                String existing = Files.readString(artefactPath);
                int lastBracket = existing.lastIndexOf(']');
                if (lastBracket > 0) {
                    json = new StringBuilder(existing.substring(0, lastBracket));
                    json.append(",\n  ");
                } else {
                    json = newOccupancyFile(dimensionId.toString());
                }
            } else {
                json = newOccupancyFile(dimensionId.toString());
            }
            json.append(record);
            json.append("\n ]\n}\n");
            Artefacts.write(artefactPath, json.toString());
            feedback.append(" -> ").append(artefactPath);
        } catch (IOException e) {
            MultiverseServer.LOGGER.debug("Failed to write occupancy artefact: {}", e.getMessage());
        }

        final String msg = feedback.toString();
        source.sendFeedback(() -> Text.literal(msg), false);
        return occupants.isEmpty() ? 0 : occupants.size();
    }

    private static StringBuilder newOccupancyFile(String dimensionId) {
        StringBuilder json = new StringBuilder(Artefacts.jsonHeader("structure-occupancy"));
        json.append(" \"dimension\": \"").append(dimensionId).append("\",\n");
        json.append(" \"occupants\": [\n  ");
        return json;
    }

    /**
     * Replays vanilla's carver-draw selection for each noise-managed group
     * whose site list contains this chunk. Answers "vanilla-draw ns:id |
     * assigned ns:id" -- first draw only, no rejection fall-through.
     *
     * This is a pure read: no generation, no state change. The draw uses
     * the same LCG chain vanilla's ChunkGenerator.setStructureStarts would
     * use, but the entries are in LIST ORDER (as vanilla sees them) which
     * depends on registry entry order observable only server-side.
     */
    private static int carverDraw(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal(
                    "Dimension not loaded: "
                    + IdentifierArgumentType.getIdentifier(ctx, "dimension")));
            return 0;
        }
        int chunkX = IntegerArgumentType.getInteger(ctx, "chunkX");
        int chunkZ = IntegerArgumentType.getInteger(ctx, "chunkZ");
        Identifier dimensionId = world.getRegistryKey().getValue();

        var calculator = world.getChunkManager().getStructurePlacementCalculator();
        long structureSeed = calculator.getStructureSeed();

        int results = 0;
        StringBuilder summary = new StringBuilder("carver-draw " + dimensionId
                + " [" + chunkX + ", " + chunkZ
                + "] (first draw only, no rejection fall-through):");
        for (var entry : calculator.getStructureSets()) {
            if (!(entry.value().placement() instanceof NoiseStructurePlacement noise)) {
                continue;
            }
            // Is this chunk a placement site for this group?
            if (!noise.index().isPlacement(chunkX, chunkZ)) {
                continue;
            }

            String group = noise.group();
            var structures = entry.value().structures();
            if (structures.size() <= 1) {
                // Single-entry set: vanilla skips the carver draw entirely
                String singleId = structures.isEmpty() ? "?"
                        : structures.get(0).structure().getKey()
                                .map(k -> k.getValue().toString()).orElse("?");
                summary.append("\n  ").append(group).append(": single-entry ")
                        .append(singleId);
                results++;
                continue;
            }

            // Build the entry list in vanilla's LIST ORDER (not sorted)
            java.util.List<CarverDraw.Entry> vanillaEntries = new java.util.ArrayList<>();
            for (var weighted : structures) {
                String id = weighted.structure().getKey()
                        .map(k -> k.getValue().toString()).orElse("?");
                vanillaEntries.add(new CarverDraw.Entry(id, weighted.weight()));
            }

            CarverDraw.DrawResult draw = CarverDraw.draw(
                    vanillaEntries, structureSeed, chunkX, chunkZ);

            // Compute the assigned structure (our pick algorithm)
            String assigned = StructurePickHelper.assignedAt(
                    noise.index().noiseSeed(), chunkX, chunkZ,
                    entry.value().structures());

            if (draw != null) {
                summary.append("\n  ").append(group)
                        .append(": vanilla-draw ").append(draw.vanillaDraw())
                        .append(" | assigned ").append(assigned != null ? assigned : "?")
                        .append(" (j=").append(draw.j())
                        .append("/").append(draw.totalWeight()).append(')');
            }
            results++;
        }

        if (results == 0) {
            summary.append(" no noise-managed group has a site at this chunk");
        }

        final String msg = summary.toString();
        source.sendFeedback(() -> Text.literal(msg), false);
        return results;
    }

    /**
     * Lists every structure set the server knows with its noise group, rarity
     * tier and where that classification came from — `registry` for a row in
     * structure_themes.json (baked or consumer overlay), `inferred` for a set
     * neither knew about, which lands in `deco` with a spacing-derived rarity.
     *
     * Output is one set per line so RCON can be grepped; a trailing summary
     * counts each group and flags the inferred ones, since a large inferred
     * count means a consumer's structure mods are all being treated as
     * scenery.
     */
    private static int structureAudit(CommandContext<ServerCommandSource> ctx, String groupFilter) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld overworld = source.getServer().getOverworld();
        var registry = overworld.getRegistryManager().get(RegistryKeys.STRUCTURE_SET);

        java.util.Map<String, Integer> byGroup = new java.util.TreeMap<>();
        java.util.List<String> lines = new java.util.ArrayList<>();
        int inferred = 0;

        for (var entry : registry.getEntrySet()) {
            String setId = entry.getKey().getValue().toString();
            var placement = entry.getValue().placement();
            int spacing = placement instanceof RandomSpreadStructurePlacement random
                    ? random.getSpacing() : -1;
            var classification = StructureGroupRegistry.classify(setId, spacing);
            byGroup.merge(classification.group(), 1, Integer::sum);
            if (classification.source() == StructureGroupRegistry.Source.INFERRED) {
                inferred++;
            }
            if (groupFilter != null && !groupFilter.equalsIgnoreCase(classification.group())) {
                continue;
            }
            lines.add(String.format("%s group=%s rarity=%s theme=%s source=%s spacing=%s",
                    setId, classification.group(), classification.rarity(),
                    classification.theme() == null ? "-" : classification.theme(),
                    classification.source().name().toLowerCase(java.util.Locale.ROOT),
                    spacing < 0 ? "n/a" : spacing));
        }

        java.util.Collections.sort(lines);

        StringBuilder summary = new StringBuilder("structure-audit: ")
                .append(registry.size()).append(" sets");
        if (groupFilter != null) {
            summary.append(" (").append(lines.size()).append(" matching group ")
                    .append(groupFilter).append(")");
        }
        summary.append(" |");
        for (var e : byGroup.entrySet()) {
            summary.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        summary.append(" | inferred=").append(inferred);

        // The rows go to a file, not the command output. RCON concatenates
        // feedback lines with no separator and truncates the response at a
        // few KB, so ~280 rows come back as one unreadable, half-missing
        // string — which looks like a working command until you try to use it.
        try {
            Path outputPath = Artefacts.dir().resolve("structure-audit.txt");
            StringBuilder body = new StringBuilder(Artefacts.textHeader("structure-audit"));
            body.append("# ").append(summary).append('\n');
            body.append("# set_id group rarity theme source spacing\n");
            for (String line : lines) {
                body.append(line).append('\n');
            }
            Artefacts.write(outputPath, body.toString());
            summary.append(" -> ").append(outputPath);
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write structure audit", e);
            summary.append(" (write failed: ").append(e.getMessage()).append(')');
        }

        final String summaryText = summary.toString();
        source.sendFeedback(() -> Text.literal(summaryText), false);
        return lines.size();
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
        com.customdimensions.dimension.DimensionFingerprints.forget(name);

        source.sendFeedback(() -> Text.literal("Queued destruction of dimension '" + name + "'"), true);
        return 1;
    }

    /**
     * Instantiates a configured dimension's world without a player having to
     * walk into it.
     *
     * <p>Custom dimensions are REGISTERED at boot but their {@code ServerWorld}
     * is created lazily on first entry, so {@code execute in <ns>:<dim>} fails
     * with "Unknown dimension" until somebody visits. That is correct at
     * runtime and wrong for automation: CI's smoke test asserts per-dimension
     * seeds, noise settings and structure density, and every one of those
     * assertions needs a live world. This is the headless equivalent of
     * walking through the portal.
     *
     * <p>Queues via {@code requestWorldLoad} (drained on END_SERVER_TICK)
     * rather than calling {@code getOrCreateDimension} directly — world
     * creation from command context deadlocks the main thread (mods/AGENTS.md,
     * dynamic world lifecycle rule). So this returns immediately and the world
     * appears a tick or two later; callers must poll, not assume.
     */
    private static int load(CommandContext<ServerCommandSource> ctx, String name) {
        ServerCommandSource source = ctx.getSource();
        // Base worlds load through here too: CreateWorldsMixin defers every
        // non-overworld world, so "the_nether" is as absent at boot as any
        // custom dimension and needs the same way in.
        if (MultiverseConfig.getInstance().getDimension(name) == null
                && MultiverseConfig.getInstance().getWorld(name) == null) {
            source.sendError(Text.literal("No configured dimension named '" + name + "'"));
            return 0;
        }
        RegistryKey<World> worldKey = RegistryKey.of(
                RegistryKeys.WORLD, DimensionManager.getInstance().identifierFor(name));
        if (source.getServer().getWorld(worldKey) != null) {
            source.sendFeedback(() -> Text.literal("Dimension " + worldKey.getValue() + " already loaded"), false);
            return 1;
        }
        DimensionManager.getInstance().requestWorldLoad(name);
        source.sendFeedback(() -> Text.literal("Queued load for " + worldKey.getValue()), false);
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

    /**
     * Resolve the {@code dimension} argument to a loaded world.
     *
     * Brigadier's identifier argument defaults a bare name to the
     * {@code minecraft} namespace, so {@code structure-census the_overgrowth}
     * asked about {@code minecraft:the_overgrowth} and answered "Dimension
     * not loaded" while the dimension sat there loaded under the configured
     * namespace — whereas {@code customdim load} takes bare names happily.
     * Two commands in the same tree disagreeing about what a name means is a
     * trap; the configured namespace is tried as a fallback so both spellings
     * work everywhere. A real {@code minecraft:} world still wins, because it
     * is tried first.
     */
    private static ServerWorld resolveWorld(CommandContext<ServerCommandSource> ctx) {
        Identifier dimId = IdentifierArgumentType.getIdentifier(ctx, "dimension");
        ServerWorld world = ctx.getSource().getServer()
                .getWorld(RegistryKey.of(RegistryKeys.WORLD, dimId));
        if (world != null || !"minecraft".equals(dimId.getNamespace())) {
            return world;
        }
        String namespace = MultiverseConfig.getInstance().getNamespace();
        if (namespace == null || namespace.isBlank() || "minecraft".equals(namespace)) {
            return null;
        }
        Identifier managed = Identifier.tryParse(namespace + ":" + dimId.getPath());
        if (managed == null) {
            return null;
        }
        return ctx.getSource().getServer()
                .getWorld(RegistryKey.of(RegistryKeys.WORLD, managed));
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

        var rawBiomeSource = world.getChunkManager().getChunkGenerator().getBiomeSource();
        var biomeSource = com.customdimensions.compat.LithostitchedCompat.unwrap(rawBiomeSource);
        if (!(biomeSource instanceof MultiNoiseBiomeSource mnbs)) {
            source.sendError(Text.literal("Not a MultiNoiseBiomeSource (got "
                    + rawBiomeSource.getClass().getSimpleName() + ")"));
            return 0;
        }

        // Phase 1: static entries from getBiomeEntries() — exact climate
        // cells for vanilla + Terralith (anything that ships multinoise data
        // in its JAR). These are the precise cells the nearest-neighbour
        // sampler needs for correct biome placement.
        var entries = ((MultiNoiseBiomeSourceAccessor) mnbs).invokeGetBiomeEntries();
        var entryList = entries.getEntries();
        var staticBiomes = new java.util.HashSet<String>();

        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < entryList.size(); i++) {
            var pair = entryList.get(i);
            var cube = pair.getFirst();
            var biome = pair.getSecond();

            String biomeId = biome.getKey()
                .map(k -> k.getValue().toString())
                .orElse("unknown");
            staticBiomes.add(biomeId);

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

        // Phase 2: exact entries from TerraBlender regions. Each Region
        // provides its biomes via addBiomes() with exact NoiseHypercube
        // cells — the same format as Phase 1. Only biomes NOT in the
        // static set are emitted (vanilla biomes in TB's
        // DefaultOverworldRegion duplicate Phase 1 cells).
        //
        // TB's RegionType has OVERWORLD and NETHER; there is no END type.
        // End biomes use EndBiomeRegistry (weighted lists, not hypercubes)
        // and the vanilla end uses TheEndBiomeSource (not MNBS), so end
        // dimensions are excluded from this path. A Nullscape-modded end
        // that swaps in MNBS gets its entries from Phase 1 (datapack JSON).
        var biomeRegistry = world.getRegistryManager()
                .get(net.minecraft.registry.RegistryKeys.BIOME);
        boolean isNether = world.getDimension().ultrawarm();
        var tbEntries = isNether
                ? com.customdimensions.compat.TerraBlenderCompat.netherEntries(biomeRegistry)
                : com.customdimensions.compat.TerraBlenderCompat.overworldEntries(biomeRegistry);
        int tbCount = 0;
        var tbBiomes = new java.util.HashSet<String>();
        var sortedTbEntries = new java.util.ArrayList<>(tbEntries);
        sortedTbEntries.sort(java.util.Comparator.comparing(p ->
                p.getSecond().getKey()
                    .map(k -> k.getValue().toString()).orElse("")));
        for (var pair : sortedTbEntries) {
            String biomeId = pair.getSecond().getKey()
                    .map(k -> k.getValue().toString())
                    .orElse("unknown");
            if (staticBiomes.contains(biomeId)) {
                continue;
            }
            tbBiomes.add(biomeId);
            var cube = pair.getFirst();
            json.append(",\n  {\"biome\": \"").append(biomeId).append("\"");
            appendRange(json, "temperature", cube.temperature());
            appendRange(json, "humidity", cube.humidity());
            appendRange(json, "continentalness", cube.continentalness());
            appendRange(json, "erosion", cube.erosion());
            appendRange(json, "depth", cube.depth());
            appendRange(json, "weirdness", cube.weirdness());
            json.append(", \"offset\": ").append(cube.offset() / 10000.0);
            json.append("}");
            tbCount++;
        }

        // Phase 3: biomes the source claims to produce but neither Phase 1
        // nor Phase 2 provided parameters for. Emitted as an exact
        // statement ("we have no parameters") rather than an approximate
        // envelope. The seed roller treats these as absent from placement.
        // Uses the RAW source (pre-unwrap) so injected biomes are included.
        var allBiomes = new java.util.HashSet<String>();
        for (var entry : rawBiomeSource.getBiomes()) {
            entry.getKey().ifPresent(k -> allBiomes.add(k.getValue().toString()));
        }
        int unresolvedCount = 0;
        var sortedAll = new java.util.ArrayList<>(allBiomes);
        java.util.Collections.sort(sortedAll);
        for (String id : sortedAll) {
            if (staticBiomes.contains(id) || tbBiomes.contains(id)) {
                continue;
            }
            json.append(",\n  {\"biome\": \"").append(id)
                    .append("\", \"unresolved\": true}");
            unresolvedCount++;
        }
        json.append("\n]\n");

        try {
            Path outputPath = Artefacts.dir().resolve("biome_params.json");
            Artefacts.write(outputPath, json.toString());
            int staticCount = staticBiomes.size();
            int tbBiomeCount = tbBiomes.size();
            int finalTbCount = tbCount;
            int finalUnresolved = unresolvedCount;
            int totalEntries = entryList.size() + tbCount + unresolvedCount;
            source.sendFeedback(() -> Text.literal(
                "Dumped " + totalEntries + " entries (" + staticCount
                + " static biomes, " + tbBiomeCount + " TB biomes ("
                + finalTbCount + " cells)"
                + (finalUnresolved > 0 ? ", " + finalUnresolved + " unresolved" : "")
                + ") to biome_params.json"), false);
            return totalEntries;
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

        // Leading '#' comment line, so a reader must skip comments — the rows
        // themselves stay plain `x,z,biome`.
        StringBuilder csv = new StringBuilder(Artefacts.textHeader("biome-grid"));
        // Exact provenance for the parity gate: a TerraBlender-wrapped source
        // selects biomes through TB's per-region trees, which the roller does
        // not mirror — such a grid's biome facts are not exactly measurable
        // until it does, and the gate needs that stated in the artefact, not
        // inferred. TB regions only initialise vanilla-tagged dimension
        // types, so most managed dimensions stamp tbInjected=false.
        var gridRaw = biomeSource;
        var gridUnwrapped = com.customdimensions.compat.LithostitchedCompat.unwrap(gridRaw);
        boolean tbInjected = false;
        if (gridUnwrapped instanceof MultiNoiseBiomeSource gridMnbs) {
            var staticIds = new java.util.HashSet<String>();
            for (var entry : ((MultiNoiseBiomeSourceAccessor) gridMnbs)
                    .invokeGetBiomeEntries().getEntries()) {
                entry.getSecond().getKey().ifPresent(
                        k -> staticIds.add(k.getValue().toString()));
            }
            for (var entry : gridRaw.getBiomes()) {
                String id = entry.getKey()
                        .map(k -> k.getValue().toString()).orElse("");
                if (!id.isEmpty() && !staticIds.contains(id)) {
                    tbInjected = true;
                    break;
                }
            }
        }
        csv.append("# tbInjected=").append(tbInjected).append('\n');
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
            Path outputPath = Artefacts.dir().resolve("biome_grid.csv");
            Artefacts.write(outputPath, csv.toString());
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

    /**
     * Predicted structure-generation surface heights (WORLD_SURFACE_WG,
     * pure density-function evaluation — no chunks touched or created) for
     * an ARBITRARY seed: the NoiseConfig is built per invocation from the
     * dimension's own generator settings and the given seed, which is what
     * lets the seed roller ask about seeds no world exists for. Grid rows
     * land in height_samples.csv; the feedback line carries the timing so
     * the flatness-gate cost stays measurable.
     *
     * Ground truth for the roller's terrain questions (the two Python
     * height models approximate; this does not) — but note the c2me caveat
     * in the seed-rolling skill: this server's noise stack must match the
     * one the answer is used against.
     */
    private static int sampleHeight(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal("Dimension not loaded"));
            return 0;
        }
        long seed = LongArgumentType.getLong(ctx, "seed");
        int centerX = IntegerArgumentType.getInteger(ctx, "centerX");
        int centerZ = IntegerArgumentType.getInteger(ctx, "centerZ");
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        int step = IntegerArgumentType.getInteger(ctx, "step");

        var chunkGen = world.getChunkManager().getChunkGenerator();
        if (!(chunkGen instanceof net.minecraft.world.gen.chunk.NoiseChunkGenerator noiseGen)) {
            source.sendError(Text.literal("Not a noise generator — no height model to sample"));
            return 0;
        }
        var noiseParams = world.getRegistryManager()
                .get(RegistryKeys.NOISE_PARAMETERS).getReadOnlyWrapper();
        NoiseConfig noiseConfig = NoiseConfig.create(
                noiseGen.getSettings().value(), noiseParams, seed);

        StringBuilder csv = new StringBuilder(Artefacts.textHeader("height-samples"));
        csv.append("# dimension=").append(world.getRegistryKey().getValue())
           .append(" seed=").append(seed).append('\n');
        long started = System.nanoTime();
        int count = 0;
        for (int x = centerX - radius; x <= centerX + radius; x += step) {
            for (int z = centerZ - radius; z <= centerZ + radius; z += step) {
                int h = chunkGen.getHeight(x, z,
                        net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG,
                        world, noiseConfig);
                csv.append(x).append(',').append(z).append(',').append(h).append('\n');
                count++;
            }
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        try {
            Path outputPath = Artefacts.dir().resolve("height_samples.csv");
            Artefacts.write(outputPath, csv.toString());
            int finalCount = count;
            source.sendFeedback(() -> Text.literal(
                    "heights " + finalCount + " columns in " + elapsedMs + "ms -> "
                    + outputPath), false);
            return count > 0 ? 1 : 0;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write height samples", e);
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
