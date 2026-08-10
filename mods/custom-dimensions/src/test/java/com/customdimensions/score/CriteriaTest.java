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
 * Phase 5's gate: every criterion gets a passing case, a failing case and an
 * absent-input case. The tests ARE the argument about what each criterion
 * should mean — the old model's judgement was an emergent property of six
 * interacting constant tables and nothing tested it at all.
 */
class CriteriaTest {

    // ------------------------------------------------------------- fixtures

    private static <T> Measured<T> gone() {
        return Measured.absent("not measured in this fixture");
    }

    private static SeedFacts facts(SeedFacts.SpawnFacts spawn, SeedFacts.BiomeFacts biomes,
                                   SeedFacts.TerrainFacts terrain,
                                   SeedFacts.StructureFacts structures, int radius) {
        return new SeedFacts("adventure:test", 1L, "now", "fp", radius,
                spawn, biomes, terrain, structures);
    }

    private static SeedFacts.SpawnFacts spawn(String biome, Double localRelief) {
        return new SeedFacts.SpawnFacts(
                biome == null ? gone() : Measured.of(biome),
                Measured.of(64),
                localRelief == null ? gone() : Measured.of(localRelief),
                Measured.of(true));
    }

    private static SeedFacts.BiomeFacts biomes(Integer distinct, Double headline, Double edges) {
        return new SeedFacts.BiomeFacts(
                Measured.of(Map.of("minecraft:plains", 1.0)),
                distinct == null ? gone() : Measured.of(distinct),
                headline == null ? gone() : Measured.of(headline),
                edges == null ? gone() : Measured.of(edges));
    }

    private static SeedFacts.TerrainFacts terrain(Double relief) {
        return new SeedFacts.TerrainFacts(
                relief == null ? gone() : Measured.of(relief),
                Measured.of(2.0), Measured.of(0.1), Measured.of(0), Measured.of(100));
    }

    private static SeedFacts.StructureFacts structures(Double clustering, Double nearestHostile) {
        return new SeedFacts.StructureFacts(
                Measured.of(Map.of()), Measured.of(Map.of()), Measured.of(Map.of()),
                Measured.of(Map.of()),
                clustering == null ? gone() : Measured.of(clustering),
                nearestHostile == null ? gone() : Measured.of(nearestHostile),
                Measured.of(10));
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

    // --------------------------------------------------- spawn reads as name

    @Test
    void spawnNamesakeIsAGateNotAScore() {
        var c = new Criteria.SpawnReadsAsNamesake();
        DimensionConfig def = config(List.of("minecraft:snowy_plains"), null, null);

        assertTrue(c.gate(), "namesake must cost no weight (P6)");
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
        // Exclusion-based placement never falls below 1.0 in practice, so the
        // scale has to rank the range it can actually reach: 1.0 is the best
        // reachable, a perfect lattice is the worst. A pass/fail at 1.0 gave
        // all 81 dimensions a permanent zero and ranked nothing.
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
        // The far branch had the ramp pointed the wrong way, so every seed
        // whose nearest hostile sat past 30% of the border scored an identical
        // 0.0 — 70 percentage points of the axis ranking nothing, with a cliff
        // at the band edge. Both properties are asserted here.
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
        // which must be REACHABLE, unlike the old absolute scale.
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

    // ------------------------------------------------ gate 2: config ceiling

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
        // P6: namesake was 1.0 for the best candidate of all 81 dimensions — a
        // sixth of the scale that ranked nothing. As a gate it must not appear
        // in the denominator at all.
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

    private static SeedFacts nothingMeasured() {
        return facts(new SeedFacts.SpawnFacts(gone(), gone(), gone(), gone()),
                new SeedFacts.BiomeFacts(gone(), gone(), gone(), gone()),
                new SeedFacts.TerrainFacts(gone(), gone(), gone(), gone(), gone()),
                new SeedFacts.StructureFacts(gone(), gone(), gone(), gone(), gone(),
                        gone(), gone()),
                4096);
    }

    /** Nothing measured except the spawn biome, so the namesake gate passes. */
    private static SeedFacts nothingMeasuredSpawn(String biome) {
        return facts(new SeedFacts.SpawnFacts(Measured.of(biome), gone(), gone(), gone()),
                new SeedFacts.BiomeFacts(gone(), gone(), gone(), gone()),
                new SeedFacts.TerrainFacts(gone(), gone(), gone(), gone(), gone()),
                new SeedFacts.StructureFacts(gone(), gone(), gone(), gone(), gone(),
                        gone(), gone()),
                4096);
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
