package com.customdimensions.roll;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.InputHash;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.FactsEngine;
import com.customdimensions.facts.SeedFacts;
import com.customdimensions.score.Criteria;
import com.customdimensions.score.Scorecard;
import com.customdimensions.score.Scorer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * The search: draw random seeds for one dimension, measure and score each
 * with the mod's own {@link FactsEngine} and {@link Scorer}, and hand every
 * new outcome to a {@link Sink}.
 *
 * <p>{@link #roll} touches no filesystem and no Fabric API — persistence is
 * entirely the {@link Sink}'s concern, which is what makes the search loop
 * itself testable with a stub measurer and no server. {@link #rollDimension}
 * is the live wiring: it persists through {@link SeedBank}.
 *
 * <p>{@link FactsEngine#measure} builds its rig headlessly — no
 * {@code ServerWorld} is created and no chunk is loaded — so a roll never
 * touches the tick loop or the world the server is actually running. A large
 * budget still runs on the calling thread for its whole duration, the same
 * as {@code spike-bench}; keep counts modest on a live server.
 */
public final class Roller {

    private Roller() {
    }

    /** What one seed came back as. */
    public record Draw(long seed, Scorecard.Verdict verdict, double achieved, double ceiling) {
    }

    /** Measures and scores one seed. The only Fabric-dependent seam in the pure loop. */
    public interface Measurer {
        Draw measure(long seed);
    }

    /** What a roll does with each newly-measured outcome — the persistence seam. */
    public interface Sink {
        void scored(long seed, double achieved, double ceiling);

        void rejected(long seed);
    }

    /**
     * An explicit stopping point for the search — a seed count, a wall-clock
     * deadline, or both, whichever binds first. {@code attempts} counts every
     * draw, including one skipped because it was already tried, so a seed
     * source that never offers anything new still terminates.
     */
    public record Budget(int maxSeeds, long deadlineNanos) {

        public static Budget seeds(int n) {
            return new Budget(n, Long.MAX_VALUE);
        }

        public static Budget wallClock(Duration d) {
            return new Budget(Integer.MAX_VALUE, System.nanoTime() + d.toNanos());
        }

        public boolean exceeded(int attempts) {
            return attempts >= this.maxSeeds || System.nanoTime() >= this.deadlineNanos;
        }
    }

    /** How many seeds one roll actually measured, and how each verdict split. */
    public record RollResult(int measured, int scored, int rejected) {
    }

    /**
     * Draws seeds from {@code seeds} until {@code budget} is spent, skipping
     * any {@code alreadyTried} reports true for and measuring the rest with
     * {@code measurer}. Every new outcome is handed to {@code sink} exactly
     * once, immediately after it is measured.
     *
     * @return how many new seeds were actually measured (scored + rejected)
     */
    public static int roll(LongPredicate alreadyTried, LongSupplier seeds,
                           Measurer measurer, Sink sink, Budget budget) {
        int attempts = 0;
        int measured = 0;
        while (!budget.exceeded(attempts)) {
            attempts++;
            long seed = seeds.getAsLong();
            if (alreadyTried.test(seed)) {
                continue;
            }
            Draw draw = measurer.measure(seed);
            measured++;
            if (draw.verdict() == Scorecard.Verdict.SCORED) {
                sink.scored(seed, draw.achieved(), draw.ceiling());
            } else {
                sink.rejected(seed);
            }
        }
        return measured;
    }

    /**
     * The live wiring: an in-memory index of every seed {@link SeedBank}
     * already has on disk for {@code dimensionId} (read once, not per draw),
     * a sink that persists each new outcome as it happens, and
     * {@link FactsEngine}/{@link Scorer} doing the actual measuring.
     *
     * <p>The measurer stashes the facts and scorecard it just built in a
     * one-slot holder; the sink below reads them straight back out instead of
     * measuring twice, which is safe because {@link #roll} always calls the
     * sink immediately after the measurer for the same seed, on one thread.
     */
    public static RollResult rollDimension(MinecraftServer server, Identifier dimensionId,
                                           DimensionConfig def, int count) {
        String dimension = dimensionId.toString();
        String inputHash = InputHash.of(def, server);
        Set<Long> tried = new LinkedHashSet<>(SeedBank.alreadyTriedSeeds(inputHash, dimension));
        Random random = new Random();

        SeedFacts[] lastFacts = new SeedFacts[1];
        Scorecard[] lastCard = new Scorecard[1];
        Measurer measurer = seed -> {
            SeedFacts facts = FactsEngine.measure(server, dimensionId, seed);
            Scorecard card = Scorer.score(facts, def, Criteria.all());
            lastFacts[0] = facts;
            lastCard[0] = card;
            return new Draw(seed, card.verdict(), card.achieved(), card.ceiling());
        };

        int[] scoredCount = {0};
        int[] rejectedCount = {0};
        Sink sink = new Sink() {
            @Override
            public void scored(long seed, double achieved, double ceiling) {
                try {
                    SeedBank.writeCandidate(dimension, seed, lastFacts[0], lastCard[0], inputHash);
                    scoredCount[0]++;
                } catch (IOException e) {
                    MultiverseServer.LOGGER.error("Failed to write candidate {} for {}",
                            seed, dimension, e);
                }
                tried.add(seed);
            }

            @Override
            public void rejected(long seed) {
                try {
                    SeedBank.appendRejected(inputHash, dimension, seed);
                    rejectedCount[0]++;
                } catch (IOException e) {
                    MultiverseServer.LOGGER.error("Failed to record rejected seed {} for {}",
                            seed, dimension, e);
                }
                tried.add(seed);
            }
        };

        int measured = roll(seed -> tried.contains(seed), random::nextLong, measurer, sink, Budget.seeds(count));
        return new RollResult(measured, scoredCount[0], rejectedCount[0]);
    }

    /**
     * Whether a dimension is worth rolling at all, from config alone. A
     * superflat dimension has no organic placement to score against; a void
     * dimension without a biome list has nothing seed-dependent in it either
     * — everything else is fair game, including a dimension whose own
     * {@code seedRoll.skip} opts out explicitly.
     */
    public static boolean rollable(DimensionConfig def) {
        DimensionConfig.SeedRoll sr = def.getSeedRoll();
        if (sr != null && Boolean.TRUE.equals(sr.skip)) {
            return false;
        }
        String type = def.getType();
        if ("superflat".equalsIgnoreCase(type)) {
            return false;
        }
        if ("void".equalsIgnoreCase(type)) {
            java.util.List<String> biomes = def.getBiomes();
            return biomes != null && !biomes.isEmpty();
        }
        return true;
    }
}
