package com.customdimensions.core.survey;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The landform classifier on synthetic sky-island tops, as the compiler's {@code floating_islands} shapes them:
 * four masses about 900 blocks across over a void, each tilted by an altitude spread that rises and falls by
 * {@code spread} blocks over 256, with tops from the dome {@code sqrt(core)} ({@code flatness} 0) to the table
 * {@code sqrt(min(1, core / 0.15))} ({@code flatness} 1). Each claim is measured under the classifier's own thresholds.
 */
class LandformsTopsTest {

    private static final int SIDE = 256, STEP_CELLS = 8;
    private static final int[][] MASSES = {{64, 64, 56, 140}, {192, 64, 56, 156}, {64, 192, 56, 150}, {192, 192, 56, 166}};
    private static final double THICKNESS = 18, FLAT_EDGE = 0.15;

    private static Landforms sky(double flatness, double spread) {
        int[] h = new int[SIDE * SIDE];
        byte[] kind = new byte[SIDE * SIDE];
        java.util.Arrays.fill(kind, (byte) 2);
        double edge = 1 - (1 - FLAT_EDGE) * flatness;
        for (int[] m : MASSES) {
            for (int z = 0; z < SIDE; z++) {
                for (int x = 0; x < SIDE; x++) {
                    double r = Math.hypot(x - m[0], z - m[1]) / m[2];
                    if (r >= 1) continue;
                    double core = Math.min(1, 2 * (1 - r));
                    double bx = x * STEP_CELLS, bz = z * STEP_CELLS;
                    double layer = m[3] + spread * Math.sin(2 * Math.PI * bx / 256) * Math.cos(2 * Math.PI * bz / 256);
                    h[z * SIDE + x] = (int) Math.round(layer + THICKNESS * Math.sqrt(Math.min(1, core / edge)) - 2);
                    kind[z * SIDE + x] = 0;
                }
            }
        }
        return new Landforms(SIDE, STEP_CELLS, 0, 0, h, kind);
    }

    private static double level(Landforms l) {
        return l.share(Landforms.Form.PLAINS) + l.share(Landforms.Form.PLATEAUS);
    }

    private static String shares(String name, Landforms l) {
        return name + ": plateaus " + l.share(Landforms.Form.PLATEAUS) + ", plains " + l.share(Landforms.Form.PLAINS) + ", hills "
                + l.share(Landforms.Form.HILLS) + ", mountains " + l.share(Landforms.Form.MOUNTAINS) + ", basins "
                + l.share(Landforms.Form.BASINS) + ", land " + (100 - l.share(Landforms.Form.VOID));
    }

    /**
     * At a low spread every top shape is level ground: the dome already reads as plains at this scale, and a
     * flat top keeps most of it level, its short roll-off reading as hills round the rim. At a spread of 24 the
     * tilt, not the top, sets the shares: under half the land is level whatever the top's shape.
     */
    @Test
    void theSpreadNotTheTopShapeDecidesHowMuchOfASkyIslandReadsAsLevelGround() {
        StringBuilder all = new StringBuilder();
        for (double spread : new double[] {0, 4, 24}) {
            Landforms dome = sky(0, spread), half = sky(0.5, spread), table = sky(1, spread);
            all.append("\nspread ").append(spread).append(": ")
                    .append(String.join("; ", shares("dome", dome), shares("flatness 0.5", half), shares("flatness 1", table)));
            double land = 100 - dome.share(Landforms.Form.VOID);
            if (spread <= 4) {
                assertTrue(level(dome) >= 0.95 * land, all.toString());
                assertTrue(level(half) >= 0.8 * land, all.toString());
                assertTrue(level(table) >= 0.75 * land, all.toString());
            } else {
                for (Landforms l : new Landforms[] {dome, half, table}) assertTrue(level(l) < 0.5 * land, all.toString());
            }
        }
    }
}
