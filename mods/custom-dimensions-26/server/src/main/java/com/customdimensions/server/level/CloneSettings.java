package com.customdimensions.server.level;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A clone's encoded noise settings against its source's. A clone's own zones
 * may bring biomes, so the clone's router {@code temperature} carries their
 * channels and its material rule carries the library palette of each zone
 * biome; everything else equals the source.
 */
final class CloneSettings {

    private CloneSettings() {
    }

    /**
     * The fields of {@code ours} that differ from {@code theirs} beyond what the
     * zones add; empty when nothing else differs. Each settings object carries
     * its {@code material_rule} resolved to the rule itself, and
     * {@code zoneRules} are the encoded palette rules the zones' biomes bring:
     * each must be in our material rule once, and the rest of it must equal
     * theirs entry for entry.
     */
    static List<String> differences(JsonObject ours, JsonObject theirs, List<JsonElement> zoneRules) {
        List<String> out = new ArrayList<>();
        for (String key : new TreeSet<>(union(ours.keySet(), theirs.keySet()))) {
            JsonElement a = ours.get(key);
            JsonElement b = theirs.get(key);
            if (key.equals("noise_router") && a != null && b != null && a.isJsonObject() && b.isJsonObject()) {
                JsonObject ra = a.getAsJsonObject();
                JsonObject rb = b.getAsJsonObject();
                for (String r : new TreeSet<>(union(ra.keySet(), rb.keySet()))) {
                    if (!r.equals("temperature") && !Objects.equals(ra.get(r), rb.get(r))) out.add("noise_router." + r);
                }
            } else if (key.equals("material_rule") && a != null && b != null && a.isJsonObject() && b.isJsonObject()) {
                List<JsonElement> rest = entries(a.getAsJsonObject());
                for (JsonElement rule : zoneRules) {
                    if (!rest.remove(rule)) out.add("material_rule (no entry for " + biomes(rule) + ")");
                }
                if (!rest.equals(entries(b.getAsJsonObject()))) out.add("material_rule");
            } else if (!Objects.equals(a, b)) {
                out.add(key);
            }
        }
        return out;
    }

    /** The rule's top-level entries: a sequence's, else the rule alone. */
    static List<JsonElement> entries(JsonObject rule) {
        List<JsonElement> out = new ArrayList<>();
        if (rule.has("type") && "minecraft:sequence".equals(rule.get("type").getAsString()) && rule.has("sequence")) {
            rule.getAsJsonArray("sequence").forEach(out::add);
        } else {
            out.add(rule);
        }
        return out;
    }

    private static String biomes(JsonElement rule) {
        if (rule.isJsonObject() && rule.getAsJsonObject().has("if_true")) {
            JsonElement condition = rule.getAsJsonObject().get("if_true");
            if (condition.isJsonObject() && condition.getAsJsonObject().has("biome_is")) {
                return condition.getAsJsonObject().get("biome_is").toString();
            }
        }
        return rule.toString();
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }
}
