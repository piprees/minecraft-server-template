package com.customdimensions.compiler.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stacked island tiers and flat tops on the terrains over a void, from their shapes sampled at columns
 * whose library noises are given: the open gap between tiers, each tier's altitude, the place cue and island
 * top a y reads, and the flat share of a top.
 */
class SkyTiersTest {

    private static final String PREFIX = "adventure:terrain/t";
    private static final String ISLANDS = "adventure:terrain/islands", ALTITUDE = "adventure:terrain/altitude", DETAIL = "adventure:terrain/detail";
    private static final int MIN_Y = 0, MAX_Y = 256;

    private record Built(TerrainFamily.Shape shape, Map<String, JsonElement> named) {}

    private static Built build(String json) {
        TerrainSpec spec = TerrainLibraryTest.spec(json);
        Ctx ctx = new Ctx(spec, "adventure", PREFIX, MIN_Y, MIN_Y, MAX_Y);
        TerrainFamily.Shape shape = spec.family().build(ctx);
        return new Built(shape, new HashMap<>(ctx.namedFunctions()));
    }

    /** A column: each noise's value by id and, where one id is read at several frequencies, by id at {@code xz_scale}. */
    private record Column(Map<String, Double> noises) {
        double noise(String id, double xzScale) {
            Double v = noises.get(id + "@" + Math.round(xzScale * 10000) / 10000.0);
            return v != null ? v : noises.getOrDefault(id, 0.0);
        }
    }

    private static double eval(Built b, JsonElement f, Column column, double y) {
        if (f.isJsonPrimitive() && f.getAsJsonPrimitive().isNumber()) return f.getAsDouble();
        if (f.isJsonPrimitive()) {
            String id = f.getAsString();
            JsonElement named = id.startsWith(PREFIX + "/") ? b.named().get(id.substring(PREFIX.length() + 1)) : null;
            if (named == null) throw new IllegalArgumentException("no function " + id);
            return eval(b, named, column, y);
        }
        JsonObject o = f.getAsJsonObject();
        String type = o.get("type").getAsString();
        return switch (type) {
            case "minecraft:add" -> eval(b, o.get("left"), column, y) + eval(b, o.get("right"), column, y);
            case "minecraft:sub" -> eval(b, o.get("left"), column, y) - eval(b, o.get("right"), column, y);
            case "minecraft:mul" -> eval(b, o.get("left"), column, y) * eval(b, o.get("right"), column, y);
            case "minecraft:min" -> Math.min(eval(b, o.get("left"), column, y), eval(b, o.get("right"), column, y));
            case "minecraft:max" -> Math.max(eval(b, o.get("left"), column, y), eval(b, o.get("right"), column, y));
            case "minecraft:sqrt" -> Math.sqrt(eval(b, o.get("input"), column, y));
            case "minecraft:floor" -> Math.floor(eval(b, o.get("input"), column, y));
            case "minecraft:cache" -> eval(b, o.get("input"), column, y);
            case "minecraft:clamp" -> Math.max(o.get("min").getAsDouble(), Math.min(o.get("max").getAsDouble(), eval(b, o.get("input"), column, y)));
            case "minecraft:lerp" -> {
                double a = eval(b, o.get("alpha"), column, y);
                yield eval(b, o.get("first"), column, y) + a * (eval(b, o.get("second"), column, y) - eval(b, o.get("first"), column, y));
            }
            case "minecraft:noise" -> column.noise(o.get("noise").getAsString(), o.get("xz_scale").getAsDouble());
            case "minecraft:gradient" -> {
                double from = o.get("from_coordinate").getAsDouble(), to = o.get("to_coordinate").getAsDouble();
                double t = Math.max(0, Math.min(1, (y - from) / (to - from)));
                yield o.get("from_value").getAsDouble() + t * (o.get("to_value").getAsDouble() - o.get("from_value").getAsDouble());
            }
            case "minecraft:spline" -> {
                JsonObject s = o.getAsJsonObject("spline");
                double x = eval(b, s.get("coordinate"), column, y);
                JsonArray p = s.getAsJsonArray("points");
                int n = p.size();
                if (x <= loc(p, 0)) yield val(p, 0);
                if (x >= loc(p, n - 1)) yield val(p, n - 1);
                int i = 0;
                while (x > loc(p, i + 1)) i++;
                double t = (x - loc(p, i)) / (loc(p, i + 1) - loc(p, i));
                yield val(p, i) + (val(p, i + 1) - val(p, i)) * t * t * (3 - 2 * t);
            }
            default -> throw new IllegalArgumentException("unexpected " + type);
        };
    }

