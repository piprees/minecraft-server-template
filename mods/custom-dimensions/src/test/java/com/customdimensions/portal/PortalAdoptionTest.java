package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry-free half of adoption: which definition an unowned frame resolves
 * to, and the per-boot gate that keeps the tick path off a frame scan. Block
 * and tag RESOLUTION needs a live registry and is covered by the bot recipes.
 */
class PortalAdoptionTest {

    private static RegistryKey<World> world(String id) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(id));
    }

    private static PortalDefinition def(String id, String frameBlock, String target) {
        return new PortalDefinition(id, frameBlock, "minecraft:flint_and_steel", target, "#FFFFFF", 0);
    }

    @BeforeEach
    void clearGate() {
        PortalAdoption.resetAttempts();
    }

    @Test
    void theDefinitionWhoseFrameFitsIsChosen() {
        // A vanilla obsidian portal standing in a configured dimension: the
        // frame is the only evidence of what it was meant to be.
        List<PortalDefinition> portals = List.of(
                def("crucible", "minecraft:crimson_planks", "adventure:the_crucible"),
                def("nether", "minecraft:obsidian", "minecraft:the_nether"),
                def("gardens", "minecraft:quartz_block", "adventure:the_blossom_gardens"));

        List<PortalDefinition> picked =
                PortalAdoption.candidates(portals, List.of("minecraft:obsidian"));

        assertEquals(1, picked.size());
        assertEquals("nether", picked.get(0).getId());
    }

    @Test
    void configOrderDecidesBetweenTwoDefinitionsThatFit() {
        List<PortalDefinition> portals = List.of(
                def("first", "minecraft:obsidian", "adventure:the_crucible"),
                def("second", "minecraft:obsidian", "minecraft:the_nether"));

        List<PortalDefinition> picked =
                PortalAdoption.candidates(portals, List.of("minecraft:obsidian"));

        assertEquals(List.of("first", "second"),
                picked.stream().map(PortalDefinition::getId).toList());
    }

    @Test
    void acceptFormsCountAsAFrameMatch() {
        PortalDefinition mossy = def("mossy", "minecraft:stone_bricks", "adventure:the_crucible");
        mossy.setFrameAccepts(List.of("minecraft:stone_bricks", "minecraft:mossy_stone_bricks"));

        List<PortalDefinition> picked =
                PortalAdoption.candidates(List.of(mossy), List.of("minecraft:mossy_stone_bricks"));

        assertEquals(1, picked.size());
        assertEquals("mossy", picked.get(0).getId());
    }

    @Test
    void aFrameNoDefinitionDescribesHasNoCandidate() {
        // Nothing is invented for it: the caller logs and leaves it inert.
        List<PortalDefinition> portals = List.of(
                def("nether", "minecraft:obsidian", "minecraft:the_nether"));

        assertTrue(PortalAdoption.candidates(portals, List.of("minecraft:mud_bricks")).isEmpty());
    }

    @Test
    void anAreaIsOfferedForAdoptionOnceOnly() {
        RegistryKey<World> crucible = world("adventure:the_crucible");
        Set<BlockPos> area = Set.of(new BlockPos(10, 64, 10), new BlockPos(10, 65, 10));

        assertTrue(PortalAdoption.claimAttempt(crucible, area));
        assertFalse(PortalAdoption.claimAttempt(crucible, area));
        assertFalse(PortalAdoption.claimAttempt(crucible, area));
    }

    @Test
    void anotherAreaAndAnotherWorldAreStillOffered() {
        RegistryKey<World> crucible = world("adventure:the_crucible");
        Set<BlockPos> area = Set.of(new BlockPos(10, 64, 10));

        assertTrue(PortalAdoption.claimAttempt(crucible, area));
        assertTrue(PortalAdoption.claimAttempt(crucible, Set.of(new BlockPos(300, 70, 300))));
        assertTrue(PortalAdoption.claimAttempt(world("minecraft:overworld"), area));
    }
}
