package com.customdimensions.command;

import com.customdimensions.mixin.StructurePlacementAccessor;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.structure.StructureSet;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.chunk.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.structure.Structure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code /customdim catalogue} — the biome, biome-tag, structure and
 * structure-set registries as the RUNNING server holds them, written to one
 * JSON file.
 *
 * <p>A jar scan cannot answer a tag question. Convention tags ({@code c:*})
 * are populated by the Fabric Convention Tags API at runtime and exist in no
 * mod's {@code data/} directory, so any membership derived from files is
 * wrong wherever a structure gates on one — which is most of the pack. The
 * static extractors under {@code scripts/} keep their per-biome and
 * per-set detail; this is the authority for anything tag-dependent.
 *
 * <p>A structure's valid biomes are emitted as the tag they came from, not
 * as a copied member list: the resolved membership lives once, under
 * {@code biomeTags}. A tag a structure names but the registry does not
 * stream is added there from the structure's own list, so every
 * {@code biomeTag} in the file resolves within the file.
 */
public final class CatalogueCommands {

    private CatalogueCommands() {
    }

    /** One structure's biome predicate: a tag name, or an explicit id list. */
    private record BiomeRef(String tag, List<String> ids, int size) {
    }

    static int catalogue(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();

        Registry<Biome> biomes = server.getRegistryManager().get(RegistryKeys.BIOME);
        Registry<Structure> structures = server.getRegistryManager().get(RegistryKeys.STRUCTURE);
        Registry<StructureSet> sets = server.getRegistryManager().get(RegistryKeys.STRUCTURE_SET);

        Map<String, List<String>> biomeTags = new TreeMap<>();
        biomes.streamTagsAndEntries().forEach(pair ->
                biomeTags.put(pair.getFirst().id().toString(), idsOf(pair.getSecond())));

        Map<String, BiomeRef> structureBiomes = new TreeMap<>();
        Map<String, String> steps = new TreeMap<>();
        Map<String, String> adaptations = new TreeMap<>();
        for (Identifier id : structures.getIds()) {
            Structure structure = structures.get(id);
            if (structure == null) {
                continue;
            }
            String key = id.toString();
            steps.put(key, structure.getFeatureGenerationStep().getName());
            adaptations.put(key, lower(structure.getTerrainAdaptation().name()));
            RegistryEntryList<Biome> valid;
            try {
                valid = structure.getValidBiomes();
            } catch (RuntimeException e) {
                // A structure whose predicate cannot be read is reported as
                // unknown rather than as empty, which reads as "generates
                // nowhere" and is a different claim.
                structureBiomes.put(key, new BiomeRef(null, null, -1));
                continue;
            }
            Optional<TagKey<Biome>> tag = valid.getTagKey();
            if (tag.isPresent()) {
                String tagId = tag.get().id().toString();
                biomeTags.computeIfAbsent(tagId, t -> idsOf(valid));
                structureBiomes.put(key, new BiomeRef(tagId, null, valid.size()));
            } else {
                List<String> ids = idsOf(valid);
                structureBiomes.put(key, new BiomeRef(null, ids, ids.size()));
            }
        }

        StringBuilder json = new StringBuilder(1 << 20);
        json.append(Artefacts.jsonHeader("registry-catalogue"));
        json.append(" \"counts\": {\"biomes\": ").append(biomes.size())
                .append(", \"biomeTags\": ").append(biomeTags.size())
                .append(", \"structures\": ").append(structures.size())
                .append(", \"structureSets\": ").append(sets.size()).append("},\n");

        json.append(" \"biomes\": [");
        List<String> biomeIds = new ArrayList<>();
        for (Identifier id : biomes.getIds()) {
            biomeIds.add(id.toString());
        }
        biomeIds.sort(String::compareTo);
        appendStrings(json, biomeIds);
        json.append("],\n");

        json.append(" \"biomeTags\": {");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : biomeTags.entrySet()) {
            json.append(first ? "\n  " : ",\n  ");
            first = false;
            json.append('"').append(e.getKey()).append("\": [");
            appendStrings(json, e.getValue());
            json.append(']');
        }
        json.append(first ? "},\n" : "\n },\n");

