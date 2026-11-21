package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.terrain.PlaceField;
import com.customdimensions.core.survey.Placeness;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code biomeLayout.places}: the compiled place field sampled ({@link DfEval}) on the quart grid inside the
 * border, so each test reads the function the game reads.
 */
class PlaceLayoutTest {
    private static final String GENERATOR = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";
    private static final String SKY = "\"terrain\": {\"base\": \"floating_islands\"}";
    private static final int RADIUS = 512;
    /** Blocks between samples, and samples a side over the border. */
    private static final int STEP = 8;
    private static final int GRID = 2 * RADIUS / STEP;
    private static final List<String> FOUR = List.of("minecraft:plains", "minecraft:forest", "minecraft:desert", "minecraft:snowy_plains");
    private static final List<String> SEVEN = List.of("minecraft:plains", "minecraft:forest", "minecraft:taiga", "minecraft:savanna",
            "minecraft:desert", "minecraft:jungle", "minecraft:swamp");

    private static String list(List<String> ids) {
        StringBuilder b = new StringBuilder("\"biomes\": [");
        for (int i = 0; i < ids.size(); i++) b.append(i == 0 ? "" : ", ").append('"').append(ids.get(i)).append('"');
        return b.append(']').toString();
    }

    private static String body(String terrain, List<String> ids, String places, String extra) {
        return GENERATOR + (terrain == null ? "" : ", " + terrain) + ", " + list(ids) + ", \"borders\": {\"player\": " + RADIUS + "}"
                + ", \"biomeLayout\": {\"mode\": \"regions\"" + extra + ", \"places\": " + places + "}";
    }

    /** A compiled definition and its place field. */
    private record Field(DatapackCompiler.Output out, String slug, JsonElement place, JsonObject source, int places) {
        /** The place index at each quart cell of the border under {@code seed}, row {@code z}. */
        int[] labels(long seed) {
            DfEval eval = new DfEval(out.files(), seed);
            int[] labels = new int[GRID * GRID];
            for (int r = 0; r < GRID; r++) {
                for (int c = 0; c < GRID; c++) {
                    float v = eval.at(place, -RADIUS + STEP / 2 + c * STEP, -RADIUS + STEP / 2 + r * STEP);
                    labels[r * GRID + c] = (int) Math.round((v + 1) * places / 2 - 0.5);
                }
            }
            return labels;
        }

        double[] shares(long seed) {
            double[] s = new double[places];
            for (int l : labels(seed)) s[l] += 1.0 / (GRID * GRID);
            return s;
        }

        String note() {
            return String.join("\n", out.report().entry(slug).notes());
        }
    }

    private static Field field(String slug, String body) {
        DatapackCompiler.Output out = Fixtures.compile(slug, body);
        JsonObject settings = JsonParser.parseString(out.files().get("data/" + Fixtures.NAMESPACE + "/worldgen/noise_settings/" + slug + ".json"))
                .getAsJsonObject();
        JsonObject stem = null;
        for (Map.Entry<String, String> e : out.files().entrySet()) {
            if (e.getKey().endsWith("dimension/" + slug + ".json")) stem = JsonParser.parseString(e.getValue()).getAsJsonObject();
        }
        JsonObject source = stem.getAsJsonObject("generator").getAsJsonObject("biome_source");
        JsonElement place = settings.getAsJsonObject("noise_router").get("temperature");
        DfEval eval = new DfEval(out.files(), 1);
        JsonObject finalFn = eval.function(place.getAsString()).getAsJsonObject();
        double step = finalFn.getAsJsonObject("left").get("right").getAsDouble();
        return new Field(out, slug, place, source, (int) Math.round(2 / step));
    }

    /**
     * Each place is one region of at least a hectare, placeness's least region ({@link Placeness#SPECK_HA}), and
     * the specks (cells cut off where several places meet in a sharp corner) hold under 0.5% of the border.
     */
    private static void assertOneRegionEach(int[] labels, int n, String what) {
        Placeness.Regions regions = new Placeness(GRID, STEP, -RADIUS, -RADIUS).regions(labels);
        double speckCells = Placeness.SPECK_HA * 10_000 / (STEP * STEP);
        int[] counts = new int[n];
        int specks = 0;
        for (int i = 0; i < regions.count(); i++) {
            if (regions.areas()[i] >= speckCells) counts[regions.labels()[i]]++;
            else specks += regions.areas()[i];
        }
        for (int p = 0; p < n; p++) assertEquals(1, counts[p], what + ": place " + p + " forms " + counts[p] + " regions of a hectare or more");
        assertTrue(specks < 0.005 * labels.length, what + ": specks hold " + specks + " cells");
    }

    private static void assertShares(double[] shares, double[] expected, double tolerance, String what) {
        for (int p = 0; p < shares.length; p++) assertEquals(expected[p], shares[p], tolerance, what + ": place " + p);
    }

    private static double[] even(int n) {
        double[] e = new double[n];
        java.util.Arrays.fill(e, 1.0 / n);
        return e;
    }

    @Test
    void fourPlacesAreOneRegionEachHoldingTheirShares() {
        Field f = field("p", body(null, FOUR, "{\"warp\": {\"blocks\": 0}}", ""));
        assertEquals(4, f.places());
        assertTrue(f.source().getAsJsonObject("regions").get("places").getAsBoolean());
        for (long seed : new long[]{1, 2, 3, 4, 5}) {
            int[] labels = f.labels(seed);
            assertOneRegionEach(labels, 4, "seed " + seed);
            assertShares(f.shares(seed), even(4), 0.01, "seed " + seed);
        }
        assertTrue(f.note().contains("4 place(s) on the temperature axis"), f.note());
    }

