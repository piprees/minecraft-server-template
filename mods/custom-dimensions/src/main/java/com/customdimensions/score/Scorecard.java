package com.customdimensions.score;

import com.customdimensions.facts.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every criterion concluded about one seed, and the one number that
 * follows from it.
 *
 * <p>Three verdicts, never blended:
 *
 * <ul>
 *   <li>{@code INVALID_CONFIG} — a human must edit the JSON. Not a seed
 *       problem, and not scoreable: a broken want must surface here rather
 *       than read as ordinary bad luck on every roll.</li>
 *   <li>{@code REJECTED} — this seed fails a hard gate, and the gate is
 *       named.</li>
 *   <li>{@code SCORED} — a percentage of what THIS dimension could achieve,
 *       plus per-criterion detail.</li>
 * </ul>
 *
 * <p>The headline is {@code achieved / ceiling}, where the ceiling counts
 * the criteria this dimension's CONFIG poses — computed with no seed data, so
 * every seed of a dimension is marked out of the same denominator and two
 * percentages can be ranked against each other. 100% means "as good as this
 * dimension gets" and is reachable, unlike a scale with an unknown maximum
 * where a genuinely strong seed can never see a number close to its top.
 */
public record Scorecard(
        String dimension,
        long seed,
        Verdict verdict,
        String verdictReason,
        double achieved,
        double ceiling,
        List<Entry> entries,
        Map<Criterion.Tier, Tally> tiers) {

    /** Backwards-shaped constructor for callers with no tier breakdown. */
    public Scorecard(String dimension, long seed, Verdict verdict, String verdictReason,
                     double achieved, double ceiling, List<Entry> entries) {
        this(dimension, seed, verdict, verdictReason, achieved, ceiling, entries, Map.of());
    }

    public enum Verdict {
        INVALID_CONFIG, REJECTED, SCORED
    }

    /** One tier's measured total and the ceiling it was measured against. */
    public record Tally(double achieved, double ceiling) {

        /** This tier out of 100, or absent when it posed no measurable question. */
        public Double percentage() {
            return ceiling <= 0.0 ? null : 100.0 * achieved / ceiling;
        }
    }

    /**
     * One criterion's conclusion, with the evidence that produced it.
     *
     * <p>{@code band} is the range it wanted in BLOCKS from spawn, or null
     * where the question is not a distance. The viewer draws it on the map;
     * nothing scores from it.
     */
    public record Entry(String id, Criterion.Group group, String target,
                        String outcome, Double value, String detail, double[] band) {

        /** A criterion whose question has no distance behind it. */
        public Entry(String id, Criterion.Group group, String target,
                     String outcome, Double value, String detail) {
            this(id, group, target, outcome, value, detail, null);
        }
    }

    /**
     * The one number on the card, out of 100, or absent when nothing applied.
     *
     * <p>The mean of the tiers that posed a measurable question, not
     * {@code achieved / ceiling} over the pooled criteria. A dimension
     * authoring seven wants and a spawn filter poses eight configured
     * questions against six general ones; pooling makes the wants worth 57%
     * of the headline for no reason but their count, and a dimension
     * authoring one want makes them worth 14%. Averaging the tiers makes
     * "does it do what was asked" worth half in both, which is a statement
     * somebody can agree or disagree with rather than an accident of how many
     * lines a config happens to have.
     *
     * <p>A tier that posed nothing measurable is skipped rather than counted
     * as zero — the same rule the criteria themselves follow. With no tier
     * breakdown at all (an older record, or a scorecard built by hand) this
     * falls back to the pooled ratio, which is what those records mean.
     */
    public Double percentage() {
        if (verdict != Verdict.SCORED) {
            return null;
        }
        double sum = 0.0;
        int counted = 0;
        for (Tally t : tiers.values()) {
            Double pct = t.percentage();
            if (pct != null) {
                sum += pct;
                counted++;
            }
        }
        if (counted > 0) {
            return sum / counted;
        }
        return ceiling <= 0.0 ? null : 100.0 * achieved / ceiling;
    }

    /** One tier's percentage, or null when it posed no measurable question. */
    public Double tierPercentage(Criterion.Tier tier) {
        Tally t = tiers.get(tier);
        return t == null ? null : t.percentage();
    }

    public String toJson() {
        StringBuilder b = new StringBuilder("{\n \"schemaVersion\": 1,\n");
        b.append(" \"dimension\": ").append(Json.quote(dimension)).append(",\n");
        b.append(" \"seed\": ").append(seed).append(",\n");
        b.append(" \"verdict\": ").append(Json.quote(verdict.name())).append(",\n");
        b.append(" \"verdictReason\": ").append(Json.quote(verdictReason)).append(",\n");
        b.append(" \"achieved\": ").append(Json.number(achieved)).append(",\n");
        b.append(" \"ceiling\": ").append(Json.number(ceiling)).append(",\n");
        Double pct = percentage();
        b.append(" \"percentage\": ")
                .append(pct == null ? "null" : Json.number(pct)).append(",\n");

        // The two tiers, each out of its own ceiling. The headline above is
        // their mean, so a card that shows only the headline hides which of
        // the two questions the seed actually answered.
        b.append(" \"tiers\": {");
        int ti = 0;
        for (Criterion.Tier tier : Criterion.Tier.values()) {
            Tally t = tiers.get(tier);
            if (t == null) {
                continue;
            }
            if (ti++ > 0) {
                b.append(',');
            }
            Double tp = t.percentage();
            b.append("\n  ").append(Json.quote(tier.name().toLowerCase()))
                    .append(": {\"achieved\": ").append(Json.number(t.achieved()))
                    .append(", \"ceiling\": ").append(Json.number(t.ceiling()))
                    .append(", \"percentage\": ")
                    .append(tp == null ? "null" : Json.number(tp)).append('}');
        }
        b.append(ti > 0 ? "\n },\n" : "},\n");

        // Per group, so the report reads in the mission's own vocabulary
        // rather than as a flat list of twenty numbers.
        Map<Criterion.Group, List<Entry>> byGroup = new LinkedHashMap<>();
        for (Criterion.Group g : Criterion.Group.values()) {
            byGroup.put(g, new ArrayList<>());
        }
        for (Entry e : entries) {
            byGroup.get(e.group()).add(e);
        }
        b.append(" \"groups\": {");
        int gi = 0;
        for (var ge : byGroup.entrySet()) {
            if (ge.getValue().isEmpty()) {
                continue;
            }
            if (gi++ > 0) {
                b.append(',');
            }
            b.append("\n  ").append(Json.quote(ge.getKey().name().toLowerCase()))
                    .append(": [");
            int i = 0;
            for (Entry e : ge.getValue()) {
                b.append(i++ > 0 ? ",\n   " : "\n   ");
                b.append("{\"id\": ").append(Json.quote(e.id()));
                b.append(", \"outcome\": ").append(Json.quote(e.outcome()));
                b.append(", \"value\": ")
                        .append(e.value() == null ? "null" : Json.number(e.value()));
                b.append(", \"target\": ").append(Json.quote(e.target()));
                b.append(", \"detail\": ").append(Json.quote(e.detail()));
                if (e.band() != null) {
                    b.append(", \"band\": [").append(Json.number(e.band()[0]))
                            .append(", ").append(Json.number(e.band()[1])).append(']');
                }
                b.append('}');
            }
            b.append("\n  ]");
        }
        b.append(gi > 0 ? "\n }\n}\n" : "}\n}\n");
        return b.toString();
    }
}
