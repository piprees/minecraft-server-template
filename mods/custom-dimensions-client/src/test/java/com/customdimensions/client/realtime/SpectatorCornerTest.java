package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SpectatorCornerTest {

    @Test
    void topLeftRunsToTheTopOfTheScreen() {
        int[] rect = SpectatorCorner.topLeft(1920, 1080);
        int side = SpectatorCorner.side(1920, 1080);
        assertArrayEquals(new int[] {0, 1080 - side, side, 1080}, rect);
    }

    @Test
    void theRectangleIsSquareOnEveryAspect() {
        for (int[] screen : new int[][] {{1920, 1080}, {1080, 1920}, {800, 800}, {3024, 1964}}) {
            int[] rect = SpectatorCorner.topLeft(screen[0], screen[1]);
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