    @Test
    void sevenWeightedPlacesHoldTheirWeightsUnderTheDefaultWarp() {
        String weights = ", \"weights\": {\"minecraft:plains\": 3, \"minecraft:desert\": 2, \"minecraft:swamp\": 0.5}";
        Field f = field("p", body(null, SEVEN, "{}", weights));
        double[] w = {3, 1, 1, 1, 2, 1, 0.5};
        double total = 9.5;
        for (int i = 0; i < 7; i++) w[i] /= total;
        for (long seed = 1; seed <= 4; seed++) {
            int[] labels = f.labels(seed);
            assertOneRegionEach(labels, 7, "seed " + seed);
            assertShares(f.shares(seed), w, 0.025, "seed " + seed);
            Placeness.Regions regions = new Placeness(GRID, STEP, -RADIUS, -RADIUS).regions(labels);
            long major = java.util.Arrays.stream(regions.areas()).filter(a -> a >= Placeness.MAJOR_PERCENT / 100 * labels.length).count();
            assertEquals(7, major, "seed " + seed + ": as many major regions as places");
        }
        assertTrue(f.note().contains("edges warped 26 blocks over 256"), f.note());
    }

    @Test
    void theSeedMovesTheAutomaticArrangementButNotTheShares() {
        Field f = field("p", body(null, FOUR, "{\"warp\": {\"blocks\": 0}}", ""));
        java.util.Set<String> layouts = new java.util.HashSet<>();
        for (long seed = 1; seed <= 12; seed++) layouts.add(java.util.Arrays.toString(f.labels(seed)));
        assertTrue(layouts.size() >= 6, "12 seeds give " + layouts.size() + " layouts");
        assertArrayEquals(f.labels(7), f.labels(7), "one seed gives one layout");
    }

    @Test
    void compilingTwiceWritesTheSameField() {
        String body = body(null, SEVEN, "{\"neighbours\": [[\"minecraft:plains\", \"minecraft:swamp\"]]}", "");
        assertEquals(Fixtures.compile("p", body).files(), Fixtures.compile("p", body).files());
    }

    /** The survey's {@link com.customdimensions.core.survey.PlaceMap} reads the compiled biome source's places as the compiler laid them. */
    @Test
    void theSurveyReadsThePlacesFromTheCompiledBiomeSource() {
        String places = "{\"groups\": {\"warm\": [\"minecraft:plains\", \"minecraft:desert\"]}}";
        Field f = field("p", body(null, FOUR, places, ""));
        PlaceLayout.Places laid = PlaceLayout.build("p", JsonParser.parseString("{\"places\": " + places + "}").getAsJsonObject(),
                List.of("temperature", "humidity"), FOUR, FOUR, Map.of(), List.of(), RADIUS);
        com.customdimensions.core.survey.PlaceMap map = com.customdimensions.core.survey.PlaceMap.of(f.source(),
                Map.of("warm", List.of("minecraft:plains", "minecraft:desert")), id -> id.contains("ocean"));
        assertEquals(laid.names(), map.names());
        assertEquals("temperature", map.axis());
        for (int p = 0; p < laid.size(); p++) {
            for (String id : laid.members().get(p)) assertEquals(p, map.of(id), id);
        }
        DfEval eval = new DfEval(f.out().files(), 1);
        int[] labels = f.labels(1);
        for (int i = 0; i < labels.length; i += 97) {
            float v = eval.at(f.place(), -RADIUS + STEP / 2 + (i % GRID) * STEP, -RADIUS + STEP / 2 + (i / GRID) * STEP);
            assertEquals(labels[i], map.index(v), "the field's value reads back as its place");
        }
    }

    /** Places are laid over the radius players see, as regions are: a smaller generation border, which only pre-generates, changes nothing. */
    @Test
    void placesAreLaidOverThePlayerBorder() {
        String own = body(null, FOUR, "{\"warp\": {\"blocks\": 0}}", "");
        String pregenerated = own.replace("\"borders\": {\"player\": " + RADIUS + "}", "\"borders\": {\"player\": " + RADIUS + ", \"generation\": " + RADIUS / 2 + "}");
        assertNotEquals(own, pregenerated);
        assertEquals(Fixtures.compile("p", own).files(), Fixtures.compile("p", pregenerated).files());
        assertTrue(field("p", pregenerated).note().contains("laid out over a " + RADIUS + "-block radius"));
        String generationOnly = own.replace("\"borders\": {\"player\": " + RADIUS + "}", "\"borders\": {\"generation\": " + RADIUS + "}");
        java.util.function.Function<String, Map<String, String>> field = body -> new java.util.TreeMap<>(Fixtures.compile("p", body).files().entrySet().stream()
                .filter(e -> e.getKey().contains("/places/")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        assertFalse(field.apply(own).isEmpty());
        assertEquals(field.apply(own), field.apply(generationOnly), "without a player border, the generation border");
    }

    @Test
    void theWarpMovesEdgesButSplitsNoPlaceAtItsLimit() {
        Field straight = field("p", body(null, SEVEN, "{\"warp\": {\"blocks\": 0}}", ""));
        Field warped = field("p", body(null, SEVEN, "{\"warp\": {\"blocks\": 38, \"wavelength\": 256}}", ""));
        for (long seed = 1; seed <= 6; seed++) {
            int[] a = straight.labels(seed), b = warped.labels(seed);
            int moved = 0;
            for (int i = 0; i < a.length; i++) if (a[i] != b[i]) moved++;
            assertTrue(moved > GRID, "seed " + seed + ": the warp moves " + moved + " cells");
            assertOneRegionEach(b, 7, "warped seed " + seed);
        }
        assertThrows(IllegalArgumentException.class, () -> field("p", body(null, SEVEN, "{\"warp\": {\"blocks\": 39, \"wavelength\": 256}}", "")));
    }

    @Test
    void theFloatFieldIsTheDiagram() {
        DatapackCompiler.Output out = Fixtures.compile("p", body(null, SEVEN, "{\"arrangement\": \"ring\", \"warp\": {\"blocks\": 0}}", ""));
        Field f = field("p", body(null, SEVEN, "{\"arrangement\": \"ring\", \"warp\": {\"blocks\": 0}}", ""));
        PlaceLayout.Places places = PlaceLayout.build("p", JsonParser.parseString("{\"places\": {\"arrangement\": \"ring\"}}").getAsJsonObject(),
                List.of("temperature", "humidity"), SEVEN, SEVEN, Map.of(), List.of(), RADIUS);
        PlaceField.Arrangement a = places.arrangements().get(0);
        int[] labels = f.labels(1);
        int differ = 0;
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                double u = (-RADIUS + STEP / 2 + c * STEP) / (double) RADIUS, v = (-RADIUS + STEP / 2 + r * STEP) / (double) RADIUS;
                if (a.place(u, v) != labels[r * GRID + c]) differ++;
            }
        }
        assertTrue(differ <= GRID / 16, differ + " cells differ from the diagram");
        assertFalse(out.files().isEmpty());
    }

