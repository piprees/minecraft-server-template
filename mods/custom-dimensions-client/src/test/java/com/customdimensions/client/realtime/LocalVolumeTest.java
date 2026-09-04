package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The box the local view reads, at the nexus rig's own numbers.
 *
 * <p>The rig: aperture x 1500-1501, y 101-103, z 1500, normal Z, viewer on the
 * low side at z 1495.5 so the side drawn is the high one.
 */
class LocalVolumeTest {

    private static final int DEPTH = 16;
    private static final int RADIUS = 8;

    @Test
    void theBoxStartsOnePastTheOpeningOnTheSideBeingLookedInto() {
        LocalVolume high = LocalVolume.of(1500, 1501, 101, 103, 1500, true, DEPTH, RADIUS);
        assertEquals(1501, high.originN());
        assertEquals(DEPTH, high.sizeN());
    }

    /** From the other side the box runs back, ending one short of the opening. */
    @Test
    void theBoxRunsTheOtherWayFromTheOtherSide() {
        LocalVolume low = LocalVolume.of(1500, 1501, 101, 103, 1500, false, DEPTH, RADIUS);
        assertEquals(1500 - DEPTH, low.originN());
        assertEquals(1499, low.originN() + low.sizeN() - 1);
    }

    @Test
    void theInPlaneAxesAreWidenedBothWays() {
        LocalVolume box = LocalVolume.of(1500, 1501, 101, 103, 1500, true, DEPTH, RADIUS);
        assertEquals(1492, box.originA());
        assertEquals(18, box.sizeA());
        assertEquals(93, box.originB());
        assertEquals(19, box.sizeB());
        assertEquals(18 * 19 * 16, box.cells());
    }

    /** A one-cell opening is a box, not an empty one. */
    @Test
    void aSingleCellOpeningStillHasABox() {
        LocalVolume box = LocalVolume.of(0, 0, 0, 0, 0, true, 1, 0);
        assertEquals(1, box.sizeA());
        assertEquals(1, box.sizeB());
        assertEquals(1, box.sizeN());
        assertEquals(1, box.cells());
    }

    /** Depth below one would describe nothing and read as "no view". */
    @Test
    void aDepthBelowOneIsRaisedRatherThanCollapsingTheBox() {
        assertEquals(1, LocalVolume.of(0, 1, 0, 2, 5, true, 0, 0).sizeN());
        assertEquals(1, LocalVolume.of(0, 1, 0, 2, 5, true, -4, 0).sizeN());
    }

    @Test
    void aNegativeRadiusNarrowsNothing() {
        LocalVolume box = LocalVolume.of(0, 1, 0, 2, 5, true, 4, -3);
        assertEquals(2, box.sizeA());
        assertEquals(3, box.sizeB());
        assertEquals(0, box.originA());
    }

    /** Negative coordinates keep the same shape; nothing here rounds. */
    @Test
    void aBoxAcrossTheOriginKeepsItsShape() {
        LocalVolume box = LocalVolume.of(-3, -2, -70, -68, -9, false, 4, 2);
        assertEquals(-5, box.originA());
        assertEquals(6, box.sizeA());
        assertEquals(-72, box.originB());
        assertEquals(7, box.sizeB());
        assertEquals(-13, box.originN());
    }
}
