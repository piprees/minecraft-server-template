package com.customdimensions.facts;

import java.util.List;
import java.util.Map;

/**
 * Everything measured about one (dimension, seed), and nothing judged about it.
 *
 * <p>No score, no weight, no verdict. A criterion in the scoring layer consumes
 * these and says what it thinks; this layer only says what is there. The split
 * exists because the old design could not tell "this seed is poor" from "this
 * config is broken" from "we could not measure it" — all three arrived as one
 * number.
 *
 * <p>Every field is a {@link Measured}, so an unmeasurable fact is a stated
 * absence with a reason rather than a zero that reads like a measurement.
 */
public record SeedFacts(
        String dimension,
        long seed,
        String measuredAt,
        String configFingerprint,
        int playableRadius,
        SpawnFacts spawn,
        BiomeFacts biomes,
        TerrainFacts terrain,
        StructureFacts structures) {

    /** Where the player starts, and what is there. */
    public record SpawnFacts(
            Measured<String> biome,
            Measured<Integer> surfaceHeight,
            Measured<Double> localRelief,
            Measured<Boolean> aboveSeaLevel) {
    }

    /**
     * What the biome layout is over the playable disc.
     *
     * @param shares          biome id -> fraction of sampled cells, summing to 1
     * @param distinctCount   how many biomes appear at all
     * @param headlineShare   the largest single share
     * @param edgeDensity     fraction of adjacent sample pairs that differ —
     *                        a mosaic scores high, two hemispheres score low.
     *                        This is what tells a varied world from a bisected
     *                        one, which a biome COUNT cannot.
     */
    public record BiomeFacts(
            Measured<Map<String, Double>> shares,
            Measured<Integer> distinctCount,
            Measured<Double> headlineShare,
            Measured<Double> edgeDensity) {
    }

    /**
     * The shape of the ground.
     *
     * @param relief    max minus min surface height over the sampled grid
     * @param grain     mean absolute height difference between adjacent samples
     *                  — relief says how tall, grain says how choppy, and a
     *                  plateau and a spike field can share a relief
     * @param waterFraction fraction of samples at or below sea level
     */
    public record TerrainFacts(
            Measured<Double> relief,
            Measured<Double> grain,
            Measured<Double> waterFraction,
            Measured<Integer> minHeight,
            Measured<Integer> maxHeight) {
    }

    /**
     * What is placed, where.
     *
     * @param pool               structure id -> its summed weight in the pool.
     *                           What COULD be placed, as distinct from what
     *                           was. Without it, a pool difference and a pick
     *                           difference look identical from the outside,
     *                           which is exactly the ambiguity that made the
     *                           first parity failure hard to localise.
     * @param byGroup            group -> how many positions
     * @param byStructure        structure id -> how many positions assigned
     * @param nearestByStructure structure id -> distance in blocks from spawn
     * @param clustering         mean nearest-neighbour distance divided by the
     *                           distance a uniform scatter of the same count
     *                           would give. Below 1 means pockets (places);
     *                           at or above 1 means even spread (noise). This
     *                           is orthogonal to the radial shape, which is
     *                           why counting positions cannot answer it.
     * @param nearestHostile     blocks from spawn to the nearest dungeons or
     *                           endgame placement
     */
    public record StructureFacts(
            Measured<Map<String, Integer>> pool,
            Measured<Map<String, Integer>> byGroup,
            Measured<Map<String, Integer>> byStructure,
            Measured<Map<String, Double>> nearestByStructure,
            Measured<Double> clustering,
            Measured<Double> nearestHostile,
            Measured<Integer> totalPositions) {
    }

    // ------------------------------------------------------------------ json

    public String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n \"schemaVersion\": 1,\n");
        b.append(" \"dimension\": ").append(Json.quote(dimension)).append(",\n");
        b.append(" \"seed\": ").append(seed).append(",\n");
        b.append(" \"measuredAt\": ").append(Json.quote(measuredAt)).append(",\n");
        b.append(" \"configFingerprint\": ")
                .append(Json.quote(configFingerprint)).append(",\n");
        b.append(" \"playableRadius\": ").append(playableRadius).append(",\n");

        b.append(" \"spawn\": {\n");
        field(b, "biome", spawn.biome().toJson(Json::quote), true);
        field(b, "surfaceHeight", spawn.surfaceHeight().toJson(v -> Json.number((long) v)), true);
        field(b, "localRelief", spawn.localRelief().toJson(Json::number), true);
        field(b, "aboveSeaLevel", spawn.aboveSeaLevel().toJson(String::valueOf), false);
        b.append(" },\n");

        b.append(" \"biomes\": {\n");
        field(b, "shares", biomes.shares().toJson(SeedFacts::doubleMap), true);
        field(b, "distinctCount",
                biomes.distinctCount().toJson(v -> Json.number((long) v)), true);
        field(b, "headlineShare", biomes.headlineShare().toJson(Json::number), true);
        field(b, "edgeDensity", biomes.edgeDensity().toJson(Json::number), false);
        b.append(" },\n");

        b.append(" \"terrain\": {\n");
        field(b, "relief", terrain.relief().toJson(Json::number), true);
        field(b, "grain", terrain.grain().toJson(Json::number), true);
        field(b, "waterFraction", terrain.waterFraction().toJson(Json::number), true);
        field(b, "minHeight", terrain.minHeight().toJson(v -> Json.number((long) v)), true);
        field(b, "maxHeight", terrain.maxHeight().toJson(v -> Json.number((long) v)), false);
        b.append(" },\n");

        b.append(" \"structures\": {\n");
        field(b, "pool", structures.pool().toJson(SeedFacts::intMap), true);
        field(b, "byGroup", structures.byGroup().toJson(SeedFacts::intMap), true);
        field(b, "byStructure", structures.byStructure().toJson(SeedFacts::intMap), true);
        field(b, "nearestByStructure",
                structures.nearestByStructure().toJson(SeedFacts::doubleMap), true);
        field(b, "clustering", structures.clustering().toJson(Json::number), true);
        field(b, "nearestHostile", structures.nearestHostile().toJson(Json::number), true);
        field(b, "totalPositions",
                structures.totalPositions().toJson(v -> Json.number((long) v)), false);
        b.append(" }\n}\n");
        return b.toString();
    }

    private static void field(StringBuilder b, String name, String json, boolean comma) {
        b.append("  ").append(Json.quote(name)).append(": ").append(json)
                .append(comma ? ",\n" : "\n");
    }

    private static String doubleMap(Map<String, Double> m) {
        StringBuilder b = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Double> e : new java.util.TreeMap<>(m).entrySet()) {
            if (i++ > 0) {
                b.append(", ");
            }
            b.append(Json.quote(e.getKey())).append(": ").append(Json.number(e.getValue()));
        }
        return b.append('}').toString();
    }

    private static String intMap(Map<String, Integer> m) {
        StringBuilder b = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(m).entrySet()) {
            if (i++ > 0) {
                b.append(", ");
            }
            b.append(Json.quote(e.getKey())).append(": ").append(e.getValue().intValue());
        }
        return b.append('}').toString();
    }

    /** Every absent fact in this record, as "path: reason". For the summary. */
    public List<String> absences() {
        List<String> out = new java.util.ArrayList<>();
        absent(out, "spawn.biome", spawn.biome());
        absent(out, "spawn.surfaceHeight", spawn.surfaceHeight());
        absent(out, "spawn.localRelief", spawn.localRelief());
        absent(out, "spawn.aboveSeaLevel", spawn.aboveSeaLevel());
        absent(out, "biomes.shares", biomes.shares());
        absent(out, "biomes.distinctCount", biomes.distinctCount());
        absent(out, "biomes.headlineShare", biomes.headlineShare());
        absent(out, "biomes.edgeDensity", biomes.edgeDensity());
        absent(out, "terrain.relief", terrain.relief());
        absent(out, "terrain.grain", terrain.grain());
        absent(out, "terrain.waterFraction", terrain.waterFraction());
        absent(out, "terrain.minHeight", terrain.minHeight());
        absent(out, "terrain.maxHeight", terrain.maxHeight());
        absent(out, "structures.pool", structures.pool());
        absent(out, "structures.byGroup", structures.byGroup());
        absent(out, "structures.byStructure", structures.byStructure());
        absent(out, "structures.nearestByStructure", structures.nearestByStructure());
        absent(out, "structures.clustering", structures.clustering());
        absent(out, "structures.nearestHostile", structures.nearestHostile());
        absent(out, "structures.totalPositions", structures.totalPositions());
        return out;
    }

    private static void absent(List<String> out, String path, Measured<?> m) {
        if (!m.isPresent()) {
            out.add(path + ": " + m.reason());
        }
    }
}
