package com.customdimensions.client;

import net.minecraft.util.Identifier;

import java.util.function.LongSupplier;

/**
 * The one-shot signal the loading-screen mixin reads.
 *
 * Suppression is only honest while the server has actually preloaded, so the
 * flag is consumed by the first world join that follows and expires on its own
 * if that join never arrives. A stale flag would show a player empty void while
 * chunks stream, which is worse than the screen it replaces.
 */
public final class PendingTransfer {
    /** Generous next to a traversal, short next to a player wandering off. */
    private static final long VALID_FOR_MS = 5_000L;

    private static final LongSupplier SYSTEM_CLOCK = System::currentTimeMillis;

    private static volatile Identifier destination;
    private static volatile long armedAtMs;
    private static volatile LongSupplier clock = SYSTEM_CLOCK;

    private PendingTransfer() {}

    public static void arm(Identifier dimension) {
        destination = dimension;
        armedAtMs = clock.getAsLong();
    }

    /** The armed destination at most once per arm, and never after VALID_FOR_MS. */
    public static Identifier consumeDestination() {
        Identifier armed = destination;
        if (armed == null) {
            return null;
        }
        destination = null;
        return clock.getAsLong() - armedAtMs <= VALID_FOR_MS ? armed : null;
    }

    /** True at most once per arm, and never after VALID_FOR_MS. */
    public static boolean consume() {
        return consumeDestination() != null;
    }

    public static void clear() {
        destination = null;
    }

    /** Test seam for the expiry window; null restores the wall clock. */
    static void setClock(LongSupplier source) {
        clock = source == null ? SYSTEM_CLOCK : source;
    }
}