    @Test
    void aRingRunsThePlacesRoundInListOrder() {
        Field f = field("p", body(null, SEVEN, "{\"arrangement\": \"ring\", \"warp\": {\"blocks\": 0}}", ""));
        int[] labels = f.labels(3);
        boolean[][] adj = PlaceField.adjacency(labels, GRID, 7);
        for (int i = 0; i < 7; i++) assertTrue(adj[i][(i + 1) % 7], "places " + i + " and " + (i + 1) % 7 + " share a border");
        assertArrayEquals(f.labels(3), f.labels(99), "a named arrangement does not vary with the seed");
        assertOneRegionEach(labels, 7, "ring");
        assertShares(f.shares(3), even(7), 0.01, "ring");
        // The first place lies north (towards -z) of the centre.
        assertEquals(0, labels[(GRID / 8) * GRID + GRID / 2]);
    }

    @Test
    void aCoreHoldsTheCentreAndTouchesEveryOtherPlace() {
        Field f = field("p", body(null, FOUR, "{\"arrangement\": \"core\", \"warp\": {\"blocks\": 0}}",
                ", \"weights\": {\"minecraft:plains\": 2}"));
        int[] labels = f.labels(1);
        assertEquals(0, labels[(GRID / 2) * GRID + GRID / 2]);
        boolean[][] adj = PlaceField.adjacency(labels, GRID, 4);
        for (int i = 1; i < 4; i++) assertTrue(adj[0][i], "the core touches place " + i);
        assertShares(f.shares(1), new double[]{0.4, 0.2, 0.2, 0.2}, 0.01, "core");
    }

