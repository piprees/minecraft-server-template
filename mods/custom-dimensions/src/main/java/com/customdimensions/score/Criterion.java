package com.customdimensions.score;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.SeedFacts;

/**
 * One named, separately arguable judgement about a seed.
 *
 * <p>The thing this replaces is a weighted mean of four components whose
 * meaning was an emergent property of six interacting constant tables. Nobody
 * could hold that in their head and nothing tested it. A criterion is a class
 * with a name, a stated target, and a result that carries the facts it was
 * computed from — so a disagreement about scoring is a disagreement about one
 * function, and a pull request can settle it.
 *
 * <p>A criterion NEVER reads a world, a registry or a seed. It reads facts. If
 * a criterion needs something the facts layer does not measure, the answer is
 * a new fact, not a lookup — otherwise the measurement and the judgement drift
 * apart, which is the whole disease being treated.
 */
public interface Criterion {

    /** Stable id, used in artefacts and diffs. Never rendered to a player. */
    String id();

    /**
     * Which of the mission's five words this serves. Grouping by the
     * vocabulary the brief actually uses is what makes the model legible:
     * "challenge" is a heading a human can argue with, "component 3" is not.
     */
    Group group();

    /**
     * Which of the two questions this answers.
     *
     * <p>They are different questions and a single mean of both cannot be
     * read: "did this seed do what THIS dimension's config asked for" and "is
     * this a good world at all" fail independently, and a seed that is
     * excellent at one and hopeless at the other is not a middling seed.
     *
     * <p>The split also fixes an arithmetic problem. A dimension's configured
     * wants number six or seven; the general criteria number about the same
     * today and would not tomorrow. Pooling them lets whichever tier happens
     * to hold more criteria decide the headline. Scoring each tier out of its
     * own ceiling and averaging the two makes intent worth half, whatever
     * either tier is made of.
     */
    default Tier tier() {
        return Tier.GENERAL;
    }

    /** What this criterion wants, in one sentence, for the report. */
    String target(DimensionConfig def);

    /**
     * Whether this criterion applies to this dimension, from config alone.
     *
     * <p>This is what makes the ceiling comparable. The ceiling is the count of
     * criteria that return true here, so every seed of a dimension is judged
     * out of the same denominator and two seeds' percentages can be ranked
     * against each other. If applicability were decided from the facts, a seed
     * that happened to leave a fact unmeasured would quietly shrink its own
     * denominator and score higher for having been measured less.
     *
     * <p>Consequence: a criterion that applies but whose input fact is absent
     * is NOT inapplicable. It scores zero and says the fact was not measured.
     */
    default boolean applicable(DimensionConfig def) {
        return true;
    }

    /**
     * Whether this is a gate rather than a graded criterion.
     *
     * <p>A gate is pass/fail and contributes to neither the achieved total nor
     * the ceiling — failing it rejects the seed outright, so there is no mark to
     * award for clearing it. A criterion like {@code namesake}, which every
     * seed either clears or fails outright, belongs here rather than occupying
     * a share of the scale while ranking nothing.
     */
    default boolean gate() {
        return false;
    }

    /**
     * The range this criterion wants, in BLOCKS from spawn, or null where its
     * question is not a distance.
     *
     * <p>Declared on the criterion rather than carried on a result, because a
     * GATE answers {@link Result.Pass}/{@link Result.Fail} and neither holds a
     * band — yet "a fortress within 512 blocks" is exactly the kind of radius
     * the map should draw. A graded criterion may still supply a per-seed band
     * on its {@link Result.Score}, which wins over this one.
     *
     * <p>A fraction, a density and a clustering figure have no radius. Leave
     * them null rather than inventing a plausible one.
     */
    default double[] band(DimensionConfig def) {
        return null;
    }

    /** Judge one seed. Must not throw: an error is an {@link Result.NotApplicable}. */
    Result evaluate(SeedFacts facts, DimensionConfig def);

    enum Group {
        THEME, INTEREST, CHALLENGE, APPROPRIATE, FUN
    }

    /**
     * {@code CONFIGURED} reads a field the dimension's author wrote and asks
     * whether the seed delivered it. {@code GENERAL} asks whether this is a
     * good world, and would ask the same of a dimension with an empty config.
     */
    enum Tier {
        CONFIGURED, GENERAL
    }

    /**
     * What a criterion concluded.
     *
     * <p>Four shapes. {@link NotApplicable} says "this question does not apply
     * here", which is different from scoring zero. A void dimension with no
     * structures should not be marked down for having no landmarks — it
     * should be not asked.
     */
    sealed interface Result {

        /** A gate this seed clears. Contributes nothing to the score. */
        record Pass(String evidence) implements Result {
        }

        /** A gate this seed fails. The seed is REJECTED, with this reason. */
        record Fail(String reason, String evidence) implements Result {
        }

        /**
         * A graded judgement in 0..1, with the facts that produced it.
         *
         * <p>{@code band} is the range this criterion wanted, in BLOCKS from
         * spawn, or null where the question is not a distance — a biome
         * share is a fraction and has no radius. It exists so the map can
         * draw what a criterion asked for; nothing scores from it.
         */
        record Score(double value, String evidence, double[] band) implements Result {
            public Score {
                if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
                    throw new IllegalArgumentException(
                            "a criterion score is a finite 0..1, got " + value);
                }
                if (band != null && band.length != 2) {
                    throw new IllegalArgumentException(
                            "a band is [min, max] in blocks, got " + band.length + " values");
                }
            }

            /** A criterion whose question has no distance behind it. */
            public Score(double value, String evidence) {
                this(value, evidence, null);
            }
        }

        /**
         * This criterion cannot apply to this dimension.
         *
         * <p>Excluded from the score AND from the ceiling, so a dimension is
         * never marked down for a question that was never asked of it. Scoring
         * an inapplicable criterion zero instead would be a permanent
         * deduction that ranks nothing and reads as ordinary bad luck.
         *
         * <p>Only ever returned when {@link Criterion#applicable} is false for
         * the same config — the scorer treats a mismatch as a criterion bug and
         * grades it zero rather than letting one seed shrink its denominator.
         */
        record NotApplicable(String reason) implements Result {
        }

        /**
         * The criterion applies, but the fact it needs was not measured.
         *
         * <p>Counted in the ceiling and worth zero. Exact-or-absent cuts both
         * ways: an absent measurement is not evidence of quality, so
         * it cannot be silently forgiven, and it is not the criterion's fault
         * either, so it is reported as unmeasured rather than as a failure.
         */
        record Unmeasured(String reason) implements Result {
        }
    }
}
