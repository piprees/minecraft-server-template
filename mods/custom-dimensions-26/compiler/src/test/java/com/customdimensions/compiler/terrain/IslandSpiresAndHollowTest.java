package com.customdimensions.compiler.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.customdimensions.compiler.terrain.SkyTiersTest.build;
import static com.customdimensions.compiler.terrain.SkyTiersTest.column;
import static com.customdimensions.compiler.terrain.SkyTiersTest.eval;
import static com.customdimensions.compiler.terrain.SkyTiersTest.islands;
import static com.customdimensions.compiler.terrain.SkyTiersTest.runs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spires standing on floating islands and a ring layout's hollow held as one lake on basin islands, from their shapes
 * sampled at columns whose library noises (and, for the hollow, position) are given.
 */
class IslandSpiresAndHollowTest {

    private static final String ISLANDS = "adventure:terrain/islands", SPIRES = "adventure:terrain/spires", LAKES = "adventure:terrain/lakes";
    private static final double SS = Noises.sigma("spires"), SL = Noises.sigma("lakes");

    // ---- spires on floating islands -------------------------------------------------------------------

    private static final String SPIRED = "{\"base\": \"floating_islands\", \"params\": {\"spires\": 0.1, \"spireHeight\": 60, \"spireTaper\": 0}}";

    /** The spire noise value at which a spire of share {@code share} reaches its full column. */
    private static double spire(double share) {
        return Noises.threshold(share, SS) + 0.6 * SS;
    }

    @Test
    void noSpiresLeavesTheIslandsAsTheyWere() {
        assertEquals(TerrainLibraryTest.compile("{\"base\": \"floating_islands\"}").files(),
                TerrainLibraryTest.compile("{\"base\": \"floating_islands\", \"params\": {\"spireHeight\": 150, \"spireTaper\": 1}}").files(),
                "spire parameters without spires change nothing");
        assertFalse(TerrainLibraryTest.compile("{\"base\": \"floating_islands\"}").files().containsKey("data/adventure/worldgen/noise/terrain/spires.json"));
    }

    @Test
    void aSpireRisesSpireHeightFromTheIslandTopItStandsOn() {
        SkyTiersTest.Built b = build(SPIRED);
        List<double[]> r = runs(b, column(ISLANDS, islands(0.3, 1), SPIRES, spire(0.1)));
        assertEquals(1, r.size(), "one run: the spire grows out of its island");
        assertEquals(150 + 18 - 2 + 60, r.get(0)[1], 0.3, "crest at the island top plus spireHeight");
        assertEquals(150 - 40 + 2, r.get(0)[0], 0.3, "the island's own underside under it");
        assertEquals(TerrainFamily.PLACE_LAND, eval(b, b.shape().place(), column(ISLANDS, islands(0.3, 1), SPIRES, spire(0.1)), 200), 1e-6);
    }

    @Test
    void theIslandBetweenSpiresKeepsItsTopAndNoSpireHangsOverTheVoid() {
        SkyTiersTest.Built b = build(SPIRED);
        double below = Noises.threshold(0.1, SS) - 0.1 * SS;
        assertEquals(166, runs(b, column(ISLANDS, islands(0.3, 1), SPIRES, below)).get(0)[1], 0.3, "an island top with no spire on it");
        assertTrue(runs(b, column(ISLANDS, islands(0.3, -0.5), SPIRES, spire(0.1))).isEmpty(), "over the void the spire noise raises nothing");
        double edge = runs(b, column(ISLANDS, islands(0.3, 0.04), SPIRES, spire(0.1))).get(0)[1];
        assertTrue(edge < 150 + 4, "no spire on an island's last block or two: its top at " + edge);
        double lip = runs(b, column(ISLANDS, islands(0.3, 0.15), SPIRES, spire(0.1))).get(0)[1];
        assertTrue(lip < 150 + 18, "no spire on the lip a top rolls off over: its top at " + lip);
        assertEquals(150 + 18 * Math.sqrt(0.3) - 2 + 60, runs(b, column(ISLANDS, islands(0.3, 0.3), SPIRES, spire(0.1))).get(0)[1], 0.3,
                "a spire a little way in stands its full height");
    }

