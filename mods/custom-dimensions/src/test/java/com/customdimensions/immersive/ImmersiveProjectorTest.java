package com.customdimensions.immersive;

import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless safety of the projector's teardown paths.
 *
 * <p>{@code PortalHelper.removeZone} calls into {@link ImmersiveProjector},
 * whose static initialiser builds a {@code ChunkTicketType}. If that class
 * could not initialise outside a running server, every unit test touching
 * zone removal would fail, and the comment in {@code removeZone} promising a
 * no-op there would be wrong. This pins both.
 */
class ImmersiveProjectorTest {

    private static PortalHelper.PortalZone zone() {
        PortalDefinition definition = new PortalDefinition(
                "test", "minecraft:obsidian", "minecraft:flint_and_steel", "adventure:the_trap", "#8844FF", 0);
        return new PortalHelper.PortalZone(
                Set.of(new BlockPos(0, 64, 0), new BlockPos(0, 65, 0)),
                definition,
                Direction.Axis.X,
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld")),
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_trap")));
    }

    @Test
    void teardownPathsAreNoOpsWithoutARunningServer() {
        ImmersiveProjector.clear();
        assertDoesNotThrow(() -> ImmersiveProjector.cleanupZone(zone()));
        assertDoesNotThrow(() -> ImmersiveProjector.cleanupZone(null));
        assertDoesNotThrow(() -> ImmersiveProjector.forgetPlayer(
                UUID.randomUUID(), "Bot", "player disconnected"));
        assertDoesNotThrow(() -> ImmersiveProjector.forgetPlayer(
                UUID.randomUUID(), "Bot", "player joined"));
        assertDoesNotThrow(() -> ImmersiveProjector.forgetInWorld(
                UUID.randomUUID(), "Bot",
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"))));
        assertDoesNotThrow(ImmersiveProjector::clear);
    }

    @Test
    void removingAnUnregisteredZoneIsSafe() {
        // The hook PortalHelper.removeZone runs on every zone teardown,
        // immersive or not. It must never be able to throw from the world
        // tick — a broken frame would otherwise crash the server.
        assertDoesNotThrow(() -> PortalHelper.removeZone(zone()));
    }
}
