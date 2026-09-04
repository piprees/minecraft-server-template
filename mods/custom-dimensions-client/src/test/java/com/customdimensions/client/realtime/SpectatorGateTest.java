package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cheap tests, both ways round the plane and at the range boundary. */
class SpectatorGateTest {

    /** An opening whose block is at 1500 on the normal axis, so the surface is 1500.5. */
    private static final double SURFACE = 1500.5;

    @Test
    void aCameraOnTheViewingSideOfAPortalFacingHighIsInFront() {
        assertTrue(SpectatorGate.inFront(SURFACE, true, 1495.0));
    }

    @Test
    void aCameraPastAPortalFacingHighIsNot() {
        assertFalse(SpectatorGate.inFront(SURFACE, true, 1505.0));
    }

    @Test
    void aCameraOnTheViewingSideOfAPortalFacingLowIsInFront() {
        assertTrue(SpectatorGate.inFront(SURFACE, false, 1505.0));
    }

    @Test
    void aCameraPastAPortalFacingLowIsNot() {
        assertFalse(SpectatorGate.inFront(SURFACE, false, 1495.0));
    }

    /** Level with the surface frames nothing, whichever way the opening faces. */
    @Test
    void aCameraLevelWithTheSurfaceIsNotInFrontFromEitherSide() {
        assertFalse(SpectatorGate.inFront(SURFACE, true, SURFACE));
        assertFalse(SpectatorGate.inFront(SURFACE, false, SURFACE));
    }

    @Test
    void theMarginKeepsAnAlmostLevelCameraOut() {
        assertFalse(SpectatorGate.inFront(SURFACE, true, SURFACE - SpectatorGate.PLANE_MARGIN / 2));
        assertTrue(SpectatorGate.inFront(SURFACE, true, SURFACE - SpectatorGate.PLANE_MARGIN * 2));
    }

    @Test
    void theRangeIsMeasuredInBlocksSquared() {
        assertTrue(SpectatorGate.withinRange(SpectatorGate.RANGE * SpectatorGate.RANGE));
        assertFalse(SpectatorGate.withinRange(SpectatorGate.RANGE * SpectatorGate.RANGE + 1.0));
    }

    @Test
    void distanceIsTheSquaredSeparation() {
        assertEquals(25.0, SpectatorGate.distanceSquared(0, 0, 0, 3, 4, 0), 1.0e-9);
    }

    @Test
    void aPortalPastTheRangeIsOutOfRange() {
        double far = SpectatorGate.distanceSquared(0, 0, 0, 0, 0, SpectatorGate.RANGE + 1.0);
        assertFalse(SpectatorGate.withinRange(far));
    }

    @Test
    void theNearestAllowedCandidateIsChosen() {
        assertEquals(1, SpectatorGate.nearest(
                new double[] {100.0, 4.0, 9.0}, new boolean[] {true, true, true}));
    }

    @Test
    void aNearerCandidateThatIsNotAllowedIsSkipped() {
        assertEquals(2, SpectatorGate.nearest(
                new double[] {100.0, 4.0, 9.0}, new boolean[] {false, false, true}));
    }

    @Test
    void nothingAllowedAnswersNoCandidate() {
        assertEquals(-1, SpectatorGate.nearest(
                new double[] {1.0, 2.0}, new boolean[] {false, false}));
    }

    /** A set that arrives in a different order still answers one portal. */
    @Test
    void aTieTakesTheEarlierCandidate() {
        assertEquals(0, SpectatorGate.nearest(
                new double[] {4.0, 4.0}, new boolean[] {true, true}));
    }
}
