package com.customdimensions.facts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-worked values for the grid computations: relief, grain, shares and edge
 * density (Phase 4 gate 2).
 *
 * <p>Every expected number here is worked out on a 3x3 layout small enough to
 * count by hand and written in the assertion, not computed by the same code
 * under test. The live parity run proves the pipeline agrees with a live
 * server; it says nothing about whether the arithmetic is the arithmetic that
 * was intended, which is what these do.
 */
class GridFactsTest {

    /** A 3x3 grid; nulls are cells outside the playable disc. */
    private static FactsEngine.Grid grid(String[] biomes, Integer[] heights) {
        int sampled = 0;
        for (Integer h : heights) {
            if (h != null) {
                sampled++;
            }
        }
        return new FactsEngine.Grid(biomes, heights, 3, 100, sampled, null);
    }

    private static final String P = "minecraft:plains";
    private static final String D = "minecraft:desert";

    // ------------------------------------------------------------------ biome

    @Test
    void sharesAndHeadlineAreCountsOverSampledCells() {
        // 6 plains, 3 desert out of 9. Shares 2/3 and 1/3, headline 2/3.
        var facts = FactsEngine.biomeFacts(grid(
                new String[] {P, P, D,
                              P, P, D,
                              P, P, D},
                new Integer[] {64, 64, 64, 64, 64, 64, 64, 64, 64}));

        assertEquals(2, facts.distinctCount().orThrow());
        assertEquals(6.0 / 9.0, facts.shares().orThrow().get(P), 1e-12);
        assertEquals(3.0 / 9.0, facts.shares().orThrow().get(D), 1e-12);
        assertEquals(6.0 / 9.0, facts.headlineShare().orThrow(), 1e-12);
    }

    @Test
    void aMosaicAndTwoHemispheresHaveTheSameBiomeCountAndDifferentEdgeDensity() {
        // This is the whole reason edge density is a fact. Both layouts are
        // two biomes over nine cells; only one of them is somewhere to explore.
        //
        // Adjacency pairs on a 3x3: 6 horizontal + 6 vertical = 12.
        //
        // Hemispheres (a vertical split at column 2): the only differing pairs
        // are the 3 horizontal ones spanning the seam. 3/12 = 0.25.
        var hemispheres = FactsEngine.biomeFacts(grid(
                new String[] {P, P, D,
                              P, P, D,
                              P, P, D},
                new Integer[] {64, 64, 64, 64, 64, 64, 64, 64, 64}));

        // Checkerboard: every adjacent pair differs. 12/12 = 1.0.
        var mosaic = FactsEngine.biomeFacts(grid(
                new String[] {P, D, P,
                              D, P, D,
                              P, D, P},
                new Integer[] {64, 64, 64, 64, 64, 64, 64, 64, 64}));

        assertEquals(hemispheres.distinctCount().orThrow(),
                mosaic.distinctCount().orThrow(),
                "the count cannot tell these apart — that is the point");
        assertEquals(3.0 / 12.0, hemispheres.edgeDensity().orThrow(), 1e-12);
        assertEquals(1.0, mosaic.edgeDensity().orThrow(), 1e-12);
    }

    @Test
    void cellsOutsideTheDiscAreNotCountedAsABiome() {
        // Nulls must not become a biome, a share, or an adjacency pair. With
        // only the centre column sampled there are 2 vertical pairs and 0
        // horizontal ones, both plains, so edge density is 0 — measured, not
        // absent, because pairs did exist.
        var facts = FactsEngine.biomeFacts(grid(
                new String[] {null, P, null,
                              null, P, null,
                              null, P, null},
                new Integer[] {null, 64, null, null, 64, null, null, 64, null}));

        assertEquals(1, facts.distinctCount().orThrow());
        assertEquals(1.0, facts.shares().orThrow().get(P), 1e-12);
        assertEquals(0.0, facts.edgeDensity().orThrow(), 1e-12);
    }

