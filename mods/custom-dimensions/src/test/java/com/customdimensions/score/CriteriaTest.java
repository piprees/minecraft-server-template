package com.customdimensions.score;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.Measured;
import com.customdimensions.facts.SeedFacts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every criterion gets a passing case, a failing case and an absent-input case.
 * These tests are where what a criterion means is stated: a disagreement about
 * scoring is a disagreement about one function and one set of assertions.
 */
class CriteriaTest {

    // ------------------------------------------------------------- fixtures

    private static <T> Measured<T> gone() {
        return Measured.absent("not measured in this fixture");
    }

    private static SeedFacts facts(SeedFacts.SpawnFacts spawn, SeedFacts.BiomeFacts biomes,
                                   SeedFacts.TerrainFacts terrain,
                                   SeedFacts.StructureFacts structures, int radius) {
        // Full coverage by default, same reasoning as terrain()'s water/height
        // defaults: every other criterion's fixture should stay unaffected by
        // playable_ground_covers_the_disc unless a test is explicitly about it.
        return facts(spawn, biomes, terrain, structures, radius, Measured.of(grid(1000, 1000)));
    }

    private static SeedFacts facts(SeedFacts.SpawnFacts spawn, SeedFacts.BiomeFacts biomes,
                                   SeedFacts.TerrainFacts terrain,
                                   SeedFacts.StructureFacts structures, int radius,
                                   Measured<SeedFacts.Grid> grid) {
        return new SeedFacts("test", "adventure:test", 1L, "now", "fp", radius,
                spawn, biomes, terrain, structures, grid);
    }

    private static SeedFacts.SpawnFacts spawn(String biome, Double localRelief) {
        // Solid ground on all nine probed columns by default, same reasoning
        // as terrain()'s water/height defaults: every other criterion's
        // fixture should stay unaffected by spawn_is_safe_to_build_on unless
        // a test is explicitly about it.
        return spawn(biome, localRelief, List.of(SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                SeedFacts.GroundKind.SOLID));
    }

    private static SeedFacts.SpawnFacts spawn(String biome, Double localRelief,
                                              List<SeedFacts.GroundKind> nearbyGround) {
        return new SeedFacts.SpawnFacts(
                Measured.of(new SeedFacts.Column(0, 0, false)),
                biome == null ? gone() : Measured.of(biome),
                Measured.of(64),
                localRelief == null ? gone() : Measured.of(localRelief),
                Measured.of(true),
                nearbyGround == null ? gone() : Measured.of(nearbyGround));
    }

    /** A grid fact carrying only what the coverage criterion reads. */
    private static SeedFacts.Grid grid(int sampled, int heightMeasured) {
        return new SeedFacts.Grid(41, List.of(), List.of(), List.of(), sampled, heightMeasured);
    }

    private static SeedFacts.BiomeFacts biomes(Integer distinct, Double headline, Double edges) {
        return new SeedFacts.BiomeFacts(
                Measured.of(Map.of("minecraft:plains", 1.0)),
                distinct == null ? gone() : Measured.of(distinct),
                headline == null ? gone() : Measured.of(headline),
                edges == null ? gone() : Measured.of(edges));
    }

    private static SeedFacts.TerrainFacts terrain(Double relief) {
        return terrain(relief, 0.1, 0, 100);
    }

    private static SeedFacts.TerrainFacts terrain(Double relief, Double water,
                                                   Integer minHeight, Integer maxHeight) {
        return new SeedFacts.TerrainFacts(
                relief == null ? gone() : Measured.of(relief),
                Measured.of(2.0),
                water == null ? gone() : Measured.of(water),
                minHeight == null ? gone() : Measured.of(minHeight),
                maxHeight == null ? gone() : Measured.of(maxHeight));
    }

    /** One group of ten placements at the given spacing — the simple case. */
    private static SeedFacts.StructureFacts structures(Double clustering, Double nearestHostile) {
        return structures(clustering == null ? null : Map.of("deco", clustering),
                Map.of("deco", 10), nearestHostile);
    }

    private static SeedFacts.StructureFacts structures(Map<String, Double> spacingByGroup,
                                                       Map<String, Integer> countByGroup,
                                                       Double nearestHostile) {
        int total = countByGroup.values().stream().mapToInt(Integer::intValue).sum();
        return new SeedFacts.StructureFacts(
                Measured.of(Map.of()),
                Measured.of(countByGroup),
                Measured.of(Map.of()),
                Measured.of(Map.of()),
                spacingByGroup == null ? gone() : Measured.of(spacingByGroup),
                spacingByGroup == null ? gone()
                        : Measured.of(spacingByGroup.values().stream()
                                .mapToDouble(Double::doubleValue).average().orElse(1.0)),
                nearestHostile == null ? gone() : Measured.of(nearestHostile),
                Measured.of(total));
    }

