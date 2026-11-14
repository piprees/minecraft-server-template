package com.customdimensions.server.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.Test;

class ClimateRegionsTest {
    private static final long[] SAMPLES = {-5000, -1000, 0, 0, 2000, 9000};

    @Test
    void landDecodesFromTheCompilersRegionsAndIsOptional() {
        com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString(
                "{\"axes\": [\"temperature\", \"humidity\"], \"radius\": 512, \"land\": {\"axis\": \"erosion\", \"min\": -0.0625}}");
        ClimateRegions r = ClimateRegions.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(Optional.of(new ClimateRegions.Land("erosion", -0.0625f)), r.land());
        ClimateRegions none = ClimateRegions.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("{\"axes\": [\"temperature\"], \"radius\": 512}")).getOrThrow();
        assertEquals(Optional.empty(), none.land());
        assertThrows(IllegalArgumentException.class, () -> new ClimateRegions(List.of("temperature"), List.of(), 512, List.of(),
                Optional.of(new ClimateRegions.Land("depth", 0))));
    }

    @Test
    void theExtremesRankAtTheEnds() {
        assertEquals(Climate.quantizeCoord(-1f), ClimateRegions.rank(-5000, SAMPLES));
        assertEquals(Climate.quantizeCoord(-1f), ClimateRegions.rank(-20000, SAMPLES));
        assertEquals(Climate.quantizeCoord(1f), ClimateRegions.rank(9000, SAMPLES));
        assertEquals(Climate.quantizeCoord(1f), ClimateRegions.rank(20000, SAMPLES));
    }

    @Test
    void ranksInterpolateBetweenSamplesAndTiesTakeTheirMiddle() {
        // 2000 is sample 4 of 0..5: rank 4/5 -> 0.6.
        assertEquals(Climate.quantizeCoord(0.6f), ClimateRegions.rank(2000, SAMPLES));
        // halfway between samples 4 and 5: 4.5/5 -> 0.8.
        assertEquals(Climate.quantizeCoord(0.8f), ClimateRegions.rank(5500, SAMPLES));
        // 0 is samples 2 and 3: 2.5/5 -> 0.
        assertEquals(0, ClimateRegions.rank(0, SAMPLES));
    }

    @Test
    void ranksAreMonotonic() {
        long last = Long.MIN_VALUE;
        for (long v = -6000; v <= 10000; v += 37) {
            long r = ClimateRegions.rank(v, SAMPLES);
            assertEquals(true, r >= last, "rank falls at " + v);
            last = r;
        }
    }

    @Test
    void aColumnIsTheCountOfCutsAtOrBelow() {
        long[] cuts = {-3333, 3333};
        assertEquals(0, ClimateRegions.column(-10000, cuts));
        assertEquals(1, ClimateRegions.column(-3333, cuts));
        assertEquals(1, ClimateRegions.column(0, cuts));
        assertEquals(2, ClimateRegions.column(10000, cuts));
    }

    @Test
    void onlySlowAxesAreRegionAxes() {
        assertThrows(IllegalArgumentException.class, () -> new ClimateRegions(List.of("depth"), List.of(), 1024));
        assertEquals(List.of("temperature", "humidity"), new ClimateRegions(List.of("temperature", "humidity"), List.of(0f), 1024).axes());
    }

    private static ClimateRegions.Area area(String role, Float lo, Float hi, List<Float> depth) {
        return new ClimateRegions.Area(role, lo == null ? Optional.empty() : Optional.of(List.of(lo, hi)), Optional.empty(),
                Optional.ofNullable(depth), List.of());
    }

    @Test
    void anAreaHoldsThePointsInsideItsBounds() {
        ClimateRegions.Area land = area("land", -0.11f, 2f, null);
        assertEquals(true, land.contains(Climate.quantizeCoord(-0.11f), 0, 0));
        assertEquals(true, land.contains(Climate.quantizeCoord(0.5f), 9999, -9999));
        assertEquals(false, land.contains(Climate.quantizeCoord(-0.2f), 0, 0));
        ClimateRegions.Area cave = area("cave", -0.11f, 2f, List.of(0.2f, 2f));
        assertEquals(false, cave.contains(Climate.quantizeCoord(0.5f), 0, Climate.quantizeCoord(0.1f)));
        assertEquals(true, cave.contains(Climate.quantizeCoord(0.5f), 0, Climate.quantizeCoord(0.3f)));
        assertEquals(true, cave.containsSample(Climate.quantizeCoord(0.5f), 0));
    }

    @Test
    void aPointRanksAmongItsOwnAreasSamples() {
        ClimateRegions regions = new ClimateRegions(List.of("temperature"), List.of(), 1024,
                List.of(area("land", 0f, 2f, null), area("ocean", -2f, 0f, null)));
        // Land samples run 0..4000, ocean samples -4000..0: 2000 is the land's median and above every ocean sample.
        ClimateRegions.Ranks ranks = new ClimateRegions.Ranks(List.of(
                ClimateRegions.measureArea(new long[][]{{0, 1000, 2000, 3000, 4000}}, List.of()),
                ClimateRegions.measureArea(new long[][]{{-4000, -3000, -2000, -1000, 0}}, List.of())));
        Climate.TargetPoint onLand = new Climate.TargetPoint(2000, 0, Climate.quantizeCoord(0.5f), 0, 0, 0);
        Climate.TargetPoint atSea = new Climate.TargetPoint(2000, 0, Climate.quantizeCoord(-0.5f), 0, 0, 0);
        assertEquals(0, regions.ranked(onLand, ranks).temperature());
        assertEquals(Climate.quantizeCoord(1f), regions.ranked(atSea, ranks).temperature());
        assertEquals(onLand.continentalness(), regions.ranked(onLand, ranks).continentalness());
    }

    @Test
    void aPlaceFieldIsReadAsItIsAndTheSecondAxisRankedWithinItsColumn() {
        ClimateRegions regions = ClimateRegions.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(
                "{\"axes\": [\"temperature\", \"humidity\"], \"columns\": [0.0], \"radius\": 512, \"places\": true}")).getOrThrow();
        assertEquals(true, regions.places());
        assertEquals(false, ClimateRegions.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(
                "{\"axes\": [\"temperature\"], \"radius\": 512}")).getOrThrow().places());
        // Two places at -0.5 and 0.5; humidity in the first runs 0..1500, in the second 1000..2500.
        long west = Climate.quantizeCoord(-0.5f), east = Climate.quantizeCoord(0.5f);
        long[][] samples = new long[2][32];
        for (int s = 0; s < 32; s++) {
            samples[0][s] = s < 16 ? west : east;
            samples[1][s] = (s % 16) * 100 + (s < 16 ? 0 : 1000);
        }
        ClimateRegions.AreaRanks area = ClimateRegions.measureArea(samples, List.of(0f), true);
        ClimateRegions.Ranks ranks = new ClimateRegions.Ranks(List.of(area));
        Climate.TargetPoint inEast = new Climate.TargetPoint(east, 1000, 0, 0, 0, 0);
        assertEquals(east, regions.ranked(inEast, ranks).temperature());
        assertEquals(Climate.quantizeCoord(-1f), regions.ranked(inEast, ranks).humidity());
        assertEquals(Climate.quantizeCoord(1f), regions.ranked(new Climate.TargetPoint(west, 1500, 0, 0, 0, 0), ranks).humidity());
    }

    @Test
    void withoutAreasOneAreaCoversEverything() {
        ClimateRegions regions = new ClimateRegions(List.of("temperature", "humidity"), List.of(0f), 1024);
        assertEquals(1, regions.effectiveAreas().size());
        assertEquals(List.of(0f), regions.effectiveAreas().get(0).columns());
        ClimateRegions.AreaRanks r = ClimateRegions.measureArea(new long[][]{{-2, -1, 1, 2}, {10, 20, 30, 40}}, List.of(0f));
        assertEquals(2, r.conditional.length);
    }
}
