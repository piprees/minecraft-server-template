package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldgenRuntimeRemovalsTest {
    private static final Set<String> REMOVE = Set.of("minecraft:patch_fire", "minecraft:crimson_fungi",
            "adventure:biome/magma_land/magma_fires", "adventure:biome/crimson_pinewood/crimson_pines");

    @Test
    void aVanillaBiomeLosesEveryListedFeature() {
        assertEquals(REMOVE, WorldgenRuntime.removals(false, REMOVE));
    }

    @Test
    void aLibraryBiomeLosesOnlyTheLibrarysOwnNamedFeatures() {
        assertEquals(Set.of("adventure:biome/magma_land/magma_fires", "adventure:biome/crimson_pinewood/crimson_pines"),
                WorldgenRuntime.removals(true, REMOVE));
        assertEquals(Set.of(), WorldgenRuntime.removals(true, Set.of("minecraft:patch_fire", "biome/x")));
    }
}
