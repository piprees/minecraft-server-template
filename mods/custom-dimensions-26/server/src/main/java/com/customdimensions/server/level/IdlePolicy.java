package com.customdimensions.server.level;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * When an open lazy level has had no players for long enough to unload it.
 * The clock is injected (milliseconds), so the timing is testable without a
 * running game. A non-positive idle time never unloads.
 */
public final class IdlePolicy {
    private final long idleMillis;
    private final LongSupplier clock;
    private final Map<String, Long> emptySince = new HashMap<>();

    public IdlePolicy(long idleMillis, LongSupplier clock) {
        this.idleMillis = idleMillis;
        this.clock = clock;
    }

    /** From {@code settings.json}'s {@code idleUnloadMinutes}; null or non-positive never unloads. */
    public static IdlePolicy ofMinutes(Integer minutes, LongSupplier clock) {
        return new IdlePolicy(minutes == null ? 0 : minutes * 60_000L, clock);
    }

    public boolean enabled() {
        return idleMillis > 0;
    }

    /**
     * Records whether {@code slug} has players now; true once it has been empty
     * for the idle time without a break.
     */
    public boolean observe(String slug, boolean occupied) {
        if (occupied) {
            emptySince.remove(slug);
            return false;
        }
        long now = clock.getAsLong();
        long since = emptySince.computeIfAbsent(slug, s -> now);
        return enabled() && now - since >= idleMillis;
    }

    /** How long {@code slug} has been empty, when it is being watched and empty. */
    public OptionalLong emptyMillis(String slug) {
        Long since = emptySince.get(slug);
        return since == null ? OptionalLong.empty() : OptionalLong.of(clock.getAsLong() - since);
    }

    /** Stops watching {@code slug}: it closed. */
    public void forget(String slug) {
        emptySince.remove(slug);
    }
}
