package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The candidate chain a site is filled from: the assigned structure, then each
 * re-draw. {@code NoiseStructureSelectionMixin} walks it at generation time and
 * {@code SiteValidity} walks it headlessly, so it must be a pure function of
 * (pool, pick value) and identical between the two.
 */
class StructurePickChainTest {

    private static List<StructurePick.PoolEntry> pool(Object... idsAndWeights) {
        List<StructurePick.PoolEntry> entries = new ArrayList<>();
        for (int i = 0; i < idsAndWeights.length; i += 2) {
            entries.add(new StructurePick.PoolEntry(
                    (String) idsAndWeights[i], (Integer) idsAndWeights[i + 1]));
        }
        return StructurePick.sortedPool(entries);
    }

    private static List<String> ids(List<StructurePick.PoolEntry> chain) {
        List<String> out = new ArrayList<>();
        for (StructurePick.PoolEntry e : chain) {
            out.add(e.structureId());
        }
        return out;
    }

    @Test
    void chainStartsAtTheAssignedStructure() {
        List<StructurePick.PoolEntry> sorted = pool("a", 3, "b", 5, "c", 2);
        for (long pv = 0; pv < 200; pv++) {
            String assigned = StructurePick.resolveWeighted(sorted, pv);
            assertEquals(assigned, StructurePick.candidates(sorted, pv, 8).get(0).structureId(),
                    "chain head must be the assignment for pick value " + pv);
        }
    }

    @Test
    void chainIsDeterministicAcrossRuns() {
        List<StructurePick.PoolEntry> sorted = pool("a", 3, "b", 5, "c", 2, "d", 1);
        long pv = 1419601956218173845L;
        assertEquals(ids(StructurePick.candidates(sorted, pv, 8)),
                ids(StructurePick.candidates(sorted, pv, 8)));
    }

    @Test
    void chainIsIndependentOfInputOrder() {
        List<StructurePick.PoolEntry> one = pool("a", 3, "b", 5, "c", 2, "d", 1);
        List<StructurePick.PoolEntry> other = pool("d", 1, "c", 2, "a", 3, "b", 5);
        for (long pv = 0; pv < 500; pv++) {
            assertEquals(ids(StructurePick.candidates(one, pv, 8)),
                    ids(StructurePick.candidates(other, pv, 8)),
                    "chain must not depend on the order entries arrived in, pick value " + pv);
        }
    }

    @Test
    void chainNeverRepeatsAStructure() {
        List<StructurePick.PoolEntry> sorted = pool("a", 3, "a", 4, "b", 5, "c", 2);
        for (long pv = 0; pv < 300; pv++) {
            List<String> chain = ids(StructurePick.candidates(sorted, pv, 8));
            assertEquals(chain.size(), chain.stream().distinct().count(),
                    "a duplicate id would re-offer the same structure, pick value " + pv);
        }
    }

    @Test
    void chainExhaustsThePoolWhenTheCapAllowsIt() {
        List<StructurePick.PoolEntry> sorted = pool("a", 3, "b", 5, "c", 2);
        assertEquals(3, StructurePick.candidates(sorted, 42L, 8).size());
    }

    @Test
    void capTruncatesTheSameChainRatherThanChangingIt() {
        List<StructurePick.PoolEntry> sorted =
                pool("a", 3, "b", 5, "c", 2, "d", 1, "e", 4, "f", 6);
        for (long pv = 0; pv < 200; pv++) {
            List<String> full = ids(StructurePick.candidates(sorted, pv, 6));
            List<String> capped = ids(StructurePick.candidates(sorted, pv, 3));
            assertEquals(full.subList(0, 3), capped,
                    "the cap must be a prefix of the uncapped chain, pick value " + pv);
        }
    }

    @Test
    void emptyPoolAndZeroCapProduceNoChain() {
        assertTrue(StructurePick.candidates(List.of(), 7L, 8).isEmpty());
        assertTrue(StructurePick.candidates(pool("a", 1), 7L, 0).isEmpty());
        assertTrue(StructurePick.candidates(null, 7L, 8).isEmpty());
    }

    @Test
    void zeroWeightPoolProducesNoChain() {
        assertTrue(StructurePick.candidates(pool("a", 0, "b", 0), 7L, 8).isEmpty());
    }

    @Test
    void poolEntryCarriesTheBypassFlagPerEntry() {
        StructurePick.PoolEntry wanted =
                new StructurePick.PoolEntry("adventure:wanted", 1, true, null);
        StructurePick.PoolEntry ordinary = new StructurePick.PoolEntry("adventure:ordinary", 1);
        List<StructurePick.PoolEntry> sorted =
                StructurePick.sortedPool(List.of(wanted, ordinary));
        List<StructurePick.PoolEntry> chain = StructurePick.candidates(sorted, 3L, 8);
        assertEquals(2, chain.size());
        for (StructurePick.PoolEntry e : chain) {
            assertEquals("adventure:wanted".equals(e.structureId()), e.bypassBiome(),
                    "the flag must travel with the entry, not with the pool");
        }
        assertFalse(ordinary.bypassBiome(), "the two-argument form defaults to no bypass");
    }
}
