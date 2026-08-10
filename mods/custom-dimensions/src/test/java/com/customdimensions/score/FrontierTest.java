package com.customdimensions.score;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Four shapes a frontier must get right: clear dominance excludes the
 * dominated candidate, a genuine tie keeps both, two candidates each winning
 * a different criterion both survive with distinct strengths, and one
 * candidate is trivially its own frontier. Everything else here is the
 * consequence of exact-or-absent applied to per-criterion comparison: a
 * criterion neither side (or only one side) carries a value for must never
 * manufacture a dominance verdict.
 */
class FrontierTest {

    private static Scorecard.Entry score(String id, double value) {
        return new Scorecard.Entry(id, Criterion.Group.INTEREST, "target", "score", value, "evidence");
    }

    private static Scorecard.Entry unmeasured(String id) {
        // Matches what Scorer actually emits: an unmeasured graded criterion
        // carries a null value, the same shape as not_applicable — not a
        // defaulted zero.
        return new Scorecard.Entry(id, Criterion.Group.INTEREST, "target", "unmeasured", null, "absent");
    }

    private static Scorecard.Entry notApplicable(String id) {
        return new Scorecard.Entry(id, Criterion.Group.INTEREST, "target", "not_applicable", null,
                "config never asked");
    }

    private static Scorecard.Entry gatePass(String id) {
        return new Scorecard.Entry(id, Criterion.Group.THEME, "target", "pass", null, "cleared");
    }

    private static Scorecard scored(String dimension, long seed, Scorecard.Entry... entries) {
        double achieved = 0.0;
        double ceiling = 0.0;
        for (Scorecard.Entry e : entries) {
            if (e.value() != null) {
                achieved += e.value();
            }
            if (!"not_applicable".equals(e.outcome()) && !"pass".equals(e.outcome())) {
                ceiling += 1.0;
            }
        }
        return new Scorecard(dimension, seed, Scorecard.Verdict.SCORED, "", achieved, ceiling, List.of(entries));
    }

    private static Scorecard rejected(String dimension, long seed) {
        return new Scorecard(dimension, seed, Scorecard.Verdict.REJECTED, "some_gate: failed", 0.0, 0.0, List.of());
    }

    private static Scorecard invalidConfig(String dimension, long seed) {
        return new Scorecard(dimension, seed, Scorecard.Verdict.INVALID_CONFIG, "nothing applied", 0.0, 0.0,
                List.of());
    }

    private static Set<Long> seedsOf(List<Frontier.Member> members) {
        return members.stream().map(m -> m.scorecard().seed()).collect(java.util.stream.Collectors.toSet());
    }

    // ------------------------------------------------------------- dominance

    @Test
    void clearDominanceExcludesTheDominatedCandidate() {
        // strong is at least as good as weak on every criterion and strictly
        // better on both — weak has no reason to be on the frontier.
        Scorecard strong = scored("adventure:test", 1, score("biomes", 0.9), score("structures", 0.8));
        Scorecard weak = scored("adventure:test", 2, score("biomes", 0.5), score("structures", 0.3));

        assertTrue(Frontier.dominates(strong, weak));
        assertTrue(!Frontier.dominates(weak, strong));

        List<Frontier.Member> frontier = Frontier.of(List.of(strong, weak));
        assertEquals(Set.of(1L), seedsOf(frontier));
    }

    @Test
    void aGenuineTieKeepsBothCandidates() {
        Scorecard a = scored("adventure:test", 1, score("biomes", 0.7), score("structures", 0.4));
        Scorecard b = scored("adventure:test", 2, score("biomes", 0.7), score("structures", 0.4));

        assertTrue(!Frontier.dominates(a, b), "identical values dominate nothing");
        assertTrue(!Frontier.dominates(b, a));

        List<Frontier.Member> frontier = Frontier.of(List.of(a, b));
        assertEquals(Set.of(1L, 2L), seedsOf(frontier));
    }

    @Test
    void eachWinningADifferentCriterionKeepsBothWithDistinctStrengths() {
        Scorecard biomeWinner = scored("adventure:test", 1, score("biomes", 0.9), score("structures", 0.2));
        Scorecard structureWinner = scored("adventure:test", 2, score("biomes", 0.2), score("structures", 0.9));

        List<Frontier.Member> frontier = Frontier.of(List.of(biomeWinner, structureWinner));
        assertEquals(Set.of(1L, 2L), seedsOf(frontier));

        Frontier.Member m1 = frontier.stream().filter(m -> m.scorecard().seed() == 1).findFirst().orElseThrow();
        Frontier.Member m2 = frontier.stream().filter(m -> m.scorecard().seed() == 2).findFirst().orElseThrow();
        assertEquals(List.of("biomes"), m1.strengths());
        assertEquals(List.of("structures"), m2.strengths());
    }

