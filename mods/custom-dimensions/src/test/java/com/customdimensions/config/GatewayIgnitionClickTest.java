package com.customdimensions.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A gateway is placed on the clicked face with no flood-fill, so the block
 * clicked is the only frame test it has. Without that test an ender eye
 * lands a gateway on a stronghold's end_portal_frame, the mixin cancels
 * before vanilla can socket the eye, and the twelve-eye ritual cannot be
 * completed by any player.
 *
 * <p>The two shipped gateways are {@code the_crumbling_reaches}
 * ({@code minecraft:mud_bricks}) and {@code the_pillared_void}
 * ({@code minecraft:polished_basalt}); nine definitions share
 * {@code minecraft:ender_eye}.
 */
class GatewayIgnitionClickTest {

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

    // === the defect ======================================================

    @Test
    void anEnderEyeOnAStrongholdFrameIgnitesNoGateway() {
        assertFalse(crumblingReaches().acceptsIgnitionClick("minecraft:end_portal_frame"),
                "the eye must reach vanilla and socket");
        assertFalse(pillaredVoid().acceptsIgnitionClick("minecraft:end_portal_frame"));
    }

    @Test
    void noOtherBlockCarriesAGatewayEither() {
        PortalDefinition def = crumblingReaches();
        for (String clicked : List.of("minecraft:stone", "minecraft:grass_block",
                "minecraft:end_stone", "minecraft:obsidian", "minecraft:dirt")) {
            assertFalse(def.acceptsIgnitionClick(clicked), clicked + " is not its frame");
        }
    }

    @Test
    void anAbsentClickedBlockIsNotAMatch() {
        assertFalse(crumblingReaches().acceptsIgnitionClick(null));
    }

    // === and it still ignites where it should ============================

    @Test
    void itsOwnFrameBlockStillPlacesAGateway() {
        assertTrue(crumblingReaches().acceptsIgnitionClick("minecraft:mud_bricks"));
        assertTrue(pillaredVoid().acceptsIgnitionClick("minecraft:polished_basalt"));
    }

    @Test
    void eachGatewayDemandsItsOwnBlock() {
        // Nine definitions share the ender eye, so a gateway that accepted any
        // click claimed every one of them and shadowed the rest.
        assertFalse(crumblingReaches().acceptsIgnitionClick("minecraft:polished_basalt"));
        assertFalse(pillaredVoid().acceptsIgnitionClick("minecraft:mud_bricks"));
    }

    @Test
    void anyMemberOfAFrameListPlacesOne() {
        PortalDefinition def = crumblingReaches();
        def.setFrameAccepts(List.of("minecraft:mud_bricks", "minecraft:packed_mud"));

        assertTrue(def.acceptsIgnitionClick("minecraft:mud_bricks"));
        assertTrue(def.acceptsIgnitionClick("minecraft:packed_mud"));
        assertFalse(def.acceptsIgnitionClick("minecraft:end_portal_frame"));
    }

    // === every other shape is unaffected =================================

    @Test
    void aFloodFilledShapeDoesNotCareWhatWasClicked() {
        // Standard, door, doorway, end_exit and pattern all find their frame
        // by flood-fill from the click, so the clicked block is not the test.
        PortalDefinition def = new PortalDefinition("the_lost_outpost", "minecraft:cobblestone",
                "minecraft:torch", "adventure:the_lost_outpost", "8844FF", 0);

        assertTrue(def.acceptsIgnitionClick("minecraft:end_portal_frame"));
        assertTrue(def.acceptsIgnitionClick("minecraft:air"));
        assertTrue(def.acceptsIgnitionClick(null));
    }

    @Test
    void anEndExitIsNotAGateway() {
        PortalDefinition def = gateway("the_starwell", "minecraft:tinted_glass");
        def.setShape("end_exit");
        assertTrue(def.acceptsIgnitionClick("minecraft:end_portal_frame"));
    }

    // === the path no shipped config takes ================================

    @Test
    void aGatewayDeclaringNoFrameStillPlacesAnywhere() {
        // Preserved deliberately, and warned about at boot rather than
        // silently refused: no shipped config declares one.
        PortalDefinition def = gateway("the_open_door", null);
        def.setFrameAccepts(List.of());

        assertTrue(def.acceptsIgnitionClick("minecraft:end_portal_frame"));
        assertTrue(def.acceptsIgnitionClick("minecraft:stone"));
    }
}
