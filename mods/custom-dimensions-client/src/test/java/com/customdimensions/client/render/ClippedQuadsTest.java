package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mask applied to geometry this code does not write: quads arrive four
 * vertices at a time from a vanilla dispatcher and only what the opening frames
 * is handed on.
 *
 * <p>Same fixture as {@link ProjectionRendererClipTest}: a 2x2 opening in the
 * plane {@code z = 0}, camera at {@code (1, 1, -5)}, so the cone widens by
 * {@code (z + 5) / 5} about {@code x = y = 1}.
 */
class ClippedQuadsTest {

    private static final int STRIDE = QuadCapture.STRIDE;
    private static final float TOLERANCE = 1.0e-4f;

    private static final double[] OPENING = {
        0.0, 0.0, 0.0,
        0.0, 2.0, 0.0,
        2.0, 2.0, 0.0,
        2.0, 0.0, 0.0,
    };

    @Test
    void aQuadInsideTheOpeningIsHandedOnWhole() {
        Recorder sink = new Recorder();
        ClippedQuads quads = new ClippedQuads(planes(), sink);

        feed(quads, 5.0f, 0.0f, 1.0f, 0.0f, 1.0f);

        assertEquals(1, quads.quadsIn());
        assertEquals(1, quads.quadsOut());
        assertEquals(4, sink.vertices.size());
        assertEquals(1, sink.opened, "the sink was opened once per polygon");
    }

    @Test
    void aQuadOutsideTheOpeningReachesNoBuffer() {
        Recorder sink = new Recorder();
        ClippedQuads quads = new ClippedQuads(planes(), sink);

        // At z = 5 the cone spans -1..3 on both in-plane axes.
        feed(quads, 5.0f, 10.0f, 11.0f, 10.0f, 11.0f);

        assertEquals(1, quads.quadsIn());
        assertEquals(0, quads.quadsOut());
        assertEquals(0, sink.vertices.size());
        assertEquals(0, sink.opened, "an empty polygon opened the buffer anyway");
    }

    @Test
    void aStraddlingQuadIsCutOnTheOpeningAndNowhereElse() {
        Recorder sink = new Recorder();
        ClippedQuads quads = new ClippedQuads(planes(), sink);

        // Half-width 2 about x = 1 at z = 5, so the cut lands on x = 3 exactly.
        feed(quads, 5.0f, 2.0f, 4.0f, 0.0f, 1.0f);

        assertEquals(4, sink.vertices.size(), "a quad cut by one plane is still a quad");
        assertEquals(2.0f, sink.min(0), TOLERANCE);
        assertEquals(3.0f, sink.max(0), TOLERANCE);
    }

    /**
     * A model's own colour, texture, overlay, light and normal have to survive
     * the cut, and be interpolated at a new corner. Without this an entity draws
     * white, unlit and untextured — which reads as the mask failing.
     */
    @Test
    void everyAttributeSurvivesTheCutAndIsInterpolatedAtANewCorner() {
        Recorder sink = new Recorder();
        ClippedQuads quads = new ClippedQuads(planes(), sink);

        feed(quads, 5.0f, 2.0f, 4.0f, 0.0f, 1.0f);

        for (float[] vertex : sink.vertices) {
            assertEquals(0.25f, vertex[3], TOLERANCE, "colour was not carried through");
            assertEquals(15.0f, vertex[11], TOLERANCE, "block light was not carried through");
            assertTrue(vertex[7] >= 0.0f && vertex[7] <= 1.0f, "u left its own range");
        }
        // u runs 0..1 across x 2..4, so the cut at x = 3 interpolates u to 0.5.
        assertEquals(0.5f, sink.max(7), TOLERANCE, "u was not interpolated at the cut");
    }

    @Test
    void twoQuadsInARowAreCutIndependently() {
        Recorder sink = new Recorder();
        ClippedQuads quads = new ClippedQuads(planes(), sink);

        feed(quads, 5.0f, 0.0f, 1.0f, 0.0f, 1.0f);
        feed(quads, 5.0f, 10.0f, 11.0f, 10.0f, 11.0f);

        assertEquals(2, quads.quadsIn());
        assertEquals(1, quads.quadsOut());
        assertEquals(4, sink.vertices.size());
    }

    /**
     * A dispatcher that stopped part-way through a quad leaves three vertices in
     * hand. Emitted, the fourth would be whatever the previous quad left there.
     */
    @Test
    void aQuadLeftPartWrittenIsDroppedRatherThanCompletedFromStaleFloats() {
        Recorder sink = new Recorder();
        ClippedQuads quads = new ClippedQuads(planes(), sink);

        float[] quad = quad(5.0f, 0.0f, 1.0f, 0.0f, 1.0f);
        for (int v = 0; v < 3; v++) {
            quads.add(quad, v * STRIDE);
        }
        quads.flush();

        assertEquals(0, quads.quadsIn());
        assertEquals(0, sink.vertices.size());

        // And the next quad starts clean rather than inheriting those three.
        feed(quads, 5.0f, 0.0f, 1.0f, 0.0f, 1.0f);
        assertEquals(1, quads.quadsIn());
        assertEquals(4, sink.vertices.size());
    }

