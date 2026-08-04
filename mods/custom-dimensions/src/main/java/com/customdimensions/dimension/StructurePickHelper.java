package com.customdimensions.dimension;

import net.minecraft.structure.StructureSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the command package to StructurePick's assignment logic.
 *
 * DimensionCommands is in com.customdimensions.command; StructurePick is in
 * com.customdimensions.dimension and its helpers (NoiseFieldIndex.priority,
 * DimensionStructures.saltOf) are package-private. This class lives in the
 * dimension package and exposes exactly the entry points the commands need
 * without widening anything else.
 */
public final class StructurePickHelper {

    private StructurePickHelper() {
    }

    /**
     * Computes the assigned structure for a noise-managed group at its
     * group's own chunk coordinates.
     *
     * @param noise       the group's noise placement (carries group name and
     *                    noiseSeed via its index)
     * @param structures  the group's StructureSet.WeightedEntry list
     * @return the assigned structure id at (chunkX, chunkZ), or null for an
     *         empty pool. chunkX/chunkZ come from the caller.
     */
    public static String assignedForGroup(NoiseStructurePlacement noise,
                                          List<StructureSet.WeightedEntry> structures) {
        // The census command already does this inline; factor out for reuse.
        return assignedAt(noise.index().noiseSeed(), 0, 0, structures);
    }

    /**
     * Computes the assigned structure at specific chunk coordinates.
     */
    public static String assignedAt(long noiseSeed, int chunkX, int chunkZ,
                                    List<StructureSet.WeightedEntry> structures) {
        List<StructurePick.PoolEntry> pickPool = new ArrayList<>();
        for (var weighted : structures) {
            weighted.structure().getKey().ifPresent(key -> pickPool.add(
                    new StructurePick.PoolEntry(
                            key.getValue().toString(), weighted.weight())));
        }
        List<StructurePick.PoolEntry> sorted = StructurePick.sortedPool(pickPool);
        return StructurePick.assignedStructure(noiseSeed, chunkX, chunkZ, sorted);
    }
}
