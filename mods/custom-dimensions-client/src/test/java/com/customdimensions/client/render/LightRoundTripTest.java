package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sky and block light from the grid to the vertex and back out again.
 *
 * <p>Three encodings meet here and none of them is the next one's: the payload
 * packs {@code sky << 4 | block} in a byte, vanilla's lightmap packs
 * {@code block << 4 | sky << 20} in an int, and the mesh keeps the two as
 * separate levels so the clip can interpolate them. A shift lost anywhere in
 * that chain is a uniformly dark destination at every camera angle, which is
 * indistinguishable from a world that has no light.
 */
class LightRoundTripTest {

    private static final int STRIDE = QuadCapture.STRIDE;
    private static final int BLOCK_AT = 11;
    private static final int SKY_AT = 12;

    /** {@code LightmapTextureManager.pack}: block in the low half, sky in the high. */
    private static int packLightmap(int block, int sky) {
        return block << 4 | sky << 20;
    }

    /** What {@code VertexConsumer.light(int)} hands the two-argument form. */
    private static int lowHalf(int packed) {
        return packed & 0xFFFF;
    }

    private static int highHalf(int packed) {
        return packed >> 16 & 0xFFFF;
    }

    private static float[] capture(int block, int sky) {
        QuadCapture capture = new QuadCapture();
        int packed = packLightmap(block, sky);
        capture.vertex(0.0f, 0.0f, 0.0f).light(lowHalf(packed), highHalf(packed));
        capture.finish();
        return capture.data();
    }

    /** The payload's own packing, as {@code ProjectionView.getLightLevel} reads it. */
    @Test
    void theGridPacksSkyAboveBlock() {
        int packed = (14 << 4) | 3;
        assertEquals(14, (packed >> 4) & 0xF, "sky came out of the wrong nibble");
        assertEquals(3, packed & 0xF, "block came out of the wrong nibble");
    }

    @Test
    void aLightmapCoordinateArrivesAsTwoLevels() {
        float[] data = capture(3, 14);
        assertEquals(3.0f, data[BLOCK_AT], "block light was lost between the lightmap and the mesh");
        assertEquals(14.0f, data[SKY_AT], "sky light was lost between the lightmap and the mesh");
    }

    /** Sky carries the fixture entirely, so dropping it alone is a black box. */
    @Test
    void skyAloneSurvivesWithNoBlockLight() {
        float[] data = capture(0, 15);
        assertEquals(0.0f, data[BLOCK_AT]);
        assertEquals(15.0f, data[SKY_AT], "sky was dropped where block light was zero");
    }

    /** What the emit writes back must be what the block renderer handed in. */
    @Test
    void theEmittedCoordinateIsTheOneThatArrived() {
        for (int block = 0; block <= 15; block++) {
            for (int sky = 0; sky <= 15; sky++) {
                float[] data = capture(block, sky);
                int packed = packLightmap(block, sky);
                assertEquals(lowHalf(packed), ((int) data[BLOCK_AT]) << 4,
                        "block " + block + "/" + sky + " did not survive the round trip");
                assertEquals(highHalf(packed), ((int) data[SKY_AT]) << 4,
                        "sky " + block + "/" + sky + " did not survive the round trip");
            }
        }
    }

    /** A vertex nobody lights is full bright, not dark: an unset level is not zero. */
    @Test
    void anUnlitVertexDefaultsToFullBrightRatherThanDark() {
        QuadCapture capture = new QuadCapture();
        capture.vertex(0.0f, 0.0f, 0.0f);
        capture.finish();
        float[] data = capture.data();
        assertEquals(15.0f, data[BLOCK_AT]);
        assertEquals(15.0f, data[SKY_AT]);
    }
}
