package com.customdimensions.client.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OpeningTest {
    /** A two-wide, three-tall upright opening on the x axis at z = 8, as the rig builds it. */
    static Opening rig() {
        return Opening.of(List.of(new int[] {8, 181, 8}, new int[] {9, 181, 8}, new int[] {8, 182, 8},
                new int[] {9, 182, 8}, new int[] {8, 183, 8}, new int[] {9, 183, 8}), 0);
    }

    @Test
    void anXAxisPortalFacesAlongZWithItsRectangleOnXAndY() {
        Opening o = rig();
        assertEquals(2, o.normalAxis());
        assertEquals(8, o.plane());
        assertEquals(8, o.a0());
        assertEquals(10, o.a1());
        assertEquals(181, o.b0());
        assertEquals(184, o.b1());
    }

    @Test
    void zAndFlatPortalsPickTheirOwnNormals() {
        assertEquals(0, Opening.of(List.<int[]>of(new int[] {4, 70, 2}), 2).normalAxis());
        Opening flat = Opening.of(List.<int[]>of(new int[] {4, 70, 2}, new int[] {5, 70, 3}), 1);
        assertEquals(1, flat.normalAxis());
        assertEquals(70, flat.plane());
        assertEquals(4, flat.a0());
        assertEquals(6, flat.a1());
        assertEquals(2, flat.b0());
        assertEquals(4, flat.b1());
        assertThrows(IllegalArgumentException.class, () -> Opening.of(List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> Opening.of(List.<int[]>of(new int[] {0, 0, 0}), 3));
    }

    @Test
    void theSurfaceSitsInsideTheNearFaceOnEitherSide() {
        Opening o = rig();
        assertTrue(o.towardsHigh(5.5));
        assertFalse(o.towardsHigh(12));
        assertEquals(8.02, o.surface(true), 1e-9);
        assertEquals(8.98, o.surface(false), 1e-9);
        assertEquals(9, o.firstLayer(true));
        assertEquals(7, o.firstLayer(false));
    }

    @Test
    void anEyeInTheOpeningIsContained() {
        Opening o = rig();
        assertTrue(o.contains(new double[] {9, 182.6, 8.5}));
        assertFalse(o.contains(new double[] {9, 182.6, 5.5}));
        assertFalse(o.contains(new double[] {12, 182.6, 8.5}));
    }

    @Test
    void theConeKeepsWhatIsSeenThroughTheOpeningAndNothingBesideIt() {
        Opening o = rig();
        double[] eye = {9, 182.6, 5.5};
        double[][] planes = o.clipPlanes(eye);
        assertEquals(5, planes.length);
        assertTrue(inside(planes, new double[] {9, 182.6, 20}), "straight through the middle");
        assertTrue(inside(planes, new double[] {14, 182.6, 30}), "oblique but through the opening");
        assertFalse(inside(planes, new double[] {20, 182.6, 12}), "beside the frame");
        assertFalse(inside(planes, new double[] {9, 182.6, 7}), "between the eye and the surface");
        assertFalse(inside(planes, new double[] {9, 182.6, 0}), "behind the eye: the mirror cone");
    }

    @Test
    void conePlanesFlipWhenTheEyeIsOnTheOtherSide() {
        Opening o = rig();
        double[][] planes = o.clipPlanes(new double[] {9, 182.6, 12});
        assertTrue(inside(planes, new double[] {9, 182.6, 1}));
        assertFalse(inside(planes, new double[] {9, 182.6, 9.5}));
    }

    @Test
    void coneCellsStartPastTheOpeningAndGrowWithDepth() {
        Opening o = rig();
        List<int[]> cells = o.cellsInCone(new double[] {9, 182.6, 5.5}, 10, 48);
        assertFalse(cells.isEmpty());
        int minZ = cells.stream().mapToInt(c -> c[2]).min().orElseThrow();
        int maxZ = cells.stream().mapToInt(c -> c[2]).max().orElseThrow();
        assertEquals(9, minZ);
        assertEquals(18, maxZ);
        long first = cells.stream().filter(c -> c[2] == 9).count();
        long last = cells.stream().filter(c -> c[2] == 18).count();
        assertTrue(last > first, "the cross-section widens: " + first + " then " + last);
        assertTrue(cells.stream().anyMatch(c -> c[0] == 9 && c[1] == 182 && c[2] == 12));
    }

    @Test
    void coneCellsAreClampedToTheRadius() {
        Opening o = rig();
        List<int[]> cells = o.cellsInCone(new double[] {9, 182.6, 7.9}, 40, 6);
        for (int[] c : cells) {
            assertTrue(Math.abs(c[0] - 9) <= 6 && Math.abs(c[1] - 182) <= 6, "within the radius");
        }
    }

    @Test
    void anEyeOnTheSurfaceSeesNothing() {
        Opening o = rig();
        assertTrue(o.cellsInCone(new double[] {9, 182.6, 8.02}, 10, 48).isEmpty());
    }

    static boolean inside(double[][] planes, double[] p) {
        for (double[] pl : planes) {
            if (pl[0] * p[0] + pl[1] * p[1] + pl[2] * p[2] + pl[3] < 0) {
                return false;
            }
        }
        return true;
    }
}
