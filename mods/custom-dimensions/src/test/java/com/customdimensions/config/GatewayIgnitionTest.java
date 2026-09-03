package com.customdimensions.config;

import com.customdimensions.portal.FrameMatcher;
import com.customdimensions.portal.PortalShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A gateway is a frame like every other portal: an ender eye lights it only
 * where the declared frame block rings a single empty cell. That geometry is
 * also what keeps the twelve-eye ritual possible — an eye used on a
 * stronghold's end_portal_frame finds no gateway frame, so the mixin declines
 * and the eye reaches vanilla and sockets.
 *
 * <p>The two shipped gateways are {@code the_crumbling_reaches}
 * ({@code minecraft:mud_bricks}) and {@code the_pillared_void}
 * ({@code minecraft:polished_basalt}); nine definitions share
 * {@code minecraft:ender_eye}.
 */
class GatewayIgnitionTest {

    private static PortalDefinition gateway(String id, String frameBlock) {
        PortalDefinition def = new PortalDefinition(
                id, frameBlock, "minecraft:ender_eye", "adventure:" + id, "8B7050", 11);
        def.setShape("end_gateway");
        return def;
    }

    private static PortalDefinition crumblingReaches() {
        return gateway("the_crumbling_reaches", "minecraft:mud_bricks");
    }

    private static PortalDefinition pillaredVoid() {
        return gateway("the_pillared_void", "minecraft:polished_basalt");
    }

    // === the frame is the test ===========================================

    @Test
    void aStrongholdFrameIsNoGatewayFrame() {
        assertFalse(crumblingReaches().resolveFrameMatcher().acceptsBlockId("minecraft:end_portal_frame"),
                "the eye must reach vanilla and socket");
        assertFalse(pillaredVoid().resolveFrameMatcher().acceptsBlockId("minecraft:end_portal_frame"));
    }

    @Test
    void noOtherBlockRingsAGatewayEither() {
        FrameMatcher matcher = crumblingReaches().resolveFrameMatcher();
        for (String block : List.of("minecraft:stone", "minecraft:grass_block",
                "minecraft:end_stone", "minecraft:obsidian", "minecraft:dirt")) {
            assertFalse(matcher.acceptsBlockId(block), block + " is not its frame");
        }
    }

    @Test
    void itsOwnFrameBlockRingsOne() {
        assertTrue(crumblingReaches().resolveFrameMatcher().acceptsBlockId("minecraft:mud_bricks"));
        assertTrue(pillaredVoid().resolveFrameMatcher().acceptsBlockId("minecraft:polished_basalt"));
    }

    @Test
    void eachGatewayDemandsItsOwnBlock() {
        // Nine definitions share the ender eye, so a gateway that accepted any
        // frame claimed every one of them and shadowed the rest.
        assertFalse(crumblingReaches().resolveFrameMatcher().acceptsBlockId("minecraft:polished_basalt"));
        assertFalse(pillaredVoid().resolveFrameMatcher().acceptsBlockId("minecraft:mud_bricks"));
    }

    @Test
    void anyMemberOfAFrameListRingsOne() {
        PortalDefinition def = crumblingReaches();
        def.setFrameAccepts(List.of("minecraft:mud_bricks", "minecraft:packed_mud"));
        FrameMatcher matcher = def.resolveFrameMatcher();

        assertTrue(matcher.acceptsBlockId("minecraft:mud_bricks"));
        assertTrue(matcher.acceptsBlockId("minecraft:packed_mud"));
        assertFalse(matcher.acceptsBlockId("minecraft:end_portal_frame"));
    }

    // === and the opening is one cell =====================================

    @Test
    void oneEmptyCellIsAGatewayAndTwoIsNot() {
        assertTrue(PortalShape.matches("end_gateway",
                Set.of(new BlockPos(0, 60, 0)), Direction.Axis.X));
        assertFalse(PortalShape.matches("end_gateway",
                Set.of(new BlockPos(0, 60, 0), new BlockPos(0, 61, 0)), Direction.Axis.X));
    }

    // === a frameless gateway cannot be lit at all ========================

    @Test
    void aGatewayDeclaringNoFrameIgnitesNowhere() {
        PortalDefinition def = gateway("the_open_door", null);
        def.setFrameAccepts(List.of());
        assertTrue(def.resolveFrameMatcher().isEmpty(),
                "an empty matcher is what PortalIgnitionMixin declines on");
    }
}
