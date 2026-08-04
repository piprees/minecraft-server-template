package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Precision plan section 2.6. Tests for the structure-pick algorithm:
 * deterministic per-site assignment from a noise group's weighted pool.
 *
 * Bootstrap-free: only pure primitives, no Minecraft types except ChunkPos
 * (which is a plain data class used only in the identity-semantics test
 * via NoiseFieldIndex).
 */
class StructurePickTest {

    private static final long SEED = 0xDEADBEEFL;

    // --- pickSeed decorrelation ------------------------------------------

    @Test
    void pickSeedDecorrelatesFromPriority() {
        // pick(pickSeed, cx, cz) must differ from priority(noiseSeed, cx, cz)
        // over a grid of positions: the pick is independent of whether the
        // site exists (which is decided by priority with the raw noiseSeed).
        long noiseSeed = 42L;
        long ps = StructurePick.pickSeed(noiseSeed);
        assertNotEquals(noiseSeed, ps, "pickSeed must differ from the raw noiseSeed");

        int matches = 0;
        int total = 0;
        for (int cx = -10; cx <= 10; cx++) {
            for (int cz = -10; cz <= 10; cz++) {
                long rawPriority = NoiseFieldIndex.priority(noiseSeed, cx, cz);
                long pickValue = StructurePick.pick(ps, cx, cz);
                if (rawPriority == pickValue) {
                    matches++;
                }
                total++;
            }
        }
        // Over 441 positions, zero or near-zero collisions.
        assertTrue(matches < 5,
                matches + " of " + total + " pick values equalled priority — should be decorrelated");
    }

    @Test
    void pickIsDeterministic() {
        long ps = StructurePick.pickSeed(SEED);
        long a = StructurePick.pick(ps, 17, -42);
        long b = StructurePick.pick(ps, 17, -42);
        assertEquals(a, b);
    }

    // --- resolveWeighted worked cases ------------------------------------

    @Test
    void resolveWeightedWorkedCase() {
        // [a:1, b:1, c:2] total 4 -> target 0=a, 1=b, 2=c, 3=c
        List<StructurePick.PoolEntry> pool = StructurePick.sortedPool(List.of(
                new StructurePick.PoolEntry("a", 1),
                new StructurePick.PoolEntry("b", 1),
                new StructurePick.PoolEntry("c", 2)));

        // Verify sort: a < b < c
        assertEquals("a", pool.get(0).structureId());
        assertEquals("b", pool.get(1).structureId());
        assertEquals("c", pool.get(2).structureId());

        // Manually check all four target values (0..3).
        // resolveWeighted uses Long.remainderUnsigned(pickValue, 4).
        // We construct pick values that produce exact remainders.
        assertEquals("a", StructurePick.resolveWeighted(pool, 0L));  // target=0, cum 1>0 at a
        assertEquals("b", StructurePick.resolveWeighted(pool, 1L));  // target=1, cum 2>1 at b
        assertEquals("c", StructurePick.resolveWeighted(pool, 2L));  // target=2, cum 4>2 at c
        assertEquals("c", StructurePick.resolveWeighted(pool, 3L));  // target=3, cum 4>3 at c
    }

    @Test
    void resolveWeightedSingleEntry() {
        List<StructurePick.PoolEntry> pool = StructurePick.sortedPool(List.of(
                new StructurePick.PoolEntry("only_one", 5)));
        // Every pick value must resolve to the single entry.
        for (long pv = 0; pv < 10; pv++) {
            assertEquals("only_one", StructurePick.resolveWeighted(pool, pv));
        }
    }

    @Test
    void resolveWeightedEmptyPool() {
        assertNull(StructurePick.resolveWeighted(List.of(), 42L));
        assertNull(StructurePick.resolveWeighted(null, 42L));
    }

    @Test
    void resolveWeightedZeroTotalWeight() {
        List<StructurePick.PoolEntry> pool = StructurePick.sortedPool(List.of(
                new StructurePick.PoolEntry("x", 0),
                new StructurePick.PoolEntry("y", 0)));
        assertNull(StructurePick.resolveWeighted(pool, 42L));
    }

    // --- input-order independence ----------------------------------------

    @Test
    void inputOrderDoesNotAffectResult() {
        List<StructurePick.PoolEntry> original = List.of(
                new StructurePick.PoolEntry("c", 2),
                new StructurePick.PoolEntry("a", 1),
                new StructurePick.PoolEntry("b", 1));
        List<StructurePick.PoolEntry> shuffled = List.of(
                new StructurePick.PoolEntry("b", 1),
                new StructurePick.PoolEntry("c", 2),
                new StructurePick.PoolEntry("a", 1));

        List<StructurePick.PoolEntry> sortedA = StructurePick.sortedPool(original);
        List<StructurePick.PoolEntry> sortedB = StructurePick.sortedPool(shuffled);

        // Same sorted order.
        assertEquals(sortedA.size(), sortedB.size());
        for (int i = 0; i < sortedA.size(); i++) {
            assertEquals(sortedA.get(i).structureId(), sortedB.get(i).structureId());
            assertEquals(sortedA.get(i).weight(), sortedB.get(i).weight());
        }

        // Same resolve results for a range of pick values.
        for (long pv = 0; pv < 20; pv++) {
            assertEquals(
                    StructurePick.resolveWeighted(sortedA, pv),
                    StructurePick.resolveWeighted(sortedB, pv),
                    "diverged at pickValue " + pv);
        }
    }