    private static double loc(JsonArray p, int i) {
        return p.get(i).getAsJsonObject().get("location").getAsDouble();
    }

    private static double val(JsonArray p, int i) {
        return p.get(i).getAsJsonObject().get("value").getAsDouble();
    }

    /** The solid runs of a column, {@code {bottom, top}} in blocks, sampled every quarter block. */
    private static List<double[]> runs(Built b, Column column) {
        List<double[]> runs = new ArrayList<>();
        double start = Double.NaN;
        for (double y = MIN_Y; y <= MAX_Y; y += 0.25) {
            boolean solid = eval(b, b.shape().plus(), column, y) > 0;
            if (solid && Double.isNaN(start)) start = y;
            if (!solid && !Double.isNaN(start)) {
                runs.add(new double[] {start, y});
                start = Double.NaN;
            }
        }
        return runs;
    }

    private static final double SIGMA = Noises.sigma("islands");

    /** The islands noise value at which a layer of {@code coverage} has core {@code core} (0 at its edge, 1 at its heart). */
    private static double islands(double coverage, double core) {
        return Noises.threshold(coverage, SIGMA) + core * SIGMA;
    }

    private static Column column(Object... pairs) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Double) pairs[i + 1]);
        return new Column(m);
    }

    private static final String TWO = "{\"base\": \"floating_islands\", \"params\": {\"tiers\": 2, \"altitude\": 100, \"tierSpacing\": 60, "
            + "\"thickness\": 20, \"underside\": 40, \"coverage\": 0.3, \"size\": 1, \"tierShrink\": 0.5, \"tierGap\": 20}}";
    /** Tier 1 of {@link #TWO}: size 0.5 and 1.31 times the frequency, coverage 0.15. */
    private static final String TIER1 = ISLANDS + "@" + Math.round(1.31 / 0.5 * 10000) / 10000.0;

    @Test
    void oneTierIsTheSingleLayerWithNoTierFunctions() {
        Built b = build("{\"base\": \"floating_islands\"}");
        assertTrue(b.named().isEmpty(), "no named functions: " + b.named().keySet());
        List<double[]> r = runs(b, column(ISLANDS, islands(0.3, 1)));
        assertEquals(1, r.size());
        assertEquals(150 + 18 - 2, r.get(0)[1], 0.3, "the dome's crown at altitude + thickness - 2");
    }

    @Test
    void aSecondTierFloatsTierSpacingAboveWithItsOwnThicknessAndAnOpenGap() {
        Built b = build(TWO);
        assertTrue(b.named().keySet().containsAll(List.of("tier0_top", "tier1_top", "tier1_bottom")), b.named().keySet().toString());
        List<double[]> r = runs(b, column(ISLANDS, islands(0.3, 1), TIER1, islands(0.15, 1)));
        assertEquals(2, r.size(), "an island stacked over an island");
        assertEquals(100 + 20 - 2, r.get(0)[1], 0.3, "lower crown");
        assertEquals(160 + 10 - 2, r.get(1)[1], 0.3, "upper crown: tier spacing 60, thickness halved");
        assertTrue(r.get(1)[0] - r.get(0)[1] >= 20 - 0.25, "gap " + (r.get(1)[0] - r.get(0)[1]));
    }

    @Test
    void theGapHoldsAtEveryCoreAndAltitudeAndCutsTheUpperUndersideBack() {
        Built b = build(TWO.replace("\"tierSpacing\": 60", "\"tierSpacing\": 40"));
        int stacked = 0;
        for (double lower : new double[] {0, 0.05, 0.3, 0.6, 1}) {
            for (double upper : new double[] {0, 0.05, 0.3, 0.6, 1}) {
                for (double alt : new double[] {-0.5, 0, 0.4}) {
                    List<double[]> r = runs(b, column(ISLANDS, islands(0.3, lower), TIER1, islands(0.15, upper), ALTITUDE, alt));
                    for (int i = 1; i < r.size(); i++) {
                        double gap = r.get(i)[0] - r.get(i - 1)[1];
                        assertTrue(gap >= 20 - 0.25, "gap " + gap + " at lower " + lower + " upper " + upper + " altitude " + alt);
                    }
                    if (r.size() == 2) stacked++;
                }
            }
        }
        assertTrue(stacked >= 6, "islands stack over islands at a spacing barely over the gap: " + stacked);
    }

    @Test
    void anUpperIslandAlsoHangsOverOpenVoid() {
        Built b = build(TWO);
        List<double[]> r = runs(b, column(ISLANDS, islands(0.3, -0.5), TIER1, islands(0.15, 1)));
        assertEquals(1, r.size());
        assertEquals(168, r.get(0)[1], 0.3);
        assertEquals(160 - 20 + 2, r.get(0)[0], 0.5, "its own underside, a tierShrink of 40 below its layer");
    }

    @Test
    void thePlaceCueAndIslandTopAreEachTiersOwnAboveTheGapsMidpoint() {
        Built b = build(TWO);
        Column overVoid = column(ISLANDS, islands(0.3, -0.5), TIER1, islands(0.15, 1));
        assertEquals(TerrainFamily.PLACE_VOID, eval(b, b.shape().place(), overVoid, 100), 1e-6, "under an upper island: the void below");
        assertEquals(TerrainFamily.PLACE_LAND, eval(b, b.shape().place(), overVoid, 170), 1e-6, "on the upper island");
        Column stacked = column(ISLANDS, islands(0.3, 1), TIER1, islands(0.15, 1));
        assertEquals(118, eval(b, b.shape().top(), stacked, 110), 0.01, "a lower underside measures from the lower top");
        assertEquals(168, eval(b, b.shape().top(), stacked, 150), 0.01, "an upper underside from the upper top");
        assertEquals(TerrainFamily.PLACE_LAND, eval(b, b.shape().place(), stacked, 110), 1e-6);
    }

    @Test
    void tierCoverageAndTierShrinkSetEachTiersIslands() {
        Built b = build(TWO.replace("\"tierShrink\": 0.5", "\"tierShrink\": 0.5, \"tierCoverage\": 0.6"));
        assertTrue(runs(b, column(ISLANDS, islands(0.3, -0.5), TIER1, islands(0.6, 0.5))).size() == 1, "tierCoverage sets the threshold");
        assertTrue(runs(b, column(ISLANDS, islands(0.3, -0.5), TIER1, islands(0.6, -0.1))).isEmpty());
    }

    @Test
    void fourTiersStackEachSmallerAndAllApart() {
        Built b = build("{\"base\": \"floating_islands\", \"params\": {\"tiers\": 4, \"altitude\": 60, \"tierSpacing\": 50, \"thickness\": 16, "
                + "\"tierShrink\": 1}}");
        Map<String, Double> m = new HashMap<>();
        m.put(ISLANDS, 2.0);
        for (int k = 1; k < 4; k++) m.put(ISLANDS + "@" + Math.round(Math.pow(1.31, k) * 10000) / 10000.0, 2.0);
        List<double[]> r = runs(b, new Column(m));
        assertEquals(4, r.size());
        for (int k = 0; k < 4; k++) assertEquals(60 + 50 * k + 16 - 2, r.get(k)[1], 0.3, "tier " + k);
    }

    @Test
    void basinIslandsStackPlainTiersClearOfItsRim() {
        Built b = build("{\"base\": \"basin_islands\", \"params\": {\"tiers\": 2, \"tierSpacing\": 40, \"tierGap\": 24, \"tierShrink\": 1}}");
        assertTrue(b.named().containsKey("tier0_top"));
        String t1 = ISLANDS + "@1.31";
        for (double lakes : new double[] {-1, 0.1, 0.2, 0.5}) {
            List<double[]> r = runs(b, column(ISLANDS, islands(0.3, 1), t1, islands(0.3, 1), "adventure:terrain/lakes", lakes));
            for (int i = 1; i < r.size(); i++) assertTrue(r.get(i)[0] - r.get(i - 1)[1] >= 24 - 0.25, "lakes " + lakes);
            assertFalse(r.isEmpty());
        }
    }

    // ---- flat tops ----------------------------------------------------------------------------------

    /** Share of an island's columns, core evenly from its edge to its heart, whose top lies within a block of the highest. */
    private static double flatShare(Built b) {
        List<Double> tops = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            tops.add(eval(b, b.shape().top(), column(ISLANDS, islands(0.3, i / 100.0)), 0));
        }
        double high = tops.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        return tops.stream().filter(t -> t >= high - 1).count() / 100.0;
    }

    @Test
    void flatnessTurnsTheDomeIntoATableWithAShortEdge() {
        double dome = flatShare(build("{\"base\": \"floating_islands\"}"));
        double half = flatShare(build("{\"base\": \"floating_islands\", \"params\": {\"flatness\": 0.5}}"));
        double table = flatShare(build("{\"base\": \"floating_islands\", \"params\": {\"flatness\": 1}}"));
        assertTrue(dome < 0.2, "a dome is flat only at its crown: " + dome);
        assertTrue(half > dome && half < table, dome + " < " + half + " < " + table);
        assertTrue(table >= 0.85, "a table is flat past its edge: " + table);
        Built b = build("{\"base\": \"floating_islands\", \"params\": {\"flatness\": 1}}");
        assertEquals(150 + 18 - 2, eval(b, b.shape().top(), column(ISLANDS, islands(0.3, 0.5)), 0), 1e-6);
    }

    @Test
    void terracesBreakATableIntoPlateauLevelsTerraceStepApart() {
        Built b = build("{\"base\": \"floating_islands\", \"params\": {\"flatness\": 1, \"terraces\": 3, \"terraceStep\": 8}}");
        double sd = Noises.sigma("detail");
        List<Long> levels = new ArrayList<>();
        for (double d = -2; d <= 2; d += 0.05) {
            double top = eval(b, b.shape().top(), column(ISLANDS, islands(0.3, 1), DETAIL, d * sd), 0);
            long level = Math.round(top);
            if (!levels.contains(level)) levels.add(level);
        }
        assertTrue(levels.containsAll(List.of(166L, 174L, 182L)), "three plateau levels 8 apart from the table at 166: " + levels);
        assertTrue(levels.stream().allMatch(l -> l >= 166 && l <= 182), "never above the top level: " + levels);
        int flat = 0, n = 0;
        for (double d = -1.5; d <= 1.5; d += 0.01, n++) {
            double a = eval(b, b.shape().top(), column(ISLANDS, islands(0.3, 1), DETAIL, d * sd), 0);
            if (Math.abs(a - Math.round((a - 166) / 8) * 8 - 166) < 0.5) flat++;
        }
        assertTrue(flat >= 0.75 * n, "most of a terraced top is on a level: " + flat + " of " + n);
    }

    // ---- validation ---------------------------------------------------------------------------------

    @Test
    void aStackThatCannotFitIsRefusedNamingTheDefinition() {
        TerrainSpec high = TerrainLibraryTest.spec("{\"base\": \"floating_islands\", \"params\": {\"tiers\": 3, \"altitude\": 200, \"tierSpacing\": 64}}");
        assertEquals("t: terrain.params.tiers 3 at tierSpacing 64 from altitude 200 lift the top tier's islands to y 334, above the dimension's top at 320",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.compile(high, "overworld", null)).getMessage());
        TerrainSpec tight = TerrainLibraryTest.spec("{\"base\": \"floating_islands\", \"params\": {\"tiers\": 2, \"tierSpacing\": 40, \"tierGap\": 36}}");
        assertEquals("t: terrain.params.tierSpacing 40 leaves no room for a tier's islands over tierGap 36; it needs at least 44",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.compile(tight, "overworld", null)).getMessage());
        assertEquals("t: terrain.params.tiers = 2.5 must be a whole number",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.spec("{\"base\": \"floating_islands\", \"params\": {\"tiers\": 2.5}}")).getMessage());
        assertEquals("t: terrain.params.tiers = 5 is outside [1, 4]",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.spec("{\"base\": \"floating_islands\", \"params\": {\"tiers\": 5}}")).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.spec("{\"base\": \"basin_islands\", \"params\": {\"flatness\": 1}}"))
                .getMessage().startsWith("t: terrain.params.flatness is not a parameter of basin_islands"));
        TerrainLibraryTest.compile(TerrainLibraryTest.spec("{\"base\": \"floating_islands\", \"params\": {\"tiers\": 3, \"altitude\": 90, \"tierSpacing\": 64, "
                + "\"flatness\": 1, \"terraces\": 4, \"terraceStep\": 8}}"), "floating_islands", null);
    }
}
