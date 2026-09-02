package com.customdimensions.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handshake decides whether the server ever knows this client exists, and
 * a send that never happens is indistinguishable from a feature that does not
 * work. Every way the attempt can be lost is a case here: never armed, not yet
 * in game, already sent, disconnected, and out of attempts.
 */
class HandshakeSenderTest {

    @BeforeEach
    @AfterEach
    void disarm() {
        HandshakeSender.disarm();
    }

    @Test
    void unarmedNeverSends() {
        assertFalse(HandshakeSender.shouldSend(true));
    }

    @Test
    void armedAndInGameSends() {
        HandshakeSender.arm();
        assertTrue(HandshakeSender.shouldSend(true));
    }

    @Test
    void armedButNotInGameWaitsAndStaysArmed() {
        HandshakeSender.arm();
        assertFalse(HandshakeSender.shouldSend(false));
        assertTrue(HandshakeSender.isArmed(), "gave up on the first tick before the client was ready");
        assertTrue(HandshakeSender.shouldSend(true));
    }

    @Test
    void aFailedSendIsRetriedBecauseNothingConfirmedIt() {
        HandshakeSender.arm();
        assertTrue(HandshakeSender.shouldSend(true));
        assertTrue(HandshakeSender.shouldSend(true), "a throw from send lost the handshake for the session");
    }

    @Test
    void sentStopsFurtherAttempts() {
        HandshakeSender.arm();
        assertTrue(HandshakeSender.shouldSend(true));
        HandshakeSender.sent();
        assertFalse(HandshakeSender.shouldSend(true), "handshake sent more than once per join");
    }

    @Test
    void givesUpRatherThanRetryingForever() {
        HandshakeSender.arm();
        for (int tick = 0; tick < HandshakeSender.ATTEMPT_TICKS; tick++) {
            assertFalse(HandshakeSender.shouldSend(false));
        }
        assertFalse(HandshakeSender.isArmed());
        assertFalse(HandshakeSender.shouldSend(true), "kept trying past the attempt budget");
    }

    @Test
    void theAttemptBudgetIsSpentOnlyByTicks() {
        HandshakeSender.arm();
        for (int tick = 0; tick < HandshakeSender.ATTEMPT_TICKS - 1; tick++) {
            HandshakeSender.shouldSend(false);
        }
        assertTrue(HandshakeSender.shouldSend(true), "the last attempt was not available");
    }

    @Test
    void disconnectDisarms() {
        HandshakeSender.arm();
        HandshakeSender.disarm();
        assertFalse(HandshakeSender.shouldSend(true));
    }
}
