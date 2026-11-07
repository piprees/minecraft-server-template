package com.customdimensions.server.command;

import com.customdimensions.server.level.PackInstaller;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * {@code /customdim catalogue}: the registries as the running server holds
 * them, written to {@code <game dir>/../.seed-rolling/catalogue/registries.json},
 * where {@code scripts/extract-registries.py} copies it from. Tags come from
 * the live registries, so convention tags ({@code c:*}) resolve as they do in
 * game (T49). The file keeps the 1.21.1 keys the script reads
 * ({@code counts.biomes|biomeTags|structures|structureSets}, {@code biomeTags})
 * and adds blocks, items, features, entity types and every tag of those
 * registries with its members.
 */
final class CatalogueCommand {
    /** Self-test hook: where {@code catalogue} writes instead of the seed-rolling folder. */
    static Path outputOverride;

    private CatalogueCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("catalogue").executes(c -> catalogue(c.getSource())));
    }

    static Path output() {
        if (outputOverride != null) return outputOverride;
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize()
                .resolveSibling(".seed-rolling").resolve("catalogue").resolve("registries.json");
    }

    static int catalogue(CommandSourceStack source) {
        JsonObject catalogue = build(source.getServer());
        Path out = output();
        try {
            Files.createDirectories(out.getParent());
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(catalogue) + "\n");
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CustomDimCommands.fail(source, "customdim.write_failed", "path", out.toString(), "reason", CustomDimCommands.reason(e));
            return 0;
        }
        JsonObject counts = catalogue.getAsJsonObject("counts");
        CustomDimCommands.ok(source, "customdim.catalogue.done", "path", out.toString(),
                "blocks", counts.get("blocks").getAsString(), "items", counts.get("items").getAsString(),
                "biomes", counts.get("biomes").getAsString(), "structures", counts.get("structures").getAsString(),
                "features", counts.get("features").getAsString(), "entities", counts.get("entityTypes").getAsString(),
                "tags", counts.get("tags").getAsString());
        return 1;
    }

    static JsonObject build(MinecraftServer server) {
        RegistryAccess access = server.registryAccess();
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Registry<net.minecraft.world.level.biome.Biome> biomes = access.lookupOrThrow(Registries.BIOME);
        Registry<Structure> structures = access.lookupOrThrow(Registries.STRUCTURE);
        Registry<StructureSet> sets = access.lookupOrThrow(Registries.STRUCTURE_SET);

        JsonObject root = new JsonObject();
        root.addProperty("stackVersion", PackInstaller.modVersion());
        root.addProperty("kind", "registry-catalogue");
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("minecraft", PackInstaller.minecraftVersion());

        JsonObject biomeTags = tags(biomes);
        JsonObject structureEntries = new JsonObject();
        for (var key : new TreeSet<>(structures.keySet())) {
            Structure structure = structures.getValue(key);
            if (structure == null) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("step", structure.step().getSerializedName());
            entry.addProperty("terrainAdaptation", structure.terrainAdaptation().getSerializedName());
            HolderSet<net.minecraft.world.level.biome.Biome> valid = structure.biomes();
            entry.addProperty("biomeCount", valid.size());
            var tag = valid.unwrapKey();
            if (tag.isPresent()) {
                String tagId = tag.get().location().toString();
                entry.addProperty("biomeTag", tagId);
                if (!biomeTags.has(tagId)) biomeTags.add(tagId, members(valid.stream().toList()));
            } else {
                entry.add("biomes", members(valid.stream().toList()));
            }
            structureEntries.add(key.toString(), entry);
        }
        JsonObject setEntries = new JsonObject();
        for (var key : new TreeSet<>(sets.keySet())) {
            StructureSet set = sets.getValue(key);
            if (set == null) continue;
            JsonObject entry = new JsonObject();
            entry.add("placement", StructurePlacement.CODEC.encodeStart(ops, set.placement()).getOrThrow());
            JsonArray members = new JsonArray();
            for (StructureSet.StructureSelectionEntry e : set.structures()) {
                e.structure().unwrapKey().ifPresent(k -> {
                    JsonObject m = new JsonObject();
                    m.addProperty("structure", k.identifier().toString());
                    m.addProperty("weight", e.weight());
                    members.add(m);
                });
            }
            entry.add("structures", members);
            setEntries.add(key.toString(), entry);
        }

        Map<String, Registry<?>> tagged = new TreeMap<>();
        for (ResourceKey<? extends Registry<?>> key : List.of(Registries.BLOCK, Registries.ITEM, Registries.ENTITY_TYPE,
                Registries.BIOME, Registries.STRUCTURE, Registries.FEATURE, Registries.PLACED_FEATURE)) {
            tagged.put(key.identifier().toString(), access.lookupOrThrow(key));
        }
        JsonObject tags = new JsonObject();
        int tagCount = 0;
        for (Map.Entry<String, Registry<?>> e : tagged.entrySet()) {
            JsonObject t = tags(e.getValue());
            tagCount += t.size();
            tags.add(e.getKey(), t);
        }

        JsonObject counts = new JsonObject();
        counts.addProperty("biomes", biomes.size());
        counts.addProperty("biomeTags", biomeTags.size());
        counts.addProperty("structures", structures.size());
        counts.addProperty("structureSets", sets.size());
        counts.addProperty("blocks", access.lookupOrThrow(Registries.BLOCK).size());
        counts.addProperty("items", access.lookupOrThrow(Registries.ITEM).size());
        counts.addProperty("features", access.lookupOrThrow(Registries.FEATURE).size());
        counts.addProperty("placedFeatures", access.lookupOrThrow(Registries.PLACED_FEATURE).size());
        counts.addProperty("entityTypes", access.lookupOrThrow(Registries.ENTITY_TYPE).size());
        counts.addProperty("tags", tagCount);
        root.add("counts", counts);
        root.add("biomes", ids(biomes.keySet()));
        root.add("biomeTags", biomeTags);
        root.add("structures", structureEntries);
        root.add("structureSets", setEntries);
        root.add("blocks", ids(access.lookupOrThrow(Registries.BLOCK).keySet()));
        root.add("items", ids(access.lookupOrThrow(Registries.ITEM).keySet()));
        root.add("features", ids(access.lookupOrThrow(Registries.FEATURE).keySet()));
        root.add("placedFeatures", ids(access.lookupOrThrow(Registries.PLACED_FEATURE).keySet()));
        root.add("entityTypes", ids(access.lookupOrThrow(Registries.ENTITY_TYPE).keySet()));
        root.add("tags", tags);
        return root;
    }

    /** Every tag of {@code registry}, sorted, with its members' ids sorted. */
    private static <T> JsonObject tags(Registry<T> registry) {
        Map<String, JsonArray> sorted = new TreeMap<>();
        registry.getTags().forEach(named -> sorted.put(named.key().location().toString(), members(named.stream().toList())));
        JsonObject out = new JsonObject();
        sorted.forEach(out::add);
        return out;
    }

    private static <T> JsonArray members(List<Holder<T>> holders) {
        TreeSet<String> ids = new TreeSet<>();
        for (Holder<T> h : holders) h.unwrapKey().map(ResourceKey::identifier).ifPresent(id -> ids.add(id.toString()));
        return ids(ids);
    }

    private static JsonArray ids(Collection<?> ids) {
        JsonArray out = new JsonArray();
        new TreeSet<>(ids.stream().map(Object::toString).toList()).forEach(out::add);
        return out;
    }
}
