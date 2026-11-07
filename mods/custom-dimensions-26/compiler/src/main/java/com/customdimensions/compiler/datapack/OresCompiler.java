package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code ores}: veins as material rules, added ores as placed features, and
 * removed placed features. 26.3 keeps ore veins in the material rule
 * ({@code minecraft:ore_vein}), so {@code veins} is data; biomes carry the
 * placed ores and are shared by every dimension, so {@code add} and
 * {@code remove} reach one dimension through the runtime, which swaps that
 * level's per-biome feature lists ({@link RuntimeWorldgen}). No biome is copied.
 */
public final class OresCompiler {

    public static final List<String> FIELDS = List.of("veins", "add", "remove");
    public static final List<String> VEIN_FIELDS = List.of("like", "ore", "rawOre", "filler", "rawOreChance");
    public static final List<String> ORE_FIELDS =
            List.of("id", "block", "deepBlock", "size", "count", "minY", "maxY", "shape", "airExposure");
    public static final List<String> VEIN_SHAPES = List.of("copper", "iron");
    public static final List<String> HEIGHT_SHAPES = List.of("uniform", "trapezoid");
    static final int DEFAULT_SIZE = 8;
    static final Pattern NAME = Pattern.compile("[a-z0-9_]+");

    public record Vein(String like, String ore, String rawOre, String filler, Double rawOreChance) {}

    public record Ore(String id, String block, String deepBlock, int size, int count, int minY, int maxY,
                      String shape, double airExposure) {}

    /**
     * @param keepVeins null when {@code veins} is absent or {@code true}, false when it is {@code false}
     * @param veins     the replacement veins when {@code veins} is a list, else null
     */
    public record Spec(Boolean keepVeins, List<Vein> veins, List<Ore> add, List<String> remove) {
        public boolean isEmpty() {
            return keepVeins == null && veins == null && add.isEmpty() && remove.isEmpty();
        }
    }

    private OresCompiler() {
    }

