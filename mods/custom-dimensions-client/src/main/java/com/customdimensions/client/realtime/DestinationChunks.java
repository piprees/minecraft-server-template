package com.customdimensions.client.realtime;

import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
        return HELD.computeIfAbsent(destination, id -> ConcurrentHashMap.newKeySet())
                .add(chunkKey(chunkX, chunkZ));
    }

    public static int count(Identifier destination) {
        Set<Long> held = destination == null ? null : HELD.get(destination);
        return held == null ? 0 : held.size();
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

    public static void drop(Identifier destination) {
        if (destination != null) {
            HELD.remove(destination);
        }
    }

    public static void clear() {
        HELD.clear();
    }
}
