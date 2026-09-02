package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortalZoneSelfLinkTest {

    private static RegistryKey<World> world(String id) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(id));
    }

    private static PortalHelper.PortalZone zone(RegistryKey<World> source, RegistryKey<World> target) {
        return new PortalHelper.PortalZone(
                Set.of(BlockPos.ORIGIN), (PortalDefinition) null, Direction.Axis.X, source, target);
    }

    @Test
    void aPortalLitInsideTheWorldItLeadsToLeadsBack() {
        // Obsidian in the Nether matches the Nether's own definition. Left
        // as written it targets the world it stands in: the projection shows
        // that world's own terrain and the traversal declines, so the portal
        // does nothing at all.
        RegistryKey<World> nether = world("minecraft:the_nether");

        assertEquals(World.OVERWORLD, zone(nether, nether).targetWorld);
    }

    @Test
    void aCustomDimensionSelfLinkAlsoLeadsBack() {
        RegistryKey<World> crucible = world("adventure:the_crucible");

        assertEquals(World.OVERWORLD, zone(crucible, crucible).targetWorld);
    }

    @Test
    void anOrdinaryOutboundTargetIsUntouched() {
        RegistryKey<World> crucible = world("adventure:the_crucible");

        assertEquals(crucible, zone(World.OVERWORLD, crucible).targetWorld);
    }
}
