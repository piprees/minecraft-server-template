package com.customdimensions.facts;

import java.util.List;
import java.util.Map;

/**
 * Everything measured about one (dimension, seed), and nothing judged about it.
 *
 * <p>No score, no weight, no verdict. A criterion in the scoring layer consumes
 * these and says what it thinks; this layer only says what is there. The split
 * keeps "this seed is poor", "this config is broken" and "we could not
 * measure it" as three distinguishable facts rather than one number that
 * conflates them.
 *
 * <p>Every field is a {@link Measured}, so an unmeasurable fact is a stated
 * absence with a reason rather than a zero that reads like a measurement.
 *
 * @param grid absent means this run could not sample a grid at all (the
 *             generator failed to build) — every dimension has one.
 */
public record SeedFacts(
        String stackVersion,
        String dimension,
        long seed,
        String measuredAt,
        String configFingerprint,
        int playableRadius,
        SpawnFacts spawn,
        BiomeFacts biomes,
        TerrainFacts terrain,
        StructureFacts structures,
        Measured<Grid> grid) {

    /**
     * Where the player starts, and what is there.
     *
     * @param column the column the four facts below were measured at, and
     *               whether the dimension declared it. Where a fact was measured
     *               is itself a fact: the measurement site is only derivable from
     *               the merged config (platform layer plus consumer overlay), and
     *               either layer alone gives a different answer.
     */
    public record SpawnFacts(
            Measured<Column> column,
            Measured<String> biome,
            Measured<Integer> surfaceHeight,
            Measured<Double> localRelief,
            Measured<Boolean> aboveSeaLevel) {
    }

    /**
     * A horizontal position, and where it came from.
     *
     * @param declared true when the dimension config names this spawn, false
     *                 when it names none and the origin stands in. A dimension
     *                 declaring {@code [0, 64, 0]} shares the coordinates and
     *                 not the meaning.
     */
    public record Column(int x, int z, boolean declared) {
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
     *                           was. Without it a pool difference and a pick
     *                           difference are indistinguishable from outside.
     * @param byGroup            group -> how many positions
     * @param byStructure        structure id -> how many positions assigned
     * @param nearestByStructure structure id -> distance in blocks from spawn
     * @param clusteringByGroup  group -> Clark-Evans for THAT group's placements
     *                           alone. Each group is an independent point process
     *                           — its own noise field, frequency and exclusion
     *                           radius — so this is the granularity at which
     *                           pockets exist. Superimposing several independent
     *                           processes drives the combined statistic toward
     *                           that of a random scatter whatever the parts look
     *                           like, so a pocketed group is invisible in the
     *                           pooled figure.
     * @param clustering          the same statistic over every group at once: a
     *                           fact about all structures taken together, and the
     *                           wrong input for a question about pockets.
     * @param nearestHostile     blocks from spawn to the nearest dungeons or
     *                           endgame placement
     */
    public record StructureFacts(
            Measured<Map<String, Integer>> pool,
            Measured<Map<String, Integer>> byGroup,
            Measured<Map<String, Integer>> byStructure,
            Measured<Map<String, Double>> nearestByStructure,
            Measured<Map<String, Double>> clusteringByGroup,
            Measured<Double> clustering,
            Measured<Double> nearestHostile,
            Measured<Integer> totalPositions) {
    }

    /**
     * The sampled biome+height grid over the playable disc — persisted so a
     * consumer (a render, a re-derived aggregate) can see the world again
     * without re-measuring it.
     *
     * <p>{@code biome} and {@code height} are row-major, {@code side * side}
     * long, indexed {@code z * side + x} — the same layout and cell count
     * {@code FactsEngine.sampleGrid} already samples in, at
     * {@code step = playableRadius * 2 / (side - 1)} blocks between adjacent
     * cells, centred on the world origin. {@code null} in either array means
     * unmeasured — outside the playable disc, or sampled without an answer —
     * and a real height of exactly zero survives as {@code 0}, never as
     * {@code null}. {@code side} is stored explicitly rather than assumed
     * from the live {@code FactsEngine.GRID} constant, which can change
     * under a file written under an earlier one.
     *
     * @param side      cells per row and column (odd, so the centre cell is
     *                  the grid's own centre)
     * @param biomeIds  distinct biome ids appearing anywhere in the grid,
     *                  indexed by {@code biome}
     * @param biome     index into {@code biomeIds} per cell, or null
     * @param height    surface height per cell, or null
     */
    public record Grid(
            int side,
            List<String> biomeIds,
            List<Integer> biome,
            List<Integer> height) {
    }

    // ------------------------------------------------------------------ json

    public String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n \"stackVersion\": ").append(Json.quote(stackVersion)).append(",\n");
        b.append(" \"dimension\": ").append(Json.quote(dimension)).append(",\n");
        b.append(" \"seed\": ").append(seed).append(",\n");
        b.append(" \"measuredAt\": ").append(Json.quote(measuredAt)).append(",\n");
        b.append(" \"configFingerprint\": ")
                .append(Json.quote(configFingerprint)).append(",\n");
        b.append(" \"playableRadius\": ").append(playableRadius).append(",\n");

        b.append(" \"spawn\": {\n");
        field(b, "column", spawn.column().toJson(SeedFacts::column), true);
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
        field(b, "clusteringByGroup",
                structures.clusteringByGroup().toJson(SeedFacts::doubleMap), true);
        field(b, "clustering", structures.clustering().toJson(Json::number), true);
        field(b, "nearestHostile", structures.nearestHostile().toJson(Json::number), true);
        field(b, "totalPositions",
                structures.totalPositions().toJson(v -> Json.number((long) v)), false);
        b.append(" },\n");

        b.append(" \"grid\": ").append(grid.toJson(SeedFacts::grid)).append("\n}\n");
        return b.toString();
    }

    private static void field(StringBuilder b, String name, String json, boolean comma) {
        b.append("  ").append(Json.quote(name)).append(": ").append(json)
                .append(comma ? ",\n" : "\n");
    }

    private static String column(Column c) {
        return "{\"x\": " + c.x() + ", \"z\": " + c.z()
                + ", \"declared\": " + c.declared() + "}";
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

    private static String stringList(List<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(Json.quote(values.get(i)));
        }
        return b.append(']').toString();
    }

    private static String intList(List<Integer> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            Integer v = values.get(i);
            b.append(v == null ? "null" : v.toString());
        }
        return b.append(']').toString();
    }

    private static String grid(Grid g) {
        return "{\"side\": " + g.side()
                + ", \"biomeIds\": " + stringList(g.biomeIds())
                + ", \"biome\": " + intList(g.biome())
                + ", \"height\": " + intList(g.height())
                + "}";
    }

    /** Every absent fact in this record, as "path: reason". For the summary. */
    public List<String> absences() {
        List<String> out = new java.util.ArrayList<>();
        absent(out, "spawn.column", spawn.column());
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
        absent(out, "grid", grid);
        return out;
    }

    private static void absent(List<String> out, String path, Measured<?> m) {
        if (!m.isPresent()) {
            out.add(path + ": " + m.reason());
        }
    }
}
