package com.customdimensions.server.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.Test;

class ClimateRegionsTest {
    private static final long[] SAMPLES = {-5000, -1000, 0, 0, 2000, 9000};

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
}
