package com.customdimensions.dimension;

import net.minecraft.structure.StructureSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic per-site structure assignment for noise-managed groups.
 *
 * Every noise site has exactly one assigned structure:
 * {@code assigned = resolveWeighted(sortedPool, pick(pickSeed, cx, cz))}.
 * The assignment governs generation via {@code NoiseStructureSelectionMixin}:
 * only the assigned structure can ever start at a noise site, its biome
 * predicate bypassed.
 *
 * <h2>The occupancy contract</h2>
 *
 * A site's assigned structure is exact and mirrored; the site is occupied by
 * that structure iff the structure's own generation accepts the position, and
 * by nothing else, ever. A structural rejection leaves the site empty and is
 * itself recorded exactly.
 *
 * <h2>Selection registry</h2>
 *
 * Per-world, keyed by {@link StructureSet.WeightedEntry} OBJECT IDENTITY
 * ({@link IdentityHashMap}): {@code DimensionStructures.transformedNoise}
 * builds each group's synthetic StructureSet from the exact WeightedEntry
 * instances NoisePoolBuilder created, so the mixin lookup is O(1) and can
 * never confuse a pool entry with the same structure appearing in a
 * pass-through or forced set. Installed by transformedNoise, replaced
 * wholesale on every calculator rebuild, cleared on
 * {@code ServerWorldEvents.UNLOAD}.
 */
public final class StructurePick {

    private StructurePick() {
    }

    /** One pool member: its id and weighted draw share. */
    public record PoolEntry(String structureId, int weight) {
    }

    /** A group's selection state for one world. */
    public record GroupSelection(
            String group,
            long noiseSeed,
            List<PoolEntry> sortedPool,
            NoiseFieldIndex index
    ) {
    }

    // --- per-world selection registry ------------------------------------

    /** worldId -> (WeightedEntry identity -> GroupSelection). */
    private static final Map<String, IdentityHashMap<StructureSet.WeightedEntry, GroupSelection>>
            REGISTRY = new ConcurrentHashMap<>();

    /**
     * Installs a world's selection registry, replacing any earlier one.
     * Called by {@code DimensionStructures.transformedNoise} at calculator
     * rebuild time.
     */
    public static void install(String worldId,
                               IdentityHashMap<StructureSet.WeightedEntry, GroupSelection> selections) {
        if (worldId == null || selections == null || selections.isEmpty()) {
            REGISTRY.remove(worldId);
            return;
        }
        REGISTRY.put(worldId, selections);
    }

    /**
     * Looks up the selection for a specific WeightedEntry instance.
     * Returns null on miss (pass-throughs, forced sets, exit shrines,
     * other mods -- all untouched).
     */
    public static GroupSelection lookup(String worldId, StructureSet.WeightedEntry entry) {
        IdentityHashMap<StructureSet.WeightedEntry, GroupSelection> map = REGISTRY.get(worldId);
        if (map == null) {
            return null;
        }
        return map.get(entry);
    }

    /** Clears a world's selection registry. Called on ServerWorldEvents.UNLOAD. */
    public static void clear(String worldId) {
        REGISTRY.remove(worldId);
    }

    /** Test seam. */
    static void resetForTests() {
        REGISTRY.clear();
    }

    // --- pure pick logic -------------------------------------------------

    /**
     * The pick seed: decorrelated from the noise seed so the structure
     * assigned to a site is independent of whether the site exists.
     */
    public static long pickSeed(long noiseSeed) {
        return noiseSeed ^ DimensionStructures.saltOf("structure_pick");
    }

    /**
     * The pick value at a chunk position: delegates to
     * {@link NoiseFieldIndex#priority} (same package, accessible).
     * The result is compared UNSIGNED.
     */
    public static long pick(long pickSeed, int cx, int cz) {
        return NoiseFieldIndex.priority(pickSeed, cx, cz);
    }

    /**
     * Returns a new list sorted by structure id (plain string sort, stable).
     * The canonical pool order both sides use before the cumulative-weight
     * walk. Input order independence is pinned by test.
     */
    public static List<PoolEntry> sortedPool(List<PoolEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<PoolEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> a.structureId().compareTo(b.structureId()));
        return Collections.unmodifiableList(sorted);
    }

    /**
     * Weighted selection from a SORTED pool using an unsigned pick value.
     * Returns the structure id of the selected entry, or null when the
     * pool's total weight is zero or negative (confirmed-empty pool).
     *
     * <pre>
     * target = Long.remainderUnsigned(pickValue, totalWeight)
     * cumulative walk in sorted order; assigned = entry where cumulative &gt; target
     * </pre>
     *
     * Python mirror: noise_placement.resolve_structure -- pickValue is
     * already unsigned from priority(), so Python's {@code %} on a
     * non-negative int is equivalent to Long.remainderUnsigned.
     */
    public static String resolveWeighted(List<PoolEntry> sortedPool, long pickValue) {
        if (sortedPool == null || sortedPool.isEmpty()) {
            return null;
        }
        long totalWeight = 0;
        for (PoolEntry e : sortedPool) {
            totalWeight += e.weight();
        }
        if (totalWeight <= 0) {
            return null;
        }
        long target = Long.remainderUnsigned(pickValue, totalWeight);
        long cumulative = 0;
        for (PoolEntry e : sortedPool) {
            cumulative += e.weight();
            if (cumulative > target) {
                return e.structureId();
            }
        }
        // Should not be reachable when totalWeight > 0 and entries sum to it,
        // but return the last entry as a safety net.
        return sortedPool.get(sortedPool.size() - 1).structureId();
    }

    /**
     * The assigned structure for a noise site, combining pickSeed + pick +
     * resolveWeighted. Returns null for an empty pool.
     */
    public static String assignedStructure(long noiseSeed, int cx, int cz,
                                           List<PoolEntry> sortedPool) {
        long ps = pickSeed(noiseSeed);
        long pv = pick(ps, cx, cz);
        return resolveWeighted(sortedPool, pv);
    }
}
