package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the local view box has to keep, and what one tick of reading it costs.
 *
 * <p>The walk is sliced across ticks, so the box no longer sets the frame
 * budget — {@code UNITS_PER_TICK} does, and that is what this holds down.
 */
class ViewBoxTest {

    /** The nexus rig's opening: x 1500-1501, y 101-103, normal Z, drawn high. */
    private static final int MIN_A = 1500;
    private static final int MAX_A = 1501;
    private static final int MIN_B = 101;
    private static final int MAX_B = 103;
    private static final int PLANE = 1500;

    /**
     * Units one tick may read. Measured sliced: 4,096 units cost 273us average
     * and 713us peak, so a unit is 173ns and this budget is about 1.4ms.
     * Raising it needs a fresh {@code realtimeBuildUs} reading.
     */
    private static final int TICK_BUDGET = 8_192;

    /**
     * Ticks a full rebuild may take before first sight is a long blank. Only
     * first sight pays it — a rebuild draws the mesh carried from the view it
     * replaces throughout.
     */
    private static final int MAX_SLICES = 24;

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

    /** The tick pays the slice, never the box. */
    @Test
    void oneTickReadsNoMoreThanTheBudget() {
        assertTrue(RealtimeView.UNITS_PER_TICK <= TICK_BUDGET,
                "a tick reads " + RealtimeView.UNITS_PER_TICK + " units, over the "
                        + TICK_BUDGET + " budget");
    }

    /**
     * Latency is what the box costs now. A rebuild is hidden by the mesh
     * carried from the view it replaces, but first sight is blank throughout.
     */
    @Test
    void aFullRebuildFinishesInsideTheLatencyBudget() {
        int units = readUnits();
        int slices = (units + RealtimeView.UNITS_PER_TICK - 1) / RealtimeView.UNITS_PER_TICK;
        assertTrue(slices <= MAX_SLICES,
                "a full rebuild takes " + slices + " ticks (" + (slices * 50) + "ms), over the "
                        + MAX_SLICES + " the budget allows");
    }

    /** What the walk actually reads: the cone's cells, and every column. */
    private static int readUnits() {
        LocalVolume box = rigBox(RealtimeView.DEPTH, RealtimeView.RADIUS);
        int cells = ViewShape.cells(MAX_A - MIN_A + 1, MAX_B - MIN_B + 1,
                box.sizeN(), RealtimeView.NEAR_RADIUS, RealtimeView.CONE_RATIO);
        return cells + box.sizeA() * box.sizeN();
    }
}
