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

    // The shipped curves (structure-type-defaults.json). Relative DENSITY per
    // radial decile, spawn -> border, each normalised to an area-weighted mean
    // of 1.0 so a curve redistributes content without changing how much of it
    // there is.
    private static final double[] INNER =
            {2.8, 2.3, 1.9, 1.6, 1.35, 1.15, 0.95, 0.8, 0.65, 0.55};
    private static final double[] OUTER =
            {0.3, 0.35, 0.45, 0.55, 0.65, 0.8, 0.95, 1.1, 1.3, 1.55};
    private static final double[] EVEN =
            {1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    // A deliberate hard edge — the one thing a 0.0 in a curve still means.
    private static final double[] OUTER_HALF_ONLY =
            {0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0};

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

    /**
     * Placements per unit area in one radial decile, normalised so a uniform
     * layout reads 1.0 everywhere. Density, not share: decile 9 covers 19% of
     * the disc and decile 0 covers 1%, so comparing raw counts would read any
     * uniform layout as border-biased.
     */
    private static double densityInDecile(NoiseFieldIndex index, int radiusChunks,
                                          int decile) {
        int bins = 10;
        int[] hist = new int[bins];
        for (ChunkPos p : index.positions()) {
            double d = Math.sqrt((double) p.x * p.x + (double) p.z * p.z);
            hist[Math.min(bins - 1, (int) (d / radiusChunks * bins))]++;
        }
        int total = index.size();
        if (total == 0) {
            return 0.0;
        }
        // Annulus areas go as 2i+1 and sum to 100 over ten bins.
        return (hist[decile] / (double) total) / ((2 * decile + 1) / 100.0);
    }

    @Test
    void innerCurveIsDenserNearSpawnThanAtTheBorder() {
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 3, INNER, 512);
        assertTrue(index.size() > 100, "not enough positions to judge: " + index.size());
        double near = densityInDecile(index, 512, 0);
        double far = densityInDecile(index, 512, 9);
        assertTrue(near > 2.0 * far,
                "inner asks for 2.8 vs 0.55 (5.1x) but measured " + near + " vs " + far);
    }

    @Test
    void outerCurveIsDenserAtTheBorderThanNearSpawn() {
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 3, OUTER, 512);
        assertTrue(index.size() > 100, "not enough positions to judge: " + index.size());
        double near = densityInDecile(index, 512, 0);
        double far = densityInDecile(index, 512, 9);
        assertTrue(far > 2.0 * near,
                "outer asks for 0.3 vs 1.55 (5.2x) but measured " + near + " vs " + far);
    }

    @Test
    void aTaperThinsTheBorderWithoutEmptyingIt() {
        // The regression this whole change exists for. Before 2026-07-29 the
        // curve multiplied into the noise before the threshold test, so the
        // moment a taper fell below the profile's threshold the band went to
        // absolute zero — 33 dimensions had no village past a third of their
        // radius. A taper must now thin, never delete.
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 3, INNER, 512);
        for (int decile = 0; decile < 10; decile++) {
            assertTrue(densityInDecile(index, 512, decile) > 0.0,
                    "decile " + decile + " is empty under a curve that only tapers");
        }
    }

    @Test
    void aZeroInTheCurveStillSuppressesAbsolutely() {
        // The escape hatch: 0.0 is now the ONLY way to ask for a hard edge, and
        // it has to keep working, or an author cannot express one at all.
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 3, OUTER_HALF_ONLY, 512);
        assertTrue(index.size() > 100, "not enough positions to judge: " + index.size());
        for (ChunkPos p : index.positions()) {
            double fraction = Math.sqrt((double) p.x * p.x + (double) p.z * p.z) / 512.0;
            assertTrue(fraction > 0.44,
                    p + " is at radial fraction " + fraction + " where the curve is 0.0");
        }
    }

    @Test
    void aUniformCurveKeepsTheGroupsOwnExclusion() {
        // Weight 1.0 must reproduce the unscaled separation exactly, or every
        // `even` group in the shipped config would have moved for nothing.
        assertEquals(7, NoiseFieldIndex.exclusionFor(7, 1.0));
        assertEquals(14, build(NoiseProfile.NATURAL, 7, EVEN, 64).spacing());
        assertEquals(14, build(NoiseProfile.NATURAL, 7, null, 64).spacing());
    }

    @Test
    void exclusionScalesAsTheInverseSquareRootOfTheWeight() {
        // d = base / sqrt(weight), so density (which goes as 1/d^2) is directly
        // proportional to the weight. Four times the weight, half the spacing.
        assertEquals(20, NoiseFieldIndex.exclusionFor(20, 1.0));
        assertEquals(10, NoiseFieldIndex.exclusionFor(20, 4.0));
        assertEquals(40, NoiseFieldIndex.exclusionFor(20, 0.25));
        // Capped at MAX_EXCLUSION_SCALE so the neighbourhood scan stays bounded.
        assertEquals(80, NoiseFieldIndex.exclusionFor(20, 0.0625));
        assertEquals(80, NoiseFieldIndex.exclusionFor(20, 0.0001));
        // Never below one chunk, whatever the peak.
        assertEquals(1, NoiseFieldIndex.exclusionFor(1, 3.0));
        // Zero is not a separation, it is a suppression.
        assertEquals(0, NoiseFieldIndex.exclusionFor(20, 0.0));
    }

    @Test
    void spacingComesFromTheCurvesPeakNotItsBase() {
        // The locate cell has to fit the DENSEST packing the curve can ask for.
        // Sized from the base instead, a cell would hold two placements wherever
        // the weight peaks and byRegion would silently drop all but the first.
        NoiseFieldIndex index = build(NoiseProfile.NATURAL, 6, INNER, 512);
        assertEquals(NoiseFieldIndex.exclusionFor(6, 2.8) * 2, index.spacing());
        assertTrue(index.spacing() < 12, "peak-scaled spacing must beat the base's 12");
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

    // --- spawn clearance --------------------------------------------------

    @Test
    void clearSpawnRadiusRemovesEveryPlacementInsideTheDisc() {
        NoiseFieldIndex open = new NoiseFieldIndex(
                SEED, NoiseProfile.DENSE, 3, EVEN, 128, 0, 0, 0);
        NoiseFieldIndex cleared = new NoiseFieldIndex(
                SEED, NoiseProfile.DENSE, 3, EVEN, 128, 0, 0, 16);

        long insideOpen = open.positions().stream()
                .filter(p -> p.x * p.x + p.z * p.z < 16 * 16).count();
        assertTrue(insideOpen > 0,
                "the control must have placements inside the disc, or this proves nothing");
        assertEquals(0, cleared.positions().stream()
                        .filter(p -> p.x * p.x + p.z * p.z < 16 * 16).count(),
                "no placement may land inside clearSpawnChunks of spawn");
        assertTrue(cleared.size() < open.size(),
                "clearing a disc must remove placements, not merely move them");
    }

    @Test
    void clearSpawnRadiusIsCentredOnSpawnNotTheOrigin() {
        NoiseFieldIndex cleared = new NoiseFieldIndex(
                SEED, NoiseProfile.DENSE, 3, EVEN, 128, 40, -25, 12);
        for (ChunkPos p : cleared.positions()) {
            long dx = p.x - 40L;
            long dz = p.z + 25L;
            assertTrue(dx * dx + dz * dz >= 12L * 12L,
                    "placement " + p + " is inside the disc around (40, -25)");
        }
    }

    @Test
    void zeroClearanceIsByteIdenticalToNoClearance() {
        // The conditional-fingerprint promise: a dimension that does not use
        // the feature must generate exactly the world it generated before.
        NoiseFieldIndex before = build(NoiseProfile.NATURAL, 3, EVEN, 64);
        NoiseFieldIndex after = new NoiseFieldIndex(
                SEED, NoiseProfile.NATURAL, 3, EVEN, 64, 0, 0, 0);
        assertEquals(before.positions(), after.positions());
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
