package com.customdimensions.core.zone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidCellBlocksTest {

    @Test
    void stoneFluidsAndTheBedrockFloorAreTerrain() {
        assertTrue(VoidCellBlocks.terrain("minecraft:stone", 40, -64));
        assertTrue(VoidCellBlocks.terrain("minecraft:deepslate", -30, -64));
        assertTrue(VoidCellBlocks.terrain("minecraft:water", 62, -64));
        assertTrue(VoidCellBlocks.terrain("minecraft:lava", -55, -64));
        assertTrue(VoidCellBlocks.terrain("minecraft:bedrock", -64, -64));
        assertTrue(VoidCellBlocks.terrain("minecraft:bedrock", -60, -64));
    }

    @Test
    void anEndSpikesCrownAndAVillagesPlanksAreFeatures() {
        assertFalse(VoidCellBlocks.terrain("minecraft:bedrock", 103, -64), "the bedrock under an End crystal");
        assertFalse(VoidCellBlocks.terrain("minecraft:bedrock", -59, -64));
        assertFalse(VoidCellBlocks.terrain("minecraft:obsidian", -64, -64));
        assertFalse(VoidCellBlocks.terrain("minecraft:oak_planks", 60, -64));
    }
}
