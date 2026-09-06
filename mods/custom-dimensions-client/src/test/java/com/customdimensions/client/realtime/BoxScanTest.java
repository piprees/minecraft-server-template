package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sliced walk: what a tick is allowed to read, and that reading it in
 * slices covers the box exactly once.
 *
 * <p>Walking the whole box in one tick is a stall proportional to DEPTH, which
 * is what bounded the box. A slice bounds the tick instead.
 */
class BoxScanTest {

    private static final int SIZE_X = 34;
    private static final int SIZE_Y = 35;
    private static final int SIZE_Z = 48;

    /** The payload's own storage index, which the walk must invert exactly. */
    private static int index(int lx, int ly, int lz) {
        return ((lx * SIZE_Z) + lz) * SIZE_Y + ly;
    }

    @Test
    void aSliceNeverReadsMoreThanItsBudget() {
        BoxScan scan = BoxScan.of(SIZE_X, SIZE_Y, SIZE_Z);
        int budget = 4_096;
        int guard = 0;
        while (!scan.done() && guard++ < 10_000) {
            int end = scan.end(budget);
            assertTrue(end - scan.cursor() <= budget,
                    "a slice read " + (end - scan.cursor()) + " units on one tick, over the "
                            + budget + " budget");
            scan = scan.advancedTo(end);
        }
        assertTrue(scan.done(), "the walk never finished");
    }

    @Test
    void theSlicesCoverEveryUnitExactlyOnce() {
        BoxScan scan = BoxScan.of(4, 5, 6);
        Set<Integer> seen = new HashSet<>();
        while (!scan.done()) {
            int end = scan.end(7);
            for (int unit = scan.cursor(); unit < end; unit++) {
                assertTrue(seen.add(unit), "unit " + unit + " was read twice");
            }
            scan = scan.advancedTo(end);
        }
        assertEquals(4 * 5 * 6 + 4 * 6, seen.size(), "the walk skipped units");
    }

    /** Cells then columns: a tint needs every cell of its own column already read. */
    @Test
    void theColumnsComeAfterTheCells() {
        BoxScan scan = BoxScan.of(4, 5, 6);
        assertEquals(4 * 5 * 6, scan.cells());
        assertEquals(4 * 6, scan.columns());
        assertEquals(4 * 5 * 6 + 4 * 6, scan.units());
    }

    @Test
    void aCellIndexInvertsToItsOwnCoordinates() {
        for (int lx = 0; lx < SIZE_X; lx += 7) {
            for (int ly = 0; ly < SIZE_Y; ly += 5) {
                for (int lz = 0; lz < SIZE_Z; lz += 11) {
                    int index = index(lx, ly, lz);
                    assertEquals(lx, BoxScan.localX(index, SIZE_Y, SIZE_Z));
                    assertEquals(ly, BoxScan.localY(index, SIZE_Y));
                    assertEquals(lz, BoxScan.localZ(index, SIZE_Y, SIZE_Z));
                }
            }
        }
    }

    @Test
    void aColumnUnitInvertsToItsInPlaneCoordinates() {
        int cells = SIZE_X * SIZE_Y * SIZE_Z;
        for (int lx = 0; lx < SIZE_X; lx += 9) {
            for (int lz = 0; lz < SIZE_Z; lz += 13) {
                int unit = cells + (lx * SIZE_Z) + lz;
                assertEquals(lx, BoxScan.columnX(unit, cells, SIZE_Z));
                assertEquals(lz, BoxScan.columnZ(unit, cells, SIZE_Z));
            }
        }
    }

    /** A zero or negative budget must still move, or the walk never finishes. */
    @Test
    void aSliceAlwaysAdvances() {
        BoxScan scan = BoxScan.of(2, 2, 2);
        assertEquals(1, scan.end(0) - scan.cursor());
        assertEquals(1, scan.end(-5) - scan.cursor());
    }

    @Test
    void aWalkIsNotDoneBeforeItsLastUnit() {
        BoxScan scan = BoxScan.of(2, 2, 2);
        assertFalse(scan.done());
        scan = scan.advancedTo(scan.units() - 1);
        assertFalse(scan.done(), "the walk reported done with a unit still unread");
        assertTrue(scan.advancedTo(scan.units()).done());
    }
}
