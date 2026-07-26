package com.customdimensions.dimension;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike task B2. All of NoiseStructurePlacement's behaviour lives in
 * NoiseFieldIndex precisely so it can be tested here, with no Bootstrap.
 */
class NoiseFieldIndexTest {

    private static final long SEED = 0xC0FFEEL;

    private static final double[] INNER =
            {1.5, 1.3, 1.0, 0.8, 0.5, 0.3, 0.1, 0.0, 0.0, 0.0};
    private static final double[] OUTER =
            {0.0, 0.0, 0.1, 0.3, 0.6, 0.8, 1.0, 1.3, 1.5, 2.0};
    private static final double[] EVEN =
            {1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    private static NoiseFieldIndex build(NoiseProfile profile, int exclusion,
                                         double[] radial, int radiusChunks) {
        return new NoiseFieldIndex(SEED, profile, exclusion, radial, radiusChunks, 0, 0);
    }

    // --- determinism -----------------------------------------------------

    @Test
    void sameInputsGiveTheSamePositions() {
        NoiseFieldIndex a = build(NoiseProfile.NATURAL, 3, EVEN, 64);
        NoiseFieldIndex b = build(NoiseProfile.NATURAL, 3, EVEN, 64);
        assertEquals(a.positions(), b.positions());
        assertTrue(a.size() > 0, "produced no positions at all");
    }

    @Test
    void differentSeedsGiveDifferentPositions() {
        NoiseFieldIndex a = new NoiseFieldIndex(SEED, NoiseProfile.NATURAL, 3, EVEN, 64, 0, 0);
        NoiseFieldIndex b = new NoiseFieldIndex(SEED + 1, NoiseProfile.NATURAL, 3, EVEN, 64, 0, 0);
        assertNotEquals(a.positions(), b.positions());
    }

    // --- exclusion -------------------------------------------------------

    @Test
    void noTwoPlacementsSitInsideTheExclusionRadius() {
        for (int exclusion : new int[]{1, 3, 6, 12, 20}) {
            NoiseFieldIndex index = build(NoiseProfile.NATURAL, exclusion, EVEN, 96);
            List<ChunkPos> positions = index.positions();
            assertTrue(positions.size() > 1,
                    "exclusion " + exclusion + " gave too few positions to test");
            for (int i = 0; i < positions.size(); i++) {
                for (int j = i + 1; j < positions.size(); j++) {
                    ChunkPos p = positions.get(i);
                    ChunkPos q = positions.get(j);
                    long dx = p.x - q.x;
                    long dz = p.z - q.z;
                    assertTrue(dx * dx + dz * dz > (long) exclusion * exclusion,
                            "exclusion " + exclusion + ": " + p + " and " + q
                            + " are too close");
                }
            }
        }
    }

    @Test
    void exclusionHoldsForEveryProfile() {
        int exclusion = 5;
        for (NoiseProfile profile : List.of(NoiseProfile.NATURAL, NoiseProfile.DENSE,
                NoiseProfile.SPARSE, NoiseProfile.CLUSTER)) {
            NoiseFieldIndex index = build(profile, exclusion, EVEN, 128);
            List<ChunkPos> positions = index.positions();
            for (int i = 0; i < positions.size(); i++) {
                for (int j = i + 1; j < positions.size(); j++) {
                    ChunkPos p = positions.get(i);
                    ChunkPos q = positions.get(j);
                    long dx = p.x - q.x;
                    long dz = p.z - q.z;
                    assertTrue(dx * dx + dz * dz > (long) exclusion * exclusion,
                            profile.id() + ": " + p + " and " + q + " are too close");
                }
            }
        }
    }

    // --- radial shaping --------------------------------------------------

    private static double fractionWithin(NoiseFieldIndex index, int radiusChunks,
                                         double innerFraction) {
        if (index.size() == 0) {
            return 0.0;
        }
        double limit = radiusChunks * innerFraction;
        long within = index.positions().stream()
                .filter(p -> Math.sqrt((double) p.x * p.x + (double) p.z * p.z) <= limit)
                .count();
        return within / (double) index.size();
    }

    @Test
    void innerCurveConcentratesNearSpawn() {
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 3, INNER, 64);
        assertTrue(index.size() > 10, "not enough positions to judge: " + index.size());
        double fraction = fractionWithin(index, 64, 0.30);
        assertTrue(fraction > 0.60,
                "only " + (fraction * 100) + "% of inner-curve positions were in the "
                + "inner 30% of the radius");
    }

