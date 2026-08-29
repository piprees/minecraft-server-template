package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A want favours and a shun discourages, in the pool weight every caller of
 * {@link NoisePoolBuilder#build} draws from.
 *
 * <p>The mechanism half pins {@link NoisePoolBuilder#favourWeight}; the outcome
 * half runs the real {@link StructurePick} over a pool with the measured
 * overworld's weight classes and counts the sites each structure actually wins,
 * because a weight that moves and a placement that does not is the failure this
 * replaces.
 */
class NoisePoolFavourTest {

    private static final Gson GSON = new Gson();
    private static final int R = NoisePoolBuilder.WEIGHT_RESOLUTION;

    // ------------------------------------------------------------- mechanism

    @Test
    void anUnfavouredWeightIsCarriedAtTheResolutionAndNothingElse() {
        for (int w = 1; w <= 200; w++) {
            assertEquals(w * R, NoisePoolBuilder.favourWeight(w, false, false),
                    "weight " + w + " was not carried at the plain resolution");
        }
    }

    @Test
    void theFactorsAreExactAtEveryWeightIncludingOne() {
        // 1 * 1.2 rounds to nearest as 1. Carrying the pool at 15 units per
        // weight is what stops a want vanishing on the rare and endgame tiers.
        for (int w = 1; w <= 512; w++) {
            int plain = NoisePoolBuilder.favourWeight(w, false, false);
            int wanted = NoisePoolBuilder.favourWeight(w, true, false);
            int shunned = NoisePoolBuilder.favourWeight(w, false, true);
            assertEquals(plain * 6, wanted * 5, "want was not exactly 1.2x at weight " + w);
            assertEquals(plain * 2, shunned * 3, "shun was not exactly /1.5 at weight " + w);
        }
    }

    @Test
    void theUnitConstantsMatchTheDocumentedFactors() {
        // The doubles are what the design doc states; the units are what the
        // pool carries. Editing one without the other is the drift this catches.
        assertEquals(Math.round(R * NoisePoolBuilder.WANT_WEIGHT_FACTOR),
                NoisePoolBuilder.favourWeight(1, true, false));
        assertEquals(Math.round(R / NoisePoolBuilder.SHUN_WEIGHT_DIVISOR),
                NoisePoolBuilder.favourWeight(1, false, true));
    }

    @Test
    void aWantOnTheFloorWeightStillMoves() {
        assertEquals(15, NoisePoolBuilder.favourWeight(1, false, false));
        assertEquals(18, NoisePoolBuilder.favourWeight(1, true, false));
    }

    @Test
    void aShunNeverRemovesAStructure() {
        // exclude removes; a shun discourages. Even at the floor it keeps mass.
        assertEquals(10, NoisePoolBuilder.favourWeight(1, false, true));
        for (int w = 1; w <= 512; w++) {
            assertTrue(NoisePoolBuilder.favourWeight(w, false, true) > 0,
                    "a shun zeroed weight " + w);
        }
    }

    @Test
    void theHeavyEndTakesTheSameFactorAsTheFloor() {
        assertEquals(160 * R, NoisePoolBuilder.favourWeight(160, false, false));
        assertEquals(160 * 18, NoisePoolBuilder.favourWeight(160, true, false));
        assertEquals(160 * 10, NoisePoolBuilder.favourWeight(160, false, true));
    }

    @Test
    void wantingAndShunningTheSameStructureCancels() {
        assertEquals(8 * R, NoisePoolBuilder.favourWeight(8, true, true));
        assertEquals(R, NoisePoolBuilder.favourWeight(1, true, true));
    }

    @Test
    void aWeightBelowOneIsStillFloored() {
        assertEquals(R, NoisePoolBuilder.favourWeight(0, false, false));
        assertEquals(R, NoisePoolBuilder.favourWeight(-4, false, false));
    }

    // ------------------------------------------------------- name resolution

    private static DimensionConfig config(String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName("the_test");
        return config;
    }

    @Test
    void structuresShunsResolvesToStructureIds() {
        Set<String> ids = NoisePoolBuilder.shunnedStructureIds(config(
                "{\"type\": \"multi_biome\", \"structures\": "
                + "{\"shuns\": {\"monument\": {}, \"mansion\": {}}}}"));
        assertEquals(Set.of("minecraft:monument", "minecraft:mansion"), ids);
    }

    @Test
    void seedRollShunsIsUsedWhenTheStructuresBlockNamesNone() {
        // 73 of the 82 shipped dimensions shun through seedRoll.shuns and are
        // scored on it; reading only structures.shuns would leave them unmoved.
        Set<String> ids = NoisePoolBuilder.shunnedStructureIds(config(
                "{\"type\": \"nether\", \"seedRoll\": {\"shuns\": [\"monument\"]}}"));
        assertEquals(Set.of("minecraft:monument"), ids);
    }

    @Test
    void anExplicitStructuresShunsBlockWinsOverSeedRoll() {
        Set<String> ids = NoisePoolBuilder.shunnedStructureIds(config(
                "{\"type\": \"nether\", \"structures\": {\"shuns\": {\"mansion\": {}}}, "
                + "\"seedRoll\": {\"shuns\": [\"monument\"]}}"));
        assertEquals(Set.of("minecraft:mansion"), ids);
    }

    @Test
    void aTagShunIsDroppedTheWayATagWantIs() {
        // "village" is #minecraft:village. A tag is a set of structures, and
        // neither wantedStructureIds nor this can weight a set member by name.
        assertEquals(Set.of(), NoisePoolBuilder.shunnedStructureIds(config(
                "{\"type\": \"multi_biome\", \"structures\": "
                + "{\"shuns\": {\"village\": {}}}}")));
    }

    @Test
    void aConfigThatShunsNothingResolvesToNothing() {
        assertEquals(Set.of(), NoisePoolBuilder.shunnedStructureIds(
                config("{\"type\": \"multi_biome\"}")));
    }

    // ---------------------------------------------------------------- outcome

    /**
     * The measured overworld's weight classes — 1, 3, 8 and a heavy tail — at a
     * tenth of its 597 members, so 250,000 weighted draws finish in seconds.
     * The proportions are what the assertions rest on, not the member count.
     */
    private static List<int[]> POOL_SHAPE = List.of(
            new int[] {17, 1}, new int[] {19, 3}, new int[] {13, 8}, new int[] {1, 160});

    private static final String RARE = "test:rare_00";
    private static final String COMMON = "test:common_00";

    private static List<StructurePick.PoolEntry> pool(String favoured,
                                                      boolean wanted, boolean shunned) {
        String[] names = {"rare", "uncommon", "common", "heavy"};
        List<StructurePick.PoolEntry> out = new ArrayList<>();
        for (int c = 0; c < POOL_SHAPE.size(); c++) {
            for (int i = 0; i < POOL_SHAPE.get(c)[0]; i++) {
                String id = String.format("test:%s_%02d", names[c], i);
                boolean hit = id.equals(favoured);
                out.add(new StructurePick.PoolEntry(id, NoisePoolBuilder.favourWeight(
                        POOL_SHAPE.get(c)[1], hit && wanted, hit && shunned)));
            }
        }
        return out;
    }

    /** Sites won per structure over a 500x500 chunk block of one noise group. */
    private static Map<String, Integer> siteCounts(List<StructurePick.PoolEntry> pool) {
        List<StructurePick.PoolEntry> sorted = StructurePick.sortedPool(pool);
        long pickSeed = StructurePick.pickSeed(0x5eedL);
        Map<String, Integer> counts = new TreeMap<>();
        for (int cx = 0; cx < 500; cx++) {
            for (int cz = 0; cz < 500; cz++) {
                counts.merge(StructurePick.resolveWeighted(
                        sorted, StructurePick.pick(pickSeed, cx, cz)), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int count(Map<String, Integer> counts, String id) {
        return counts.getOrDefault(id, 0);
    }

    @Test
    void aWantedFloorWeightStructureWinsMoreSitesThanItDidUnwanted() {
        int before = count(siteCounts(pool(null, false, false)), RARE);
        int after = count(siteCounts(pool(RARE, true, false)), RARE);
        assertTrue(before > 300, "the baseline sample is too small to read: " + before);
        assertTrue(after > before * 1.1,
                "a want did not raise site count: " + before + " -> " + after);
    }

    @Test
    void aWantIsAFavouringNotASeat() {
        // The whole T53 failure is a want that becomes mandatory. A wanted
        // structure must still lose the overwhelming majority of sites.
        Map<String, Integer> counts = siteCounts(pool(RARE, true, false));
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(count(counts, RARE) < total / 100,
                "a want took " + count(counts, RARE) + " of " + total + " sites");
    }

    @Test
    void aShunnedCommonStructureLosesSites() {
        int before = count(siteCounts(pool(null, false, false)), COMMON);
        int after = count(siteCounts(pool(COMMON, false, true)), COMMON);
        assertTrue(after < before * 0.8,
                "a shun did not lower site count: " + before + " -> " + after);
    }

    @Test
    void aShunnedFloorWeightStructureStillGetsSites() {
        // exclude removes; a shun on something already at the floor must leave
        // it in the world rather than deleting it.
        assertTrue(count(siteCounts(pool(RARE, false, true)), RARE) > 0,
                "a shun removed " + RARE + " entirely");
    }

    @Test
    void favouringOneStructureLeavesEveryOtherWeightUntouched() {
        // The consequence for the heavy end, stated as an assertion: none.
        List<StructurePick.PoolEntry> base = pool(null, false, false);
        List<StructurePick.PoolEntry> favoured = pool(RARE, true, false);
        for (int i = 0; i < base.size(); i++) {
            if (base.get(i).structureId().equals(RARE)) {
                assertTrue(favoured.get(i).weight() > base.get(i).weight());
                continue;
            }
            assertEquals(base.get(i).weight(), favoured.get(i).weight(),
                    base.get(i).structureId() + " changed weight");
        }
    }
}
