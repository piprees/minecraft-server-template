package com.customdimensions.compiler.convert;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionFormatException;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.Generator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one 1.21.1 (v1) definition's JSON into schema v2
 * ({@code docs/design/rebuild-26/schema-v2.md}). It works on the file's own
 * JSON, so settings defaults stay in settings.json rather than being copied
 * into every definition. Each call returns the v2 tree and a {@link FileReport}.
 */
public final class DefinitionConverter {

    /** The base world {@code paradise_lost} is written under this slug. */
    public static final String AVIARY_SLUG = "the_aviary";
    public static final String PARADISE_LOST_SLUG = "paradise_lost";
    public static final String PARADISE_LOST_ID = "adventure:paradise_lost";
    public static final String AVIARY_ID = "adventure:the_aviary";

    /** A whole string that is a namespaced id, optionally a {@code #tag}. */
    public static final Pattern ID = Pattern.compile("#?([a-z0-9_.-]+):([a-z0-9_./-]+)");

    private static final Map<String, String> BASE_WORLD_PRESETS = Map.of(
            "overworld", "overworld",
            "the_nether", "nether",
            "the_end", "end",
            PARADISE_LOST_SLUG, "floating_islands");

    private static final Map<String, String> TYPE_PRESETS = Map.of(
            "overworld", "overworld",
            "multi_biome", "overworld",
            "large_biomes", "large_biomes",
            "amplified", "amplified",
            "cave", "caves",
            "sky_islands", "floating_islands",
            "nether", "nether",
            "end", "end",
            "nether_islands", "end");

    /** v1 noise settings built from Terralith and Tectonic data: {@code terrain.base} becomes null for W8. */
    private static final Set<String> TERRAIN_PRESETS = Set.of("adventure:wide", "adventure:compressed");
    private static final String VOID_NOISE = "adventure:void";

    private static final Set<String> V1_FIELDS = Set.of(
            "type", "description", "seed", "spawn", "noiseSettings", "biomes", "biome", "checkerboardScale",
            "layers", "flatBiome", "settingsOverrides", "biomePatches", "scale", "borders", "difficulty",
            "hostileSpawning", "structureDensity", "structures", "portal", "exitPortal", "exits",
            "exitShrines", "environment", "seedRoll", "dimensionId", "inputHash");

    /** Top-level v1 fields dropped whole: parsed and inert in v1, or legacy. */
    private static final List<String> DROPPED_FIELDS = List.of("dimensionId", "hostileSpawning", "scale", "inputHash");
    private static final List<String> DROPPED_ROLL_FIELDS =
            List.of("family", "spawnRadius", "locateCap", "allowEndgameNearSpawn");

    private static final Set<String> DIMENSION_TYPE = Set.copyOf(DefinitionReader.DIMENSION_TYPE_FIELDS);
    private static final Set<String> ATMOSPHERE = Set.copyOf(DefinitionReader.ATMOSPHERE_FIELDS);
    /** v1 environment rule name to v2 rule name; {@code piglinSafe} is negated. */
    private static final Map<String, String> RULE_NAMES = Map.of(
            "bedWorks", "bedsWork",
            "respawnAnchorWorks", "respawnAnchorsWork",
            "piglinSafe", "piglinsZombify",
            "ultraWarm", "ultraWarm",
            "natural", "natural",
            "hasRaids", "raids");
    private static final Set<String> PATCH_FIELDS =
            Set.of("biome", "x", "z", "radius", "blend", "shape", "replace", "scope");

    private final NamespaceRegister register;

    public DefinitionConverter(NamespaceRegister register) {
        this.register = register;
    }

    /** One converted file: its v2 slug, its JSON and what the conversion reported. */
    public record Converted(String slug, JsonObject json, FileReport report) {
        public String fileName() {
            return slug + ".json";
        }
    }

    public static String targetSlug(String v1Slug) {
        return PARADISE_LOST_SLUG.equals(v1Slug) ? AVIARY_SLUG : v1Slug;
    }

