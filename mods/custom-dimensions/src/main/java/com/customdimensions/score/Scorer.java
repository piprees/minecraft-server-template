package com.customdimensions.score;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.SeedFacts;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every criterion over one seed's facts and produces a {@link Scorecard}.
 *
 * <p>Four rules, and each replaces a specific thing the weighted mean got
 * wrong:
 *
 * <ol>
 *   <li><b>A failed gate rejects the seed outright.</b> It is not a deduction
 *       to be bought back elsewhere. A weighted mean says every deficiency is
 *       purchasable with a surplus, so a dead structure list can still score
 *       highly on the strength of everything else rather than being flagged.</li>
 *   <li><b>A criterion that does not apply is excluded from BOTH the score and
 *       the ceiling.</b> Not scored zero. A void dimension is not marked down
 *       for having no landmarks — it is not asked.</li>
 *   <li><b>A criterion that applies but could not be measured is excluded the
 *       same way.</b> "Measurements are exact or absent with a reason" means
 *       an absent one is not a zero either: recording it as one silently
 *       understates a candidate whose measurement partly failed, which then
 *       scores BELOW one that measured genuinely badly with no way to tell
 *       the two apart from the percentage alone.</li>
 *   <li><b>The headline is achieved/ceiling</b>, where the ceiling is the sum
 *       of what was actually measured among the applicable criteria. 100%
 *       means "as good as this dimension gets on what could be measured" and
 *       is reachable.</li>
 * </ol>
 *
 * <p>Every criterion is weighted 1. That is a deliberate starting point, not
 * an oversight: a hand-fitted weight is an unverifiable guess, and nothing
 * here has labelled data yet to justify one criterion outweighing another.
 * Equal weight is the honest default until that changes.
 */
public final class Scorer {

    private Scorer() {
    }

    public static Scorecard score(SeedFacts facts, DimensionConfig def,
                                  List<Criterion> criteria) {
        List<Scorecard.Entry> entries = new ArrayList<>();
        double achieved = 0.0;
        double ceiling = 0.0;
        int graded = 0;
        String failedGate = null;
        String failedGateReason = null;

        for (Criterion c : criteria) {
            String target = safeTarget(c, def);
            boolean applies = safeApplicable(c, def);

            if (!applies) {
                entries.add(new Scorecard.Entry(c.id(), c.group(), target,
                        "not_applicable", null,
                        "this dimension's config does not pose this question"));
                continue;
            }

            Criterion.Result r;
            try {
                r = c.evaluate(facts, def);
            } catch (RuntimeException e) {
                // A criterion that throws is a bug in the criterion, not a
                // verdict about the seed — but it applies, so it stays in the
                // ceiling and scores zero rather than shrinking the denominator.
                r = new Criterion.Result.Unmeasured(
                        "the criterion threw " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }

            if (r instanceof Criterion.Result.Pass p) {
                entries.add(new Scorecard.Entry(c.id(), c.group(), target,
                        "pass", null, p.evidence()));
            } else if (r instanceof Criterion.Result.Fail f) {
                entries.add(new Scorecard.Entry(c.id(), c.group(), target,
                        "fail", null, f.reason() + " (" + f.evidence() + ")"));
                if (failedGate == null) {
                    failedGate = c.id();
                    failedGateReason = f.reason();
                }
            } else if (r instanceof Criterion.Result.Score s) {
                entries.add(new Scorecard.Entry(c.id(), c.group(), target,
                        "score", s.value(), s.evidence(), s.band()));
                achieved += s.value();
                ceiling += 1.0;
                graded++;
            } else if (r instanceof Criterion.Result.Unmeasured u) {
                // Excluded from BOTH achieved and ceiling, the same shape as
                // not_applicable: an absent measurement is not evidence of
                // quality, so it cannot count against the denominator any
                // more than it can count for the numerator.
                entries.add(new Scorecard.Entry(c.id(), c.group(), target,
                        "unmeasured", null, u.reason()));
            } else if (r instanceof Criterion.Result.NotApplicable na) {
                // applicable(def) said yes and evaluate said no. The two
                // disagree, so the criterion is wrong about itself; grade it
                // zero rather than let this seed be judged out of fewer marks
                // than its siblings.
                entries.add(new Scorecard.Entry(c.id(), c.group(), target,
                        "unmeasured", c.gate() ? null : 0.0,
                        "criterion bug: applicable(config) is true but it "
                        + "returned not-applicable — " + na.reason()));
                if (!c.gate()) {
                    ceiling += 1.0;
                }
            }
        }

        if (failedGate != null) {
            return new Scorecard(facts.dimension(), facts.seed(),
                    Scorecard.Verdict.REJECTED,
                    failedGate + ": " + failedGateReason,
                    achieved, ceiling, entries);
        }
        // Checked from config alone, not the accumulated (now measured-only)
        // `ceiling` above: that variable is 0.0 whenever nothing was measured
        // this seed even though the config poses real questions, and the two
        // INVALID_CONFIG reasons below would be indistinguishable otherwise.
        double configCeiling = ceiling(def, criteria);
        if (configCeiling <= 0.0) {
            // The config asks no graded question at all. That is not a score of
            // zero — it is "nothing here was measurable", and saying so is the
            // whole point of keeping the three verdicts apart.
            return new Scorecard(facts.dimension(), facts.seed(),
                    Scorecard.Verdict.INVALID_CONFIG,
                    "no criterion could be applied to this dimension — check "
                    + "customdim lint before rolling it",
                    0.0, 0.0, entries);
        }
        if (graded == 0) {
            // Criteria applied and every one of them came back unmeasured. A 0%
            // here would rank this seed below a genuinely poor one that WAS
            // measured, which is a lie about which is worse. The reported
            // ceiling is what the config poses, not the (here, zero) measured
            // total — a seed that measured nothing must not look denominator-
            // free, or it silently escapes the ceiling-stability guarantee
            // every other seed of this dimension is held to.
            return new Scorecard(facts.dimension(), facts.seed(),
                    Scorecard.Verdict.INVALID_CONFIG,
                    "every applicable criterion came back unmeasured — read the "
                    + "facts artefact and customdim lint before rolling this",
                    0.0, configCeiling, entries);
        }
        return new Scorecard(facts.dimension(), facts.seed(),
                Scorecard.Verdict.SCORED, "", achieved, ceiling, entries);
    }

    /**
     * The ceiling for a dimension, from config alone — no seed, no facts.
     * Computable without seed data and stable across runs: the scorer's own
     * ceiling must equal it for every seed, and a test asserts so.
     */
    public static double ceiling(DimensionConfig def, List<Criterion> criteria) {
        double ceiling = 0.0;
        for (Criterion c : criteria) {
            if (!c.gate() && safeApplicable(c, def)) {
                ceiling += 1.0;
            }
        }
        return ceiling;
    }

    private static boolean safeApplicable(Criterion c, DimensionConfig def) {
        try {
            return c.applicable(def);
        } catch (RuntimeException e) {
            // A criterion that cannot decide whether it applies is applicable:
            // the alternative silently removes marks from the denominator.
            return true;
        }
    }

    private static String safeTarget(Criterion c, DimensionConfig def) {
        try {
            return c.target(def);
        } catch (RuntimeException e) {
            return "(target unavailable: " + e.getClass().getSimpleName() + ")";
        }
    }
}