    /** Every sample within {@code margin} blocks of {@code (x, z)}, less a sample's width, lies in place {@code p}. */
    private static void assertHeld(int[] labels, int x, int z, double margin, int p, String what) {
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                double bx = -RADIUS + STEP / 2.0 + c * STEP, bz = -RADIUS + STEP / 2.0 + r * STEP;
                if (Math.hypot(bx - x, bz - z) <= margin - STEP) {
                    assertEquals(p, labels[r * GRID + c], what + ": the sample at " + bx + ", " + bz + " lies outside place " + p);
                }
            }
        }
    }

    private static final double MARGIN = PlaceField.PIN_MARGIN * RADIUS;

    private static String weights(List<String> ids, double... w) {
        StringBuilder b = new StringBuilder(", \"weights\": {");
        for (int i = 0; i < w.length; i++) b.append(i == 0 ? "" : ", ").append('"').append(ids.get(i)).append("\": ").append(w[i]);
        return b.append('}').toString();
    }

    @Test
    void aHeartWritesNoConstantPastVanillasRange() {
        DatapackCompiler.Output out = Fixtures.compile("p", body(null, FOUR, "{\"arrangement\": \"core\"}", weights(FOUR, 20, 50, 15, 15)));
        int functions = 0;
        for (Map.Entry<String, String> file : out.files().entrySet()) {
            if (!file.getKey().contains("/worldgen/density_function/")) continue;
            functions++;
            List<Double> numbers = new ArrayList<>();
            numbers(JsonParser.parseString(file.getValue()), numbers);
            for (double n : numbers) assertTrue(Math.abs(n) <= 1_000_000, file.getKey() + " writes " + n);
        }
        assertTrue(functions > 0, "the heart compiles density functions");
    }

    private static void numbers(JsonElement e, List<Double> out) {
        if (e.isJsonObject()) for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) numbers(m.getValue(), out);
        else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) numbers(x, out);
        else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) out.add(e.getAsDouble());
    }

    @Test
    void aSmallCoreIsAHeartRingedByTheOthers() {
        String body = body(null, FOUR, "{\"arrangement\": \"core\", \"warp\": {\"blocks\": 0}}", weights(FOUR, 20, 50, 15, 15));
        Field f = field("p", body);
        int[] labels = f.labels(1);
        assertHeld(labels, 0, 0, MARGIN, 0, "heart");
        boolean[][] adj = PlaceField.adjacency(labels, GRID, 4);
        for (int i = 1; i < 4; i++) assertTrue(adj[0][i], "the heart touches place " + i);
        assertOneRegionEach(labels, 4, "heart");
        assertShares(f.shares(1), new double[]{0.2, 0.5, 0.15, 0.15}, 0.01, "heart");
        assertEquals(Fixtures.compile("p", body).files(), Fixtures.compile("p", body).files(), "one heart compiles to one field");
        Field warped = field("p", body(null, FOUR, "{\"arrangement\": \"core\"}", weights(FOUR, 20, 50, 15, 15)));
        for (long seed = 1; seed <= 4; seed++) {
            int[] w = warped.labels(seed);
            assertHeld(w, 0, 0, MARGIN / 2, 0, "warped heart, seed " + seed);
            assertOneRegionEach(w, 4, "warped heart, seed " + seed);
            assertShares(warped.shares(seed), new double[]{0.2, 0.5, 0.15, 0.15}, 0.02, "warped heart, seed " + seed);
        }
    }

    @Test
    void aHeartFieldIsItsDiagramAndATwoPlaceHeartIsAnIsland() {
        Field f = field("p", body(null, FOUR, "{\"arrangement\": \"core\", \"direction\": 90, \"warp\": {\"blocks\": 0}}", weights(FOUR, 20, 50, 15, 15)));
        PlaceLayout.Places places = PlaceLayout.build("p", JsonParser.parseString("{\"places\": {\"arrangement\": \"core\", \"direction\": 90}}")
                .getAsJsonObject(), List.of("temperature", "humidity"), FOUR, FOUR, Map.of("minecraft:plains", 20.0, "minecraft:forest", 50.0,
                "minecraft:desert", 15.0, "minecraft:snowy_plains", 15.0), List.of(), RADIUS);
        PlaceField.Arrangement a = places.arrangements().get(0);
        assertTrue(a.heart() > 0, "a heart below its largest neighbour is a disc");
        int[] labels = f.labels(1);
        int differ = 0;
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                double u = (-RADIUS + STEP / 2 + c * STEP) / (double) RADIUS, v = (-RADIUS + STEP / 2 + r * STEP) / (double) RADIUS;
                if (a.place(u, v) != labels[r * GRID + c]) differ++;
            }
        }
        assertEquals(0, differ, "cells that differ from the diagram");
        assertEquals(1, labels[(GRID / 2) * GRID + GRID - 2], "the first wedge is centred east");
        Field two = field("p", body(null, FOUR.subList(0, 2), "{\"arrangement\": \"core\", \"warp\": {\"blocks\": 0}}", weights(FOUR, 10, 90)));
        int[] l2 = two.labels(1);
        assertHeld(l2, 0, 0, MARGIN, 0, "island");
        assertShares(two.shares(1), new double[]{0.1, 0.9}, 0.01, "island");
        assertOneRegionEach(l2, 2, "island");
    }

    /** Hearts smaller than their largest neighbour: 20/50/15/15, 21/51/13/16, 19/51/20/10, 20/55/25 and five places at 20/50/12/11/6. */
    @Test
    void measuredHeartsHoldTheCentre() {
        double[][] cases = {{20, 50, 15, 15}, {21, 51, 13, 16}, {19, 51, 20, 10}, {20, 55, 25}, {20, 50, 12, 11, 6}};
        for (double[] w : cases) {
            List<String> ids = (w.length <= FOUR.size() ? FOUR : SEVEN).subList(0, w.length);
            Field f = field("p", body(null, ids, "{\"arrangement\": \"core\", \"warp\": {\"blocks\": 0}}", weights(ids, w)));
            int[] labels = f.labels(1);
            String what = java.util.Arrays.toString(w);
            assertHeld(labels, 0, 0, MARGIN, 0, what);
            assertOneRegionEach(labels, w.length, what);
            double total = java.util.Arrays.stream(w).sum();
            assertShares(f.shares(1), java.util.Arrays.stream(w).map(x -> x / total).toArray(), 0.01, what);
        }
    }

    @Test
    void aCoreThatAlreadyHoldsKeepsItsEvenRing() {
        PlaceLayout.Places places = PlaceLayout.build("p", JsonParser.parseString("{\"places\": {\"arrangement\": \"core\"}}").getAsJsonObject(),
                List.of("temperature", "humidity"), FOUR, FOUR, Map.of("minecraft:plains", 2.0), List.of(), RADIUS);
        PlaceField.Arrangement a = places.arrangements().get(0);
        assertEquals(0, a.x()[0], 0);
        assertEquals(0.6, Math.hypot(a.x()[1], a.z()[1]), 1e-9, "the even ring at its own radius");
        PlaceLayout.Places heart = PlaceLayout.build("p", JsonParser.parseString("{\"places\": {\"arrangement\": \"core\"}}").getAsJsonObject(),
                List.of("temperature", "humidity"), FOUR, FOUR, Map.of("minecraft:plains", 20.0, "minecraft:forest", 50.0, "minecraft:desert", 15.0,
                        "minecraft:snowy_plains", 15.0), List.of(), RADIUS);
        assertEquals(-1, PlaceField.unheld(heart.arrangements().get(0), new double[][]{{0, 0}, null, null, null}));
    }

    @Test
    void aPinnedSiteHoldsItsPlaceWhereverTheOthersFall() {
        String centre = "{\"at\": {\"minecraft:plains\": [0, 0]}, \"warp\": {\"blocks\": 0}}";
        Field f = field("p", body(null, FOUR, centre, weights(FOUR, 15, 40, 25, 20)));
        String corner = "{\"at\": {\"minecraft:plains\": [380, -380]}, \"warp\": {\"blocks\": 0}}";
        Field c = field("p", body(null, FOUR, corner, weights(FOUR, 8, 52, 20, 20)));
        for (long seed = 1; seed <= 6; seed++) {
            int[] labels = f.labels(seed);
            assertHeld(labels, 0, 0, MARGIN, 0, "centre pin, seed " + seed);
            assertOneRegionEach(labels, 4, "centre pin, seed " + seed);
            assertShares(f.shares(seed), new double[]{0.15, 0.4, 0.25, 0.2}, 0.01, "centre pin, seed " + seed);
            int[] cl = c.labels(seed);
            assertHeld(cl, 380, -380, MARGIN, 0, "corner pin, seed " + seed);
            assertShares(c.shares(seed), new double[]{0.08, 0.52, 0.2, 0.2}, 0.01, "corner pin, seed " + seed);
        }
    }

    /**
     * A heart's disc is read at the point the whole warp moves: near the border the taper would leave its edge a drawn
     * circle. Each cell is the heart exactly where its untapered warped point lies inside the disc.
     */
    @Test
    void aHeartsEdgeWandersByTheWholeWarp() {
        Field f = field("p", body(null, FOUR.subList(0, 2), "{\"at\": {\"minecraft:forest\": [0, 0]}, \"warp\": {\"blocks\": 38, \"wavelength\": 256}}", ""));
        double r = Math.sqrt(4 * 0.5 / Math.PI);
        com.google.gson.JsonPrimitive hu = new com.google.gson.JsonPrimitive(Fixtures.NAMESPACE + ":places/p/heart_u");
        com.google.gson.JsonPrimitive hv = new com.google.gson.JsonPrimitive(Fixtures.NAMESPACE + ":places/p/heart_v");
        for (long seed = 1; seed <= 3; seed++) {
            DfEval eval = new DfEval(f.out().files(), seed);
            int[] labels = f.labels(seed);
            int differ = 0, moved = 0;
            for (int row = 0; row < GRID; row++) {
                for (int c = 0; c < GRID; c++) {
                    int x = -RADIUS + STEP / 2 + c * STEP, z = -RADIUS + STEP / 2 + row * STEP;
                    double u = eval.at(hu, x, z), v = eval.at(hv, x, z);
                    boolean inside = u * u + v * v < r * r;
                    if (inside != (labels[row * GRID + c] == 1)) differ++;
                    if (inside != (Math.hypot(x, z) / RADIUS < r)) moved++;
                }
            }
            assertTrue(differ <= GRID / 16, "seed " + seed + ": " + differ + " cells differ from the disc at the warped point");
            assertTrue(moved > GRID, "seed " + seed + ": the warp moves " + moved + " cells of the heart's edge");
            assertOneRegionEach(labels, 2, "warped heart, seed " + seed);
            assertHeld(labels, 0, 0, MARGIN / 2, 1, "warped heart, seed " + seed);
        }
    }

    @Test
    void aHeartKeepsItsWarpClearOfTheBorder() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> field("p", body(null, FOUR.subList(0, 2),
                "{\"at\": {\"minecraft:forest\": [0, 0]}, \"warp\": {\"blocks\": 60, \"wavelength\": 512}}", "")));
        assertTrue(e.getMessage().contains("heart, minecraft:forest, has 52 blocks"), e.getMessage());
        Field taken = field("p", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"terrain\": {\"climateWarp\": {\"blocks\": 60, "
                + "\"wavelength\": 512}}, " + list(FOUR.subList(0, 2)) + ", \"borders\": {\"player\": " + RADIUS + "}, \"biomeLayout\": "
                + "{\"mode\": \"regions\", \"places\": {\"at\": {\"minecraft:forest\": [0, 0]}}}");
        assertTrue(taken.note().contains("edges warped 52 blocks over 512"), taken.note());
        Field ring = field("p", body(null, FOUR, "{\"warp\": {\"blocks\": 76, \"wavelength\": 512}}", ""));
        assertTrue(ring.note().contains("edges warped 76 blocks"), "no heart, no room to keep: " + ring.note());
    }

    @Test
    void aLonePinNoPowerCellCanHoldBecomesAHeartAtItsSite() {
        Field half = field("p", body(null, FOUR.subList(0, 2), "{\"at\": {\"minecraft:forest\": [0, 0]}, \"warp\": {\"blocks\": 0}}", ""));
        int[] labels = half.labels(1);
        assertHeld(labels, 0, 0, MARGIN, 1, "half at the centre");
        assertShares(half.shares(1), new double[]{0.5, 0.5}, 0.01, "half at the centre");
        assertOneRegionEach(labels, 2, "half at the centre");
        Field off = field("p", body(null, FOUR, "{\"at\": {\"minecraft:desert\": [-120, 100]}, \"warp\": {\"blocks\": 0}}",
                weights(FOUR, 15, 60, 10, 15)));
        int[] ol = off.labels(1);
        assertHeld(ol, -120, 100, MARGIN, 2, "off-centre heart");
        assertShares(off.shares(1), new double[]{0.15, 0.6, 0.1, 0.15}, 0.01, "off-centre heart");
        assertOneRegionEach(ol, 4, "off-centre heart");
        boolean[][] adj = PlaceField.adjacency(ol, GRID, 4);
        for (int i : new int[]{0, 1, 3}) assertTrue(adj[2][i], "the heart touches place " + i);
    }

    @Test
    void aPinThatCannotHoldIsRefusedNamingThePlace() {
        String crowded = "{\"at\": {\"minecraft:plains\": [0, 0], \"minecraft:forest\": [60, 0], \"minecraft:desert\": [-400, 400], "
                + "\"minecraft:snowy_plains\": [400, 400]}}";
        IllegalArgumentException at = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("p", body(null, FOUR, crowded,
                weights(FOUR, 10, 60, 15, 15))));
        assertTrue(at.getMessage().startsWith("p: biomeLayout.places: minecraft:plains (10.0%) does not hold 51 blocks round its site"), at.getMessage());
        assertTrue(at.getMessage().contains("minecraft:forest (60.0%) reaches over it; give minecraft:plains a bigger share"), at.getMessage());
        IllegalArgumentException core = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("p", body(null, FOUR,
                "{\"arrangement\": \"core\"}", weights(FOUR, 0.5, 30, 30, 30))));
        assertTrue(core.getMessage().startsWith("p: biomeLayout.places: the core arrangement cannot keep minecraft:plains (0.6%) at the centre with 51 blocks"),
                core.getMessage());
    }

    @Test
    void bandsAndSectorsLayThePlacesInOrder() {
        Field bands = field("p", body(null, FOUR, "{\"arrangement\": \"bands\", \"direction\": 90, \"warp\": {\"blocks\": 0}}", ""));
        int[] labels = bands.labels(1);
        int row = (GRID / 2) * GRID;
        int last = -1;
        for (int c = 0; c < GRID; c++) {
            assertTrue(labels[row + c] >= last, "bands run west to east in list order");
            last = labels[row + c];
        }
        assertEquals(3, last);
        assertShares(bands.shares(1), even(4), 0.01, "bands");
        Field sectors = field("p", body(null, FOUR, "{\"arrangement\": \"sectors\", \"warp\": {\"blocks\": 0}}",
                ", \"weights\": {\"minecraft:plains\": 3}"));
        assertShares(sectors.shares(1), new double[]{0.5, 1 / 6.0, 1 / 6.0, 1 / 6.0}, 0.01, "sectors");
        assertOneRegionEach(sectors.labels(1), 4, "sectors");
    }

    @Test
    void sitesTheAuthorGivesStayPut() {
        String places = "{\"at\": {\"minecraft:plains\": [0, 0], \"minecraft:forest\": [0, -350], \"minecraft:desert\": [350, 0], "
                + "\"minecraft:snowy_plains\": [-300, 300]}, \"warp\": {\"blocks\": 0}}";
        Field f = field("p", body(null, FOUR, places, ""));
        int[] labels = f.labels(1);
        assertEquals(0, labels[(GRID / 2) * GRID + GRID / 2]);
        assertEquals(1, labels[(GRID / 16) * GRID + GRID / 2], "the forest is north");
        assertEquals(2, labels[(GRID / 2) * GRID + GRID - GRID / 16], "the desert is east");
        assertArrayEquals(labels, f.labels(42));
        Field partial = field("p", body(null, FOUR, "{\"at\": {\"minecraft:plains\": [0, 0]}, \"warp\": {\"blocks\": 0}}", ""));
        for (long seed = 1; seed <= 6; seed++) assertEquals(0, partial.labels(seed)[(GRID / 2) * GRID + GRID / 2], "seed " + seed);
    }

    @Test
    void hintsAreHonouredOrRefused() {
        String hints = "{\"neighbours\": [[\"minecraft:plains\", \"minecraft:swamp\"]], \"apart\": [[\"minecraft:desert\", \"minecraft:taiga\"], "
                + "[\"minecraft:jungle\", \"minecraft:plains\"]], \"warp\": {\"blocks\": 0}}";
        Field f = field("p", body(null, SEVEN, hints, ""));
        for (long seed = 1; seed <= 10; seed++) {
            boolean[][] adj = PlaceField.adjacency(f.labels(seed), GRID, 7);
            assertTrue(adj[0][6], "seed " + seed + ": plains touch the swamp");
            assertFalse(adj[4][2], "seed " + seed + ": desert and taiga apart");
            assertFalse(adj[5][0], "seed " + seed + ": jungle and plains apart");
        }
        StringBuilder apart = new StringBuilder("{\"apart\": [");
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                apart.append(apart.charAt(apart.length() - 1) == '[' ? "" : ", ").append("[\"").append(FOUR.get(i)).append("\", \"")
                        .append(FOUR.get(j)).append("\"]");
            }
        }
        String impossible = apart.append("]}").toString();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> field("p", body(null, FOUR, impossible, "")));
        assertTrue(e.getMessage().startsWith("p: biomeLayout.places: no automatic arrangement holds the hints"), e.getMessage());
        IllegalArgumentException ring = assertThrows(IllegalArgumentException.class, () -> field("p", body(null, SEVEN,
                "{\"arrangement\": \"ring\", \"neighbours\": [[\"minecraft:plains\", \"minecraft:savanna\"]]}", "")));
        assertTrue(ring.getMessage().contains("in the ring arrangement places minecraft:plains and minecraft:savanna share no border"), ring.getMessage());
    }

    @Test
    void aGroupIsOnePlaceMixedOnTheSecondAxis() {
        String places = "{\"groups\": {\"woods\": [\"minecraft:forest\", \"minecraft:birch_forest\"]}, \"warp\": {\"blocks\": 0}}";
        List<String> ids = List.of("minecraft:plains", "minecraft:forest", "minecraft:birch_forest", "minecraft:desert");
        Field f = field("p", body(null, ids, places, ", \"weights\": {\"minecraft:forest\": 2}"));
        assertEquals(3, f.places());
        assertShares(f.shares(1), new double[]{0.2, 0.6, 0.2}, 0.01, "group");
        Map<String, double[]> cells = new LinkedHashMap<>();
        for (JsonElement e : f.source().getAsJsonObject("base").getAsJsonArray("biomes")) {
            JsonObject p = e.getAsJsonObject().getAsJsonObject("parameters");
            JsonArray t = p.getAsJsonArray("temperature"), h = p.getAsJsonArray("humidity");
            cells.put(e.getAsJsonObject().get("biome").getAsString(),
                    new double[]{t.get(0).getAsDouble(), t.get(1).getAsDouble(), h.get(0).getAsDouble(), h.get(1).getAsDouble()});
        }
        assertArrayEquals(new double[]{-0.3333, 0.3333, -2, 0.3333}, cells.get("minecraft:forest"), 1e-9);
        assertArrayEquals(new double[]{-0.3333, 0.3333, 0.3333, 2}, cells.get("minecraft:birch_forest"), 1e-9);
        assertArrayEquals(new double[]{-2, -0.3333, -2, 2}, cells.get("minecraft:plains"), 1e-9);
        for (JsonElement a : f.source().getAsJsonObject("regions").getAsJsonArray("areas")) {
            assertEquals("[-0.3333,0.3333]", a.getAsJsonObject().get("columns").toString(), a.toString());
        }
    }

    @Test
    void boundBiomesStillWinAndRolesKeepTheirAreas() {
        List<String> ids = List.of("minecraft:plains", "minecraft:forest", "minecraft:desert", "minecraft:ocean", "minecraft:beach");
        String body = GENERATOR + ", \"biomes\": [\"minecraft:plains\", \"minecraft:forest\", \"minecraft:desert\", \"minecraft:ocean\", "
                + "\"minecraft:beach\", {\"id\": \"minecraft:stony_peaks\", \"climate\": {\"erosion\": [-2, -0.78], \"continentalness\": [0.3, 2]}}], "
                + "\"borders\": {\"player\": 512}, \"biomeLayout\": {\"mode\": \"regions\", \"places\": {}}";
        Field f = field("p", body);
        assertEquals(3, f.places(), "the land biomes are the places; the ocean and beach keep their role areas");
        JsonArray entries = f.source().getAsJsonObject("base").getAsJsonArray("biomes");
        List<String> oceanColumns = new ArrayList<>();
        boolean peak = false;
        for (JsonElement e : entries) {
            String biome = e.getAsJsonObject().get("biome").getAsString();
            JsonObject p = e.getAsJsonObject().getAsJsonObject("parameters");
            if (biome.equals("minecraft:ocean")) oceanColumns.add(p.get("temperature").toString());
            if (biome.equals("minecraft:stony_peaks")) peak |= p.get("erosion").toString().equals("[-2.0,-0.78]");
        }
        assertTrue(peak, "the bound peak keeps its box");
        assertEquals(3, oceanColumns.size(), "the ocean takes every place's column of the sea: " + oceanColumns);
        JsonArray areas = f.source().getAsJsonObject("regions").getAsJsonArray("areas");
        for (JsonElement a : areas) assertEquals("[-0.3333,0.3333]", a.getAsJsonObject().get("columns").toString(), a.toString());
        assertTrue(ids.size() > 3);
    }

    @Test
    void zonesWrapThePlaceField() {
        String body = body(null, FOUR, "{}", "").replace("\"borders\"", "\"zones\": [{\"shape\": \"ring\", \"centre\": [0, 0], \"inner\": 100, \"outer\": 200, \"blend\": 32, "
                + "\"biomes\": [\"minecraft:meadow\"]}], \"borders\"");
        DatapackCompiler.Output out = Fixtures.compile("p", body);
        String settings = out.files().get("data/" + Fixtures.NAMESPACE + "/worldgen/noise_settings/p.json");
        JsonObject router = JsonParser.parseString(settings).getAsJsonObject().getAsJsonObject("noise_router");
        String zoneTemperature = out.files().get("data/" + Fixtures.NAMESPACE + "/worldgen/density_function/zones/p/temperature.json");
        assertTrue(router.get("temperature").getAsString().endsWith("zones/p/temperature"), router.toString());
        assertTrue(zoneTemperature.contains(Fixtures.NAMESPACE + ":places/p/place"), zoneTemperature);
    }

    @Test
    void overAVoidThePlacesCutTheBorderAndTheLandTakesThem() {
        Field f = field("p", body(SKY, FOUR, "{\"arrangement\": \"sectors\"}", ", \"over\": \"land\""));
        JsonObject regions = f.source().getAsJsonObject("regions");
        assertTrue(regions.get("places").getAsBoolean());
        assertEquals("continentalness", regions.getAsJsonObject("land").get("axis").getAsString());
        assertOneRegionEach(f.labels(1), 4, "sky");
    }

    @Test
    void withoutPlacesNothingChanges() {
        String body = GENERATOR + ", " + list(FOUR) + ", \"biomeLayout\": {\"mode\": \"regions\"}";
        DatapackCompiler.Output out = Fixtures.compile("p", body);
        assertTrue(out.files().keySet().stream().noneMatch(k -> k.contains("/places/")), out.files().keySet().toString());
        assertFalse(out.files().get(out.files().keySet().stream().filter(k -> k.endsWith("dimension/p.json")).findFirst().orElseThrow())
                .contains("\"places\""));
    }

    @Test
    void malformedPlacesAreRefusedNamingTheDefinition() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("{\"shape\": 1}", "biomeLayout.places.shape is not a field");
        cases.put("{\"arrangement\": \"spiral\"}", "biomeLayout.places.arrangement must be one of");
        cases.put("{\"direction\": 400}", "biomeLayout.places.direction must be a number from 0 to 360");
        cases.put("{\"groups\": {\"Woods\": [\"minecraft:forest\"]}}", "a group name is lowercase");
        cases.put("{\"groups\": {\"woods\": [\"minecraft:taiga\"]}}", "names \"minecraft:taiga\", no listed biome without a climate");
        cases.put("{\"groups\": {\"a\": [\"minecraft:forest\"], \"b\": [\"minecraft:forest\"]}}", "minecraft:forest is in both a and b");
        cases.put("{\"neighbours\": [[\"minecraft:forest\", \"minecraft:taiga\"]]}", "names \"minecraft:taiga\", which is no place");
        cases.put("{\"neighbours\": [[\"minecraft:forest\", \"minecraft:forest\"]]}", "pairs minecraft:forest with itself");
        cases.put("{\"neighbours\": [[\"minecraft:forest\", \"minecraft:desert\"]], \"apart\": [[\"minecraft:desert\", \"minecraft:forest\"]]}",
                "both neighbours and apart");
        cases.put("{\"arrangement\": \"ring\", \"at\": {\"minecraft:forest\": [0, 0]}}", "give one or the other");
        cases.put("{\"at\": {\"minecraft:forest\": [600, 0]}}", "must lie inside 95% of the 512-block border");
        cases.put("{\"at\": {\"minecraft:forest\": [0, 0], \"minecraft:plains\": [1, 1]}}", "on one site");
        cases.put("{\"warp\": {\"blocks\": 100, \"wavelength\": 256}}", "above 0.15 of the wavelength");
        cases.put("{\"warp\": {\"wavelength\": 10}}", "biomeLayout.places.warp.wavelength must be a number from 64 to 4096");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("p", body(null, FOUR, c.getKey(), "")),
                    c.getKey());
            assertTrue(e.getMessage().startsWith("p: ") && e.getMessage().contains(c.getValue()), c.getKey() + " -> " + e.getMessage());
        }
        IllegalArgumentException axis = assertThrows(IllegalArgumentException.class,
                () -> Fixtures.compile("p", body(null, FOUR, "{}", ", \"axes\": [\"erosion\", \"humidity\"]")));
        assertTrue(axis.getMessage().contains("must be temperature or humidity"), axis.getMessage());
        IllegalArgumentException bound = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("p", GENERATOR
                + ", \"biomes\": [\"minecraft:plains\", {\"id\": \"minecraft:desert\", \"climate\": {\"temperature\": [0.5, 1]}}], "
                + "\"biomeLayout\": {\"mode\": \"regions\", \"places\": {}}"));
        assertTrue(bound.getMessage().contains("narrows the place axis"), bound.getMessage());
        IllegalArgumentException oneAxis = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("p", body(null, FOUR,
                "{\"groups\": {\"g\": [\"minecraft:forest\", \"minecraft:desert\"]}}", ", \"axes\": [\"temperature\"]")));
        assertTrue(oneAxis.getMessage().contains("give biomeLayout.axes two"), oneAxis.getMessage());
        List<String> many = new ArrayList<>();
        for (String id : List.of("plains", "forest", "taiga", "savanna", "desert", "jungle", "swamp", "meadow", "grove", "badlands",
                "birch_forest", "dark_forest", "snowy_plains", "ice_spikes", "sunflower_plains", "flower_forest", "cherry_grove")) {
            many.add("minecraft:" + id);
        }
        IllegalArgumentException limit = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("p", body(null, many, "{}", "")));
        assertTrue(limit.getMessage().contains("lays out 17 places; the limit is 16"), limit.getMessage());
        assertNotEquals(0, PlaceField.MAX_PLACES);
    }

    /** The four place gallery fixtures against {@code golden/26.3/<slug>/}, our decisions only; regenerate with {@code -PupdateGolden=true}. */
    @Test
    void galleryFixturesMatchTheCommittedOutput() throws java.io.IOException {
        for (String slug : List.of("places_four", "places_seven", "places_ring", "places_core")) {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of("../server/src/selftest/gallery/" + slug + ".json")).trim();
            JsonObject def = JsonParser.parseString(text).getAsJsonObject();
            for (String key : List.of("schemaVersion", "description", "seed", "spawn")) def.remove(key);
            String body = def.toString();
            TerrainGoldenTest.matchesBody(slug, body.substring(1, body.length() - 1));
        }
    }

    @Test
    void theGalleryFixturesMeetTheirSurveyRows() {
        // places_seven: seven places (woods is one of two biomes), the plains 2.5 of 9.5.
        String text;
        try {
            text = java.nio.file.Files.readString(java.nio.file.Path.of("../server/src/selftest/gallery/places_seven.json"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        JsonObject def = JsonParser.parseString(text).getAsJsonObject();
        for (String key : List.of("schemaVersion", "description", "seed", "spawn")) def.remove(key);
        def.add("borders", JsonParser.parseString("{\"player\": " + RADIUS + "}"));
        String body = def.toString();
        Field f = field("places_seven", body.substring(1, body.length() - 1));
        assertEquals(7, f.places());
        double[] shares = f.shares(20261022);
        assertEquals(2.5 / 9.5, shares[0], 0.025, "plains");
        assertEquals(1.5 / 9.5, shares[1], 0.025, "woods");
        boolean[][] adj = PlaceField.adjacency(f.labels(20261022), GRID, 7);
        assertTrue(adj[0][5], "plains beside the swamp");
        assertFalse(adj[4][6], "desert apart from the snow");
    }

    @Test
    void theTerrainsClimateWarpIsTheDialCappedWhereItCouldSplitAPlace() {
        String terrain = "\"terrain\": {\"base\": \"continents\", \"climateWarp\": {\"blocks\": 160, \"wavelength\": 512}}";
        Field f = field("p", body(terrain, FOUR, "{}", ""));
        assertTrue(f.note().contains("edges warped 76 blocks over 512"), f.note());
        assertTrue(f.note().contains("take terrain.climateWarp at 76 of its 160 blocks"), f.note());
        Field own = field("p", body(terrain, FOUR, "{\"warp\": {\"blocks\": 20, \"wavelength\": 300}}", ""));
        assertTrue(own.note().contains("edges warped 20 blocks over 300"), own.note());
        assertFalse(own.note().contains("take terrain.climateWarp"), own.note());
    }

    @Test
    void sixteenPlacesFitTheEncoding() {
        List<String> ids = new ArrayList<>();
        for (String id : List.of("plains", "forest", "taiga", "savanna", "desert", "jungle", "swamp", "meadow", "grove", "badlands",
                "birch_forest", "dark_forest", "snowy_plains", "ice_spikes", "sunflower_plains", "flower_forest")) {
            ids.add("minecraft:" + id);
        }
        Field f = field("p", body(null, ids, "{\"warp\": {\"blocks\": 0}}", ""));
        assertEquals(16, f.places());
        assertOneRegionEach(f.labels(5), 16, "sixteen");
        assertShares(f.shares(5), even(16), 0.01, "sixteen");
    }
}
