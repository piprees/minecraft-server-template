package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a destination-entities snapshot asks the world to do.
 *
 * <p>The recorder is the whole test. A receiver that decodes the payload and
 * then draws nothing passes any assertion about values, so every case here
 * asserts the CALLS — an entity that never reaches the world is the exact
 * defect this feature exists to close.
 */
class EntityFeedPlanTest {

    private static final class Recorder implements EntityFeedPlan.Sink {

        final List<String> calls = new ArrayList<>();

        @Override
        public void spawn(int id) {
            calls.add("spawn " + id);
        }

        @Override
        public void move(int id) {
            calls.add("move " + id);
        }

        @Override
        public void remove(int id) {
            calls.add("remove " + id);
        }
    }

    private static Set<Integer> held(int... ids) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int id : ids) {
            out.add(id);
        }
        return out;
    }

    @Test
    void anUnknownIdIsSpawned() {
        Recorder recorder = new Recorder();
        EntityFeedPlan.apply(held(), List.of(7), new int[0], recorder);
        assertEquals(List.of("spawn 7"), recorder.calls,
                "a fed entity never reached the destination world");
    }

    @Test
    void aKnownIdIsMovedRatherThanSpawnedAgain() {
        Recorder recorder = new Recorder();
        EntityFeedPlan.apply(held(7), List.of(7), new int[0], recorder);
        assertEquals(List.of("move 7"), recorder.calls);
    }

    @Test
    void aDepartedIdIsRemoved() {
        Recorder recorder = new Recorder();
        EntityFeedPlan.apply(held(7, 8), List.of(7), new int[] {8}, recorder);
        assertEquals(List.of("move 7", "remove 8"), recorder.calls);
    }

    /** A boundary-crossing entity is in both lists and must not flicker. */
    @Test
    void anIdInBothListsStays() {
        Recorder recorder = new Recorder();
        EntityFeedPlan.apply(held(7), List.of(7), new int[] {7}, recorder);
        assertEquals(List.of("move 7"), recorder.calls,
                "an entity named present and departed was dropped");
    }

    @Test
    void anIdTheWorldNeverHeldIsNotRemoved() {
        Recorder recorder = new Recorder();
        EntityFeedPlan.apply(held(7), List.of(7), new int[] {99}, recorder);
        assertEquals(List.of("move 7"), recorder.calls);
    }

    @Test
    void theHeldSetFollowsTheSnapshot() {
        Set<Integer> after = EntityFeedPlan.apply(
                held(7, 8), List.of(7, 9), new int[] {8}, new Recorder());
        assertEquals(held(7, 9), after);
    }

    /** A snapshot naming nobody empties the world rather than freezing it. */
    @Test
    void anEmptySnapshotWithDeparturesEmptiesTheWorld() {
        Recorder recorder = new Recorder();
        Set<Integer> after = EntityFeedPlan.apply(held(1, 2), List.of(), new int[] {1, 2}, recorder);
        assertEquals(List.of("remove 1", "remove 2"), recorder.calls);
        assertTrue(after.isEmpty());
    }

    /**
     * The snapshot is the whole truth about who is standing there, so an id it
     * does not name goes whether or not the server also named it departed. A
     * server that has forgotten what this client holds — it drops its record
     * on every teardown — names nobody, and relying on that list alone strands
     * the entity in the world for the rest of the session.
     */
    @Test
    void anIdMissingFromTheSnapshotGoesEvenUnnamed() {
        Recorder recorder = new Recorder();
        Set<Integer> after = EntityFeedPlan.apply(held(7, 8), List.of(7), new int[0], recorder);
        assertEquals(List.of("move 7", "remove 8"), recorder.calls,
                "an entity the snapshot no longer names was left in the world");
        assertEquals(held(7), after);
    }

    @Test
    void nothingIsAskedOfAnEmptySnapshot() {
        Recorder recorder = new Recorder();
        EntityFeedPlan.apply(held(), List.of(), new int[0], recorder);
        assertEquals(List.of(), recorder.calls);
    }
}
