package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectatorCornerTest {

    @Test
    void thePreviewSitsAgainstTheLeftEdgeVerticallyCentred() {
        int[] rect = SpectatorCorner.preview(1920, 1080);
        int side = SpectatorCorner.side(1920, 1080);
        int bottom = (1080 - side) / 2;
        assertArrayEquals(new int[] {0, bottom, side, bottom + side}, rect);
    }

    @Test
    void thePreviewClearsTheMinimapSquareInTheTopLeft() {
        int[] rect = SpectatorCorner.preview(1708, 960);
        assertTrue(rect[3] <= 960 - 270,
                "top of preview at " + rect[3] + " runs into the 290x270 minimap panel");
    }

    @Test
    void theRectangleIsSquareOnEveryAspect() {
        for (int[] screen : new int[][] {{1920, 1080}, {1080, 1920}, {800, 800}, {3024, 1964}}) {
            int[] rect = SpectatorCorner.preview(screen[0], screen[1]);
            assertEquals(rect[2] - rect[0], rect[3] - rect[1],
                    "width and height differ at " + screen[0] + "x" + screen[1]);
        }
    }

    @Test
    void theSideFitsTheSHORTERAxis() {
        assertEquals(SpectatorCorner.side(1920, 1080), SpectatorCorner.side(1080, 1920));
        assertEquals(324, SpectatorCorner.side(1920, 1080));
    }

    @Test
    void aDegenerateScreenStillGivesADrawableRectangle() {
        assertEquals(1, SpectatorCorner.side(0, 0));
        assertEquals(1, SpectatorCorner.side(-4, 900));
    }
}
