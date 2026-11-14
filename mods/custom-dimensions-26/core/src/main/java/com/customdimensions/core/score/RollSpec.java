package com.customdimensions.core.score;

import com.customdimensions.core.definition.Definition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A definition's {@code roll} field, typed: what the roller scores a seed
 * against. Shuns are the union of {@code roll.shuns} and the keys of
 * {@code structures.shuns}, so the scorer marks down what placement already
 * avoids.
 *
 * @param wants        want name to its band word ({@code near_spawn},
 *                     {@code spread}, {@code near_border}); a want with any
 *                     other word is in {@code unknownWants} and not scored
 * @param heightRange  {@code [low, high]} with low below high, or null
 */
public record RollSpec(boolean skip, String mood, List<String> spawnFilter, String water, String terrain,
                       int[] heightRange, Map<String, String> wants, List<String> shuns,
                       boolean allowHazardousSpawn, List<String> unknownWants) {

    public static final RollSpec NONE = new RollSpec(false, null, List.of(), null, null, null, Map.of(), List.of(),
            false, List.of());

    public RollSpec {
        spawnFilter = List.copyOf(spawnFilter);
        wants = Map.copyOf(new LinkedHashMap<>(wants));
        shuns = List.copyOf(shuns);
        unknownWants = List.copyOf(unknownWants);
    }

    public static RollSpec of(Definition def) {
        return def == null ? NONE : parse(def.roll(), def.structures());
    }

    public static RollSpec parse(JsonObject roll, JsonObject structures) {
        JsonObject r = roll == null ? new JsonObject() : roll;
        List<String> filter = strings(r.get("spawnFilter"));
        Map<String, String> wants = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        if (r.has("wants") && r.get("wants").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("wants").entrySet()) {
                String word = word(e.getValue());
                if (word != null && Band.of(word) != null) wants.put(e.getKey(), word);
                else unknown.add(e.getKey());
            }
        }
        Set<String> shuns = new LinkedHashSet<>();
        JsonElement rollShuns = r.get("shuns");
        if (rollShuns != null && rollShuns.isJsonArray()) shuns.addAll(strings(rollShuns));
        else if (rollShuns != null && rollShuns.isJsonObject()) shuns.addAll(rollShuns.getAsJsonObject().keySet());
        if (structures != null && structures.has("shuns") && structures.get("shuns").isJsonObject()) {
            shuns.addAll(structures.getAsJsonObject("shuns").keySet());
        }
        return new RollSpec(bool(r.get("skip")), word(r.get("mood")), filter, word(r.get("water")), word(r.get("terrain")),
                range(r.get("heightRange")), wants, new ArrayList<>(shuns), bool(r.get("allowHazardousSpawn")), unknown);
    }

    /** Where a wanted structure should sit, as fractions of the playable radius from spawn. */
    public enum Band {
        NEAR_SPAWN("near_spawn", 0.00, 0.15),
        SPREAD("spread", 0.10, 0.75),
        NEAR_BORDER("near_border", 0.55, 1.00);

        /** How far outside the band, as a fraction of the radius, scores zero. */
        public static final double TOLERANCE = 0.25;

        public final String word;
        public final double low;
        public final double high;

        Band(String word, double low, double high) {
            this.word = word;
            this.low = low;
            this.high = high;
        }

        /** The fraction of the radius beyond which a start scores as none: the band's far edge plus {@link #TOLERANCE}. */
        public double reach() {
            return high + TOLERANCE;
        }

        public static Band of(String word) {
            for (Band b : values()) if (b.word.equals(word)) return b;
            return null;
        }
    }

    private static boolean bool(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static String word(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
        String s = e.getAsString().trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement item : (JsonArray) e) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() && !item.getAsString().isBlank()) {
                out.add(item.getAsString().trim());
            }
        }
        return out;
    }

    private static int[] range(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 2) return null;
        int a = e.getAsJsonArray().get(0).getAsInt();
        int b = e.getAsJsonArray().get(1).getAsInt();
        return a == b ? null : new int[] {Math.min(a, b), Math.max(a, b)};
    }
}