    @Test
    void aSingleCandidateIsAlwaysOnTheFrontier() {
        Scorecard only = scored("adventure:test", 1, score("biomes", 0.1), score("structures", 0.1));
        List<Frontier.Member> frontier = Frontier.of(List.of(only));
        assertEquals(1, frontier.size());
        assertEquals(1L, frontier.get(0).scorecard().seed());
        // Nothing to be distinctively better than — the frontier is still
        // right, the summary just has nothing to say.
        assertEquals(List.of("biomes", "structures"), frontier.get(0).strengths());
    }

    // -------------------------------------------------- exact-or-absent

    @Test
    void gatesAndNotApplicableEntriesNeverAffectComparison() {
        // Same graded values, but one card also carries a passed gate and an
        // inapplicable criterion the other does not — neither the null-value
        // gate outcome nor the null-value not_applicable outcome may tip
        // dominance either way.
        Scorecard a = scored("adventure:test", 1, score("biomes", 0.6), gatePass("namesake"));
        Scorecard b = scored("adventure:test", 2, score("biomes", 0.6), notApplicable("terrain_matches_preset"));

        assertTrue(!Frontier.dominates(a, b));
        assertTrue(!Frontier.dominates(b, a));
        assertEquals(Set.of(1L, 2L), seedsOf(Frontier.of(List.of(a, b))));
    }

    @Test
    void anUnmeasuredGradedCriterionIsExcludedFromComparisonNotTreatedAsZero() {
        // Scorer excludes an unmeasured graded criterion's value entirely
        // (null, the same shape as not_applicable) rather than defaulting it
        // to 0.0 — a candidate missing one measurement must not look WORSE
        // on that criterion, only silent on it. The only comparable
        // criterion left between these two is "biomes", and it ties, so
        // neither dominates.
        Scorecard measured = scored("adventure:test", 1, score("biomes", 0.5), score("structures", 0.1));
        Scorecard partiallyMeasured = scored("adventure:test", 2, score("biomes", 0.5), unmeasured("structures"));

        assertTrue(!Frontier.dominates(measured, partiallyMeasured),
                "structures has no comparable value on one side — a tie on biomes alone proves nothing");
        assertTrue(!Frontier.dominates(partiallyMeasured, measured));
        assertEquals(Set.of(1L, 2L), seedsOf(Frontier.of(List.of(measured, partiallyMeasured))));
    }

    @Test
    void noComparableCriterionMeansNeitherDominates() {
        // Two candidates that share no graded criterion at all: there is no
        // evidence for a dominance claim in either direction.
        Scorecard a = scored("adventure:test", 1, score("only_a", 0.9));
        Scorecard b = scored("adventure:test", 2, score("only_b", 0.9));

        assertTrue(!Frontier.dominates(a, b));
        assertTrue(!Frontier.dominates(b, a));
        assertEquals(Set.of(1L, 2L), seedsOf(Frontier.of(List.of(a, b))));
    }

    // ------------------------------------------------------------- scoping

    @Test
    void rejectedAndInvalidConfigCardsAreExcludedBeforeComparison() {
        Scorecard good = scored("adventure:test", 1, score("biomes", 0.5));
        Scorecard bad = rejected("adventure:test", 2);
        Scorecard unscoreable = invalidConfig("adventure:test", 3);

        List<Frontier.Member> frontier = Frontier.of(List.of(good, bad, unscoreable));
        assertEquals(Set.of(1L), seedsOf(frontier));
    }

    @Test
    void dimensionsAreNeverComparedAgainstEachOther() {
        // Seed 2 would dominate seed 1 on every shared criterion id if the
        // two dimensions were pooled — they must not be.
        Scorecard weakInDimA = scored("adventure:dim_a", 1, score("biomes", 0.1));
        Scorecard strongInDimB = scored("adventure:dim_b", 2, score("biomes", 0.9));

        List<Frontier.Member> frontier = Frontier.of(List.of(weakInDimA, strongInDimB));
        assertEquals(Set.of(1L, 2L), seedsOf(frontier),
                "each dimension's own frontier must survive independently");
    }
}