    private static DimensionConfig config(List<String> spawnFilter, String terrainWord,
                                          List<String> biomeList) {
        DimensionConfig def = new DimensionConfig();
        DimensionConfig.SeedRoll sr = new DimensionConfig.SeedRoll();
        sr.spawnFilter = spawnFilter;
        sr.terrain = terrainWord;
        def.setSeedRoll(sr);
        if (biomeList != null) {
            java.util.List<com.google.gson.JsonElement> raw = new java.util.ArrayList<>();
            for (String b : biomeList) {
                raw.add(new com.google.gson.JsonPrimitive(b));
            }
            def.setBiomes(raw);
        }
        return def;
    }

    private static SeedFacts full(String spawnBiome, Double localRelief, Integer distinct,
                                  Double headline, Double edges, Double relief,
                                  Double clustering, Double nearestHostile, int radius) {
        return facts(spawn(spawnBiome, localRelief), biomes(distinct, headline, edges),
                terrain(relief), structures(clustering, nearestHostile), radius);
    }

    /** A structures fact with an explicit pool and nearestByStructure map, for the reachability gates. */
    private static SeedFacts.StructureFacts structuresWithPoolAndNearest(
            Map<String, Integer> pool, Map<String, Double> nearestByStructure) {
        return new SeedFacts.StructureFacts(
                Measured.of(pool),
                Measured.of(Map.of("deco", 10)),
                Measured.of(Map.of()),
                Measured.of(nearestByStructure),
                Measured.of(Map.of("deco", 1.0)),
                Measured.of(1.0),
                Measured.of(900.0),
                Measured.of(10));
    }

