package com.customdimensions.dimension;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Vanilla's nearest-hypercube lookup, on a synthetic table, through
 * {@code getValueSimple} — the plain argmin, not the tree lookup whose
 * {@code ThreadLocal} incumbent decides ties ([T59]).
 *
 * <p>Every share instrument this pack has ever had rests on four properties of
 * {@code NoiseHypercube.getSquaredDistance}: an axis a cell leaves open costs
 * it nothing, a range contains its ends, the six axis terms sum in squares, and
 * {@code square(offset)} is added last and unconditionally. They are pinned
 * here so an instrument can read them off a passing test rather than
 * re-deriving them.
 *
 * <p>The depth pair at the bottom is [K7]: the climate grids sample at y=0 and
 * the game reads block y 64, half a unit lower, and that half unit decides
 * which cell wins.
 */
class NearestHypercubeLookupTest {

    private static MultiNoiseUtil.ParameterRange range(double lo, double hi) {
        return MultiNoiseUtil.ParameterRange.of((float) lo, (float) hi);
    }

    private static final MultiNoiseUtil.ParameterRange OPEN = range(-2.0, 2.0);

    /** Blocks below the grids' sampled height, over the depth gradient's -1/128 per block. */
    private static final double DEPTH_AT_Y64 = -0.5;

    private static MultiNoiseUtil.NoiseHypercube cube(
            MultiNoiseUtil.ParameterRange temperature, MultiNoiseUtil.ParameterRange humidity,
            MultiNoiseUtil.ParameterRange continentalness, MultiNoiseUtil.ParameterRange erosion,
            MultiNoiseUtil.ParameterRange depth, MultiNoiseUtil.ParameterRange weirdness,
            double offset) {
        return MultiNoiseUtil.createNoiseHypercube(temperature, humidity, continentalness,
                erosion, depth, weirdness, (float) offset);
    }

    /** A cell constraining temperature and humidity alone. */
    private static MultiNoiseUtil.NoiseHypercube surfaceCube(
            MultiNoiseUtil.ParameterRange temperature, MultiNoiseUtil.ParameterRange humidity,
            double offset) {
        return cube(temperature, humidity, OPEN, OPEN, OPEN, OPEN, offset);
    }

    /** A cell constraining depth alone — a cave band's shape. */
    private static MultiNoiseUtil.NoiseHypercube depthCube(double lo, double hi, double offset) {
        return cube(OPEN, OPEN, OPEN, OPEN, range(lo, hi), OPEN, offset);
    }

    private static MultiNoiseUtil.NoiseValuePoint point(double temperature, double humidity,
                                                        double depth, double weirdness) {
        return MultiNoiseUtil.createNoiseValuePoint((float) temperature, (float) humidity,
                0.0f, 0.0f, (float) depth, (float) weirdness);
    }

    @SafeVarargs
    private static String winner(MultiNoiseUtil.NoiseValuePoint at,
                                 Pair<MultiNoiseUtil.NoiseHypercube, String>... cells) {
        return new MultiNoiseUtil.Entries<>(List.of(cells)).getValueSimple(at);
    }

    // ------------------------------------------------------- the four properties

    @Test
    void anAxisACellLeavesOpenCostsItNothing() {
        // The ratchet in miniature: the band is inside on its one axis and free
        // on the rest, so the native's second axis is a cost only the native pays.
        Pair<MultiNoiseUtil.NoiseHypercube, String> band =
                Pair.of(surfaceCube(OPEN, range(-0.5, 0.5), 0.0), "band");
        Pair<MultiNoiseUtil.NoiseHypercube, String> exact =
                Pair.of(surfaceCube(range(0.5, 0.6), range(-0.5, 0.5), 0.0), "native");

        assertEquals("band", winner(point(0.0, 0.0, 0.0, 0.0), band, exact));
        assertEquals("band", winner(point(0.0, 0.0, 0.0, 0.0), exact, band),
                "table order must not decide this — the band wins on distance");
    }

    @Test
    void anOffsetIsWhatLetsTheExactNativeWin() {
        // The same table with the band carrying an offset. It is the only term
        // that does not depend on the sample, so it is the only one that can
        // hand a well-fitted native the point.
        Pair<MultiNoiseUtil.NoiseHypercube, String> band =
                Pair.of(surfaceCube(OPEN, range(-0.5, 0.5), 0.1), "band");
        Pair<MultiNoiseUtil.NoiseHypercube, String> exact =
                Pair.of(surfaceCube(range(-0.1, 0.1), range(-0.5, 0.5), 0.0), "native");

        assertEquals("native", winner(point(0.0, 0.0, 0.0, 0.0), band, exact));
    }

    @Test
    void theOffsetIsPaidWhereverTheSampleSits() {
        // Two identical windows, one carrying an offset. The axis terms are
        // equal at every sample, inside the window and far outside it, so the
        // offset decides all of them.
        Pair<MultiNoiseUtil.NoiseHypercube, String> plain =
                Pair.of(cube(OPEN, OPEN, OPEN, OPEN, OPEN, range(-0.5, 0.5), 0.0), "plain");
        Pair<MultiNoiseUtil.NoiseHypercube, String> weighted =
                Pair.of(cube(OPEN, OPEN, OPEN, OPEN, OPEN, range(-0.5, 0.5), 0.1), "weighted");

        for (double weirdness : new double[] {0.0, 0.5, 1.9, -1.9}) {
            assertEquals("plain", winner(point(0.0, 0.0, 0.0, weirdness), weighted, plain),
                    "the offset went unpaid at weirdness " + weirdness);
        }
    }

