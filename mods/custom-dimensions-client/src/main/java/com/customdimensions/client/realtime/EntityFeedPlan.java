package com.customdimensions.client.realtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one destination-entities snapshot asks of the world holding it.
 *
 * <p>The payload is a whole snapshot rather than a stream of events, so a
 * spawn and a move carry the same message and only the ids the world already
 * holds tell them apart. This is that decision, over ids alone: the calls it
 * names are the defect's shape, since a receiver that decodes a snapshot
 * correctly and then does nothing with it looks identical to one that works.
 */
public final class EntityFeedPlan {

    private EntityFeedPlan() {}

    /** Records what the snapshot asked for. Calls, not values. */
    public interface Sink {

        /** This id is not in the world yet: create it and add it. */
        void spawn(int id);

        /** This id is already in the world: move it to the new pose. */
        void move(int id);

        /** This id has left the fed disc: take it out of the world. */
        void remove(int id);
    }

    /**
     * Applies one snapshot and answers what the world holds afterwards.
     *
     * <p>{@code present} is the whole truth about who is standing near that
     * arrival, so anything held and not named in it goes — {@code departed} is
     * belt and braces, not the rule. The server drops its record of what a
     * client holds on every teardown, and a client that removed only what it
     * was explicitly told to would strand those entities for the session.
     *
     * <p>An id in both lists is standing there this pass and stays: dropping
     * it would flicker every entity that crosses the boundary.
     */
    public static Set<Integer> apply(Set<Integer> held, List<Integer> present,
            int[] departed, Sink sink) {
        Set<Integer> standing = held == null ? new LinkedHashSet<>() : new LinkedHashSet<>(held);
        Set<Integer> now = new LinkedHashSet<>();
        if (present != null) {
            for (Integer id : present) {
                now.add(id);
                if (standing.contains(id)) {
                    sink.move(id);
                } else {
                    sink.spawn(id);
                    standing.add(id);
                }
            }
        }
        Set<Integer> going = new LinkedHashSet<>(standing);
        if (departed != null) {
            for (int id : departed) {
                going.add(id);
            }
        }
        for (Integer id : going) {
            if (now.contains(id) || !standing.remove(id)) {
                continue;
            }
            sink.remove(id);
        }
        return standing;
    }
}
