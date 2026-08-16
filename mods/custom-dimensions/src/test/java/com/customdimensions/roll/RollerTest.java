package com.customdimensions.roll;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.score.Scorecard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search loop's boundedness — the property {@code roll} exists to
 * guarantee. {@link Roller#screenShortlist} and {@link Roller#measureOne}
 * wire in {@code SeedBank}, {@code FactsEngine} and {@code Scorer} and need a
 * live server, so they are exercised in the local verification loop
 * (mods/AGENTS.md), not here; everything below is the pure loop with a stub
 * {@link Roller.Measurer} and a {@link Roller.Sink} backed by plain in-memory
 * collections, plus {@link Roller.TopN}, which needs neither.
 */
class RollerTest {

    /** A sink that just records what it was told, for asserting against. */
    private static final class RecordingSink implements Roller.Sink {
        final List<Long> scoredSeeds = new ArrayList<>();
        final List<Long> rejectedSeeds = new ArrayList<>();
        final List<String> rejectionReasons = new ArrayList<>();

        @Override
        public void scored(long seed, double achieved, double ceiling) {
            this.scoredSeeds.add(seed);
        }

        @Override
        public void rejected(long seed, String reason) {
            this.rejectedSeeds.add(seed);
            this.rejectionReasons.add(reason);
        }
    }

    @Test
    void aSeedCountBudgetNeverMeasuresMoreThanItAllows() {
        AtomicInteger measured = new AtomicInteger();
        AtomicLong counter = new AtomicLong();
        Roller.Measurer measurer = seed -> {
            measured.incrementAndGet();
            return new Roller.Draw(seed, Scorecard.Verdict.SCORED, 1.0, 1.0, "");
        };

        int result = Roller.roll(seed -> false, counter::getAndIncrement, measurer,
                new RecordingSink(), Roller.Budget.seeds(7));

        assertEquals(7, measured.get());
        assertEquals(7, result);
    }

    @Test
    void alreadyTriedSeedsAreSkippedAndNeverReachTheMeasurer() {
        AtomicInteger measured = new AtomicInteger();
        Roller.Measurer measurer = seed -> {
            measured.incrementAndGet();
            return new Roller.Draw(seed, Scorecard.Verdict.SCORED, 1.0, 1.0, "");
        };
        RecordingSink sink = new RecordingSink();

        // A seed source that offers nothing but an already-tried seed must
        // still terminate — the budget bounds ATTEMPTS, not new measurements.
        int result = Roller.roll(seed -> true, () -> 7L, measurer, sink, Roller.Budget.seeds(5));

        assertEquals(0, measured.get(), "an already-tried seed must never be re-measured");
        assertEquals(0, result);
        assertTrue(sink.scoredSeeds.isEmpty());
        assertTrue(sink.rejectedSeeds.isEmpty());
    }

    @Test
    void aPastDeadlineBudgetIsExceededImmediately() {
        Roller.Budget budget = new Roller.Budget(Integer.MAX_VALUE, System.nanoTime() - 1);
        assertTrue(budget.exceeded(0));
    }

    @Test
    void aFutureDeadlineBudgetIsNotYetExceeded() {
        Roller.Budget budget = new Roller.Budget(
                Integer.MAX_VALUE, System.nanoTime() + java.time.Duration.ofMinutes(5).toNanos());
        assertFalse(budget.exceeded(0));
    }

    @Test
    void scoredAndRejectedDrawsGoToDifferentSinkMethodsAndNeverBoth() {
        AtomicLong counter = new AtomicLong();
        Roller.Measurer measurer = seed -> seed % 3 == 0
                ? new Roller.Draw(seed, Scorecard.Verdict.REJECTED, 0.0, 10.0,
                        "fortress_reachable_in_nether: beyond the 512-block floor")
                : new Roller.Draw(seed, Scorecard.Verdict.SCORED, seed % 10, 10.0, "");
        RecordingSink sink = new RecordingSink();

        int measured = Roller.roll(seed -> false, counter::getAndIncrement, measurer,
                sink, Roller.Budget.seeds(20));

        assertEquals(20, measured);
        // Seeds 0, 3, 6, ..., 18 (7 of the 20 draws) hit the REJECTED branch.
        assertEquals(7, sink.rejectedSeeds.size());
        assertEquals(13, sink.scoredSeeds.size());
        Set<Long> both = new HashSet<>(sink.scoredSeeds);
        both.retainAll(sink.rejectedSeeds);
        assertTrue(both.isEmpty(), "a seed cannot be both scored and rejected");
        // The reason travels with the rejection. The scorecard that knew it is
        // gone by the time the sink runs, so if the loop drops it here the bank
        // can never be asked which gate did it.
        assertEquals(7, sink.rejectionReasons.size());
        for (String reason : sink.rejectionReasons) {
            assertEquals("fortress_reachable_in_nether: beyond the 512-block floor", reason);
        }
    }

    @Test
    void aSeedDrawnTwiceInOneRunIsMeasuredOnlyOnceWhenTheSinkFeedsBackIntoAlreadyTried() {
        // The shape screenShortlist and measureOne actually use: alreadyTried
        // and the sink share one mutable set, so a seed the sink just
        // recorded is not drawn again within the same run even though the
        // seed source alone would offer it forever.
        Set<Long> tried = new HashSet<>();
        AtomicInteger measured = new AtomicInteger();
        Roller.Measurer measurer = seed -> {
            measured.incrementAndGet();
            return new Roller.Draw(seed, Scorecard.Verdict.SCORED, 1.0, 1.0, "");
        };
        Roller.Sink sink = new Roller.Sink() {
            public void scored(long seed, double achieved, double ceiling) {
                tried.add(seed);
            }

            public void rejected(long seed, String reason) {
                tried.add(seed);
            }
        };

        int result = Roller.roll(seed -> tried.contains(seed), () -> 42L, measurer, sink, Roller.Budget.seeds(10));

        assertEquals(1, measured.get(), "the second and later draws of the same seed must be skipped");
        // The return value counts NEW measurements, not attempts — one seed
        // drawn ten times is one measurement. The budget still bounds the
        // ATTEMPTS underneath (proven by this test returning at all: a seed
        // source offering nothing but 42 forever would spin without it).
        assertEquals(1, result, "only the genuinely new measurement counts");
    }

    @Test
    void rollableSkipsSuperflatAndBiomelessVoidButNotOtherTypes() {
        assertFalse(Roller.rollable(typed("superflat", null)));
        assertFalse(Roller.rollable(typed("void", null)));
        assertTrue(Roller.rollable(typed("void", List.of("minecraft:plains"))));
        assertTrue(Roller.rollable(typed("overworld", null)));
        assertTrue(Roller.rollable(typed("nether", null)));
    }

    @Test
    void rollableHonoursAnExplicitSkipFlag() {
        DimensionConfig def = typed("overworld", null);
        DimensionConfig.SeedRoll sr = new DimensionConfig.SeedRoll();
        sr.skip = true;
        def.setSeedRoll(sr);

        assertFalse(Roller.rollable(def));
    }

    private static DimensionConfig typed(String type, List<String> biomes) {
        DimensionConfig def = new DimensionConfig();
        def.setType(type);
        if (biomes != null) {
            List<com.google.gson.JsonElement> raw = new java.util.ArrayList<>();
            for (String b : biomes) {
                raw.add(new com.google.gson.JsonPrimitive(b));
            }
            def.setBiomes(raw);
        }
        return def;
    }

    /**
     * {@link Roller.TopN} is tier 1's accumulator: pure, so its ranking is
     * pinned here rather than only inferred from a live shortlist that
     * needed a server to produce.
     */
    @org.junit.jupiter.api.Nested
    class TopNTest {

        @Test
        void keepsOnlyTheBestCapacityEntries() {
            Roller.TopN top = new Roller.TopN(3);
            top.add(1L, 10.0);
            top.add(2L, 90.0);
            top.add(3L, 50.0);
            top.add(4L, 20.0);
            top.add(5L, 80.0);

            assertEquals(List.of(2L, 5L, 3L), top.bestFirst(),
                    "only the three highest-ranked seeds survive, best first");
        }

        @Test
        void ordersDescendingByRank() {
            Roller.TopN top = new Roller.TopN(10);
            top.add(10L, 5.0);
            top.add(20L, 95.0);
            top.add(30L, 50.0);

            assertEquals(List.of(20L, 30L, 10L), top.bestFirst());
        }

        @Test
        void tiesBreakByDrawOrderNotBySeedValue() {
            Roller.TopN top = new Roller.TopN(5);
            top.add(999L, 50.0);
            top.add(1L, 50.0);

            assertEquals(List.of(999L, 1L), top.bestFirst(),
                    "equal rank keeps the seed drawn first ahead of one drawn later");
        }

        @Test
        void anEmptyAccumulatorShortlistsNothing() {
            assertTrue(new Roller.TopN(Roller.SHORTLIST).bestFirst().isEmpty());
        }
    }
}