    private static SeedFacts withStructures(SeedFacts.StructureFacts structures, int radius) {
        return facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain(30.0), structures, radius);
    }

    private static SeedFacts withTerrain(SeedFacts.TerrainFacts terrain, int radius) {
        return facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain, structures(0.6, 900.0), radius);
    }

    private static SeedFacts withGrid(Measured<SeedFacts.Grid> grid, int radius) {
        return facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain(30.0),
                structures(0.6, 900.0), radius, grid);
    }

    private static SeedFacts withNearbyGround(List<SeedFacts.GroundKind> ground, int radius) {
        return facts(spawn("b", 4.0, ground), biomes(5, 0.4, 0.3), terrain(30.0),
                structures(0.6, 900.0), radius);
    }

    // --------------------------------------------------- spawn reads as name

    @Test
    void spawnNamesakeIsAGateNotAScore() {
        var c = new Criteria.SpawnReadsAsNamesake();
        DimensionConfig def = config(List.of("minecraft:snowy_plains"), null, null);

        assertTrue(c.gate(), "namesake must cost no weight");
        assertTrue(c.applicable(def));
        assertInstanceOf(Criterion.Result.Pass.class, c.evaluate(
                full("minecraft:snowy_plains", 4.0, 5, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), def));
        assertInstanceOf(Criterion.Result.Fail.class, c.evaluate(
                full("minecraft:desert", 4.0, 5, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), def));
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                full(null, 4.0, 5, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), def));
        // No spawn filter configured is NOT a failure — nothing was asked, and
        // that is decided from config alone so the ceiling never moves.
        assertFalse(c.applicable(config(null, null, null)));
    }

    // ------------------------------------------------------- headline share

    @Test
    void headlineShareFailsAtBothEndsNotJustOne() {
        var c = new Criteria.HeadlineBiomeDominatesAppropriately();
        DimensionConfig def = config(null, null, null);

        double inBand = score(c.evaluate(full("b", 4.0, 5, 0.40, 0.3, 30.0, 0.6, 900.0, 4096), def));
        double mush = score(c.evaluate(full("b", 4.0, 5, 0.12, 0.3, 30.0, 0.6, 900.0, 4096), def));
        double monoculture =
                score(c.evaluate(full("b", 4.0, 5, 0.95, 0.3, 30.0, 0.6, 900.0, 4096), def));

        assertEquals(1.0, inBand, 1e-9);
        assertTrue(mush < 0.5, "a mosaic with no identity scored " + mush);
        assertTrue(monoculture < 0.5, "a single-biome world scored " + monoculture);
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                full("b", 4.0, 5, null, 0.3, 30.0, 0.6, 900.0, 4096), def));
    }

    // -------------------------------------------------------------- variety

    @Test
    void varietyTargetScalesToWhatTheConfigPermits() {
        var c = new Criteria.BiomeVarietyPresent();
        // A dimension listing two biomes cannot produce four and must not be
        // marked down for obeying its own config.
        DimensionConfig two = config(null, null, List.of("a", "b"));
        assertEquals(1.0, score(c.evaluate(
                full("b", 4.0, 2, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), two)), 1e-9);

        // Eight listed, two delivered: a quarter of the palette the author
        // chose. A target capped at four would have called this perfect.
        DimensionConfig many = config(null, null,
                List.of("a", "b", "c", "d", "e", "f", "g", "h"));
        assertEquals(0.25, score(c.evaluate(
                full("b", 4.0, 2, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), many)), 1e-9);
        assertEquals(1.0, score(c.evaluate(
                full("b", 4.0, 8, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), many)), 1e-9);

        // A single-biome dimension is not asked the question at all, decided
        // from config with no facts in sight.
        assertFalse(c.applicable(config(null, null, List.of("only"))));
        assertTrue(c.applicable(many));
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                full("b", 4.0, null, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), many));
    }

    // ----------------------------------------------------------- biome edges

    @Test
    void edgeDensityRewardsAMosaicOverTwoHemispheres() {
        var c = new Criteria.BiomeEdgesNearSpawn();
        DimensionConfig def = config(null, null, null);
        assertEquals(1.0, score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.50, 30.0, 0.6, 900.0, 4096), def)), 1e-9);
        // Two hemispheres: five biomes, almost no edges. A biome COUNT cannot
        // tell this apart from a mosaic, which is why edge density is a fact.
        assertTrue(score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.02, 30.0, 0.6, 900.0, 4096), def)) < 0.2);
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                full("b", 4.0, 5, 0.4, null, 30.0, 0.6, 900.0, 4096), def));
    }

    // -------------------------------------------------------- clustering

    @Test
    void evenlySpreadStructuresScoreZeroAndPocketsScoreHigh() {
        var c = new Criteria.StructuresFormPlacesNotNoise();
        DimensionConfig def = config(null, null, null);
        // A group on an even profile cannot fall below 1.0 — Poisson-disc
        // placement enforces a minimum separation — so its scale runs from 1.0
        // (as loose as its spacing allows) down to a perfect lattice. A pass/fail
        // at 1.0 scores every such group zero and ranks nothing.
        double loose = score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, 1.02, 900.0, 4096), def));
        double rigid = score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, 1.31, 900.0, 4096), def));
        assertTrue(loose > rigid,
                "loose " + loose + " must beat rigid " + rigid);
        assertTrue(loose > 0.9 && rigid > 0.0 && rigid < 0.8,
                "the reachable range must span usable scores, got "
                + loose + " and " + rigid);
        assertEquals(1.0, score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, 0.5, 900.0, 4096), def)), 1e-9,
                "genuine pockets are still the best possible answer");
        assertEquals(0.0, score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, Criteria.StructuresFormPlacesNotNoise.LATTICE,
                        900.0, 4096), def)), 1e-9,
                "a perfect lattice is maximum dispersion and scores zero");
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, null, 900.0, 4096), def));
        assertFalse(c.applicable(noStructures()),
                "a dimension with structures switched off is not asked about them");
    }

    @Test
    void aPocketedGroupIsNotHiddenBySixDispersedOnes() {
        // The defect the per-group fact exists to fix. `dungeons` sits in
        // pockets; six other groups are evenly spread. Judged on the pooled
        // statistic — one number over every group's placements at once — the
        // pocket is invisible, which is why the measurement never fell below 1
        // across the whole bank. Judged per group, weighted by placements, the
        // pocketed group is worth what it holds.
        var c = new Criteria.StructuresFormPlacesNotNoise();
        DimensionConfig def = withClusterGroup("dungeons");
        assertEquals(List.of("dungeons"),
                Criteria.StructuresFormPlacesNotNoise.clusterGroups(def),
                "the fixture must actually put dungeons on the cluster profile, or "
                + "this test proves nothing about the cluster branch");

        Map<String, Integer> counts = Map.of("dungeons", 40, "deco", 60);
        SeedFacts pocketed = facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain(30.0),
                structures(Map.of("dungeons", 0.45, "deco", 1.05), counts, 900.0), 4096);
        SeedFacts flat = facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain(30.0),
                structures(Map.of("dungeons", 1.05, "deco", 1.05), counts, 900.0), 4096);

        double withPocket = score(c.evaluate(pocketed, def));
        double withoutPocket = score(c.evaluate(flat, def));
        assertTrue(withPocket > withoutPocket,
                "a dimension whose cluster group pocketed (" + withPocket + ") must beat "
                + "one whose cluster group did not (" + withoutPocket + ")");
        // 40 of 100 placements score 1.0; the other 60 score the even-profile
        // value for 1.05. Hand-worked rather than read off the implementation.
        double evenAt105 = (Criteria.StructuresFormPlacesNotNoise.LATTICE - 1.05)
                / (Criteria.StructuresFormPlacesNotNoise.LATTICE - 1.0);
        assertEquals((40 * 1.0 + 60 * evenAt105) / 100.0, withPocket, 1e-9);
    }

    @Test
    void aClusterGroupThatFailedToPocketIsMarkedDownWhereAnEvenGroupIsNot() {
        // The two branches must actually differ, or naming a profile means
        // nothing. Same measured spacing, two configs: on `cluster` it is a
        // failure to deliver what was asked for, on an even profile it is the
        // best the mechanism can do.
        var c = new Criteria.StructuresFormPlacesNotNoise();
        Map<String, Double> spacing = Map.of("dungeons", 1.0);
        Map<String, Integer> counts = Map.of("dungeons", 25);
        SeedFacts f = facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain(30.0),
                structures(spacing, counts, 900.0), 4096);

        double asCluster = score(c.evaluate(f, withClusterGroup("dungeons")));
        double asEven = score(c.evaluate(f, config(null, null, null)));
        assertEquals(0.0, asCluster, 1e-9,
                "a cluster group at a random scatter delivered none of what it asked for");
        assertEquals(1.0, asEven, 1e-9,
                "an even group at 1.0 is as loose as its spacing permits");
    }

    @Test
    void atwoPlacementGroupCannotSwingTheAnswer() {
        // Clark-Evans over two points is mostly noise. Weighting by placements
        // is what stops the noisiest group deciding the score — a plain
        // best-of-groups reading would systematically pick it.
        var c = new Criteria.StructuresFormPlacesNotNoise();
        DimensionConfig def = config(null, null, null);
        SeedFacts f = facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain(30.0),
                structures(Map.of("loot", 1.0, "deco", 1.6),
                        Map.of("loot", 2, "deco", 500), 900.0), 4096);
        double v = score(c.evaluate(f, def));
        double decoAlone = score(c.evaluate(
                facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain(30.0),
                        structures(Map.of("deco", 1.6), Map.of("deco", 500), 900.0), 4096),
                def));
        assertTrue(Math.abs(v - decoAlone) < 0.01,
                "two placements moved the answer by " + Math.abs(v - decoAlone));
    }

    // ------------------------------------------------- first encounter band

    @Test
    void firstEncounterIsJudgedAsAFractionOfTheBorderNotInBlocks() {
        var c = new Criteria.FirstEncounterDistance();
        DimensionConfig def = config(null, null, null);
        // 300 blocks is next door in an 8192 world and a long walk in a 512 one.
        assertTrue(score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, 0.6, 300.0, 8192), def)) < 1.0);
        assertEquals(1.0, score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, 0.6, 300.0, 2048), def)), 1e-9);
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, 0.6, null, 4096), def));
        assertFalse(c.applicable(noStructures()));
    }

    @Test
    void theLongWalkBranchStillRanksInsteadOfCollapsingToZero() {
        // The far branch must decay smoothly with no cliff at the band edge —
        // a ramp pointed the wrong way would flatten every seed past 30% of
        // the border to an identical 0.0, ranking 70 percentage points of the
        // axis as nothing. Both properties are asserted here.
        var c = new Criteria.FirstEncounterDistance();
        DimensionConfig def = config(null, null, null);
        double atEdge = score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, 0.6, 3000.0, 10000), def));
        double midway = score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, 0.6, 6500.0, 10000), def));
        double atBorder = score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 30.0, 0.6, 9900.0, 10000), def));

        assertEquals(1.0, atEdge, 1e-9, "the band edge must not be a cliff");
        assertTrue(midway > atBorder,
                "halfway out scored " + midway + ", at the border " + atBorder);
        assertTrue(midway < 1.0 && atBorder < midway,
                "the far tail must decay monotonically, got " + midway + " then " + atBorder);
    }

    // ---------------------------------------------------------- terrain word

    @Test
    void terrainIsJudgedAgainstTheWordTheConfigUsed() {
        var c = new Criteria.TerrainMatchesPreset();
        DimensionConfig mountains = config(null, "mountainous", null);
        assertEquals(1.0, score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 120.0, 0.6, 900.0, 4096), mountains)), 1e-9);
        assertTrue(score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 5.0, 0.6, 900.0, 4096), mountains)) < 0.5,
                "a flat world claiming to be mountainous should score badly");
        // No word configured, and an unknown word, are both "not asked" — and
        // both are decided from config, so neither depends on the seed.
        assertFalse(c.applicable(config(null, null, null)));
        assertFalse(c.applicable(config(null, "lumpy", null)));
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, null, 0.6, 900.0, 4096), mountains));
    }

    // ---------------------------------------------------------- water intent

    @Test
    void waterFractionIsJudgedAgainstTheWordTheConfigUsed() {
        var c = new Criteria.WaterMatchesIntent();
        DimensionConfig sea = config(null, null, null);
        sea.getSeedRoll().water = "sea";

        assertTrue(c.applicable(sea));
        assertEquals(1.0, score(c.evaluate(
                withTerrain(terrain(30.0, 0.7, 0, 100), 4096), sea)), 1e-9);
        assertTrue(score(c.evaluate(
                withTerrain(terrain(30.0, 0.05, 0, 100), 4096), sea)) < 0.5,
                "a near-dry world claiming to be a sea dimension should score badly");

        // No water preference configured, and an unrecognised word, are both
        // "not asked" — decided from config alone, with no facts in sight.
        assertFalse(c.applicable(config(null, null, null)));
        DimensionConfig unknown = config(null, null, null);
        unknown.getSeedRoll().water = "brackish";
        assertFalse(c.applicable(unknown));

        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                withTerrain(terrain(30.0, null, 0, 100), 4096), sea));
    }

    // ---------------------------------------------------- height range intent

    @Test
    void heightRangeIsScoredAsOverlapWithTheConfiguredSpan() {
        var c = new Criteria.HeightRangeMatchesIntent();
        DimensionConfig ranged = config(null, null, null);
        ranged.getSeedRoll().heightRange = new int[] {-64, 320};

        assertTrue(c.applicable(ranged));
        assertEquals(1.0, score(c.evaluate(
                withTerrain(terrain(30.0, 0.1, -64, 320), 4096), ranged)), 1e-9,
                "a measured span exactly matching the configured envelope is full overlap");
        assertEquals(0.0, score(c.evaluate(
                withTerrain(terrain(30.0, 0.1, 400, 500), 4096), ranged)), 1e-9,
                "a measured span entirely outside the configured envelope has no overlap");

        // No height range configured, and a zero-span one, are both "not
        // asked" — decided from config alone.
        assertFalse(c.applicable(config(null, null, null)));
        DimensionConfig degenerate = config(null, null, null);
        degenerate.getSeedRoll().heightRange = new int[] {100, 100};
        assertFalse(c.applicable(degenerate), "a zero-span heightRange states no real intent");

        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                withTerrain(terrain(30.0, 0.1, null, 320), 4096), ranged));
    }

    // --------------------------------------------------------- disc coverage

    @Test
    void groundCoverageIsScoredAsTheFractionOfSampledColumnsWithAFloor() {
        var c = new Criteria.PlayableGroundCoversTheDisc();
        DimensionConfig def = config(null, null, null);

        assertTrue(c.applicable(def));
        assertEquals(1.0, score(c.evaluate(
                withGrid(Measured.of(grid(1000, 1000)), 4096), def)), 1e-9,
                "every sampled column resolved a floor");
        assertEquals(0.5, score(c.evaluate(
                withGrid(Measured.of(grid(1000, 500)), 4096), def)), 1e-9,
                "half the sampled columns came back with no floor at all");

        // A dimension whose own terrain word already says the floor is
        // deliberately sparse is not asked the question at all.
        assertFalse(c.applicable(config(null, "void", null)),
                "a void dimension is supposed to have gaps");
        assertFalse(c.applicable(config(null, "islands", null)),
                "a field of floating islands is supposed to have gaps");

        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                withGrid(gone(), 4096), def));
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                withGrid(Measured.of(grid(0, 0)), 4096), def),
                "zero attempted columns is a measurement failure, not a coverage of zero");
    }

    // ------------------------------------------------- progression floor

    @Test
    void reachabilityGatesApplyOnlyToTheLiteralBaseWorld() {
        var fortress = new Criteria.FortressReachableInNether();
        var endCity = new Criteria.EndCityReachableInEnd();

        DimensionConfig nether = new DimensionConfig();
        nether.setName("the_nether");
        DimensionConfig end = new DimensionConfig();
        end.setName("the_end");
        DimensionConfig customNetherPocket = new DimensionConfig();
        customNetherPocket.setName("the_blackstone_keep");

        assertTrue(fortress.gate());
        assertTrue(endCity.gate());
        assertTrue(fortress.applicable(nether));
        assertFalse(fortress.applicable(end));
        assertFalse(fortress.applicable(customNetherPocket),
                "a custom nether-flavoured pocket is optional content, not the progression path");
        assertTrue(endCity.applicable(end));
        assertFalse(endCity.applicable(nether));
    }

    @Test
    void fortressWithinTheFloorPassesBeyondItFails() {
        var c = new Criteria.FortressReachableInNether();
        DimensionConfig nether = new DimensionConfig();
        nether.setName("the_nether");

        SeedFacts close = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:fortress", 100), Map.of("minecraft:fortress", 400.0)), 1024);
        SeedFacts far = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:fortress", 100), Map.of("minecraft:fortress", 4000.0)), 1024);

        assertInstanceOf(Criterion.Result.Pass.class, c.evaluate(close, nether));
        Criterion.Result.Fail fail =
                assertInstanceOf(Criterion.Result.Fail.class, c.evaluate(far, nether));
        assertTrue(fail.reason().contains("4000"), fail.reason());
    }

    @Test
    void aFortressAbsentFromThePoolIsUnmeasuredNotFailed() {
        // The whole point of exact-or-absent: a dimension whose pool never
        // included a fortress at all is not a bad seed, it is a question
        // that does not apply to this dimension's config.
        var c = new Criteria.FortressReachableInNether();
        DimensionConfig nether = new DimensionConfig();
        nether.setName("the_nether");
        SeedFacts noFortressInPool = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:nether_bridge", 50), Map.of()), 1024);
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(noFortressInPool, nether));
    }

    @Test
    void aFortressInThePoolButNotPlacedThisSeedIsUnmeasuredNotFailed() {
        // In the pool (could spawn here) but this seed's own placement left
        // no measured distance: an absent measurement, not evidence the seed
        // is bad — the gate must not guess "absent means unreachable".
        var c = new Criteria.FortressReachableInNether();
        DimensionConfig nether = new DimensionConfig();
        nether.setName("the_nether");
        SeedFacts unplaced = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:fortress", 100), Map.of()), 1024);
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(unplaced, nether));
    }

    @Test
    void endCityFloorMirrorsTheFortressGate() {
        var c = new Criteria.EndCityReachableInEnd();
        DimensionConfig end = new DimensionConfig();
        end.setName("the_end");
        SeedFacts close = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:end_city", 80), Map.of("minecraft:end_city", 1500.0)), 8192);
        SeedFacts far = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:end_city", 80), Map.of("minecraft:end_city", 3000.0)), 8192);
        assertInstanceOf(Criterion.Result.Pass.class, c.evaluate(close, end));
        assertInstanceOf(Criterion.Result.Fail.class, c.evaluate(far, end));
    }

    @Test
    void reachabilityGatesCostNoCeilingWeight() {
        DimensionConfig nether = new DimensionConfig();
        nether.setName("the_nether");
        DimensionConfig overworldLike = new DimensionConfig();
        overworldLike.setName("overworld");
        assertEquals(Scorer.ceiling(overworldLike, Criteria.all()),
                Scorer.ceiling(nether, Criteria.all()), 1e-9,
                "a gate must never appear in the ceiling regardless of which dimension it targets");
    }

    @Test
    void aFailedReachabilityGateRejectsTheWholeSeed() {
        DimensionConfig nether = new DimensionConfig();
        nether.setName("the_nether");
        SeedFacts far = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:fortress", 100), Map.of("minecraft:fortress", 4000.0)), 1024);
        Scorecard card = Scorer.score(far, nether, Criteria.all());
        assertEquals(Scorecard.Verdict.REJECTED, card.verdict());
        assertTrue(card.verdictReason().startsWith("fortress_reachable_in_nether"),
                card.verdictReason());
    }

    // ------------------------------------------------------------ lethality

    @Test
    void aSheerDropAtSpawnIsAGateFailureNotALowScore() {
        var c = new Criteria.NothingIsImmediatelyLethal();
        DimensionConfig def = config(null, null, null);
        assertTrue(c.gate());
        assertInstanceOf(Criterion.Result.Pass.class, c.evaluate(
                full("b", 8.0, 5, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), def));
        assertInstanceOf(Criterion.Result.Fail.class, c.evaluate(
                full("b", 80.0, 5, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), def));
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                full("b", null, 5, 0.4, 0.3, 30.0, 0.6, 900.0, 4096), def));
    }

    @Test
    void spawnSafetyGatesOnHazardousFluidOrAMinorityOfSolidGround() {
        var c = new Criteria.SpawnIsSafeToBuildOn();
        DimensionConfig def = config(null, null, null);
        assertTrue(c.gate());
        // No config field states relevance for this one — it is a universal
        // invariant, same register as NothingIsImmediatelyLethal — so it is
        // always applicable rather than conditioned on an intent nobody sets.
        assertTrue(c.applicable(def));

        assertInstanceOf(Criterion.Result.Pass.class, c.evaluate(withNearbyGround(
                List.of(SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                        SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                        SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.OPEN_WATER,
                        SeedFacts.GroundKind.OPEN_WATER, SeedFacts.GroundKind.OPEN_WATER,
                        SeedFacts.GroundKind.OPEN_WATER), 4096), def),
                "five of nine solid is at least half, and nothing is hazardous");

        Criterion.Result.Fail lava = assertInstanceOf(Criterion.Result.Fail.class, c.evaluate(
                withNearbyGround(List.of(SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                        SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                        SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                        SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                        SeedFacts.GroundKind.HAZARDOUS_FLUID), 4096), def),
                "one hazardous column among eight solid ones is not a majority vote");
        assertTrue(lava.reason().contains("hazardous"), lava.reason());

        Criterion.Result.Fail drowning = assertInstanceOf(Criterion.Result.Fail.class, c.evaluate(
                withNearbyGround(List.of(SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                        SeedFacts.GroundKind.SOLID, SeedFacts.GroundKind.SOLID,
                        SeedFacts.GroundKind.OPEN_WATER, SeedFacts.GroundKind.OPEN_WATER,
                        SeedFacts.GroundKind.OPEN_WATER, SeedFacts.GroundKind.OPEN_WATER,
                        SeedFacts.GroundKind.OPEN_WATER), 4096), def),
                "four of nine solid is under half — no reliable nearby platform, but nothing lethal");
        assertTrue(drowning.reason().contains("solid"), drowning.reason());

        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                withNearbyGround(null, 4096), def));
    }

    // ---------------------------------------------------------------- scorer

    @Test
    void aFailedGateRejectsTheSeedRatherThanDeductingFromIt() {
        // The weighted mean's central error: a deficiency purchasable with a
        // surplus elsewhere. A gate is not for sale.
        DimensionConfig def = config(List.of("minecraft:snowy_plains"), "rolling", null);
        Scorecard card = Scorer.score(
                full("minecraft:desert", 4.0, 5, 0.4, 0.35, 30.0, 0.5, 900.0, 4096),
                def, Criteria.all());
        assertEquals(Scorecard.Verdict.REJECTED, card.verdict());
        assertTrue(card.verdictReason().startsWith("spawn_reads_as_namesake"),
                card.verdictReason());
    }

    @Test
    void inapplicableCriteriaLeaveTheCeilingAloneSoNothingIsMarkedDownUnasked() {
        DimensionConfig def = config(null, null, null);
        // Terrain word and spawn filter are unset, so those two are not asked.
        Scorecard card = Scorer.score(
                full("b", 4.0, 5, 0.40, 0.50, 30.0, 0.5, 300.0, 2048), def, Criteria.all());
        assertEquals(Scorecard.Verdict.SCORED, card.verdict());
        long notAsked = card.entries().stream()
                .filter(e -> e.outcome().equals("not_applicable")).count();
        assertTrue(notAsked >= 2, "expected the unconfigured criteria to be skipped");
        // Every graded criterion is perfect here, so the percentage is 100 —
        // which must be REACHABLE.
        assertEquals(100.0, card.percentage(), 1e-9);
    }

    @Test
    void aSeedNothingCouldBeMeasuredOnIsNotAZeroPercentSeed() {
        // A 0% would rank this below a genuinely poor seed that WAS measured,
        // which is a lie about which of the two is worse.
        DimensionConfig def = config(null, null, null);
        Scorecard card = Scorer.score(nothingMeasured(), def, Criteria.all());
        assertEquals(Scorecard.Verdict.INVALID_CONFIG, card.verdict());
        assertEquals(null, card.percentage(),
                "an unscoreable seed has no percentage, not a zero one");
    }

    // ------------------------------------------------------- config ceiling

    @Test
    void theCeilingComesFromConfigAloneAndIsTheSameForEverySeed() {
        DimensionConfig def = config(List.of("minecraft:snowy_plains"), "rolling",
                List.of("a", "b", "c"));
        double fromConfig = Scorer.ceiling(def, Criteria.all());

        // Three seeds that differ in every fact, including one where nothing
        // was measurable at all. Same denominator or the percentages cannot be
        // compared, which is the entire point of ranking seeds.
        Scorecard good = Scorer.score(
                full("minecraft:snowy_plains", 4.0, 5, 0.40, 0.50, 30.0, 0.5, 300.0, 2048),
                def, Criteria.all());
        Scorecard poor = Scorer.score(
                full("minecraft:snowy_plains", 4.0, 1, 0.98, 0.01, 300.0, 1.4, 20.0, 2048),
                def, Criteria.all());
        Scorecard blank = Scorer.score(nothingMeasuredSpawn("minecraft:snowy_plains"),
                def, Criteria.all());

        assertEquals(fromConfig, good.ceiling(), 1e-9);
        assertEquals(fromConfig, poor.ceiling(), 1e-9);
        assertEquals(fromConfig, blank.ceiling(), 1e-9,
                "an unmeasured seed must not shrink its own denominator");
        assertEquals(fromConfig, Scorer.ceiling(def, Criteria.all()), 1e-9,
                "the ceiling must be stable across runs");
        assertTrue(good.percentage() > poor.percentage(),
                "good " + good.percentage() + " vs poor " + poor.percentage());
    }

    @Test
    void gatesCostNoWeightSoClearingThemBuysNothing() {
        // A gate must not appear in the denominator at all: clearing one is a
        // precondition for being scored, not a mark to be awarded.
        DimensionConfig withGate = config(List.of("minecraft:snowy_plains"), null, null);
        DimensionConfig withoutGate = config(null, null, null);
        assertEquals(Scorer.ceiling(withoutGate, Criteria.all()),
                Scorer.ceiling(withGate, Criteria.all()), 1e-9);
    }

    @Test
    void everyCriterionThatAppliesIsEitherGradedOrSaysWhyNot() {
        // The scorer's contract: an applicable criterion always lands in the
        // ceiling, and its entry always carries a reason a human can read.
        DimensionConfig def = config(List.of("minecraft:snowy_plains"), "rolling",
                List.of("a", "b", "c"));
        Scorecard card = Scorer.score(nothingMeasuredSpawn("minecraft:snowy_plains"),
                def, Criteria.all());
        for (Scorecard.Entry e : card.entries()) {
            assertTrue(e.detail() != null && !e.detail().isBlank(),
                    e.id() + " gave no readable reason");
        }
    }

    @Test
    void anUnmeasuredGradedCriterionShrinksTheCeilingRatherThanCountingAsAZero() {
        // "biome_variety_present" applies (three biomes configured) but comes
        // back unmeasured (distinctCount absent) — it must vanish from the
        // ceiling exactly like an inapplicable criterion, not sit in the
        // denominator contributing a silent zero.
        DimensionConfig def = config(List.of("minecraft:snowy_plains"), "rolling",
                List.of("a", "b", "c"));
        double configCeiling = Scorer.ceiling(def, Criteria.all());
        Scorecard card = Scorer.score(
                full("minecraft:snowy_plains", 4.0, null, 0.40, 0.50, 30.0, 0.5, 300.0, 2048),
                def, Criteria.all());

        assertEquals(Scorecard.Verdict.SCORED, card.verdict());
        long unmeasuredGraded = card.entries().stream()
                .filter(e -> e.outcome().equals("unmeasured") && e.value() == null)
                .count();
        assertEquals(1, unmeasuredGraded, "exactly biome_variety_present should be unmeasured here");
        assertEquals(configCeiling - 1.0, card.ceiling(), 1e-9,
                "the one unmeasured criterion must shrink the ceiling by exactly one");
        // achieved is untouched either way — the fix is entirely about the
        // denominator, never about crediting an absent measurement.
        double sumOfValues = card.entries().stream()
                .filter(e -> e.value() != null)
                .mapToDouble(Scorecard.Entry::value).sum();
        assertEquals(sumOfValues, card.achieved(), 1e-9);
    }

    @Test
    void aPartiallyMeasuredSeedIsNeverPenalisedBelowWhatItsMeasuredCriteriaEarned() {
        // The defect itself: under the old "ceiling += 1.0 for every
        // applicable criterion, measured or not" rule, this seed's
        // percentage would have been achieved / configCeiling — diluted by
        // the one criterion that could not be measured. It must now be
        // achieved / (configCeiling - 1), which is never lower.
        DimensionConfig def = config(List.of("minecraft:snowy_plains"), "rolling",
                List.of("a", "b", "c"));
        double configCeiling = Scorer.ceiling(def, Criteria.all());
        Scorecard card = Scorer.score(
                full("minecraft:snowy_plains", 4.0, null, 0.40, 0.50, 30.0, 0.5, 300.0, 2048),
                def, Criteria.all());

        double oldPercentage = 100.0 * card.achieved() / configCeiling;
        assertTrue(card.percentage() >= oldPercentage - 1e-9,
                "new percentage " + card.percentage() + " must never fall below what the old "
                + "formula (diluted by an unmeasured criterion) gave: " + oldPercentage);
        assertTrue(card.ceiling() < configCeiling,
                "the ceiling must genuinely shrink, or this test proves nothing");
    }

    private static SeedFacts nothingMeasured() {
        return facts(new SeedFacts.SpawnFacts(gone(), gone(), gone(), gone(), gone(), gone()),
                new SeedFacts.BiomeFacts(gone(), gone(), gone(), gone()),
                new SeedFacts.TerrainFacts(gone(), gone(), gone(), gone(), gone()),
                new SeedFacts.StructureFacts(gone(), gone(), gone(), gone(), gone(),
                        gone(), gone(), gone()),
                4096, gone());
    }

    /** Nothing measured except the spawn biome, so the namesake gate passes. */
    private static SeedFacts nothingMeasuredSpawn(String biome) {
        return facts(new SeedFacts.SpawnFacts(
                        Measured.of(new SeedFacts.Column(0, 0, false)),
                        Measured.of(biome), gone(), gone(), gone(), gone()),
                new SeedFacts.BiomeFacts(gone(), gone(), gone(), gone()),
                new SeedFacts.TerrainFacts(gone(), gone(), gone(), gone(), gone()),
                new SeedFacts.StructureFacts(gone(), gone(), gone(), gone(), gone(),
                        gone(), gone(), gone()),
                4096, gone());
    }

    /** A config putting one group on the `cluster` profile. */
    private static DimensionConfig withClusterGroup(String group) {
        DimensionConfig def = config(null, null, null);
        DimensionConfig.Structures s = new DimensionConfig.Structures();
        com.google.gson.JsonObject noise = new com.google.gson.JsonObject();
        noise.addProperty(group, "cluster");
        s.noise = noise;
        def.setStructures(s);
        return def;
    }

    private static DimensionConfig noStructures() {
        DimensionConfig def = config(null, null, null);
        DimensionConfig.Structures s = new DimensionConfig.Structures();
        s.mode = "none";
        def.setStructures(s);
        return def;
    }

    private static double score(Criterion.Result r) {
        return assertInstanceOf(Criterion.Result.Score.class, r).value();
    }
}
