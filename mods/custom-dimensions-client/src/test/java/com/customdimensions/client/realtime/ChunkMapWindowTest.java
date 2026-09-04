package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window vanilla will accept a chunk into. Copied rules, so they are
 * asserted rather than remembered: a wrong radius here does not throw, it
 * drops the outer ring of every destination and reads as the feed stalling.
 */
class ChunkMapWindowTest {

    /** {@code max(2, loadDistance) + 3}, read out of 1.21.1's own bytecode. */
    @Test
    void theRadiusIsVanillasOwnFormula() {
        assertEquals(5, ChunkMapWindow.radiusFor(0));
        assertEquals(5, ChunkMapWindow.radiusFor(2));
        assertEquals(11, ChunkMapWindow.radiusFor(8));
        assertEquals(19, ChunkMapWindow.radiusFor(16));
    }

    /**
     * The test is Chebyshev. A Euclidean one would reject the corners of the
     * square vanilla actually keeps, so half the fed wedge would be thrown
     * away at oblique angles and nowhere else.
     */
    @Test
    void theWindowIsASquareNotACircle() {
        assertTrue(ChunkMapWindow.inRange(46, 46, 46 + 11, 46 + 11, 8),
                "the corner of the square was rejected, so the test is Euclidean");
        assertFalse(ChunkMapWindow.inRange(46, 46, 46 + 12, 46, 8));
        assertFalse(ChunkMapWindow.inRange(46, 46, 46, 46 - 12, 8));
    }

    @Test
    void theCentreIsAlwaysInsideIts0wnWindow() {
        assertTrue(ChunkMapWindow.inRange(-750, 46, -750, 46, 2));
    }

    /**
     * The invariant that matters across the two mods: whatever radius the
     * server feeds, the window must already cover it. A destination stood up
     * too small loses its outer ring on arrival, and nothing says so but a
     * vanilla warning nobody is watching for.
     */
    @Test
    void everyFedChunkFitsTheWindowStoodUpForIt() {
        for (int feedRadius = 1; feedRadius <= 16; feedRadius++) {
            int loadDistance = ChunkMapWindow.loadDistanceFor(feedRadius);
            assertTrue(ChunkMapWindow.radiusFor(loadDistance) >= feedRadius,
                    "radius " + feedRadius + " is fed but not accepted");
            // The furthest chunk the feed's disc can reach on either axis.
            assertTrue(ChunkMapWindow.inRange(46, 46, 46 + feedRadius, 46, loadDistance));
            assertTrue(ChunkMapWindow.inRange(46, 46, 46, 46 - feedRadius, loadDistance));
        }
    }

    @Test
    void aLoadDistanceIsNeverBelowVanillasFloor() {
        assertEquals(2, ChunkMapWindow.loadDistanceFor(0));
        assertEquals(2, ChunkMapWindow.loadDistanceFor(1));
        assertEquals(16, ChunkMapWindow.loadDistanceFor(16));
    }
}
