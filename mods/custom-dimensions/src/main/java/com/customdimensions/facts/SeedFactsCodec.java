package com.customdimensions.facts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads back what {@link SeedFacts#toJson()} wrote.
 *
 * <p>A written artefact nobody can parse is a log line with brackets. Every
 * consumer downstream of this layer — the viewer, the checkers, a future
 * comparison across runs — reads these files, so the writer's format is a
 * contract and this is the half that proves it.
 *
 * <p>The property worth having is {@code toJson(read(toJson(f))).equals(toJson(f))}
 * and, more strongly, that the parsed record equals the original. Both are
 * asserted in {@code SeedFactsCodecTest}. An absence must survive the trip as an
 * absence with its reason intact: a round trip that quietly turns
 * {@code {"absent": "..."}} into a zero would defeat D4 at the file boundary,
 * which is the one place the type system stops helping.
 */
public final class SeedFactsCodec {

    private SeedFactsCodec() {
    }

    public static SeedFacts read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int version = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 0;
        if (version != 1) {
            throw new IllegalArgumentException(
                    "unknown facts schemaVersion " + version + " — refusing to guess "
                    + "at a layout this build does not know");
        }

        JsonObject spawn = obj(root, "spawn");
        JsonObject biomes = obj(root, "biomes");
        JsonObject terrain = obj(root, "terrain");
        JsonObject structures = obj(root, "structures");

        return new SeedFacts(
                root.get("dimension").getAsString(),
                root.get("seed").getAsLong(),
                root.get("measuredAt").getAsString(),
                root.get("configFingerprint").getAsString(),
                root.get("playableRadius").getAsInt(),
                new SeedFacts.SpawnFacts(
                        measured(spawn, "biome", JsonElement::getAsString),
                        measured(spawn, "surfaceHeight", JsonElement::getAsInt),
                        measured(spawn, "localRelief", JsonElement::getAsDouble),
                        measured(spawn, "aboveSeaLevel", JsonElement::getAsBoolean)),
                new SeedFacts.BiomeFacts(
                        measured(biomes, "shares", SeedFactsCodec::doubleMap),
                        measured(biomes, "distinctCount", JsonElement::getAsInt),
                        measured(biomes, "headlineShare", JsonElement::getAsDouble),
                        measured(biomes, "edgeDensity", JsonElement::getAsDouble)),
                new SeedFacts.TerrainFacts(
                        measured(terrain, "relief", JsonElement::getAsDouble),
                        measured(terrain, "grain", JsonElement::getAsDouble),
                        measured(terrain, "waterFraction", JsonElement::getAsDouble),
                        measured(terrain, "minHeight", JsonElement::getAsInt),
                        measured(terrain, "maxHeight", JsonElement::getAsInt)),
                new SeedFacts.StructureFacts(
                        measured(structures, "pool", SeedFactsCodec::intMap),
                        measured(structures, "byGroup", SeedFactsCodec::intMap),
                        measured(structures, "byStructure", SeedFactsCodec::intMap),
                        measured(structures, "nearestByStructure", SeedFactsCodec::doubleMap),
                        measured(structures, "clustering", JsonElement::getAsDouble),
                        measured(structures, "nearestHostile", JsonElement::getAsDouble),
                        measured(structures, "totalPositions", JsonElement::getAsInt)));
    }

    private static JsonObject obj(JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonObject()) {
            throw new IllegalArgumentException("facts record has no " + name + " section");
        }
        return parent.getAsJsonObject(name);
    }

    /**
     * An absence is {@code {"absent": "reason"}} and anything else is a value.
     * A map-valued fact is also an object, so the discriminator has to be the
     * key, not the JSON type.
     */
    private static <T> Measured<T> measured(JsonObject parent, String name,
                                            Function<JsonElement, T> parse) {
        if (!parent.has(name)) {
            throw new IllegalArgumentException("facts record has no field " + name);
        }
        JsonElement element = parent.get(name);
        if (element.isJsonObject() && element.getAsJsonObject().has("absent")) {
            return Measured.absent(element.getAsJsonObject().get("absent").getAsString());
        }
        return Measured.of(parse.apply(element));
    }

    private static Map<String, Double> doubleMap(JsonElement element) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            out.put(e.getKey(), e.getValue().getAsDouble());
        }
        return out;
    }

    private static Map<String, Integer> intMap(JsonElement element) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            out.put(e.getKey(), e.getValue().getAsInt());
        }
        return out;
    }
}
