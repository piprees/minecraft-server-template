package com.customdimensions.score;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.Measured;
import com.customdimensions.facts.SeedFacts;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts over a bank of scorecards: a scorer that puts every dimension at
 * 95-99% has stopped ranking, and a criterion that returns the same value
 * (or a gate that only ever passes) everywhere is dead weight that ranks
 * nothing while still occupying a share of the ceiling.
 */
class ScorecardDistributionTest {

    private static final int RADIUS = 4096;

    // --- fixtures ------------------------------------------------------

    private static SeedFacts.SpawnFacts spawn(String biome, double localRelief) {
        return new SeedFacts.SpawnFacts(
                Measured.of(new SeedFacts.Column(0, 0, true)),
                Measured.of(biome),
                Measured.of(64),
                Measured.of(localRelief),
                Measured.of(true),
                Measured.absent("not measured in this fixture"));
    }

    private static SeedFacts.BiomeFacts biomes(int distinct, double headline, double edges) {
        return new SeedFacts.BiomeFacts(
                Measured.of(Map.of("minecraft:plains", 1.0)),
                Measured.of(distinct), Measured.of(headline), Measured.of(edges));
    }

    private static SeedFacts.TerrainFacts terrain(double relief) {
        return new SeedFacts.TerrainFacts(
                Measured.of(relief), Measured.of(2.0), Measured.of(0.1),
                Measured.of(0), Measured.of(100));
    }

    private static SeedFacts.StructureFacts structures(double clustering, double nearestHostile) {
        Map<String, Double> spacing = Map.of("deco", clustering);
        Map<String, Integer> counts = Map.of("deco", 20);
        return new SeedFacts.StructureFacts(
                Measured.of(Map.of()), Measured.of(counts), Measured.of(Map.of()), Measured.of(Map.of()),
                Measured.of(spacing), Measured.of(clustering), Measured.of(nearestHostile),
                Measured.of(20));
    }

    private static SeedFacts facts(String spawnBiome, double localRelief, int distinct,
                                   double headline, double edges, double relief,
                                   double clustering, double nearestHostile, long seed) {
        return new SeedFacts("test", "adventure:test", seed, "now", "fp", RADIUS,
                spawn(spawnBiome, localRelief), biomes(distinct, headline, edges),
                terrain(relief), structures(clustering, nearestHostile),
                Measured.absent("not measured in this fixture"));
    }

    /** One config every scored scenario shares: every criterion applies to it. */
    private static DimensionConfig config() {
        DimensionConfig def = new DimensionConfig();
        DimensionConfig.SeedRoll sr = new DimensionConfig.SeedRoll();
        sr.spawnFilter = List.of("minecraft:snowy_plains");
        sr.terrain = "rolling";
        def.setSeedRoll(sr);
        List<JsonElement> raw = new ArrayList<>();
        for (String b : List.of("a", "b", "c", "d")) {
            raw.add(new JsonPrimitive(b));
        }
        def.setBiomes(raw);
        return def;
    }

    /**
     * Ten scored seeds, each parameter swept end to end, plus two rejections
     * (one per gate) — a bank shaped like a real roll session's spread rather
     * than a single hand-picked "good" case.
     */
    private static List<Scorecard> bank() {
        double[] headline = {0.10, 0.15, 0.22, 0.32, 0.40, 0.50, 0.60, 0.75, 0.85, 0.95};
        double[] edges =    {0.02, 0.05, 0.10, 0.20, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55};
        double[] relief =   {5,    15,   20,   30,   40,   55,   65,   80,   100,  150};
        double[] cluster =  {0.5,  0.7,  0.85, 1.0,  1.05, 1.15, 1.3,  1.5,  1.8,  2.1491};
        double[] nearFrac = {0.01, 0.03, 0.05, 0.10, 0.15, 0.20, 0.30, 0.40, 0.60, 0.90};
        int[] distinct =    {1,    1,    2,    2,    3,    3,    4,    4,    4,    4};

        DimensionConfig def = config();
        List<Criterion> criteria = Criteria.all();
        List<Scorecard> cards = new ArrayList<>();
        for (int i = 0; i < headline.length; i++) {
            SeedFacts f = facts("minecraft:snowy_plains", 5.0, distinct[i], headline[i], edges[i],
                    relief[i], cluster[i], nearFrac[i] * RADIUS, i);
            cards.add(Scorer.score(f, def, criteria));
        }
        // Both gates must fail at least once, or the "gate that always
        // passes" branch of the dead-criteria check is never exercised on a
        // healthy bank either.
        cards.add(Scorer.score(facts("minecraft:desert", 5.0, 4, 0.40, 0.35, 35.0, 1.0,
                0.15 * RADIUS, 100), def, criteria));
        cards.add(Scorer.score(facts("minecraft:snowy_plains", 80.0, 4, 0.40, 0.35, 35.0, 1.0,
                0.15 * RADIUS, 101), def, criteria));
        return cards;
    }

