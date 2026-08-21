package com.customdimensions.facts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-worked values for the grid computations: relief, grain, shares and edge
 * density.
 *
 * <p>Every expected number here is worked out on a 3x3 layout small enough to
 * count by hand and written in the assertion, not computed by the same code
 * under test. The live parity run proves the pipeline agrees with a live
 * server; it says nothing about whether the arithmetic is the arithmetic that
 * was intended, which is what these do.
 */
class GridFactsTest {

    /**
     * A 3x3 grid with no column probed wet; nulls are cells outside the
     * playable disc. Since the water fraction is now the probed count and
     * nothing else, a fixture built this way is a DRY world whatever its
     * heights — use the three-arg form for anything asserting water.
     */
    private static FactsEngine.Grid grid(String[] biomes, Integer[] heights) {
        return grid(biomes, heights, new boolean[heights.length]);
    }

    /**
     * As above, with {@code submerged} standing in for what the live engine
     * probed at each column — {@code SpikeSampler.groundlessHoldsFluid} where
     * the column has no floor, {@code surfaceHoldsFluid} where it has one.
     * Meaningful at every column, not only the groundless ones: a floor under
     * the sea line is not necessarily under water.
     */
    private static FactsEngine.Grid grid(String[] biomes, Integer[] heights, boolean[] submerged) {
        int sampled = 0;
        for (Integer h : heights) {
            if (h != null) {
                sampled++;
            }
        }
        return new FactsEngine.Grid(biomes, heights, submerged, 3, 100, sampled);
    }

    /** The world floor these fixtures sit well above, so nothing reads as void. */
    private static final int FLOOR = -64;

    /** A dry-void generator: no floor means open sky, as in the End. */
    private static SeedFacts.TerrainFacts terrain(FactsEngine.Grid grid, Integer seaLevel) {
        return FactsEngine.terrainFacts(grid, seaLevel, false, FLOOR);
    }

    /** A flooding generator: no floor means ocean, as in an overworld preset. */
    private static SeedFacts.TerrainFacts flooded(FactsEngine.Grid grid, Integer seaLevel) {
        return FactsEngine.terrainFacts(grid, seaLevel, true, FLOOR);
    }

    /**
     * Biome facts with the mosaic reading taken over the same 3x3 layout. The
     * live engine reads it from a separate fixed-step patch around spawn; the
     * arithmetic under test is identical either way.
     */
    private static SeedFacts.BiomeFacts biomes(FactsEngine.Grid grid) {
        return FactsEngine.biomeFacts(grid, FactsEngine.edgeDensity(grid.biome(), 3));
    }

    private static final String P = "minecraft:plains";
    private static final String D = "minecraft:desert";

    // ------------------------------------------------------------------ biome