    @Test
    void aGridWithNoAdjacentPairAtAllHasNoEdgeDensityRatherThanZero() {
        // A single sampled cell has no pair to compare. Zero would read as
        // "measured, and perfectly uniform", which is a different claim.
        var facts = FactsEngine.biomeFacts(grid(
                new String[] {null, null, null,
                              null, P, null,
                              null, null, null},
                new Integer[] {null, null, null, null, 64, null, null, null, null}));

        assertEquals(1, facts.distinctCount().orThrow());
        assertFalse(facts.edgeDensity().isPresent());
        assertTrue(facts.edgeDensity().reason().contains("adjacent"),
                facts.edgeDensity().reason());
    }

    // ---------------------------------------------------------------- terrain

    @Test
    void reliefIsMaxMinusMinAndGrainIsTheMeanAdjacentStep() {
        // Heights:  60 62 64
        //           60 62 64
        //           60 62 64
        // Relief 64-60 = 4.
        // Horizontal pairs: 6, each |step| 2 -> 12. Vertical pairs: 6, each 0.
        // Grain = 12 / 12 = 1.0.
        var facts = FactsEngine.terrainFacts(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {60, 62, 64,
                               60, 62, 64,
                               60, 62, 64}), 63);

        assertEquals(4.0, facts.relief().orThrow(), 1e-12);
        assertEquals(1.0, facts.grain().orThrow(), 1e-12);
        assertEquals(60, facts.minHeight().orThrow());
        assertEquals(64, facts.maxHeight().orThrow());
    }

    @Test
    void aPlateauAndASpikeFieldShareAReliefAndDifferInGrain() {
        // Both span 60..70, so relief cannot separate them. That is exactly
        // the confusion grain exists to resolve.
        var plateau = FactsEngine.terrainFacts(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {60, 60, 60,
                               60, 70, 70,
                               60, 70, 70}), 63);
        var spikes = FactsEngine.terrainFacts(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {60, 70, 60,
                               70, 60, 70,
                               60, 70, 60}), 63);

        assertEquals(plateau.relief().orThrow(), spikes.relief().orThrow(), 1e-12);
        // Plateau: 12 pairs, 4 of them cross the step (|10| each) -> 40/12.
        assertEquals(40.0 / 12.0, plateau.grain().orThrow(), 1e-12);
        // Spikes: every one of the 12 pairs steps 10 -> 120/12 = 10.
        assertEquals(10.0, spikes.grain().orThrow(), 1e-12);
        assertTrue(spikes.grain().orThrow() > plateau.grain().orThrow());
    }

    @Test
    void waterFractionCountsColumnsAtOrBelowSeaLevel() {
        // Sea level 63; heights 60,61,62 are at or below it, 64..69 are not.
        var facts = FactsEngine.terrainFacts(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {60, 61, 62,
                               64, 65, 66,
                               67, 68, 69}), 63);

        assertEquals(3.0 / 9.0, facts.waterFraction().orThrow(), 1e-12);
    }

    @Test
    void aGeneratorWithNoSeaLevelHasNoWaterFractionRatherThanZero() {
        var facts = FactsEngine.terrainFacts(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {60, 60, 60, 60, 60, 60, 60, 60, 60}), null);

        assertFalse(facts.waterFraction().isPresent());
        assertTrue(facts.waterFraction().reason().contains("sea level"),
                facts.waterFraction().reason());
        // The heights are still real, so relief and grain are still measured.
        assertEquals(0.0, facts.relief().orThrow(), 1e-12);
        assertEquals(0.0, facts.grain().orThrow(), 1e-12);
    }

    @Test
    void oneColumnIsNotEnoughToHaveTerrainAtAll() {
        // A single height gives no relief and no grain. Zero would claim a
        // perfectly flat world was measured.
        var facts = FactsEngine.terrainFacts(grid(
                new String[] {null, null, null, null, P, null, null, null, null},
                new Integer[] {null, null, null, null, 64, null, null, null, null}), 63);

        assertFalse(facts.relief().isPresent());
        assertFalse(facts.grain().isPresent());
        assertFalse(facts.minHeight().isPresent());
    }
}
