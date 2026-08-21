package com.customdimensions.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * {@code {"absent": "..."}} into a zero would let a guess masquerade as a
 * measurement at the one boundary the {@link Measured} type cannot police.
 *
 * <p>Reading is parsing, not policy. The record carries the {@code stackVersion}
 * that measured it; whether a record from another release may be used is the
 * caller's decision, taken where the running version is known. There is no
 * backwards compatibility: a record from any other release is deleted and
 * re-measured, never adapted.
 */
public final class SeedFactsCodec {

    private SeedFactsCodec() {
    }

    public static SeedFacts read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject spawn = obj(root, "spawn");
        JsonObject biomes = obj(root, "biomes");
        JsonObject terrain = obj(root, "terrain");
        JsonObject structures = obj(root, "structures");

        return new SeedFacts(
                root.get("stackVersion").getAsString(),
                root.get("dimension").getAsString(),
                root.get("seed").getAsLong(),
                root.get("measuredAt").getAsString(),
                root.get("configFingerprint").getAsString(),
                root.get("playableRadius").getAsInt(),
                new SeedFacts.SpawnFacts(
                        measured(spawn, "column", SeedFactsCodec::column),
                        measured(spawn, "biome", JsonElement::getAsString),
                        measured(spawn, "surfaceHeight", JsonElement::getAsInt),
                        measured(spawn, "localRelief", JsonElement::getAsDouble),
                        measured(spawn, "aboveSeaLevel", JsonElement::getAsBoolean),
                        measured(spawn, "nearbyGround", SeedFactsCodec::groundKindList),
                        measured(spawn, "safeColumnFraction", JsonElement::getAsDouble)),
                new SeedFacts.BiomeFacts(
                        measured(biomes, "shares", SeedFactsCodec::doubleMap),
                        measured(biomes, "distinctCount", JsonElement::getAsInt),
                        measured(biomes, "headlineShare", JsonElement::getAsDouble),
                        measured(biomes, "edgeDensityNearSpawn", JsonElement::getAsDouble)),
                new SeedFacts.TerrainFacts(
                        measured(terrain, "groundFraction", JsonElement::getAsDouble),
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
                        measured(structures, "clusteringByGroup", SeedFactsCodec::doubleMap),
                        measured(structures, "clustering", JsonElement::getAsDouble),
                        measured(structures, "nearestHostile", JsonElement::getAsDouble),
                        measured(structures, "totalPositions", JsonElement::getAsInt),
                        measured(structures, "tagMembers", SeedFactsCodec::stringListMap),
                        measured(structures, "passThroughByStructure", SeedFactsCodec::intMap),
                        measured(structures, "passThroughNearestByStructure", SeedFactsCodec::doubleMap),
                        measured(structures, "passThroughUnmodelledSets",
                                e -> stringList(e.getAsJsonArray()))),
                measured(root, "grid", SeedFactsCodec::grid));
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

    /**
     * A spawn column. {@code declared} is read, never defaulted: a record that
     * lost it would say a measurement site was chosen deliberately when the
     * origin was used for want of one.
     */
    private static SeedFacts.Column column(JsonElement element) {
        JsonObject o = element.getAsJsonObject();
        return new SeedFacts.Column(o.get("x").getAsInt(), o.get("z").getAsInt(),
                o.get("declared").getAsBoolean());
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

    private static Map<String, List<String>> stringListMap(JsonElement element) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            out.put(e.getKey(), stringList(e.getValue().getAsJsonArray()));
        }
        return out;
    }

    private static List<String> stringList(JsonArray array) {
        List<String> out = new ArrayList<>(array.size());
        for (JsonElement e : array) {
            out.add(e.getAsString());
        }
        return out;
    }

    private static List<Integer> intList(JsonArray array) {
        List<Integer> out = new ArrayList<>(array.size());
        for (JsonElement e : array) {
            out.add(e.isJsonNull() ? null : e.getAsInt());
        }
        return out;
    }

    private static SeedFacts.Grid grid(JsonElement element) {
        JsonObject o = element.getAsJsonObject();
        return new SeedFacts.Grid(
                o.get("side").getAsInt(),
                stringList(o.getAsJsonArray("biomeIds")),
                intList(o.getAsJsonArray("biome")),
                intList(o.getAsJsonArray("height")),
                o.get("sampled").getAsInt(),
                o.get("heightMeasured").getAsInt());
    }

    private static List<SeedFacts.GroundKind> groundKindList(JsonElement element) {
        List<SeedFacts.GroundKind> out = new ArrayList<>();
        for (JsonElement e : element.getAsJsonArray()) {
            out.add(SeedFacts.GroundKind.valueOf(e.getAsString()));
        }
        return out;
    }
}
