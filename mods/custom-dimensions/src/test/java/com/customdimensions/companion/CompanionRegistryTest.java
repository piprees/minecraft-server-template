package com.customdimensions.companion;

import com.customdimensions.command.Artefacts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.3. Membership of this set is the only thing standing between a vanilla
 * client and a payload it cannot parse, so an unknown protocol version has to
 * leave the player outside it.
 */
class CompanionRegistryTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeEach
    void emptyRegistry() {
        CompanionNetwork.clear();
    }

    @AfterEach
    void leaveNothingBehind() {
        CompanionNetwork.clear();
    }

    @Test
    void aPlayerWhoNeverSaidHelloIsNotCapable() {
        assertFalse(CompanionNetwork.isCompanion(PLAYER));
    }

    @Test
    void matchingVersionMakesAPlayerCapable() {
        CompanionNetwork.onHello(PLAYER, "Tester", Artefacts.stackVersion());
        assertTrue(CompanionNetwork.isCompanion(PLAYER));
    }

    @Test
    void unknownVersionDegradesToVanilla() {
        CompanionNetwork.onHello(PLAYER, "Tester", "0.0.0-not-this-jar");
        assertFalse(CompanionNetwork.isCompanion(PLAYER), "version skew produced a hybrid");
    }

    @Test
    void disconnectLeavesNoEntryToLeakIntoTheNextSession() {
        CompanionNetwork.onHello(PLAYER, "Tester", Artefacts.stackVersion());
        CompanionNetwork.forget(PLAYER);
        assertFalse(CompanionNetwork.isCompanion(PLAYER));
    }

    @Test
    void aPlayerWhoDeclaredNothingIsServerDrawn() {
        assertEquals(PortalViewPreference.SERVER_DRAWN, CompanionNetwork.portalView(PLAYER));
        assertTrue(CompanionNetwork.streamsSlab(PLAYER));
    }

    @Test
    void aDeclarationFromACompanionIsHonoured() {
        CompanionNetwork.onHello(PLAYER, "Tester", Artefacts.stackVersion());
        CompanionNetwork.onPortalView(PLAYER, "Tester",
                new CompanionPayloads.PortalView(true, false, 24, 64));

        assertTrue(CompanionNetwork.portalView(PLAYER).rendersLocally());
        assertEquals(24, CompanionNetwork.portalView(PLAYER).maxRenderDistance());
        assertFalse(CompanionNetwork.streamsSlab(PLAYER));
    }

    /**
     * The declaration is only meaningful alongside a matching protocol
     * version: without one the server would stop describing a far side to a
     * client that cannot receive the description either way.
     */
    @Test
    void aDeclarationFromANonCompanionIsIgnored() {
        CompanionNetwork.onPortalView(PLAYER, "Tester",
                new CompanionPayloads.PortalView(true, false, 24, 64));

        assertEquals(PortalViewPreference.SERVER_DRAWN, CompanionNetwork.portalView(PLAYER));
        assertTrue(CompanionNetwork.streamsSlab(PLAYER));
    }

    @Test
    void theLatestDeclarationWinsSoAToggleTakesEffect() {
        CompanionNetwork.onHello(PLAYER, "Tester", Artefacts.stackVersion());
        CompanionNetwork.onPortalView(PLAYER, "Tester",
                new CompanionPayloads.PortalView(true, false, 24, 64));
        CompanionNetwork.onPortalView(PLAYER, "Tester",
                new CompanionPayloads.PortalView(false, true, 24, 64));

        assertTrue(CompanionNetwork.streamsSlab(PLAYER), "the toggle back did not restore the slab");
    }

    @Test
    void disconnectDropsTheDeclarationTooSoTheNextPlayerOnThatIdIsNotSilenced() {
        CompanionNetwork.onHello(PLAYER, "Tester", Artefacts.stackVersion());
        CompanionNetwork.onPortalView(PLAYER, "Tester",
                new CompanionPayloads.PortalView(true, false, 24, 64));

        CompanionNetwork.forget(PLAYER);

        assertEquals(PortalViewPreference.SERVER_DRAWN, CompanionNetwork.portalView(PLAYER));
        assertTrue(CompanionNetwork.streamsSlab(PLAYER));
    }

    @Test
    void clearDropsTheDeclarationsAsWellAsTheCompanions() {
        CompanionNetwork.onHello(PLAYER, "Tester", Artefacts.stackVersion());
        CompanionNetwork.onPortalView(PLAYER, "Tester",
                new CompanionPayloads.PortalView(true, false, 24, 64));

        CompanionNetwork.clear();

        assertTrue(CompanionNetwork.streamsSlab(PLAYER));
    }
}