        json.append(" \"structures\": {");
        first = true;
        for (Map.Entry<String, BiomeRef> e : structureBiomes.entrySet()) {
            json.append(first ? "\n  " : ",\n  ");
            first = false;
            BiomeRef ref = e.getValue();
            json.append('"').append(e.getKey()).append("\": {\"step\": \"")
                    .append(steps.get(e.getKey())).append("\", \"terrainAdaptation\": \"")
                    .append(adaptations.get(e.getKey())).append("\", \"biomeCount\": ")
                    .append(ref.size());
            if (ref.tag() != null) {
                json.append(", \"biomeTag\": \"").append(ref.tag()).append('"');
            } else if (ref.ids() != null) {
                json.append(", \"biomes\": [");
                appendStrings(json, ref.ids());
                json.append(']');
            }
            json.append('}');
        }
        json.append(first ? "},\n" : "\n },\n");

        json.append(" \"structureSets\": {");
        first = true;
        for (Identifier id : sortedIds(sets)) {
            StructureSet set = sets.get(id);
            if (set == null) {
                continue;
            }
            json.append(first ? "\n  " : ",\n  ");
            first = false;
            json.append('"').append(id).append("\": {");
            appendPlacement(json, set.placement());
            json.append(", \"structures\": [");
            boolean firstMember = true;
            for (StructureSet.WeightedEntry weighted : set.structures()) {
                String member = weighted.structure().getKey()
                        .map(k -> k.getValue().toString()).orElse(null);
                if (member == null) {
                    continue;
                }
                json.append(firstMember ? "" : ", ");
                firstMember = false;
                json.append("{\"structure\": \"").append(member)
                        .append("\", \"weight\": ").append(weighted.weight()).append('}');
            }
            json.append("]}");
        }
        json.append(first ? "}\n}\n" : "\n }\n}\n");

        Path out = Artefacts.rollingDir().resolve("catalogue").resolve("registries.json");
        try {
            Artefacts.write(out, json.toString());
        } catch (IOException e) {
            source.sendError(Text.literal("catalogue: write failed: " + e.getMessage()));
            return 0;
        }

        int overworldTagged = biomeTags.getOrDefault("c:is_overworld", List.of()).size();
        final String msg = "catalogue: " + biomes.size() + " biomes, " + biomeTags.size()
                + " biome tags (#c:is_overworld resolves to " + overworldTagged + "), "
                + structures.size() + " structures, " + sets.size()
                + " structure sets -> " + out;
        source.sendFeedback(() -> Text.literal(msg), false);
        return biomeTags.size();
    }

    /** Placement fields common to every type, plus the type-specific grid. */
    private static void appendPlacement(StringBuilder json, StructurePlacement placement) {
        Identifier type = Registries.STRUCTURE_PLACEMENT.getId(placement.getType());
        StructurePlacementAccessor fields = (StructurePlacementAccessor) placement;
        json.append("\"placementType\": \"").append(type == null ? "unknown" : type)
                .append("\", \"frequency\": ").append(fields.getFrequencyField())
                .append(", \"salt\": ").append(fields.getSaltField())
                .append(", \"frequencyReduction\": \"")
                .append(lower(fields.getFrequencyReductionMethodField().name())).append('"');
        if (placement instanceof RandomSpreadStructurePlacement random) {
            json.append(", \"spacing\": ").append(random.getSpacing())
                    .append(", \"separation\": ").append(random.getSeparation())
                    .append(", \"spreadType\": \"").append(lower(random.getSpreadType().name()))
                    .append('"');
        } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
            json.append(", \"distance\": ").append(rings.getDistance())
                    .append(", \"spread\": ").append(rings.getSpread())
                    .append(", \"count\": ").append(rings.getCount());
        }
    }

    /** Enum constant to its datapack spelling. */
    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static List<String> idsOf(RegistryEntryList<Biome> list) {
        List<String> ids = new ArrayList<>();
        for (RegistryEntry<Biome> entry : list) {
            entry.getKey().ifPresent(k -> ids.add(k.getValue().toString()));
        }
        ids.sort(String::compareTo);
        return ids;
    }

    private static List<Identifier> sortedIds(Registry<?> registry) {
        List<Identifier> ids = new ArrayList<>(registry.getIds());
        ids.sort((a, b) -> a.toString().compareTo(b.toString()));
        return ids;
    }

    private static void appendStrings(StringBuilder json, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append('"').append(values.get(i)).append('"');
        }
    }
}
