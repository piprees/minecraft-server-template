package com.customdimensions.core.dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.ChunkPos;
import com.customdimensions.core.structure.StructureSites;
import java.util.List;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

/** Where a noise group's sites settle on a world of islands in a void. */
class SettledSitesTest {
    /** Islands: discs of 9 chunks' radius on a 40-chunk lattice, the rest void, as an end preset's outer islands. */
    private static final BiPredicate<Integer, Integer> ISLANDS = (x, z) -> {
        int dx = Math.floorMod(x, 40) - 20;
        int dz = Math.floorMod(z, 40) - 20;
        return dx * dx + dz * dz <= 81;
    };

    @Test
    void sitesInTheVoidSettleOnAnIslandInReach() {
        NoiseFieldIndex index = NoiseFieldIndex.withinBudget(42L, NoiseProfile.fromString("natural"), 30, null, 256, 0, 0, 0, 0, 1);
        List<ChunkPos> field = index.positions();
        long inVoid = field.stream().filter(p -> !ISLANDS.test(p.x(), p.z())).count();
        assertTrue(inVoid > field.size() / 2, "the field alone puts most sites in the void: " + inVoid + " of " + field.size());
        List<ChunkPos> settled = StructureSites.settle(field, 7L, 400, ISLANDS);
        long onIslands = settled.stream().filter(p -> ISLANDS.test(p.x(), p.z())).count();
        assertEquals(settled.size(), onIslands, "every site has an island within 400 blocks");
        assertTrue(settled.size() > field.size() * 3 / 4, "few sites merge: " + settled.size() + " of " + field.size());
        for (ChunkPos p : field) {
            if (ISLANDS.test(p.x(), p.z())) assertTrue(settled.contains(p), "a site that can be filled stays where it is");
        }
        assertEquals(settled, StructureSites.settle(field, 7L, 400, ISLANDS), "seeded: the same every time");
    }

    @Test
    void aSiteWithNothingInReachStays() {
        List<ChunkPos> field = List.of(new ChunkPos(0, 0), new ChunkPos(100, 100));
        assertEquals(field, StructureSites.settle(field, 1L, 64, (x, z) -> false));
    }
}
