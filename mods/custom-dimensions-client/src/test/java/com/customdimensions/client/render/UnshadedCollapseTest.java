package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        float dark = UnshadedDestination.scale(0, 0, 0.0f, 0.0f);
        float mid = UnshadedDestination.scale(0, 7, 0.0f, 0.0f);
        float bright = UnshadedDestination.scale(0, 15, 0.0f, 0.0f);
        assertTrue(dark < mid, "level 0 and 7 draw alike at ambient 0");
        assertTrue(mid < bright, "level 7 and 15 draw alike at ambient 0");
        assertEquals(1.0f, bright, 1.0e-6f, "full sky is not full brightness");
    }

    /**
     * A saturated ambient flattens the curve completely — every level returns
     * one. That is correct for a destination whose own ambient saturates it,
     * and catastrophic for one merely VIEWED from such a source, which is why
     * this target takes the destination's ambient and not the source's.
     */
    @Test
    void aSaturatedAmbientCollapsesEveryLevelToOne() {
        for (int level = 0; level <= 15; level++) {
            assertEquals(1.0f, UnshadedDestination.scale(0, level, 1.0f, 0.0f), 1.0e-6f,
                    "sky " + level + " scaled to something other than 1 at a saturated ambient");
            assertEquals(1.0f, UnshadedDestination.scale(level, 0, 1.0f, 0.0f), 1.0e-6f,
                    "block " + level + " scaled to something other than 1 at a saturated ambient");
        }
    }

    /**
     * The saturated SOURCE is the case the grey box measures in. A destination
     * at ambient 0 seen from a source at ambient 1 must still spread its own
     * levels — routing them through source-lightmap space first is what lost
     * them, and that is the composition this target no longer performs.
     */
    @Test
    void aDarkDestinationSeenFromASaturatedSourceKeepsItsLevels() {
        float dark = UnshadedDestination.scale(0, 0, 0.0f, 0.0f);
        float bright = UnshadedDestination.scale(0, 15, 0.0f, 0.0f);
        assertTrue(bright - dark > 0.5f,
                "a destination at ambient 0 cannot tell its own levels apart");

        // What the discarded composition produced: lift into a saturated
        // source first, then shade against that source.
        float liftedDark = UnshadedDestination.scale(
                0, AmbientLift.lift(0, 0.0f, 1.0f), 1.0f, 0.0f);
        float liftedBright = UnshadedDestination.scale(
                0, AmbientLift.lift(15, 0.0f, 1.0f), 1.0f, 0.0f);
        assertEquals(liftedDark, liftedBright, 1.0e-6f,
                "the old composition is no longer flat; this test describes nothing");
    }

    /** The brighter channel wins, as the lightmap composes them. */
    @Test
    void theBrighterChannelDecides() {
        assertEquals(UnshadedDestination.scale(0, 15, 0.0f, 0.0f),
                UnshadedDestination.scale(15, 0, 0.0f, 0.0f), 1.0e-6f);
        assertEquals(UnshadedDestination.scale(15, 15, 0.0f, 0.0f),
                UnshadedDestination.scale(15, 0, 0.0f, 0.0f), 1.0e-6f);
    }

    /** Levels outside 0..15 are held, so a lifted level cannot scale past full. */
    @Test
    void levelsAreHeldInsideTheirRange() {
        assertEquals(UnshadedDestination.scale(0, 15, 0.0f, 0.0f),
                UnshadedDestination.scale(0, 99, 0.0f, 0.0f), 1.0e-6f);
        assertEquals(UnshadedDestination.scale(0, 0, 0.0f, 0.0f),
                UnshadedDestination.scale(0, -4, 0.0f, 0.0f), 1.0e-6f);
    }

    /**
     * What the dev bridge reports. The reading has to distinguish a collapsed
     * ambient from a spread one on sight, or a re-baseline cannot tell whether
     * the fixture exercised this target at all.
     */
    @Test
    void theLabelShowsWhetherTheLevelsAreSpreadOrFlat() {
        String flat = UnshadedDestination.label(1.0f, 0.0f);
        assertEquals("0>1.000,7>1.000,15>1.000", flat,
                "a saturated ambient does not read as flat");

        String spread = UnshadedDestination.label(0.25f, 0.0f);
        assertTrue(spread.startsWith("0>0.250"), "level 0 does not read the ambient floor");
        assertTrue(spread.endsWith("15>1.000"), "full sky does not read full brightness");
        assertNotEquals(flat, spread, "0.25 and 1.0 report the same scale");
    }

    /** An ambient that never reached the payload must not read as a real floor. */
    @Test
    void anUnsetAmbientSaysSoRatherThanReadingAsZero() {
        assertEquals("unset", UnshadedDestination.label(-1.0f, 0.0f),
                "an unset ambient reports a scale it does not have");
    }
}
