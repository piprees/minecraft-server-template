package com.customdimensions.roll;

import org.junit.jupiter.api.Test;

import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ground-finding half of a render, pinned against hand-built density
 * fields with no Minecraft Bootstrap.
 *
 * <p>The regression these exist for: an island world drawn as solid ground
 * with holes in it, and a nether drawn as an unbroken lava sea. Both came of
 * inferring a height from the climate point's depth in generators whose
 * depth is a CONSTANT, so every column in the world shared one answer.
 */
class TerrainShapeTest {

    /** A density field defined by a lambda over (x, y, z) — solid above zero. */
    private static TerrainShape.Density field(ToDoubleFunction<int[]> f) {
        return (x, y, z) -> f.applyAsDouble(new int[]{x, y, z});
    }

    /** Ground everywhere up to {@code surface}, air above it. */
    private static TerrainShape.Density groundUpTo(int surface) {
        return field(p -> p[1] <= surface ? 1.0 : -1.0);
    }

    // ------------------------------------------------------------- surfaceY

    @Test
    void surfaceYFindsTheExactTopBlockOfSolidGround() {
        TerrainShape.Band band = new TerrainShape.Band(0, 384, 8);
        for (int surface : new int[]{1, 63, 64, 65, 100, 383}) {
            assertEquals(surface, TerrainShape.surfaceY(groundUpTo(surface), band, 0, 0),
                    "the surface is the last solid block, not the coarse rung that found it");
        }
    }

    @Test
    void anEmptyColumnIsAbsentRatherThanTheWorldFloor() {
        TerrainShape.Band band = new TerrainShape.Band(0, 128, 8);
        assertNull(TerrainShape.surfaceY(field(p -> -1.0), band, 0, 0),
                "a void column has no ground — the renderer paints it as void, not as the floor");
    }

    @Test
    void islandsInAVoidReadAsMostlyVoid() {
        // A slab of ground between y=48 and y=64, and only where x is inside a
        // narrow band: the shape of an island world, where most columns are
        // nothing at all.
        TerrainShape.Band band = new TerrainShape.Band(0, 128, 8);
        TerrainShape.Density islands = field(p ->
                Math.floorMod(p[0], 400) < 40 && p[1] >= 48 && p[1] <= 64 ? 1.0 : -1.0);
        int solid = 0;
        for (int x = 0; x < 4000; x += 4) {
            if (TerrainShape.surfaceY(islands, band, x, 0) != null) {
                solid++;
            }
        }
        double fraction = solid / (double) (4000 / 4);
        assertTrue(fraction > 0.05 && fraction < 0.15,
                "about a tenth of the columns carry an island; got " + fraction);
    }

    @Test
    void anIslandOneGeneratorCellThickIsStillFound() {
        // The thinnest slab a generator can express: density is linear
        // between two cell corners, so nothing solid fits between two rungs
        // one cell apart. This is the boundary case the rung spacing exists
        // to make safe — a coarser walk steps straight over it.
        TerrainShape.Band band = new TerrainShape.Band(-64, 320, 8);
        TerrainShape.Density slab = field(p -> p[1] >= 64 && p[1] <= 71 ? 1.0 : -1.0);
        assertEquals(71, TerrainShape.surfaceY(slab, band, 0, 0));
    }

    @Test
    void aCompressedGeneratorGetsAFinerWalkThanATallOne() {
        // The rung is the generator's own cell height, never a fixed
        // fraction of the world: a four-block cell must walk on four
        // whatever the band's height.
        assertEquals(4, new TerrainShape.Band(0, 128, 4).rung());
        assertEquals(4, new TerrainShape.Band(-64, 2032, 4).rung());
        assertEquals(16, new TerrainShape.Band(-64, 320, 16).rung());
    }