    /**
     * A model's normal has to survive the cut, including at a corner the clip
     * invented. A shader pack shades a model from its normal, so a normal
     * dropped or interpolated to zero here is a model the pack cannot light —
     * which reads as a dark silhouette and not as a mask fault.
     */
    @Test
    void theNormalSurvivesTheCutAtEveryCornerIncludingAnInventedOne() {
        Recorder sink = new Recorder();
        ClippedQuads quads = new ClippedQuads(planes(), sink);

        // Cut on the cone at x = 3, so two of the four corners are invented.
        feed(quads, 5.0f, 2.0f, 4.0f, 0.0f, 1.0f);

        assertEquals(4, sink.vertices.size());
        for (float[] vertex : sink.vertices) {
            assertEquals(0.0f, vertex[13], TOLERANCE);
            assertEquals(0.0f, vertex[14], TOLERANCE);
            assertEquals(1.0f, vertex[15], TOLERANCE, "the normal was lost at a cut corner");
        }
    }

    /**
     * A quad whose corners disagree about their normal keeps a normal of
     * useful length through the cut. Linear interpolation shortens it, and a
     * shortened normal dims a model rather than losing it.
     */
    @Test
    void anInterpolatedNormalKeepsUsefulLength() {
        Recorder sink = new Recorder();
        ClippedQuads quads = new ClippedQuads(planes(), sink);

        float[] quad = quad(5.0f, 2.0f, 4.0f, 0.0f, 1.0f);
        // Two corners facing +z, two facing +x: a 90 degree disagreement is the
        // worst a quad of a curved model produces.
        for (int v = 2; v < 4; v++) {
            quad[v * STRIDE + 13] = 1.0f;
            quad[v * STRIDE + 15] = 0.0f;
        }
        for (int v = 0; v < 4; v++) {
            quads.add(quad, v * STRIDE);
        }

        for (float[] vertex : sink.vertices) {
            double length = Math.sqrt(vertex[13] * vertex[13] + vertex[14] * vertex[14]
                    + vertex[15] * vertex[15]);
            assertTrue(length > 0.7, "an interpolated normal collapsed to length " + length);
        }
    }

    private static AperturePlanes planes() {
        AperturePlanes planes = new AperturePlanes(9, STRIDE);
        assertTrue(planes.build(OPENING.clone(), 1, 1.0, 1.0, -5.0));
        return planes;
    }

    private static void feed(ClippedQuads quads, float z, float x0, float x1, float y0, float y1) {
        float[] quad = quad(z, x0, x1, y0, y1);
        for (int v = 0; v < 4; v++) {
            quads.add(quad, v * STRIDE);
        }
    }

    /** One quad carrying a full set of attributes, u running 0..1 across x. */
    private static float[] quad(float z, float x0, float x1, float y0, float y1) {
        float[][] corners = {{x0, y0, 0.0f}, {x0, y1, 0.0f}, {x1, y1, 1.0f}, {x1, y0, 1.0f}};
        float[] data = new float[STRIDE * 4];
        for (int i = 0; i < 4; i++) {
            int at = i * STRIDE;
            data[at] = corners[i][0];
            data[at + 1] = corners[i][1];
            data[at + 2] = z;
            data[at + 3] = 0.25f;
            data[at + 4] = 0.5f;
            data[at + 5] = 0.75f;
            data[at + 6] = 1.0f;
            data[at + 7] = corners[i][2];
            data[at + 8] = 0.0f;
            data[at + 9] = 0.0f;
            data[at + 10] = 10.0f;
            data[at + 11] = 15.0f;
            data[at + 12] = 15.0f;
            data[at + 15] = 1.0f;
        }
        return data;
    }

    private static final class Recorder implements ClippedQuads.Sink {

        private final List<float[]> vertices = new ArrayList<>();
        private int opened;

        @Override
        public void begin() {
            this.opened++;
        }

        @Override
        public void vertex(float[] data, int at) {
            float[] copy = new float[STRIDE];
            System.arraycopy(data, at, copy, 0, STRIDE);
            this.vertices.add(copy);
        }

        float min(int element) {
            float out = Float.MAX_VALUE;
            for (float[] vertex : this.vertices) {
                out = Math.min(out, vertex[element]);
            }
            return out;
        }

        float max(int element) {
            float out = -Float.MAX_VALUE;
            for (float[] vertex : this.vertices) {
                out = Math.max(out, vertex[element]);
            }
            return out;
        }
    }
}
