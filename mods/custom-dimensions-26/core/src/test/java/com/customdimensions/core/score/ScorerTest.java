package com.customdimensions.core.score;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.customdimensions.core.score.RollFixtures.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScorerTest {

    @Test
    void aPlainSeedIsScoredAndItsCeilingIsTheConfigCeiling() {
        RollTarget t = target("{\"spawnFilter\": [\"minecraft:plains\"], \"terrain\": \"rolling\", \"water\": \"none\"}");
        List<Criterion> criteria = Criteria.forTarget(t, List.of());
        Scorecard card = Scorer.score(plain(), t, criteria);
        assertEquals(Scorecard.Verdict.SCORED, card.verdict());
        assertEquals(Scorer.ceiling(t, criteria), card.ceiling());
        assertTrue(card.percentage() > 50.0);
    }

    @Test
    void aFailedGateRejectsTheSeedAndCarriesItsBand() {
        RollTarget nether = target(Criteria.NETHER, "{}", 1000, 2);
        RollFacts far = withStructures(structures(Set.of("minecraft:fortress"), Map.of(), List.of(start("minecraft:fortress", 900))));
        Scorecard card = Scorer.score(far, nether, Criteria.forTarget(nether, List.of()));
        assertEquals(Scorecard.Verdict.REJECTED, card.verdict());
        assertTrue(card.verdictReason().startsWith("fortress_reachable_in_nether"));
        Scorecard.Entry gate = card.entries().stream().filter(e -> e.id().equals("fortress_reachable_in_nether")).findFirst().orElseThrow();
        assertArrayEquals(new double[] {0.0, 500.0}, gate.band());
        assertNull(card.percentage());
    }

    @Test
    void aWantsScoreCarriesItsBandInBlocks() {
        RollTarget t = target("{\"wants\": {\"village\": \"spread\"}}");
        RollFacts f = withStructures(structures(Set.of("minecraft:village_plains"), Map.of("village", List.of("minecraft:village_plains")),
                List.of(start("minecraft:village_plains", 400))));
        Scorecard.Entry want = Scorer.score(f, t, Criteria.forTarget(t, List.of())).entries().stream()
                .filter(e -> e.id().equals("wants:village")).findFirst().orElseThrow();
        assertArrayEquals(new double[] {100.0, 750.0}, want.band(), 1e-9);
        assertEquals(1.0, want.value());
    }

    @Test
    void anUnmeasuredCriterionLeavesTheCeilingAndAThrowingOneScoresZero() {
        RollTarget t = target("{}");
        Criterion throwing = new Criterion() {
            public String id() {
                return "throws";
            }

            public Group group() {
                return Group.FUN;
            }

            public String target(RollTarget def) {
                return "nothing";
            }

            public Result evaluate(RollFacts facts, RollTarget def) {
                throw new IllegalStateException("boom");
            }
        };
        RollFacts noPatch = facts(spawn("minecraft:plains", null), plain().biomes(), plain().terrain(), plain().structures(), List.of());
        Scorecard card = Scorer.score(noPatch, t, List.of(new Criteria.SpawnIsPlayable(), throwing, new Criteria.PlayableGroundCoversTheDisc()));
        assertEquals(Scorecard.Verdict.SCORED, card.verdict());
        assertEquals(1.0, card.ceiling());
        assertEquals("unmeasured", card.entries().getFirst().outcome());
        assertEquals("unmeasured", card.entries().get(1).outcome());
    }

    @Test
    void aConfigAskingNothingIsInvalid() {
        RollTarget t = target("{}");
        Scorecard card = Scorer.score(plain(), t, List.of(new Criteria.WaterMatchesIntent()));
        assertEquals(Scorecard.Verdict.INVALID_CONFIG, card.verdict());
    }

    @Test
    void theContactSheetRanksScoredBeforeRejectedBeforeInvalidThenByPercentage() {
        Scorecard high = new Scorecard("d", 1, Scorecard.Verdict.SCORED, "", 9, 10, List.of());
        Scorecard low = new Scorecard("d", 2, Scorecard.Verdict.SCORED, "", 1, 10, List.of());
        Scorecard rejected = new Scorecard("d", 3, Scorecard.Verdict.REJECTED, "gate", 10, 10, List.of());
        Scorecard invalid = new Scorecard("d", 4, Scorecard.Verdict.INVALID_CONFIG, "none", 0, 0, List.of());
        List<Scorecard> sorted = new java.util.ArrayList<>(List.of(invalid, rejected, low, high));
        sorted.sort(ContactSheet.RANKING);
        assertEquals(List.of(1L, 2L, 3L, 4L), sorted.stream().map(Scorecard::seed).toList());
    }
}
