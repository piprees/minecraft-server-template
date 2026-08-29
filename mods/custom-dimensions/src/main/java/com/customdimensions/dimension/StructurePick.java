package com.customdimensions.dimension;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructureSet;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic per-site structure assignment for noise-managed groups.
 *
 * Every noise site has exactly one assigned structure:
 * {@code assigned = resolveWeighted(sortedPool, pick(pickSeed, cx, cz))}, and
 * one candidate chain that follows from it ({@link #candidates}).
 *
 * <h2>The occupancy contract</h2>
 *
 * A site is occupied by the first candidate in its chain whose own generation
 * accepts the position, and by nothing outside that chain, ever. A chain that
 * declines end to end leaves the site empty, recorded once by
 * {@link RejectionCensus}.
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

    /**
     * One pool member: its id, weighted draw share, whether the dimension
     * admitted it despite its biomes ({@code structures.include} or a want),
     * the registry entry a re-draw needs to start it, and whether the config
     * favoured or discouraged it.
     *
     * <p>{@code bypassBiome} is the flag {@link NoisePoolBuilder#admittedDespiteBiomes}
     * decides; {@code structure} is null for headless callers that only need
     * the assignment.
     *
     * <p>{@code wanted} and {@code shunned} are carried rather than derived
     * because {@link NoisePoolBuilder#favourWeight} folds them into an integer
     * that cannot be read backwards — nothing downstream can tell a wanted
     * weight-1 structure from a plain one at weight 1.2. Both come from the
     * {@link NoisePoolBuilder.Result} the pool was built with, so no caller
     * resolves the config a second time.
     */
    public record PoolEntry(String structureId, int weight, boolean bypassBiome,
                            RegistryEntry<Structure> structure,
                            boolean wanted, boolean shunned) {

        public PoolEntry(String structureId, int weight) {
            this(structureId, weight, false, null, false, false);
        }

        public PoolEntry(String structureId, int weight, boolean bypassBiome,
                         RegistryEntry<Structure> structure) {
            this(structureId, weight, bypassBiome, structure, false, false);
        }
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
     * How many candidates a site may try. One number for the mixin that walks
     * the chain and the instrument that predicts it — two would drift, and the
     * drift would read as the fix not working.
     */
    public static final int MAX_CANDIDATES = 8;

    /** Distinct structures a pool can place; one reachable twice is one thing. */
    public static int distinctStructures(List<PoolEntry> pool) {
        if (pool == null || pool.isEmpty()) {
            return 0;
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (PoolEntry e : pool) {
            ids.add(e.structureId());
        }
        return ids.size();
    }

    /** The unsigned pick value at a site: pickSeed + pick, in one call. */
    public static long pickValue(long noiseSeed, int cx, int cz) {
        return pick(pickSeed(noiseSeed), cx, cz);
    }

    /**
     * The assigned structure for a noise site, combining pickSeed + pick +
     * resolveWeighted. Returns null for an empty pool.
     */
    public static String assignedStructure(long noiseSeed, int cx, int cz,
                                           List<PoolEntry> sortedPool) {
        return resolveWeighted(sortedPool, pickValue(noiseSeed, cx, cz));
    }

    /**
     * The ordered candidate chain at a site: the assigned structure, then each
     * successive re-draw on the pool minus what has been tried, at the SAME
     * pick value — vanilla's own remove-and-redraw shape, iteratively.
     *
     * <p>A pure function of (pool, pickValue), so the chain is identical
     * whatever order sets are visited in and whoever asks:
     * {@code NoiseStructureSelectionMixin} walks it to fill a site whose
     * assigned structure declines the position, and the site-validity
     * instrument walks the same list to say what will stand there.
     *
     * <p>Every entry sharing a picked id is removed together — the mixin
     * identifies a candidate by id, so a duplicate would re-offer the same
     * structure. {@code cap} truncates the chain and nothing else.
     */
    public static List<PoolEntry> candidates(List<PoolEntry> sortedPool, long pickValue, int cap) {
        if (sortedPool == null || sortedPool.isEmpty() || cap <= 0) {
            return List.of();
        }
        List<PoolEntry> remaining = new ArrayList<>(sortedPool);
        List<PoolEntry> chain = new ArrayList<>();
        while (chain.size() < cap && !remaining.isEmpty()) {
            String id = resolveWeighted(remaining, pickValue);
            if (id == null) {
                break;
            }
            PoolEntry picked = null;
            for (Iterator<PoolEntry> it = remaining.iterator(); it.hasNext(); ) {
                PoolEntry e = it.next();
                if (e.structureId().equals(id)) {
                    if (picked == null) {
                        picked = e;
                    }
                    it.remove();
                }
            }
            if (picked == null) {
                break;
            }
            chain.add(picked);
        }
        return List.copyOf(chain);
    }
}
