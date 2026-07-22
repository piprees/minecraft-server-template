package com.customdimensions.portal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortalDecayTest {

    @Test
    void builtInPairsResolve() {
        assertEquals("minecraft:crying_obsidian", PortalDecay.resolve("minecraft:obsidian", null));
        assertEquals("minecraft:cracked_stone_bricks", PortalDecay.resolve("minecraft:stone_bricks", null));
        assertEquals("minecraft:cracked_nether_bricks", PortalDecay.resolve("minecraft:nether_bricks", null));
        assertEquals("minecraft:cracked_polished_blackstone_bricks",
                PortalDecay.resolve("minecraft:polished_blackstone_bricks", null));
        assertEquals("minecraft:cracked_deepslate_bricks", PortalDecay.resolve("minecraft:deepslate_bricks", null));
        assertEquals("minecraft:cracked_deepslate_tiles", PortalDecay.resolve("minecraft:deepslate_tiles", null));
    }

    @Test
    void configOverrideWinsOverBuiltIn() {
        Map<String, String> overrides = Map.of("minecraft:obsidian", "minecraft:blackstone");
        assertEquals("minecraft:blackstone", PortalDecay.resolve("minecraft:obsidian", overrides));
    }

    @Test
    void logsStripAndPlanksBurnOut() {
        assertEquals("minecraft:stripped_oak_log", PortalDecay.resolve("minecraft:oak_log", null));
        assertEquals("minecraft:stripped_mangrove_log", PortalDecay.resolve("minecraft:mangrove_log", null));
        assertEquals("minecraft:air", PortalDecay.resolve("minecraft:oak_planks", null));
        assertEquals("minecraft:air", PortalDecay.resolve("minecraft:cherry_planks", null));
        // Modded namespaces keep their namespace when stripping.
        assertEquals("regions_unexplored:stripped_maple_log",
                PortalDecay.resolve("regions_unexplored:maple_log", null));
    }

    @Test
    void alreadyStrippedLogsDoNotDoubleStrip() {
        assertNull(PortalDecay.resolve("minecraft:stripped_oak_log", null));
    }

    @Test
    void unmappedBlocksReturnNull() {
        assertNull(PortalDecay.resolve("minecraft:crying_obsidian", null));
        assertNull(PortalDecay.resolve("minecraft:diamond_block", null));
        assertNull(PortalDecay.resolve(null, null));
    }

    @Test
    void partialPickIsDeterministicForSameSeed() {
        List<Integer> first = PortalDecay.pickPartialIndices(20, 12345L);
        List<Integer> second = PortalDecay.pickPartialIndices(20, 12345L);
        assertEquals(first, second);
    }

    @Test
    void partialPickReturnsOneOrTwoDistinctIndicesInRange() {
        for (long seed = 0; seed < 50; seed++) {
            List<Integer> picked = PortalDecay.pickPartialIndices(14, seed);
            assertTrue(picked.size() >= 1 && picked.size() <= 2, "seed " + seed + ": " + picked);
            assertEquals(picked.size(), picked.stream().distinct().count());
            for (int index : picked) {
                assertTrue(index >= 0 && index < 14);
            }
        }
    }

    @Test
    void partialPickHandlesDegenerateFrames() {
        assertTrue(PortalDecay.pickPartialIndices(0, 1L).isEmpty());
        assertEquals(List.of(0), PortalDecay.pickPartialIndices(1, 99L));
    }
}
