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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        // fixture should stay unaffected by spawn_is_playable unless
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
                nearbyGround == null ? gone() : Measured.of(nearbyGround),
                Measured.of(1.0));
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
        // Ground everywhere by default, same reasoning as the water and
        // height defaults: no other criterion's fixture should move because
        // playable_ground_covers_the_disc exists.
        return terrain(relief, water, minHeight, maxHeight, 1.0);
    }

    private static SeedFacts.TerrainFacts terrain(Double relief, Double water,
                                                   Integer minHeight, Integer maxHeight,
                                                   Double groundFraction) {
        return new SeedFacts.TerrainFacts(
                groundFraction == null ? gone() : Measured.of(groundFraction),
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
                Measured.of(total),
                gone(), gone(), gone(), gone());
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
                Measured.of(10),
                gone(), gone(), gone(), gone());
    }

    private static SeedFacts withStructures(SeedFacts.StructureFacts structures, int radius) {
        return facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain(30.0), structures, radius);
    }

    private static SeedFacts withTerrain(SeedFacts.TerrainFacts terrain, int radius) {
        return facts(spawn("b", 4.0), biomes(5, 0.4, 0.3), terrain, structures(0.6, 900.0), radius);
    }

    /** A facts record whose only interesting field is the spawn lattice reading. */
    private static SeedFacts withSafeColumns(Double safeFraction, int radius) {
        SeedFacts.SpawnFacts base = spawn("b", 4.0);
        return facts(new SeedFacts.SpawnFacts(base.column(), base.biome(), base.surfaceHeight(),
                        base.localRelief(), base.aboveSeaLevel(), base.nearbyGround(),
                        safeFraction == null ? gone() : Measured.of(safeFraction)),
                biomes(5, 0.4, 0.3), terrain(30.0), structures(0.6, 900.0), radius);
    }

    private static SeedFacts withNearbyGround(List<SeedFacts.GroundKind> ground, int radius) {
        return facts(spawn("b", 4.0, ground), biomes(5, 0.4, 0.3), terrain(30.0),
                structures(0.6, 900.0), radius);
    }

    /** A named spawn biome and an explicit biome-share map, for the namesake grade. */
    private static SeedFacts withShares(Map<String, Double> shares, String spawnBiome) {
        return facts(spawn(spawnBiome, 4.0),
                new SeedFacts.BiomeFacts(Measured.of(shares), Measured.of(shares.size()),
                        Measured.of(shares.values().stream().mapToDouble(Double::doubleValue)
                                .max().orElse(1.0)),
                        Measured.of(0.3)),
                terrain(30.0), structures(0.6, 900.0), 4096);
    }

    // --------------------------------------------------- spawn reads as name

    @Test
    void namesakeIsGradedOnHowMuchOfTheWorldReadsAsTheDimension() {
        // Not a gate. A spawn outside the filter used to REJECT the seed, and
        // that was measuring the wrong thing: picking a candidate writes the
        // position you were standing in as the spawn, so a world with its
        // namesake biome anywhere in it can be given a namesake spawn.
        var c = new Criteria.SpawnReadsAsNamesake();
        DimensionConfig def = config(List.of("minecraft:snowy_plains"), null, null);

        assertFalse(c.gate(), "rejecting on a movable spawn is what starved the boards");
        assertTrue(c.applicable(def));
        assertEquals(Criterion.Tier.CONFIGURED, c.tier());

        assertEquals(1.0, score(c.evaluate(withShares(
                Map.of("minecraft:snowy_plains", 1.0), "minecraft:snowy_plains"), def)), 1e-9,
                "a spawn already in a namesake biome is the full mark");

        // Spawn elsewhere, but a third of the world is the namesake: as easy
        // to place a spawn in as a world entirely covered by it.
        assertEquals(1.0, score(c.evaluate(withShares(
                Map.of("minecraft:snowy_plains", 0.33, "minecraft:desert", 0.67),
                "minecraft:desert"), def)), 1e-9);

        double sliver = score(c.evaluate(withShares(
                Map.of("minecraft:snowy_plains", 0.05, "minecraft:desert", 0.95),
                "minecraft:desert"), def));
        assertTrue(sliver > 0.0 && sliver < 0.5,
                "a sliver of namesake is worth something and not much, got " + sliver);

        assertEquals(0.0, score(c.evaluate(withShares(
                Map.of("minecraft:desert", 1.0), "minecraft:desert"), def)), 1e-9,
                "no namesake biome anywhere means this world is not this dimension");

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
        DimensionConfig def = config(null, null, List.of("a", "b", "c", "d", "e"));

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

    @Test
    void headlineShareIsOnlyAskedOfADimensionThatDeclaredAPalette() {
        var c = new Criteria.HeadlineBiomeDominatesAppropriately();

        // The overworld declares no biome list, so its biomes are whatever the
        // mod stack registered: Terralith's ~1800 of them put the headline
        // share at 0.088 and no seed can lift it into the 0.30-0.55 band. Asked,
        // it is a guaranteed zero occupying a share of the scale and ranking
        // nothing between one seed and the next.
        assertFalse(c.applicable(config(null, null, null)));
        assertFalse(c.applicable(config(null, null, List.of())));

        // One declared biome is full domination BY REQUEST — the band exists to
        // catch a multi-biome dimension that collapsed to one, not to mark down
        // an author who asked for exactly one.
        assertFalse(c.applicable(config(null, null, List.of("only"))));

        assertTrue(c.applicable(config(null, null, List.of("a", "b"))));
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
    void heightRangeIsScoredAsContainmentNotCoverageOfTheConfiguredSpan() {
        // The defect this replaces: scored as the share of the CONFIGURED
        // span the terrain filled, every dimension setting the field averaged
        // 0.168 and could not do better. All six declare [-60, 440] and real
        // terrain occupies 70-odd blocks of it, so a world behaving exactly as
        // asked was marked down for not being 500 blocks tall. An envelope is
        // a permission, not a quota.
        var c = new Criteria.HeightRangeMatchesIntent();
        DimensionConfig ranged = config(null, null, null);
        ranged.getSeedRoll().heightRange = new int[] {-64, 320};

        assertTrue(c.applicable(ranged));
        assertEquals(1.0, score(c.evaluate(
                withTerrain(terrain(30.0, 0.1, -64, 320), 4096), ranged)), 1e-9,
                "a measured span exactly filling the envelope is inside it");
        assertEquals(1.0, score(c.evaluate(
                withTerrain(terrain(30.0, 0.1, 90, 166), 4096), ranged)), 1e-9,
                "the_abyssal_shrine's real numbers: a narrow world entirely inside a wide "
                + "envelope is doing exactly what it was told, and used to score 0.15");
        assertEquals(0.5, score(c.evaluate(
                withTerrain(terrain(30.0, 0.1, 220, 420), 4096), ranged)), 1e-9,
                "half the terrain above the envelope's ceiling costs exactly half");
        assertEquals(0.0, score(c.evaluate(
                withTerrain(terrain(30.0, 0.1, 400, 500), 4096), ranged)), 1e-9,
                "a measured span entirely outside the configured envelope has none inside");

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
    void groundCoverageIsScoredAsTheFractionOfColumnsThatCarryGround() {
        var c = new Criteria.PlayableGroundCoversTheDisc();
        DimensionConfig def = config(null, null, null);

        assertTrue(c.applicable(def));
        assertEquals(1.0, score(c.evaluate(
                withTerrain(terrain(30.0, 0.1, 0, 100, 1.0), 4096), def)), 1e-9,
                "every sampled column carries ground");
        assertEquals(0.5, score(c.evaluate(
                withTerrain(terrain(30.0, 0.1, 0, 100, 0.5), 4096), def)), 1e-9,
                "half the sampled columns are empty");

        // A dimension whose own terrain word already says the floor is
        // deliberately sparse is not asked the question at all — that word is
        // exactly what TerrainMatchesPreset reads, against this same fact.
        assertFalse(c.applicable(config(null, "void", null)),
                "a void dimension is supposed to have gaps");
        assertFalse(c.applicable(config(null, "islands", null)),
                "a field of floating islands is supposed to have gaps");

        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(
                withTerrain(terrain(30.0, 0.1, 0, 100, null), 4096), def));
    }

    @Test
    void everyDimensionIsAskedExactlyOneOfTheTwoGroundQuestions() {
        // The two criteria read the same fact, so their applicability must be
        // disjoint AND exhaustive or a dimension is either double-counted for
        // having a floor or never asked about one at all.
        var coverage = new Criteria.PlayableGroundCoversTheDisc();
        var preset = new Criteria.TerrainMatchesPreset();
        for (String word : new String[] {null, "void", "islands", "rolling", "mountainous"}) {
            DimensionConfig def = config(null, word, null);
            boolean groundShape = "void".equals(word) || "islands".equals(word);
            assertEquals(!groundShape, coverage.applicable(def),
                    "coverage applicability for terrain word " + word);
            assertEquals(groundShape, "void".equals(word) || "islands".equals(word),
                    "ground-shape words are exactly void and islands");
            if (groundShape) {
                assertTrue(preset.applicable(def), word + " must be read by terrain_matches_preset");
            }
        }
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
        DimensionConfig nether = withBorder("the_nether", 1024);

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

    /** A dimension config with an explicit player border, so no default is assumed. */
    private static DimensionConfig withBorder(String name, int playerBorder) {
        DimensionConfig def = new DimensionConfig();
        def.setName(name);
        DimensionConfig.Borders b = new DimensionConfig.Borders();
        b.player = playerBorder;
        b.generation = playerBorder;
        def.setBorders(b);
        return def;
    }

    @Test
    void reachabilityFloorIsHalfTheDimensionsOwnBorder() {
        // Both gates ask the same question against their own scale, because a
        // distance in blocks means different things at a 1024 border and an
        // 8192 one.
        DimensionConfig nether = withBorder("the_nether", 1024);
        DimensionConfig end = withBorder("the_end", 8192);
        assertEquals(512.0, Criteria.reachableWithin(nether), 1e-9);
        assertEquals(4096.0, Criteria.reachableWithin(end), 1e-9);
    }

    @Test
    void endCityFloorMirrorsTheFortressGate() {
        var c = new Criteria.EndCityReachableInEnd();
        DimensionConfig end = withBorder("the_end", 8192);
        SeedFacts close = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:end_city", 80), Map.of("minecraft:end_city", 1500.0)), 8192);
        SeedFacts far = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:end_city", 80), Map.of("minecraft:end_city", 5000.0)), 8192);
        assertInstanceOf(Criterion.Result.Pass.class, c.evaluate(close, end));
        assertInstanceOf(Criterion.Result.Fail.class, c.evaluate(far, end));
    }

    @Test
    void anEndCityBeyondTheVoidRingIsStillReachable() {
        // End cities only generate on the outer islands, which start about
        // 1024 blocks out, so the nearest one measures ~2050-3450 blocks from
        // spawn on an ordinary seed. A floor that rejects those is rejecting
        // the End's geometry, not a bad world.
        var c = new Criteria.EndCityReachableInEnd();
        DimensionConfig end = withBorder("the_end", 8192);
        for (double blocks : new double[] {2048.0, 2291.0, 3453.0}) {
            SeedFacts facts = withStructures(structuresWithPoolAndNearest(
                    Map.of("minecraft:end_city", 80),
                    Map.of("minecraft:end_city", blocks)), 8192);
            assertInstanceOf(Criterion.Result.Pass.class, c.evaluate(facts, end),
                    blocks + " blocks is inside an 8192 border and must pass");
        }
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
        DimensionConfig nether = withBorder("the_nether", 1024);
        SeedFacts far = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:fortress", 100), Map.of("minecraft:fortress", 4000.0)), 1024);
        Scorecard card = Scorer.score(far, nether, Criteria.all());
        assertEquals(Scorecard.Verdict.REJECTED, card.verdict());
        assertTrue(card.verdictReason().startsWith("fortress_reachable_in_nether"),
                card.verdictReason());
    }

    // ------------------------------------------------------- spawn playability

    @Test
    void aCliffAtTheOriginNoLongerThrowsAwayAWorldWithSomewhereElseToStand() {
        // The behaviour change this criterion exists for. Both gates it
        // replaces read the ORIGIN column and rejected the seed outright, and
        // the picker moves off that column anyway — so what ranks is whether
        // the neighbourhood holds a column to arrive in, not whether the
        // origin happens to be one.
        var c = new Criteria.SpawnIsPlayable();
        DimensionConfig def = config(null, null, null);
        assertFalse(c.gate(), "a gate here is what threw away good worlds");

        Criterion.Result.Score ample = assertInstanceOf(Criterion.Result.Score.class,
                c.evaluate(withSafeColumns(0.40, 4096), def));
        assertEquals(1.0, ample.value(), 1e-9, "past AMPLE the picker has all the choice it needs");

        Criterion.Result.Score thin = assertInstanceOf(Criterion.Result.Score.class,
                c.evaluate(withSafeColumns(0.08, 4096), def));
        assertTrue(thin.value() > 0.0 && thin.value() < 1.0, "a thin choice still ranks: " + thin.value());
    }

    @Test
    void nowhereToArriveIsStillZeroRatherThanASoftenedGate() {
        var c = new Criteria.SpawnIsPlayable();
        DimensionConfig def = config(null, null, null);

        Criterion.Result.Score none = assertInstanceOf(Criterion.Result.Score.class,
                c.evaluate(withSafeColumns(0.0, 4096), def));
        assertEquals(0.0, none.value(), 1e-9);
        assertTrue(none.evidence().contains("nowhere"), none.evidence());
    }

    @Test
    void moreSafeColumnsNeverRankBelowFewer() {
        var c = new Criteria.SpawnIsPlayable();
        DimensionConfig def = config(null, null, null);
        double previous = -1.0;
        for (double fraction : new double[] {0.0, 0.04, 0.08, 0.16, 0.25, 0.5, 1.0}) {
            double v = assertInstanceOf(Criterion.Result.Score.class,
                    c.evaluate(withSafeColumns(fraction, 4096), def)).value();
            assertTrue(v >= previous, "safe fraction " + fraction + " scored " + v
                    + ", below the previous " + previous);
            previous = v;
        }
    }

    @Test
    void anUnmeasuredLatticeIsUnmeasuredNotUnsafe() {
        // Exact-or-absent: a lattice nothing could be probed on is not
        // evidence that the spawn is bad.
        var c = new Criteria.SpawnIsPlayable();
        assertInstanceOf(Criterion.Result.Unmeasured.class,
                c.evaluate(withSafeColumns(null, 4096), config(null, null, null)));
    }

    // ---------------------------------------------------------------- scorer

    @Test
    void aFailedGateRejectsTheSeedRatherThanDeductingFromIt() {
        // The weighted mean's central error: a deficiency purchasable with a
        // surplus elsewhere. A gate is not for sale. Blaze rods have no source
        // but a fortress, so a Nether whose only one is 4000 blocks out is
        // broken however well it measures everywhere else.
        DimensionConfig nether = withBorder("the_nether", 1024);
        Scorecard card = Scorer.score(withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:fortress", 100), Map.of("minecraft:fortress", 4000.0)), 1024),
                nether, Criteria.all());
        assertEquals(Scorecard.Verdict.REJECTED, card.verdict());
        assertTrue(card.verdictReason().startsWith("fortress_reachable_in_nether"),
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
        Scorecard blank = Scorer.score(nothingMeasuredSpawn("minecraft:desert"),
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
        // precondition for being scored, not a mark to be awarded. Every gate
        // this pack has is unconditional or keyed on a base world's id, so the
        // check is that no config change adds one to the ceiling.
        DimensionConfig plain = config(null, null, null);
        DimensionConfig nether = new DimensionConfig();
        nether.setName("the_nether");
        double gates = Criteria.all().stream().filter(Criterion::gate).count();
        assertTrue(gates >= 2, "the fixture must actually contain gates, found " + gates);
        assertEquals(Criteria.all().size() - gates - notAsked(plain),
                Scorer.ceiling(plain, Criteria.all()), 1e-9,
                "the ceiling is every applicable NON-gate criterion and nothing else");
        assertEquals(Scorer.ceiling(plain, Criteria.all()) + 0.0,
                Scorer.ceiling(plain, Criteria.all()), 1e-9);
        assertTrue(Scorer.ceiling(nether, Criteria.all()) > 0.0);
    }

    /** How many of the fixed criteria this config does not pose at all. */
    private static double notAsked(DimensionConfig def) {
        return Criteria.all().stream()
                .filter(c -> !c.gate() && !c.applicable(def)).count();
    }

    @Test
    void everyCriterionThatAppliesIsEitherGradedOrSaysWhyNot() {
        // The scorer's contract: an applicable criterion always lands in the
        // ceiling, and its entry always carries a reason a human can read.
        DimensionConfig def = config(List.of("minecraft:snowy_plains"), "rolling",
                List.of("a", "b", "c"));
        Scorecard card = Scorer.score(nothingMeasuredSpawn("minecraft:desert"),
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
        return facts(new SeedFacts.SpawnFacts(gone(), gone(), gone(), gone(), gone(), gone(), gone()),
                new SeedFacts.BiomeFacts(gone(), gone(), gone(), gone()),
                new SeedFacts.TerrainFacts(gone(), gone(), gone(), gone(), gone(), gone()),
                new SeedFacts.StructureFacts(gone(), gone(), gone(), gone(), gone(),
                        gone(), gone(), gone(), gone(), gone(), gone(), gone()),
                4096, gone());
    }

    /**
     * Nothing measured but the spawn biome. Pass a biome OUTSIDE the config's
     * filter: the namesake criterion then falls through to {@code
     * biomes.shares}, which is absent here, so every applicable criterion
     * comes back unmeasured — which is what these tests are about.
     */
    private static SeedFacts nothingMeasuredSpawn(String biome) {
        return facts(new SeedFacts.SpawnFacts(
                        Measured.of(new SeedFacts.Column(0, 0, false)),
                        Measured.of(biome), gone(), gone(), gone(), gone(), gone()),
                new SeedFacts.BiomeFacts(gone(), gone(), gone(), gone()),
                new SeedFacts.TerrainFacts(gone(), gone(), gone(), gone(), gone(), gone()),
                new SeedFacts.StructureFacts(gone(), gone(), gone(), gone(), gone(),
                        gone(), gone(), gone(), gone(), gone(), gone(), gone()),
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

    // ------------------------------------------------------------ terrain word

    @Test
    void theGroundWordsAreReadAgainstGroundFractionNotRelief() {
        // 486 wants and 12 terrain words were authored and read by nothing;
        // this half is the terrain words. Nine configs say islands and three
        // say void, and neither is a relief band — they ask whether there is
        // ground under you, which relief cannot answer.
        var c = new Criteria.TerrainMatchesPreset();
        DimensionConfig islands = config(null, "islands", null);
        DimensionConfig empty = config(null, "void", null);

        assertTrue(c.applicable(islands), "islands used to be not_applicable on every seed");
        assertTrue(c.applicable(empty), "void used to be not_applicable on every seed");
        assertEquals(Criterion.Tier.CONFIGURED, c.tier());

        assertEquals(1.0, score(c.evaluate(
                withTerrain(terrain(60.0, 0.1, 0, 200, 0.30), 4096), islands)), 1e-9,
                "a third of the disc carrying ground is a field of islands");
        assertTrue(score(c.evaluate(
                withTerrain(terrain(60.0, 0.1, 0, 200, 1.00), 4096), islands)) < 0.6,
                "solid ground everywhere is a continent, whatever the config called it");

        assertEquals(1.0, score(c.evaluate(
                withTerrain(terrain(0.0, 0.1, 0, 0, 0.02), 4096), empty)), 1e-9);
        assertEquals(0.0, score(c.evaluate(
                withTerrain(terrain(60.0, 0.1, 0, 200, 0.95), 4096), empty)), 1e-9,
                "a world with ground nearly everywhere is not a void");

        // The relief vocabulary is untouched.
        assertEquals(1.0, score(c.evaluate(
                full("b", 4.0, 5, 0.4, 0.3, 120.0, 0.6, 900.0, 4096),
                config(null, "mountainous", null))), 1e-9);
    }

    // ------------------------------------------------------- wants and shuns

    /** Structure facts with a pool and one measured distance, for the want criteria. */
    private static SeedFacts withWant(String structureId, Double nearest, int radius) {
        Map<String, Double> near = nearest == null ? Map.of() : Map.of(structureId, nearest);
        return withStructures(structuresWithPoolAndNearest(
                Map.of(structureId, 100), near), radius);
    }

    private static DimensionConfig radius(int borderRadius) {
        DimensionConfig def = config(null, null, null);
        DimensionConfig.Borders borders = new DimensionConfig.Borders();
        borders.player = borderRadius;
        def.setBorders(borders);
        return def;
    }

    @Test
    void aWantIsJudgedAsAFractionOfTheBorderNotInBlocks() {
        // The pack spans 1024-block borders to 16384-block ones. 900 blocks is
        // most of the way across a pocket dimension and the doorstep of a
        // full-sized one, so the same distance cannot mean the same thing in
        // both — which is why the bands are fractions.
        var c = new Criteria.WantedStructure("igloo", "minecraft:igloo",
                Criteria.Band.NEAR_SPAWN);

        assertEquals(1.0, score(c.evaluate(
                withWant("minecraft:igloo", 900.0, 8192), radius(8192))), 1e-9,
                "900 blocks is 11% of an 8192 border — near spawn");
        assertEquals(0.0, score(c.evaluate(
                withWant("minecraft:igloo", 900.0, 1024), radius(1024))), 1e-9,
                "the same 900 blocks is almost the whole of a 1024 border");
    }

    @Test
    void aMissIsMeasuredAgainstTheWorldNotAgainstTheBandsOwnWidth() {
        // The bands are different widths — near_spawn spans 15% of the radius
        // and spread spans 65%. Decaying over its own width would make the
        // same walk a total miss on one and a minor one on the other, and the
        // player walking it does not know which word the config used.
        double missBy = 0.10;   // a tenth of the radius past the band's edge
        var near = new Criteria.WantedStructure("igloo", "minecraft:igloo",
                Criteria.Band.NEAR_SPAWN);
        var spread = new Criteria.WantedStructure("igloo", "minecraft:igloo",
                Criteria.Band.SPREAD);

        double nearScore = score(near.evaluate(withWant("minecraft:igloo",
                (Criteria.Band.NEAR_SPAWN.high + missBy) * 4096, 4096), radius(4096)));
        double spreadScore = score(spread.evaluate(withWant("minecraft:igloo",
                (Criteria.Band.SPREAD.high + missBy) * 4096, 4096), radius(4096)));

        assertEquals(nearScore, spreadScore, 1e-9,
                "the same distance outside two bands must cost the same");
        assertEquals(1.0 - missBy / Criteria.Band.TOLERANCE, nearScore, 1e-9);
    }

    @Test
    void aWantCarriesItsBandInBlocksSoTheMapCanDrawIt() {
        // dartboard.js reads .mrow[data-band] as a range in BLOCKS from spawn.
        // A band declared as a fraction has to arrive there already converted,
        // or the arc is drawn at a fraction of a block from the centre.
        var c = new Criteria.WantedStructure("igloo", "minecraft:igloo",
                Criteria.Band.NEAR_BORDER);
        double[] band = c.band(radius(2048));
        assertEquals(0.55 * 2048, band[0], 1e-9);
        assertEquals(2048.0, band[1], 1e-9);

        Criterion.Result.Score s = assertInstanceOf(Criterion.Result.Score.class,
                c.evaluate(withWant("minecraft:igloo", 1500.0, 2048), radius(2048)));
        assertEquals(band[0], s.band()[0], 1e-9, "the per-seed result must carry it too");
    }

    @Test
    void aWantForSomethingAbsentFromThePoolIsUnmeasuredRatherThanZero() {
        // True of every seed of this dimension, not evidence about this one.
        // Scoring it zero is a permanent deduction for a config line lint
        // already reports — the same reasoning the reachability gates use.
        var c = new Criteria.WantedStructure("igloo", "minecraft:igloo", Criteria.Band.SPREAD);
        SeedFacts noIgloo = withStructures(structuresWithPoolAndNearest(
                Map.of("minecraft:village_plains", 50), Map.of()), 4096);
        Criterion.Result.Unmeasured u = assertInstanceOf(Criterion.Result.Unmeasured.class,
                c.evaluate(noIgloo, radius(4096)));
        assertTrue(u.reason().contains("pool"), u.reason());
    }

    @Test
    void aWantInThePoolThatThisSeedNeverPlacedScoresZero() {
        // The opposite case, and it must NOT be unmeasured: the structure
        // could have generated here and did not, which is a fact about this
        // seed and exactly what the want was asking about.
        var c = new Criteria.WantedStructure("igloo", "minecraft:igloo", Criteria.Band.SPREAD);
        assertEquals(0.0, score(c.evaluate(
                withWant("minecraft:igloo", null, 4096), radius(4096))), 1e-9);
    }

    @Test
    void anUnresolvableWantSaysSoRatherThanGuessing() {
        var unknown = new Criteria.WantedStructure("nonsuch", null, Criteria.Band.SPREAD);
        assertInstanceOf(Criterion.Result.Unmeasured.class,
                unknown.evaluate(withWant("minecraft:igloo", 500.0, 4096), radius(4096)));
    }

    // ------------------------------------------------------------- tag wants

    @Test
    void aTagWantIsScoredOnItsNearestMEMBERNotReportedUnmeasured() {
        // `village` resolves to #minecraft:village, and a tag is a SET — there
        // is no structure called "village" to measure a distance to. With
        // membership measured, the want is answered by whichever member is
        // closest, which is what "is there a village near spawn" means.
        var c = new Criteria.WantedStructure("village", "#minecraft:village",
                Criteria.Band.NEAR_SPAWN);
        SeedFacts facts = withTagWant(
                Map.of("minecraft:village_taiga", 3000.0, "minecraft:village_plains", 400.0),
                4096);

        assertEquals(1.0, score(c.evaluate(facts, radius(4096))), 1e-9,
                "the nearest member at 400 of 4096 is inside near_spawn");
    }

    @Test
    void aTagWantCountsEveryMembersPlacements() {
        var c = new Criteria.WantedStructure("village", "#minecraft:village",
                Criteria.Band.NEAR_SPAWN);
        Criterion.Result.Score r = assertInstanceOf(Criterion.Result.Score.class, c.evaluate(
                withTagWant(Map.of("minecraft:village_taiga", 3000.0,
                        "minecraft:village_plains", 400.0), 4096), radius(4096)));
        // The fixture places 3 of each member, so a tag want sees 6.
        assertTrue(r.evidence().contains("6 placed"), r.evidence());
        assertTrue(r.evidence().contains("minecraft:village_plains"),
                "the evidence must name the member that answered: " + r.evidence());
    }

    @Test
    void aTagWithNoMembersOnThisModStackIsUnmeasuredNotZero() {
        var c = new Criteria.WantedStructure("village", "#minecraft:village",
                Criteria.Band.SPREAD);
        SeedFacts empty = withStructures(new SeedFacts.StructureFacts(
                Measured.of(Map.of("minecraft:igloo", 10)),
                Measured.of(Map.of("deco", 10)), Measured.of(Map.of()), Measured.of(Map.of()),
                Measured.of(Map.of("deco", 1.0)), Measured.of(1.0), Measured.of(900.0),
                Measured.of(10),
                Measured.of(Map.of("#minecraft:village", List.<String>of())),
                gone(), gone(), gone()), 4096);

        Criterion.Result.Unmeasured u = assertInstanceOf(Criterion.Result.Unmeasured.class,
                c.evaluate(empty, radius(4096)));
        assertTrue(u.reason().contains("no structures"), u.reason());
    }

    @Test
    void aTagWantWithNoMembershipMeasuredIsUnmeasuredRatherThanGuessedAt() {
        // Guessing at membership would score a want against the wrong
        // structures, which is worse than saying it was not measured.
        var c = new Criteria.WantedStructure("village", "#minecraft:village",
                Criteria.Band.SPREAD);
        assertInstanceOf(Criterion.Result.Unmeasured.class,
                c.evaluate(withWant("minecraft:igloo", 500.0, 4096), radius(4096)));
    }

    @Test
    void aTagShunIsFullMarksOnlyWhenEveryMemberIsAbsent() {
        var c = new Criteria.ShunnedStructure("village", "#minecraft:village");
        // One member present at the rim still counts as present.
        assertTrue(score(c.evaluate(withTagWant(
                Map.of("minecraft:village_plains", 4096.0), 4096), radius(4096))) >= 1.0 - 1e-9);
        // A member on the doorstep is the disappointment the shun is about.
        assertEquals(0.1, score(c.evaluate(withTagWant(
                Map.of("minecraft:village_plains", 409.6), 4096), radius(4096))), 1e-9);
    }

    /** Structures whose pool, distances and byStructure are a tag's members. */
    private static SeedFacts withTagWant(Map<String, Double> nearestByMember, int radius) {
        Map<String, Integer> pool = new java.util.LinkedHashMap<>();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String id : nearestByMember.keySet()) {
            pool.put(id, 100);
            counts.put(id, 3);
        }
        return withStructures(new SeedFacts.StructureFacts(
                Measured.of(pool),
                Measured.of(Map.of("deco", 10)),
                Measured.of(counts),
                Measured.of(nearestByMember),
                Measured.of(Map.of("deco", 1.0)),
                Measured.of(1.0),
                Measured.of(900.0),
                Measured.of(10),
                Measured.of(Map.of("#minecraft:village",
                        List.of("minecraft:village_plains", "minecraft:village_taiga"))),
                gone(), gone(), gone()),
                radius);
    }

    @Test
    void aShunIsFullMarksWhenAbsentAndScoredByDistanceWhenNot() {
        var c = new Criteria.ShunnedStructure("monument", "minecraft:monument");
        assertEquals(Criterion.Tier.CONFIGURED, c.tier());

        assertEquals(1.0, score(c.evaluate(
                withStructures(structuresWithPoolAndNearest(
                        Map.of("minecraft:monument", 10), Map.of()), 4096), radius(4096))), 1e-9,
                "absent is the answer in full");

        double atSpawn = score(c.evaluate(
                withWant("minecraft:monument", 0.0, 4096), radius(4096)));
        double halfway = score(c.evaluate(
                withWant("minecraft:monument", 2048.0, 4096), radius(4096)));
        double atRim = score(c.evaluate(
                withWant("minecraft:monument", 4096.0, 4096), radius(4096)));

        assertEquals(0.0, atSpawn, 1e-9, "the thing you said no to, at spawn");
        assertEquals(0.5, halfway, 1e-9);
        assertEquals(1.0, atRim, 1e-9);
        assertTrue(atSpawn < halfway && halfway < atRim, "distance must rank");
    }

    // ------------------------------------------------------- hazardous spawn

    @Test
    void aDimensionThatMeansToBeDangerousIsNotAskedWhetherItsSpawnIsPlayable() {
        var c = new Criteria.SpawnIsPlayable();
        DimensionConfig asked = config(null, null, null);
        DimensionConfig hazardous = config(null, null, null);
        hazardous.getSeedRoll().allowHazardousSpawn = true;

        assertTrue(c.applicable(asked));
        assertFalse(c.applicable(hazardous));

        // Explicit false is the default spelled out, not a third state.
        DimensionConfig explicit = config(null, null, null);
        explicit.getSeedRoll().allowHazardousSpawn = false;
        assertTrue(c.applicable(explicit));
    }

    @Test
    void aWorldWithNowhereToArriveScoresPoorlyRatherThanVanishing() {
        // The yield fix, end to end. Before, a bad spawn REJECTED the seed and
        // the reason went with it; now the same world is scored, ranked below
        // its siblings, and still on the board where a person can see why.
        DimensionConfig def = config(null, null, null);
        Scorecard card = Scorer.score(withSafeColumns(0.0, 2048), def, Criteria.all());

        assertEquals(Scorecard.Verdict.SCORED, card.verdict(), card.verdictReason());
        Scorecard.Entry entry = card.entries().stream()
                .filter(e -> e.id().equals("spawn_is_playable")).findFirst().orElseThrow();
        assertEquals(0.0, entry.value(), 1e-9);
        assertTrue(card.percentage() < 100.0, "a world with nowhere to arrive is not a perfect one");
    }

    @Test
    void optingOutWithdrawsTheQuestionRatherThanScoringZeroOnIt() {
        // Now that spawn playability is graded rather than gated, opting out
        // takes it out of the DENOMINATOR too. That is the point: a dimension
        // that means to be dangerous where you arrive is not asked, so it is
        // neither marked down for delivering danger nor handed a free mark for
        // a question it declined.
        DimensionConfig asked = config(null, null, null);
        DimensionConfig hazardous = config(null, null, null);
        hazardous.getSeedRoll().allowHazardousSpawn = true;

        assertEquals(Scorer.ceiling(asked, Criteria.all()) - 1.0,
                Scorer.ceiling(hazardous, Criteria.all()), 1e-9,
                "opting out must remove the criterion from the ceiling, not leave it scoring zero");

        // And a world with nowhere to arrive is then not marked down for it.
        Scorecard card = Scorer.score(withSafeColumns(0.0, 2048), hazardous, Criteria.all());
        Scorecard.Entry entry = card.entries().stream()
                .filter(e -> e.id().equals("spawn_is_playable")).findFirst().orElseThrow();
        assertEquals("not_applicable", entry.outcome());
        assertNull(entry.value(), "a question that was not asked has no mark");
    }

    // ------------------------------------------------------------------ tiers

    @Test
    void theHeadlineIsTheMeanOfTheTiersNotThePooledRatio() {
        // A dimension authoring several wants poses more configured questions
        // than general ones. Pooled, the wants would decide the headline by
        // weight of numbers; averaged by tier, intent is worth half whatever
        // either tier is made of.
        DimensionConfig def = radius(4096);
        List<Criterion> criteria = new java.util.ArrayList<>(Criteria.all());
        for (int i = 0; i < 6; i++) {
            criteria.add(new Criteria.WantedStructure("w" + i, "minecraft:igloo",
                    Criteria.Band.NEAR_SPAWN));
        }
        // Every want perfect (igloo at 1% of the border) over a world that is
        // middling on the general criteria — half the mosaic it could be.
        SeedFacts f = facts(spawn("b", 4.0), biomes(5, 0.4, 0.10), terrain(30.0),
                structuresWithPoolAndNearest(Map.of("minecraft:igloo", 100),
                        Map.of("minecraft:igloo", 40.0)), 4096);
        Scorecard card = Scorer.score(f, def, criteria);

        Double configured = card.tierPercentage(Criterion.Tier.CONFIGURED);
        Double general = card.tierPercentage(Criterion.Tier.GENERAL);
        assertTrue(configured != null && general != null,
                "both tiers must have posed something: " + card.tiers());
        assertEquals(100.0, configured, 1e-9, "six perfect wants");
        assertTrue(general < 100.0, "the general tier is not perfect here, got " + general);
        assertEquals((configured + general) / 2.0, card.percentage(), 1e-9,
                "the headline is the mean of the tiers, not achieved/ceiling over both");
    }

    @Test
    void aTierThatPosedNothingIsSkippedRatherThanCountedAsZero() {
        // A config with no configured-tier question measurable must not be
        // dragged to half marks by an absent tier.
        DimensionConfig bare = config(null, null, null);
        Scorecard card = Scorer.score(
                full("b", 4.0, 5, 0.40, 0.20, 30.0, 0.5, 300.0, 2048), bare, Criteria.all());
        assertEquals(100.0, card.percentage(), 1e-9);
    }
}
