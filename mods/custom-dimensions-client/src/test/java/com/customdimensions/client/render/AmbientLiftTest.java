package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inversion of vanilla's {@code LightmapTextureManager.getBrightness}. The
 * whole point is that a destination level shades through the SOURCE lightmap to
 * the destination's own brightness, so every case here is stated as a brightness
 * comparison rather than as an expected level.
 */
class AmbientLiftTest {

    private static float shadedBySource(int level, float destination, float source) {
        return AmbientLift.brightness(AmbientLift.lift(level, destination, source), source);
    }

    /**
     * Only 16 levels exist, so the answer is the closest of them rather than an
     * exact match. Asserted as "no other level is nearer", which pins the
     * quantisation without pretending it is not there.
     */
    @Test
    void everyLevelShadesAsCloseToTheDestinationAsALevelCan() {
        for (float destination : new float[] {0.05f, 0.15f, 0.2f, 0.5f}) {
            for (int level = 0; level <= 15; level++) {
                float wanted = AmbientLift.brightness(level, destination);
                float got = Math.abs(shadedBySource(level, destination, 0.0f) - wanted);
                for (int other = 0; other <= 15; other++) {
                    assertTrue(got <= Math.abs(AmbientLift.brightness(other, 0.0f) - wanted) + 1e-6f,
                            "level " + level + " at ambient " + destination
                                    + " had a nearer answer at " + other);
                }
            }
        }
    }

    /** The dark end is where ambient light is the whole of what is seen. */
    @Test
    void theDarkEndLandsWithinOneLevelOfTheDestinationsOwnBrightness() {
        for (int level = 0; level <= 7; level++) {
            assertEquals(AmbientLift.brightness(level, 0.15f),
                    shadedBySource(level, 0.15f, 0.0f), 0.03f,
                    "level " + level + " does not shade to the destination's brightness");
        }
    }

    @Test
    void anUnlitCellPicksUpTheDestinationsAmbientFloor() {
        assertTrue(AmbientLift.lift(0, 0.15f, 0.0f) > 0,
                "a dark cell in a dimension with ambient light stayed at the source's floor");
        assertTrue(AmbientLift.lift(0, 0.2f, 0.0f) > AmbientLift.lift(0, 0.15f, 0.0f),
                "more ambient light did not read brighter");
    }

    @Test
    void fullDaylightStaysFullDaylight() {
        assertEquals(15, AmbientLift.lift(15, 0.15f, 0.0f));
        assertEquals(15, AmbientLift.lift(15, 0.0f, 0.15f));
    }

    /**
     * The no-op case is exact, which is what lets the emit line separate "the
     * value never arrived" from "it arrived and the two dimensions agree".
     */
    @Test
    void matchingAmbientsAreTheIdentity() {
        for (int level = 0; level <= 15; level++) {
            assertEquals(level, AmbientLift.lift(level, 0.1f, 0.1f));
        }
    }

    @Test
    void anUnsetAmbientIsTheIdentity() {
        for (int level = 0; level <= 15; level++) {
            assertEquals(level, AmbientLift.lift(level, AmbientLift.UNSET, 0.0f));
        }
    }

    /**
     * The source texture holds no texel below its own ambient floor, so a
     * darker destination reads at that floor rather than going negative. This
     * is the change's stated limit, pinned so it cannot become silent.
     */
    @Test
    void aDestinationDarkerThanTheSourceFloorClampsAtTheFloor() {
        assertEquals(0, AmbientLift.lift(0, 0.0f, 0.15f));
        assertEquals(0, AmbientLift.lift(1, 0.0f, 0.5f));
    }

    @Test
    void everyAnswerIsALegalLightLevelAndNeverGoesBackwards() {
        for (float destination : new float[] {0.0f, 0.05f, 0.15f, 0.2f, 0.5f, 1.0f, 4.0f}) {
            int previous = -1;
            for (int level = -3; level <= 18; level++) {
                int lifted = AmbientLift.lift(level, destination, 0.0f);
                assertTrue(lifted >= 0 && lifted <= 15,
                        "level " + level + " at ambient " + destination + " left 0..15");
                assertTrue(lifted >= previous, "the lift went backwards at level " + level);
                previous = lifted;
            }
        }
    }

    /** A fully bright source lightmap has no darker texel to aim at. */
    @Test
    void aSaturatedSourceIsTheIdentity() {
        assertEquals(7, AmbientLift.lift(7, 0.0f, 1.0f));
    }

    @Test
    void theLabelNamesAnUnsetValueRatherThanPrintingMinusOne() {
        assertTrue(AmbientLift.label(AmbientLift.UNSET, 0.0f).startsWith("dstunset/src0.000"),
                "an absent value has to read differently from a present one");
        assertTrue(AmbientLift.label(0.1f, 0.1f).endsWith("0>0,7>7,15>15"),
                "an applied-but-equal pair has to read as the identity");
    }
}