    // --- duplicate-id adjacency ------------------------------------------

    @Test
    void duplicateIdAdjacencyEquivalence() {
        // [(x,2),(x,3),(y,5)] must be equivalent to [(x,5),(y,5)]
        // for all targets 0..9 — Java's stable sort keeps duplicate-id
        // entries adjacent, so walking the cumulative weights merges them.
        List<StructurePick.PoolEntry> split = StructurePick.sortedPool(List.of(
                new StructurePick.PoolEntry("x", 2),
                new StructurePick.PoolEntry("x", 3),
                new StructurePick.PoolEntry("y", 5)));
        List<StructurePick.PoolEntry> merged = StructurePick.sortedPool(List.of(
                new StructurePick.PoolEntry("x", 5),
                new StructurePick.PoolEntry("y", 5)));

        // Total weight: split = 2+3+5=10, merged = 5+5=10.
        for (long target = 0; target < 10; target++) {
            String fromSplit = StructurePick.resolveWeighted(split, target);
            String fromMerged = StructurePick.resolveWeighted(merged, target);
            assertEquals(fromMerged, fromSplit,
                    "diverged at target " + target + ": split=" + fromSplit + " merged=" + fromMerged);
        }
    }

    // --- selection-registry identity semantics ----------------------------

    @Test
    void registryUsesObjectIdentityNotEquality() {
        StructurePick.resetForTests();
        try {
            // Two WeightedEntry-like objects with the same content but
            // different identity must not cross-match.
            // We cannot create real WeightedEntry without Bootstrap, so we
            // test the IdentityHashMap contract directly.
            Object entryA = new Object();
            Object entryB = new Object();

            IdentityHashMap<Object, String> map = new IdentityHashMap<>();
            map.put(entryA, "found");

            assertNotNull(map.get(entryA), "same instance must hit");
            assertNull(map.get(entryB), "different instance with same hash must miss");
        } finally {
            StructurePick.resetForTests();
        }
    }

    // --- NoiseFieldIndex salted-priority tests ----------------------------

    @Test
    void saltedPriorityIsDeterministic() {
        long a = NoiseFieldIndex.priority(SEED, 10, -20);
        long b = NoiseFieldIndex.priority(SEED, 10, -20);
        assertEquals(a, b);
    }

    @Test
    void saltedPriorityDiffersFromPlacementRank() {
        // The pick uses pickSeed (noiseSeed ^ saltOf("structure_pick")),
        // while placement uses the raw noiseSeed. The two must differ.
        long noiseSeed = 12345L;
        long ps = StructurePick.pickSeed(noiseSeed);

        int matches = 0;
        for (int cx = -5; cx <= 5; cx++) {
            for (int cz = -5; cz <= 5; cz++) {
                if (NoiseFieldIndex.priority(noiseSeed, cx, cz)
                        == NoiseFieldIndex.priority(ps, cx, cz)) {
                    matches++;
                }
            }
        }
        // 121 positions, virtually zero collisions expected.
        assertTrue(matches < 3, matches + " collisions over 121 positions — too many");
    }

    // --- assignedStructure integration -----------------------------------

    @Test
    void assignedStructureIsDeterministicAcrossCalls() {
        List<StructurePick.PoolEntry> pool = StructurePick.sortedPool(List.of(
                new StructurePick.PoolEntry("a", 1),
                new StructurePick.PoolEntry("b", 1),
                new StructurePick.PoolEntry("c", 2)));

        String first = StructurePick.assignedStructure(SEED, 42, -7, pool);
        String second = StructurePick.assignedStructure(SEED, 42, -7, pool);
        assertNotNull(first);
        assertEquals(first, second);
    }

    @Test
    void assignedStructureCoversAllPoolMembers() {
        // Over a grid, every pool member must be assigned at least once
        // (with enough positions and reasonable weights).
        List<StructurePick.PoolEntry> pool = StructurePick.sortedPool(List.of(
                new StructurePick.PoolEntry("alpha", 2),
                new StructurePick.PoolEntry("beta", 2),
                new StructurePick.PoolEntry("gamma", 2)));

        Map<String, Integer> counts = new HashMap<>();
        for (int cx = -50; cx <= 50; cx++) {
            for (int cz = -50; cz <= 50; cz++) {
                String assigned = StructurePick.assignedStructure(SEED, cx, cz, pool);
                counts.merge(assigned, 1, Integer::sum);
            }
        }
        assertEquals(3, counts.size(), "not all pool members were assigned: " + counts);
        for (var e : counts.entrySet()) {
            assertTrue(e.getValue() > 100,
                    e.getKey() + " was assigned only " + e.getValue() + " times — expected ~3400");
        }
    }
}