    @Test
    void aCeilingedWorldReportsTheRoofBecauseTheRoofIsTheHighestSolidBlock() {
        // Not a defect: the roof genuinely is the top of the column. The
        // renderer draws a nether from its floor via the band, which is why
        // the band comes from the generator's shape config rather than the
        // dimension type's full height.
        TerrainShape.Band band = new TerrainShape.Band(0, 128, 8);
        TerrainShape.Density nether = field(p -> p[1] <= 40 || p[1] >= 120 ? 1.0 : -1.0);
        assertEquals(128, TerrainShape.surfaceY(nether, band, 0, 0));
    }

    // ----------------------------------------------------------- calibration

    @Test
    void depthIsAHeightWhenTheClaimedSurfaceHasGroundUnderItAndAirOver() {
        TerrainShape.Band band = new TerrainShape.Band(-64, 320, 8);
        int n = 40;
        int[] xs = new int[n];
        int[] zs = new int[n];
        Integer[] claimed = new Integer[n];
        for (int i = 0; i < n; i++) {
            xs[i] = i * 37;
            zs[i] = i * 53;
            claimed[i] = 64 + i;
        }
        // Ground follows the claim exactly: a per-column surface at the
        // claimed height.
        TerrainShape.Density sloping = field(p -> p[1] <= 64 + (p[0] / 37) ? 1.0 : -1.0);
        TerrainShape.Calibration c = TerrainShape.calibrate(sloping, band, xs, zs, claimed);
        assertTrue(c.depthIsHeight());
        assertEquals(n, c.tested());
        assertEquals(1.0, c.agreement(), 1e-9);
    }

    @Test
    void aConstantDepthFailsCalibrationBecauseOneHeightCannotDescribeAWorld() {
        // What the End and the Nether actually do: depth is a constant, so
        // every column claims the same surface while the ground is elsewhere.
        TerrainShape.Band band = new TerrainShape.Band(0, 128, 8);
        int n = 40;
        int[] xs = new int[n];
        int[] zs = new int[n];
        Integer[] claimed = new Integer[n];
        for (int i = 0; i < n; i++) {
            xs[i] = i * 37;
            zs[i] = i * 53;
            claimed[i] = 0;   // 128 * 0.0
        }
        TerrainShape.Density islands = field(p -> p[1] >= 48 && p[1] <= 64 ? 1.0 : -1.0);
        TerrainShape.Calibration c = TerrainShape.calibrate(islands, band, xs, zs, claimed);
        assertFalse(c.depthIsHeight(),
                "a constant depth must send the render to the density probe");
    }

    @Test
    void calibrationSkipsColumnsTooCloseToTheBandEdgeToProbe() {
        TerrainShape.Band band = new TerrainShape.Band(0, 128, 8);
        int[] xs = {0, 16, 32};
        int[] zs = {0, 16, 32};
        Integer[] claimed = {0, 128, 64};   // two sit within the probe margin
        TerrainShape.Calibration c = TerrainShape.calibrate(groundUpTo(64), band, xs, zs, claimed);
        assertEquals(1, c.tested(), "only the interior column can be probed");
        assertTrue(c.depthIsHeight());
    }

    @Test
    void noProbableColumnIsNotAVerdict() {
        TerrainShape.Band band = new TerrainShape.Band(0, 128, 8);
        TerrainShape.Calibration c = TerrainShape.calibrate(
                groundUpTo(64), band, new int[]{0}, new int[]{0}, new Integer[]{null});
        assertEquals(0, c.tested());
        assertFalse(c.depthIsHeight(),
                "nothing measured means the exact path, never the approximation on trust");
    }

    // ------------------------------------------------------------------ band

    @Test
    void theRungNeverCollapsesToZeroOnATinyBand() {
        assertTrue(new TerrainShape.Band(0, 1, 8).rung() >= 1);
        assertTrue(new TerrainShape.Band(0, 0, 0).rung() >= 1);
        assertNotNull(TerrainShape.surfaceY(groundUpTo(0), new TerrainShape.Band(0, 0, 8), 0, 0));
    }
}
