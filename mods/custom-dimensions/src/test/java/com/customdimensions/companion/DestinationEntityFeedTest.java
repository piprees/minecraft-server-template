package com.customdimensions.companion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of the destination's entities reach a client drawing the far side.
 *
 * <p>The fixture is the main rig's arrival: {@code the_amplified_reaches}
 * around a column at 3000, 80, 2000. Every position below is a destination
 * coordinate, which is what the wire carries.
 */
class DestinationEntityFeedTest {

    private static final double CX = 3000.0;
    private static final double CY = 80.0;
    private static final double CZ = 2000.0;

    private static DestinationEntityFeed.Seen at(int id, double x, double y, double z) {
        return new DestinationEntityFeed.Seen(id, x, y, z, 0.0f, 0.0f, 0.0f);
    }

    private static List<DestinationEntityFeed.Seen> select(
            List<DestinationEntityFeed.Seen> candidates, int cap) {
        return DestinationEntityFeed.select(candidates, CX, CY, CZ,
                DestinationEntityFeed.RADIUS, DestinationEntityFeed.HEIGHT, cap);
    }

    private static int[] ids(List<DestinationEntityFeed.Seen> seen) {
        int[] out = new int[seen.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = seen.get(i).id();
        }
        return out;
    }

    @Test
    void anEntityBeyondTheDiscIsNotSent() {
        List<DestinationEntityFeed.Seen> picked = select(List.of(
                at(1, CX + 4.0, CY, CZ),
                at(2, CX + DestinationEntityFeed.RADIUS + 8.0, CY, CZ)), 16);
        assertArrayEquals(new int[] {1}, ids(picked),
                "an entity outside the horizontal radius was fed");
    }

    @Test
    void anEntityAboveTheReachIsNotSent() {
        List<DestinationEntityFeed.Seen> picked = select(List.of(
                at(1, CX, CY + 4.0, CZ),
                at(2, CX, CY + DestinationEntityFeed.HEIGHT + 4.0, CZ)), 16);
        assertArrayEquals(new int[] {1}, ids(picked),
                "an entity outside the vertical reach was fed");
    }

    /** The one standing in the doorway matters more than the one at the horizon. */
    @Test
    void theNearestGoFirst() {
        List<DestinationEntityFeed.Seen> picked = select(List.of(
                at(7, CX + 30.0, CY, CZ),
                at(8, CX + 2.0, CY, CZ),
                at(9, CX + 12.0, CY, CZ)), 16);
        assertArrayEquals(new int[] {8, 9, 7}, ids(picked));
    }

    @Test
    void theCapHoldsAndKeepsTheNearest() {
        List<DestinationEntityFeed.Seen> crowd = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            crowd.add(at(100 + i, CX + 1.0 + i, CY, CZ));
        }
        List<DestinationEntityFeed.Seen> picked = select(crowd, DestinationEntityFeed.MAX_ENTITIES);
        assertEquals(DestinationEntityFeed.MAX_ENTITIES, picked.size(),
                "the cap did not hold");
        assertEquals(100, picked.get(0).id());
        assertEquals(100 + DestinationEntityFeed.MAX_ENTITIES - 1,
                picked.get(picked.size() - 1).id());
    }

    /** A cap of zero is off, not empty: it is what an unbounded feed would mean. */
    @Test
    void aCapOfZeroSendsNothing() {
        assertEquals(0, select(List.of(at(1, CX, CY, CZ)), 0).size());
    }

    @Test
    void aStillSceneIsNotResent() {
        List<DestinationEntityFeed.Seen> scene = List.of(at(1, CX, CY, CZ), at(2, CX + 3.0, CY, CZ));
        assertFalse(DestinationEntityFeed.changed(scene, scene),
                "an unchanged snapshot was sent again");
    }

    @Test
    void aMovedEntityIsResent() {
        assertTrue(DestinationEntityFeed.changed(
                List.of(at(1, CX, CY, CZ)),
                List.of(at(1, CX + 0.5, CY, CZ))));
    }

    /** Below the wire's own resolution is not a change. */
    @Test
    void aMoveUnderTheQuantumIsNotAChange() {
        assertFalse(DestinationEntityFeed.changed(
                List.of(at(1, CX, CY, CZ)),
                List.of(at(1, CX + DestinationEntityFeed.POSITION_QUANTUM / 8.0, CY, CZ))));
    }

    @Test
    void aTurnedEntityIsResent() {
        assertTrue(DestinationEntityFeed.changed(
                List.of(new DestinationEntityFeed.Seen(1, CX, CY, CZ, 0.0f, 0.0f, 0.0f)),
                List.of(new DestinationEntityFeed.Seen(1, CX, CY, CZ, 90.0f, 0.0f, 0.0f))));
    }

    @Test
    void anArrivalIsAChange() {
        assertTrue(DestinationEntityFeed.changed(
                List.of(at(1, CX, CY, CZ)),
                List.of(at(1, CX, CY, CZ), at(2, CX + 1.0, CY, CZ))));
    }

    @Test
    void anEntityThatLeftIsNamedForRemoval() {
        assertArrayEquals(new int[] {2}, DestinationEntityFeed.departed(
                List.of(at(1, CX, CY, CZ), at(2, CX + 1.0, CY, CZ)),
                List.of(at(1, CX, CY, CZ))));
    }

    @Test
    void nothingLeavesWhenNothingLeft() {
        List<DestinationEntityFeed.Seen> scene = List.of(at(1, CX, CY, CZ));
        assertEquals(0, DestinationEntityFeed.departed(scene, scene).length);
    }

    @Test
    void theCadenceHoldsBetweenSnapshots() {
        assertTrue(DestinationEntityFeed.due(100L, 0L, DestinationEntityFeed.INTERVAL));
        assertTrue(DestinationEntityFeed.due(104L, 100L, DestinationEntityFeed.INTERVAL));
        assertFalse(DestinationEntityFeed.due(103L, 100L, DestinationEntityFeed.INTERVAL),
                "a snapshot went out inside the interval");
    }

    /** A player who has never been fed is due immediately, not in four ticks. */
    @Test
    void aFirstSnapshotIsDueAtOnce() {
        assertTrue(DestinationEntityFeed.due(0L, Long.MIN_VALUE, DestinationEntityFeed.INTERVAL));
    }
}
