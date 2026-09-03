package com.customdimensions.portal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The aperture particle field: how much of an opening is allowed to be dust,
 * and where.
 *
 * <p>The portal is a frame, an empty interior and the effects, so an even
 * fill of the plane is not a look — it is the failure. Everything here is
 * about the plane staying see-through.
 */
class PortalApertureTest {

    /** A vertical plane on the X axis: width along X, height along Y. */
    private static Set<BlockPos> plane(int width, int height) {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                cells.add(new BlockPos(x, 64 + y, 0));
            }
        }
        return cells;
    }

    @Test
    void aVanillaSizedOpeningIsAllRim() {
        Map<BlockPos, Integer> depths = PortalAperture.rimDepths(plane(2, 3), Direction.Axis.X);
        assertEquals(6, depths.size());
        depths.values().forEach(d -> assertEquals(0, d, "a 2x3 opening has no middle"));
    }

    @Test
    void depthGrowsInwardsFromTheFrame() {
        Map<BlockPos, Integer> depths = PortalAperture.rimDepths(plane(5, 5), Direction.Axis.X);
        assertEquals(2, depths.get(new BlockPos(2, 66, 0)), "centre of a 5x5");
        assertEquals(1, depths.get(new BlockPos(1, 65, 0)));
        assertEquals(0, depths.get(new BlockPos(0, 64, 0)));
        assertEquals(0, depths.get(new BlockPos(4, 68, 0)));
    }

    @Test
    void edgeWeightFallsOffWithDepth() {
        assertEquals(1.0, PortalAperture.edgeWeight(0, 0.45), 1e-9);
        assertEquals(0.45, PortalAperture.edgeWeight(1, 0.45), 1e-9);
        assertEquals(0.2025, PortalAperture.edgeWeight(2, 0.45), 1e-9);
        assertTrue(PortalAperture.edgeWeight(3, 0.45) < PortalAperture.edgeWeight(2, 0.45));
    }

    @Test
    void anEvenBiasWeightsEveryCellTheSame() {
        for (int depth = 0; depth < 5; depth++) {
            assertEquals(1.0, PortalAperture.edgeWeight(depth, 1.0), 1e-9);
        }
    }

    @Test
    void normalAxisIsThePlanesCrossing() {
        assertEquals(Direction.Axis.Z, PortalAperture.normalAxis(Direction.Axis.X));
        assertEquals(Direction.Axis.X, PortalAperture.normalAxis(Direction.Axis.Z));
        assertEquals(Direction.Axis.Y, PortalAperture.normalAxis(Direction.Axis.Y));
    }

    @Test
    void aPassIsReproducible() {
        Set<BlockPos> interior = plane(5, 5);
        List<BlockPos> first = PortalAperture.emittingCells(interior, Direction.Axis.X, 1234, 0.35, 0.45);
        List<BlockPos> again = PortalAperture.emittingCells(interior, Direction.Axis.X, 1234, 0.35, 0.45);
        assertEquals(first, again, "the same pass must plan the same cells");
    }

    @Test
    void consecutivePassesDiffer() {
        Set<BlockPos> interior = plane(5, 5);
        int changed = 0;
        for (long tick = 0; tick < 40; tick++) {
            if (!PortalAperture.emittingCells(interior, Direction.Axis.X, tick, 0.35, 0.45)
                    .equals(PortalAperture.emittingCells(interior, Direction.Axis.X, tick + 1, 0.35, 0.45))) {
                changed++;
            }
        }
        assertTrue(changed > 30, "a static pattern would read as a texture, not dust: " + changed);
    }

    @Test
    void densityIsTheShareOfTheRimThatEmits() {
        Set<BlockPos> interior = plane(2, 3);
        long emitted = 0;
        int passes = 4000;
        for (long tick = 0; tick < passes; tick++) {
            emitted += PortalAperture.emittingCells(interior, Direction.Axis.X, tick, 0.35, 0.45).size();
        }
        double share = emitted / (double) (passes * interior.size());
        assertEquals(0.35, share, 0.03, "measured fill share of an all-rim opening");
    }

    @Test
    void driftGoesBothWaysThroughThePlane() {
        boolean positive = false;
        boolean negative = false;
        for (long tick = 0; tick < 50 && !(positive && negative); tick++) {
            int sign = PortalAperture.driftSign(tick, new BlockPos(3, 70, 9));
            assertTrue(sign == 1 || sign == -1);
            positive |= sign == 1;
            negative |= sign == -1;
        }
        assertTrue(positive && negative, "a broadcast particle has no viewer, so both sides get fed");
    }

    @Test
    void jitterStaysInsideTheCellAndRepeats() {
        BlockPos cell = new BlockPos(-3859, -20, 3288);
        for (long tick = 0; tick < 200; tick++) {
            double j = PortalAperture.jitter(tick, cell, 0, 0.42);
            assertTrue(j >= -0.42 && j <= 0.42, "jitter escaped the cell: " + j);
        }
        assertEquals(PortalAperture.jitter(7, cell, 1, 0.42),
                PortalAperture.jitter(7, cell, 1, 0.42));
        assertNotEquals(PortalAperture.jitter(7, cell, 0, 0.42),
                PortalAperture.jitter(7, cell, 1, 0.42),
                "each axis needs its own offset or the scatter is a diagonal line");
    }

    // ------------------------------------------------------------------
    // Negative: the cases that must never happen
    // ------------------------------------------------------------------

    @Test
    void noConfigurationCanFillThePlane() {
        Set<BlockPos> interior = plane(9, 9);
        int cap = PortalAperture.emissionCap(interior.size());
        assertTrue(cap < interior.size(), "the cap must bite before the plane is full");
        for (long tick = 0; tick < 500; tick++) {
            List<BlockPos> cells = PortalAperture.emittingCells(
                    interior, Direction.Axis.X, tick, 1.0, 1.0);
            assertTrue(cells.size() <= cap,
                    "pass " + tick + " emitted " + cells.size() + " of " + interior.size());
        }
    }

    @Test
    void aCappedPassSpendsItsBudgetOnTheRim() {
        Set<BlockPos> interior = plane(9, 9);
        Map<BlockPos, Integer> depths = PortalAperture.rimDepths(interior, Direction.Axis.X);
        List<BlockPos> cells = PortalAperture.emittingCells(interior, Direction.Axis.X, 11, 1.0, 1.0);
        assertEquals(PortalAperture.emissionCap(interior.size()), cells.size());
        for (int i = 1; i < cells.size(); i++) {
            assertTrue(depths.get(cells.get(i - 1)) <= depths.get(cells.get(i)),
                    "cells must be spent rim-first");
        }
    }

    @Test
    void zeroDensityEmitsNothingAtAll() {
        Set<BlockPos> interior = plane(4, 5);
        for (long tick = 0; tick < 500; tick++) {
            assertTrue(PortalAperture.emittingCells(interior, Direction.Axis.X, tick, 0.0, 0.45).isEmpty(),
                    "particles switched off must mean switched off, pass " + tick);
        }
    }

    @Test
    void aRimOnlyBiasNeverTouchesTheMiddle() {
        Set<BlockPos> interior = plane(7, 7);
        Map<BlockPos, Integer> depths = PortalAperture.rimDepths(interior, Direction.Axis.X);
        for (long tick = 0; tick < 500; tick++) {
            for (BlockPos cell : PortalAperture.emittingCells(
                    interior, Direction.Axis.X, tick, 1.0, 0.0)) {
                assertEquals(0, depths.get(cell), "edgeBias 0 must leave the opening clear");
            }
        }
    }

    @Test
    void anEmptyOpeningCostsNothing() {
        assertTrue(PortalAperture.emittingCells(Set.of(), Direction.Axis.X, 1, 1.0, 1.0).isEmpty());
        assertTrue(PortalAperture.emittingCells(null, Direction.Axis.X, 1, 1.0, 1.0).isEmpty());
        assertTrue(PortalAperture.rimDepths(null, Direction.Axis.X).isEmpty());
        assertEquals(0, PortalAperture.emissionCap(0));
    }

    @Test
    void outOfRangeDensityCannotEscapeTheCap() {
        Set<BlockPos> interior = plane(6, 6);
        int cap = PortalAperture.emissionCap(interior.size());
        for (double density : new double[]{-1.0, 5.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            for (long tick = 0; tick < 60; tick++) {
                assertTrue(PortalAperture.emittingCells(
                                interior, Direction.Axis.X, tick, density, 1.0).size() <= cap,
                        "density " + density + " must clamp, not overflow");
            }
        }
    }
}
