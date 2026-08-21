package com.customdimensions.roll;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.InputHash;
import com.customdimensions.command.SpikeSampler;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * The search: draw random seeds for one dimension, measure and score each
 * with the mod's own {@link FactsEngine} and {@link Scorer}, and hand every
 * new outcome to a {@link Sink}.
 *
 * <p>{@link #roll} touches no filesystem and no Fabric API — persistence is
 * entirely the {@link Sink}'s concern, which is what makes the search loop
 * itself testable with a stub measurer and no server. {@link #screenShortlist}
 * and {@link #measureShortlist} are the live wiring: a cheap tier-1 sweep over
 * a pool of {@code count} seeds narrows to {@link #SHORTLIST}, then {@link
 * #measureOne} runs on each survivor — both tiers submit through the same
 * {@link #fanOut}, on the same caller-supplied pool, and both persist through
 * {@link SeedBank}.
 *
 * <p>{@link FactsEngine#measure} builds its rig headlessly — no
 * {@code ServerWorld} is created and no chunk is loaded — so a roll never
 * touches the tick loop or the world the server is actually running.
 * {@code web/RollPipeline} orchestrates dimensions on their own threads and
 * shares one bounded pool across all of them for the actual seed
 * measurement ({@link #screenShortlist}), so the server stays playable
 * throughout. Per-call state ({@code tried}, the draw {@link Random}, the
 * shortlist) is touched only by the calling thread — see {@link #screenShortlist}.
 *
 * <p>Rendering is NOT part of the search. A map costs orders of magnitude
 * more than a measurement and only the top of a board is ever opened, so
 * {@code web/RenderQueue} reconciles against the leaderboard beside the roll
 * rather than every scored seed paying for a picture on its way past.
 */
public final class Roller {

    private Roller() {
    }

    /**
     * What one seed came back as.
     *
     * @param verdictReason why a non-SCORED verdict landed — the gate that
     *                      rejected it. Carried through the pure loop so the
     *                      sink can persist it: the scorecard that knew the
     *                      answer is gone by the time the sink runs, and a
     *                      bank of bare seed numbers cannot be asked which
     *                      gate did it.
     */
    public record Draw(long seed, Scorecard.Verdict verdict, double achieved, double ceiling,
                       String verdictReason) {
    }

    /** Measures and scores one seed. The only Fabric-dependent seam in the pure loop. */
    public interface Measurer {
        Draw measure(long seed);
    }

    /** What a roll does with each newly-measured outcome — the persistence seam. */
    public interface Sink {
        void scored(long seed, double achieved, double ceiling);

        void rejected(long seed, String reason);
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
        return roll(alreadyTried, seeds, measurer, sink, budget, () -> false);
    }

    /**
     * The same, abandoning partway when {@code abandonIf} turns true.
     *
     * <p>A tier-1 screen is thousands of seeds and minutes long, so the budget
     * alone is far too coarse a place to notice that somebody cancelled the
     * run or opened a different dimension. Checked once per seed, which costs
     * nothing beside a measurement and bounds the wait at one.
     */
    public static int roll(LongPredicate alreadyTried, LongSupplier seeds,
                           Measurer measurer, Sink sink, Budget budget,
                           java.util.function.BooleanSupplier abandonIf) {
        int attempts = 0;
        int measured = 0;
        while (!budget.exceeded(attempts) && !abandonIf.getAsBoolean()) {
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
                sink.rejected(seed, draw.verdictReason());
            }
        }
        return measured;
    }

    /**
     * The same draw/measure/sink shape as {@link #roll}, but {@code
     * measurer} runs on {@code pool} — up to {@code parallelism} seeds in
     * flight at once — instead of the calling thread. {@code pool} is
     * shared with every other dimension currently screening OR measuring
     * tier 2 (see {@link #measureShortlist}), so it is the one thing that
     * bounds TOTAL seed-measurement concurrency across all of them; this
     * method only ever asks it for up to {@code parallelism} seeds of its
     * own. A measurement that throws aborts the whole sweep — the same
     * seam {@link #screenShortlist}'s caller already catches around a tier-1
     * failure — see {@link #fanOut} for the shared window mechanics.
     *
     * <p>Sink calls land in DRAW order, never completion order: a future is
     * only applied once every future submitted before it has been applied,
     * so {@link TopN}'s tie-break (draw order) and {@code screened}'s
     * insertion order come out identical to what {@link #roll} would have
     * produced for the same drawn sequence, whatever order the pool
     * actually finished the measurements in.
     *
     * <p>{@code alreadyTried} alone cannot catch a seed drawn twice while
     * its first draw is still in flight — the sink has not run yet, so
     * nothing has told {@code alreadyTried} about it. {@code inFlight}
     * covers that gap, so a duplicate draw is skipped exactly as {@link
     * #roll}'s fully sequential loop would skip it.
     */
    private static int rollParallel(LongPredicate alreadyTried, LongSupplier seeds,
                                    Measurer measurer, Sink sink, Budget budget,
                                    java.util.function.BooleanSupplier abandonIf,
                                    ExecutorService pool, int parallelism,
                                    Runnable onMeasured) {
        return fanOut(alreadyTried, seeds, measurer, budget, abandonIf, pool, parallelism,
                draw -> {
                    onMeasured.run();
                    if (draw.verdict() == Scorecard.Verdict.SCORED) {
                        sink.scored(draw.seed(), draw.achieved(), draw.ceiling());
                    } else {
                        sink.rejected(draw.seed(), draw.verdictReason());
                    }
                },
                (seed, e) -> {
                    throw e;
                });
    }

    /** One in-flight measurement: which seed it is for, so a failure can be logged against it. */
    private record Pending(long seed, Future<Draw> future) {
    }

    /**
     * The windowed fan-out itself, shared by {@link #rollParallel} (tier 1)
     * and {@link #measureShortlist} (tier 2): submits up to {@code
     * parallelism} measurements from {@code seeds} to {@code pool} at once
     * and drains the front of the window as each completes, in draw order.
     * Stops SUBMITTING the moment {@code budget} is spent or {@code
     * abandonIf} turns true; whatever is already in flight is drained, not
     * abandoned mid-measurement — a candidate's write must never be left
     * half-done. Callers differ only in how a seed is sourced and what a
     * per-seed failure means for the rest of the run: {@code onFailure} may
     * re-throw to abort (tier 1) or log and let the loop carry on to the
     * next seed (tier 2) — either way {@code onSuccess} is never called for
     * the seed that failed.
     *
     * @return how many seeds {@code onSuccess} was called for
     */
    static int fanOut(LongPredicate alreadyTried, LongSupplier seeds, Measurer measurer,
                      Budget budget, java.util.function.BooleanSupplier abandonIf,
                      ExecutorService pool, int parallelism,
                      java.util.function.Consumer<Draw> onSuccess,
                      java.util.function.BiConsumer<Long, RuntimeException> onFailure) {
        int attempts = 0;
        int measured = 0;
        boolean interrupted = false;
        Set<Long> inFlight = new LinkedHashSet<>();
        Deque<Pending> window = new ArrayDeque<>();
        try {
        while (true) {
            while (window.size() < parallelism && !budget.exceeded(attempts)
                    && !abandonIf.getAsBoolean()) {
                attempts++;
                long seed = seeds.getAsLong();
                if (alreadyTried.test(seed) || !inFlight.add(seed)) {
                    continue;
                }
                window.addLast(new Pending(seed, pool.submit(() -> measurer.measure(seed))));
            }
            if (window.isEmpty()) {
                break;
            }
            Pending pending = window.pollFirst();
            Draw draw;
            try {
                draw = pending.future().get();
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            } catch (ExecutionException e) {
                inFlight.remove(pending.seed());
                RuntimeException re = e.getCause() instanceof RuntimeException r
                        ? r : new RuntimeException(e.getCause());
                onFailure.accept(pending.seed(), re);
                continue;
            }
            inFlight.remove(draw.seed());
            measured++;
            onSuccess.accept(draw);
        }
        } finally {
            settle(window);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return measured;
    }

    /**
     * Waits out whatever is still running, discarding results. A measurement
     * writes its candidate before it returns, so a caller that leaves the loop
     * early — cancelled, interrupted, or carrying an exception — must not read
     * the bank until those writes have landed.
     */
    private static void settle(Deque<Pending> window) {
        while (!window.isEmpty()) {
            Pending pending = window.pollFirst();
            try {
                pending.future().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | RuntimeException ignored) {
                // Already failed or already reported; the write is what matters.
            }
        }
    }

    /** How many tier-1 survivors a shortlist carries into a full tier-2 measurement. */
    public static final int SHORTLIST = 10;

    /**
     * What one tier-1 screen produced.
     *
     * <p>{@code screened} is how many seeds were actually measured, which is
     * NOT the pool size: a screen that is cancelled, or told to yield to a
     * dimension somebody has just opened, stops early and measures fewer. The
     * two were conflated once and a screen that measured nothing reported the
     * whole pool, which hid the reason every board came back empty.
     */
    public record Screen(List<Long> shortlist, int screened, int survivors) {
    }

    /**
     * A rank tier 1 assigned one seed, and the order it was drawn in — the
     * tie-break, so a fixed draw sequence always shortlists the same set in
     * the same order.
     */
    private record Ranked(long seed, double rank, int order) {
    }

    private static int compareRanked(Ranked a, Ranked b) {
        int byRank = Double.compare(b.rank(), a.rank());   // descending: best first
        return byRank != 0 ? byRank : Integer.compare(a.order(), b.order());
    }

    /**
     * A bounded top-N by descending rank. Re-sorts on the rare insert that
     * grows past capacity rather than keeping a heap — capacity is {@link
     * #SHORTLIST}, ten, so the whole list is cheaper to re-sort each time
     * than a heap is to code correctly for a size this small. Not
     * thread-safe: one per {@link #screenShortlist} call, and {@code add} is
     * only ever called from the thread running that call — seed measurement
     * itself may run on a shared pool, but every {@code add} is applied back
     * on the caller in draw order, never from a pool thread.
     */
    static final class TopN {
        private final int capacity;
        private final List<Ranked> entries = new ArrayList<>();
        private int seq;

        TopN(int capacity) {
            this.capacity = capacity;
        }

        void add(long seed, double rank) {
            entries.add(new Ranked(seed, rank, seq++));
            if (entries.size() > capacity) {
                entries.sort(Roller::compareRanked);
                entries.remove(entries.size() - 1);
            }
        }

        /** Every seed added, best rank first, ties broken by draw order. */
        List<Long> bestFirst() {
            List<Ranked> sorted = new ArrayList<>(entries);
            sorted.sort(Roller::compareRanked);
            List<Long> out = new ArrayList<>(sorted.size());
            for (Ranked r : sorted) {
                out.add(r.seed());
            }
            return out;
        }
    }

    /**
     * TIER 1: screens up to {@code count} fresh seeds — {@code count} is now
     * genuinely the pool size, not a ceiling that never bound — through
     * {@link FactsEngine#measureCheap} and the dimension's real {@link
     * com.customdimensions.score.Criterion criteria}, and returns the best
     * {@link #SHORTLIST} by coarse rank, best first.
     *
     * <p>A seed whose cheap facts already fail a hard gate ({@code
     * fortress_reachable_in_nether}, {@code end_city_reachable_in_end}) is
     * rejected here, PERMANENTLY, and never reaches tier 2: both gates read
     * only {@link com.customdimensions.facts.SeedFacts.StructureFacts},
     * which this tier measures in full (the real {@link
     * FactsEngine#measure}'s own structure pass — no NoiseConfig, pure
     * arithmetic through {@code NoiseStructurePlacement}'s seeded noise field
     * and vanilla's own random-spread grid), so a full measurement could
     * never change the verdict a fuller one already reached exactly. This is
     * the "fail faster" tier 2 would otherwise need its own reordering for —
     * subsumed here, because nothing a seed does at tier 2 alters what its
     * structures already are at tier 1.
     *
     * <p>Every surviving seed is ranked by {@link Scorecard#percentage()}
     * over whatever this tier could cheaply measure (structures, spawn
     * biome, near-spawn biome edges) — the SAME criteria tier 2 will apply,
     * over a smaller set of facts, so the rank is a genuine partial reading
     * of the same scorecard rather than a heuristic standing in for it.
     * Deterministic: the same seed against the same config always gets the
     * same cheap facts and the same rank.
     *
     * <p>The sweep itself runs on {@code measurePool} — up to {@code
     * parallelism} seeds measured at once, shared with every other
     * dimension currently screening (see {@link #rollParallel}) — while the
     * seed draw, the gate check and every shortlist mutation stay on the
     * calling thread, so the result is identical to a fully sequential
     * sweep of the same drawn sequence. {@link SpikeSampler.Base} and
     * everything {@link FactsEngine#measureCheap} reads from it are safe to
     * share across those threads: {@code base} carries only the seed-
     * independent generator/biome-source/registry lookups, each seed's own
     * {@link SpikeSampler.Rig} (built fresh inside {@code measureCheap} via
     * {@link SpikeSampler#forSeedClimate}) is never shared, and {@code
     * criteria}, {@code def} and the jar-baked registries
     * ({@code StructureGroupRegistry}, {@code MultiverseConfig}) are read-only
     * for the run's duration — the same sharing rule {@link CandidateRender}
     * already relies on for its own per-worker rigs over one shared
     * {@code Base}.
     */
    public static Screen screenShortlist(MinecraftServer server, Identifier dimensionId,
                                             DimensionConfig def, int count,
                                             java.util.function.BooleanSupplier abandonIf,
                                             ExecutorService measurePool, int parallelism,
                                             Runnable onMeasured) {
        String dimension = dimensionId.toString();
        String inputHash = InputHash.of(def, server);
        Set<Long> tried = new LinkedHashSet<>(SeedBank.alreadyTriedSeeds(inputHash, dimension));
        Random random = new Random();
        java.util.List<com.customdimensions.score.Criterion> criteria = Criteria.forConfig(def);
        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);

        TopN shortlist = new TopN(SHORTLIST);
        // Every survivor's rank, kept for one write at the end. Without it the
        // only trace of how a shortlist was chosen is the order tier 2 happened
        // to measure it in, and the pool's score distribution — the thing
        // sizing the pool has to be argued from — is gone entirely.
        Map<Long, Double> screened = new LinkedHashMap<>();
        Measurer tier1 = seed -> {
            SeedFacts cheap = FactsEngine.measureCheap(server, dimensionId, def, base, seed);
            Scorecard card = Scorer.score(cheap, def, criteria);
            boolean hardFail = card.verdict() == Scorecard.Verdict.REJECTED;
            Double pct = card.percentage();
            double rank = hardFail || pct == null ? 0.0 : pct;
            return new Draw(seed, hardFail ? Scorecard.Verdict.REJECTED : Scorecard.Verdict.SCORED,
                    rank, 100.0, card.verdictReason());
        };
        Sink sink = new Sink() {
            @Override
            public void scored(long seed, double achieved, double ceiling) {
                shortlist.add(seed, achieved);
                screened.put(seed, achieved);
                tried.add(seed);
            }

            @Override
            public void rejected(long seed, String reason) {
                try {
                    SeedBank.appendRejected(inputHash, dimension, seed, reason);
                } catch (IOException e) {
                    MultiverseServer.LOGGER.error(
                            "Failed to record tier-1 rejected seed {} for {}", seed, dimension, e);
                }
                tried.add(seed);
            }
        };
        int screenedCount = rollParallel(seed -> tried.contains(seed), random::nextLong, tier1, sink,
                Budget.seeds(count), abandonIf, measurePool, Math.max(1, parallelism),
                onMeasured);
        // A screen that measured nothing has nothing to say about the pool, and
        // the record it would write is indistinguishable from one whose every
        // seed was rejected. Writing it destroys the previous screen's ranks,
        // which are the only account of how the pool scored.
        if (screenedCount > 0) {
            try {
                SeedBank.writeScreened(inputHash, dimension, screened);
            } catch (IOException e) {
                MultiverseServer.LOGGER.error(
                        "Failed to record the tier-1 screen for {}", dimension, e);
            }
        }
        return new Screen(shortlist.bestFirst(), screenedCount, screened.size());
    }

    /**
     * TIER 2: the full measurement and score for ONE shortlisted seed —
     * {@link FactsEngine#measure} and {@link Scorer#score}, unweakened and
     * unreordered, the same pair every candidate in the bank was always
     * judged by. Writes the result to {@link SeedBank} whatever the
     * verdict, and returns it so a caller can log or assert against it.
     */
    public static Draw measureOne(MinecraftServer server, Identifier dimensionId,
                                  DimensionConfig def, long seed) {
        String dimension = dimensionId.toString();
        String inputHash = InputHash.of(def, server);
        java.util.List<com.customdimensions.score.Criterion> criteria = Criteria.forConfig(def);
        SeedFacts facts = FactsEngine.measure(server, dimensionId, seed);
        Scorecard card = Scorer.score(facts, def, criteria);
        try {
            if (card.verdict() == Scorecard.Verdict.SCORED) {
                SeedBank.writeCandidate(dimension, seed, facts, card, inputHash);
            } else {
                SeedBank.appendRejected(inputHash, dimension, seed, card.verdictReason());
            }
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to record seed {} for {}", seed, dimension, e);
        }
        return new Draw(seed, card.verdict(), card.achieved(), card.ceiling(), card.verdictReason());
    }

    /**
     * TIER 2, parallel: {@link #measureOne} for every seed in {@code
     * shortlist}, on {@code measurePool} — the SAME pool tier 1 screens on,
     * shared via {@link #fanOut} — up to {@code parallelism} in flight at
     * once, so total concurrent measurement work across both tiers, and
     * every dimension currently rolling, never exceeds one bound. A full
     * measurement is ~a hundred core-seconds on a modded dimension; running
     * one per dimension unbounded is heavy oversubscription on a machine
     * with far fewer cores than dimensions.
     *
     * <p>Stops SUBMITTING the moment {@code abandonIf} turns true —
     * cancelled, or yielding to a dimension somebody has just opened — and
     * drains whatever is already in flight rather than abandoning a
     * half-written candidate. A seed whose measurement throws is logged and
     * skipped; the rest of the shortlist still measures, so one bad seed
     * does not cost the dimension the others.
     *
     * @return how many seeds were genuinely measured (not counting one that
     *         threw), which may be fewer than {@code shortlist.size()} on
     *         an abandon
     */
    public static int measureShortlist(MinecraftServer server, Identifier dimensionId,
                                       DimensionConfig def, List<Long> shortlist,
                                       java.util.function.BooleanSupplier abandonIf,
                                       ExecutorService measurePool, int parallelism,
                                       Runnable onMeasured) {
        java.util.Iterator<Long> it = shortlist.iterator();
        return fanOut(seed -> false, it::next,
                seed -> measureOne(server, dimensionId, def, seed),
                Budget.seeds(shortlist.size()), abandonIf, measurePool, Math.max(1, parallelism),
                draw -> onMeasured.run(),
                (seed, e) -> MultiverseServer.LOGGER.error(
                        "Tier-2 measurement failed for seed {} in {}", seed, dimensionId, e));
    }

    /**
     * Measures ONE named seed and banks it, whatever the verdict.
     *
     * <p>The search draws at random and only keeps what clears the gates,
     * which is right for finding candidates and useless for the seed a
     * dimension already has: that one is not a candidate, it is the world,
     * and a card saying UNROLLED beside a render of it answers nothing. It is
     * the point of comparison every other card is judged against, so it needs
     * the same scorecard they have.
     *
     * <p>A rejected one is written too. {@link Scorecard#percentage} is null
     * for any verdict but SCORED, and {@link SeedBank#parseSummary} drops a
     * candidate without one, so this can never enter a leaderboard or count
     * toward a roll target — it only gives the card its reasons.
     *
     * @return true when it measured, false when the bank already held it
     */
    public static boolean measureNamed(MinecraftServer server, Identifier dimensionId,
                                       DimensionConfig def, long seed) throws IOException {
        String dimension = dimensionId.toString();
        String inputHash = InputHash.of(def, server);
        if (java.nio.file.Files.isRegularFile(
                SeedBank.candidatePath(inputHash, dimension, seed))) {
            return false;
        }
        SeedFacts facts = FactsEngine.measure(server, dimensionId, seed);
        Scorecard card = Scorer.score(facts, def, Criteria.forConfig(def));
        SeedBank.writeCandidate(dimension, seed, facts, card, inputHash);
        return true;
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
