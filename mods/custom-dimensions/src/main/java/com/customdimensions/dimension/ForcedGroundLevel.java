package com.customdimensions.dimension;

/**
 * The height a {@code structures.force} entry pinned, held for the duration of
 * one start attempt so the generator's height queries answer with it.
 *
 * <p>A thread-local, unlike {@link ForcedStartOverride}'s registry, because the
 * question is different. "Is this attempt forced?" is answerable from the
 * attempt's own arguments; "which forced attempt is this height query serving?"
 * is not — {@code getHeight} sees a column and nothing else. The scope is one
 * synchronous {@code createStructureStart} call inside a try/finally, so there
 * is no staleness window and no dependence on a callback another mod's
 * transform could starve (the failure class in TROUBLESHOOTING.md#t24).
 *
 * <p>Chunk generation runs on c2me worker threads; each start attempt is one
 * thread's synchronous call, so a thread-local is exactly the right width.
 */
public final class ForcedGroundLevel {

    private static final ThreadLocal<Integer> PINNED = new ThreadLocal<>();

    private ForcedGroundLevel() {
    }

    /** Pins every height query on this thread to {@code y} until disarmed. */
    public static void arm(int y) {
        PINNED.set(y);
    }

    /** Releases the pin. Always call from a finally. */
    public static void disarm() {
        PINNED.remove();
    }

    /** The pinned height, or null when this thread is not inside a pinned attempt. */
    public static Integer pinned() {
        return PINNED.get();
    }
}
