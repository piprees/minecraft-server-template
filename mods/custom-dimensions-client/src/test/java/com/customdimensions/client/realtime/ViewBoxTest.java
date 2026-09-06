package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the local view box is allowed to cost and what shape it has to keep.
 *
 * <p>{@code RealtimeView.build} walks every cell of it on END_CLIENT_TICK, so
 * the budget is a main-thread budget: holding the old mesh across a rebuild
 * hides the mesh build, not this walk.
 */
class ViewBoxTest {

    /** The nexus rig's opening: x 1500-1501, y 101-103, normal Z, drawn high. */
    private static final int MIN_A = 1500;
    private static final int MAX_A = 1501;
    private static final int MIN_B = 101;
    private static final int MAX_B = 103;
    private static final int PLANE = 1500;

    /** Cells of one rebuild's main-thread walk at that opening. */
    private static final int CELL_BUDGET = 64_000;

    private static LocalVolume rigBox(int depth, int radius) {
        return LocalVolume.of(MIN_A, MAX_A, MIN_B, MAX_B, PLANE, true, depth, radius);
    }

    /**
     * Depth alone reintroduces the straight edge: past {@code DEPTH /
     * CONE_RATIO} lateral blocks the sightline cone leaves the box sideways.
     */
    @Test
    void depthAndRadiusMoveTogether() {
        assertTrue(RealtimeView.RADIUS * RealtimeView.CONE_RATIO >= RealtimeView.DEPTH,
                "DEPTH " + RealtimeView.DEPTH + " over RADIUS " + RealtimeView.RADIUS
                        + ": the cone leaves the box before the far edge");
    }

    @Test
    void theRigBoxStaysInsideItsMainThreadBudget() {
        int cells = rigBox(RealtimeView.DEPTH, RealtimeView.RADIUS).cells();
        assertTrue(cells <= CELL_BUDGET,
                "one rebuild walks " + cells + " cells on END_CLIENT_TICK, over the "
                        + CELL_BUDGET + " budget");
    }

    /** The box has to reach past the render-distance-independent near ground. */
    @Test
    void theBoxReachesTwoChunksPastTheOpening() {
        assertTrue(rigBox(RealtimeView.DEPTH, RealtimeView.RADIUS).sizeN() >= 32,
                "the view stops inside two chunks and reads as a diorama");
    }
}
