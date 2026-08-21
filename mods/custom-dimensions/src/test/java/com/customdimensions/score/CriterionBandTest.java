package com.customdimensions.score;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.Measured;
import com.customdimensions.facts.SeedFacts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The band a criterion wants, in blocks from spawn, and how it reaches the
 * scorecard.
 *
 * <p>The regression these exist for: a GATE answers {@code Pass}/{@code Fail},
 * neither of which carries a band, so the two clearest distances in the model
 * — a fortress within 512 blocks, an end city within 2048 — had no way to
 * reach the map at all.
 */
class CriterionBandTest {

    private static SeedFacts facts() {
        return new SeedFacts("test", "adventure:test", 1L, "now", "fp", 1000,
                null, null, null, null, Measured.absent("not measured in this fixture"));
    }

    /** A criterion that answers whatever the test hands it. */
    private static final class Stub implements Criterion {
        private final Result result;
        private final double[] band;
        private final boolean gate;

        Stub(Result result, double[] band, boolean gate) {
            this.result = result;
            this.band = band;
            this.gate = gate;
        }

        public String id() {
            return "stub";
        }

        public Group group() {
            return Group.THEME;
        }

        public String target(DimensionConfig def) {
            return "a stub";
        }

        public boolean gate() {
            return this.gate;
        }

        public double[] band(DimensionConfig def) {
            return this.band;
        }

        public Result evaluate(SeedFacts f, DimensionConfig def) {
            return this.result;
        }
    }

    private static Scorecard.Entry only(Criterion c) {
        Scorecard card = Scorer.score(facts(), new DimensionConfig(), List.of(c));
        assertEquals(1, card.entries().size());
        return card.entries().get(0);
    }

    @Test
    void aCriterionDeclaresNoBandByDefault() {
        assertNull(new Stub(new Criterion.Result.Pass("ok"), null, true)
                .band(new DimensionConfig()));
    }

    @Test
    void aPassedGateCarriesTheBandItDeclared() {
        // The whole point: Result.Pass has no band field, so without the
        // criterion declaring one a gate could never draw its radius.
        Scorecard.Entry e = only(new Stub(
                new Criterion.Result.Pass("found one"), new double[] {0.0, 512.0}, true));
        assertEquals("pass", e.outcome());
        assertArrayEquals(new double[] {0.0, 512.0}, e.band());
    }

    @Test
    void aFailedGateCarriesItToo() {
        // A failure is exactly when a person wants to see how far away the
        // thing was, so the band must survive the reject path as well.
        Scorecard.Entry e = only(new Stub(
                new Criterion.Result.Fail("too far", "923 blocks"),
                new double[] {0.0, 512.0}, true));
        assertEquals("fail", e.outcome());
        assertArrayEquals(new double[] {0.0, 512.0}, e.band());
    }

    @Test
    void aResultsOwnBandWinsOverTheDeclaredOne() {
        // first_encounter_distance computes a per-seed band; a class-level
        // default must never overwrite it.
        Scorecard.Entry e = only(new Stub(
                new Criterion.Result.Score(1.0, "ev", new double[] {409.6, 2457.6}),
                new double[] {0.0, 512.0}, false));
        assertArrayEquals(new double[] {409.6, 2457.6}, e.band());
    }

    @Test
    void aScoreWithNoBandOfItsOwnFallsBackToTheDeclaredOne() {
        Scorecard.Entry e = only(new Stub(
                new Criterion.Result.Score(1.0, "ev", null),
                new double[] {0.0, 512.0}, false));
        assertArrayEquals(new double[] {0.0, 512.0}, e.band());
    }

    @Test
    void aBandThatIsNotAPairIsNoBand() {
        // Scorecard.Entry does not validate, so a malformed band would reach
        // the page and dartboard.js would read band[1] off a one-element array.
        Scorecard.Entry e = only(new Stub(
                new Criterion.Result.Pass("ok"), new double[] {512.0}, true));
        assertNull(e.band());
    }

    @Test
    void aCriterionThatThrowsFromBandHasNone() {
        Criterion throwing = new Criterion() {
            public String id() {
                return "throws";
            }

            public Group group() {
                return Group.THEME;
            }

            public String target(DimensionConfig def) {
                return "a criterion that cannot state its band";
            }

            public boolean gate() {
                return true;
            }

            public double[] band(DimensionConfig def) {
                throw new IllegalStateException("boom");
            }

            public Result evaluate(SeedFacts f, DimensionConfig def) {
                return new Result.Pass("ok");
            }
        };
        assertNull(only(throwing).band());
    }

    @Test
    void theReachabilityGatesDeclareTheirOwnFloors() {
        // Both floors are fractions of the dimension's own playable border, so
        // they scale with it instead of meaning different things at a 1024
        // border and an 8192 one. The fractions differ: a fortress is findable
        // across the Nether, while end cities sit only in the outer islands, so
        // the End's floor is its whole radius. CensusCommands reads the same
        // definitions rather than mirroring numbers that could drift from them.
        DimensionConfig nether = new DimensionConfig();
        DimensionConfig.Borders small = new DimensionConfig.Borders();
        small.player = 1024;
        nether.setBorders(small);
        DimensionConfig end = new DimensionConfig();
        DimensionConfig.Borders large = new DimensionConfig.Borders();
        large.player = 8192;
        end.setBorders(large);
        assertArrayEquals(new double[] {0.0, 512.0},
                new Criteria.FortressReachableInNether().band(nether));
        assertArrayEquals(new double[] {0.0, 8192.0},
                new Criteria.EndCityReachableInEnd().band(end));
    }

    @Test
    void aCriterionWhoseQuestionIsNotADistanceHasNoBand() {
        // Only three of the fixed criteria ask a distance. A fraction, a
        // density and a share of safe columns must stay null rather than
        // carry an invented radius the map would draw as a meaningful ring.
        DimensionConfig def = new DimensionConfig();
        assertNull(new Criteria.HeadlineBiomeDominatesAppropriately().band(def));
        assertNull(new Criteria.BiomeEdgesNearSpawn().band(def));
        assertNull(new Criteria.SpawnIsPlayable().band(def));
        assertNull(new Criteria.SpawnReadsAsNamesake().band(def));
    }
}
