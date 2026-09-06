package com.customdimensions.client.realtime;

import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What this client holds of each destination, keyed by chunk.
 *
 * <p>A record of what ARRIVED, kept apart from what is done with it. The
 * server feeds the wedge through each opening nearest-first, and this is the
 * count that says whether it got here — the plain number the plan asks for
 * instead of an absence of errors.
 */
public final class DestinationChunks {

    /** Grepped in the client log for what one destination has received. */
    public static final String RECEIVE_MARKER = "companion-client:destination-chunks";

    private static final Map<Identifier, Set<Long>> HELD = new ConcurrentHashMap<>();

    /**
     * Per destination, a counter every accepted chunk bumps — a re-accept of a
     * key already held included. That re-accept is a resend of changed blocks
     * and leaves {@link #count} identical, so the count cannot say the
     * view is stale and this can.
     */
    private static final Map<Identifier, Integer> REVISIONS = new ConcurrentHashMap<>();

    /**
     * Monotonic for the life of the process, so two readings subtract to a
     * count over a window and a reading of zero means the path never ran.
     */
    private static final AtomicLong ACCEPTS = new AtomicLong();

    private DestinationChunks() {}

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }

    /** Records one chunk; true when it was not already held. */
    public static boolean accept(Identifier destination, int chunkX, int chunkZ) {
        if (destination == null) {
            return false;
        }
        REVISIONS.merge(destination, 1, Integer::sum);
        ACCEPTS.incrementAndGet();
        return HELD.computeIfAbsent(destination, id -> ConcurrentHashMap.newKeySet())
                .add(chunkKey(chunkX, chunkZ));
    }

    public static int count(Identifier destination) {
        Set<Long> held = destination == null ? null : HELD.get(destination);
        return held == null ? 0 : held.size();
    }

    /** Chunks this destination has accepted, re-accepts included. */
    public static int revision(Identifier destination) {
        Integer revision = destination == null ? null : REVISIONS.get(destination);
        return revision == null ? 0 : revision;
    }

    /** Chunks accepted across every destination, re-accepts included. */
    public static long accepts() {
        return ACCEPTS.get();
    }

    public static int total() {
        int sum = 0;
        for (Set<Long> held : HELD.values()) {
            sum += held.size();
        }
        return sum;
    }

    /** Every destination held, with its chunk count. */
    public static Map<Identifier, Integer> counts() {
        Map<Identifier, Integer> out = new HashMap<>();
        HELD.forEach((destination, held) -> out.put(destination, held.size()));
        return out;
    }

    /**
     * The revision survives a drop on purpose: a re-fed destination whose
     * revision restarted could match a build bookmark taken before it went,
     * and the walk would never run.
     */
    public static void drop(Identifier destination) {
        if (destination != null) {
            HELD.remove(destination);
        }
    }

    /** Paired with {@code RealtimeView.clear}, which drops the bookmarks. */
    public static void clear() {
        HELD.clear();
        REVISIONS.clear();
    }
}
