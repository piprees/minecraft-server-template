package com.customdimensions.compiler.convert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What converting one file flagged, dropped, rewrote and left for W8. */
public final class FileReport {

    /** A flagged id: where it occurs in the v1 file, its namespace, and the owners' decisions. */
    public record Flagged(String id, String namespace, String decisions, List<String> where) {
    }

    /** A v1 field the converter did not carry; {@code field} is the name the totals count by. */
    public record Dropped(String field, String detail) {
    }

    /** A value replaced in the v2 file; {@code where} is its v2 path. */
    public record Rewrite(String where, String from, String to) {
    }

    /** Something W8 has to decide; {@code category} is what the totals count by. */
    public record Decision(String category, String detail) {
    }

    private final String source;
    private final String target;
    private final Map<String, Flagged> flagged = new TreeMap<>();
    private final List<Dropped> dropped = new ArrayList<>();
    private final List<Rewrite> rewrites = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();

    public FileReport(String source, String target) {
        this.source = source;
        this.target = target;
    }

    public String source() {
        return source;
    }

    public String target() {
        return target;
    }

    void flag(String id, String namespace, String ownerDecisions, String where) {
        flagged.computeIfAbsent(id, k -> new Flagged(id, namespace, ownerDecisions, new ArrayList<>()))
                .where().add(where);
    }

    void drop(String field, String detail) {
        dropped.add(new Dropped(field, detail));
    }

    void rewrite(String where, String from, String to) {
        rewrites.add(new Rewrite(where, from, to));
    }

    void decide(String category, String detail) {
        decisions.add(new Decision(category, detail));
    }

    /** Flagged ids, sorted by id. */
    public List<Flagged> flagged() {
        return List.copyOf(flagged.values());
    }

    /** Occurrences of flagged ids per namespace, sorted by namespace. */
    public Map<String, Integer> flaggedCountByNamespace() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Flagged f : flagged.values()) {
            counts.merge(f.namespace(), f.where().size(), Integer::sum);
        }
        return counts;
    }

    public List<Dropped> dropped() {
        return List.copyOf(dropped);
    }

    public List<Rewrite> rewrites() {
        return List.copyOf(rewrites);
    }

    public List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    public boolean isEmpty() {
        return flagged.isEmpty() && dropped.isEmpty() && rewrites.isEmpty() && decisions.isEmpty();
    }

    /** Dropped fields of this file counted by name, in first-seen order. */
    Map<String, Integer> droppedByField() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        dropped.forEach(d -> counts.merge(d.field(), 1, Integer::sum));
        return counts;
    }
}
