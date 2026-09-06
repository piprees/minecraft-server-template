package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rejecting a whole depth slab against the opening, instead of clipping each of
 * its quads and finding none survive.
 *
 * <p>Measured at the rig: 2,314 quads into the solid layer, none emitted, and
 * 128 vertices of 1,189 quads on the next. Almost the entire mesh is cut every
 * frame, so what the box test skips is most of the draw's work.
 */
class ApertureRejectsTest {

    private static final int STRIDE = 3;

    /** An opening 2 wide and 3 high at z = 0, corners walked as edges. */
    private static final double[] OPENING = {
        -1.0, -1.5, 0.0,
        1.0, -1.5, 0.0,
        1.0, 1.5, 0.0,
        -1.0, 1.5, 0.0,
    };

    /** Eye 5 back, level with the opening's centre. */
    private static AperturePlanes cone() {
        AperturePlanes planes = new AperturePlanes(8, STRIDE);
        assertTrue(planes.build(OPENING, 1, 0.0, 0.0, -5.0), "the fixture cone degenerated");
        return planes;
    }

    @Test
    void aSlabInFrontOfTheOpeningSurvives() {
        assertFalse(cone().rejects(-1.0, -1.0, 1.0, 1.0, 1.0, 9.0),
                "a slab straight through the opening was rejected");
    }

    @Test
    void aSlabOffToOneSideIsRejected() {
        assertTrue(cone().rejects(50.0, -1.0, 1.0, 60.0, 1.0, 9.0),
                "a slab far outside the cone was clipped quad by quad instead of skipped");
    }

    @Test
    void aSlabAboveTheConeIsRejected() {
        assertTrue(cone().rejects(-1.0, 80.0, 1.0, 1.0, 90.0, 9.0));
    }

    /** Conservative: a box only partly inside must survive, or geometry vanishes. */
    @Test
    void aSlabStraddlingTheConeEdgeSurvives() {
        assertFalse(cone().rejects(0.5, -1.0, 1.0, 60.0, 1.0, 9.0),
                "a slab reaching into the cone was rejected, which would drop visible terrain");
    }

    /**
     * The safety property: the box test may never skip geometry the clip would
     * have kept. Checked against the clip itself rather than against a second
     * opinion about the geometry.
     */
    @Test
    void nothingInARejectedBoxSurvivesTheClip() {
        AperturePlanes planes = cone();
        double[] box = {50.0, -1.0, 1.0, 60.0, 1.0, 9.0};
        assertTrue(planes.rejects(box[0], box[1], box[2], box[3], box[4], box[5]),
                "the fixture box is not rejected, so this proves nothing");
        assertEquals(0, survivorsOfCornerQuads(planes, box),
                "a box the test skipped still held geometry the clip would keep");
    }

    /** A box that is NOT rejected must be one the clip can keep something from. */
    @Test
    void aSurvivingBoxIsWorthClipping() {
        AperturePlanes planes = cone();
        double[] box = {-1.0, -1.0, 1.0, 1.0, 1.0, 9.0};
        assertFalse(planes.rejects(box[0], box[1], box[2], box[3], box[4], box[5]));
        assertTrue(survivorsOfCornerQuads(planes, box) > 0,
                "the box survived the test but the clip keeps nothing in it");
    }

    /** Two faces of the box, clipped the way the draw clips a quad. */
    private static int survivorsOfCornerQuads(AperturePlanes planes, double[] box) {
        float[][] quads = {
            {(float) box[0], (float) box[1], (float) box[2], (float) box[3], (float) box[1],
                (float) box[2], (float) box[3], (float) box[4], (float) box[5],
                (float) box[0], (float) box[4], (float) box[5]},
            {(float) box[0], (float) box[1], (float) box[5], (float) box[3], (float) box[1],
                (float) box[5], (float) box[3], (float) box[4], (float) box[2],
                (float) box[0], (float) box[4], (float) box[2]},
        };
        int survivors = 0;
        for (float[] quad : quads) {
            float[] in = quad.clone();
            float[] out = new float[AperturePlanes.MAX_POLY * STRIDE];
            int count = 4;
            for (int plane = 0; plane < planes.count() && count >= 3; plane++) {
                count = planes.clip(in, count, out, plane);
                System.arraycopy(out, 0, in, 0, Math.min(count * STRIDE, in.length));
            }
            if (count >= 3) {
                survivors += count;
            }
        }
        return survivors;
    }

    /** No planes means no information, so nothing may be skipped on their word. */
    @Test
    void anEmptyConeRejectsNothing() {
        assertFalse(new AperturePlanes(8, STRIDE).rejects(0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
    }
}