    @Test
    void aRangeContainsItsEndsAndTheTieIsSettledByTableOrder() {
        // Two bands sharing a boundary are BOTH at distance zero from a sample
        // sitting on it, so the plain argmin returns whichever came first. The
        // live tree lookup hands the same tie to the incumbent instead ([T59]),
        // which is why a share measured this way is a model and not the world.
        Pair<MultiNoiseUtil.NoiseHypercube, String> lower =
                Pair.of(cube(OPEN, OPEN, OPEN, OPEN, OPEN, range(-0.5, 0.0), 0.0), "lower");
        Pair<MultiNoiseUtil.NoiseHypercube, String> upper =
                Pair.of(cube(OPEN, OPEN, OPEN, OPEN, OPEN, range(0.0, 0.5), 0.0), "upper");

        MultiNoiseUtil.NoiseValuePoint onTheBoundary = point(0.0, 0.0, 0.0, 0.0);
        assertEquals("lower", winner(onTheBoundary, lower, upper));
        assertEquals("upper", winner(onTheBoundary, upper, lower),
                "a shared boundary must be a genuine tie, not a win for one of them");
    }

    @Test
    void distanceIsMeasuredFromTheNearerEndAndTheAxesSumInSquares() {
        // Two cells whose ranking flips on the second axis alone: the winner at
        // (0.3, 0.4) loses at (0.3, 0.7) because 0.09 + 0.49 exceeds 0.49.
        Pair<MultiNoiseUtil.NoiseHypercube, String> pinned =
                Pair.of(surfaceCube(range(0.0, 0.0), range(0.0, 0.0), 0.0), "pinned");
        Pair<MultiNoiseUtil.NoiseHypercube, String> distant =
                Pair.of(surfaceCube(range(1.0, 1.0), OPEN, 0.0), "distant");

        assertEquals("pinned", winner(point(0.3, 0.4, 0.0, 0.0), pinned, distant));
        assertEquals("distant", winner(point(0.3, 0.7, 0.0, 0.0), pinned, distant),
                "0.7 from the nearer end of [1.0, 1.0] must beat 0.3 and 0.7 summed in squares");
    }

    @Test
    void anAxisNoCellConstrainsCannotMoveTheArgmin() {
        // An axis every cell leaves open adds the same term to every distance,
        // so it is common-mode. A share instrument may drop such an axis; this
        // is the claim that lets it.
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> table = new ArrayList<>();
        table.add(Pair.of(cube(OPEN, OPEN, OPEN, OPEN, OPEN, range(-0.9, -0.3), 0.0), "a"));
        table.add(Pair.of(cube(OPEN, OPEN, OPEN, OPEN, OPEN, range(-0.1, 0.2), 0.0), "b"));
        table.add(Pair.of(cube(OPEN, OPEN, OPEN, OPEN, OPEN, range(0.4, 0.9), 0.0), "c"));
        MultiNoiseUtil.Entries<String> entries = new MultiNoiseUtil.Entries<>(table);

        for (int w = -20; w <= 20; w++) {
            double weirdness = w / 10.0;
            String atZero = entries.getValueSimple(point(0.0, 0.0, 0.0, weirdness));
            for (double depth : new double[] {-2.0, -0.5, 0.5, 2.0}) {
                assertEquals(atZero, entries.getValueSimple(point(0.0, 0.0, depth, weirdness)),
                        "depth moved the winner at weirdness " + weirdness
                                + " though no cell constrains it");
            }
        }
    }

    // ------------------------------------------------------------- the depth pair

    @Test
    void depthReadAtTheGridsHeightPicksADifferentCellFromDepthAtY64() {
        // [K7]: the grids carry depth at y=0 and the game reads block y 64.
        // At a sampled 0.4 the cave band contains the column; at the y64 value
        // the surface cell is nearer, and the whole share flips with it.
        Pair<MultiNoiseUtil.NoiseHypercube, String> cave =
                Pair.of(depthCube(0.1, 2.0, 0.0), "cave");
        Pair<MultiNoiseUtil.NoiseHypercube, String> surface =
                Pair.of(depthCube(-0.005, 0.0, 0.0), "surface");

        double sampled = 0.4;
        assertEquals("cave", winner(point(0.0, 0.0, sampled, 0.0), cave, surface));
        assertEquals("surface",
                winner(point(0.0, 0.0, sampled + DEPTH_AT_Y64, 0.0), cave, surface));
    }

    @Test
    void misreadingDepthOutweighsTheOffsetABandSweepMoves() {
        // The magnitude behind [K7], executed rather than argued. A surface cell
        // pinned at depth [-0.005, 0.000] is handed a free 0.2450 of squared
        // distance by a depth read half a unit high — several times the 0.0548
        // an offset of 0.234 costs a band. So the two reads disagree about who
        // holds the column, and a sweep cannot recover the difference.
        Pair<MultiNoiseUtil.NoiseHypercube, String> band =
                Pair.of(cube(OPEN, OPEN, OPEN, OPEN, OPEN, OPEN, 0.234), "band");
        Pair<MultiNoiseUtil.NoiseHypercube, String> surface =
                Pair.of(depthCube(-0.005, 0.0, 0.0), "surface");

        String atGridHeight = winner(point(0.0, 0.0, 0.0, 0.0), band, surface);
        String atY64 = winner(point(0.0, 0.0, DEPTH_AT_Y64, 0.0), band, surface);

        assertEquals("surface", atGridHeight);
        assertEquals("band", atY64);
        assertNotEquals(atGridHeight, atY64,
                "if these agree the fixture no longer reproduces the defect");
    }
}
