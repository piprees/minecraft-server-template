package com.customdimensions.server.level;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeRegistryTest {

    private static final List<String> DIRECTORIES = List.of("dimension", "dimension_type", "worldgen/density_function",
            "worldgen/noise", "worldgen/noise_settings");

    @Test
    void aRuntimeFileNamesItsRegistryAndTheEntryItReplaces() {
        RuntimeRegistry.Target t = RuntimeRegistry.target(
                "runtime_registry/adventure/worldgen/density_function/terrain/overworld/continents.json", DIRECTORIES);
        assertEquals("worldgen/density_function", t.directory());
        assertEquals(Identifier.fromNamespaceAndPath("adventure", "terrain/overworld/continents"), t.id());
        RuntimeRegistry.Target stem = RuntimeRegistry.target("runtime_registry/minecraft/dimension/the_nether.json", DIRECTORIES);
        assertEquals("dimension", stem.directory());
        assertEquals(Identifier.withDefaultNamespace("the_nether"), stem.id());
    }

    @Test
    void aDirectoryMatchesOnlyAWholePathSegment() {
        assertEquals("worldgen/noise_settings",
                RuntimeRegistry.target("runtime_registry/adventure/worldgen/noise_settings/c.json", DIRECTORIES).directory());
        assertEquals("dimension_type", RuntimeRegistry.target("runtime_registry/adventure/dimension_type/c.json", DIRECTORIES).directory());
    }

    @Test
    void aFileOutsideAnyRegistryNamesNothing() {
        assertNull(RuntimeRegistry.target("runtime_registry/adventure/worldgen/carver/c.json", DIRECTORIES));
        assertNull(RuntimeRegistry.target("runtime_registry/adventure/dimension.json", DIRECTORIES));
        assertNull(RuntimeRegistry.target("runtime_registry/c.json", DIRECTORIES));
        assertNull(RuntimeRegistry.target("lazy_dimension/c.json", DIRECTORIES));
    }
}
