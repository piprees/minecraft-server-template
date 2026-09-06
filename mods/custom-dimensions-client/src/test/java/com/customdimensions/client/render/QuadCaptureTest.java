package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the block renderer emits, caught as flat floats. Every consumer of a
 * mesh reads it by stride, so a vertex short or a stride wrong is geometry
 * read from the middle of its neighbour.
 */
class QuadCaptureTest {

    private static final int STRIDE = QuadCapture.STRIDE;

    private static void quad(QuadCapture capture, float x, float y, float z) {
        for (int v = 0; v < 4; v++) {
            capture.vertex(x + v, y, z);
        }
    }

    @Test
    void aVertexIsOneStrideAndFourOfThemAreAQuad() {
        QuadCapture capture = new QuadCapture();
        quad(capture, 0.0f, 0.0f, 0.0f);
        capture.finish();
        assertEquals(STRIDE * 4, capture.floatCount());
        assertEquals(1, capture.quadCount());
    }

    /** A partly-emitted quad would be read past its end, so it is dropped. */
    @Test
    void finishDropsARaggedQuad() {
        QuadCapture capture = new QuadCapture();
        quad(capture, 0.0f, 0.0f, 0.0f);
        capture.vertex(9.0f, 9.0f, 9.0f);
        capture.vertex(9.0f, 9.0f, 9.0f);
        capture.finish();
        assertEquals(STRIDE * 4, capture.floatCount(), "a half quad survived into the mesh");
        assertEquals(1, capture.quadCount());
    }

    /** Fluids arrive at chunk-relative coordinates and are corrected on the way in. */
    @Test
    void theOffsetLandsOnThePositionAndNothingElse() {
        QuadCapture capture = new QuadCapture();
        capture.setOffset(16.0f, -64.0f, 32.0f);
        capture.vertex(1.0f, 2.0f, 3.0f);
        capture.finish();
        float[] data = capture.data();
        assertEquals(17.0f, data[0]);
        assertEquals(-62.0f, data[1]);
        assertEquals(35.0f, data[2]);
    }

    /** An offset set after a vertex starts belongs to the next one, not to it. */
    @Test
    void anOffsetChangedMidQuadDoesNotMoveTheVertexAlreadyStarted() {
        QuadCapture capture = new QuadCapture();
        capture.vertex(1.0f, 2.0f, 3.0f);
        capture.setOffset(100.0f, 100.0f, 100.0f);
        capture.vertex(1.0f, 2.0f, 3.0f);
        capture.finish();
        float[] data = capture.data();
        assertEquals(1.0f, data[0], "the first vertex moved after it was written");
        assertEquals(101.0f, data[STRIDE]);
    }

    /** The buffer starts at 256 quads and has to carry what it held when it grows. */
    @Test
    void growingPastTheInitialBufferKeepsEveryVertex() {
        QuadCapture capture = new QuadCapture();
        int quads = 600;
        for (int i = 0; i < quads; i++) {
            quad(capture, i, 0.0f, 0.0f);
        }
        capture.finish();
        assertEquals(quads, capture.quadCount());
        assertEquals(STRIDE * 4 * quads, capture.floatCount());

        float[] data = capture.data();
        assertTrue(data.length >= capture.floatCount());
        for (int i = 0; i < quads; i++) {
            assertEquals((float) i, data[i * STRIDE * 4],
                    "quad " + i + " lost its position when the buffer grew");
        }
    }

    /** Light arrives packed into two coordinates and is kept as levels to interpolate. */
    @Test
    void lightIsKeptAsLevelsRatherThanLightmapCoordinates() {
        QuadCapture capture = new QuadCapture();
        capture.vertex(0.0f, 0.0f, 0.0f).light(11 << 4, 15 << 4);
        capture.finish();
        float[] data = capture.data();
        assertEquals(11.0f, data[11]);
        assertEquals(15.0f, data[12]);
    }
}
