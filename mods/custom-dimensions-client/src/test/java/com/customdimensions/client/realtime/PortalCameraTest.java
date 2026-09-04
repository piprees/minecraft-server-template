package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The camera on the far side.
 *
 * <p>The scale-1 and scale-4 cases are the ones the plan calls the trap, so
 * they are asserted against MEASURED numbers rather than derived ones. The
 * nexus figures come from a real crossing: an arrow summoned at overworld
 * x 1500.3 was logged by the server crossing into {@code the_crimson_nexus}
 * at x 750.30, with the interior's integer-averaged column 1500 and scale 2.0
 * giving {@code dx = round(1500 / 2) - 1500 = -750}.
 */
class PortalCameraTest {

    private static final double TOLERANCE = 1.0e-9;

    @Test
    void theCameraIsTheViewerTranslatedAndNothingElse() {
        assertArrayEquals(new double[] {751.5, 56.0, 745.5},
                PortalCamera.destinationEye(1501.5, 100.0, 1495.5, -750, -44, -750), TOLERANCE);
    }

    /**
     * The measured crossing, reproduced by the camera's own arithmetic. If
     * this drifts, the view and the entities that travel through it disagree
     * about where the far side is.
     */
    @Test
    void theTransformAgreesWithAMeasuredCrossingAtScaleTwo() {
        assertEquals(750.30, PortalCamera.translate(1500.30, -750), 1.0e-9);
    }

    /**
     * Scale 4 differs only in the NUMBERS, never in the shape of the sum. A
     * camera that divided by the scale would be right at scale 1 and wrong
     * here, which is the whole trap.
     */
    @Test
    void scaleFourIsTheSameArithmeticWithDifferentNumbers() {
        // the_crucible: overworld column 3260 at scale 4 arrives at 815.
        int dx = Math.round(3260 / 4.0f) - 3260;
        assertEquals(-2445, dx);
        assertEquals(815.5, PortalCamera.translate(3260.5, dx), TOLERANCE);

        // Scale 1: the offset is zero and the camera does not move at all.
        assertEquals(3260.5, PortalCamera.translate(3260.5, 0), TOLERANCE);
    }

    @Test
    void aScaleOnePortalLeavesTheCameraWhereItStands() {
        assertArrayEquals(new double[] {10.5, 64.0, -3.25},
                PortalCamera.destinationEye(10.5, 64.0, -3.25, 0, 0, 0), TOLERANCE);
    }

    // ---- the near plane -------------------------------------------------

    @Test
    void thePlaneMovesWithTheSameOffsetTheCameraDoes() {
        assertEquals(750.5, PortalCamera.destinationPlane(1500, -750), TOLERANCE);
    }

    /**
     * The camera on the +N side sees only -N of the plane, and the mirror
     * case must hold too — a hardcoded sign is right half the time, which is
     * the hardest kind of wrong to spot in a screenshot.
     */
    @Test
    void onlyTheFarSideOfThePlaneIsDrawn() {
        assertTrue(PortalCamera.beyondPlane(740.0, 750.5, 755.0), "the far side was culled");
        assertFalse(PortalCamera.beyondPlane(760.0, 750.5, 755.0),
                "geometry between the camera and the portal was kept, and it occludes the view");

        assertTrue(PortalCamera.beyondPlane(760.0, 750.5, 745.0));
        assertFalse(PortalCamera.beyondPlane(740.0, 750.5, 745.0));
    }

    @Test
    void aPointInThePlaneIsNotBeyondIt() {
        assertFalse(PortalCamera.beyondPlane(750.5, 750.5, 755.0));
    }

    @Test
    void aCameraInThePlaneDrawsNothingRatherThanGuessingASide() {
        assertFalse(PortalCamera.beyondPlane(740.0, 750.5, 750.5));
        assertEquals(0.0, PortalCamera.depthBeyondPlane(740.0, 750.5, 750.5), TOLERANCE);
    }

    /** Positive beyond the plane, negative in front of it, from either side. */
    @Test
    void theClipDistanceIsSignedTheSameWayFromBothSides() {
        assertEquals(10.5, PortalCamera.depthBeyondPlane(740.0, 750.5, 755.0), TOLERANCE);
        assertEquals(-9.5, PortalCamera.depthBeyondPlane(760.0, 750.5, 755.0), TOLERANCE);

        assertEquals(9.5, PortalCamera.depthBeyondPlane(760.0, 750.5, 745.0), TOLERANCE);
        assertEquals(-10.5, PortalCamera.depthBeyondPlane(740.0, 750.5, 745.0), TOLERANCE);
    }
}
