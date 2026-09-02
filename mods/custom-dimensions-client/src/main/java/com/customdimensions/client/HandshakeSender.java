package com.customdimensions.client;

/**
 * Decides WHEN the handshake goes out. Sending it needs a live client, so the
 * timing lives here where it can be tested and the send stays in the caller.
 *
 * <p>Armed on join and retried each tick until the client is in game, because
 * the JOIN callback runs before the client is guaranteed to have a network
 * handler. Runs out of attempts rather than retrying forever — a server that
 * never receives it leaves the player on exactly the vanilla path.
 */
public final class HandshakeSender {
    /** Client ticks to keep trying before giving up and staying vanilla. */
    static final int ATTEMPT_TICKS = 100;

    private static int ticksLeft;

    private HandshakeSender() {}

    public static void arm() {
        ticksLeft = ATTEMPT_TICKS;
    }

    public static void disarm() {
        ticksLeft = 0;
    }

    /** True on a tick the handshake should be attempted; spends one attempt. */
    public static boolean shouldSend(boolean inGame) {
        if (ticksLeft <= 0) {
            return false;
        }
        ticksLeft--;
        return inGame;
    }

    /** Stops further attempts. Called only after a send that did not throw. */
    public static void sent() {
        ticksLeft = 0;
    }

    static boolean isArmed() {
        return ticksLeft > 0;
    }
}
