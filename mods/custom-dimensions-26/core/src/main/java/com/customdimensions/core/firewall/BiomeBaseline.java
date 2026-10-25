package com.customdimensions.core.firewall;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A vanilla biome's own placed features per decoration step and spawned
 * entity types per category, read from vanilla's biome file, and what a
 * loaded biome holds beyond them.
 */
public record BiomeBaseline(List<Set<String>> features, Map<String, Set<String>> spawns) {
    static final String SPAWNS_ATTRIBUTE = "minecraft:gameplay/natural_mob_spawns";

    /** A placed feature in {@code step} that neither vanilla nor a reviewed bridge put there. */
    public record Addition(int step, String id) {}

    public static BiomeBaseline parse(String biomeJson) {
        JsonObject biome = JsonParser.parseString(biomeJson).getAsJsonObject();
        List<Set<String>> features = new ArrayList<>();
        if (biome.has("features")) {
            for (JsonElement step : biome.getAsJsonArray("features")) {
                Set<String> ids = new TreeSet<>();
                if (step.isJsonArray()) step.getAsJsonArray().forEach(e -> { if (e.isJsonPrimitive()) ids.add(e.getAsString()); });
                features.add(ids);
            }
        }
        Map<String, Set<String>> spawns = new TreeMap<>();
        JsonObject byCategory = spawnsByCategory(biome);
        if (byCategory != null) {
            for (Map.Entry<String, JsonElement> category : byCategory.entrySet()) {
                Set<String> types = new TreeSet<>();
                if (category.getValue().isJsonArray()) {
                    for (JsonElement entry : category.getValue().getAsJsonArray()) {
                        if (entry.isJsonObject() && entry.getAsJsonObject().has("type")) types.add(entry.getAsJsonObject().get("type").getAsString());
                    }
                }
                spawns.put(category.getKey(), types);
            }
        }
        return new BiomeBaseline(List.copyOf(features), Map.copyOf(spawns));
    }

    /** The {@code spawns_by_category} object: under the 26.3 attribute's {@code argument}, else the attribute itself, else the older {@code spawners}. */
    private static JsonObject spawnsByCategory(JsonObject biome) {
        if (biome.has("attributes") && biome.getAsJsonObject("attributes").has(SPAWNS_ATTRIBUTE)) {
            JsonObject attribute = biome.getAsJsonObject("attributes").getAsJsonObject(SPAWNS_ATTRIBUTE);
            JsonObject value = attribute.has("argument") ? attribute.getAsJsonObject("argument") : attribute;
            return value.has("spawns_by_category") ? value.getAsJsonObject("spawns_by_category") : null;
        }
        return biome.has("spawners") ? biome.getAsJsonObject("spawners") : null;
    }

    /** Features in {@code current} (ids per step) that are not vanilla's in that step and not in {@code adopted}. */
    public List<Addition> featureAdditions(List<List<String>> current, Set<String> adopted) {
        List<Addition> out = new ArrayList<>();
        for (int step = 0; step < current.size(); step++) {
            Set<String> own = step < features.size() ? features.get(step) : Set.of();
            for (String id : current.get(step)) {
                if (!own.contains(id) && !adopted.contains(id)) out.add(new Addition(step, id));
            }
        }
        return out;
    }

    /** Whether {@code type} spawning in {@code category} was added: not vanilla's there and not in {@code adopted}. */
    public boolean spawnAdded(String category, String type, Set<String> adopted) {
        return !spawns.getOrDefault(category, Set.of()).contains(type) && !adopted.contains(type);
    }
}
