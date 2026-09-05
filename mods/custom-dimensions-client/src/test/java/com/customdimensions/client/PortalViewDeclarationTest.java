package com.customdimensions.client;

import com.customdimensions.client.config.RealtimeSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalViewDeclarationTest {

    @BeforeEach
    void reset() {
        PortalViewDeclaration.clear();
    }

    @Test
    void aClientThatCanStandDestinationsUpDeclaresNothingExtra() {
        assertFalse(PortalViewDeclaration.destinationRefused(false));
        assertFalse(PortalViewDeclaration.renderPassDisabled(false));
        assertFalse(PortalViewDeclaration.refused());
        assertEquals(RealtimeSettings.DEFAULTS,
                PortalViewDeclaration.declared(RealtimeSettings.DEFAULTS));
    }

    /**
     * The latch. Re-declaring changes what the server sends, which changes
     * what this client holds; a second refusal must not start that again.
     */
    @Test
    void aClientRefusingTwiceOwesExactlyOneDeclaration() {
        assertTrue(PortalViewDeclaration.destinationRefused(true),
                "the first refusal must be declared or the player sees a bare aperture for ever");
        assertFalse(PortalViewDeclaration.destinationRefused(true));
        assertFalse(PortalViewDeclaration.destinationRefused(true));
        assertFalse(PortalViewDeclaration.renderPassDisabled(true),
                "a second reason for the same stand-down is still one declaration");
    }

    @Test
    void aRenderPassThatStoodItselfDownIsAlsoARefusal() {
        assertTrue(PortalViewDeclaration.renderPassDisabled(true));
        assertTrue(PortalViewDeclaration.refused());
        assertEquals("render-pass-disabled", PortalViewDeclaration.reason());
    }

    @Test
    void aRefusedDestinationNamesItselfInTheReason() {
        assertTrue(PortalViewDeclaration.destinationRefused(true));
        assertEquals("destination-refused", PortalViewDeclaration.reason());
    }

    /**
     * The whole point of the fallback: the player's own server-side setting is
     * already true, so forcing client-side off puts them back on the slab with
     * nothing having to invent a preference for them.
     */
    @Test
    void aRefusalHandsTheFarSideBackToTheServer() {
        PortalViewDeclaration.destinationRefused(true);

        RealtimeSettings declared = PortalViewDeclaration.declared(RealtimeSettings.DEFAULTS);

        assertFalse(declared.renderClientSidePortals());
        assertTrue(declared.effectiveServerSide(), "the player was left with no preview at all");
        assertTrue(RealtimeSettings.DEFAULTS.renderClientSidePortals(),
                "the player's stored setting must not be written; they did not choose this");
    }

    /** A player who turned the slab off is not given it back by a refusal. */
    @Test
    void aRefusalDoesNotOverrideAnOptOutFromTheSlab() {
        PortalViewDeclaration.destinationRefused(true);

        RealtimeSettings declared = PortalViewDeclaration.declared(
                new RealtimeSettings(true, 16, true, false, false, true, true, false));

        assertFalse(declared.renderClientSidePortals());
        assertFalse(declared.effectiveServerSide());
    }

    /** A new connection, a new world, or a setting change tries this client again. */
    @Test
    void clearingReArmsTheLocalRender() {
        PortalViewDeclaration.destinationRefused(true);
        PortalViewDeclaration.clear();

        assertFalse(PortalViewDeclaration.refused());
        assertEquals("", PortalViewDeclaration.reason());
        assertTrue(PortalViewDeclaration.destinationRefused(true),
                "a refusal after re-arming must be declared again");
    }

    /** Grepped in the client log; a changed literal reads as a bug that stopped. */
    @Test
    void refusalMarker() {
        assertEquals("companion-client:client-side-refused",
                PortalViewDeclaration.REFUSAL_MARKER);
    }
}
