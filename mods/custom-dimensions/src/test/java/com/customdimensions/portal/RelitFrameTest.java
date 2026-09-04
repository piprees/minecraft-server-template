package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-lighting a frame that is already a portal. The dedup in
 * {@code addZoneIfAbsent} is what stops a second zone appearing over one
 * interior — but the answer is only worth computing if a caller reads it, and
 * an ignition that registers nothing must not charge the player for it.
 *
 * <p>The identity is (target world, axis, interior), so each of the three is
 * exercised as its own "this is a different portal" case: a dedup keyed too
 * loosely refuses a legitimate second portal, which is the same silence
 * pointing the other way.
 */
class RelitFrameTest {

    private static final RegistryKey<World> OVERWORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));

    private final List<PortalHelper.PortalZone> registered = new ArrayList<>();

    private static PortalDefinition crucible() {
        return new PortalDefinition("the_crucible", "minecraft:copper_block", "minecraft:diamond",
                "adventure:the_crucible", "8BAF5B", 11);
    }

    private static PortalDefinition nexus() {
        return new PortalDefinition("the_crimson_nexus", "minecraft:nether_bricks",
                "minecraft:flint_and_steel", "adventure:the_crimson_nexus", "AF2B2B", 11);
    }

    /** The Crucible rig's real opening: 2 wide, 3 tall, on the X plane. */
    private static Set<BlockPos> opening(int x, int y, int z) {
        return Set.of(
                new BlockPos(x, y, z), new BlockPos(x + 1, y, z),
                new BlockPos(x, y + 1, z), new BlockPos(x + 1, y + 1, z),
                new BlockPos(x, y + 2, z), new BlockPos(x + 1, y + 2, z));
    }

    private static PortalHelper.PortalZone zone(PortalDefinition def, Set<BlockPos> interior,
            Direction.Axis axis) {
        return new PortalHelper.PortalZone(interior, def, axis, OVERWORLD, def.getTargetKey());
    }

    private boolean register(PortalHelper.PortalZone zone) {
        boolean added = PortalHelper.registerZone(zone);
        if (added) {
            this.registered.add(zone);
        }
        return added;
    }

    @BeforeEach
    @AfterEach
    void clear() {
        for (PortalHelper.PortalZone zone : this.registered) {
            PortalHelper.removeZone(zone);
        }
        this.registered.clear();
    }

    // === the answer the mixin has to read ================================

    @Test
    void lightingAFrameTheFirstTimeRegistersIt() {
        assertTrue(register(zone(crucible(), opening(3260, 85, 2883), Direction.Axis.X)),
                "a frame nobody has lit is a new portal");
        assertEquals(1, PortalHelper.getSourceZones(OVERWORLD).size());
    }

    @Test
    void lightingAnAlreadyLitFrameRegistersNothingAndSaysSo() {
        register(zone(crucible(), opening(3260, 85, 2883), Direction.Axis.X));

        boolean added = PortalHelper.registerZone(
                zone(crucible(), opening(3260, 85, 2883), Direction.Axis.X));

        assertFalse(added, "an equivalent zone is already there, so nothing was registered");
        assertEquals(1, PortalHelper.getSourceZones(OVERWORLD).size(),
                "one interior, one zone — a second doubles particles, projection and aura");
    }

    // === and the three things that make a DIFFERENT portal ===============

    @Test
    void aDifferentInteriorIsADifferentPortal() {
        register(zone(crucible(), opening(3260, 85, 2883), Direction.Axis.X));

        assertTrue(register(zone(crucible(), opening(14, 132, 19), Direction.Axis.X)),
                "the second Crucible rig is its own portal, not a duplicate of the first");
        assertEquals(2, PortalHelper.getSourceZones(OVERWORLD).size());
    }

    @Test
    void aDifferentAxisIsADifferentPortal() {
        register(zone(crucible(), opening(3260, 85, 2883), Direction.Axis.X));

        assertTrue(register(zone(crucible(), opening(3260, 85, 2883), Direction.Axis.Z)));
    }

    @Test
    void aDifferentDestinationIsADifferentPortal() {
        register(zone(crucible(), opening(3260, 85, 2883), Direction.Axis.X));

        assertTrue(register(zone(nexus(), opening(3260, 85, 2883), Direction.Axis.X)),
                "same opening, another destination — the dedup must not swallow it");
    }

    // === the reason the player is told ===================================

    @Test
    void alreadyLitIsTheFurthestAnAttemptCanGet() {
        // Nothing about the frame is wrong; it is a portal already. Anything
        // that outranked it would be reported instead of the truth.
        for (IgnitionRefusal reason : IgnitionRefusal.values()) {
            assertTrue(IgnitionRefusal.ALREADY_LIT.progress() >= reason.progress(),
                    reason + " must not outrank ALREADY_LIT");
        }
        assertEquals(IgnitionRefusal.ALREADY_LIT,
                IgnitionRefusal.furthest(IgnitionRefusal.AXIS_NOT_ALLOWED, IgnitionRefusal.ALREADY_LIT));
    }
}
