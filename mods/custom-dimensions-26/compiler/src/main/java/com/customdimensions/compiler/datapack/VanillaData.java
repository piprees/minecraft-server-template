package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read access to the vanilla files the compiler starts from: the server jar's
 * {@code data/minecraft/**} and the data generator's multi-noise parameter
 * lists, from a {@link VanillaSource}. Every getter returns a copy.
 */
public final class VanillaData {

    private static final String PROBE = "data/minecraft/dimension_type/overworld.json";
    private static final String PROBE_REPORT = "reports/biome_parameters/minecraft/overworld.json";

    private final VanillaSource source;
    private final Map<String, JsonElement> cache = new HashMap<>();
    private Set<String> biomeIds;

    /** The files {@code :compiler:vanillaData} extracted into {@code root}. */
    public VanillaData(Path root) {
        this(VanillaSource.directory(root));
    }

    public VanillaData(VanillaSource source) {
        this.source = source;
        if (source.read(PROBE).isEmpty() || source.read(PROBE_REPORT).isEmpty()) {
            throw new IllegalArgumentException(source.describe()
                    + " holds no vanilla data; run ./gradlew :compiler:vanillaData");
        }
    }

    public VanillaSource source() {
        return source;
    }

    /** {@code data/minecraft/dimension_type/<name>.json}. */
    public JsonObject dimensionType(String name) {
        return object("data/minecraft/dimension_type/" + name + ".json");
    }

    /** {@code data/minecraft/worldgen/noise_settings/<name>.json}. */
    public JsonObject noiseSettings(String name) {
        return object("data/minecraft/worldgen/noise_settings/" + name + ".json");
    }

    /** {@code data/minecraft/worldgen/density_function/<path>.json}. */
    public JsonObject densityFunction(String path) {
        return object("data/minecraft/worldgen/density_function/" + path + ".json");
    }

    /** The entries of {@code reports/biome_parameters/minecraft/<list>.json}, in vanilla's order. */
    public JsonArray biomeParameters(String list) {
        return object("reports/biome_parameters/minecraft/" + list + ".json").getAsJsonArray("biomes");
    }

    /** Every vanilla biome id, from {@code data/minecraft/worldgen/biome/}. */
    public Set<String> biomeIds() {
        if (biomeIds == null) {
            Set<String> ids = new TreeSet<>();
            source.list("data/minecraft/worldgen/biome").stream()
                    .filter(n -> n.endsWith(".json"))
                    .forEach(n -> ids.add("minecraft:" + n.substring(0, n.length() - 5)));
            biomeIds = Set.copyOf(ids);
        }
        return biomeIds;
    }

    /** A vanilla timeline by id ({@code minecraft:day}). */
    public JsonObject timeline(String id) {
        return object("data/minecraft/timeline/" + path(id) + ".json");
    }

    /**
     * The timeline ids a {@code timelines} value names, tags expanded in
     * order: {@code "#minecraft:in_overworld"}, a single id, or a list of either.
     */
    public List<String> expandTimelines(JsonElement value) {
        Set<String> out = new LinkedHashSet<>();
        expand(value, out);
        return new ArrayList<>(out);
    }

    private void expand(JsonElement value, Set<String> out) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement e : value.getAsJsonArray()) {
                expand(e, out);
            }
            return;
        }
        String ref = value.getAsString();
        if (ref.startsWith("#")) {
            JsonObject tag = object("data/minecraft/tags/timeline/" + path(ref.substring(1)) + ".json");
            for (JsonElement e : tag.getAsJsonArray("values")) {
                expand(e, out);
            }
        } else {
            out.add(ref);
        }
    }

    private static String path(String id) {
        if (!id.startsWith("minecraft:")) {
            throw new IllegalArgumentException("not a vanilla id: " + id);
        }
        return id.substring("minecraft:".length());
    }

    private JsonObject object(String relative) {
        JsonElement parsed = cache.computeIfAbsent(relative, path -> JsonParser.parseString(source.read(path)
                .orElseThrow(() -> new IllegalStateException("vanilla file missing: " + path + " in " + source.describe()))));
        return parsed.getAsJsonObject().deepCopy();
    }
}
