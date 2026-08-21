package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure logic — no Minecraft Bootstrap, no server.
 */
class NoiseProfileTest {

    private static final long SEED = 12345L;

    // --- determinism -----------------------------------------------------

    @Test
    void evaluateIsDeterministicAcrossCalls() {
        double expected = NoiseProfile.NATURAL.evaluate(SEED, 100, 200);
        for (int i = 0; i < 1000; i++) {
            assertEquals(expected, NoiseProfile.NATURAL.evaluate(SEED, 100, 200),
                    0.0, "evaluate must be bit-identical across calls");
        }
    }

    @Test
    void freshSamplerGivesTheSameAnswer() {
        // The per-seed cache must not be what makes it deterministic.
        double cached = NoiseProfile.NATURAL.evaluate(SEED, -73, 41);
        double fresh = new StructureNoise(SEED).sampleChunk(-73, 41, 0.025);
        assertEquals(fresh, cached, 0.0);
    }

    @Test
    void differentSeedsGiveDifferentFields() {
        // Regression: without the irrational lattice offset in StructureNoise,
        // every 40th chunk on both axes scored exactly 0.5 for EVERY seed
        // (Perlin is 0 at a lattice point), so this failed at i = 0, 40, 80,
        // 120, 160 — a permanent seed-independent grid of candidates.
        int same = 0;
        for (int i = 0; i < 200; i++) {
            if (NoiseProfile.NATURAL.evaluate(SEED, i, i)
                    == NoiseProfile.NATURAL.evaluate(SEED + 1, i, i)) {
                same++;
            }
        }
        assertEquals(0, same, "seeds produced identical values at " + same + "/200 chunks");
    }

    @Test
    void noChunkIsSeedIndependent() {
        // The general form of the lattice bug. Two seeds agreeing at one cell
        // is ordinary gradient-noise coincidence; a cell that reads the same
        // across MANY unrelated seeds is a structural fixed point, which is
        // what a lattice hit was.
        long[] seeds = {SEED, SEED * 31 + 7, -SEED, 0L, 1L, 1L << 40, -1L, 987654321L};
        List<NoiseProfile> profiles = List.of(
                NoiseProfile.NATURAL, NoiseProfile.DENSE, NoiseProfile.SPARSE);
        for (NoiseProfile p : profiles) {
            for (int cx = -60; cx <= 200; cx++) {
                for (int cz = -60; cz <= 200; cz += 7) {
                    double first = p.evaluate(seeds[0], cx, cz);
                    boolean moved = false;
                    for (int i = 1; i < seeds.length && !moved; i++) {
                        moved = p.evaluate(seeds[i], cx, cz) != first;
                    }
                    assertTrue(moved, p.id() + " reads " + first + " at ("
                            + cx + ", " + cz + ") for all " + seeds.length + " seeds");
                }
            }
        }
    }

    // --- range -----------------------------------------------------------

    @Test
    void everyProfileStaysInUnitRange() {
        // Deterministic pseudo-random probe rather than java.util.Random, so a
        // failure is reproducible.
        List<NoiseProfile> profiles = List.of(
                NoiseProfile.NATURAL, NoiseProfile.DENSE,
                NoiseProfile.SPARSE, NoiseProfile.CLUSTER);
        long state = 0x5DEECE66DL;
        for (int i = 0; i < 10_000; i++) {
            state = StructureNoise.mix64(state + 0x9E3779B97F4A7C15L);
            int cx = (int) (state % 100_000);
            int cz = (int) ((state >>> 32) % 100_000);
            for (NoiseProfile p : profiles) {
                double v = p.evaluate(SEED, cx, cz);
                assertTrue(v >= 0.0 && v <= 1.0,
                        p.id() + " produced " + v + " at (" + cx + ", " + cz + ")");
            }
        }
    }

    // --- profile distinction ---------------------------------------------

    /**
     * The window has to be wide enough to cover many lattice cells of the
     * COARSEST frequency in play, or the measurement is one hillside rather
     * than a distribution. `sparse` at frequency 0.015 has a 67-chunk lattice
     * period, so a 100x100 probe sees about 1.5 cells and can easily report a
     * higher hit rate than `natural` — which is exactly what a first version
     * of this test did.
     */
    private static final int WINDOW = 1000;   // chunks, +/- about the origin
    private static final int STEP = 4;

