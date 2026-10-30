package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code materials} palettes as a 26.3 material rule
 * ({@code worldgen/material_rule}). The compiled rule is a sequence: the
 * preset's bedrock entries, its ore veins (or {@code ores.veins}), each
 * per-biome palette under a {@code minecraft:biome} condition, the
 * dimension's palette, then the rest of the preset's rule, so anything a
 * palette leaves unset falls through to vanilla.
 *
 * <p>A palette's fields, as vanilla material conditions:
 * <ul>
 *   <li>{@code ceilings}: {@code minecraft:under_ceiling}, the solid blocks over an open space;</li>
 *   <li>{@code cliffs}: {@code minecraft:steep} and {@code minecraft:under_floor};</li>
 *   <li>{@code beaches}: {@code minecraft:under_floor} between sea level − 4 and sea level + 2;</li>
 *   <li>{@code top}: {@code minecraft:on_floor} and {@code minecraft:not_underwater};</li>
 *   <li>{@code filler}: {@code minecraft:under_floor}, the surface depth below a floor;</li>
 *   <li>{@code deep}: every other solid block.</li>
 * </ul>
 * Where the preset's rule gates its surface on {@code minecraft:above_preliminary_surface}
 * (the overworld family), the floor fields are gated the same way, so cave floors keep
 * vanilla's materials.
 */
public final class MaterialsCompiler {

    public static final List<String> PALETTE_FIELDS = List.of("top", "filler", "deep", "cliffs", "beaches", "ceilings");
    public static final String DEFAULT_BLOCK = "defaultBlock";
    public static final String BIOMES = "biomes";
    /** Preset rule entries that stay ahead of everything: bedrock. */
    static final Set<String> BEDROCK_RULES = Set.of("minecraft:bedrock_floor", "minecraft:bedrock_roof");
    static final String ABOVE_SURFACE = "minecraft:above_preliminary_surface";
    static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    static final Pattern BIOME_KEY = Pattern.compile("#?[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** One palette: field to block id, in {@link #PALETTE_FIELDS} order. */
    public record Palette(Map<String, String> blocks) {
        public boolean isEmpty() {
            return blocks.isEmpty();
        }
    }

    /** The dimension's palette and the per-biome ones by biome id or {@code #tag}, in file order. */
    public record Spec(Palette dimension, Map<String, Palette> biomes) {
        public boolean isEmpty() {
            return dimension.isEmpty() && biomes.isEmpty();
        }

        /** Every block a palette names. */
        public Set<String> blocks() {
            Set<String> out = new java.util.TreeSet<>(dimension.blocks().values());
            biomes.values().forEach(p -> out.addAll(p.blocks().values()));
            return out;
        }
    }

    private MaterialsCompiler() {
    }

    /** Reads {@code materials}; an unknown field or a malformed id is an error naming the slug and field. */
    public static Spec read(String slug, JsonObject materials) {
        if (materials == null) {
            return new Spec(new Palette(Map.of()), Map.of());
        }
        for (String key : materials.keySet()) {
            if (!PALETTE_FIELDS.contains(key) && !DEFAULT_BLOCK.equals(key) && !BIOMES.equals(key)) {
                throw error(slug, key, "is not a materials field; known: " + DEFAULT_BLOCK + ", "
                        + String.join(", ", PALETTE_FIELDS) + ", " + BIOMES);
            }
        }
        if (materials.has(DEFAULT_BLOCK) && !materials.get(DEFAULT_BLOCK).isJsonNull()) {
            id(slug, DEFAULT_BLOCK, materials.get(DEFAULT_BLOCK));
        }
        Palette dimension = palette(slug, "", materials, false);
        Map<String, Palette> biomes = new LinkedHashMap<>();
        JsonElement perBiome = materials.get(BIOMES);
        if (perBiome != null && !perBiome.isJsonNull()) {
            if (!perBiome.isJsonObject()) {
                throw error(slug, BIOMES, "must be an object of biome id or #tag to palette");
            }
            for (Map.Entry<String, JsonElement> e : perBiome.getAsJsonObject().entrySet()) {
                String field = BIOMES + "[" + e.getKey() + "]";
                if (!BIOME_KEY.matcher(e.getKey()).matches()) {
                    throw error(slug, field, "is not a biome id or #tag");
                }
                if (!e.getValue().isJsonObject()) {
                    throw error(slug, field, "must be a palette object");
                }
                Palette p = palette(slug, field + ".", e.getValue().getAsJsonObject(), true);
                if (p.isEmpty()) {
                    throw error(slug, field, "sets no palette field");
                }
                biomes.put(e.getKey(), p);
            }
        }
        return new Spec(dimension, biomes);
    }

    /**
     * The compiled rule over the preset's {@code presetRuleId}, or null when
     * the palette is empty and {@code veins} is null (the preset's rule as it is).
     *
     * @param veins the ore-vein entries to use, or null to keep the preset's
     */
    static JsonObject compose(VanillaData vanilla, String presetRuleId, Spec spec, List<JsonElement> veins, int seaLevel) {
        if (spec.isEmpty() && veins == null) {
            return null;
        }
        JsonObject preset = vanilla.materialRule(presetRuleId);
        List<JsonElement> entries = new ArrayList<>();
        if ("minecraft:sequence".equals(preset.get("type").getAsString())) {
            preset.getAsJsonArray("sequence").forEach(entries::add);
        } else {
            entries.add(preset);
        }
        List<JsonElement> bedrock = new ArrayList<>();
        List<JsonElement> presetVeins = new ArrayList<>();
        List<JsonElement> rest = new ArrayList<>();
        for (JsonElement e : entries) {
            if (isReference(e) && BEDROCK_RULES.contains(e.getAsString())) {
                bedrock.add(e);
            } else if (isReference(e) && "minecraft:ore_vein".equals(vanilla.materialRule(e.getAsString()).get("type").getAsString())) {
                presetVeins.add(e);
            } else {
                rest.add(e);
            }
        }
        boolean gate = rest.stream().anyMatch(MaterialsCompiler::isSurfaceGate);
        JsonArray sequence = new JsonArray();
        bedrock.forEach(sequence::add);
        (veins == null ? presetVeins : veins).forEach(sequence::add);
        for (Map.Entry<String, Palette> e : spec.biomes().entrySet()) {
            JsonObject condition = new JsonObject();
            condition.addProperty("type", "minecraft:biome");
            if (e.getKey().startsWith("#")) {
                condition.addProperty("biome_is", e.getKey());
            } else {
                JsonArray ids = new JsonArray();
                ids.add(e.getKey());
                condition.add("biome_is", ids);
            }
            sequence.add(when(condition, paletteRule(e.getValue(), gate, seaLevel)));
        }
        if (!spec.dimension().isEmpty()) {
            sequence.add(paletteRule(spec.dimension(), gate, seaLevel));
        }
        rest.forEach(sequence::add);
        return sequenceOf(sequence);
    }

    /** One palette as a sequence: ceilings, then the floor fields (gated when the preset gates), then deep. */
    static JsonObject paletteRule(Palette palette, boolean gate, int seaLevel) {
        Map<String, String> b = palette.blocks();
        JsonArray out = new JsonArray();
        if (b.containsKey("ceilings")) {
            out.add(when(ref("minecraft:under_ceiling"), block(b.get("ceilings"))));
        }
        JsonArray floor = new JsonArray();
        if (b.containsKey("cliffs")) {
            floor.add(when(unit("minecraft:steep"), when(ref("minecraft:under_floor"), block(b.get("cliffs")))));
        }
        if (b.containsKey("beaches")) {
            floor.add(when(ref("minecraft:under_floor"), when(yAbove(seaLevel - 4),
                    when(not(yAbove(seaLevel + 3)), block(b.get("beaches"))))));
        }
        if (b.containsKey("top")) {
            floor.add(when(ref("minecraft:on_floor"), when(ref("minecraft:not_underwater"), block(b.get("top")))));
        }
        if (b.containsKey("filler")) {
            floor.add(when(ref("minecraft:under_floor"), block(b.get("filler"))));
        }
        if (!floor.isEmpty()) {
            JsonObject floorRule = floor.size() == 1 ? floor.get(0).getAsJsonObject() : sequenceOf(floor);
            out.add(gate ? when(unit(ABOVE_SURFACE), floorRule) : floorRule);
        }
        if (b.containsKey("deep")) {
            out.add(block(b.get("deep")));
        }
        return out.size() == 1 ? out.get(0).getAsJsonObject() : sequenceOf(out);
    }

    private static Palette palette(String slug, String prefix, JsonObject o, boolean closed) {
        Map<String, String> blocks = new LinkedHashMap<>();
        if (closed) {
            for (String key : o.keySet()) {
                if (!PALETTE_FIELDS.contains(key)) {
                    throw error(slug, prefix + key, "is not a palette field; known: " + String.join(", ", PALETTE_FIELDS));
                }
            }
        }
        for (String field : PALETTE_FIELDS) {
            JsonElement v = o.get(field);
            if (v != null && !v.isJsonNull()) {
                blocks.put(field, id(slug, prefix + field, v));
            }
        }
        return new Palette(blocks);
    }

    private static String id(String slug, String field, JsonElement v) {
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || !ID.matcher(v.getAsString()).matches()) {
            throw error(slug, field, "must be a block id like `minecraft:stone`, not " + v);
        }
        return v.getAsString();
    }

