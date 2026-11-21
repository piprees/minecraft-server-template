package com.customdimensions.core.firewall;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeBaselineTest {
    private static final String RIVER = """
            {"features": [[], ["minecraft:lake_lava_underground", "minecraft:lake_lava_surface"], ["minecraft:amethyst_geode"]],
             "attributes": {"minecraft:gameplay/natural_mob_spawns": {"argument": {"spawn_costs": {},
               "spawns_by_category": {"monster": [{"type": "minecraft:zombie", "count": 4, "weight": 95}],
                                      "water_creature": [{"type": "minecraft:squid", "count": 4, "weight": 2}]}}}}}
            """;

    @Test
    void parsesFeaturesAndSpawnsOf26_3() {
        BiomeBaseline b = BiomeBaseline.parse(RIVER);
        assertEquals(3, b.features().size());
        assertEquals(Set.of("minecraft:amethyst_geode"), b.features().get(2));
        assertEquals(Set.of("minecraft:zombie"), b.spawns().get("monster"));
    }

    @Test
    void additionsAreWhatVanillaAndBridgesLack() {
        BiomeBaseline b = BiomeBaseline.parse(RIVER);
        List<List<String>> current = List.of(List.of(), List.of("minecraft:lake_lava_surface", "farmersdelight:wild_rice"),
                List.of("minecraft:amethyst_geode"), List.of("lostcities:x", "farmersdelight:mushroom_colony"));
        assertEquals(List.of(new BiomeBaseline.Addition(1, "farmersdelight:wild_rice"), new BiomeBaseline.Addition(3, "lostcities:x")),
                b.featureAdditions(current, Set.of("farmersdelight:mushroom_colony")));
        assertEquals(List.of(new BiomeBaseline.Addition(1, "minecraft:amethyst_geode")),
                b.featureAdditions(List.of(List.of(), List.of("minecraft:amethyst_geode")), Set.of()), "a vanilla feature moved to another step is an addition");
    }

    @Test
    void spawnAdditions() {
        BiomeBaseline b = BiomeBaseline.parse(RIVER);
        assertFalse(b.spawnAdded("monster", "minecraft:zombie", Set.of()));
        assertTrue(b.spawnAdded("creature", "clucking_ducks:duck", Set.of()));
        assertFalse(b.spawnAdded("creature", "clucking_ducks:duck", Set.of("clucking_ducks:duck")));
        assertTrue(b.spawnAdded("creature", "minecraft:zombie", Set.of()), "vanilla's type in another category is an addition");
    }

    @Test
    void olderSpawnersKeyAndEmptyBiome() {
        BiomeBaseline b = BiomeBaseline.parse("{\"spawners\": {\"monster\": [{\"type\": \"minecraft:husk\"}]}}");
        assertEquals(Set.of("minecraft:husk"), b.spawns().get("monster"));
        BiomeBaseline empty = BiomeBaseline.parse("{}");
        assertTrue(empty.features().isEmpty() && empty.spawns().isEmpty());
        assertEquals(1, empty.featureAdditions(List.of(List.of("a:b")), Set.of()).size());
    }
}
