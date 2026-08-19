package com.customdimensions.roll;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.score.Scorecard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /**
     * The windowed fan-out {@code rollParallel} and {@code measureShortlist}
     * both submit through — a real {@link ExecutorService}, but a stub
     * {@link Roller.Measurer} that never touches a server, so the mechanism
     * is tested without Fabric. Bounded concurrency itself (never more than
     * {@code parallelism} pending at once) is a one-line loop guard, evident
     * from reading {@code fanOut}, and not re-proven here with a timing test
     * — what IS tested is the two behaviours tier 2 needed and did not have
     * before: a failing seed does not stop the rest of the shortlist, and
     * stopping submission on abandon still drains what is already in flight.
     */
    @org.junit.jupiter.api.Nested
    class FanOutTest {

        /**
         * The guard that matters: a parallel sweep must bank exactly what a
         * serial one would. Pool size and window are wider than the seed
         * count, so completions genuinely race; the sink still sees draw
         * order.
         */
        @Test
        void aParallelSweepBanksExactlyWhatASerialSweepWould() {
            java.util.function.Supplier<Roller.Measurer> measurer = () -> seed -> {
                // Uneven work, so later seeds finish before earlier ones.
                try {
                    Thread.sleep(seed % 5 == 0 ? 6 : 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Roller.Draw(seed,
                        seed % 4 == 0 ? Scorecard.Verdict.REJECTED : Scorecard.Verdict.SCORED,
                        (seed * 37) % 101, 100.0, "stub");
            };

            RecordingSink serialSink = new RecordingSink();
            AtomicLong serialNext = new AtomicLong(1);
            int serialCount = Roller.roll(seed -> false, serialNext::getAndIncrement,
                    measurer.get(), serialSink, Roller.Budget.seeds(40));

            RecordingSink parallelSink = new RecordingSink();
            AtomicLong parallelNext = new AtomicLong(1);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            int parallelCount;
            try {
                parallelCount = Roller.fanOut(seed -> false, parallelNext::getAndIncrement,
                        measurer.get(), Roller.Budget.seeds(40), () -> false, pool, 8,
                        draw -> {
                            if (draw.verdict() == Scorecard.Verdict.SCORED) {
                                parallelSink.scored(draw.seed(), draw.achieved(), draw.ceiling());
                            } else {
                                parallelSink.rejected(draw.seed(), draw.verdictReason());
                            }
                        },
                        (seed, e) -> {
                            throw e;
                        });
            } finally {
                pool.shutdownNow();
            }

            assertEquals(serialCount, parallelCount, "measured count must match serial");
            assertEquals(serialSink.scoredSeeds, parallelSink.scoredSeeds,
                    "scored seeds and their order must match serial");
            assertEquals(serialSink.rejectedSeeds, parallelSink.rejectedSeeds,
                    "rejected seeds and their order must match serial");
        }

        @Test
        void aFailingSeedIsSkippedAndTheRestOfTheBatchStillMeasures() throws InterruptedException {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Long> seeds = List.of(1L, 2L, 3L);
                java.util.Iterator<Long> it = seeds.iterator();
                List<Long> succeeded = Collections.synchronizedList(new ArrayList<>());
                List<Long> failed = Collections.synchronizedList(new ArrayList<>());
                Roller.Measurer measurer = seed -> {
                    if (seed == 2L) {
                        throw new RuntimeException("boom");
                    }
                    return new Roller.Draw(seed, Scorecard.Verdict.SCORED, 1.0, 1.0, "");
                };

                int measured = Roller.fanOut(s -> false, it::next, measurer,
                        Roller.Budget.seeds(seeds.size()), () -> false, pool, 1,
                        draw -> succeeded.add(draw.seed()),
                        (seed, e) -> failed.add(seed));

                assertEquals(2, measured, "the failing seed must not count as measured");
                assertEquals(List.of(1L, 3L), succeeded, "seeds either side of the failure still measure");
                assertEquals(List.of(2L), failed);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void abandonStopsSubmittingButDrainsWhatIsAlreadyInFlight() throws InterruptedException {
            int parallelism = 2;
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch release = new CountDownLatch(1);
                AtomicInteger drawn = new AtomicInteger();
                java.util.function.LongSupplier seeds = drawn::incrementAndGet;
                // Seed 1's measurement blocks until the test releases it, so
                // fanOut's own thread is provably still waiting on the first
                // result while the pre-release assertion below runs.
                Roller.Measurer measurer = seed -> {
                    if (seed == 1) {
                        await(release);
                    }
                    return new Roller.Draw(seed, Scorecard.Verdict.SCORED, 1.0, 1.0, "");
                };
                List<Long> succeeded = Collections.synchronizedList(new ArrayList<>());
                Thread runner = new Thread(() -> Roller.fanOut(s -> false, seeds, measurer,
                        Roller.Budget.seeds(3), () -> drawn.get() >= parallelism, pool, parallelism,
                        draw -> succeeded.add(draw.seed()), (seed, e) -> { }));
                runner.start();

                // fanOut only has to fill a window of 2 and block on the
                // first (still-latched) result — generous relative to that.
                // A full window alone would explain this; it does not yet
                // prove abandonIf did anything.
                Thread.sleep(300);
                assertEquals(parallelism, drawn.get(), "the window fills to parallelism before blocking");

                // Releasing drains seed 1, which frees a window slot with a
                // seed still available under the budget (3) — the ONLY thing
                // that can now be stopping a third draw is abandonIf itself.
                release.countDown();
                runner.join(2000);
                assertEquals(parallelism, drawn.get(),
                        "abandonIf true must stop submission even once the window has room again");
                assertEquals(List.of(1L, 2L), succeeded,
                        "both already-submitted seeds drain even though abandon fired before either finished");
            } finally {
                pool.shutdownNow();
            }
        }

        private void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
