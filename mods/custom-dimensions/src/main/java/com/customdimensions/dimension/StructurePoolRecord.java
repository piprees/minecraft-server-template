package com.customdimensions.dimension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Which structures each dimension's noise groups can actually draw from, and
 * with what weight.
 *
 * <h2>Why this exists</h2>
 *
 * A noise position is one weighted draw from its group's pool, so "is there a
 * Village within 500 blocks" and "is there a settlement within 500 blocks" are
 * different questions. The seed roller could only ever ask the second one: the
 * census it banks per candidate records per-GROUP counts and a radial
 * histogram, never which structure landed on which position. That made two
 * whole classes of criterion meaningless:
 *
 * <ul>
 *   <li>a shun failed whenever its group was present, and an enabled group is
 *       populated by definition — so 167 shuns across 64 of 81 dimensions had
 *       never once been satisfied on any seed;</li>
 *   <li>a want was credited whenever ANY group member reached the band, so
 *       asking for a Village was really asking for any one of forty settlement
 *       types.</li>
 * </ul>
 *
 * The roller cannot derive the pool. Membership is decided server-side by
 * {@link NoisePoolBuilder}, intersecting each structure's own biome list with
 * the dimension's biome source — registry data the roller has no access to. So
 * it has to be told, and this is the telling.
 *
 * <h2>Why a recorder rather than a command that computes it</h2>
 *
 * {@link NoisePoolBuilder#build} needs a live {@code BiomeSource}, which only
 * exists once a world is loaded. A command that loaded 81 worlds to answer
 * would have to go through {@code requestWorldLoad} — asynchronous, because
 * calling {@code getOrCreateDimension} synchronously from command context
 * deadlocks the main thread. Recording what the boot path already computes
 * costs nothing and cannot deadlock: every managed dimension registers its pool
 * as its own placement calculator is installed.
 *
 * The consequence is that a dump covers the dimensions loaded SO FAR, and says
 * how many. That is deliberate. A missing dimension makes the roller fall back
 * to the group-level reading it used before — the same answer as today, never a
 * wrong one — so partial data improves scoring for what it covers and breaks
 * nothing else.
 *
 * Thread-safe: chunk generation is multithreaded under c2me and worlds load in
 * parallel, so both maps are concurrent.
 */
public final class StructurePoolRecord {

    /** One pool member: what it is, and how heavily the draw favours it. */
    public record Entry(String structureId, int weight) {
    }

    /** dimension name -> group -> structure id -> weight. */
    private static final Map<String, Map<String, Map<String, Integer>>> POOLS =
            new ConcurrentSkipListMap<>();

    private StructurePoolRecord() {
    }

    /**
     * Records one dimension's resolved pools, replacing any earlier record for
     * it — a reload rebuilds the calculator, and the newer answer is the true
     * one.
     *
     * Takes plain {@link Entry} lists rather than {@code StructureSet
     * .WeightedEntry}, so everything here is testable without Minecraft's
     * Bootstrap (which this codebase's unit tests deliberately avoid). The
     * registry lookup that turns a {@code RegistryEntry<Structure>} into an id
     * happens at the one call site that already holds a registry.
     *
     * Groups whose pool came out empty are recorded as an empty object rather
     * than omitted: "this dimension has no maritime structures" is a real
     * answer that gives every maritime want a share of 0, and dropping it would
     * be indistinguishable from "not measured yet" — which means share 1.0, the
     * opposite conclusion.
     */
    public static void record(String dimensionName, Map<String, List<Entry>> byGroup) {
        if (dimensionName == null || byGroup == null) {
            return;
        }
        Map<String, Map<String, Integer>> pools = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<Entry>> group : byGroup.entrySet()) {
            Map<String, Integer> weights = new LinkedHashMap<>();
            // Sort entries by structure id for canonical ordering. Both sides
            // sort before the cumulative-weight walk; determinism of the
            // artefact is the bonus.
            List<Entry> sorted = new java.util.ArrayList<>(group.getValue());
            sorted.sort((a, b) -> {
                String sa = a == null || a.structureId() == null ? "" : a.structureId();
                String sb = b == null || b.structureId() == null ? "" : b.structureId();
                return sa.compareTo(sb);
            });
            for (Entry entry : sorted) {
                if (entry == null || entry.structureId() == null) {
                    continue;
                }
                // A structure can appear twice in one pool when two sets both
                // carry it. The weights add, because that is what vanilla's
                // weighted draw sees — recording them separately would
                // under-count the structure's share of its group.
                weights.merge(entry.structureId(), entry.weight(), Integer::sum);
            }
            pools.put(group.getKey(), weights);
        }
        POOLS.put(dimensionName, pools);
    }

    /** How many dimensions have registered a pool. */
    public static int size() {
        return POOLS.size();
    }

    /**
     * The whole record as JSON. `header` is the artefact header from
     * {@code Artefacts.jsonHeader}, which already ends in a comma; the payload
     * sits under a "dimensions" key so the file can grow fields without moving
     * it.
     *
     * Hand-built rather than Gson-serialised for the same reason the census
     * dump is: the shape is fixed, the file is read by a Python mirror, and a
     * serialiser configured differently in some future version is a way for the
     * two to disagree silently.
     */
    public static String toJson(String header) {
        StringBuilder json = new StringBuilder(header).append(" \"dimensions\": {");
        int dims = 0;
        for (Map.Entry<String, Map<String, Map<String, Integer>>> dim : POOLS.entrySet()) {
            json.append(dims++ > 0 ? ",\n  \"" : "\n  \"")
                    .append(dim.getKey()).append("\": {");
            int groups = 0;
            for (Map.Entry<String, Map<String, Integer>> group
                    : new ConcurrentSkipListMap<>(dim.getValue()).entrySet()) {
                json.append(groups++ > 0 ? ",\n   \"" : "\n   \"")
                        .append(group.getKey()).append("\": {");
                int n = 0;
                for (Map.Entry<String, Integer> weight : group.getValue().entrySet()) {
                    json.append(n++ > 0 ? ", \"" : "\"")
                            .append(weight.getKey()).append("\": ").append(weight.getValue());
                }
                json.append('}');
            }
            json.append(groups > 0 ? "\n  }" : "}");
        }
        json.append(dims > 0 ? "\n }\n}\n" : "}\n}\n");
        return json.toString();
    }

    /** Test seam only. */
    static void reset() {
        POOLS.clear();
    }
}