    private static double hitRate(NoiseProfile profile) {
        int hits = 0;
        int total = 0;
        for (int cx = -WINDOW; cx < WINDOW; cx += STEP) {
            for (int cz = -WINDOW; cz < WINDOW; cz += STEP) {
                total++;
                if (profile.evaluate(SEED, cx, cz) > profile.threshold()) {
                    hits++;
                }
            }
        }
        return hits / (double) total;
    }

    @Test
    void denseBeatsNaturalBeatsSparse() {
        double dense = hitRate(NoiseProfile.DENSE);
        double natural = hitRate(NoiseProfile.NATURAL);
        double sparse = hitRate(NoiseProfile.SPARSE);
        assertTrue(dense > natural,
                "dense=" + dense + " must exceed natural=" + natural);
        assertTrue(natural > sparse,
                "natural=" + natural + " must exceed sparse=" + sparse);
        assertTrue(sparse > 0.0, "sparse produced nothing at all");
    }

    @Test
    void hitRatesLandInTheirDesignBands() {
        // Bands are wide enough not to be brittle, tight enough that
        // retuning a threshold or frequency by accident fails.
        assertRate("natural", hitRate(NoiseProfile.NATURAL), 0.12, 0.32);
        assertRate("dense", hitRate(NoiseProfile.DENSE), 0.45, 0.75);
        assertRate("sparse", hitRate(NoiseProfile.SPARSE), 0.01, 0.12);
    }

    private static void assertRate(String name, double rate, double lo, double hi) {
        assertTrue(rate >= lo && rate <= hi,
                name + " hit rate " + (rate * 100) + "% outside the expected "
                + (lo * 100) + "-" + (hi * 100) + "%");
    }

    @Test
    void clusterLeavesMostOfTheWorldEmpty() {
        // Coarse layer frequency 0.008 = a 125-chunk lattice period, so this
        // needs the widest window of the lot.
        int nonZero = 0;
        int total = 0;
        for (int cx = -WINDOW; cx < WINDOW; cx += STEP) {
            for (int cz = -WINDOW; cz < WINDOW; cz += STEP) {
                total++;
                if (NoiseProfile.CLUSTER.evaluate(SEED, cx, cz) > 0.0) {
                    nonZero++;
                }
            }
        }
        double fraction = nonZero / (double) total;
        assertTrue(fraction <= 0.20,
                "cluster activated " + (fraction * 100) + "% of chunks, expected <= 20%");
        assertTrue(fraction > 0.0, "cluster activated nothing at all");
    }

    @Test
    void clusterPlacesLessOftenThanSparse() {
        // The whole point: mostly empty, then a dense pocket. Overall density
        // must come out below `sparse` even though the fine layer's frequency
        // (0.05) is the highest of any profile.
        int placed = 0;
        int total = 0;
        for (int cx = -WINDOW; cx < WINDOW; cx += STEP) {
            for (int cz = -WINDOW; cz < WINDOW; cz += STEP) {
                total++;
                if (NoiseProfile.CLUSTER.evaluate(SEED, cx, cz)
                        > NoiseProfile.CLUSTER.threshold()) {
                    placed++;
                }
            }
        }
        double rate = placed / (double) total;
        assertTrue(rate > 0.0, "cluster placed nothing at all");
        assertTrue(rate < hitRate(NoiseProfile.SPARSE),
                "cluster rate " + rate + " should undercut sparse");
    }

    @Test
    void clusterActiveRegionsAreContiguous() {
        // The point of the profile: active chunks form pockets, not confetti.
        // An active chunk should almost always have an active neighbour.
        int active = 0;
        int isolated = 0;
        for (int cx = 1; cx < 199; cx++) {
            for (int cz = 1; cz < 199; cz++) {
                if (NoiseProfile.CLUSTER.evaluate(SEED, cx, cz) <= 0.0) {
                    continue;
                }
                active++;
                boolean neighbour =
                        NoiseProfile.CLUSTER.evaluate(SEED, cx - 1, cz) > 0.0
                        || NoiseProfile.CLUSTER.evaluate(SEED, cx + 1, cz) > 0.0
                        || NoiseProfile.CLUSTER.evaluate(SEED, cx, cz - 1) > 0.0
                        || NoiseProfile.CLUSTER.evaluate(SEED, cx, cz + 1) > 0.0;
                if (!neighbour) {
                    isolated++;
                }
            }
        }
        assertTrue(active > 0, "no active cluster chunks to test");
        assertTrue(isolated < active * 0.05,
                isolated + " of " + active + " active chunks were isolated — "
                + "the coarse layer is not producing regions");
    }

