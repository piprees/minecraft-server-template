package com.customdimensions.core.zone;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link LandShare}: the share holds for any spread of samples, and the shift stays bounded. */
class LandShareTest {

    private static double[] samples(long seed, double mean, double spread) {
        Random r = new Random(seed);
        double[] s = new double[LandShare.GRID * LandShare.GRID];
        for (int i = 0; i < s.length; i++) s[i] = mean + spread * r.nextGaussian();
        return s;
    }

    @Test
    void theShareOfLandHoldsForAnySeedAndSpread() {
        for (long seed = 1; seed <= 20; seed++) {
            for (double spread : new double[]{0.05, 0.3, 0.6}) {
                for (double share : new double[]{0.1, 0.5, 0.75, 0.95}) {
                    double[] s = samples(seed, seed % 2 == 0 ? -0.6 : 0.4, spread);
                    double offset = LandShare.offset(s, share, -0.11);
                    assertEquals(share, LandShare.landShare(s, offset, -0.11), 2.0 / s.length, "seed " + seed + " spread " + spread);
                }
            }
        }
    }

    @Test
    void theEndsAndTheClampHold() {
        double[] s = {0.0, 0.1, 0.2, 0.3, 0.4};
        assertEquals(-0.11, LandShare.offset(s, 1, -0.11), 1e-12, "all land puts the coast at the lowest sample");
        assertEquals(-0.51, LandShare.offset(s, 0, -0.11), 1e-12, "no land puts the coast at the highest");
        assertEquals(-0.31, LandShare.offset(s, 0.5, -0.11), 1e-12);
        assertEquals(LandShare.MAX_SHIFT, LandShare.offset(new double[]{-9, -9}, 0.5, 0), 0);
        assertEquals(0, LandShare.offset(new double[0], 0.5, 0), 0);
    }

    @Test
    void theGridSpansTheBorder() {
        assertEquals(-512, LandShare.gridCoordinate(0, 512));
        assertEquals(512, LandShare.gridCoordinate(LandShare.GRID - 1, 512));
    }

    @Test
    void checkerCellsFollowVanillasSource() {
        assertEquals(128, CheckerCells.width(3));
        assertEquals(0, CheckerCells.index(0, 0, 3, 9));
        assertEquals(0, CheckerCells.index(127, 127, 3, 9));
        assertEquals(1, CheckerCells.index(128, 0, 3, 9));
        assertEquals(2, CheckerCells.index(128, 128, 3, 9));
        assertEquals(8, CheckerCells.index(-1, 0, 3, 9));
        assertEquals(7, CheckerCells.index(-129, 0, 3, 9));
        assertEquals(1, CheckerCells.index(32, 0, 1, 2));
    }
}
