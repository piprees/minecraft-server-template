package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.convert.JsonWriter;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.Generator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The golden form of a compiled file: only the compiler's decisions, never
 * Mojang's data. Dimension types and noise settings become a diff against the
 * vanilla file they start from; multi-noise climate parameters, and any object
 * or object-holding array inside a diff, become the sha1 of their canonical
 * JSON. A changed mapping still changes the golden form.
 */
final class GoldenForm {

    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private GoldenForm() {
    }

    static String of(String path, String text, Definition definition, VanillaData vanilla) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        JsonObject form;
        if (path.contains("/dimension_type/")) {
            String base = DatapackCompiler.baseDimensionType(definition.generator());
            form = diff("minecraft:" + base, json, vanilla.dimensionType(base));
        } else if (path.contains("/worldgen/noise_settings/")) {
            Generator g = definition.generator();
            String base = Generator.NOISE.equals(g.type()) ? g.preset() : "overworld";
            form = diff("minecraft:" + base, json, vanilla.noiseSettings(base));
        } else if (path.contains("/dimension/") || path.contains("/lazy_dimension/")) {
            form = hashClimate(json);
        } else {
            form = json;
        }
        return JsonWriter.write(form);
    }

    /** {@code {"base", "changed", "removed"}}; a changed {@code attributes} map is itself a diff. */
    static JsonObject diff(String baseId, JsonObject ours, JsonObject base) {
        JsonObject out = new JsonObject();
        out.addProperty("base", baseId);
        JsonObject d = diffObject(ours, base, true);
        d.entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
        return out;
    }

    private static JsonObject diffObject(JsonObject ours, JsonObject base, boolean top) {
        JsonObject changed = new JsonObject();
        for (String key : new TreeSet<>(ours.keySet())) {
            JsonElement v = ours.get(key);
            if (base.has(key) && base.get(key).equals(v)) {
                continue;
            }
            if (top && "attributes".equals(key) && v.isJsonObject() && base.has(key) && base.get(key).isJsonObject()) {
                changed.add(key, diffObject(v.getAsJsonObject(), base.getAsJsonObject(key), false));
            } else {
                changed.add(key, summarise(v));
            }
        }
        JsonArray removed = new JsonArray();
        new TreeSet<>(base.keySet()).stream().filter(k -> !ours.has(k)).forEach(removed::add);
        JsonObject out = new JsonObject();
        out.add("changed", changed);
        if (!removed.isEmpty()) {
            out.add("removed", removed);
        }
        return out;
    }

    /** Scalars and arrays of scalars as they are; objects and arrays holding them as a sha1. */
    private static JsonElement summarise(JsonElement v) {
        if (v.isJsonObject()) {
            return new com.google.gson.JsonPrimitive(sha1(v));
        }
        if (v.isJsonArray()) {
            for (JsonElement e : v.getAsJsonArray()) {
                if (!e.isJsonPrimitive() && !e.isJsonNull()) {
                    return new com.google.gson.JsonPrimitive(sha1(v));
                }
            }
        }
        return v.deepCopy();
    }

    /** The stem with every multi-noise entry's {@code parameters} as a sha1. */
    private static JsonObject hashClimate(JsonObject stem) {
        JsonObject out = stem.deepCopy();
        JsonObject generator = out.getAsJsonObject("generator");
        JsonObject source = generator.has("biome_source") ? generator.getAsJsonObject("biome_source") : null;
        if (source != null && source.has("biomes")) {
            for (JsonElement e : source.getAsJsonArray("biomes")) {
                if (e.isJsonObject() && e.getAsJsonObject().has("parameters")) {
                    JsonObject entry = e.getAsJsonObject();
                    entry.addProperty("parameters", sha1(entry.get("parameters")));
                }
            }
        }
        return out;
    }

    static String sha1(JsonElement e) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(COMPACT.toJson(canonical(e)).getBytes(StandardCharsets.UTF_8));
            return "sha1:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonElement canonical(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            new TreeMap<>(e.getAsJsonObject().asMap()).forEach((k, v) -> out.add(k, canonical(v)));
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            e.getAsJsonArray().forEach(v -> out.add(canonical(v)));
            return out;
        }
        return e.deepCopy();
    }
}
