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

    /** A palette that holds below {@code below}, an absolute y. */
    public record Band(int below, Palette palette) {
    }

    /**
     * The dimension's palette, the per-biome ones by biome id or {@code #tag}
     * in file order, the depth bands deepest first, and a floor (a block, or
     * null) for every biome outside {@code ownFloors} that no palette tops.
     */
    public record Spec(Palette dimension, Map<String, Palette> biomes, List<Band> bands, String floor, List<String> ownFloors) {
        public Spec(Palette dimension, Map<String, Palette> biomes) {
            this(dimension, biomes, List.of(), null, List.of());
        }

        public boolean isEmpty() {
            return dimension.isEmpty() && biomes.isEmpty() && bands.isEmpty() && floor == null;
        }

        /** Every block a palette names. */
        public Set<String> blocks() {
            Set<String> out = new java.util.TreeSet<>(dimension.blocks().values());
            biomes.values().forEach(p -> out.addAll(p.blocks().values()));
            bands.forEach(b -> out.addAll(b.palette().blocks().values()));
            if (floor != null) out.add(floor);
            return out;
        }

        public Spec withBiomes(Map<String, Palette> b) {
            return new Spec(dimension, b, bands, floor, ownFloors);
        }

        public Spec withFloor(String block, List<String> keep) {
            return new Spec(dimension, biomes, bands, block, keep);
        }
    }

    public static final String BANDS = "bands";
    public static final String BELOW = "below";

    /**
     * The ground of each vanilla Nether biome, laid by the Nether's own rule on
     * the nether preset and by these palettes on any other, where the preset's
     * rule has no clause for them.
     */
    public static final Map<String, Palette> NETHER_GROUND = Map.of(
            "minecraft:crimson_forest", new Palette(Map.of("top", "minecraft:crimson_nylium", "filler", "minecraft:netherrack")),
            "minecraft:warped_forest", new Palette(Map.of("top", "minecraft:warped_nylium", "filler", "minecraft:netherrack")),
            "minecraft:soul_sand_valley", new Palette(Map.of("top", "minecraft:soul_sand", "filler", "minecraft:soul_soil")),
            "minecraft:nether_wastes", new Palette(Map.of("top", "minecraft:netherrack", "filler", "minecraft:netherrack")),
            "minecraft:basalt_deltas", new Palette(Map.of("top", "minecraft:basalt", "filler", "minecraft:blackstone",
                    "cliffs", "minecraft:blackstone")));
    /** The floor a biome with no floor of its own takes under the caves preset's ceiling, where vanilla's rule lays grass. */
    public static final String SUNLESS_FLOOR = "minecraft:moss_block";
    /** The vanilla rule whose biome conditions name every overworld biome with a floor of its own. */
    static final String BIOME_SURFACE = "minecraft:overworld/biome_surface";

    /** The rule round {@link #BIOME_SURFACE} whose biome clauses lay a block of their own before it (badlands terracotta, frozen-ocean ice). */
    static final String SURFACE = "minecraft:overworld/surface";

    /**
     * The biomes vanilla's overworld surface gives a floor of their own: every
     * biome {@link #BIOME_SURFACE} names, and every one a {@link #SURFACE}
     * clause lays a block other than water for. Every other one falls to the
     * grass of {@code biome_surface/default}.
     */
    public static List<String> ownFloors(VanillaData vanilla) {
        Set<String> out = new java.util.TreeSet<>();
        collectBiomes(vanilla.materialRule(BIOME_SURFACE), out, false);
        collectBiomes(vanilla.materialRule(SURFACE), out, true);
        return List.copyOf(out);
    }

    private static void collectBiomes(JsonElement e, Set<String> out, boolean byBlock) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            JsonElement condition = o.get("if_true");
            if (condition != null && condition.isJsonObject() && condition.getAsJsonObject().has("biome_is")) {
                Set<String> blocks = new java.util.TreeSet<>();
                if (byBlock) blocksOf(o.get("then_run"), blocks);
                blocks.remove("minecraft:water");
                if (!byBlock || !blocks.isEmpty()) {
                    JsonElement b = condition.getAsJsonObject().get("biome_is");
                    if (b.isJsonArray()) b.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
                    else out.add(b.getAsString());
                }
            }
            o.entrySet().forEach(kv -> collectBiomes(kv.getValue(), out, byBlock));
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> collectBiomes(x, out, byBlock));
        }
    }

    private static void blocksOf(JsonElement e, Set<String> out) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("result_state") && o.get("result_state").isJsonPrimitive()) out.add(o.get("result_state").getAsString());
            o.entrySet().forEach(kv -> blocksOf(kv.getValue(), out));
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> blocksOf(x, out));
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
            if (!PALETTE_FIELDS.contains(key) && !DEFAULT_BLOCK.equals(key) && !BIOMES.equals(key) && !BANDS.equals(key)) {
                throw error(slug, key, "is not a materials field; known: " + DEFAULT_BLOCK + ", "
                        + String.join(", ", PALETTE_FIELDS) + ", " + BIOMES + ", " + BANDS);
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
        return new Spec(dimension, biomes, bands(slug, materials.get(BANDS)), null, List.of());
    }

    /** {@code materials.bands}: palettes by depth, each below a y higher than the last. */
    private static List<Band> bands(String slug, JsonElement e) {
        if (e == null || e.isJsonNull()) return List.of();
        if (!e.isJsonArray() || e.getAsJsonArray().isEmpty()) {
            throw error(slug, BANDS, "must be a non-empty list of palettes, each with `below`, deepest first");
        }
        List<Band> out = new ArrayList<>();
        Integer last = null;
        for (int i = 0; i < e.getAsJsonArray().size(); i++) {
            String field = BANDS + "[" + i + "]";
            JsonElement b = e.getAsJsonArray().get(i);
            if (!b.isJsonObject()) throw error(slug, field, "must be a palette object with `below`");
            JsonObject o = b.getAsJsonObject().deepCopy();
            JsonElement below = o.remove(BELOW);
            if (below == null || !below.isJsonPrimitive() || !below.getAsJsonPrimitive().isNumber()
                    || below.getAsDouble() != Math.rint(below.getAsDouble())) {
                throw error(slug, field + "." + BELOW, "must be a whole y, the band's top");
            }
            int y = below.getAsInt();
            if (y < -2032 || y > 2032) throw error(slug, field + "." + BELOW, y + " is outside the world");
            if (last != null && y <= last) {
                throw error(slug, field + "." + BELOW, y + " must lie above the band before it (" + last + "): bands run deepest first");
            }
            Palette p = palette(slug, field + ".", o, true);
            if (p.isEmpty()) throw error(slug, field, "sets no palette field");
            out.add(new Band(y, p));
            last = y;
        }
        return List.copyOf(out);
    }

    /**
     * The compiled rule over the preset's {@code presetRuleId}, or null when
     * the palette is empty and {@code veins} is null (the preset's rule as it is).
     *
     * @param veins the ore-vein entries to use, or null to keep the preset's
     */
    static JsonObject compose(VanillaData vanilla, String presetRuleId, Spec spec, List<JsonElement> veins, int seaLevel) {
        return compose(vanilla, presetRuleId, spec, veins, seaLevel, null, null);
    }

    /**
     * As {@link #compose(VanillaData, String, Spec, List, int)}; with a {@code defaultBlock}, the preset's
     * {@link #fallsBackTo fallback} to its own default block {@code presetDefault} lays {@code defaultBlock}
     * instead, so the ground no palette field claims is the definition's (the End's rule is that one block).
     */
    static JsonObject compose(VanillaData vanilla, String presetRuleId, Spec spec, List<JsonElement> veins, int seaLevel,
                              String presetDefault, String defaultBlock) {
        JsonObject preset = vanilla.materialRule(presetRuleId);
        List<JsonElement> entries = new ArrayList<>();
        if ("minecraft:sequence".equals(preset.get("type").getAsString())) {
            preset.getAsJsonArray("sequence").forEach(entries::add);
        } else {
            entries.add(preset);
        }
        boolean refill = defaultBlock != null && !defaultBlock.equals(presetDefault)
                && entries.stream().anyMatch(e -> fallsBackTo(e, presetDefault));
        if (spec.isEmpty() && veins == null && !refill) {
            return null;
        }
        if (refill) {
            entries.replaceAll(e -> fallsBackTo(e, presetDefault) ? block(defaultBlock) : e);
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
        boolean gate = gated(rest);
        JsonArray sequence = new JsonArray();
        bedrock.forEach(sequence::add);
        (veins == null ? presetVeins : veins).forEach(sequence::add);
        for (Band band : spec.bands()) {
            sequence.add(when(not(yAbove(band.below())), paletteRule(band.palette(), gate, seaLevel)));
        }
        for (Map.Entry<String, Palette> e : spec.biomes().entrySet()) {
            sequence.add(biomeRule(e.getKey(), e.getValue(), gate, seaLevel));
        }
        if (!spec.dimension().isEmpty()) {
            sequence.add(paletteRule(spec.dimension(), gate, seaLevel));
        }
        if (spec.floor() != null) {
            sequence.add(floorRule(spec.floor(), spec.ownFloors()));
        }
        rest.forEach(sequence::add);
        return sequenceOf(sequence);
    }

    /** Whether {@code e} is an unconditional {@code minecraft:block} entry laying {@code block}: a preset's fill of its own default block. */
    static boolean fallsBackTo(JsonElement e, String block) {
        if (block == null || !e.isJsonObject()) return false;
        JsonObject o = e.getAsJsonObject();
        return "minecraft:block".equals(o.get("type").getAsString()) && o.get("result_state").isJsonPrimitive()
                && block.equals(o.get("result_state").getAsString());
    }

    /** Whether a preset rule's entries gate their surface rules on {@code minecraft:above_preliminary_surface}. */
    public static boolean gated(Iterable<JsonElement> entries) {
        for (JsonElement e : entries) {
            if (isSurfaceGate(e)) return true;
        }
        return false;
    }

    /** The palette of {@code biome} (an id or {@code #tag}) as a rule that holds only in that biome. */
    public static JsonObject biomeRule(String biome, Palette palette, boolean gate, int seaLevel) {
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "minecraft:biome");
        if (biome.startsWith("#")) {
            condition.addProperty("biome_is", biome);
        } else {
            JsonArray ids = new JsonArray();
            ids.add(biome);
            condition.add("biome_is", ids);
        }
        return when(condition, paletteRule(palette, gate, seaLevel));
    }

    /** {@code block} on every dry floor of a biome outside {@code keep}. */
    static JsonObject floorRule(String block, List<String> keep) {
        JsonObject rule = when(ref("minecraft:on_floor"), when(ref("minecraft:not_underwater"), block(block)));
        if (keep.isEmpty()) return rule;
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "minecraft:biome");
        JsonArray ids = new JsonArray();
        keep.forEach(ids::add);
        condition.add("biome_is", ids);
        return when(not(condition), rule);
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