    public Converted convert(String v1Slug, JsonObject v1) {
        String slug = targetSlug(v1Slug);
        String source = v1Slug + ".json";
        FileReport report = new FileReport(source, slug + ".json");
        scanIds(v1, "", report);
        for (String key : v1.keySet()) {
            if (!V1_FIELDS.contains(key)) {
                throw new ConversionException(source + ": unknown v1 field " + key
                        + "; the converter needs a mapping for it");
            }
        }

        Map<String, JsonElement> parts = new HashMap<>();
        parts.put("schemaVersion", new JsonPrimitive(Definition.SCHEMA_VERSION));
        copy(v1, "description", parts, "description");
        copy(v1, "seed", parts, "seed");
        copy(v1, "spawn", parts, "spawn");

        JsonObject generator = generator(v1Slug, v1, source, report);
        parts.put("generator", generator);
        terrain(v1, source, report, parts);
        biomes(v1, generator, source, report, parts);
        settingsOverrides(v1, generator, source, report, parts);
        zones(v1, source, parts);
        environment(v1, source, parts);
        copy(v1, "borders", parts, "borders");
        copy(v1, "difficulty", parts, "difficulty");
        structures(v1, source, report, parts);
        portals(v1, source, report, parts);
        copy(v1, "exitPortal", parts, "exitPortal");
        copy(v1, "exits", parts, "exits");
        copy(v1, "exitShrines", parts, "exitShrines");
        roll(v1, source, report, parts);
        for (String field : DROPPED_FIELDS) {
            if (v1.has(field)) {
                report.drop(field, field + ": " + v1.get(field));
            }
        }
        if (PARADISE_LOST_SLUG.equals(v1Slug)) {
            report.decide("base world paradise_lost converts to the_aviary",
                    "written as the_aviary.json (adventure:the_aviary) with preset floating_islands; "
                            + "its Paradise Lost ids are flagged above");
        }

        JsonObject out = new JsonObject();
        for (String key : Definition.FIELD_ORDER) {
            if (parts.containsKey(key)) {
                out.add(key, parts.get(key));
            }
        }
        rewriteIds(out, "", report);
        try {
            DefinitionReader.read(JsonWriter.write(out), slug + ".json");
        } catch (DefinitionFormatException e) {
            throw new ConversionException("converted " + source + " is not valid v2: " + e.getMessage());
        }
        return new Converted(slug, out, report);
    }

