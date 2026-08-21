package com.customdimensions.roll;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The maths behind a render, pinned with synthetic input and no Minecraft
 * Bootstrap: colour blending, the height a climate point implies, the local
 * relief shading, and the grid/world coordinate transform. Finding the ground
 * where depth cannot describe it is {@link TerrainShapeTest}. {@code render}
 * itself — the Fabric-dependent half — is exercised live (mods/AGENTS.md's
 * verification loop).
 */
class CandidateRenderTest {

    @Test
    void blendInterpolatesEachChannelIndependently() {
        assertEquals(0x000000, CandidateRender.blend(0x000000, 0xFFFFFF, 0.0));
        assertEquals(0xFFFFFF, CandidateRender.blend(0x000000, 0xFFFFFF, 1.0));
        assertEquals(0x808080, CandidateRender.blend(0x000000, 0xFFFFFF, 0.5));
    }

    @Test
    void shadeScalesAndClampsEveryChannel() {
        assertEquals(0x804020, CandidateRender.shade(0x804020, 1.0));
        assertEquals(0xFFFFFF, CandidateRender.shade(0xFFFFFF, 2.0), "over-bright must clamp to 255, never wrap");
        assertEquals(0x000000, CandidateRender.shade(0x804020, 0.0));
    }

    @Test
    void waterColorAtDarkensWithDepthAndFloorsAtSixtyPercent() {
        int surface = CandidateRender.waterColorAt(0xFFFFFF, 60, 60, 0);   // at sea level: no depth
        int deepest = CandidateRender.waterColorAt(0xFFFFFF, 0, 60, 0);    // at the render's own minimum: full depth
        assertEquals(0xFFFFFF, surface);
        assertEquals(0x999999, deepest, "full depth must floor at 60% brightness, never go darker");
    }

    // ------------------------------------------------------------- height

    @Test
    void heightFromDepthFollowsTheSampleNoiseRelation() {
        // surface_Y = 128 * depth, the relation customdim sample-noise
        // documents as generation ground truth for these graphs.
        assertEquals(64, CandidateRender.heightFromDepth(new double[]{0, 0, 0, 0, 0.5, 0}, -64, 320));
        assertEquals(0, CandidateRender.heightFromDepth(new double[]{0, 0, 0, 0, 0.0, 0}, -64, 320));
    }

    @Test
    void heightFromDepthClampsToTheWorldRatherThanRunningPastIt() {
        assertEquals(320, CandidateRender.heightFromDepth(new double[]{0, 0, 0, 0, 99.0, 0}, -64, 320));
        assertEquals(-64, CandidateRender.heightFromDepth(new double[]{0, 0, 0, 0, -99.0, 0}, -64, 320));
    }

    @Test
    void heightFromDepthIsAbsentRatherThanZeroWithNoClimate() {
        assertNull(CandidateRender.heightFromDepth(null, -64, 320));
        assertNull(CandidateRender.heightFromDepth(new double[]{0, 0}, -64, 320));
    }

    // ---------------------------------------------------------------- relief

    @Test
    void reliefIsLevelWhereTheGroundIsFlat() {
        int side = 3;
        int[] height = new int[9];
        boolean[] known = new boolean[9];
        java.util.Arrays.fill(height, 70);
        java.util.Arrays.fill(known, true);
        assertEquals(1.0, CandidateRender.relief(height, known, side, 1, 1), 0.15);
    }

    @Test
    void aCellHigherThanItsNorthNeighbourIsBrighterThanOneLower() {
        int side = 3;
        boolean[] known = new boolean[9];
        java.util.Arrays.fill(known, true);
        int[] rising = {60, 60, 60, 70, 70, 70, 80, 80, 80};    // ground climbs southward
        int[] falling = {80, 80, 80, 70, 70, 70, 60, 60, 60};
        assertTrue(CandidateRender.relief(rising, known, side, 1, 1)
                        > CandidateRender.relief(falling, known, side, 1, 1),
                "vanilla shades a cell against the one to its NORTH");
    }

    // ------------------------------------------------------ height lattice

    /** A 3x3 coarse field of known ground, ten blocks apart in each direction. */
    private static int[] slope(int coarseSide) {
        int[] h = new int[coarseSide * coarseSide];
        for (int cz = 0; cz < coarseSide; cz++) {
            for (int cx = 0; cx < coarseSide; cx++) {
                h[cz * coarseSide + cx] = 60 + 10 * cx + 100 * cz;
            }
        }
        return h;
    }

    @Test
    void aStrideOfOneReadsTheCornerItSitsOnAndNothingElse() {
        // The whole coarse-lattice mechanism has to vanish at stride 1, or
        // "the same picture" could never be asserted against the old renderer.
        int coarseSide = 5;
        int[] h = slope(coarseSide);
        boolean[] known = new boolean[coarseSide * coarseSide];
        java.util.Arrays.fill(known, true);
        for (int gz = 0; gz < 4; gz++) {
            for (int gx = 0; gx < 4; gx++) {
                assertEquals(h[gz * coarseSide + gx],
                        CandidateRender.heightAt(h, known, coarseSide, gx, gz, 1));
            }
        }
    }

    @Test
    void aPointBetweenFourMeasuredCornersIsTheirBilinearBlend() {
        int coarseSide = 3;
        int[] h = slope(coarseSide);
        boolean[] known = new boolean[coarseSide * coarseSide];
        java.util.Arrays.fill(known, true);
        // gx=1, gz=1 at stride 2 is the exact centre of the first cell:
        // corners 60, 70, 160, 170 average to 115.
        assertEquals(115, CandidateRender.heightAt(h, known, coarseSide, 1, 1, 2));
        // On the cell edge it is the midpoint of that edge's two corners.
        assertEquals(65, CandidateRender.heightAt(h, known, coarseSide, 1, 0, 2));
    }

    @Test
    void aCellStraddlingAnEdgeTakesTheNearestMeasuredColumnRatherThanABlend() {
        int coarseSide = 3;
        int[] h = slope(coarseSide);
        boolean[] known = new boolean[coarseSide * coarseSide];
        java.util.Arrays.fill(known, true);
        known[1] = false;                       // the (1, 0) corner has no ground
        // Nearest to (gx=1, gz=0) at stride 2 is that missing corner itself, so
        // the answer is "no ground" rather than a blend of the three that have it.
        assertNull(CandidateRender.heightAt(h, known, coarseSide, 1, 0, 2));
        // Nearest to (gx=0, gz=0) is the corner that IS measured.
        assertEquals(60, CandidateRender.heightAt(h, known, coarseSide, 0, 0, 2));
    }

    @Test
    void aCellWithNoMeasuredCornerIsOpenAir() {
        int coarseSide = 3;
        int[] h = slope(coarseSide);
        boolean[] known = new boolean[coarseSide * coarseSide];
        assertNull(CandidateRender.heightAt(h, known, coarseSide, 1, 1, 2));
    }

    // ---------------------------------------------------------------- geometry

    @Test
    void gridAndWorldOffsetRoundTripThroughEachOther() {
        int step = 32;
        int half = 64;
        for (int gx = 0; gx < 129; gx++) {
            int offset = CandidateRender.gridToWorldOffset(gx, step, half);
            assertEquals(gx, CandidateRender.worldToGrid(offset, step, half));
        }
    }
}
