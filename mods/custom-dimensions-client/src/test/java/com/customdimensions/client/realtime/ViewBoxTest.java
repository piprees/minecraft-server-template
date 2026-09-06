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
     * Units one tick may read however cheap they are. The clock is the real
     * bound; this only stops a slice whose every unit skips.
     */
    private static final int TICK_BUDGET = 8_192;

    /**
     * Wall clock a slice may spend on END_CLIENT_TICK. A sixteen-millisecond
     * frame cannot afford much of one, and a unit's cost spans 27ns to 710ns
     * depending on what it reads, so this is the only bound that holds.
     */
    private static final long SLICE_CEILING_NANOS = 2_000_000L;

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

    /** The frame guarantee. A count cannot bound a duration; the clock can. */
    @Test
    void aSliceIsBoundedByTheClock() {
        assertTrue(RealtimeView.SLICE_BUDGET_NANOS <= SLICE_CEILING_NANOS,
                "a slice may spend " + RealtimeView.SLICE_BUDGET_NANOS / 1000
                        + "us on the client thread, over the "
                        + SLICE_CEILING_NANOS / 1000 + "us ceiling");
        assertTrue(RealtimeView.SLICE_BUDGET_NANOS > 0, "the clock bound is off");
    }

    /**
     * Latency when the reads are cheap. This is a FLOOR: a slice stopped by
     * the clock reads fewer units, so a dense box takes more ticks than this,
     * and how many is a measurement rather than arithmetic.
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
