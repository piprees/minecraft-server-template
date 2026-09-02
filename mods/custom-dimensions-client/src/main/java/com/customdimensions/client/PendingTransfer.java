package com.customdimensions.client;

import net.minecraft.util.Identifier;

import java.util.function.LongSupplier;

/**
 * The signal both loading-screen mixins read.
 *
 * One crossing installs a terrain screen twice — joinWorld's reset(Screen) and
 * startWorldLoading's setScreen(Screen) — so reads do not consume. The arm ends
 * when ArrivalScreen ticks, or on its own after VALID_FOR_MS.
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

    /** The armed destination while the window holds, disarming once it lapses. */
    public static Identifier peekDestination() {
        Identifier armed = destination;
        if (armed == null) {
            return null;
        }
        if (clock.getAsLong() - armedAtMs > VALID_FOR_MS) {
            destination = null;
            return null;
        }
        return armed;
    }

    /** True while a preloaded arrival is still expected. */
    public static boolean isArmed() {
        return peekDestination() != null;
    }

    public static void clear() {
        destination = null;
    }

    /** Test seam for the expiry window; null restores the wall clock. */
    static void setClock(LongSupplier source) {
        clock = source == null ? SYSTEM_CLOCK : source;
    }
}