    /** settings.json: {@code schemaVersion} added, {@code defaults.structureDensity} moved to {@code defaults.structures.density}. */
    public Converted convertSettings(JsonObject v1) {
        FileReport report = new FileReport("settings.json", "settings.json");
        scanIds(v1, "", report);
        JsonObject out = new JsonObject();
        out.addProperty("schemaVersion", Definition.SCHEMA_VERSION);
        for (Map.Entry<String, JsonElement> e : v1.entrySet()) {
            if (e.getKey().equals("schemaVersion")) {
                continue;
            }
            if (e.getKey().equals("defaults") && e.getValue().isJsonObject()) {
                out.add("defaults", settingsDefaults(e.getValue().getAsJsonObject()));
            } else {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        rewriteIds(out, "", report);
        try {
            DefinitionReader.readSettings(JsonWriter.write(out), "settings.json");
        } catch (DefinitionFormatException e) {
            throw new ConversionException("converted settings.json is not valid v2: " + e.getMessage());
        }
        return new Converted("settings", out, report);
    }

    private static JsonObject settingsDefaults(JsonObject defaults) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : defaults.entrySet()) {
            if (e.getKey().equals("structureDensity")) {
                JsonObject structures = new JsonObject();
                structures.add("density", e.getValue().deepCopy());
                if (defaults.has("structures") && defaults.get("structures").isJsonObject()) {
                    defaults.getAsJsonObject("structures").entrySet()
                            .forEach(s -> structures.add(s.getKey(), s.getValue().deepCopy()));
                }
                out.add("structures", structures);
            } else if (e.getKey().equals("structures") && defaults.has("structureDensity")) {
                continue;
            } else {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------
    // Layers
    // ------------------------------------------------------------------------

    private JsonObject generator(String v1Slug, JsonObject v1, String source, FileReport report) {
        String type = string(v1, "type", source);
        String noise = string(v1, "noiseSettings", source);
        JsonObject gen = new JsonObject();
        if (VOID_NOISE.equals(noise)) {
            gen.addProperty("type", Generator.VOID);
            if (type != null && !"void".equals(type)) {
                report.decide("noiseSettings adventure:void replaces the type",
                        "v1 type " + type + " with noiseSettings adventure:void converts to generator.type void");
            }
        } else if (type == null) {
            String preset = BASE_WORLD_PRESETS.get(v1Slug);
            if (preset == null) {
                throw new ConversionException(source + ": no type, and " + v1Slug + " is not a base world");
            }
            noise(gen, preset);
        } else if (TYPE_PRESETS.containsKey(type)) {
            noise(gen, TYPE_PRESETS.get(type));
        } else {
            switch (type) {
                case "void" -> gen.addProperty("type", Generator.VOID);
                case "superflat" -> {
                    gen.addProperty("type", Generator.FLAT);
                    if (v1.has("flatBiome")) {
                        gen.add("biome", v1.get("flatBiome").deepCopy());
                    }
                    if (v1.has("layers")) {
                        gen.add("layers", v1.get("layers").deepCopy());
                    }
                }
                case "checkerboard" -> {
                    gen.addProperty("type", Generator.CHECKERBOARD);
                    if (v1.has("checkerboardScale")) {
                        gen.add("cellScale", v1.get("checkerboardScale").deepCopy());
                    }
                }
                case "single_biome" -> {
                    gen.addProperty("type", Generator.SINGLE_BIOME);
                    gen.addProperty("biome", singleBiome(v1, source));
                }
                default -> {
                    Matcher id = ID.matcher(type);
                    if (!id.matches() || type.startsWith("#")) {
                        throw new ConversionException(source + ": unknown type " + type);
                    }
                    gen.addProperty("type", Generator.CLONE);
                    gen.addProperty("of", type);
                    if (register.isFlagged(id.group(1))) {
                        report.decide("clone of a dimension from a flagged mod",
                                "generator clones " + type + "; the " + id.group(1) + " namespace is "
                                        + register.decisions(id.group(1)));
                    }
                }
            }
        }
        String genType = gen.get("type").getAsString();
        if (v1.has("checkerboardScale") && !Generator.CHECKERBOARD.equals(genType)) {
            report.drop("checkerboardScale", "checkerboardScale: " + v1.get("checkerboardScale")
                    + " (only checkerboard reads it)");
        }
        for (String flatOnly : List.of("layers", "flatBiome")) {
            if (v1.has(flatOnly) && !Generator.FLAT.equals(genType)) {
                report.drop(flatOnly, flatOnly + " (only superflat reads it)");
            }
        }
        return gen;
    }

    private static void noise(JsonObject gen, String preset) {
        gen.addProperty("type", Generator.NOISE);
        gen.addProperty("preset", preset);
    }

    private static String singleBiome(JsonObject v1, String source) {
        JsonElement biomes = v1.get("biomes");
        if (biomes == null || !biomes.isJsonArray() || biomes.getAsJsonArray().size() != 1) {
            throw new ConversionException(source + ": single_biome needs exactly one entry in biomes");
        }
        JsonElement only = biomes.getAsJsonArray().get(0);
        if (only.isJsonObject() && only.getAsJsonObject().has("id")) {
            return only.getAsJsonObject().get("id").getAsString();
        }
        return only.getAsString();
    }

    private static void terrain(JsonObject v1, String source, FileReport report, Map<String, JsonElement> parts) {
        String noise = string(v1, "noiseSettings", source);
        if (noise == null || VOID_NOISE.equals(noise)) {
            return;
        }
        if (!TERRAIN_PRESETS.contains(noise)) {
            throw new ConversionException(source + ": unknown noiseSettings " + noise
                    + "; the converter maps " + TERRAIN_PRESETS + " and " + VOID_NOISE);
        }
        JsonObject terrain = new JsonObject();
        terrain.add("base", JsonNull.INSTANCE);
        parts.put("terrain", terrain);
        report.decide("terrain.base: null where v1 named a preset",
                "v1 noiseSettings " + noise + " is built from Terralith and Tectonic data; terrain.base is null");
    }

    private static void biomes(JsonObject v1, JsonObject generator, String source, FileReport report,
                               Map<String, JsonElement> parts) {
        if (Generator.SINGLE_BIOME.equals(generator.get("type").getAsString())) {
            return;
        }
        if (!v1.has("biomes")) {
            String legacy = string(v1, "biome", source);
            if (legacy != null) {
                JsonArray list = new JsonArray();
                for (String id : legacy.split(",")) {
                    if (!id.isBlank()) {
                        list.add(id.trim());
                    }
                }
                parts.put("biomes", list);
                report.rewrite("biomes", "biome: \"" + legacy + "\"", "biomes: " + list);
            }
            return;
        }
        if (v1.has("biome")) {
            report.drop("biome", "biome: " + v1.get("biome") + " (biomes wins)");
        }
        JsonElement list = v1.get("biomes");
        if (!list.isJsonArray()) {
            throw new ConversionException(source + ": biomes must be an array");
        }
        JsonArray out = new JsonArray();
        for (JsonElement entry : list.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                out.add(entry.deepCopy());
                continue;
            }
            JsonObject converted = new JsonObject();
            for (Map.Entry<String, JsonElement> e : entry.getAsJsonObject().entrySet()) {
                switch (e.getKey()) {
                    case "id" -> converted.add("id", e.getValue().deepCopy());
                    case "parameters" -> converted.add("climate", e.getValue().deepCopy());
                    default -> throw new ConversionException(source + ": biomes entry field " + e.getKey()
                            + " has no v2 mapping");
                }
            }
            out.add(converted);
        }
        parts.put("biomes", out);
    }

    private static void settingsOverrides(JsonObject v1, JsonObject generator, String source, FileReport report,
                                          Map<String, JsonElement> parts) {
        JsonObject overrides = object(v1, "settingsOverrides", source);
        if (overrides == null) {
            return;
        }
        JsonObject liquids = new JsonObject();
        JsonObject materials = new JsonObject();
        for (String key : DefinitionReader.LIQUIDS_FIELDS) {
            if (overrides.has(key)) {
                liquids.add(key, overrides.get(key).deepCopy());
            }
        }
        for (Map.Entry<String, JsonElement> e : overrides.entrySet()) {
            switch (e.getKey()) {
                case "seaLevel", "defaultFluid" -> {
                }
                case "defaultBlock" -> materials.add("defaultBlock", e.getValue().deepCopy());
                case "endIsland" -> {
                    JsonElement preset = generator.get("preset");
                    if (preset != null && "end".equals(preset.getAsString())) {
                        generator.add("endIsland", e.getValue().deepCopy());
                    } else {
                        report.drop("settingsOverrides.endIsland", "settingsOverrides.endIsland: " + e.getValue()
                                + " (only the end preset reads it; a no-op on "
                                + (preset != null ? "preset " + preset.getAsString() : generator.get("type").getAsString())
                                + ")");
                    }
                }
                case "disableMobGeneration" -> {
                    report.drop("settingsOverrides.disableMobGeneration", "disableMobGeneration: " + e.getValue());
                    report.decide("settingsOverrides.disableMobGeneration has no v2 field",
                            "v1 disableMobGeneration: " + e.getValue() + " is not carried");
                }
                default -> throw new ConversionException(source + ": settingsOverrides." + e.getKey()
                        + " has no v2 mapping");
            }
        }
        if (!liquids.isEmpty()) {
            parts.put("liquids", liquids);
        }
        if (!materials.isEmpty()) {
            parts.put("materials", materials);
        }
    }

    private static void zones(JsonObject v1, String source, Map<String, JsonElement> parts) {
        JsonElement patches = v1.get("biomePatches");
        if (patches == null) {
            return;
        }
        if (!patches.isJsonArray()) {
            throw new ConversionException(source + ": biomePatches must be an array");
        }
        JsonArray zones = new JsonArray();
        for (JsonElement p : patches.getAsJsonArray()) {
            if (!p.isJsonObject()) {
                throw new ConversionException(source + ": biomePatches entries must be objects");
            }
            JsonObject patch = p.getAsJsonObject();
            for (String key : patch.keySet()) {
                if (!PATCH_FIELDS.contains(key)) {
                    throw new ConversionException(source + ": biomePatches field " + key + " has no v2 mapping");
                }
            }
            JsonObject zone = new JsonObject();
            zone.add("shape", patch.has("shape") ? patch.get("shape").deepCopy() : new JsonPrimitive("circle"));
            if (patch.has("x") && patch.has("z")) {
                JsonArray centre = new JsonArray();
                centre.add(patch.get("x").deepCopy());
                centre.add(patch.get("z").deepCopy());
                zone.add("centre", centre);
            }
            for (String key : List.of("radius", "blend")) {
                if (patch.has(key)) {
                    zone.add(key, patch.get(key).deepCopy());
                }
            }
            if (patch.has("biome")) {
                JsonArray biomes = new JsonArray();
                biomes.add(patch.get("biome").deepCopy());
                zone.add("biomes", biomes);
            }
            for (String key : List.of("replace", "scope")) {
                if (patch.has(key)) {
                    zone.add(key, patch.get(key).deepCopy());
                }
            }
            zones.add(zone);
        }
        if (!zones.isEmpty()) {
            parts.put("zones", zones);
        }
    }

    private static void environment(JsonObject v1, String source, Map<String, JsonElement> parts) {
        JsonObject env = object(v1, "environment", source);
        if (env == null) {
            return;
        }
        for (String key : env.keySet()) {
            if (!DIMENSION_TYPE.contains(key) && !ATMOSPHERE.contains(key) && !RULE_NAMES.containsKey(key)) {
                throw new ConversionException(source + ": environment." + key + " has no v2 mapping");
            }
        }
        JsonObject dimensionType = new JsonObject();
        for (String key : DefinitionReader.DIMENSION_TYPE_FIELDS) {
            if (env.has(key)) {
                dimensionType.add(key, env.get(key).deepCopy());
            }
        }
        JsonObject atmosphere = new JsonObject();
        for (String key : DefinitionReader.ATMOSPHERE_FIELDS) {
            if (env.has(key)) {
                atmosphere.add(key, env.get(key).deepCopy());
            }
        }
        Map<String, JsonElement> rules = new HashMap<>();
        for (Map.Entry<String, String> rule : RULE_NAMES.entrySet()) {
            JsonElement value = env.get(rule.getKey());
            if (value == null) {
                continue;
            }
            if (rule.getKey().equals("piglinSafe")) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new ConversionException(source + ": environment.piglinSafe must be true or false");
                }
                value = new JsonPrimitive(!value.getAsBoolean());
            }
            rules.put(rule.getValue(), value.deepCopy());
        }
        JsonObject orderedRules = new JsonObject();
        for (String key : DefinitionReader.RULES_FIELDS) {
            if (rules.containsKey(key)) {
                orderedRules.add(key, rules.get(key));
            }
        }
        putIfNotEmpty(parts, "dimensionType", dimensionType);
        putIfNotEmpty(parts, "atmosphere", atmosphere);
        putIfNotEmpty(parts, "rules", orderedRules);
    }