    private static List<Scorecard> scoredOnly(List<Scorecard> cards) {
        return cards.stream().filter(c -> c.verdict() == Scorecard.Verdict.SCORED).toList();
    }

    // --- percentage bounds and spread ----------------------------------

    @Test
    void noScoredCardExceedsOneHundredPercent() {
        for (Scorecard card : scoredOnly(bank())) {
            Double pct = card.percentage();
            assertTrue(pct != null && pct <= 100.0 + 1e-9,
                    card.dimension() + "@" + card.seed() + " scored " + pct);
        }
    }

    @Test
    void theBankDiscriminatesRatherThanClusteringNearTheCeiling() {
        List<Scorecard> scored = scoredOnly(bank());
        assertTrue(scored.size() >= 10, "need a real bank to say anything about a distribution");
        double min = scored.stream().mapToDouble(c -> c.percentage()).min().orElseThrow();
        double max = scored.stream().mapToDouble(c -> c.percentage()).max().orElseThrow();
        assertTrue(min < 95.0,
                "every scored card sits at 95%% or above (min " + min + ") — the scorer has "
                + "stopped ranking");
        assertTrue(max - min > 20.0,
                "the spread across a deliberately varied bank is only " + (max - min) + " points");
    }

    // --- dead-criteria detector -----------------------------------------

    /**
     * Criteria that returned the same value everywhere (or, for a gate, only
     * ever passed) across a bank. A graded criterion is dead when every
     * recorded value is identical AND it was never seen as anything but
     * scored/unmeasured; a gate is dead when it was never graded and never
     * seen as anything but a pass.
     */
    static List<String> deadCriteria(List<Scorecard> cards) {
        Map<String, Set<Double>> values = new LinkedHashMap<>();
        Map<String, Set<String>> outcomes = new LinkedHashMap<>();
        for (Scorecard card : cards) {
            for (Scorecard.Entry e : card.entries()) {
                outcomes.computeIfAbsent(e.id(), k -> new LinkedHashSet<>()).add(e.outcome());
                if (e.value() != null) {
                    double rounded = Math.round(e.value() * 1_000_000.0) / 1_000_000.0;
                    values.computeIfAbsent(e.id(), k -> new LinkedHashSet<>()).add(rounded);
                }
            }
        }
        List<String> dead = new ArrayList<>();
        for (String id : outcomes.keySet()) {
            Set<Double> graded = values.getOrDefault(id, Set.of());
            Set<String> outs = outcomes.get(id);
            if (graded.size() == 1 && Set.of("score", "unmeasured").containsAll(outs)) {
                dead.add(id + "=" + graded.iterator().next());
            } else if (graded.isEmpty() && outs.equals(Set.of("pass"))) {
                dead.add(id + "=always pass");
            }
        }
        return dead;
    }

    @Test
    void noCriterionIsDeadWeightOverAVariedBank() {
        List<String> dead = deadCriteria(bank());
        assertEquals(List.of(), dead,
                "a criterion that never varies ranks nothing while still occupying the ceiling: "
                + dead);
    }

    private static Scorecard.Entry entry(String id, String outcome, Double value) {
        return new Scorecard.Entry(id, Criterion.Group.THEME, "target", outcome, value, "detail");
    }

    private static Scorecard cardOf(Scorecard.Entry... entries) {
        return new Scorecard("test", 1L, Scorecard.Verdict.SCORED, "", 1.0, 1.0, List.of(entries));
    }

    @Test
    void detectorCatchesAScoreCriterionThatNeverChanges() {
        List<Scorecard> cards = List.of(
                cardOf(entry("rigid", "score", 0.5), entry("healthy", "score", 0.1)),
                cardOf(entry("rigid", "score", 0.5), entry("healthy", "score", 0.6)),
                cardOf(entry("rigid", "score", 0.5), entry("healthy", "score", 0.9)));
        List<String> dead = deadCriteria(cards);
        assertEquals(List.of("rigid=0.5"), dead);
    }

    @Test
    void detectorCatchesAGateThatOnlyEverPasses() {
        List<Scorecard> cards = List.of(
                cardOf(entry("gate_always", "pass", null), entry("gate_mixed", "pass", null)),
                cardOf(entry("gate_always", "pass", null), entry("gate_mixed", "fail", null)),
                cardOf(entry("gate_always", "pass", null), entry("gate_mixed", "pass", null)));
        List<String> dead = deadCriteria(cards);
        assertEquals(List.of("gate_always=always pass"), dead,
                "a gate that failed at least once must not be flagged");
    }

    @Test
    void detectorLeavesAGenuinelyVaryingCriterionAlone() {
        List<Scorecard> cards = List.of(
                cardOf(entry("varies", "score", 0.1)),
                cardOf(entry("varies", "score", 0.5)),
                cardOf(entry("varies", "unmeasured", null)));
        assertEquals(List.of(), deadCriteria(cards));
    }
}
