package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.dimension.NoiseStructurePlacement;
import com.customdimensions.dimension.StructureGroupRegistry;
import com.customdimensions.dimension.StructurePickHelper;
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
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Operator commands for runtime dimension lifecycle — the seed roller's
 * workhorse (SEED_ROLL_MODE boots skip creation; the roller then drives
 * create/measure/destroy over RCON):
 *
 *   /customdim create <name> <type> <seed> [noiseSettings] [structureDensity] [biome]
 *   /customdim destroy <name>
 *   /customdim list
 *   /customdim bank-gc [delete]
 *   /customdim locate biome <dimension> <biome_id> [timeout]
 *   /customdim locate structure <dimension> <structure_id> [timeout]
 *   /customdim locate-result <uuid>
 *   /customdim structure-audit [group]
 *   /customdim structure-census <dimension>
 *   /customdim occupant <dimension> <chunkX> <chunkZ>
 *   /customdim eval-df <dimension> <df_id> <x> <y> <z>
 *   /customdim carver-draw <dimension> <chunkX> <chunkZ>
 *   /customdim render-check <dimension> <seed> [radius]
 *   /customdim render-check-headless <dimension> <seed> [radius]
 *   /customdim render-check-reset
 *
 * Rolling, banking, rendering and picking a winner are NOT commands — they
 * live on the page the mod hosts (web/SeedServer, `./dev seeds`), which talks
 * to the process that owns the registries and the live server with no RCON
 * hop in between. This list named them for long enough that somebody will
 * have gone looking.
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
                .then(CommandManager.literal("tryout-list")
                    .executes(DimensionCommands::tryOutList))
                .then(CommandManager.literal("list")
                    .executes(DimensionCommands::list))
                .then(CommandManager.literal("bank-gc")
                    .executes(ctx -> bankGc(ctx, false))
                    .then(CommandManager.literal("delete")
                        .executes(ctx -> bankGc(ctx, true))))
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
                .then(CommandManager.literal("debug-prng")
                    .then(CommandManager.argument("seed", LongArgumentType.longArg())
                        .executes(DimensionCommands::debugPrng)))
                .then(CommandManager.literal("sample-noise")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("x", IntegerArgumentType.integer())
                            .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                .executes(DimensionCommands::sampleNoise)))))
                .then(CommandManager.literal("structure-audit")
                    .executes(ctx -> structureAudit(ctx, null))
                    .then(CommandManager.argument("group", StringArgumentType.word())
                        .executes(ctx -> structureAudit(ctx,
                            StringArgumentType.getString(ctx, "group")))))
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
                .then(CommandManager.literal("lint")
                    .executes(ctx -> SpikeCommands.lint(ctx, null))
                    .then(CommandManager.argument("dimension", StringArgumentType.word())
                        .executes(ctx -> SpikeCommands.lint(ctx,
                            StringArgumentType.getString(ctx, "dimension")))))
                .then(CommandManager.literal("gensettings")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .executes(DimensionCommands::gensettings)))
                .then(CommandManager.literal("score")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("seed", LongArgumentType.longArg())
                            .executes(ScoreCommands::score))))
                .then(CommandManager.literal("facts")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("seed", LongArgumentType.longArg())
                            .executes(FactsCommands::facts))))
                .then(CommandManager.literal("structure-census")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .executes(CensusCommands::structureCensus)))
                .then(CommandManager.literal("spike-compare")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("seed", LongArgumentType.longArg())
                            .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 4096))
                                .then(CommandManager.argument("span", IntegerArgumentType.integer(16, 1000000))
                                    .executes(SpikeCommands::compare))))))
                .then(CommandManager.literal("render-check")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("seed", LongArgumentType.longArg())
                            .executes(ctx -> renderCheck(ctx, RenderCheck.Mode.FULL, null))
                            .then(CommandManager.argument("radius", IntegerArgumentType.integer(16, 1000000))
                                .executes(ctx -> renderCheck(ctx, RenderCheck.Mode.FULL,
                                    IntegerArgumentType.getInteger(ctx, "radius")))))))
                .then(CommandManager.literal("render-check-headless")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("seed", LongArgumentType.longArg())
                            .executes(ctx -> renderCheck(ctx, RenderCheck.Mode.HEADLESS, null))
                            .then(CommandManager.argument("radius", IntegerArgumentType.integer(16, 1000000))
                                .executes(ctx -> renderCheck(ctx, RenderCheck.Mode.HEADLESS,
                                    IntegerArgumentType.getInteger(ctx, "radius")))))))
                .then(CommandManager.literal("column-ladder")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("seed", LongArgumentType.longArg())
                            .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                    .executes(DimensionCommands::columnLadder))))))
                .then(CommandManager.literal("render-check-reset")
                    .executes(ctx -> {
                        int n = RenderCheck.reset();
                        ctx.getSource().sendFeedback(
                            () -> Text.literal("render-check: dropped " + n + " job(s)"), false);
                        return 1;
                    }))
                .then(CommandManager.literal("eval-df")
                    .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("df_id", IdentifierArgumentType.identifier())
                            .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(DimensionCommands::evalDf)))))))
        );
    }

    /**
     * Starts (or polls) a three-way world/facts/render comparison.
     *
     * <p>The world side generates chunks over several seconds to minutes, so
     * this never blocks: it answers the job's current line and the caller
     * runs it again to poll. The finished line carries the three water
     * fractions, the disagreement count and the buckets, plus the artefact
     * path — everything a person needs before opening the file.
     */
    /**
     * Prints one column's block ladder beside its density ladder.
     *
     * <p>Headless and single-column, so it answers inline rather than polling:
     * the summary names the two walks' answers and the first y they disagree
     * about, which is the diagnosis when there is one.
     */
    private static int columnLadder(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        Identifier dimension = IdentifierArgumentType.getIdentifier(ctx, "dimension");
        long seed = LongArgumentType.getLong(ctx, "seed");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        try {
            String line = ColumnLadder.probe(source.getServer(), dimension, seed, x, z);
            source.sendFeedback(() -> Text.literal(line), false);
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("column-ladder failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()));
            return 0;
        }
    }

    private static int renderCheck(CommandContext<ServerCommandSource> ctx,
                                   RenderCheck.Mode mode, Integer radius) {
        ServerCommandSource source = ctx.getSource();
        Identifier dimension = IdentifierArgumentType.getIdentifier(ctx, "dimension");
        long seed = LongArgumentType.getLong(ctx, "seed");
        RenderCheck.Job job = RenderCheck.start(source.getServer(), dimension, seed, mode, radius);
        source.sendFeedback(() -> Text.literal(job.line()), false);
        return job.state() == RenderCheck.State.FAILED ? 0 : 1;
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
    /**
     * What a LIVE world's generator is actually made of.
     *
     * <p>Every static layer can be correct and the world still be wrong: the
     * settings a dimension ends up with are assembled at runtime from the
     * registry, the mod's own overrides and whatever datapacks won. This
     * reports the assembled answer — the only one the blocks come from.
     *
     * <p>Inline rather than a file: three values and a rule name, which RCON
     * carries without truncating.
     */
    private static int gensettings(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal("Dimension not loaded: "
                    + IdentifierArgumentType.getIdentifier(ctx, "dimension")));
            return 0;
        }
        net.minecraft.world.gen.chunk.ChunkGenerator gen = world.getChunkManager().getChunkGenerator();
        if (!(gen instanceof net.minecraft.world.gen.chunk.NoiseChunkGenerator noiseGen)) {
            source.sendFeedback(() -> Text.literal(world.getRegistryKey().getValue()
                    + ": not a noise generator (" + gen.getClass().getSimpleName() + ")"), false);
            return 1;
        }
        var entry = noiseGen.getSettings();
        var settings = entry.value();
        String settingsId = entry.getKey().map(k -> k.getValue().toString()).orElse("(inline)");
        String defaultBlock = net.minecraft.registry.Registries.BLOCK
                .getId(settings.defaultBlock().getBlock()).toString();
        String defaultFluid = net.minecraft.registry.Registries.BLOCK
                .getId(settings.defaultFluid().getBlock()).toString();
        String line = world.getRegistryKey().getValue()
                + ": settings=" + settingsId
                + " defaultBlock=" + defaultBlock
                + " defaultFluid=" + defaultFluid
                + " seaLevel=" + settings.seaLevel()
                + " y=" + world.getBottomY() + ".." + (world.getTopY() - 1);
        MultiverseServer.LOGGER.info("gensettings {}", line);
        source.sendFeedback(() -> Text.literal(line), false);
        return 1;
    }

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

        // Append to <world>/customdimensions/census/occupancy__{dimension}.json
        // (append pattern matching rejections — occupancy is a recorded fact
        // about a generated chunk, world state rather than a seed-rolling
        // hypothesis, so it is keyed by dimension alone, not by config hash).
        try {
            String dimPart = dimensionId.toString().replace(":", "__");
            Path artefactPath = Artefacts.censusDir(source.getServer())
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

        // Rows go to a file, not command output — RCON truncates and
        // concatenates response lines, so ~280 rows would come back unreadable.
        // Registry-wide (every structure set, not one dimension), so the hash
        // covers only the stack version and mod list.
        try {
            String hash = InputHash.of(null, source.getServer());
            Path outputPath = Artefacts.rollingDir().resolve("lint")
                    .resolve(hash + ".structure-audit.json");
            StringBuilder body = new StringBuilder(Artefacts.jsonHeader("structure-audit"));
            body.append(" \"summary\": ")
                    .append(com.customdimensions.facts.Json.quote(summary.toString())).append(",\n");
            body.append(" \"sets\": [");
            for (int i = 0; i < lines.size(); i++) {
                body.append(i > 0 ? ",\n  " : "\n  ")
                        .append(com.customdimensions.facts.Json.quote(lines.get(i)));
            }
            body.append(lines.isEmpty() ? "]\n}\n" : "\n ]\n}\n");
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

    /**
     * Which try-out worlds are live. A diagnostic, not a way in: starting,
     * entering, leaving and ending a try-out all happen in the browser,
     * through the port the mod hosts.
     */
    private static int tryOutList(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        var sessions = com.customdimensions.tryout.TryOut.sessions();
        StringBuilder b = new StringBuilder("tryout-list: " + sessions.size() + " live");
        for (var s : sessions) {
            b.append(" | ").append(s.dimension()).append(" seed=").append(s.seed())
                    .append(" -> ").append(s.worldId());
        }
        final String msg = b.toString();
        source.sendFeedback(() -> Text.literal(msg), false);
        return sessions.size();
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
     * with "Unknown dimension" until somebody visits — correct at runtime, but
     * CI's smoke test needs a live world to assert per-dimension seeds, noise
     * settings and structure density.
     *
     * <p>Queues via {@code requestWorldLoad} (drained on END_SERVER_TICK)
     * rather than calling {@code getOrCreateDimension} directly — world
     * creation from command context deadlocks the main thread (mods/AGENTS.md,
     * dynamic world lifecycle rule). Returns immediately; the world appears a
     * tick or two later, so callers must poll.
     */
    private static int load(CommandContext<ServerCommandSource> ctx, String name) {
        ServerCommandSource source = ctx.getSource();
        // Reserved dimensions load through here too: CreateWorldsMixin defers every
        // non-overworld world, so "the_nether" is as absent at boot as any
        // custom dimension and needs the same way in.
        if (MultiverseConfig.getInstance().getCustomDimension(name) == null
                && MultiverseConfig.getInstance().getReservedDimensionBySlug(name) == null) {
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

    /**
     * Removes candidate banks no configured dimension can reach.
     *
     * <p>The bank is keyed by {@link InputHash}, which covers the whole
     * dimension config bar the seed plus the mod's measurement-relevant bytes.
     * Every edit to either therefore strands the previous key's renders, and
     * nothing has ever removed one — {@code sweep} prunes seeds WITHIN a
     * dimension dir, never across hashes. A pack that has been rolled a few
     * times carries hundreds of unreachable banks.
     *
     * <p>Reports by default and deletes only on {@code bank-gc delete}. Only
     * the mod can do this: the live hash set has to be computed, and any
     * shell-side heuristic (mtime, say) is guessing at which banks are dead.
     */
    private static int bankGc(CommandContext<ServerCommandSource> ctx, boolean delete) {
        ServerCommandSource source = ctx.getSource();
        Path root = Artefacts.rollingDir().resolve("candidates");
        if (!Files.isDirectory(root)) {
            source.sendFeedback(() -> Text.literal("No candidate bank on disk"), false);
            return 0;
        }
        Set<String> live = new HashSet<>();
        for (DimensionConfig def : MultiverseConfig.getInstance().getAllDimensions()) {
            live.add(InputHash.of(def, source.getServer()));
        }
        long bytes = 0;
        int stale = 0;
        int failed = 0;
        try (var entries = Files.list(root)) {
            for (Path dir : entries.filter(Files::isDirectory).toList()) {
                if (live.contains(dir.getFileName().toString())) {
                    continue;
                }
                stale++;
                bytes += sizeOf(dir);
                if (delete && !deleteTree(dir)) {
                    failed++;
                }
            }
        } catch (IOException e) {
            source.sendError(Text.literal("Could not read the bank: " + e));
            return 0;
        }
        int megabytes = (int) (bytes / (1024 * 1024));
        String verb = delete ? "Deleted" : "Would delete";
        int finalStale = stale;
        int finalFailed = failed;
        source.sendFeedback(() -> Text.literal(verb + " " + finalStale + " unreachable bank(s), "
                + megabytes + " MB; " + live.size() + " live"
                + (finalFailed > 0 ? " (" + finalFailed + " could not be removed)" : "")
                + (delete ? "" : " — run 'customdim bank-gc delete' to remove them")), false);
        return stale;
    }

    private static long sizeOf(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Depth-first so children go before their parent. */
    private static boolean deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (IOException e) {
            MultiverseServer.LOGGER.warn("Could not remove stale bank {}: {}", dir, e.toString());
            return false;
        }
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
     * {@code minecraft} namespace, while {@code customdim load} takes bare
     * names under the configured namespace — so the configured namespace is
     * tried as a fallback here too. A real {@code minecraft:} world still
     * wins, since it is tried first.
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

        // Tectonic continentalness: manual path + octave origin dump
        {
            var tecRng = splitter.split("tectonic:parameter/continentalness");
            double[] tecAmps = {1.75, 1, 2, 3, 2, 2, 1, 1, 1};
            var tecParams = new net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters(
                -10, new it.unimi.dsi.fastutil.doubles.DoubleArrayList(tecAmps));
            var tecNoise = net.minecraft.util.math.noise.DoublePerlinNoiseSampler.create(tecRng, tecParams);
            double v0 = tecNoise.sample(0, 0, 0);
            double vShift = tecNoise.sample(0.527180713, 0, 0.527180713);
            source.sendFeedback(() -> Text.literal(String.format(
                "noise tectonic:parameter/continentalness s(0)=%.10f s(0.527)=%.10f", v0, vShift)), false);
            String manualOrigins = dumpOctaveOrigins(tecNoise, "manual");
            source.sendFeedback(() -> Text.literal(manualOrigins), false);
        }

        // Same noise via NoiseConfig.getOrCreateSampler (the live server path)
        {
            var server = ctx.getSource().getServer();
            var noiseParamsLookup = server.getRegistryManager()
                    .get(RegistryKeys.NOISE_PARAMETERS).getReadOnlyWrapper();
            var settingsLookup = server.getRegistryManager()
                    .get(net.minecraft.registry.RegistryKeys.CHUNK_GENERATOR_SETTINGS)
                    .getReadOnlyWrapper();
            // Build a NoiseConfig from adventure:compressed settings
            var settingsKey = RegistryKey.of(
                    net.minecraft.registry.RegistryKeys.CHUNK_GENERATOR_SETTINGS,
                    Identifier.tryParse("adventure:compressed"));
            try {
                var settings = settingsLookup.getOrThrow(settingsKey).value();
                var configNoise = NoiseConfig.create(settings, noiseParamsLookup, seed);
                var tecKey = RegistryKey.of(RegistryKeys.NOISE_PARAMETERS,
                        Identifier.tryParse("tectonic:parameter/continentalness"));
                // Probe: what key does the registry entry report?
                var noiseReg = server.getRegistryManager().get(RegistryKeys.NOISE_PARAMETERS);
                var regEntry = noiseReg.getEntry(tecKey);
                String regKeyStr = regEntry.map(e -> e.getKey()
                        .map(k -> k.getValue().toString()).orElse("NO_KEY"))
                        .orElse("NOT_FOUND");
                source.sendFeedback(() -> Text.literal("registry_key=" + regKeyStr), false);
                var configSampler = configNoise.getOrCreateSampler(tecKey);
                double cv = configSampler.sample(0.527180713, 0, 0.527180713);
                source.sendFeedback(() -> Text.literal(String.format(
                    "config tectonic:parameter/continentalness s(0.527)=%.10f", cv)), false);
                String configOrigins = dumpOctaveOrigins(configSampler, "config");
                source.sendFeedback(() -> Text.literal(configOrigins), false);
            } catch (Exception e) {
                source.sendFeedback(() -> Text.literal("config path error: " + e.getMessage()), false);
            }
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

    /**
     * Evaluates an arbitrary density function from the DENSITY_FUNCTION
     * registry at block coordinates (x, y, z). Binds the DF through a
     * fresh NoiseConfig built from the dimension's generator settings and
     * world seed — the same binding the noise pipeline uses.
     */
    private static int evalDf(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = resolveWorld(ctx);
        if (world == null) {
            source.sendError(Text.literal("Dimension not loaded: "
                    + IdentifierArgumentType.getIdentifier(ctx, "dimension")));
            return 0;
        }
        Identifier dfId = IdentifierArgumentType.getIdentifier(ctx, "df_id");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");

        var result = DensityFunctionEvaluator.evaluate(world, dfId, x, y, z);
        if (!result.ok()) {
            source.sendError(Text.literal("eval-df: " + result.errorMsg()));
            return 0;
        }

        String msg = String.format("df %s at (%d,%d,%d) = %.9f [%s]",
                dfId, x, y, z, result.value(), result.binding());
        source.sendFeedback(() -> Text.literal(msg), false);
        return 1;
    }

    /**
     * Extracts per-octave Perlin origins from a DoublePerlinNoiseSampler via
     * reflection. Returns a compact string for RCON comparison against the
     * Python side's octave origins.
     */
    private static String dumpOctaveOrigins(
            net.minecraft.util.math.noise.DoublePerlinNoiseSampler sampler,
            String label) {
        try {
            // DoublePerlinNoiseSampler has private firstSampler/secondSampler
            java.lang.reflect.Field firstF = null, secondF = null;
            for (var f : net.minecraft.util.math.noise.DoublePerlinNoiseSampler.class.getDeclaredFields()) {
                if (net.minecraft.util.math.noise.OctavePerlinNoiseSampler.class.isAssignableFrom(f.getType())) {
                    if (firstF == null) firstF = f;
                    else { secondF = f; break; }
                }
            }
            if (firstF == null) return label + ":noFields";
            firstF.setAccessible(true);
            var first = (net.minecraft.util.math.noise.OctavePerlinNoiseSampler) firstF.get(sampler);
            // OctavePerlinNoiseSampler has private octaveSamplers (PerlinNoiseSampler[])
            java.lang.reflect.Field octF = null;
            for (var f : net.minecraft.util.math.noise.OctavePerlinNoiseSampler.class.getDeclaredFields()) {
                if (f.getType().isArray() && !f.getType().getComponentType().isPrimitive()
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    octF = f; break;
                }
            }
            if (octF == null) return label + ":noOctaveField";
            octF.setAccessible(true);
            var octaves = (net.minecraft.util.math.noise.PerlinNoiseSampler[]) octF.get(first);
            StringBuilder sb = new StringBuilder(label + "_first:");
            for (int i = 0; i < octaves.length; i++) {
                if (octaves[i] != null) {
                    sb.append(String.format(" [%.6f,%.6f,%.6f]",
                            octaves[i].originX, octaves[i].originY, octaves[i].originZ));
                } else {
                    sb.append(" [null]");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return label + ":error=" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

}