    private static void structures(JsonObject v1, String source, FileReport report, Map<String, JsonElement> parts) {
        JsonObject out = new JsonObject();
        if (v1.has("structureDensity")) {
            out.add("density", v1.get("structureDensity").deepCopy());
        }
        JsonObject structures = object(v1, "structures", source);
        if (structures != null) {
            for (Map.Entry<String, JsonElement> e : structures.entrySet()) {
                switch (e.getKey()) {
                    case "endgame" -> report.drop("structures.endgame", "structures.endgame: " + e.getValue());
                    case "shuns" -> out.add("shuns", shuns(e.getValue(), "structures.shuns", report));
                    case "density" -> {
                        if (!out.has("density")) {
                            out.add("density", e.getValue().deepCopy());
                        }
                    }
                    default -> out.add(e.getKey(), e.getValue().deepCopy());
                }
            }
        }
        putIfNotEmpty(parts, "structures", out);
    }

    /** Map-form shuns keep their names with {@code {}} values; a list stays a list. */
    private static JsonElement shuns(JsonElement shuns, String field, FileReport report) {
        if (!shuns.isJsonObject()) {
            return shuns.deepCopy();
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : shuns.getAsJsonObject().entrySet()) {
            boolean empty = e.getValue().isJsonObject() && e.getValue().getAsJsonObject().isEmpty();
            if (!empty) {
                report.drop(field + " distance", field + "." + e.getKey() + ": " + e.getValue());
            }
            out.add(e.getKey(), new JsonObject());
        }
        return out;
    }