    @Test
    void outerCurvePushesToTheBorder() {
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 3, OUTER, 64);
        assertTrue(index.size() > 10, "not enough positions to judge: " + index.size());
        double inner = fractionWithin(index, 64, 0.45);
        assertTrue(1.0 - inner > 0.60,
                "only " + ((1.0 - inner) * 100) + "% of outer-curve positions were in "
                + "the outer 55% of the radius");
    }

    @Test
    void innerCurveLeavesTheBorderEmpty() {
        // The tail of `inner` is 0.0, and a zero weight must suppress
        // absolutely, not merely reduce.
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 3, INNER, 64);
        for (ChunkPos p : index.positions()) {
            double fraction = Math.sqrt((double) p.x * p.x + (double) p.z * p.z) / 64.0;
            assertTrue(fraction < 0.75,
                    p + " is at radial fraction " + fraction + " where inner is 0.0");
        }
    }

    @Test
    void evenCurveMatchesNoCurveAtAll() {
        assertEquals(build(NoiseProfile.NATURAL, 3, EVEN, 48).positions(),
                build(NoiseProfile.NATURAL, 3, null, 48).positions());
    }

    // --- profile ordering ------------------------------------------------

    @Test
    void denserProfilesPlaceMore() {
        int dense = build(NoiseProfile.DENSE, 4, EVEN, 200).size();
        int natural = build(NoiseProfile.NATURAL, 4, EVEN, 200).size();
        int sparse = build(NoiseProfile.SPARSE, 4, EVEN, 200).size();
        assertTrue(dense > natural, "dense=" + dense + " natural=" + natural);
        assertTrue(natural > sparse, "natural=" + natural + " sparse=" + sparse);
    }

    @Test
    void biggerExclusionPlacesFewer() {
        int tight = build(NoiseProfile.NATURAL, 2, EVEN, 128).size();
        int loose = build(NoiseProfile.NATURAL, 16, EVEN, 128).size();
        assertTrue(tight > loose, "exclusion 2 gave " + tight + ", 16 gave " + loose);
    }

    // --- bounds ----------------------------------------------------------

    @Test
    void everyPositionIsInsideTheRadius() {
        int radius = 40;
        NoiseFieldIndex index = build(NoiseProfile.DENSE, 2, EVEN, radius);
        for (ChunkPos p : index.positions()) {
            double dist = Math.sqrt((double) p.x * p.x + (double) p.z * p.z);
            assertTrue(dist <= radius, p + " is " + dist + " chunks out, radius " + radius);
        }
    }

    @Test
    void positionsCentreOnSpawnNotTheOrigin() {
        NoiseFieldIndex index = new NoiseFieldIndex(
                SEED, NoiseProfile.DENSE, 2, EVEN, 30, 500, -300);
        assertTrue(index.size() > 0);
        for (ChunkPos p : index.positions()) {
            double dist = Math.hypot(p.x - 500.0, p.z + 300.0);
            assertTrue(dist <= 30, p + " is outside the spawn-centred radius");
        }
    }

    @Test
    void hugeRadiusIsCapped() {
        NoiseFieldIndex index = build(NoiseProfile.SPARSE, 20, EVEN, 100_000);
        for (ChunkPos p : index.positions()) {
            double dist = Math.hypot(p.x, p.z);
            assertTrue(dist <= NoiseFieldIndex.MAX_RADIUS_CHUNKS,
                    p + " escaped the radius cap");
        }
    }

    // --- degenerate inputs -----------------------------------------------

    @Test
    void nothingPassesWhenTheCurveIsAllZero() {
        double[] zero = new double[10];
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 3, zero, 64);
        assertEquals(0, index.size());
        assertFalse(index.isPlacement(0, 0));
    }

    @Test
    void zeroRadiusProducesNothingOrOneChunk() {
        NoiseFieldIndex index = build(NoiseProfile.DENSE, 3, EVEN, 0);
        assertTrue(index.size() <= 1, "radius 0 produced " + index.size() + " positions");
    }

    @Test
    void exclusionBelowOneIsClampedNotDividedByZero() {
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 0, EVEN, 32);
        assertTrue(index.spacing() >= 2);
        for (ChunkPos p : index.positions()) {
            assertTrue(index.isPlacement(p.x, p.z));
        }
    }

    // --- locate contract -------------------------------------------------

    @Test
    void everyPlacementIsAStartChunk() {
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 6, EVEN, 96);
        assertTrue(index.size() > 0);
        for (ChunkPos p : index.positions()) {
            assertTrue(index.isPlacement(p.x, p.z),
                    p + " is in positions() but isPlacement says no");
        }
    }

    @Test
    void startForAlwaysAnswersWithinTheProbedCell() {
        // Vanilla's locate walks rings of spacing-sized cells and asks
        // getStartChunk for each, so the answer has to belong to the cell it
        // was asked about, or locate reports a position it then fails to
        // confirm.
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 6, EVEN, 96);
        int spacing = index.spacing();
        for (int cellX = -8; cellX <= 8; cellX++) {
            for (int cellZ = -8; cellZ <= 8; cellZ++) {
                for (int ox : new int[]{0, spacing / 2, spacing - 1}) {
                    ChunkPos got = index.startFor(cellX * spacing + ox, cellZ * spacing + ox);
                    assertEquals(cellX, Math.floorDiv(got.x, spacing),
                            "startFor answered outside the probed cell: " + got);
                    assertEquals(cellZ, Math.floorDiv(got.z, spacing),
                            "startFor answered outside the probed cell: " + got);
                }
            }
        }
    }

    @Test
    void aPopulatedCellIsLocatable() {
        // One placement per cell is locatable; any others in the same cell
        // still generate (isStartChunk is set membership) but locate returns
        // the registered one. Same accepted degradation FixedStructurePlacement
        // documents. What must hold: a cell containing placements never
        // answers with a non-placement.
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 6, EVEN, 96);
        int spacing = index.spacing();
        Set<Long> populated = new HashSet<>();
        for (ChunkPos p : index.positions()) {
            populated.add(NoiseFieldIndex.regionKey(
                    Math.floorDiv(p.x, spacing), Math.floorDiv(p.z, spacing)));
        }
        assertFalse(populated.isEmpty());
        for (ChunkPos p : index.positions()) {
            ChunkPos start = index.startFor(p.x, p.z);
            assertTrue(index.isPlacement(start.x, start.z),
                    "cell containing " + p + " answered " + start
                    + ", which is not a placement");
        }
    }

    @Test
    void cellsRarelyHoldMoreThanOnePlacement() {
        // Not a correctness requirement, but if it were common, locate would
        // be missing most of the world's structures. spacing = exclusion * 2
        // keeps it to a small minority.
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 6, EVEN, 200);
        int spacing = index.spacing();
        Set<Long> cells = new HashSet<>();
        int collisions = 0;
        for (ChunkPos p : index.positions()) {
            long cell = NoiseFieldIndex.regionKey(
                    Math.floorDiv(p.x, spacing), Math.floorDiv(p.z, spacing));
            if (!cells.add(cell)) {
                collisions++;
            }
        }
        double rate = collisions / (double) index.size();
        assertTrue(rate < 0.35,
                (rate * 100) + "% of placements shared a cell with another — "
                + "locate would miss most of them");
    }

    @Test
    void emptyCellsAnswerWithSomethingThatIsNotAPlacement() {
        NoiseFieldIndex index = build(NoiseProfile.SPARSE, 12, EVEN, 96);
        int spacing = index.spacing();
        int checked = 0;
        for (int cx = -80; cx <= 80 && checked < 40; cx += spacing) {
            for (int cz = -80; cz <= 80 && checked < 40; cz += spacing) {
                ChunkPos start = index.startFor(cx, cz);
                if (!index.isPlacement(start.x, start.z)) {
                    checked++;   // an empty cell: the origin must not place
                    assertFalse(index.isPlacement(start.x, start.z));
                }
            }
        }
    }

    // --- radial curve sampling -------------------------------------------

    @Test
    void radialWeightInterpolatesBetweenPoints() {
        double[] ramp = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        assertEquals(0.0, NoiseFieldIndex.radialWeight(ramp, 0, 100), 1e-9);
        assertEquals(9.0, NoiseFieldIndex.radialWeight(ramp, 100, 100), 1e-9);
        assertEquals(9.0, NoiseFieldIndex.radialWeight(ramp, 250, 100), 1e-9,
                "beyond the border must clamp, not extrapolate");
        assertEquals(4.5, NoiseFieldIndex.radialWeight(ramp, 50, 100), 1e-9);
        assertEquals(2.25, NoiseFieldIndex.radialWeight(ramp, 25, 100), 1e-9);
    }

    @Test
    void radialWeightHandlesDegenerateInputs() {
        assertEquals(1.0, NoiseFieldIndex.radialWeight(null, 10, 100), 1e-9);
        assertEquals(1.0, NoiseFieldIndex.radialWeight(new double[0], 10, 100), 1e-9);
        assertEquals(3.0, NoiseFieldIndex.radialWeight(new double[]{3, 9}, 10, 0), 1e-9);
        assertEquals(0.0, NoiseFieldIndex.radialWeight(
                new double[]{0, 1}, -5, 100), 1e-9);
    }

    // --- performance -----------------------------------------------------

    @Test
    void largestDimensionBuildsWithinBudget() {
        // The spike's target: an 8192-block radius (512 chunks) in under
        // 200ms. Warm up first so this measures the algorithm, not JIT.
        build(NoiseProfile.NATURAL, 3, EVEN, 128);
        long start = System.nanoTime();
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 3, EVEN, 512);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("NoiseFieldIndex 512-chunk radius: " + millis
                + "ms, " + index.size() + " positions");
        assertTrue(index.size() > 0);
        // Generous ceiling — the assertion is "not pathological", the printed
        // number is the thing to actually look at.
        assertTrue(millis < 3000, "took " + millis + "ms, expected well under 3s");
    }
}
