package com.customdimensions.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure logic for combining biome parameter entries from multiple sources
 * (static multinoise preset, TerraBlender regions) and identifying biomes
 * whose exact parameters are not available from any source.
 *
 * <p>Bootstrap-free: all data enters as plain records so the merging and
 * precedence logic is unit-testable without Minecraft classes.
 */
public final class BiomeParamCollector {

    /** An exact climate cell for one biome from one source. */
    public record Entry(String biomeId, double tempMin, double tempMax,
                        double humidMin, double humidMax,
                        double contMin, double contMax,
                        double erosMin, double erosMax,
                        double depthMin, double depthMax,
                        double weirdMin, double weirdMax,
                        double offset) {
    }

    /** A biome present in the dimension's biome source but absent from
     *  every parameter source. */
    public record Unresolved(String biomeId) {
    }

    public record Result(List<Entry> staticEntries, List<Entry> tbEntries,
                         List<Unresolved> unresolved) {

        /** Every biome id that has at least one exact entry. */
        public Set<String> resolvedBiomes() {
            Set<String> ids = new LinkedHashSet<>();
            for (Entry e : staticEntries) {
                ids.add(e.biomeId());
            }
            for (Entry e : tbEntries) {
                ids.add(e.biomeId());
            }
            return ids;
        }
    }

    private BiomeParamCollector() {
    }

    /**
     * Merge static entries and TB entries, then compute the unresolved set.
     *
     * <p>TB entries whose biome already appears in the static set are kept
     * (a biome can legitimately have cells in both sources), but the static
     * set is considered the primary source. A biome is unresolved only
     * when it appears in {@code allBiomeIds} but has zero entries in
     * either source.
     *
     * @param staticEntries  exact cells from getBiomeEntries()
     * @param tbEntries      exact cells from TB region extraction
     * @param allBiomeIds    every biome the dimension's biome source claims
     *                       to produce
     */
    public static Result merge(List<Entry> staticEntries, List<Entry> tbEntries,
                               Set<String> allBiomeIds) {
        Set<String> staticBiomes = new LinkedHashSet<>();
        for (Entry e : staticEntries) {
            staticBiomes.add(e.biomeId());
        }

        // TB entries for biomes NOT in the static set
        List<Entry> filteredTb = new ArrayList<>();
        for (Entry e : tbEntries) {
            if (!staticBiomes.contains(e.biomeId())) {
                filteredTb.add(e);
            }
        }

        // Biomes in the source but not in either set
        Set<String> resolved = new LinkedHashSet<>(staticBiomes);
        for (Entry e : filteredTb) {
            resolved.add(e.biomeId());
        }
        List<Unresolved> unresolvedList = new ArrayList<>();
        List<String> sortedAll = new ArrayList<>(allBiomeIds);
        Collections.sort(sortedAll);
        for (String id : sortedAll) {
            if (!resolved.contains(id)) {
                unresolvedList.add(new Unresolved(id));
            }
        }

        return new Result(staticEntries, filteredTb, unresolvedList);
    }
}
