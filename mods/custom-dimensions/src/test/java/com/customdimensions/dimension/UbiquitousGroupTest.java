package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A curated ubiquitous set keeps its own grid rather than joining a pool. See T55. */
class UbiquitousGroupTest {

    @Test
    void theShippedTableClassifiesVanillasUbiquitousSets() {
        for (String setId : new String[]{
                "minecraft:mineshafts", "minecraft:buried_treasures",
                "minecraft:nether_fossils"}) {
            assertTrue(NoisePoolBuilder.ubiquitous(setId),
                    setId + " must keep its own grid");
        }
    }

    @Test
    void adventureContentIsNotUbiquitous() {
        for (String setId : new String[]{
                "minecraft:villages", "minecraft:strongholds", "minecraft:ancient_cities",
                "dungeons_plus:dungeons", "terralith:underground_dungeon"}) {
            assertFalse(NoisePoolBuilder.ubiquitous(setId),
                    setId + " is adventure content and belongs in a pool");
        }
    }

    @Test
    void anUnknownSetIsNotUbiquitous() {
        assertFalse(NoisePoolBuilder.ubiquitous("nonesuch:whatever"));
        assertFalse(NoisePoolBuilder.ubiquitous(null));
    }
}
