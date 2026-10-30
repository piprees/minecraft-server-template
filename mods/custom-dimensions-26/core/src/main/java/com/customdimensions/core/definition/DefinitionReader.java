package com.customdimensions.core.definition;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.config.DimensionConfigLoader;
import com.customdimensions.core.dimension.ExitTarget;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Reads schema v2 definitions and settings. A file without
 * {@code "schemaVersion": 2} is refused with a message naming the converter;
 * unknown keys at the top level and in the closed objects ({@code generator},
 * {@code liquids}, {@code dimensionType}, {@code atmosphere}, {@code rules},
 * {@code borders}) are refused, as {@code schema/definition.schema.json} does.
 * Whole-line {@code //} comments are allowed, as in v1.
 */
public final class DefinitionReader {

    public static final List<String> GENERATOR_FIELDS =
            List.of("type", "preset", "of", "cellScale", "biome", "layers", "endIsland");
    public static final List<String> LIQUIDS_FIELDS = List.of("seaLevel", "lavaLevel", "defaultFluid", "aquifers");
    public static final List<String> DIMENSION_TYPE_FIELDS = List.of("minY", "height", "logicalHeight",
            "hasSkylight", "hasCeiling", "infiniburn", "monsterSpawnLightLevel", "monsterSpawnBlockLightLimit");
    public static final List<String> ATMOSPHERE_FIELDS =
            List.of("skyColor", "fogColor", "ambientLight", "fixedTime", "effects");
    public static final List<String> RULES_FIELDS =
            List.of("bedsWork", "respawnAnchorsWork", "piglinsZombify", "ultraWarm", "natural", "raids");
    public static final List<String> BORDERS_FIELDS = List.of("player", "generation");
    public static final List<String> DEATHS_FIELDS = List.of("inventory", "experience");
    public static final List<String> WIN_FIELDS =
            List.of("id", "trigger", "title", "description", "icon", "frame", "message", "exit");
    public static final List<String> TRIGGER_FIELDS =
            List.of("type", "pos", "radius", "entity", "item", "advancement", "count", "ticks");

    private DefinitionReader() {
    }

    public static Definition read(Path file) {
        return read(readText(file), file.toString());
    }

    public static DefinitionSettings readSettings(Path file) {
        return readSettings(readText(file), file.toString());
    }

    /** Parses one definition; {@code source} names the file in error messages. */
    public static Definition read(String text, String source) {
        JsonObject root = parseVersioned(text, source);
        Ctx ctx = new Ctx(source);
        ctx.onlyKeys(root, "", Definition.FIELD_ORDER);
        if (!root.has("generator")) {
            throw ctx.fail("generator", "is required");
        }
        return new Definition(
                Definition.SCHEMA_VERSION,
                ctx.string(root, "description"),
                seed(ctx, root.get("seed")),
                spawn(ctx, root.get("spawn")),
                generator(ctx, ctx.object(root, "generator")),
                ctx.object(root, "terrain"),
                biomes(ctx, root.get("biomes")),
                ctx.closed(root, "liquids", LIQUIDS_FIELDS, (c, o) -> new Liquids(
                        c.integer(o, "seaLevel"), c.integer(o, "lavaLevel"), c.string(o, "defaultFluid"),
                        c.string(o, "aquifers"))),
                ctx.object(root, "materials"),
                ctx.object(root, "ores"),
                ctx.objectList(root, "zones"),
                ctx.closed(root, "dimensionType", DIMENSION_TYPE_FIELDS, (c, o) -> new DimensionTypeFields(
                        c.integer(o, "minY"), c.integer(o, "height"), c.integer(o, "logicalHeight"),
                        c.bool(o, "hasSkylight"), c.bool(o, "hasCeiling"), c.string(o, "infiniburn"),
                        o.get("monsterSpawnLightLevel"), c.integer(o, "monsterSpawnBlockLightLimit"))),
                ctx.closed(root, "atmosphere", ATMOSPHERE_FIELDS, (c, o) -> new Atmosphere(
                        c.string(o, "skyColor"), c.string(o, "fogColor"), c.number(o, "ambientLight"),
                        c.longValue(o, "fixedTime"), c.string(o, "effects"))),
                ctx.closed(root, "rules", RULES_FIELDS, (c, o) -> new Rules(
                        c.bool(o, "bedsWork"), c.bool(o, "respawnAnchorsWork"), c.bool(o, "piglinsZombify"),
                        c.bool(o, "ultraWarm"), c.bool(o, "natural"), c.bool(o, "raids"))),
                ctx.closed(root, "borders", BORDERS_FIELDS, (c, o) -> new Borders(
                        c.integer(o, "player"), c.integer(o, "generation"))),
                ctx.object(root, "difficulty"),
                ctx.object(root, "structures"),
                ctx.objectList(root, "portals"),
                ctx.object(root, "exitPortal"),
                ctx.object(root, "exits"),
                ctx.object(root, "exitShrines"),
                ctx.closed(root, "deaths", DEATHS_FIELDS, (c, o) -> new Deaths(
                        deathRule(c, o, "inventory"), deathRule(c, o, "experience"))),
                wins(ctx, root.get("wins")),
                ctx.object(root, "roll"));
    }

    /** Parses settings.json; it carries {@code "schemaVersion": 2} like a definition. */
    public static DefinitionSettings readSettings(String text, String source) {
        JsonObject root = parseVersioned(text, source);
        Ctx ctx = new Ctx(source);
        JsonObject defaults = ctx.object(root, "defaults");
        JsonObject suppress = ctx.object(root, "suppress");
        return new DefinitionSettings(Definition.SCHEMA_VERSION, ctx.string(root, "namespace"),
                ctx.integer(root, "idleUnloadMinutes"), ctx.object(root, "frames"),
                defaults != null ? defaults : new JsonObject(), suppress != null ? suppress : new JsonObject());
    }

    private static JsonObject parseVersioned(String text, String source) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(DimensionConfigLoader.stripJsonComments(text));
        } catch (JsonParseException e) {
            throw new DefinitionFormatException(source + ": not valid JSON: " + e.getMessage(), e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new DefinitionFormatException(source + ": a definition is a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement version = root.get("schemaVersion");
        boolean isV2 = version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()
                && version.getAsDouble() == Definition.SCHEMA_VERSION;
        if (!isV2) {
            String found = version == null ? "no \"schemaVersion\"" : "\"schemaVersion\": " + version;
            throw new DefinitionFormatException(source + ": " + found + " — this is not a schema v2 file. "
                    + "A 1.21.1 (v1) definition is converted with " + Definition.CONVERTER_TASK + ".");
        }
        return root;
    }

    private static Seed seed(Ctx ctx, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && Seed.ENV.equals(value.getAsString())) {
            return Seed.env();
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                return Seed.of(Long.parseLong(value.getAsString()));
            } catch (NumberFormatException e) {
                throw ctx.fail("seed", "must be a whole number that fits a long, got " + value);
            }
        }
        throw ctx.fail("seed", "must be a whole number or \"env\", got " + value);
    }

    private static Deaths.Rule deathRule(Ctx ctx, JsonObject o, String key) {
        String value = ctx.string(o, key);
        if (value == null) {
            return null;
        }
        Deaths.Rule rule = Deaths.Rule.parse(value);
        if (rule == null) {
            throw ctx.fail(key, "must be \"keep\" or \"drop\", got \"" + value + "\"");
        }
        return rule;
    }

    private static List<Win> wins(Ctx ctx, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw ctx.fail("wins", "must be an array");
        }
        List<Win> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        JsonArray array = value.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            Ctx c = new Ctx(ctx.source(), ctx.prefix() + "wins[" + i + "].");
            if (!array.get(i).isJsonObject()) {
                throw ctx.fail("wins[" + i + "]", "must be an object");
            }
            JsonObject o = array.get(i).getAsJsonObject();
            c.onlyKeys(o, c.prefix(), WIN_FIELDS);
            String id = c.string(o, "id");
            if (id == null || !id.matches("[a-z0-9_]+")) {
                throw c.fail("id", "is required and must match [a-z0-9_]+, got " + id);
            }
            if (!ids.add(id)) {
                throw c.fail("id", "\"" + id + "\" is used twice");
            }
            JsonObject t = c.object(o, "trigger");
            if (t == null) {
                throw c.fail("trigger", "is required");
            }
            Win.Trigger trigger = trigger(new Ctx(c.source(), c.prefix() + "trigger."), t);
            String frame = c.string(o, "frame");
            if (frame != null && !Win.FRAMES.contains(frame)) {
                throw c.fail("frame", "must be one of " + Win.FRAMES + ", got \"" + frame + "\"");
            }
            JsonElement exit = o.get("exit");
            if (exit != null && !exit.isJsonNull() && ExitTarget.parse(exit) == null) {
                throw c.fail("exit", "is not an exit target: " + exit);
            }
            String title = c.string(o, "title");
            if (title == null) {
                throw c.fail("title", "is required");
            }
            out.add(new Win(id, trigger, title, c.string(o, "description"), c.string(o, "icon"), frame,
                    c.string(o, "message"), exit == null || exit.isJsonNull() ? null : exit));
        }
        return out;
    }

    private static Win.Trigger trigger(Ctx c, JsonObject t) {
        c.onlyKeys(t, c.prefix(), TRIGGER_FIELDS);
        String typeName = c.string(t, "type");
        Win.Type type = typeName == null ? null : Win.Type.parse(typeName);
        if (type == null) {
            throw c.fail("type", "must be one of enter, reach, kill, obtain, survive, advancement, got " + typeName);
        }
        Integer count = c.integer(t, "count");
        Integer radius = c.integer(t, "radius");
        Integer ticks = c.integer(t, "ticks");
        BlockPos pos = null;
        String target = null;
        switch (type) {
            case REACH -> {
                pos = t.has("pos") ? spawnLike(c, t.get("pos"), "pos") : null;
                if (pos == null) throw c.fail("pos", "is required for reach");
            }
            case KILL -> target = required(c, t, "entity", type);
            case OBTAIN -> target = required(c, t, "item", type);
            case ADVANCEMENT -> target = required(c, t, "advancement", type);
            case SURVIVE -> {
                if (ticks == null || ticks < 1) throw c.fail("ticks", "is required for survive and must be at least 1");
            }
            case ENTER -> { }
        }
        if (count != null && (count < 1 || count > 1000)) {
            throw c.fail("count", "must be 1 to 1000, got " + count);
        }
        return new Win.Trigger(type, pos, radius != null ? Math.max(0, radius) : 3, target,
                count != null ? count : 1, ticks != null ? ticks : 0);
    }

    private static String required(Ctx c, JsonObject t, String key, Win.Type type) {
        String value = c.string(t, key);
        if (value == null || com.customdimensions.core.Id.tryParse(value.startsWith("#") ? value.substring(1) : value) == null) {
            throw c.fail(key, "is required for " + type.json() + " and must be an id or #tag, got " + value);
        }
        return value;
    }

    private static BlockPos spawnLike(Ctx ctx, JsonElement value, String key) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            throw ctx.fail(key, "must be [x, y, z], got " + value);
        }
        JsonArray xyz = value.getAsJsonArray();
        return new BlockPos(ctx.intOf(xyz.get(0), key + "[0]"), ctx.intOf(xyz.get(1), key + "[1]"),
                ctx.intOf(xyz.get(2), key + "[2]"));
    }

    private static BlockPos spawn(Ctx ctx, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            throw ctx.fail("spawn", "must be [x, y, z], got " + value);
        }
        JsonArray xyz = value.getAsJsonArray();
        int[] c = new int[3];
        for (int i = 0; i < 3; i++) {
            c[i] = ctx.intOf(xyz.get(i), "spawn[" + i + "]");
        }
        return new BlockPos(c[0], c[1], c[2]);
    }

    private static Generator generator(Ctx ctx, JsonObject o) {
        ctx.onlyKeys(o, "generator.", GENERATOR_FIELDS);
        String type = ctx.string(o, "type");
        if (type == null || !Generator.TYPES.contains(type)) {
            throw ctx.fail("generator.type", "must be one of " + sorted(Generator.TYPES) + ", got " + type);
        }
        String preset = ctx.string(o, "preset");
        if (Generator.NOISE.equals(type)) {
            if (preset == null || !Generator.PRESETS.contains(preset)) {
                throw ctx.fail("generator.preset", "must be one of " + sorted(Generator.PRESETS) + ", got " + preset);
            }
        } else if (preset != null) {
            throw ctx.fail("generator.preset", "applies to the noise type only");
        }
        String of = ctx.string(o, "of");
        if (Generator.CLONE.equals(type) != (of != null)) {
            throw ctx.fail("generator.of", "is required by the clone type and allowed nowhere else");
        }
        Integer cellScale = ctx.integer(o, "cellScale");
        if (cellScale != null && !Generator.CHECKERBOARD.equals(type)) {
            throw ctx.fail("generator.cellScale", "applies to the checkerboard type only");
        }
        String biome = ctx.string(o, "biome");
        if (biome != null && !Generator.FLAT.equals(type) && !Generator.SINGLE_BIOME.equals(type)) {
            throw ctx.fail("generator.biome", "applies to the flat and single_biome types only");
        }
        List<JsonObject> layers = ctx.objectList(o, "layers");
        if (o.has("layers") && !Generator.FLAT.equals(type)) {
            throw ctx.fail("generator.layers", "applies to the flat type only");
        }
        Boolean endIsland = ctx.bool(o, "endIsland");
        if (endIsland != null && !"end".equals(preset)) {
            throw ctx.fail("generator.endIsland", "applies to the end preset only");
        }
        return new Generator(type, preset, of, cellScale, biome, o.has("layers") ? layers : null, endIsland);
    }

    private static List<BiomeEntry> biomes(Ctx ctx, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw ctx.fail("biomes", "must be an array");
        }
        List<BiomeEntry> entries = new ArrayList<>();
        int i = 0;
        for (JsonElement e : value.getAsJsonArray()) {
            String where = "biomes[" + i++ + "]";
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                entries.add(new BiomeEntry(e.getAsString(), null));
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                ctx.onlyKeys(o, where + ".", List.of("id", "climate"));
                String id = ctx.string(o, "id");
                if (id == null) {
                    throw ctx.fail(where + ".id", "is required");
                }
                entries.add(new BiomeEntry(id, ctx.object(o, "climate")));
            } else {
                throw ctx.fail(where, "must be a biome id or {\"id\", \"climate\"}");
            }
        }
        return entries;
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read definition " + file, e);
        }
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    /** Typed field access that names the file and the field when a value is wrong. */
    private record Ctx(String source, String prefix) {

        Ctx(String source) {
            this(source, "");
        }

        DefinitionFormatException fail(String field, String problem) {
            return new DefinitionFormatException(source + ": " + prefix + field + " " + problem);
        }

        void onlyKeys(JsonObject o, String prefix, List<String> allowed) {
            Set<String> unknown = new LinkedHashSet<>();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (!allowed.contains(e.getKey())) {
                    unknown.add(e.getKey());
                }
            }
            if (!unknown.isEmpty()) {
                throw new DefinitionFormatException(source + ": unknown field(s) " + prefix
                        + String.join(", " + prefix, unknown) + "; schema v2 allows " + allowed);
            }
        }

        <T> T closed(JsonObject root, String key, List<String> allowed, BiFunction<Ctx, JsonObject, T> build) {
            JsonObject o = object(root, key);
            if (o == null) {
                return null;
            }
            onlyKeys(o, prefix + key + ".", allowed);
            return build.apply(new Ctx(source, prefix + key + "."), o);
        }

        JsonObject object(JsonObject o, String key) {
            JsonElement v = o.get(key);
            if (v == null || v.isJsonNull()) {
                return null;
            }
            if (!v.isJsonObject()) {
                throw fail(key, "must be an object, got " + v);
            }
            return v.getAsJsonObject();
        }

        List<JsonObject> objectList(JsonObject o, String key) {
            JsonElement v = o.get(key);
            if (v == null || v.isJsonNull()) {
                return List.of();
            }
            if (!v.isJsonArray()) {
                throw fail(key, "must be an array, got " + v);
            }
            List<JsonObject> list = new ArrayList<>();
            for (JsonElement e : v.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    throw fail(key, "entries must be objects, got " + e);
                }
                list.add(e.getAsJsonObject());
            }
            return list;
        }

        String string(JsonObject o, String key) {
            JsonPrimitive p = primitive(o, key);
            if (p == null) {
                return null;
            }
            if (!p.isString()) {
                throw fail(key, "must be a string, got " + p);
            }
            return p.getAsString();
        }

        Boolean bool(JsonObject o, String key) {
            JsonPrimitive p = primitive(o, key);
            if (p == null) {
                return null;
            }
            if (!p.isBoolean()) {
                throw fail(key, "must be true or false, got " + p);
            }
            return p.getAsBoolean();
        }

        Integer integer(JsonObject o, String key) {
            JsonPrimitive p = primitive(o, key);
            return p == null ? null : intOf(p, key);
        }

        Long longValue(JsonObject o, String key) {
            JsonPrimitive p = primitive(o, key);
            if (p == null) {
                return null;
            }
            try {
                return Long.parseLong(requireNumber(p, key).getAsString());
            } catch (NumberFormatException e) {
                throw fail(key, "must be a whole number, got " + p);
            }
        }

        Double number(JsonObject o, String key) {
            JsonPrimitive p = primitive(o, key);
            return p == null ? null : requireNumber(p, key).getAsDouble();
        }

        int intOf(JsonElement e, String key) {
            if (!e.isJsonPrimitive()) {
                throw fail(key, "must be a whole number, got " + e);
            }
            try {
                return Integer.parseInt(requireNumber(e.getAsJsonPrimitive(), key).getAsString());
            } catch (NumberFormatException ex) {
                throw fail(key, "must be a whole number, got " + e);
            }
        }

        private JsonPrimitive requireNumber(JsonPrimitive p, String key) {
            if (!p.isNumber()) {
                throw fail(key, "must be a number, got " + p);
            }
            return p;
        }

        private JsonPrimitive primitive(JsonObject o, String key) {
            JsonElement v = o.get(key);
            if (v == null || v.isJsonNull()) {
                return null;
            }
            if (!v.isJsonPrimitive()) {
                throw fail(key, "must be a single value, got " + v);
            }
            return v.getAsJsonPrimitive();
        }
    }
}
