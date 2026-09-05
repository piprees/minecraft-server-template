package com.customdimensions.client.realtime;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counters a verifier reads to tell three states apart: a feed that never
 * arrived, one that arrived with no world to apply it to, and one that was
 * applied. Standing a world up needs a bootstrapped client, so what is pinned
 * here is the middle state — the one that is otherwise silent, and the one a
 * point-in-time entity count of zero cannot be distinguished from.
 *
 * <p>Every assertion is a DELTA. The counters are monotonic for the life of
 * the process and no reset seam exists, because a counter that can go
 * backwards is worth less than one that cannot ([T63]).
 */
class DestinationEntitiesTest {

    private static final Identifier NEXUS = Identifier.of("adventure:the_crimson_nexus");

    @Test
    void aSnapshotWithNoWorldStandingIsCountedRatherThanSilent() {
        long snapshots = DestinationEntities.snapshots();
        long dropped = DestinationEntities.snapshotsDropped();

        DestinationEntities.accept(null, NEXUS, List.of(), List.of(), new int[0]);

        assertEquals(1L, DestinationEntities.snapshots() - snapshots,
                "a decoded snapshot went uncounted");
        assertEquals(1L, DestinationEntities.snapshotsDropped() - dropped,
                "a snapshot nothing could apply reads the same as one never sent");
        assertEquals(0, DestinationEntities.count(NEXUS));
        assertEquals(0, DestinationEntities.total());
    }

    @Test
    void aSnapshotThatCouldNotBeAppliedSpawnsNothing() {
        long spawned = DestinationEntities.spawned();
        long moved = DestinationEntities.moved();
        long removed = DestinationEntities.removed();
        long refused = DestinationEntities.refused();

        DestinationEntities.accept(null, NEXUS, List.of(), List.of(), new int[0]);

        assertEquals(0L, DestinationEntities.spawned() - spawned);
        assertEquals(0L, DestinationEntities.moved() - moved);
        assertEquals(0L, DestinationEntities.removed() - removed);
        assertEquals(0L, DestinationEntities.refused() - refused);
    }

    /** The counters outlive a join, so a window spanning one still subtracts. */
    @Test
    void clearingDoesNotRewindTheCounters() {
        DestinationEntities.accept(null, NEXUS, List.of(), List.of(), new int[0]);
        long snapshots = DestinationEntities.snapshots();

        DestinationEntities.clear();

        assertTrue(DestinationEntities.snapshots() >= snapshots,
                "clear() rewound a counter a window is measured against");
    }

    @Test
    void retainingWithNothingHeldIsANoOp() {
        DestinationEntities.retain(Set.of());
        DestinationEntities.retain(null);
        assertEquals(0, DestinationEntities.total());
    }

    @Test
    void droppingAnUnknownDestinationIsANoOp() {
        DestinationEntities.drop(NEXUS);
        DestinationEntities.drop(null);
        assertTrue(DestinationEntities.counts().isEmpty());
    }
}
