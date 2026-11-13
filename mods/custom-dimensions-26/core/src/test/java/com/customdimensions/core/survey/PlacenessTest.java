package com.customdimensions.core.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PlacenessTest {
    static final int SIDE = 64, STEP = 16;
    final Placeness p = new Placeness(SIDE, STEP, -512, -512);

    static int[] fill(java.util.function.IntBinaryOperator f) {
        int[] out = new int[SIDE * SIDE];
        for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++) out[z * SIDE + x] = f.applyAsInt(x, z);
        return out;
    }

    static double[] field(java.util.function.IntToDoubleFunction f) {
        double[] out = new double[SIDE * SIDE];
        for (int i = 0; i < out.length; i++) out[i] = f.applyAsDouble(i);
        return out;
    }

    JsonObject json(int[] biomes, int[] forms, double[] heights, double[] cont, int[] spawn) {
        return p.toJson(biomes, k -> "b" + k, forms, k -> "f" + k, heights, cont, spawn);
    }

    @Test
    void islandsAreCountedSizedAndJudgedOnTheirOwnBiomes() {
        boolean[] land = new boolean[SIDE * SIDE];
        int[] biomes = fill((x, z) -> -1);
        for (int z = 0; z < 10; z++) for (int x = 0; x < 10; x++) {
            land[z * SIDE + x] = true;
            biomes[z * SIDE + x] = x < 5 ? 0 : 1;
        }
        for (int z = 20; z < 24; z++) for (int x = 20; x < 30; x++) {
            land[z * SIDE + x] = true;
            biomes[z * SIDE + x] = 2;
        }
        land[40 * SIDE + 40] = true;
        biomes[40 * SIDE + 40] = 2;
        JsonObject o = p.islandsJson(land, biomes);
        assertEquals(3, o.get("count").getAsInt());
        JsonObject ha = o.getAsJsonObject("sizesHa");
        assertEquals(2.6, ha.get("max").getAsDouble(), "100 cells of 16 x 16 blocks");
        assertEquals(1.0, ha.get("p50").getAsDouble());
        // 50 of the first island's 100 cells, all 40 of the second's, the speck's one: 91 of 141
        assertEquals(64.5, o.get("dominantBiomePercent").getAsDouble());
    }

    @Test
    void voidBiomesAreSharesOfTheVoidCells() {
        int[] biomes = fill((x, z) -> z < 2 ? (x < 48 ? 0 : 1) : -1);
        JsonObject o = p.voidBiomesJson(biomes, k -> "b" + k);
        assertEquals(75.0, o.get("b0").getAsDouble());
        assertEquals(25.0, o.get("b1").getAsDouble());
    }

    @Test
    void oneBiomeIsOnePlace() {
        int[] one = fill((x, z) -> 0);
        JsonObject o = json(one, one, field(i -> 70), null, new int[]{0, 0});
        JsonObject b = o.getAsJsonObject("biomeRegions");
        assertEquals(1, b.get("count").getAsInt());
        assertEquals(100.0, b.get("largestPercent").getAsDouble());
        assertEquals(0.0, b.get("edgeKmPerKm2").getAsDouble());
        assertEquals(0.0, b.get("nearEdgePercent").getAsDouble());
        assertEquals(100.0, o.getAsJsonObject("dominant").get("percent").getAsDouble());
        assertEquals("b0", o.getAsJsonObject("dominant").get("biome").getAsString());
        JsonObject spawn = o.getAsJsonObject("at").getAsJsonObject("spawn");
        assertEquals(100.0, spawn.get("biomeRegionPercent").getAsDouble());
        assertEquals(-1, spawn.get("biomeEdgeBlocks").getAsInt());
        // a constant field has no variance: no range to reach
        assertEquals(0.0, o.getAsJsonObject("correlationLength").getAsJsonObject("surfaceHeight").get("sd").getAsDouble());
    }

    @Test
    void halvesAreTwoRegionsWithOneStraightEdge() {
        int[] halves = fill((x, z) -> x < SIDE / 2 ? 0 : 1);
        JsonObject b = json(halves, halves, null, null, null).getAsJsonObject("biomeRegions");
        assertEquals(2, b.get("count").getAsInt());
        assertEquals(2, b.get("major").getAsInt());
        JsonObject shares = json(halves, halves, null, null, null).getAsJsonObject("biomesPercent");
        assertEquals(50.0, shares.get("b0").getAsDouble());
        assertEquals(50.0, shares.get("b1").getAsDouble());
        int[] one = fill((x, z) -> x == 0 && z == 0 ? 1 : 0);
        assertEquals(0.1, json(one, one, null, null, null).getAsJsonObject("biomesPercent").get("b1").getAsDouble(), "present is never 0");
        assertEquals(50.0, b.get("largestPercent").getAsDouble());
        assertEquals(100.0, b.get("top3Percent").getAsDouble());
        // one boundary SIDE cells long over a SIDE x SIDE grid: 1024 blocks over 1.048576 km^2
        assertEquals(Math.round(SIDE * STEP / 1000.0 / (SIDE * SIDE * STEP * STEP / 1e6) * 100) / 100.0, b.get("edgeKmPerKm2").getAsDouble());
        // within 64 blocks (4 cells) of x = 31.5: columns 27..36 are 10 of 64
        assertEquals(Stats.percent(10, 64), b.get("nearEdgePercent").getAsDouble());
    }

    @Test
    void aCheckerboardIsNoiseAndSameLabelsApartAreSeparateRegions() {
        int[] checker = fill((x, z) -> (x / 2 + z / 2) % 2);
        JsonObject b = json(checker, checker, null, null, null).getAsJsonObject("biomeRegions");
        assertEquals(SIDE * SIDE / 4, b.get("count").getAsInt(), "2x2 blocks touch only at corners");
        assertEquals(0, b.get("major").getAsInt());
        assertTrue(b.get("largestPercent").getAsDouble() < 1);
        assertEquals(100.0, b.get("specksPercent").getAsDouble(), "0.1 ha regions are specks");
        assertEquals(0.0, b.get("perKm2").getAsDouble());
        assertEquals(0.0, b.get("nearEdgePercent").getAsDouble(), "specks draw no edges");
        assertEquals(Math.round(4 * STEP * STEP / 10_000.0 * 100) / 100.0, b.get("medianHa").getAsDouble());
    }

    @Test
    void specksInAPlaceLeaveItWhole() {
        int[] specked = fill((x, z) -> x % 16 == 3 && z % 16 == 3 ? 1 : 0);
        JsonObject b = json(specked, specked, null, null, null).getAsJsonObject("biomeRegions");
        assertEquals(17, b.get("count").getAsInt());
        assertEquals(1, b.get("major").getAsInt());
        assertEquals(Math.round(1 / 1.048576 * 10) / 10.0, b.get("perKm2").getAsDouble(), "one region of a hectare or more over 1.05 km2");
        assertEquals(0.0, b.get("nearEdgePercent").getAsDouble());
        assertEquals(Stats.percent(16, SIDE * SIDE), b.get("specksPercent").getAsDouble());
    }

    @Test
    void negativeLabelsJoinNoRegion() {
        int[] holes = fill((x, z) -> x == SIDE / 2 ? -1 : 0);
        Placeness.Regions r = p.regions(holes);
        assertEquals(2, r.count(), "a void column splits the biome in two");
        assertEquals(-1, r.region()[SIDE / 2]);
    }

    @Test
    void correlationLengthFollowsTheFeatureSize() {
        Random rnd = new Random(7);
        double[] white = field(i -> rnd.nextGaussian());
        JsonObject w = p.correlation(white);
        assertEquals(STEP, w.get("blocks").getAsInt(), "white noise decorrelates in one cell");
        assertFalse(w.get("beyond").getAsBoolean());
        double[] wave = field(i -> Math.sin(2 * Math.PI * (i % SIDE) / 32.0) + Math.sin(2 * Math.PI * (i / SIDE) / 32.0));
        JsonObject s = p.correlation(wave);
        // pooled over both axes the semivariance of sin x + sin z is (1 - cos d) / 2 of its variance: 95% at d = 154 degrees, 13.7 cells
        // (the window's ends shift it by a cell); white noise is one cell, so the range orders feature sizes
        int blocks = s.get("blocks").getAsInt();
        assertTrue(blocks >= 13 * STEP && blocks <= 16 * STEP, "range " + blocks);
        double[] ramp = field(i -> i % SIDE);
        JsonObject t = p.correlation(ramp);
        assertTrue(t.get("beyond").getAsBoolean(), "a trend across the border never reaches the sill");
        assertEquals(SIDE / 2 * STEP, t.get("blocks").getAsInt());
    }

    @Test
    void nanCellsAreSkipped() {
        double[] withVoid = field(i -> i % SIDE < 8 ? Double.NaN : (i % SIDE) % 2);
        JsonObject c = p.correlation(withVoid);
        assertEquals(STEP, c.get("blocks").getAsInt());
        assertEquals(1, p.correlation(field(i -> i == 0 ? 1 : Double.NaN)).get("samples").getAsInt());
    }

    @Test
    void spawnOutsideTheGridSaysSo() {
        int[] one = fill((x, z) -> 0);
        JsonObject s = json(one, one, null, null, new int[]{5000, 0}).getAsJsonObject("at").getAsJsonObject("spawn");
        assertTrue(s.get("outside").getAsBoolean());
    }

    @Test
    void spawnEdgeDistanceIsInBlocks() {
        int[] halves = fill((x, z) -> x < SIDE / 2 ? 0 : 1);
        // block -512 + 10 * 16 = -352 is column 10; the boundary cell is column 31: 21 cells away
        JsonObject s = json(halves, halves, null, null, new int[]{-352, 0}).getAsJsonObject("at").getAsJsonObject("spawn");
        assertEquals(21 * STEP, s.get("biomeEdgeBlocks").getAsInt());
        assertEquals("b0", s.get("biome").getAsString());
        assertEquals(50.0, s.get("landformRegionPercent").getAsDouble());
    }

    @Test
    void aCoastIsNoLandBiomeEdge() {
        // west half land biome 0, east half the sea biome 1: one land region, the coast its only boundary
        int[] all = fill((x, z) -> x < SIDE / 2 ? 0 : 1);
        int[] land = fill((x, z) -> x < SIDE / 2 ? 0 : -1);
        JsonObject o = p.toJson(all, k -> "b" + k, all, k -> "f" + k, null, null, null, land);
        assertTrue(o.getAsJsonObject("biomeRegions").get("edgeKmPerKm2").getAsDouble() > 0, "the coast is an edge over all columns");
        JsonObject l = o.getAsJsonObject("landBiomeRegions");
        assertEquals(50.0, l.get("landPercent").getAsDouble());
        assertEquals(1, l.get("count").getAsInt());
        assertEquals(100.0, l.get("largestPercent").getAsDouble(), "of the land");
        assertEquals(0.0, l.get("edgeKmPerKm2").getAsDouble());
        assertEquals(0.0, l.get("nearEdgePercent").getAsDouble());
        assertEquals(100.0, l.getAsJsonObject("biomesPercent").get("b0").getAsDouble());
    }

    @Test
    void landRegionsAreMeasuredOverTheLand() {
        // a quarter of the grid is land, split into two biomes; the rest is sea
        int[] land = fill((x, z) -> z >= SIDE / 2 || x >= SIDE / 2 ? -1 : x < SIDE / 4 ? 0 : 1);
        JsonObject l = p.toJson(land, k -> "b" + k, land, k -> "f" + k, null, null, null, land).getAsJsonObject("landBiomeRegions");
        assertEquals(25.0, l.get("landPercent").getAsDouble());
        assertEquals(2, l.get("count").getAsInt());
        assertEquals(50.0, l.get("largestPercent").getAsDouble());
        double landKm2 = SIDE * SIDE / 4.0 * STEP * STEP / 1e6;
        assertEquals(Math.round(2 / landKm2 * 10) / 10.0, l.get("perKm2").getAsDouble());
        // one straight boundary SIDE/2 cells long between the two land biomes
        assertEquals(Math.round(SIDE / 2.0 * STEP / 1000.0 / landKm2 * 100) / 100.0, l.get("edgeKmPerKm2").getAsDouble());
    }

    @Test
    void placesAreMeasuredAsPlacesOverTheLand() {
        // the field halves the grid; land is an island of place 0 with a strip of place 1, an island of place 1 and a one-cell speck
        int[] field = fill((x, z) -> x < SIDE / 2 ? 0 : 1);
        int[] lands = fill((x, z) -> x < 20 && z < 20 ? (x < 15 ? 0 : 1) : x >= 40 && x < 50 && z >= 40 && z < 50 ? 1 : x == 60 && z == 60 ? 1 : -1);
        boolean[] land = new boolean[lands.length];
        for (int i = 0; i < land.length; i++) land[i] = lands[i] >= 0;
        JsonObject o = p.placesJson(java.util.List.of("coast", "highlands"), "temperature", field, lands, land, lands);
        assertEquals(2, o.get("count").getAsInt());
        JsonObject coast = o.getAsJsonObject("byPlace").getAsJsonObject("coast");
        JsonObject high = o.getAsJsonObject("byPlace").getAsJsonObject("highlands");
        assertEquals(50.0, coast.get("percent").getAsDouble(), "a place's share of the sampled area is the field's");
        assertEquals(59.9, coast.get("landPercent").getAsDouble());
        assertEquals(1, coast.get("landRegions").getAsInt());
        assertEquals(59.9, coast.get("largestPercent").getAsDouble());
        assertEquals(40.1, high.get("landPercent").getAsDouble());
        assertEquals(2, high.get("landRegions").getAsInt(), "the strip and the island; the speck is no region of 1%");
        assertEquals(20.0, high.get("largestPercent").getAsDouble());
        assertEquals(0.2, high.get("specksPercent").getAsDouble());
        JsonObject r = o.getAsJsonObject("landRegions");
        assertEquals(4, r.get("count").getAsInt());
        assertEquals(3, r.get("major").getAsInt());
        assertEquals(99.8, r.get("top3Percent").getAsDouble());
        assertEquals(0.2, r.get("specksPercent").getAsDouble());
        assertEquals(80.0, o.getAsJsonObject("islands").get("dominantPlacePercent").getAsDouble(),
                "the first island is three quarters one place, the others wholly theirs");
    }

    @Test
    void noLandMeasuresNothing() {
        int[] sea = fill((x, z) -> 0);
        int[] land = fill((x, z) -> -1);
        JsonObject l = p.toJson(sea, k -> "b" + k, sea, k -> "f" + k, null, null, null, land).getAsJsonObject("landBiomeRegions");
        assertEquals(0.0, l.get("landPercent").getAsDouble());
        assertEquals(0, l.get("count").getAsInt());
        assertEquals(0.0, l.get("largestPercent").getAsDouble());
        assertEquals(0.0, l.get("edgeKmPerKm2").getAsDouble());
        assertFalse(json(sea, sea, null, null, null).has("landBiomeRegions"), "absent unless asked for");
    }
}