    @Test
    void aSpireIsGroundNoCaveCutsDownIntoItsIsland() {
        SkyTiersTest.Built b = build(SPIRED);
        SkyTiersTest.Column spire = column(ISLANDS, islands(0.3, 1), SPIRES, spire(0.1));
        for (double y : new double[] {166 - 5, 166, 190, 166 + 60 - 1}) {
            assertTrue(eval(b, b.shape().solid(), spire, y) > 0, "the spire's body is sealed at y " + y);
        }
        assertTrue(eval(b, b.shape().solid(), spire, 166 + 60 + 1) < 0, "and nothing over its crest");
        assertTrue(eval(b, b.shape().solid(), spire, 166 - 8) < 0, "nor deep in the island under it");
        double below = Noises.threshold(0.1, SS) - 0.1 * SS;
        assertTrue(eval(b, b.shape().solid(), column(ISLANDS, islands(0.3, 1), SPIRES, below), 166 - 1) < 0,
                "an island top with no spire on it keeps its caves");
        assertNull(build("{\"base\": \"floating_islands\"}").shape().solid(), "no spires, nothing sealed");
    }

    @Test
    void spireShareAndGirthSetTheSpireNoise() {
        String height = TerrainLibraryTest.compile("{\"base\": \"floating_islands\", \"params\": {\"spires\": 0.05, \"spireThickness\": 2}}")
                .files().toString();
        assertTrue(height.contains("\"noise\":\"adventure:terrain/spires\",\"shift_x\":\"minecraft:shift_x\",\"shift_z\":\"minecraft:shift_z\",\"xz_scale\":0.5"),
                "spireThickness 2 halves the spire noise's frequency");
        SkyTiersTest.Built b = build("{\"base\": \"floating_islands\", \"params\": {\"spires\": 0.05}}");
        assertEquals(166, runs(b, column(ISLANDS, islands(0.3, 1), SPIRES, Noises.threshold(0.05, SS) - 0.01)).get(0)[1], 0.3,
                "just under a 5% share's threshold, no spire");
    }

    @Test
    void anUpperTierClearsTheSpiresByItsGap() {
        SkyTiersTest.Built b = build("{\"base\": \"floating_islands\", \"params\": {\"tiers\": 2, \"altitude\": 100, \"tierSpacing\": 80, \"thickness\": 20, "
                + "\"tierGap\": 20, \"spires\": 0.1, \"spireHeight\": 40, \"spireTaper\": 0}}");
        String tier1 = ISLANDS + "@" + Math.round(1.31 / 0.6 * 10000) / 10000.0;
        List<double[]> r = runs(b, column(ISLANDS, islands(0.3, 1), tier1, islands(0.18, 1), SPIRES, spire(0.1)));
        assertEquals(100 + 20 - 2 + 40, r.get(0)[1], 0.3, "the spire's crest");
        for (int i = 1; i < r.size(); i++) assertTrue(r.get(i)[0] - r.get(i - 1)[1] >= 20 - 0.25, "the tier above clears the spire");
    }

