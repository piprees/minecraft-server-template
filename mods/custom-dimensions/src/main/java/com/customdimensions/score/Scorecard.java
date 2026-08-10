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
 * <p>Three verdicts, never blended (D6):
 *
 * <ul>
 *   <li>{@code INVALID_CONFIG} — a human must edit the JSON. Not a seed
 *       problem, and not scoreable. This is the channel whose absence let 142
 *       broken wants read as bad luck for months.</li>
 *   <li>{@code REJECTED} — this seed fails a hard gate, and the gate is
 *       named.</li>
 *   <li>{@code SCORED} — a percentage of what THIS dimension could achieve,
 *       plus per-criterion detail.</li>
 * </ul>
 *
 * <p>The headline is {@code achieved / ceiling} (D5), where the ceiling counts
 * the criteria this dimension's CONFIG poses — computed with no seed data, so
 * every seed of a dimension is marked out of the same denominator and two
 * percentages can be ranked against each other. 100% means "as good as this
 * dimension gets" and is reachable; the old absolute scale had an unknown,
 * usually unreachable maximum, so 83 out of a possible 87 read as a failure.
 */
public record Scorecard(
        String dimension,
        long seed,
        Verdict verdict,
        String verdictReason,
        double achieved,
        double ceiling,
        List<Entry> entries) {

    public enum Verdict {
        INVALID_CONFIG, REJECTED, SCORED
    }

    /** One criterion's conclusion, with the evidence that produced it. */
    public record Entry(String id, Criterion.Group group, String target,
                        String outcome, Double value, String detail) {
    }

    /** achieved/ceiling as a percentage, or absent when nothing applied. */
    public Double percentage() {
        if (verdict != Verdict.SCORED || ceiling <= 0.0) {
            return null;
        }
        return 100.0 * achieved / ceiling;
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
                b.append(", \"detail\": ").append(Json.quote(e.detail())).append('}');
            }
            b.append("\n  ]");
        }
        b.append(gi > 0 ? "\n }\n}\n" : "}\n}\n");
        return b.toString();
    }
}
