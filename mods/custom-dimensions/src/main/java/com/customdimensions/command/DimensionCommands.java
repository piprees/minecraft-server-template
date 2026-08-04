package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.dimension.FixedStructurePlacement;
import com.customdimensions.dimension.NoiseStructurePlacement;
import com.customdimensions.dimension.StructureGroupRegistry;
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
        // exit-shrine set). Recorded so per-dimension set-id filtering
        // (structures.mode/exclude) is visible in the artefact — a filtered
        // pass-through must be ABSENT from this list.
        json.append(",\n \"passThrough\": [");
        int passThroughCount = 0;
        for (var entry : calculator.getStructureSets()) {
            var placement = entry.value().placement();
            if (placement instanceof NoiseStructurePlacement
                    || placement instanceof FixedStructurePlacement) {
                continue;
            }
            String setId = entry.getKey().map(k -> k.getValue().toString()).orElse(null);
            if (setId == null) {
                continue;
            }
            if (passThroughCount++ > 0) {
                json.append(", ");
            }
            json.append('"').append(setId).append('"');
        }
        json.append("]\n}\n");

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

        var biomeSource = world.getChunkManager().getChunkGenerator().getBiomeSource();
        if (!(biomeSource instanceof MultiNoiseBiomeSource mnbs)) {
            source.sendError(Text.literal("Not a MultiNoiseBiomeSource"));
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

        // Phase 2: spatial grid sample via the LIVE getBiome() — captures
        // TerraBlender-injected biomes that the static entries miss. For
        // each sample point, record the biome + the climate values. Only
        // biomes NOT already in the static dump get entries here.
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        var sampler = noiseConfig.getMultiNoiseSampler();
        int radius = 8192;
        int step = 64;

        // Collect (climate point, biome) observations for non-static biomes.
        // Group by biome, then bin by the vanilla temperature×humidity grid
        // (5 temp bands × 5 humidity bands) to produce distinct entries per
        // climate cell — same granularity as vanilla/Terralith entries.
        var discovered = new java.util.HashMap<String,
            java.util.HashMap<Long, long[]>>();

        int gridPoints = 0;
        for (int x = -radius; x <= radius; x += step) {
            for (int z = -radius; z <= radius; z += step) {
                int qx = x >> 2;
                int qz = z >> 2;

                var biome = biomeSource.getBiome(qx, 0, qz, sampler);
                String biomeId = biome.getKey()
                    .map(k -> k.getValue().toString())
                    .orElse("unknown");

                if (staticBiomes.contains(biomeId)) continue;

                MultiNoiseUtil.NoiseValuePoint point = sampler.sample(qx, 0, qz);
                long temp = point.temperatureNoise();
                long humid = point.humidityNoise();
                long cont = point.continentalnessNoise();
                long eros = point.erosionNoise();
                long depth = point.depth();
                long weird = point.weirdnessNoise();

                // Bin key: quantise temp and humidity to the 5 vanilla bands
                // (-1.0, -0.45, -0.15, 0.2, 0.55, 1.0 for temp; similar
                // for humidity). This keeps each biome's disjoint climate
                // cells as separate entries rather than merging them.
                int tempBin = quantiseBand(temp, TEMP_BREAKS);
                int humidBin = quantiseBand(humid, HUMID_BREAKS);
                long binKey = ((long) tempBin << 32) | (humidBin & 0xFFFFFFFFL);

                var biomeMap = discovered.computeIfAbsent(biomeId,
                    k -> new java.util.HashMap<>());
                long[] range = biomeMap.computeIfAbsent(binKey, k -> new long[]{
                    Long.MAX_VALUE, Long.MIN_VALUE,
                    Long.MAX_VALUE, Long.MIN_VALUE,
                    Long.MAX_VALUE, Long.MIN_VALUE,
                    Long.MAX_VALUE, Long.MIN_VALUE,
                    Long.MAX_VALUE, Long.MIN_VALUE,
                    Long.MAX_VALUE, Long.MIN_VALUE
                });
                range[0] = Math.min(range[0], temp);   range[1] = Math.max(range[1], temp);
                range[2] = Math.min(range[2], humid);  range[3] = Math.max(range[3], humid);
                range[4] = Math.min(range[4], cont);   range[5] = Math.max(range[5], cont);
                range[6] = Math.min(range[6], eros);   range[7] = Math.max(range[7], eros);
                range[8] = Math.min(range[8], depth);   range[9] = Math.max(range[9], depth);
                range[10] = Math.min(range[10], weird); range[11] = Math.max(range[11], weird);
                gridPoints++;
            }
        }

        // Append discovered entries (sorted for determinism).
        int discoveredCount = 0;
        var sortedDiscovered = new java.util.ArrayList<>(discovered.keySet());
        java.util.Collections.sort(sortedDiscovered);
        for (String biomeId : sortedDiscovered) {
            var bins = discovered.get(biomeId);
            var sortedBins = new java.util.ArrayList<>(bins.keySet());
            java.util.Collections.sort(sortedBins);
            for (long binKey : sortedBins) {
                long[] r = bins.get(binKey);
                json.append(",\n  {\"biome\": \"").append(biomeId).append("\"");
                json.append(", \"temperature\": [").append(r[0] / 10000.0).append(", ").append(r[1] / 10000.0).append("]");
                json.append(", \"humidity\": [").append(r[2] / 10000.0).append(", ").append(r[3] / 10000.0).append("]");
                json.append(", \"continentalness\": [").append(r[4] / 10000.0).append(", ").append(r[5] / 10000.0).append("]");
                json.append(", \"erosion\": [").append(r[6] / 10000.0).append(", ").append(r[7] / 10000.0).append("]");
                json.append(", \"depth\": [").append(r[8] / 10000.0).append(", ").append(r[9] / 10000.0).append("]");
                json.append(", \"weirdness\": [").append(r[10] / 10000.0).append(", ").append(r[11] / 10000.0).append("]");
                json.append(", \"offset\": 0.0}");
                discoveredCount++;
            }
        }
        json.append("\n]\n");

        try {
            // No schemaVersion header here, deliberately: this artefact is a
            // bare JSON ARRAY that the seed roller loads as a list
            // (scripts/seed/biome_params.json). Wrapping it in an object to
            // carry a version would break every roller that reads it. It
            // still gets the atomic write, which is what matters — the dump
            // is large and the roller reads it straight afterwards.
            Path outputPath = Artefacts.dir().resolve("biome_params.json");
            Artefacts.write(outputPath, json.toString());
            int staticCount = staticBiomes.size();
            int newBiomes = sortedDiscovered.size();
            int newEntries = discoveredCount;
            int totalEntries = entryList.size() + discoveredCount;
            int totalGrid = gridPoints;
            source.sendFeedback(() -> Text.literal(
                "Dumped " + totalEntries + " entries (" + staticCount
                + " static biomes + " + newBiomes + " discovered via "
                + totalGrid + " grid samples, " + newEntries
                + " new entries) to biome_params.json"), false);
            return totalEntries;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write biome params", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    // Vanilla temperature band breakpoints (×10000 for long-quantised values).
    private static final long[] TEMP_BREAKS = {-4500, -1500, 2000, 5500};
    // Vanilla humidity band breakpoints.
    private static final long[] HUMID_BREAKS = {-3500, -1000, 1000, 3000};

    private static int quantiseBand(long value, long[] breaks) {
        for (int i = 0; i < breaks.length; i++) {
            if (value < breaks[i]) return i;
        }
        return breaks.length;
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
