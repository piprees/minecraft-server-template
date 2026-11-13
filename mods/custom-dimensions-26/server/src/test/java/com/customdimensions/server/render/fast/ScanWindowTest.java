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
}