    public static Spec read(String slug, JsonObject ores) {
        if (ores == null) {
            return new Spec(null, null, List.of(), List.of());
        }
        closed(slug, "", ores, FIELDS);
        Boolean keep = null;
        List<Vein> veins = null;
        JsonElement v = ores.get("veins");
        if (v != null && !v.isJsonNull()) {
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                keep = v.getAsBoolean() ? null : Boolean.FALSE;
            } else if (v.isJsonArray()) {
                veins = new ArrayList<>();
                int i = 0;
                for (JsonElement e : v.getAsJsonArray()) {
                    veins.add(vein(slug, "veins[" + i++ + "]", e));
                }
            } else {
                throw error(slug, "veins", "must be true, false or a list of veins");
            }
        }
        List<Ore> add = new ArrayList<>();
        JsonElement a = ores.get("add");
        if (a != null && !a.isJsonNull()) {
            if (!a.isJsonArray()) {
                throw error(slug, "add", "must be a list of ores");
            }
            Set<String> ids = new HashSet<>();
            int i = 0;
            for (JsonElement e : a.getAsJsonArray()) {
                Ore ore = ore(slug, "add[" + i++ + "]", e);
                if (!ids.add(ore.id())) {
                    throw error(slug, "add[" + (i - 1) + "].id", "`" + ore.id() + "` is used twice");
                }
                add.add(ore);
            }
        }
        List<String> remove = new ArrayList<>();
        JsonElement r = ores.get("remove");
        if (r != null && !r.isJsonNull()) {
            if (!r.isJsonArray()) {
                throw error(slug, "remove", "must be a list of placed feature ids");
            }
            int i = 0;
            for (JsonElement e : r.getAsJsonArray()) {
                remove.add(id(slug, "remove[" + i++ + "]", e));
            }
        }
        return new Spec(keep, veins, List.copyOf(add), List.copyOf(remove));
    }

    /** Refuses a removed {@code minecraft:} feature vanilla does not have. */
    static void validate(String slug, Spec spec, VanillaData vanilla) {
        for (int i = 0; i < spec.remove().size(); i++) {
            String id = spec.remove().get(i);
            if (id.startsWith("minecraft:") && !vanilla.hasPlacedFeature(id)) {
                throw error(slug, "remove[" + i + "]", "`" + id + "` is not a vanilla placed feature");
            }
        }
    }

    /** The ore-vein entries for the material rule: null keeps the preset's, empty removes them. */
    static List<JsonElement> veins(Spec spec, VanillaData vanilla) {
        if (Boolean.FALSE.equals(spec.keepVeins())) {
            return List.of();
        }
        if (spec.veins() == null) {
            return null;
        }
        List<JsonElement> out = new ArrayList<>();
        for (Vein vein : spec.veins()) {
            JsonObject rule = vanilla.materialRule("minecraft:overworld/" + vein.like() + "_ore_vein");
            rule.addProperty("ore_block", vein.ore());
            rule.addProperty("raw_ore_block", vein.rawOre() != null ? vein.rawOre() : vein.ore());
            if (vein.filler() != null) {
                rule.addProperty("filler_block", vein.filler());
            }
            if (vein.rawOreChance() != null) {
                rule.addProperty("raw_ore_chance", vein.rawOreChance());
            }
            out.add(rule);
        }
        return out;
    }

    /** {@code <ns>:ores/<slug>/<id>}: the configured and the placed feature of one added ore. */
    static String featureId(String namespace, String slug, Ore ore) {
        return namespace + ":ores/" + slug + "/" + ore.id();
    }

    /**
     * Each added ore as a {@code minecraft:ore} feature and its placed feature,
     * by pack path. It replaces stone and deepslate ore replaceables, and every
     * block in {@code paletteBlocks}, so a palette's own stone takes ores too.
     */
    static Map<String, JsonObject> features(String namespace, String slug, Spec spec, Set<String> paletteBlocks) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (Ore ore : spec.add()) {
            String id = featureId(namespace, slug, ore);
            JsonArray targets = new JsonArray();
            targets.add(target(ore.block(), tagMatch("minecraft:stone_ore_replaceables")));
            targets.add(target(ore.deepBlock() != null ? ore.deepBlock() : ore.block(), tagMatch("minecraft:deepslate_ore_replaceables")));
            for (String b : paletteBlocks) {
                JsonObject match = new JsonObject();
                match.addProperty("predicate_type", "minecraft:block_match");
                match.addProperty("block", b);
                targets.add(target(ore.block(), match));
            }
            JsonObject feature = new JsonObject();
            feature.addProperty("type", "minecraft:ore");
            feature.addProperty("discard_chance_on_air_exposure", ore.airExposure());
            feature.addProperty("size", ore.size());
            feature.add("targets", targets);
            out.put(DatapackCompiler.dataPath(id, "worldgen/feature"), feature);

            JsonArray placement = new JsonArray();
            JsonObject count = new JsonObject();
            count.addProperty("type", "minecraft:count");
            count.addProperty("count", ore.count());
            placement.add(count);
            JsonObject square = new JsonObject();
            square.addProperty("type", "minecraft:in_square");
            placement.add(square);
            JsonObject height = new JsonObject();
            height.addProperty("type", "minecraft:" + ore.shape());
            height.add("min_inclusive", absolute(ore.minY()));
            height.add("max_inclusive", absolute(ore.maxY()));
            JsonObject range = new JsonObject();
            range.addProperty("type", "minecraft:height_range");
            range.add("height", height);
            placement.add(range);
            JsonObject placed = new JsonObject();
            placed.addProperty("feature", id);
            placed.add("placement", placement);
            out.put(DatapackCompiler.dataPath(id, "worldgen/placed_feature"), placed);
        }
        return out;
    }

    private static Vein vein(String slug, String field, JsonElement e) {
        if (!e.isJsonObject()) {
            throw error(slug, field, "must be a vein object");
        }
        JsonObject o = e.getAsJsonObject();
        closed(slug, field + ".", o, VEIN_FIELDS);
        String like = string(slug, field + ".like", o.get("like"));
        if (!VEIN_SHAPES.contains(like)) {
            throw error(slug, field + ".like", "`" + like + "` is not one of " + String.join(", ", VEIN_SHAPES));
        }
        Double chance = null;
        if (o.has("rawOreChance")) {
            chance = number(slug, field + ".rawOreChance", o.get("rawOreChance"));
            if (chance < 0 || chance > 1) {
                throw error(slug, field + ".rawOreChance", chance + " is outside 0 to 1");
            }
        }
        return new Vein(like, id(slug, field + ".ore", o.get("ore")),
                o.has("rawOre") ? id(slug, field + ".rawOre", o.get("rawOre")) : null,
                o.has("filler") ? id(slug, field + ".filler", o.get("filler")) : null, chance);
    }

    private static Ore ore(String slug, String field, JsonElement e) {
        if (!e.isJsonObject()) {
            throw error(slug, field, "must be an ore object");
        }
        JsonObject o = e.getAsJsonObject();
        closed(slug, field + ".", o, ORE_FIELDS);
        String id = string(slug, field + ".id", o.get("id"));
        if (!NAME.matcher(id).matches()) {
            throw error(slug, field + ".id", "`" + id + "` must match [a-z0-9_]+");
        }
        int size = o.has("size") ? integer(slug, field + ".size", o.get("size"), 0, 64) : DEFAULT_SIZE;
        int count = integer(slug, field + ".count", o.get("count"), 0, 256);
        int minY = integer(slug, field + ".minY", o.get("minY"), Integer.MIN_VALUE, Integer.MAX_VALUE);
        int maxY = integer(slug, field + ".maxY", o.get("maxY"), Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (minY > maxY) {
            throw error(slug, field + ".minY", minY + " is above maxY " + maxY);
        }
        String shape = o.has("shape") ? string(slug, field + ".shape", o.get("shape")) : "uniform";
        if (!HEIGHT_SHAPES.contains(shape)) {
            throw error(slug, field + ".shape", "`" + shape + "` is not one of " + String.join(", ", HEIGHT_SHAPES));
        }
        double air = o.has("airExposure") ? number(slug, field + ".airExposure", o.get("airExposure")) : 0.0;
        if (air < 0 || air > 1) {
            throw error(slug, field + ".airExposure", air + " is outside 0 to 1");
        }
        return new Ore(id, id(slug, field + ".block", o.get("block")),
                o.has("deepBlock") ? id(slug, field + ".deepBlock", o.get("deepBlock")) : null,
                size, count, minY, maxY, shape, air);
    }

    private static void closed(String slug, String prefix, JsonObject o, List<String> fields) {
        for (String key : o.keySet()) {
            if (!fields.contains(key)) {
                throw error(slug, prefix + key, "is not a field here; known: " + String.join(", ", fields));
            }
        }
    }

    private static String string(String slug, String field, JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw error(slug, field, "is required and must be a string");
        }
        return e.getAsString();
    }

    private static String id(String slug, String field, JsonElement e) {
        String s = string(slug, field, e);
        if (!MaterialsCompiler.ID.matcher(s).matches()) {
            throw error(slug, field, "`" + s + "` is not an id like `minecraft:stone`");
        }
        return s;
    }

    private static double number(String slug, String field, JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw error(slug, field, "must be a number");
        }
        return e.getAsDouble();
    }

    private static int integer(String slug, String field, JsonElement e, int min, int max) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()
                || e.getAsDouble() != Math.rint(e.getAsDouble())) {
            throw error(slug, field, "is required and must be an integer");
        }
        int v = e.getAsInt();
        if (v < min || v > max) {
            throw error(slug, field, v + " is outside " + min + " to " + max);
        }
        return v;
    }

    private static JsonObject target(String state, JsonObject predicate) {
        JsonObject t = new JsonObject();
        t.addProperty("state", state);
        t.add("target", predicate);
        return t;
    }

    private static JsonObject tagMatch(String tag) {
        JsonObject t = new JsonObject();
        t.addProperty("predicate_type", "minecraft:tag_match");
        t.addProperty("tag", tag);
        return t;
    }

    private static JsonObject absolute(int y) {
        JsonObject a = new JsonObject();
        a.addProperty("absolute", y);
        return a;
    }

    static IllegalArgumentException error(String slug, String field, String message) {
        return new IllegalArgumentException(slug + ": ores." + field + ": " + message);
    }
}
