package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RunScannerTest {
    static final int LO = -64;
    static final int HI = 319;

    interface F {
        float at(int x, int y, int z);
    }

    /** {@code f} sampled point by point, counting the points. */
    static final class Counted implements RunScanner.Density {
        final F f;
        long points;

        Counted(F f) {
            this.f = f;
        }

        @Override
        public void volume(int x0, int y0, int z0, int nx, int ny, int nz, int sxz, int sy, float[] out) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    for (int y = 0; y < ny; y++) out[(z * nx + x) * ny + y] = f.at(x0 + x * sxz, y0 + y * sy, z0 + z * sxz);
                }
            }
            points += (long) nx * ny * nz;
        }
    }

    static RunScanner scanner(F f) {
        return new RunScanner(new Counted(f), new Counted(f), LO, HI, 4, 8);
    }

    /** Solid where {@code y} lies in one of the inclusive {@code [bottom, top]} slabs. */
    static F slabs(int... bounds) {
        return (x, y, z) -> {
            float best = -1000;
            for (int i = 0; i < bounds.length; i += 2) {
                float centre = (bounds[i] + bounds[i + 1]) / 2f;
                float half = (bounds[i + 1] - bounds[i] + 1) / 2f;
                best = Math.max(best, half - Math.abs(y - centre));
            }
            return best;
        };
    }

    static int[] runs(ColumnRuns r) {
        int[] out = new int[r.count() * 2];
        for (int i = 0; i < r.count(); i++) {
            out[2 * i] = r.bottom(i);
            out[2 * i + 1] = r.top(i);
        }
        return out;
    }

    @Test
    void aSingleSurfaceIsOneRunFromTheFloorOfTheNoise() {
        ColumnRuns[] cols = scanner((x, y, z) -> 64.5f - y).scan(0, 0, 4, 4, 4);
        for (ColumnRuns r : cols) {
            assertArrayEquals(new int[] {LO, 64}, runs(r));
            assertFalse(r.roofed(HI));
            assertEquals(65, r.surface(HI, LO));
        }
    }

    @Test
    void aRoofedColumnHasItsSurfaceOnTheFloorUnderTheRoof() {
        ColumnRuns r = scanner((x, y, z) -> Math.max(40.5f - y, y - 99.5f)).scan(0, 0, 4, 1, 1)[0];
        assertArrayEquals(new int[] {100, HI, LO, 40}, runs(r));
        assertTrue(r.roofed(HI));
        assertEquals(41, r.surface(HI, LO));
        assertEquals(1, r.surfaceRun(HI, LO));
    }

    @Test
    void threeSkyTiersAreThreeRunsTopmostFirst() {
        RunScanner s = scanner(slabs(150, 170, 100, 120, 40, 60));
        ColumnRuns r = s.scan(0, 0, 4, 1, 1)[0];
        assertArrayEquals(new int[] {150, 170, 100, 120, 40, 60}, runs(r));
        assertEquals(171, r.surface(HI, LO));
        assertEquals(1, r.runAtOrBelow(130));
        assertTrue(r.solidAt(110));
        assertFalse(r.solidAt(130));
        assertEquals(6, s.refinedCells(), "one refined cell per crossing");
    }

    @Test
    void aVoidColumnHasNoRunsAndItsSurfaceAtTheFloor() {
        RunScanner s = scanner((x, y, z) -> -1);
        ColumnRuns r = s.scan(0, 0, 8, 2, 2)[0];
        assertEquals(0, r.count());
        assertEquals(LO, r.surface(HI, LO));
        assertEquals(0, s.refinedCells());
    }

    @Test
    void aRoofSolidToTheFloorPutsTheSurfaceAboveTheRoof() {
        ColumnRuns r = scanner((x, y, z) -> 1).scan(0, 0, 4, 1, 1)[0];
        assertArrayEquals(new int[] {LO, HI}, runs(r));
        assertEquals(-2, r.surfaceRun(HI, LO));
        assertEquals(HI + 1, r.surface(HI, LO));
    }

    @Test
    void eachColumnIsSampledAtItsOwnStridePosition() {
        F slope = (x, y, z) -> 64.5f + x / 8f + z / 16f - y;
        for (int step : new int[] {1, 2, 3, 4, 8, 16}) {
            ColumnRuns[] cols = scanner(slope).scan(-32, 16, step, 5, 3);
            for (int j = 0; j < 3; j++) {
                for (int i = 0; i < 5; i++) {
                    int x = -32 + i * step;
                    int z = 16 + j * step;
                    int expected = (int) Math.floor(64.5f + x / 8f + z / 16f - 1e-4f);
                    assertEquals(expected, cols[i + 5 * j].top(0), "step " + step + " column " + i + "," + j);
                }
            }
        }
    }

    @Test
    void columnsOnTheCellLatticeAreScoutedDirectlyAndOthersFromTheLattice() {
        RunScanner s = scanner((x, y, z) -> 64.5f - y);
        assertTrue(s.onLattice(0, 0, 4));
        assertTrue(s.onLattice(-64, 32, 8));
        assertFalse(s.onLattice(0, 0, 2));
        assertFalse(s.onLattice(2, 0, 4));
        Counted scout = new Counted((x, y, z) -> 64.5f - y);
        RunScanner counted = new RunScanner(scout, new Counted((x, y, z) -> 64.5f - y), LO, HI, 4, 8);
        counted.scan(0, 0, 8, 4, 4);
        assertEquals(16L * 49, scout.points, "4x4 columns, 49 rows from -64 to 320");
        scout.points = 0;
        counted.scan(0, 0, 2, 4, 4);
        assertEquals(3L * 3 * 49, scout.points, "the 3x3 lattice round 4x4 columns 2 blocks apart");
        scout.points = 0;
        counted.scan(2, 0, 12, 4, 4);
        assertEquals(16L * 49, scout.points, "off the lattice at a stride of three cells: the columns themselves");
        assertFalse(counted.scoutsColumns(2, 0, 3));
        assertTrue(counted.scoutsColumns(2, 0, 4));
    }

    @Test
    void aDensityWithoutInterpolationIsScoutedEveryEightBlocks() {
        Counted scout = new Counted((x, y, z) -> 64.5f - y);
        RunScanner s = new RunScanner(scout, scout, LO, HI, 0, 0);
        assertTrue(s.onLattice(3, 5, 7));
        ColumnRuns r = s.scan(3, 5, 7, 1, 1)[0];
        assertArrayEquals(new int[] {LO, 64}, runs(r));
    }

    @Test
    void aLayerThinnerThanACellBetweenTwoRowsIsMissed() {
        ColumnRuns r = scanner(slabs(130, 133, LO, 40)).scan(0, 0, 4, 1, 1)[0];
        assertArrayEquals(new int[] {LO, 40}, runs(r));
    }

    @Test
    void aColumnWithMoreRunsThanTheCapKeepsItsTopmost() {
        int[] bounds = new int[60];
        for (int i = 0; i < 30; i++) {
            bounds[2 * i] = -60 + i * 16;
            bounds[2 * i + 1] = -60 + i * 16 + 7;
        }
        F f = slabs(bounds);
        ColumnRuns r = new RunScanner(new Counted(f), new Counted(f), LO, 511, 4, 8).scan(0, 0, 4, 1, 1)[0];
        assertEquals(ColumnRuns.MAX_RUNS, r.count());
        assertTrue(r.truncated());
        assertEquals(-60 + 29 * 16 + 7, r.top(0));
        assertEquals(-60 + 6 * 16, r.bottom(ColumnRuns.MAX_RUNS - 1));
    }

    @Test
    void theTopOfTheNoiseIsSolidWhenTheTopRowIsAboveIt() {
        RunScanner s = new RunScanner(new Counted((x, y, z) -> 1), new Counted((x, y, z) -> 1), 0, 125, 4, 8);
        ColumnRuns r = s.scan(0, 0, 4, 1, 1)[0];
        assertArrayEquals(new int[] {0, 125}, runs(r));
    }
}
