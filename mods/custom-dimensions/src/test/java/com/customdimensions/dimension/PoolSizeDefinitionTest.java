package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The repetition ceiling counts how many DIFFERENT structures can stand in a
 * group, not how many pool entries there are. One structure reachable through
 * two sets is still one thing a player meets.
 */
class PoolSizeDefinitionTest {

    private static List<StructurePick.PoolEntry> pool(String... ids) {
        List<StructurePick.PoolEntry> out = new ArrayList<>();
        for (String id : ids) {
            out.add(new StructurePick.PoolEntry(id, 1));
        }
        return out;
    }

    @Test
    void aStructureReachableTwiceCountsOnce() {
        List<StructurePick.PoolEntry> p = pool("a:one", "a:two", "a:one");
        assertEquals(3, p.size(), "entries");
        assertEquals(2, StructurePick.distinctStructures(p),
                "a duplicate entry must not inflate the ceiling's target");
    }

    @Test
    void anEmptyPoolIsZero() {
        assertEquals(0, StructurePick.distinctStructures(pool()));
        assertEquals(0, StructurePick.distinctStructures(null));
    }

    @Test
    void distinctMatchesASetOfTheIds() {
        List<StructurePick.PoolEntry> p = pool("a:x", "a:y", "a:z", "a:y", "a:x");
        Set<String> ids = new HashSet<>();
        for (StructurePick.PoolEntry e : p) {
            ids.add(e.structureId());
        }
        assertEquals(ids.size(), StructurePick.distinctStructures(p));
    }
}
