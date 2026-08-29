package com.customdimensions.dimension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The footprint table's contract, and the two ways it is easy to break:
 * an unmeasured structure silently becoming a zero, and size overwhelming
 * the profile's own density intent.
 *
 * <p>Reads the real jar resource where it asserts on shipped content, so
 * this also gates a regenerated structure_sizes.json.
 */
class StructureFootprintsTest {

    @BeforeEach
    void clear() {
        StructureFootprints.reset();
    }

    @AfterEach
    void restore() {
        StructureFootprints.reset();
    }

    // ---------------------------------------------------------------- table

    @Test
    void shippedTableCoversTheWholePack() {
        // 781 of 783 measured. A regeneration that loses most of the table
        // still "works" — every structure just takes the median — so the
        // count is the only thing that catches it.
        assertTrue(StructureFootprints.size() > 700,
                "expected the shipped table to carry the pack; got "
                + StructureFootprints.size());
    }

    @Test
    void shippedMedianIsPlausible() {
        int median = StructureFootprints.median();
        assertTrue(median >= 20 && median <= 80,
                "median span outside the measured range; got " + median);
    }

    @Test
    void aVillageIsWiderThanItsDeclaredSearchBound() {
        // village_plains declares max_distance_from_center 80 and measures 170.
        // This asserts the MEASURED value reached the jar, which is the whole
        // point of the table — T54.
        assertTrue(StructureFootprints.spanOf("minecraft:village_plains") > 120,
                "village_plains should carry its measured span, not its declared bound; got "
                + StructureFootprints.spanOf("minecraft:village_plains"));
    }

    @Test
    void aMineshaftIsMeasuredDespiteDeclaringNeitherField() {
        // mineshaft is not a jigsaw structure and declares no size at all, so
        // a jar-field proxy would have had nothing for it.
        assertTrue(StructureFootprints.isMeasured("minecraft:mineshaft"));
        assertTrue(StructureFootprints.spanOf("minecraft:mineshaft") > 100);
    }

    // ------------------------------------------------------------- fallback

    @Test
    void anUnmeasuredStructureTakesTheMedianNeverZero() {
        assertFalse(StructureFootprints.isMeasured("nonesuch:not_a_structure"));
        assertEquals(StructureFootprints.median(),
                StructureFootprints.spanOf("nonesuch:not_a_structure"));
        assertTrue(StructureFootprints.spanOf("nonesuch:not_a_structure") > 0,
                "a missing structure must never resolve to no personal space");
    }

    @Test
    void aNullIdTakesTheMedianRatherThanThrowing() {
        assertEquals(StructureFootprints.median(), StructureFootprints.spanOf(null));
        assertFalse(StructureFootprints.isMeasured(null));
    }

    @Test
    void radiusIsHalfTheSpan() {
        int span = StructureFootprints.spanOf("minecraft:village_plains");
        assertEquals(span / 2, StructureFootprints.radiusOf("minecraft:village_plains"));
    }

    // ----------------------------------------------------------- sizeFactor

    @Test
    void theMedianStructureScalesNothing() {
        StructureFootprints.install(Map.of("a:small", 10, "a:mid", 40, "a:big", 160));
        assertEquals(1.0, StructureFootprints.sizeFactor("a:mid"), 1e-9);
    }

    @Test
    void sizeFactorIsTheRootOfTheRatioNotTheRatio() {
        StructureFootprints.install(Map.of("a:small", 10, "a:mid", 40, "a:big", 160));
        // 160/40 = 4x the span; a radius should move by 2x, not 4x. Without
        // the root the measured 1-to-256 span range drives a 256x spread in
        // separation and the profile's density intent stops meaning anything.
        assertEquals(2.0, StructureFootprints.sizeFactor("a:big"), 1e-9);
        assertEquals(0.6, StructureFootprints.sizeFactor("a:small"), 1e-9,
                "10/40 roots to 0.5 and clamps at the floor");
    }

    @Test
    void sizeFactorStaysInsideItsBounds() {
        StructureFootprints.install(Map.of(
                "a:tiny", 1, "a:mid", 44, "a:huge", 256, "a:vast", 100000));
        for (String id : new String[]{"a:tiny", "a:mid", "a:huge", "a:vast", "a:missing"}) {
            double f = StructureFootprints.sizeFactor(id);
            assertTrue(f >= StructureFootprints.MIN_SIZE_FACTOR
                            && f <= StructureFootprints.MAX_SIZE_FACTOR,
                    id + " produced an out-of-bounds size factor: " + f);
        }
    }

    @Test
    void sizeFactorIsMonotonicInFootprint() {
        StructureFootprints.install(Map.of(
                "a:1", 8, "a:2", 20, "a:3", 44, "a:4", 90, "a:5", 200));
        double previous = 0.0;
        for (String id : new String[]{"a:1", "a:2", "a:3", "a:4", "a:5"}) {
            double f = StructureFootprints.sizeFactor(id);
            assertTrue(f >= previous,
                    "size factor must not decrease as footprint grows: " + id + " gave " + f);
            previous = f;
        }
    }

    @Test
    void anUnmeasuredStructureScalesNothing() {
        StructureFootprints.install(Map.of("a:mid", 40));
        assertEquals(1.0, StructureFootprints.sizeFactor("nonesuch:missing"), 1e-9,
                "an absence must not be treated as tiny");
    }

    @Test
    void theRealTableProducesTheFullFactorRange() {
        // The shipped spread is 1 to 256 blocks, so both clamps must actually
        // bite on real content — a table that never reaches its bounds would
        // mean the factor is doing nothing.
        double smallest = StructureFootprints.sizeFactor("minecraft:buried_treasure");
        double largest = StructureFootprints.sizeFactor("minecraft:ancient_city");
        assertTrue(smallest < 1.0, "buried_treasure should scale down; got " + smallest);
        assertTrue(largest > 1.5, "ancient_city should scale up; got " + largest);
    }
}
