package com.customdimensions.companion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
        CompanionNetwork.onHello(PLAYER, "Tester",CompanionPayloads.PROTOCOL_VERSION);
        assertTrue(CompanionNetwork.isCompanion(PLAYER));
    }

    @Test
    void unknownVersionDegradesToVanilla() {
        CompanionNetwork.onHello(PLAYER, "Tester",999);
        assertFalse(CompanionNetwork.isCompanion(PLAYER), "version skew produced a hybrid");
    }

    @Test
    void disconnectLeavesNoEntryToLeakIntoTheNextSession() {
        CompanionNetwork.onHello(PLAYER, "Tester",CompanionPayloads.PROTOCOL_VERSION);
        CompanionNetwork.forget(PLAYER);
        assertFalse(CompanionNetwork.isCompanion(PLAYER));
    }
}