    private static void portals(JsonObject v1, String source, FileReport report, Map<String, JsonElement> parts) {
        JsonElement portal = v1.get("portal");
        if (portal == null || portal.isJsonNull()) {
            return;
        }
        JsonArray entries = new JsonArray();
        if (portal.isJsonObject()) {
            entries.add(portal);
        } else if (portal.isJsonArray()) {
            entries = portal.getAsJsonArray();
        } else {
            throw new ConversionException(source + ": portal must be an object or an array");
        }
        JsonArray out = new JsonArray();
        int index = 0;
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject()) {
                throw new ConversionException(source + ": portal entries must be objects");
            }
            out.add(portalEntry(entry.getAsJsonObject(), "portals[" + index++ + "]", report));
        }
        parts.put("portals", out);
    }

    /** One portal unchanged except {@code immersive} to {@code preview}: on by default, so only off or tuning is written. */
    private static JsonObject portalEntry(JsonObject entry, String where, FileReport report) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : entry.entrySet()) {
            if (!e.getKey().equals("immersive")) {
                out.add(e.getKey(), e.getValue().deepCopy());
                continue;
            }
            JsonElement value = e.getValue();
            if (value.isJsonObject()) {
                out.add("preview", value.deepCopy());
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                if (!value.getAsBoolean()) {
                    out.addProperty("preview", false);
                }
            } else if (!value.isJsonNull()) {
                report.drop("portal.immersive", where + ".immersive: " + value + " (not true, false or an object;"
                        + " v1 read it as on, and preview defaults to on)");
            }
        }
        return out;
    }

    private static void roll(JsonObject v1, String source, FileReport report, Map<String, JsonElement> parts) {
        JsonObject seedRoll = object(v1, "seedRoll", source);
        if (seedRoll == null) {
            return;
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : seedRoll.entrySet()) {
            if (DROPPED_ROLL_FIELDS.contains(e.getKey())) {
                report.drop("seedRoll." + e.getKey(), "seedRoll." + e.getKey() + ": " + e.getValue());
            } else if (e.getKey().equals("shuns")) {
                out.add("shuns", shuns(e.getValue(), "seedRoll.shuns", report));
            } else {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        putIfNotEmpty(parts, "roll", out);
    }

    // ------------------------------------------------------------------------
    // Ids
    // ------------------------------------------------------------------------

    /** Flags every key and string value in the v1 tree whose namespace the register does not keep. */
    private void scanIds(JsonElement element, String path, FileReport report) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                String child = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                flagIfId(e.getKey(), child + " (key)", report);
                scanIds(e.getValue(), child, report);
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                scanIds(array.get(i), path + "[" + i + "]", report);
            }
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            flagIfId(element.getAsString(), path, report);
        }
    }

    private void flagIfId(String text, String where, FileReport report) {
        Matcher id = ID.matcher(text);
        if (id.matches() && register.isFlagged(id.group(1))) {
            report.flag(text, id.group(1), register.decisions(id.group(1)), where);
        }
    }

    /** Points every reference to the dimension {@code adventure:paradise_lost} at {@code adventure:the_aviary}. */
    private static void rewriteIds(JsonElement element, String path, FileReport report) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                String child = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                if (isParadiseLostId(e.getValue())) {
                    e.setValue(new JsonPrimitive(AVIARY_ID));
                    report.rewrite(child, PARADISE_LOST_ID, AVIARY_ID);
                } else {
                    rewriteIds(e.getValue(), child, report);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                String child = path + "[" + i + "]";
                if (isParadiseLostId(array.get(i))) {
                    array.set(i, new JsonPrimitive(AVIARY_ID));
                    report.rewrite(child, PARADISE_LOST_ID, AVIARY_ID);
                } else {
                    rewriteIds(array.get(i), child, report);
                }
            }
        }
    }

    private static boolean isParadiseLostId(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && PARADISE_LOST_ID.equals(e.getAsString());
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static void copy(JsonObject v1, String from, Map<String, JsonElement> parts, String to) {
        if (v1.has(from) && !v1.get(from).isJsonNull()) {
            parts.put(to, v1.get(from).deepCopy());
        }
    }

    private static void putIfNotEmpty(Map<String, JsonElement> parts, String key, JsonObject value) {
        if (!value.isEmpty()) {
            parts.put(key, value);
        }
    }

    private static String string(JsonObject o, String key, String source) {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull()) {
            return null;
        }
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
            throw new ConversionException(source + ": " + key + " must be a string");
        }
        return v.getAsString();
    }

    private static JsonObject object(JsonObject o, String key, String source) {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull()) {
            return null;
        }
        if (!v.isJsonObject()) {
            throw new ConversionException(source + ": " + key + " must be an object");
        }
        return v.getAsJsonObject();
    }
}
