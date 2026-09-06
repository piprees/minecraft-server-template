package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Light outside the box comes from the nearest cell inside it. Outside is
 * unread world, not dark world, and answering 0 there lit the boundary against
 * a neighbour claiming pitch black ({@code TROUBLESHOOTING.md#t108}).
 *
 * <p>Only the clamp is covered. {@code lightAt} itself needs a
 * {@code ClientProjection}, which needs {@code Blocks.AIR} and a bootstrap
 * this module's test classpath cannot run.
 */
class GridClampTest {

    @Test
    void aCoordinateInsideTheBoxIsItself() {
        for (int local = 0; local < 34; local++) {
            assertEquals(local, ClientProjection.clamp(local, 34));
        }
    }

    @Test
    void aCoordinateBelowTheBoxTakesTheNearEdge() {
        assertEquals(0, ClientProjection.clamp(-1, 34));
        assertEquals(0, ClientProjection.clamp(-4096, 34));
    }

    @Test
    void aCoordinateAboveTheBoxTakesTheFarEdge() {
        assertEquals(33, ClientProjection.clamp(34, 34));
        assertEquals(33, ClientProjection.clamp(4096, 34));
    }

    /** A one-cell axis has one legal index, so both directions land on it. */
    @Test
    void aSingleCellAxisAlwaysAnswersZero() {
        assertEquals(0, ClientProjection.clamp(-1, 1));
        assertEquals(0, ClientProjection.clamp(0, 1));
        assertEquals(0, ClientProjection.clamp(9, 1));
    }
}