    @Test
    void sharesAndHeadlineAreCountsOverSampledCells() {
        // 6 plains, 3 desert out of 9. Shares 2/3 and 1/3, headline 2/3.
        var facts = biomes(grid(
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
        var hemispheres = biomes(grid(
                new String[] {P, P, D,
                              P, P, D,
                              P, P, D},
                new Integer[] {64, 64, 64, 64, 64, 64, 64, 64, 64}));

        // Checkerboard: every adjacent pair differs. 12/12 = 1.0.
        var mosaic = biomes(grid(
                new String[] {P, D, P,
                              D, P, D,
                              P, D, P},
                new Integer[] {64, 64, 64, 64, 64, 64, 64, 64, 64}));

        assertEquals(hemispheres.distinctCount().orThrow(),
                mosaic.distinctCount().orThrow(),
                "the count cannot tell these apart — that is the point");
        assertEquals(3.0 / 12.0, hemispheres.edgeDensityNearSpawn().orThrow(), 1e-12);
        assertEquals(1.0, mosaic.edgeDensityNearSpawn().orThrow(), 1e-12);
    }

    @Test
    void cellsOutsideTheDiscAreNotCountedAsABiome() {
        // Nulls must not become a biome, a share, or an adjacency pair. With
        // only the centre column sampled there are 2 vertical pairs and 0
        // horizontal ones, both plains, so edge density is 0 — measured, not
        // absent, because pairs did exist.
        var facts = biomes(grid(
                new String[] {null, P, null,
                              null, P, null,
                              null, P, null},
                new Integer[] {null, 64, null, null, 64, null, null, 64, null}));

        assertEquals(1, facts.distinctCount().orThrow());
        assertEquals(1.0, facts.shares().orThrow().get(P), 1e-12);
        assertEquals(0.0, facts.edgeDensityNearSpawn().orThrow(), 1e-12);
    }

    @Test
    void aGridWithNoAdjacentPairAtAllHasNoEdgeDensityRatherThanZero() {
        // A single sampled cell has no pair to compare. Zero would read as
        // "measured, and perfectly uniform", which is a different claim.
        var facts = biomes(grid(
                new String[] {null, null, null,
                              null, P, null,
                              null, null, null},
                new Integer[] {null, null, null, null, 64, null, null, null, null}));

        assertEquals(1, facts.distinctCount().orThrow());
        assertFalse(facts.edgeDensityNearSpawn().isPresent());
        assertTrue(facts.edgeDensityNearSpawn().reason().contains("adjacent"),
                facts.edgeDensityNearSpawn().reason());
    }

    // -------------------------------------------------------- tier-1 screening

    /**
     * A grid shaped exactly like what {@code sampleGrid} produces for a
     * climate-only rig: biomes populated, heights and submerged left at
     * their zero values (null / false) because there is no terrain router to
     * ask. {@code biomeFacts} must still compute real shares from it — this
     * is the whole of tier 1's fix, expressed as a fixture rather than a
     * live measurement no test here can take.
     */
    @Test
    void biomeFactsMeasuresRealSharesFromAGridWithNoHeightsAtAll() {
        var facts = biomes(grid(
                new String[] {P, P, D,
                              P, P, D,
                              P, P, D},
                new Integer[9]));   // every height null — the climate-only case

        assertEquals(2, facts.distinctCount().orThrow());
        assertEquals(6.0 / 9.0, facts.shares().orThrow().get(P), 1e-12);
        assertEquals(3.0 / 9.0, facts.shares().orThrow().get(D), 1e-12);
        assertEquals(6.0 / 9.0, facts.headlineShare().orThrow(), 1e-12,
                "a biome-only grid still answers headlineShare exactly like a full one");
    }

    @Test
    void aGridDominatedByOneBiomeYieldsAHighShareForIt() {
        var facts = biomes(grid(
                new String[] {P, P, P, P, P, P, P, P, D},
                new Integer[9]));

        assertEquals(8.0 / 9.0, facts.shares().orThrow().get(P), 1e-12);
        assertEquals(1.0 / 9.0, facts.shares().orThrow().get(D), 1e-12);
    }

    @Test
    void aGridWithNoneOfABiomeGivesItNoShareAtAll() {
        var facts = biomes(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[9]));

        assertEquals(1.0, facts.shares().orThrow().get(P), 1e-12);
        assertFalse(facts.shares().orThrow().containsKey(D), "an absent biome has no entry");
    }

    // -------------------------------------------------------- grid geometry

    /**
     * The fraction of {@code side x side} grid cells clipped to the disc that
     * fall in the "eastern" half ({@code dx >= 0}) — a shape whose true area
     * fraction is 0.5 by the disc's own symmetry, computed straight from
     * {@link FactsEngine#gridOffsets}, no {@code SpikeSampler} in sight.
     */
    private static double easternFraction(int side, int radius) {
        int[][] offsets = FactsEngine.gridOffsets(radius, side);
        int east = 0;
        int total = 0;
        for (int[] at : offsets) {
            if (at == null) {
                continue;
            }
            total++;
            if (at[0] >= 0) {
                east++;
            }
        }
        return east / (double) total;
    }

    @Test
    void screenGridAndFullGridSampleTheSameDiscJustAtDifferentDensities() {
        // FactsEngine.measureCheap (tier 1) walks the same gridOffsets
        // geometry FactsEngine.measure (tier 2) does, just at SCREEN_GRID
        // instead of GRID. Both densities put a grid point exactly on the
        // x=0 line (both sides are odd), so neither reads exactly 0.5 — the
        // tolerances below allow for that one shared column, not for the two
        // densities disagreeing about where the disc is.
        int radius = 4096;
        double full = easternFraction(FactsEngine.GRID, radius);
        double screen = easternFraction(FactsEngine.SCREEN_GRID, radius);

        assertEquals(0.5, full, 0.05,
                "GRID (~1300 cells) must land close to the true 50/50 split, got " + full);
        assertEquals(0.5, screen, 0.12,
                "SCREEN_GRID (~130 cells) is coarser but must still land close, got " + screen);
        assertEquals(full, screen, 0.15,
                "the two densities must agree with each other within sampling noise, got "
                + full + " vs " + screen);
    }

    // ---------------------------------------------------------------- terrain

    @Test
    void reliefIsTheInterquartileRangeAndGrainIsTheMeanAdjacentStep() {
        // Heights:  60 62 64
        //           60 62 64
        //           60 62 64
        // Sorted: three 60s, three 62s, three 64s (n=9). Q1 index (n-1)/4=2
        // -> 60. Q3 index 3(n-1)/4=6 -> 64. Relief (IQR) 64-60 = 4.
        // Horizontal pairs: 6, each |step| 2 -> 12. Vertical pairs: 6, each 0.
        // Grain = 12 / 12 = 1.0.
        var facts = terrain(grid(
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
    void aSingleOutlierDoesNotDominateRelief() {
        // Eight columns tightly clustered at 60-63, one wildly off at 185 —
        // the shape a ceilinged dimension takes when nearly every column
        // resolves to genuine ground but one rare column catches a shallow
        // pocket close under the roof (the_boneyard case: max pinned near
        // the roof in almost every seed while the terrain that actually
        // exists barely moves). max - min would read 125; the interquartile
        // range must describe the middle of the column, not its single
        // most extreme sample.
        var facts = terrain(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {60, 60, 61,
                               61, 62, 62,
                               63, 63, 185}), 63);

        assertEquals(2.0, facts.relief().orThrow(), 1e-12,
                "the outlier must not drag the interquartile range out to 125");
        assertEquals(60, facts.minHeight().orThrow(), "min still reports the true extreme");
        assertEquals(185, facts.maxHeight().orThrow(), "max still reports the true extreme");
    }

    @Test
    void aPlateauAndASpikeFieldShareAReliefAndDifferInGrain() {
        // Both span 60..70, so relief cannot separate them. That is exactly
        // the confusion grain exists to resolve.
        var plateau = terrain(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {60, 60, 60,
                               60, 70, 70,
                               60, 70, 70}), 63);
        var spikes = terrain(grid(
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
    void waterFractionCountsWhatWasProbed_notWhatSeaLevelImplies() {
        // Sea level 63. Heights 60,61,62 sit BELOW it and 64..69 above it, so
        // the old height <= seaLevel rule answers 3/9 whatever the columns
        // actually contain. The probe says otherwise: one of the deep three is
        // a dry basin and one of the high six is a perched pool. Both happen in
        // the pack — measured on the_catalyst_maw, 116 columns of the first
        // kind and 11 of the second.
        boolean[] probed = new boolean[] {true, true, false,
                                          false, true, false,
                                          false, false, false};
        var facts = flooded(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {60, 61, 62,
                               64, 65, 66,
                               67, 68, 69}, probed), 63);

        assertEquals(3.0 / 9.0, facts.waterFraction().orThrow(), 1e-12,
                "three columns were probed wet, and it is a coincidence of this "
                + "fixture that the sea line also answers three — the SET differs");
        // Same count, different columns: the fixture is built so a rule reading
        // heights alone cannot produce this set. Move one probe and the sea-line
        // rule cannot follow.
        boolean[] drier = new boolean[] {true, false, false,
                                         false, false, false,
                                         false, false, false};
        var drierFacts = flooded(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {60, 61, 62,
                               64, 65, 66,
                               67, 68, 69}, drier), 63);
        assertEquals(1.0 / 9.0, drierFacts.waterFraction().orThrow(), 1e-12,
                "one column probed wet, against three the sea line would claim");
    }

    @Test
    void aGeneratorWhoseDefaultFluidIsAirHasNoWaterHoweverDeepItsColumnsAre() {
        // Every column below sea level, every one probed wet — and still dry,
        // because the End's default fluid is air and floodsVoid gates the whole
        // count. Guards against a stray probe result leaking into a dry world.
        boolean[] allWet = new boolean[9];
        java.util.Arrays.fill(allWet, true);
        var facts = terrain(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {10, 11, 12,
                               13, 14, 15,
                               16, 17, 18}, allWet), 63);

        assertEquals(0.0, facts.waterFraction().orThrow(), 1e-12);
    }

    @Test
    void aGeneratorWithNoSeaLevelHasNoWaterFractionRatherThanZero() {
        var facts = terrain(grid(
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
        var facts = terrain(grid(
                new String[] {null, null, null, null, P, null, null, null, null},
                new Integer[] {null, null, null, null, 64, null, null, null, null}), 63);

        assertFalse(facts.relief().isPresent());
        assertFalse(facts.grain().isPresent());
        assertFalse(facts.minHeight().isPresent());
    }

    // ---------------------------------------------------------- ground or void

    @Test
    void aColumnAtTheWorldFloorIsVoidRatherThanTerrainAtThatHeight() {
        // Vanilla's getHeight is sampleHeightmap(...).orElse(getBottomY()), so
        // an empty column answers the floor. Read as a surface height, this
        // whole layout is a flat world at -64; read correctly it is a void.
        var facts = terrain(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {FLOOR, FLOOR, FLOOR,
                               FLOOR, FLOOR, FLOOR,
                               FLOOR, FLOOR, FLOOR}), 63);

        assertEquals(0.0, facts.groundFraction().orThrow(), 1e-12);
        assertFalse(facts.relief().isPresent(), "a void has no relief");
        assertTrue(facts.relief().reason().contains("no column"), facts.relief().reason());
        assertEquals(0.0, facts.waterFraction().orThrow(), 1e-12,
                "a DRY void is not an ocean, however far below sea level its floor sits");
    }

    @Test
    void aFloodedVoidIsAnOceanAndADryOneIsNot() {
        // The same nine floorless columns, each probed wet, and the answer is
        // opposite either way: the End's default fluid is air and its void is
        // empty however the probe reads, while an overworld-shaped preset
        // reports what its aquifer actually left there. floodsVoid is the
        // gate, submerged is the probe result — the fact must honour both,
        // not read the generator's default fluid as a verdict on its own.
        boolean[] allWet = {true, true, true, true, true, true, true, true, true};
        FactsEngine.Grid empty = grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {FLOOR, FLOOR, FLOOR,
                               FLOOR, FLOOR, FLOOR,
                               FLOOR, FLOOR, FLOOR},
                allWet);

        assertEquals(0.0, terrain(empty, 63).waterFraction().orThrow(), 1e-12,
                "a dry-void generator counts no groundless column wet, whatever a probe found");
        assertEquals(1.0, flooded(empty, 63).waterFraction().orThrow(), 1e-12);
    }

    @Test
    void aGroundlessColumnIsWetOnlyWhereItsOwnProbeFoundFluid() {
        // aquifers_enabled makes the fluid level noise-driven per region, so
        // a flooding generator does not fill every floorless column — this is
        // the defect measured on the_catalyst_maw: 348 of 893 groundless
        // columns held no fluid at all despite a flooding default. Three of
        // the nine columns here are probed wet, three dry, three carry
        // ground — the fraction must be exactly the probed count, never all
        // or none of the floorless columns.
        FactsEngine.Grid mixed = grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {FLOOR, FLOOR, 90,
                               FLOOR, FLOOR, 100,
                               FLOOR, FLOOR, 110},
                new boolean[] {true, false, false,
                               true, false, false,
                               true, false, false});

        assertEquals(3.0 / 9.0, flooded(mixed, 63).waterFraction().orThrow(), 1e-12,
                "only the three columns a probe actually found wet count as water");
    }

    @Test
    void openOceanCountsTowardHowWetAWorldIs() {
        // Water fraction is over the SAMPLED disc, not the land alone. Six
        // floorless columns around three islands, all probed wet, is a
        // mostly-water world; measured over the islands only it reads bone
        // dry, which is the reading that made a `water: sea` dimension score
        // 0.09 for being an archipelago.
        boolean[] wetAroundIslands = {true, false, true, true, false, true, true, false, true};
        FactsEngine.Grid islands = grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {FLOOR, 90, FLOOR,
                               FLOOR, 100, FLOOR,
                               FLOOR, 110, FLOOR},
                wetAroundIslands);

        assertEquals(6.0 / 9.0, flooded(islands, 63).waterFraction().orThrow(), 1e-12,
                "the six columns of open ocean are the wettest part of this world");
        assertEquals(0.0, terrain(islands, 63).waterFraction().orThrow(), 1e-12,
                "the same layout with a dry void is three islands in open sky");
    }