    @Test
    void spiresThatWouldStandOverTheTopAreRefused() {
        TerrainSpec high = TerrainLibraryTest.spec("{\"base\": \"floating_islands\", \"params\": {\"altitude\": 200, \"spires\": 0.1, \"spireHeight\": 120}}");
        assertEquals("t: terrain.params.spireHeight 120 on islands at altitude 200 lifts the spires' crests to y 338, above the dimension's top at 320",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.compile(high, "overworld", null)).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TerrainLibraryTest.spec("{\"base\": \"floating_islands\", \"params\": {\"spires\": 0.5}}")).getMessage()
                .startsWith("t: terrain.params.spires = 0.5 is outside [0, 0.3]"));
    }

    // ---- the ring's hollow as one lake ------------------------------------------------------------------

    private static final String RING = "\"layout\": {\"type\": \"ring\", \"radius\": 300, \"falloff\": 160, \"strength\": 0.9}";
    private static final String HOLLOW = "{\"base\": \"basin_islands\", \"params\": {\"hollowDepth\": 30, \"basinDepth\": 8, \"rimHeight\": 4, \"gaps\": 0, "
            + "\"islandShare\": 0.02}, " + RING + "}";
    /** The hollow's edge: radius less half the falloff. */
    private static final double EDGE = 300 - 80;
    private static final double A = 150;

    private static SkyTiersTest.Column at(double x, double lakes) {
        return new SkyTiersTest.Column(Map.of(ISLANDS, -1.0, LAKES, lakes), x, 0);
    }

    private static double floor(SkyTiersTest.Built b, double x, double lakes) {
        return eval(b, b.named().get(TerrainFamily.BASIN_FLOOR), at(x, lakes), 0);
    }

    @Test
    void theHollowIsOneLakeHollowDepthDeepAtItsHeart() {
        SkyTiersTest.Built b = build(HOLLOW);
        for (double x : new double[] {0, 60, EDGE - 64}) {
            assertEquals(A - 30, floor(b, x, -1), 0.01, "the hollow's floor at " + x);
            List<double[]> r = runs(b, at(x, -1));
            assertEquals(1, r.size());
            assertEquals(A - 30, r.get(0)[1], 0.3, "the mass is solid up to its floor at " + x);
            assertTrue(eval(b, b.named().get(TerrainFamily.BASIN), at(x, -1), 0) >= 1, "water stands on the floor at " + x);
        }
    }

    @Test
    void aRimRingsTheHollowAtItsEdgeAndNothingLiesPastIt() {
        SkyTiersTest.Built b = build(HOLLOW);
        double crest = -1000;
        for (double x = EDGE - 24; x <= EDGE; x += 1) crest = Math.max(crest, floor(b, x, -1));
        assertEquals(A + 4, crest, 0.01, "the rim stands rimHeight over the water line");
        assertTrue(floor(b, EDGE - 24, -1) <= A - 2 + 0.01, "the shore lies under the water line");
        assertTrue(runs(b, at(EDGE + 40, -1)).isEmpty(), "past the edge, with no island and no lake mass, the sky is open");
        assertTrue(eval(b, b.named().get(TerrainFamily.BASIN), at(EDGE + 40, -1), 0) < 0, "and no water stands there");
    }

    @Test
    void ringLakesDecidesWhetherTheRingKeepsItsOwnLakeMasses() {
        double heartNoise = Noises.threshold(0.4, SL) + 0.8 * SL;
        SkyTiersTest.Built kept = build(HOLLOW);
        SkyTiersTest.Built only = build(HOLLOW.replace("\"hollowDepth\": 30", "\"hollowDepth\": 30, \"ringLakes\": 0"));
        assertFalse(runs(kept, at(300, heartNoise)).isEmpty(), "a lake mass on the ring's crest");
        assertTrue(runs(only, at(300, heartNoise)).isEmpty(), "ringLakes 0: the hollow's lake is the only one");
        assertEquals(A - 30, floor(only, 0, -1), 0.01, "and the hollow keeps its lake");
    }

    @Test
    void noHollowLeavesTheBasinsAsTheyWere() {
        String plain = "{\"base\": \"basin_islands\", " + RING + "}";
        assertEquals(TerrainLibraryTest.compile(plain).files(),
                TerrainLibraryTest.compile(plain.replace("{\"base\": \"basin_islands\", ", "{\"base\": \"basin_islands\", \"params\": {\"ringLakes\": 0}, "))
                        .files(), "ringLakes without a hollow changes nothing");
    }

    @Test
    void aHollowNeedsARingLargeEnoughToHoldIt() {
        assertEquals("t: terrain.params.hollowDepth fills a ring layout's hollow; set terrain.layout.type ring", assertThrows(IllegalArgumentException.class,
                () -> TerrainLibraryTest.compile("{\"base\": \"basin_islands\", \"params\": {\"hollowDepth\": 20}}")).getMessage());
        assertEquals("t: terrain.params.hollowDepth fills a ring layout's hollow; set terrain.layout.type ring", assertThrows(IllegalArgumentException.class,
                () -> TerrainLibraryTest.compile("{\"base\": \"basin_islands\", \"params\": {\"hollowDepth\": 20}, "
                        + "\"layout\": {\"type\": \"radial\", \"radius\": 300}}")).getMessage());
        assertEquals("t: terrain.params.hollowDepth needs a hollow at least 80 blocks across its radius; terrain.layout.radius 120 less half its "
                        + "falloff 160 leaves 40", assertThrows(IllegalArgumentException.class,
                () -> TerrainLibraryTest.compile("{\"base\": \"basin_islands\", \"params\": {\"hollowDepth\": 20}, "
                        + "\"layout\": {\"type\": \"ring\", \"radius\": 120, \"falloff\": 160}}")).getMessage());
    }
}
