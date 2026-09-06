package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the unshaded target multiplies a vertex colour by. It is the only place
 * the destination's own light reaches the screen on that path — the layer
 * samples no lightmap and the coordinate written is full bright — so a scale
 * that does not vary with the level is a destination drawn without its light.
 *
 * <p>The floor is the SOURCE ambient, and vanilla's curve is
 * {@code ambient + f(level) * (1 - ambient)}. At ambient 1 that second term is
 * zero for every level.
 */
class UnshadedCollapseTest {

    /** The near field and the far field must not draw the same. */
    @Test
    void anUnsaturatedSourceSpreadsTheLevelsApart() {
        float dark = UnshadedDestination.scale(0, 0, 0.0f);
        float mid = UnshadedDestination.scale(0, 7, 0.0f);
        float bright = UnshadedDestination.scale(0, 15, 0.0f);
        assertTrue(dark < mid, "level 0 and 7 draw alike at ambient 0");
        assertTrue(mid < bright, "level 7 and 15 draw alike at ambient 0");
        assertEquals(1.0f, bright, 1.0e-6f, "full sky is not full brightness");
    }

    /**
     * A saturated source flattens the curve completely: sky 0 and sky 15 come
     * back identical, so nothing the destination reports about its own light
     * survives to the screen. This is the state the grey box measures in —
     * {@code e2e_one} declares {@code ambientLight: 1.0}.
     */
    @Test
    void aSaturatedSourceCollapsesEveryLevelToOne() {
        for (int level = 0; level <= 15; level++) {
            assertEquals(1.0f, UnshadedDestination.scale(0, level, 1.0f), 1.0e-6f,
                    "sky " + level + " scaled to something other than 1 at a saturated source");
            assertEquals(1.0f, UnshadedDestination.scale(level, 0, 1.0f), 1.0e-6f,
                    "block " + level + " scaled to something other than 1 at a saturated source");
        }
    }

    /** The brighter channel wins, as the lightmap composes them. */
    @Test
    void theBrighterChannelDecides() {
        assertEquals(UnshadedDestination.scale(0, 15, 0.0f),
                UnshadedDestination.scale(15, 0, 0.0f), 1.0e-6f);
        assertEquals(UnshadedDestination.scale(15, 15, 0.0f),
                UnshadedDestination.scale(15, 0, 0.0f), 1.0e-6f);
    }

    /** Levels outside 0..15 are held, so a lifted level cannot scale past full. */
    @Test
    void levelsAreHeldInsideTheirRange() {
        assertEquals(UnshadedDestination.scale(0, 15, 0.0f),
                UnshadedDestination.scale(0, 99, 0.0f), 1.0e-6f);
        assertEquals(UnshadedDestination.scale(0, 0, 0.0f),
                UnshadedDestination.scale(0, -4, 0.0f), 1.0e-6f);
    }
}
