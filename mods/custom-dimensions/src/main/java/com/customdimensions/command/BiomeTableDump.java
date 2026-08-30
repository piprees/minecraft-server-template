package com.customdimensions.command;

import com.customdimensions.dimension.DatapackDimensions;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.mixin.MultiNoiseBiomeSourceAccessor;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /customdim biome-table [dimension]} — a dimension's multi-noise
 * parameter table as JSON, biome id to the hypercubes it occupies.
 *
 * <p>The overworld's table is the reason this exists. It is CODE, not data:
 * the server jar ships
 * {@code multi_noise_biome_source_parameter_list/overworld.json} as 37 bytes
 * naming a preset, and {@code OverworldBiomeBuilder} generates the ~180
 * entries at runtime. Nothing on disk carries them, and transcribing them by
 * hand is the kind of guess no check catches.
 *
 * <p>One run on any boot produces an artefact that changes only on a Minecraft
 * version bump or a mod that injects into the table.
 */
public final class BiomeTableDump {

    private BiomeTableDump() {
    }

    static int biomeTable(CommandContext<ServerCommandSource> ctx, Identifier dimensionId) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getServer().getWorld(
                RegistryKey.of(RegistryKeys.WORLD, dimensionId));
        if (world == null) {
            source.sendError(Text.literal("Dimension not loaded: " + dimensionId));
            return 0;
        }
        MultiNoiseBiomeSource multiNoise = DimensionManager.multiNoiseOf(
                world.getChunkManager().getChunkGenerator());
        String origin = "live";
        if (multiNoise == null) {
            // A mod replaced the live source with one that is not multi-noise
            // ([T-DEF2]). The datapack entry still carries the family's own
            // table, which is the thing worth dumping.
            multiNoise = DatapackDimensions.multiNoiseFor(source.getServer(),
                    RegistryKey.of(RegistryKeys.DIMENSION, dimensionId));
            origin = "datapack";
        }
        if (multiNoise == null) {
            source.sendError(Text.literal(dimensionId + ": not a MultiNoiseBiomeSource ("
                    + world.getChunkManager().getChunkGenerator().getBiomeSource()
                            .getClass().getName() + ") and no datapack entry carries one"));
            return 0;
        }

        Map<String, List<String>> byBiome = new TreeMap<>();
        int cells = 0;
        for (Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>> pair
                : ((MultiNoiseBiomeSourceAccessor) multiNoise).invokeGetBiomeEntries().getEntries()) {
            String id = pair.getSecond().getKey()
                    .map(k -> k.getValue().toString()).orElse("(unregistered)");
            byBiome.computeIfAbsent(id, k -> new ArrayList<>()).add(cube(pair.getFirst()));
            cells++;
        }

        StringBuilder json = new StringBuilder(1 << 18);
        json.append(Artefacts.jsonHeader("biome-parameter-table"));
        json.append(" \"dimension\": \"").append(dimensionId).append("\",\n");
        json.append(" \"source\": \"").append(origin).append("\",\n");
        json.append(" \"counts\": {\"biomes\": ").append(byBiome.size())
                .append(", \"cells\": ").append(cells).append("},\n");
        json.append(" \"table\": {");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : byBiome.entrySet()) {
            json.append(first ? "\n" : ",\n").append("  \"").append(e.getKey()).append("\": [");
            for (int i = 0; i < e.getValue().size(); i++) {
                json.append(i == 0 ? "\n   " : ",\n   ").append(e.getValue().get(i));
            }
            json.append("\n  ]");
            first = false;
        }
        json.append(first ? "}\n}\n" : "\n }\n}\n");

        Path out = Artefacts.rollingDir().resolve("catalogue")
                .resolve("biome-table__" + dimensionId.toString().replace(':', '_') + ".json");
        try {
            Artefacts.write(out, json.toString());
        } catch (IOException e) {
            source.sendError(Text.literal("biome-table: write failed: " + e.getMessage()));
            return 0;
        }
        final String msg = "biome-table " + dimensionId + " (" + origin + "): " + byBiome.size()
                + " biomes over " + cells + " cell(s) -> " + out;
        source.sendFeedback(() -> Text.literal(msg), false);
        return 1;
    }

    static int biomeTable(CommandContext<ServerCommandSource> ctx) {
        return biomeTable(ctx, World.OVERWORLD.getValue());
    }

    /** One hypercube, in the float units the config schema uses, not raw longs. */
    private static String cube(MultiNoiseUtil.NoiseHypercube c) {
        return "{\"temperature\": " + range(c.temperature())
                + ", \"humidity\": " + range(c.humidity())
                + ", \"continentalness\": " + range(c.continentalness())
                + ", \"erosion\": " + range(c.erosion())
                + ", \"depth\": " + range(c.depth())
                + ", \"weirdness\": " + range(c.weirdness())
                + ", \"offset\": " + fixed(c.offset()) + "}";
    }

    private static String range(MultiNoiseUtil.ParameterRange r) {
        return "[" + fixed(r.min()) + ", " + fixed(r.max()) + "]";
    }

    /** MultiNoiseUtil stores each axis as (long) (value * 10000). */
    private static String fixed(long v) {
        return String.valueOf(v / 10000.0);
    }
}
