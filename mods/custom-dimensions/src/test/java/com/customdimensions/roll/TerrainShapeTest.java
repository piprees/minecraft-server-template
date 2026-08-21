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
    void surfaceYAnswersTheOpenBlockAboveTheGround() {
        // The OCEAN_FLOOR_WG convention, which is what the facts read
        // (SpikeSampler.sample) and what ColumnScan.findPlayableFloorY
        // returns. One definition or the two drift, and the whole difference
        // lands on the shoreline: ground topping out exactly at sea level is
        // dry land to the facts and sea to anything comparing the ground
        // block itself.
        TerrainShape.Band band = new TerrainShape.Band(0, 384, 8);
        for (int surface : new int[]{1, 63, 64, 65, 100, 300}) {
            assertEquals(surface + 1, TerrainShape.surfaceY(groundUpTo(surface), band, 0, 0),
                    "the surface is the first open block above the ground");
        }
    }

    @Test
    void anAirGapThinnerThanARungIsStillTheUndersideOfTheRoof() {
        // The nether control at (-150, 125), to scale: the roof slab runs
        // 181..191, the gap under it is 176..180 — FIVE blocks against a rung
        // of eight — and the terrain below tops out at 175.
        //
        // A walk that samples on the rung lands on 183 and 175, finds both
        // solid, and carries on down as though the roof went with it. That is
        // exactly what happened: blocks said 176, the density walk said 24, and
        // the two agreed about every block in between. It can only ever read
        // LOW, which is what max(render - facts) == 0 was saying.
        TerrainShape.Band band = new TerrainShape.Band(0, 191, 8);
        TerrainShape.Density column = field(p -> {
            int y = p[1];
            boolean solid = y >= 181 || (y <= 175 && y >= 20);
            return solid ? 1.0 : -1.0;
        });

        assertEquals(176, TerrainShape.surfaceY(column, band, 0, 0, true),
                "the five-block gap at 176..180 is the roof underside, rung or no rung");
    }

    @Test
    void aSolidLayerThinnerThanARungIsStillTheGround() {
        // The nether control at (-400, 25), to scale: roof 186..191, a
        // fourteen-block gap under it, ONE solid block at y=171 with air above
        // and below, and nothing else until y=23.
        //
        // The block heightmap takes that speck because it is the highest
        // opaque thing in the column, and the live world agrees. A walk
        // sampling on the rung steps 182, 174, 166 and never sees it, so the
        // render answered 23 against the blocks' 172 — 149 blocks apart, with
        // both ladders agreeing about every y between them.
        TerrainShape.Band band = new TerrainShape.Band(0, 191, 8);
        TerrainShape.Density column = field(p -> {
            int y = p[1];
            boolean solid = y >= 186 || y == 171 || y <= 23;
            return solid ? 1.0 : -1.0;
        });

        assertEquals(172, TerrainShape.surfaceY(column, band, 0, 0, true),
                "one solid block at 171 is the ground, rung or no rung");
    }

    @Test
    void aColumnSolidToTheBandTopAnswersOneAboveIt() {
        // Not a real terrain case, but the boundary the walk has to survive:
        // nothing open anywhere, so the "first open block above" is the one
        // just past the band.
        TerrainShape.Band band = new TerrainShape.Band(0, 384, 8);
        assertEquals(385, TerrainShape.surfaceY(groundUpTo(1000), band, 0, 0));
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
        assertEquals(72, TerrainShape.surfaceY(slab, band, 0, 0));
    }

    // -------------------------------------------- the bottom of the band

    @Test
    void groundConfinedToTheBottomSliverIsStillFound() {
        // The coarse walk steps DOWN by the rung from the band's top, so its
        // last sample lands `rung - 1` blocks above the floor whenever the
        // span is not a whole number of rungs — and that is EVERY real band.
        // Vanilla requires the generation shape's height to be a whole number
        // of cells, and `bandOf` produces a span of `height - 1`. Ground that
        // exists only in that untested sliver read as void.
        TerrainShape.Band band = new TerrainShape.Band(0, 15, 8);   // minY 0, height 16, cell 8
        TerrainShape.Density onlyAtTheFloor = field(p -> p[1] <= 1 ? 1.0 : -1.0);
        assertEquals(2, TerrainShape.surfaceY(onlyAtTheFloor, band, 0, 0),
                "ground at y=0..1 is inside the band and must not read as void");
    }

    @Test
    void aSurfaceAtEveryHeightInARealisticBandIsFound() {
        // Ground from the band's floor up to `surface`, swept through every
        // height — the shape a real column has, and the one case where the
        // walk's rung alignment against the band's own span actually matters.
        // (A single ISOLATED block is not swept here: a solid layer thinner
        // than a cell cannot exist in a generator, because density is linear
        // between two cell corners. That is Band's documented contract, not a
        // gap — asserting otherwise would be asserting something false about
        // the domain.)
        TerrainShape.Band band = new TerrainShape.Band(-64, 319, 4);   // height 384, cell 4
        for (int surface = band.bottomY(); surface < band.topY(); surface++) {
            final int s = surface;
            TerrainShape.Density groundTo = field(p -> p[1] <= s ? 1.0 : -1.0);
            assertEquals(s + 1, TerrainShape.surfaceY(groundTo, band, 0, 0),
                    "ground topping out at y=" + s + " must be found");
        }
    }

    @Test
    void aCeilingedFloorAtTheVeryBottomIsStillAFloor() {
        // The same gap, reached through playableFloor rather than the first
        // walk: a ceilinged column whose only ground is in the bottom sliver.
        TerrainShape.Band band = new TerrainShape.Band(0, 191, 8);
        TerrainShape.Density d = field(p -> p[1] >= 150 || p[1] <= 1 ? 1.0 : -1.0);
        assertEquals(2, TerrainShape.surfaceY(d, band, 0, 0, true));
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

    // ------------------------------------------------------- ceilinged worlds

    @Test
    void aCeilingedWorldIsWalkedUnderItsRoof() {
        // Ground to y=40, open 41..119, roof from 120 up. Asked as an open
        // dimension this answers the top of the roof, which is what a nether
        // map was drawn from; asked as a ceilinged one it answers the floor a
        // player stands on.
        TerrainShape.Band band = new TerrainShape.Band(0, 128, 8);
        TerrainShape.Density nether = field(p -> p[1] <= 40 || p[1] >= 120 ? 1.0 : -1.0);
        assertEquals(129, TerrainShape.surfaceY(nether, band, 0, 0, false));
        assertEquals(41, TerrainShape.surfaceY(nether, band, 0, 0, true));
    }

    @Test
    void aClampedRoofGradientSolidToTheBandTopIsStillWalkedThrough() {
        // What the real nether does. Vanilla's nether router forces solid at
        // the roof with a y_clamped_gradient, and a clamped gradient keeps its
        // top value for every y above the clamp — so the density says "solid"
        // from the roof upward without limit. Measured on the live nether:
        // the old walk answered the band top (y=191) for all 1313 columns of
        // a world whose real floors run 27–182.
        TerrainShape.Band band = new TerrainShape.Band(0, 191, 8);
        TerrainShape.Density clamped = field(p -> p[1] <= 40 || p[1] >= 120 ? 1.0 : -1.0);
        assertEquals(192, TerrainShape.surfaceY(clamped, band, 0, 0, false),
                "asked as an open dimension it still answers off the top of the band — "
                + "a constant, and the same constant for every column in the world");
        assertEquals(41, TerrainShape.surfaceY(clamped, band, 0, 0, true));
    }

    @Test
    void aCeilingedColumnSolidToTheFloorHasNoSurface() {
        TerrainShape.Band band = new TerrainShape.Band(0, 191, 8);
        assertNull(TerrainShape.surfaceY(field(p -> 1.0), band, 0, 0, true),
                "entombed: no open interior under the roof");
    }

    @Test
    void aCeilingedColumnWithNoFloorUnderTheRoofHasNoSurface() {
        // A roof over an open shaft that never reaches ground.
        TerrainShape.Band band = new TerrainShape.Band(0, 191, 8);
        TerrainShape.Density hollow = field(p -> p[1] >= 120 ? 1.0 : -1.0);
        assertNull(TerrainShape.surfaceY(hollow, band, 0, 0, true));
    }

    @Test
    void aCeilingedColumnTakesTheHighestGroundWhetherOrNotThereIsRoomOnIt() {
        // A one-cell gap at y=100 between solid at 99 and 101, and the real
        // floor at 60. This used to answer 61: a single open cell was read as
        // a suffocation rather than a floor, and the walk carried on past it.
        //
        // That rule is gone. It was cheap over block states and treacherous
        // over a density field — where the density says solid and the
        // generated blocks are air, headroom failed on ground that is
        // genuinely standable, and the walk fell through to whatever was
        // beneath. The clip under the roof does the job it was there for.
        // 101 is the highest solid below the clip, so 102 is the answer.
        TerrainShape.Band band = new TerrainShape.Band(0, 191, 4);
        TerrainShape.Density d = field(p -> {
            int y = p[1];
            if (y >= 120) {
                return 1.0;           // roof
            }
            if (y == 100) {
                return -1.0;          // a single open cell
            }
            if (y >= 99 && y <= 101) {
                return 1.0;           // solid either side of it
            }
            return y <= 60 ? 1.0 : -1.0;   // the floor below
        });
        assertEquals(102, TerrainShape.surfaceY(d, band, 0, 0, true));
    }

    @Test
    void aCeilingedRoofThickerThanOneRungIsCrossedInOnePass() {
        // A forty-block roof slab: starting the underside search a few blocks
        // under its top would open a pocket inside the rock.
        TerrainShape.Band band = new TerrainShape.Band(0, 191, 8);
        TerrainShape.Density d = field(p -> p[1] >= 150 || p[1] <= 70 ? 1.0 : -1.0);
        assertEquals(71, TerrainShape.surfaceY(d, band, 0, 0, true));
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
