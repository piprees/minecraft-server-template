package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Read access to the vanilla files the compiler starts from: the server jar's
 * {@code data/minecraft/**} and the data generator's {@code reports/}, both
 * extracted by {@code :compiler:vanillaData}. Every getter returns a copy.
 */
public final class VanillaData {

    private final Path root;
    private final Map<Path, JsonElement> cache = new HashMap<>();
    private Set<String> biomeIds;

    public VanillaData(Path root) {
        this.root = root;
        if (!Files.isRegularFile(root.resolve("data/minecraft/dimension_type/overworld.json"))
                || !Files.isDirectory(root.resolve("reports/biome_parameters"))) {
            throw new IllegalArgumentException(root + " holds no vanilla data; run ./gradlew :compiler:vanillaData");
        }
    }

    public Path root() {
        return root;
    }

    /** {@code data/minecraft/dimension_type/<name>.json}. */
    public JsonObject dimensionType(String name) {
        return object("data/minecraft/dimension_type/" + name + ".json");
    }

    /** {@code data/minecraft/worldgen/noise_settings/<name>.json}. */
    public JsonObject noiseSettings(String name) {
        return object("data/minecraft/worldgen/noise_settings/" + name + ".json");
    }

    /** The entries of {@code reports/biome_parameters/minecraft/<list>.json}, in vanilla's order. */
    public JsonArray biomeParameters(String list) {
        return object("reports/biome_parameters/minecraft/" + list + ".json").getAsJsonArray("biomes");
    }

    /** Every vanilla biome id, from {@code data/minecraft/worldgen/biome/}. */
    public Set<String> biomeIds() {
        if (biomeIds == null) {
            Set<String> ids = new TreeSet<>();
            try (Stream<Path> files = Files.list(root.resolve("data/minecraft/worldgen/biome"))) {
                files.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".json"))
                        .forEach(n -> ids.add("minecraft:" + n.substring(0, n.length() - 5)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
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
        Path file = root.resolve(relative);
        JsonElement parsed = cache.computeIfAbsent(file, f -> {
            try {
                return JsonParser.parseString(Files.readString(f));
            } catch (IOException e) {
                throw new UncheckedIOException("vanilla file missing: " + f, e);
            }
        });
        return parsed.getAsJsonObject().deepCopy();
    }
}
