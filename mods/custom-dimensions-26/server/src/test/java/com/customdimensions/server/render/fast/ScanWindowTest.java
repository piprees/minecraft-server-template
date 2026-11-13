package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ScanWindowTest {
    static final int LO = -64;
    static final int HI = 319;

    static ColumnRuns run(int bottom, int top) {
        boolean[] solid = new boolean[HI - LO + 1];
        for (int y = bottom; y <= top; y++) solid[y - LO] = true;
        return ColumnRuns.of(solid, LO, solid.length);
    }

    /** A coarse grid of 4 × 4 columns, 4 grid pixels apart, every top at {@code top} but one at {@code peak}. */
    static ScanWindow window(String mode, int top, int peak, int lowest) {
        int[] tops = new int[16];
        Arrays.fill(tops, top);
        tops[5] = peak;
        return new ScanWindow(mode, LO, HI, 8, 4, 4, tops, lowest);
    }

    @Test
    void modeFollowsTheRunsFound() {
        assertEquals(ScanWindow.FULL, ScanWindow.mode(1000, 30, 0));
        assertEquals(ScanWindow.TIERS, ScanWindow.mode(1000, 0, 400));
        assertEquals(ScanWindow.SURFACE, ScanWindow.mode(1000, 10, 10));
    }

    @Test
    void surfaceWindowSpansTheTilesNeighbourhoodOnCellRows() {
        ScanWindow w = window(ScanWindow.SURFACE, 70, 101, LO);
        assertArrayEquals(new int[] {32, 119}, w.window(0, 0, 4, 4), "70 - 32 down to a cell row, 101 + 16 up to a cell's top");
        assertArrayEquals(new int[] {32, 87}, w.window(12, 12, 4, 4), "the peak is more than one coarse spacing away");
    }

    @Test
    void tiersWindowStartsUnderTheLowestRunFound() {
        ScanWindow w = window(ScanWindow.TIERS, 150, 200, 90);
        assertArrayEquals(new int[] {72, 223}, w.window(0, 0, 4, 4));
    }

    @Test
    void fullModeAndEmptyNeighbourhoodsScanWholeColumns() {
        assertArrayEquals(new int[] {LO, HI}, window(ScanWindow.FULL, 70, 70, LO).window(0, 0, 4, 4));
        int[] none = new int[16];
        Arrays.fill(none, Integer.MIN_VALUE);
        assertArrayEquals(new int[] {LO, HI}, new ScanWindow(ScanWindow.SURFACE, LO, HI, 8, 4, 4, none, LO).window(0, 0, 4, 4));
        assertEquals(ScanWindow.FULL, new ScanWindow(ScanWindow.SURFACE, LO, HI, 0, 4, 4, none, LO).mode(), "no cell to align to");
    }

    @Test
    void groundAtAnEdgeWidensTheWindow() {
        ScanWindow surface = window(ScanWindow.SURFACE, 70, 70, LO);
        assertNull(surface.widen(new ColumnRuns[] {run(32, 70)}, 32, 95));
        assertArrayEquals(new int[] {32, HI}, surface.widen(new ColumnRuns[] {run(32, 95)}, 32, 95), "ground at the top");
        assertArrayEquals(new int[] {LO, 95}, surface.widen(new ColumnRuns[] {ColumnRuns.EMPTY}, 32, 95), "a column with no ground");
        ScanWindow tiers = window(ScanWindow.TIERS, 150, 150, 90);
        assertArrayEquals(new int[] {LO, 175}, tiers.widen(new ColumnRuns[] {run(72, 80)}, 72, 175), "ground at the floor");
        assertNull(tiers.widen(new ColumnRuns[] {ColumnRuns.EMPTY}, 72, 175), "an empty sky column is fine");
        assertEquals(3, surface.rescanned() + tiers.rescanned());
    }

    /** A roofed coarse grid in blocks: 4 × 4 columns 64 blocks apart from (-128, -128), floors at {@code floor} but one at {@code low}. */
    static ScanWindow floors(int floor, int low, int start) {
        int[] tops = new int[16];
        Arrays.fill(tops, 255);
        int[] f = new int[16];
        Arrays.fill(f, floor);
        f[5] = low;
        return new ScanWindow(ScanWindow.FULL, 0, 255, 8, -128, -128, 64, 4, tops, 0, f, start).as(ScanWindow.FLOOR);
    }

    @Test
    void floorWindowRunsFromUnderTheFloorsNearbyToTheStart() {
        ScanWindow w = floors(90, 60, 200);
        assertEquals(ScanWindow.FLOOR, w.mode());
        assertArrayEquals(new int[] {24, 200}, w.windowAt(-128, -128, -113, -113), "60 - 32 down to a cell row");
        assertArrayEquals(new int[] {56, 200}, w.windowAt(64, 64, 79, 79), "the low floor is more than one spacing away");
        assertArrayEquals(new int[] {56, 200}, w.windowAt(64, 64, 79, 79));
    }

    @Test
    void aRunReachingTheStartIsTheRoofAndAColumnWithoutFloorWidens() {
        ScanWindow w = floors(90, 90, 200);
        ColumnRuns roofAndFloor = ColumnRuns.of(solid(new int[] {150, 200}, new int[] {56, 90}), 56, 145);
        assertNull(w.widen(new ColumnRuns[] {roofAndFloor}, 56, 200), "a roof bulge over a floor");
        ColumnRuns pillar = ColumnRuns.of(solid(new int[] {56, 200}), 56, 145);
        assertArrayEquals(new int[] {0, 200}, w.widen(new ColumnRuns[] {pillar}, 56, 200), "a column solid to the start has no floor in it");
        assertArrayEquals(new int[] {0, 200}, w.widen(new ColumnRuns[] {ColumnRuns.EMPTY}, 56, 200));
        assertNull(w.widen(new ColumnRuns[] {ColumnRuns.EMPTY}, 0, 200), "already at the noise floor");
    }

    @Test
    void runsDropTheirTopRunsAndFindTheRunAtAHeight() {
        ColumnRuns r = ColumnRuns.of(solid(new int[] {150, 200}, new int[] {56, 90}), 56, 145);
        assertEquals(2, r.count());
        assertEquals(90, r.from(1).top(0));
        assertEquals(1, r.from(1).count());
        assertEquals(0, r.from(2).count());
        assertEquals(r, r.from(0));
        assertEquals(0, r.runAt(170));
        assertEquals(1, r.runAt(56));
        assertEquals(-1, r.runAt(120));
        assertEquals(-1, r.runAt(201));
    }

    /** Solid blocks over 56..200 for each {bottom, top} span. */
    static boolean[] solid(int[]... spans) {
        boolean[] s = new boolean[145];
        for (int[] span : spans) for (int y = span[0]; y <= span[1]; y++) s[y - 56] = true;
        return s;
    }
}