    private static boolean isReference(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static boolean isSurfaceGate(JsonElement e) {
        if (!e.isJsonObject() || !e.getAsJsonObject().has("if_true")) {
            return false;
        }
        JsonElement condition = e.getAsJsonObject().get("if_true");
        return condition.isJsonObject() && ABOVE_SURFACE.equals(condition.getAsJsonObject().get("type").getAsString());
    }

    static JsonObject block(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:block");
        o.addProperty("result_state", id);
        return o;
    }

    static JsonObject when(JsonElement condition, JsonObject then) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:condition");
        o.add("if_true", condition);
        o.add("then_run", then);
        return o;
    }

    static JsonObject sequenceOf(JsonArray entries) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:sequence");
        o.add("sequence", entries);
        return o;
    }

    private static JsonElement ref(String id) {
        return new com.google.gson.JsonPrimitive(id);
    }

    private static JsonObject unit(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    private static JsonObject not(JsonObject condition) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:not");
        o.add("invert", condition);
        return o;
    }

    /** {@code minecraft:y_above} at an absolute height, with no surface or stone depth added. */
    private static JsonObject yAbove(int y) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("absolute", y);
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:y_above");
        o.add("anchor", anchor);
        o.addProperty("surface_depth_multiplier", 0);
        o.addProperty("add_stone_depth", false);
        return o;
    }

    static IllegalArgumentException error(String slug, String field, String message) {
        return new IllegalArgumentException(slug + ": materials." + field + ": " + message);
    }
}
