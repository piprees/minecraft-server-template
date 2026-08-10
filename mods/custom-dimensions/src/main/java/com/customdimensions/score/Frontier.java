package com.customdimensions.score;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The non-dominated set over a bank's scorecards, and what each member is
 * distinctively strong at.
 *
 * <p>The headline {@code achieved/ceiling} collapses onto whichever ceiling a
 * dimension's config produces — a small, repeated set of values across every
 * dimension the mod ships — so a percentage is that ceiling wearing a percent
 * sign, and two candidates at the same percentage can be good for unrelated
 * reasons the number cannot carry. This compares candidates PER CRITERION
 * instead: candidate A dominates candidate B when A is at least as good as B
 * on every criterion both were scored on, and strictly better on at least
 * one. Whatever no other candidate dominates is the frontier — genuinely
 * different good seeds, not the single maximum.
 *
 * <p>Only {@link Scorecard.Verdict#SCORED} cards are eligible.
 * {@code REJECTED} carries no comparable achievement and {@code
 * INVALID_CONFIG} carries none either; both are dropped before dominance is
 * even considered, the same way they are excluded from ranking today.
 *
 * <p>Comparison never crosses dimensions: candidates are grouped by {@link
 * Scorecard#dimension()} first, and the frontier is computed independently
 * within each group. A percentage from one dimension was never comparable to
 * another's either — this makes that explicit rather than assumed by the
 * caller.
 */
public final class Frontier {

    private Frontier() {
    }

    /**
     * One frontier member: the scorecard, plus the criterion ids where it
     * ties for the best value across the whole frontier — its distinctive
     * reasons to keep it. Empty when every criterion is tied across the
     * entire frontier; the member is still non-dominated, just not
     * distinguishable by this summary.
     */
    public record Member(Scorecard scorecard, List<String> strengths) {
    }

    /**
     * The frontier over every SCORED candidate in {@code candidates},
     * grouped by dimension. Order within a dimension's group follows the
     * input order; the groups themselves follow each dimension's first
     * appearance.
     */
    public static List<Member> of(List<Scorecard> candidates) {
        Map<String, List<Scorecard>> byDimension = new LinkedHashMap<>();
        for (Scorecard c : candidates) {
            if (c.verdict() == Scorecard.Verdict.SCORED) {
                byDimension.computeIfAbsent(c.dimension(), k -> new ArrayList<>()).add(c);
            }
        }
        List<Member> out = new ArrayList<>();
        for (List<Scorecard> group : byDimension.values()) {
            out.addAll(frontierOf(group));
        }
        return out;
    }

    private static List<Member> frontierOf(List<Scorecard> scored) {
        List<Scorecard> nonDominated = new ArrayList<>();
        for (Scorecard candidate : scored) {
            boolean dominated = scored.stream()
                    .anyMatch(other -> other != candidate && dominates(other, candidate));
            if (!dominated) {
                nonDominated.add(candidate);
            }
        }
        List<Member> members = new ArrayList<>();
        for (Scorecard candidate : nonDominated) {
            members.add(new Member(candidate, strengths(candidate, nonDominated)));
        }
        return members;
    }

    /**
     * True when {@code a} is at least as good as {@code b} on every criterion
     * both carry a comparable value for, and strictly better on at least
     * one. Two candidates that share no comparable criterion at all cannot
     * dominate each other in either direction — there is no evidence for it.
     */
    static boolean dominates(Scorecard a, Scorecard b) {
        Map<String, Double> av = values(a);
        Map<String, Double> bv = values(b);
        Set<String> common = new LinkedHashSet<>(av.keySet());
        common.retainAll(bv.keySet());
        boolean strictlyBetter = false;
        for (String id : common) {
            double x = av.get(id);
            double y = bv.get(id);
            if (x < y) {
                return false;
            }
            if (x > y) {
                strictlyBetter = true;
            }
        }
        return strictlyBetter;
    }

    /**
     * Criterion id -> comparable value. Only entries carrying a non-null
     * value qualify: {@code not_applicable} is always null (the config never
     * posed the question), and every gate outcome (pass/fail/unmeasured) is
     * also null (a gate is binary and a SCORED card never carries a failed
     * one) — none of these say anything about how GOOD a candidate is, so
     * treating a null as a zero would let an excluded or gated criterion
     * manufacture false dominance. An {@code unmeasured} graded criterion
     * already carries an explicit 0.0 from {@link Scorer} ("counted in the
     * ceiling and worth zero") and compares like any other score.
     */
    private static Map<String, Double> values(Scorecard card) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Scorecard.Entry e : card.entries()) {
            if (e.value() != null) {
                out.put(e.id(), e.value());
            }
        }
        return out;
    }

    /** Criterion ids where this candidate ties for the frontier-wide best value. */
    private static List<String> strengths(Scorecard candidate, List<Scorecard> frontier) {
        Map<String, Double> mine = values(candidate);
        Map<String, Double> best = new LinkedHashMap<>();
        for (Scorecard other : frontier) {
            for (Map.Entry<String, Double> e : values(other).entrySet()) {
                best.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : mine.entrySet()) {
            Double top = best.get(e.getKey());
            if (top != null && Math.abs(e.getValue() - top) < 1e-9) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
