package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source-entity cap. A client world hands entities back in entity-section
 * order, which has nothing to do with distance, so a cap applied to that order
 * answers with a far cave cluster and omits the arrow three blocks away — the
 * one entity the reading exists to see.
 */
class DevStateNearestFirstTest {

    /** Squared distance, so the comparator is exercised on the same scale the caller uses. */
    private static double sq(Double distance) {
        return distance * distance;
    }

    @Test
    void theCapKeepsTheNearestNotWhateverArrivedFirst() {
        List<Double> sectionOrder = List.of(30.0, 28.0, 26.0, 24.0, 3.0);
        assertEquals(List.of(3.0, 24.0, 26.0),
                DevState.nearestFirst(sectionOrder, DevStateNearestFirstTest::sq, 3));
    }

    @Test
    void everythingComesBackNearestFirstWhenNothingIsCut() {
        assertEquals(List.of(1.0, 5.0, 9.0),
                DevState.nearestFirst(List.of(9.0, 1.0, 5.0), DevStateNearestFirstTest::sq, 24));
    }

    @Test
    void anEmptyWorldIsAnEmptyList() {
        assertTrue(DevState.nearestFirst(List.<Double>of(),
                DevStateNearestFirstTest::sq, 24).isEmpty());
    }

    /** A {@code subList} view of an immutable source would throw here. */
    @Test
    void whatComesBackIsAListOfItsOwn() {
        List<Double> kept = DevState.nearestFirst(List.of(4.0, 2.0, 6.0),
                DevStateNearestFirstTest::sq, 2);
        assertEquals(List.of(2.0, 4.0), kept);
        kept.add(8.0);
        assertEquals(3, kept.size());
    }
}