    @Test
    void islandsMeasureTheirOwnTerrainAndNotTheVoidAroundIt() {
        // Three island columns at 90..110 in a 3x3 of void. Counting the void
        // as ground gives relief 0 (the interquartile range of six -64s) and a
        // water fraction of 6/9 — both statements about a world that is not
        // there. The islands are what the dimension is.
        var facts = terrain(grid(
                new String[] {P, P, P, P, P, P, P, P, P},
                new Integer[] {FLOOR, 90, FLOOR,
                               FLOOR, 100, FLOOR,
                               FLOOR, 110, FLOOR}), 63);

        assertEquals(3.0 / 9.0, facts.groundFraction().orThrow(), 1e-12);
        assertEquals(90, facts.minHeight().orThrow());
        assertEquals(110, facts.maxHeight().orThrow());
        assertEquals(0.0, facts.waterFraction().orThrow(), 1e-12,
                "every column with ground is above sea level, and this void is dry");
        // The three islands are a vertical run, so the two pairs between them
        // are the only adjacencies with ground at both ends: (100-90) and
        // (110-100), mean 10.
        assertEquals(10.0, facts.grain().orThrow(), 1e-12);
    }

    @Test
    void groundFractionIsOverColumnsAttemptedNotColumnsAnswered() {
        // Four cells outside the playable disc, three of the five inside it
        // carrying ground. The denominator is what the disc asked for (5),
        // never the whole square (9) and never the columns that answered (3).
        var facts = terrain(grid(
                new String[] {null, P, null,
                              P, P, P,
                              null, P, null},
                new Integer[] {null, 70, null,
                               FLOOR, 71, FLOOR,
                               null, 72, null}), 63);

        assertEquals(3.0 / 5.0, facts.groundFraction().orThrow(), 1e-12);
    }
}
