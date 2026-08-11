package com.customdimensions.roll;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The maths and palette decisions behind a render, pinned with synthetic
 * input and no Minecraft Bootstrap: the sizing formula (given a measured
 * per-column cost, not a real one), colour selection and blending, height
 * shading, and the grid/world coordinate transform. {@code render} itself —
 * the Fabric-dependent half — is exercised live (mods/AGENTS.md's
 * verification loop), same as {@code SeedBank}'s writes.
 */
class CandidateRenderTest {

    // ------------------------------------------------------------- chooseSide

    @Test
    void chooseSideFitsTheDiscToTheBudgetAtTheMeasuredCost() {
        long perColumn = Duration.ofMillis(1).toNanos();
        long budget = Duration.ofSeconds(5).toNanos();
        int side = CandidateRender.chooseSide(perColumn, budget, CandidateRender.MIN_SIDE,
                CandidateRender.MAX_SIDE);

        // affordableColumns = 5000; side*side*(pi/4) <= 5000, so side = 79 — no
        // floor or ceiling involved at this cost.
        assertEquals(79, side);
    }

    @Test
    void chooseSideFloorsAPathologicallyExpensiveColumnRatherThanGoingBelowMinSide() {
        long slow = Duration.ofMillis(100).toNanos();
        long budget = Duration.ofSeconds(5).toNanos();
        int side = CandidateRender.chooseSide(slow, budget, CandidateRender.MIN_SIDE,
                CandidateRender.MAX_SIDE);

        // affordableColumns = 50; side*side*(pi/4) <= 50 gives side = 7,
        // floored up to MIN_SIDE.
        assertEquals(CandidateRender.MIN_SIDE, side);
    }

    @Test
    void chooseSideGrowsWithACheaperColumn() {
        long cheap = Duration.ofNanos(1_000).toNanos();
        long budget = Duration.ofSeconds(5).toNanos();
        int side = CandidateRender.chooseSide(cheap, budget, CandidateRender.MIN_SIDE,
                CandidateRender.MAX_SIDE);

        assertEquals(CandidateRender.MAX_SIDE, side, "5,000,000 affordable columns must hit the ceiling");
    }

    @Test
    void chooseSideIsAlwaysOdd() {
        for (long perColumn : new long[] {10, 1_000, 100_000, 10_000_000}) {
            int side = CandidateRender.chooseSide(perColumn, Duration.ofSeconds(5).toNanos(),
                    CandidateRender.MIN_SIDE, CandidateRender.MAX_SIDE);
            assertEquals(1, side % 2, "side " + side + " for perColumn=" + perColumn + " must be odd");
        }
    }

    @Test
    void chooseSideNeverExceedsBoundsRegardlessOfCost() {
        int side = CandidateRender.chooseSide(1, Duration.ofSeconds(60).toNanos(),
                CandidateRender.MIN_SIDE, CandidateRender.MAX_SIDE);
        assertTrue(side <= CandidateRender.MAX_SIDE);
        side = CandidateRender.chooseSide(Long.MAX_VALUE / 2, Duration.ofSeconds(5).toNanos(),
                CandidateRender.MIN_SIDE, CandidateRender.MAX_SIDE);
        assertTrue(side >= CandidateRender.MIN_SIDE);
    }

    // ------------------------------------------------------------------ colour

    @Test
    void terrainBaseColorPrefersGrassOverFoliageOverFallback() {
        assertEquals(0x112233, CandidateRender.terrainBaseColor(0x112233, 0x445566, 0x000000, 0xFFFFFF));
        assertEquals(0x445566, CandidateRender.terrainBaseColor(null, 0x445566, 0x000000, 0xFFFFFF));
    }

    @Test
    void terrainBaseColorFallsBackToAFogSkyBlendWithNeitherOverride() {
        // fog=black, sky=white, blend at t=0.5 must land exactly halfway per channel.
        assertEquals(0x808080, CandidateRender.terrainBaseColor(null, null, 0x000000, 0xFFFFFF));
    }

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
    void heightFactorIsFlatWhenTheGridHasNoRelief() {
        assertEquals(1.0, CandidateRender.heightFactor(64, 64, 64), 1e-9);
    }

    @Test
    void heightFactorSpansTheConfiguredRangeAtTheExtremes() {
        assertEquals(0.35, CandidateRender.heightFactor(0, 0, 100), 1e-9);
        assertEquals(1.6, CandidateRender.heightFactor(100, 0, 100), 1e-9);
        assertEquals((0.35 + 1.6) / 2.0, CandidateRender.heightFactor(50, 0, 100), 1e-9);
    }

    @Test
    void waterColorAtDarkensWithDepthAndFloorsAtSixtyPercent() {
        int surface = CandidateRender.waterColorAt(0xFFFFFF, 60, 60, 0);   // at sea level: no depth
        int deepest = CandidateRender.waterColorAt(0xFFFFFF, 0, 60, 0);    // at the render's own minimum: full depth
        assertEquals(0xFFFFFF, surface);
        assertEquals(0x999999, deepest, "full depth must floor at 60% brightness, never go darker");
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

    @Test
    void theBorderRingIsOneCellWide() {
        int radius = 1000;
        int step = 20;
        assertTrue(CandidateRender.nearBorder(radius, radius, step));
        assertTrue(CandidateRender.nearBorder(radius - step / 2.0, radius, step));
        assertTrue(CandidateRender.nearBorder(radius + step / 2.0, radius, step));
        // A full step either side is a two-cell band, which at the
        // measurement grid's size was a seventh of the whole picture.
        assertFalse(CandidateRender.nearBorder(radius - step, radius, step));
        assertFalse(CandidateRender.nearBorder(radius + step, radius, step));
    }

    // ---------------------------------------------------------- biome boundary

    @Test
    void aCellSurroundedByItsOwnBiomeHasNoBoundary() {
        int side = 3;
        int[] biomeId = {0, 0, 0, 0, 0, 0, 0, 0, 0};
        boolean[] known = {true, true, true, true, true, true, true, true, true};
        assertFalse(CandidateRender.bordersADifferentBiome(biomeId, known, side, 1, 1));
    }

    @Test
    void aSingleForeignNeighbourIsEnoughToMarkABoundary() {
        int side = 3;
        //             col0 col1 col2
        int[] biomeId = {0, 0, 0,
                          0, 1, 0,   // centre is the only biome-1 cell
                          0, 0, 0};
        boolean[] known = {true, true, true, true, true, true, true, true, true};

        assertTrue(CandidateRender.bordersADifferentBiome(biomeId, known, side, 1, 1),
                "the centre sits inside a ring of a different biome");
        assertTrue(CandidateRender.bordersADifferentBiome(biomeId, known, side, 1, 0),
                "the cell directly above the centre borders it");
        assertFalse(CandidateRender.bordersADifferentBiome(biomeId, known, side, 0, 0),
                "the corner touches only same-biome neighbours");
    }

    @Test
    void anUnmeasuredNeighbourNeverCountsAsAForeignBiome() {
        int side = 3;
        //             col0 col1 col2
        int[] biomeId = {0, 1, 0,   // the cell above the centre differs...
                          0, 0, 0,
                          0, 0, 0};
        boolean[] known = {true, false, true,   // ...but was never measured
                            true, true, true,
                            true, true, true};
        assertFalse(CandidateRender.bordersADifferentBiome(biomeId, known, side, 1, 1),
                "an unmeasured cell carries no biome fact to disagree with");
    }
}
