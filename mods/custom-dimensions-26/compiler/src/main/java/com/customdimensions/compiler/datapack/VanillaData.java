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
    /** The data generator's registry report: every built-in registry with its entries. */
    public static final String REGISTRIES_REPORT = "reports/registries.json";
    /** Datapack folders under {@code data/minecraft/} whose file names are ids. */
    static final List<String> DATA_FOLDERS = List.of("dimension_type", "timeline", "worldgen/biome",
            "worldgen/block_state_provider", "worldgen/carver", "worldgen/density_function", "worldgen/feature",
            "worldgen/material_condition", "worldgen/material_rule", "worldgen/noise", "worldgen/noise_settings",
            "worldgen/placed_feature", "worldgen/processor_list", "worldgen/structure", "worldgen/structure_set",
            "worldgen/template_pool");
    /** Tag folders under {@code data/minecraft/tags/} a worldgen file can name. */
    static final List<String> TAG_FOLDERS = List.of("block", "fluid", "item", "entity_type", "game_event",
            "worldgen/biome", "worldgen/structure");

    private final VanillaSource source;
    private final Map<String, JsonElement> cache = new HashMap<>();
    private Set<String> biomeIds;
    private Map<String, Set<String>> registries;

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

    /** A material rule by id ({@code minecraft:overworld/surface}): {@code data/minecraft/worldgen/material_rule/<path>.json}. */
    public JsonObject materialRule(String id) {
        return object("data/minecraft/worldgen/material_rule/" + path(id) + ".json");
    }

    /** Whether vanilla has the placed feature {@code id}. */
    public boolean hasPlacedFeature(String id) {
        return id.startsWith("minecraft:")
                && source.read("data/minecraft/worldgen/placed_feature/" + path(id) + ".json").isPresent();
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

    /**
     * The ids in the built-in registry {@code registry} ({@code minecraft:block},
     * {@code minecraft:particle_type}, {@code minecraft:worldgen/feature_type}),
     * from {@link #REGISTRIES_REPORT}; empty when vanilla has no such registry.
     */
    public Set<String> registry(String registry) {
        return registries().getOrDefault(registry, Set.of());
    }

    private Map<String, Set<String>> registries() {
        if (registries == null) {
            Map<String, Set<String>> out = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : object(REGISTRIES_REPORT).entrySet()) {
                out.put(e.getKey(), Set.copyOf(e.getValue().getAsJsonObject().getAsJsonObject("entries").keySet()));
            }
            registries = Map.copyOf(out);
        }
        return registries;
    }

    /** Whether vanilla has a {@code data/minecraft/<folder>/<path>.json} for the {@code minecraft:} id. */
    public boolean hasFile(String folder, String id) {
        return id.startsWith("minecraft:") && source.read("data/minecraft/" + folder + "/" + path(id) + ".json").isPresent();
    }

    /**
     * Whether a {@code minecraft:} reference names something vanilla has: a
     * built-in registry entry, a datapack file in {@link #DATA_FOLDERS}, or a
     * tag (with or without its {@code #}) in {@link #TAG_FOLDERS}.
     */
    public boolean knows(String ref) {
        boolean tag = ref.startsWith("#");
        String id = tag ? ref.substring(1) : ref;
        if (!id.startsWith("minecraft:")) return false;
        if (!tag) {
            if (registries().values().stream().anyMatch(ids -> ids.contains(id))) return true;
            if (DATA_FOLDERS.stream().anyMatch(folder -> hasFile(folder, id))) return true;
        }
        return TAG_FOLDERS.stream().anyMatch(folder -> hasFile("tags/" + folder, id));
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
