package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The view answers light from the grid, and its lighting provider is the
 * SOURCE world's — wrong for every destination position. Nothing here calls
 * it, and vanilla's block renderer is not seen to, but that is an assumption
 * about someone else's code.
 *
 * <p>So it is counted. {@code viewLightingReads} on the dev bridge is
 * monotonic: above zero at any point in a session means a caller took the
 * source world's lighting for a destination block, and the assumption in the
 * javadoc is false on this client's mod set.
 */
class ProjectionViewAssumptionTest {

    /**
     * A counter that resets would hide the one read that matters, since
     * nothing reads the bridge at the moment a mesh is built.
     */
    @Test
    void theLightingReadCountIsMonotonic() {
        int first = ProjectionView.lightingReads();
        assertTrue(first >= 0);
        assertEquals(first, ProjectionView.lightingReads(),
                "reading the count changed it, so a later reader sees fewer than happened");
    }
}