    @Test
    void exclusionMultipliersMatchTheSpec() {
        assertEquals(2.0, NoiseProfile.NATURAL.exclusionMultiplier(), 0.0);
        assertEquals(1.6, NoiseProfile.DENSE.exclusionMultiplier(), 1e-9);
        assertEquals(2.6, NoiseProfile.SPARSE.exclusionMultiplier(), 1e-9);
        assertEquals(0.8, NoiseProfile.CLUSTER.exclusionMultiplier(), 1e-9);
    }

    @Test
    void thresholdsMatchTheSpec() {
        assertEquals(0.68, NoiseProfile.NATURAL.threshold(), 1e-9);
        assertEquals(0.45, NoiseProfile.DENSE.threshold(), 1e-9);
        assertEquals(0.85, NoiseProfile.SPARSE.threshold(), 1e-9);
        assertEquals(0.80, NoiseProfile.CLUSTER.threshold(), 1e-9);
    }

    // --- fromString ------------------------------------------------------

    @Test
    void fromStringResolvesKnownNames() {
        assertSame(NoiseProfile.NATURAL, NoiseProfile.fromString("natural"));
        assertSame(NoiseProfile.DENSE, NoiseProfile.fromString("dense"));
        assertSame(NoiseProfile.SPARSE, NoiseProfile.fromString("sparse"));
        assertSame(NoiseProfile.CLUSTER, NoiseProfile.fromString("cluster"));
        assertSame(NoiseProfile.NATURAL, NoiseProfile.fromString("NATURAL"),
                "config values are case-insensitive elsewhere in this mod");
    }

    @Test
    void fromStringTreatsNoneAndAbsenceAsNoProfile() {
        assertNull(NoiseProfile.fromString("none"));
        assertNull(NoiseProfile.fromString(null));
        assertNull(NoiseProfile.fromString(""));
    }

    @Test
    void fromStringReportsUnknownNamesInsteadOfGuessing() {
        List<String> reported = new ArrayList<>();
        assertNull(NoiseProfile.fromString("garbage", reported::add));
        assertEquals(List.of("garbage"), reported);
        // ...and `none` is not an error.
        reported.clear();
        assertNull(NoiseProfile.fromString("none", reported::add));
        assertTrue(reported.isEmpty());
    }

    // --- the underlying field --------------------------------------------

    @Test
    void samplerIsSmoothNotHashed() {
        // Adjacent chunks must be correlated, or "noise" is just a hash and
        // the exclusion/cluster behaviour is meaningless.
        StructureNoise noise = new StructureNoise(SEED);
        double worst = 0.0;
        for (int i = 0; i < 500; i++) {
            double a = noise.sampleChunk(i, 7, 0.025);
            double b = noise.sampleChunk(i + 1, 7, 0.025);
            worst = Math.max(worst, Math.abs(a - b));
        }
        assertTrue(worst < 0.2,
                "adjacent samples differed by " + worst + " — field is not smooth");
    }

    @Test
    void fieldIsWellSpreadNotDegenerate() {
        // A broken Fisher-Yates (e.g. a negative index from a signed
        // remainder) would duplicate permutation entries and collapse the
        // field's range. Sample off-lattice: ON a lattice point Perlin is
        // always exactly 0, so a lattice-aligned probe proves nothing.
        Set<Double> distinct = new HashSet<>();
        double min = 1.0;
        double max = 0.0;
        StructureNoise noise = new StructureNoise(SEED);
        for (int i = 0; i < 2000; i++) {
            double v = noise.sampleChunk(i, i * 3 + 1, 0.025);
            distinct.add(v);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        // Not 2000: a smooth field walked along a line produces occasional
        // exact double repeats. A degenerate permutation collapses it far
        // harder than this — the range asserts below are the sharper check.
        assertTrue(distinct.size() > 1700,
                "only " + distinct.size() + " distinct values over 2000 samples");
        assertTrue(min < 0.25, "field never went low: min=" + min);
        assertTrue(max > 0.75, "field never went high: max=" + max);
    }

    @Test
    void seedZeroWorks() {
        assertNotNull(new StructureNoise(0L));
        double v = NoiseProfile.NATURAL.evaluate(0L, 0, 0);
        assertTrue(v >= 0.0 && v <= 1.0);
    }

    @Test
    void negativeCoordinatesBehave() {
        for (int i = 1; i <= 200; i++) {
            double v = NoiseProfile.NATURAL.evaluate(SEED, -i, -i * 3);
            assertTrue(v >= 0.0 && v <= 1.0, "negative coords produced " + v);
        }
    }
}
