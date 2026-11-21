package com.customdimensions.client.view;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 2×3 opening in the plane z = 10 (portal axis x, so its normal runs along
 * z), the eye 4 blocks in front of it on the low side, a model quad standing
 * on the far side.
 */
class ModelClipTest {
    static final Opening OP = Opening.of(List.of(new int[] {0, 64, 10}, new int[] {1, 64, 10}, new int[] {0, 65, 10},
            new int[] {1, 65, 10}, new int[] {0, 66, 10}, new int[] {1, 66, 10}), 0);
    static final double[] EYE = {1.0, 65.5, 6.0};
    static final double EPS = PortalRenderer.EPSILON;

    static double d0() {
        return Math.abs(OP.surface(OP.towardsHigh(EYE[2])) - EYE[2]);
    }

    /** An eye-relative upright quad at world z, spanning x0..x1 and y0..y1, with uv and a white colour. */
    static double[][] quad(double x0, double x1, double y0, double y1, double z) {
        double[][] q = {{x0, y0, z}, {x1, y0, z}, {x1, y1, z}, {x0, y1, z}};
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            out[i] = new double[] {q[i][0] - EYE[0], q[i][1] - EYE[1], q[i][2] - EYE[2], i == 1 || i == 2 ? 1 : 0, i >> 1, 255, 255, 255, 255};
        }
        return out;
    }

    @Test
    void aQuadInsideTheViewSurvivesSquashedOntoTheSlab() {
        List<double[]> poly = ModelClip.clip(quad(0.8, 1.2, 65.2, 65.8, 20), OP.clipPlanes(EYE), EYE, 2, d0(), EPS);
        assertEquals(4, poly.size());
        for (double[] v : poly) {
            assertTrue(v[2] >= d0() && v[2] < d0() + EPS, "on the slab: " + v[2]);
            assertEquals(14.0, v[9], 0.1, "its unsquashed distance rides along");
        }
        double[] v = poly.get(0);
        double q = v[2] / 14.0;
        assertEquals((0.8 - EYE[0]) * q, v[0], 1e-9, "moved along its own ray: its place on screen is kept");
        assertEquals((65.2 - EYE[1]) * q, v[1], 1e-9);
    }

    @Test
    void aQuadBesideTheOpeningIsCulled() {
        assertTrue(ModelClip.clip(quad(8, 9, 65, 66, 20), OP.clipPlanes(EYE), EYE, 2, d0(), EPS).isEmpty());
    }

    @Test
    void aQuadOnTheNearSideIsNotSomethingTheOpeningShows() {
        assertTrue(ModelClip.clip(quad(0.8, 1.2, 65.2, 65.8, 8), OP.clipPlanes(EYE), EYE, 2, d0(), EPS).isEmpty());
    }

    @Test
    void aQuadAcrossTheEdgeIsCutToTheSightline() {
        double[][] planes = OP.clipPlanes(EYE);
        List<double[]> poly = ModelClip.clip(quad(-3, 5, 65.2, 65.8, 14), planes, EYE, 2, d0(), EPS);
        assertFalse(poly.isEmpty());
        // At z 14 the cone through x 0..2 from the eye at x 1, z 6 spans x -1..3 (twice the distance).
        for (double[] v : poly) {
            double k = 8.0 / v[2];
            double worldX = EYE[0] + v[0] * k;
            assertTrue(worldX >= -1 - 1e-6 && worldX <= 3 + 1e-6, "inside the cone: " + worldX);
        }
        assertEquals((4 - 8 / 4.02) / 8, poly.stream().mapToDouble(v -> v[3]).min().orElseThrow(), 0.01,
                "u is cut with the quad");
    }

    @Test
    void theSphereCullAgreesWithThePlanes() {
        double[][] planes = OP.clipPlanes(EYE);
        assertFalse(ModelClip.outside(planes, new double[] {1, 65.5, 20}, 0.5));
        assertTrue(ModelClip.outside(planes, new double[] {30, 65.5, 20}, 2));
        assertFalse(ModelClip.outside(planes, new double[] {4.5, 65.5, 20}, 2), "a sphere across the edge is kept");
        assertTrue(ModelClip.outside(planes, new double[] {1, 65.5, 7}, 1), "a sphere on the near side is culled");
    }

    @Test
    void aDestinationPositionIsDrawnAtTheSourceAndBoundedByItsSize() {
        assertArrayEquals(new double[] {21, -60, 2}, ModelClip.source(new double[] {21, -60, 52}, 0, 0, 50), 1e-9);
        assertArrayEquals(new double[] {10, 64.975, -4, 2.95},
                ModelClip.bounds(new double[] {10, 64, -4}, 0.6, 1.95), 1e-9);
    }

    @Test
    void polygonsBecomeQuadsAsTheBlocksDo() {
        assertArrayEquals(new int[][] {{0, 1, 2, 2}}, ModelClip.quads(3));
        assertArrayEquals(new int[][] {{0, 1, 2, 3}}, ModelClip.quads(4));
        assertArrayEquals(new int[][] {{0, 1, 2, 3}, {0, 3, 4, 4}}, ModelClip.quads(5));
        assertEquals(0, ModelClip.quads(2).length);
    }
}
