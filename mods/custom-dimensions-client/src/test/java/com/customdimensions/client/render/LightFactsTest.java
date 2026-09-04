package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The light instrument's arithmetic: the two channels stay apart, the
 * denominator is printed, and a grid nobody sampled reads differently from a
 * dark one.
 */
class LightFactsTest {

    private static final int STRIDE = QuadCapture.STRIDE;

    /** Server packing is {@code sky << 4 | block}; reading it the other way round swaps the channels. */
    @Test
    void packedLightSplitsSkyFromBlock() {
        LightFacts facts = LightFacts.ofPacked(new byte[] {(byte) ((3 << 4) | 11)});
        assertEquals(11, facts.blockMin());
        assertEquals(11, facts.blockMax());
        assertEquals(3, facts.skyMin());
        assertEquals(3, facts.skyMax());
    }

    @Test
    void theTopBitOfASkyNibbleSurvivesTheByte() {
        LightFacts facts = LightFacts.ofPacked(new byte[] {(byte) ((15 << 4) | 15)});
        assertEquals(15, facts.skyMax(), "sky 15 packs to a negative byte");
        assertEquals(15, facts.blockMax());
    }

    @Test
    void rangesAndMeansSpanTheWholeGrid() {
        LightFacts facts = LightFacts.ofPacked(new byte[] {
            (byte) ((0 << 4) | 0),
            (byte) ((4 << 4) | 8),
            (byte) ((8 << 4) | 4),
        });
        assertEquals(3, facts.cells());
        assertEquals(0, facts.blockMin());
        assertEquals(8, facts.blockMax());
        assertEquals(4.0, facts.blockMean(), 1e-9);
        assertEquals(0, facts.skyMin());
        assertEquals(8, facts.skyMax());
        assertEquals(4.0, facts.skyMean(), 1e-9);
    }

    /** T63: a mean of zero over an empty grid must not read as a dark one. */
    @Test
    void anEmptyGridIsDistinguishableFromADarkOne() {
        LightFacts none = LightFacts.ofPacked(new byte[0]);
        LightFacts dark = LightFacts.ofPacked(new byte[] {0, 0, 0});
        assertEquals(0, none.cells());
        assertEquals(3, dark.cells());
        assertNotEquals(none, dark);
        assertEquals(LightFacts.EMPTY, none);
    }

    /** {@code lit} is the count of cells carrying light in either channel. */
    @Test
    void litCountsCellsWithAnyLightAtAll() {
        LightFacts facts = LightFacts.ofPacked(new byte[] {
            0,
            (byte) ((0 << 4) | 1),
            (byte) ((1 << 4) | 0),
            0,
        });
        assertEquals(4, facts.cells());
        assertEquals(2, facts.lit());
    }

    @Test
    void aNullGridIsEmpty() {
        assertEquals(LightFacts.EMPTY, LightFacts.ofPacked(null));
    }

    /**
     * Vertex light is stored unpacked at offsets 11 (block) and 12 (sky), the
     * levels {@link QuadCapture#light} divided out of the lightmap coordinate.
     */
    @Test
    void vertexLightReadsTheTwoLevelSlots() {
        float[] data = new float[STRIDE * 2];
        data[11] = 11.0f;
        data[12] = 0.0f;
        data[STRIDE + 11] = 4.0f;
        data[STRIDE + 12] = 15.0f;
        LightFacts facts = LightFacts.ofVertices(data, STRIDE * 2, STRIDE);
        assertEquals(2, facts.cells());
        assertEquals(4, facts.blockMin());
        assertEquals(11, facts.blockMax());
        assertEquals(0, facts.skyMin());
        assertEquals(15, facts.skyMax());
    }

    /** Only the vertices actually written count — the array is over-allocated. */
    @Test
    void vertexLightStopsAtTheWrittenLength() {
        float[] data = new float[STRIDE * 4];
        data[11] = 7.0f;
        data[12] = 7.0f;
        data[STRIDE * 3 + 11] = 15.0f;
        data[STRIDE * 3 + 12] = 15.0f;
        LightFacts facts = LightFacts.ofVertices(data, STRIDE, STRIDE);
        assertEquals(1, facts.cells());
        assertEquals(7, facts.blockMax());
        assertEquals(7, facts.skyMax());
    }

    @Test
    void anEmptyLayerHasNoVertices() {
        assertEquals(LightFacts.EMPTY, LightFacts.ofVertices(new float[STRIDE], 0, STRIDE));
        assertEquals(LightFacts.EMPTY, LightFacts.ofVertices(null, 8, STRIDE));
    }

    /**
     * The label names its denominator: a reader has to be able to tell
     * "nothing was sampled" from "everything sampled dark".
     */
    @Test
    void theLabelCarriesTheDenominator() {
        assertEquals("cells=0", LightFacts.EMPTY.label());
        assertEquals("cells=3 lit=0 block=0..0/0.0 sky=0..0/0.0",
                LightFacts.ofPacked(new byte[] {0, 0, 0}).label());
        assertEquals("cells=1 lit=1 block=11..11/11.0 sky=0..0/0.0",
                LightFacts.ofPacked(new byte[] {11}).label());
    }
}
