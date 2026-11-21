package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.render.ColumnSamples;
import com.customdimensions.core.render.RenderGrid;
import java.util.List;
import org.junit.jupiter.api.Test;

class FastLayersTest {
    @Test
    void theDefaultStrideKeepsABorderToBorderImageNearTwoThousandPixels() {
        assertEquals(1, FastRender.defaultStep(1024));
        assertEquals(4, FastRender.defaultStep(4096));
        assertEquals(8, FastRender.defaultStep(8192));
        assertEquals(16, FastRender.defaultStep(16384));
        assertTrue(FastRender.stepAllowed(8192, 4));
        assertFalse(FastRender.stepAllowed(8192, 3), "5462 pixels a side");
        assertFalse(FastRender.stepAllowed(1024, 17));
        assertFalse(FastRender.stepAllowed(1024, 0));
    }

    @Test
    void aWideGridGoesPastTheRenderLimitsThatOfStillHolds() {
        RenderGrid g = RenderGrid.wide(0, 0, 8192, 4);
        assertEquals(4096, g.side());
        assertEquals(-8192, g.x(0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> RenderGrid.wide(0, 0, 8192, 2));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> RenderGrid.of(0, 0, 8192, 16));
    }

    @Test
    void aCutShowsItsFaceWhereSolidAndTheRunBelowElsewhereDarkerWithDepth() {
        FastPixels p = new FastPixels(2);
        int[] palette = {0xC0C0C0};
        p.cutTop[0] = 30;
        p.cutBlock[0] = 0;
        p.cutTop[1] = 10;
        p.cutBlock[1] = 0;
        p.cutTop[2] = 29;
        p.cutBlock[2] = 0;
        int[] out = FastLayers.cut(p, palette, 30);
        assertEquals(FastLayers.CUT_FACE, out[0]);
        assertTrue((out[1] & 0xFF) < (out[2] & 0xFF), "20 blocks down is darker than one");
        assertEquals(FastLayers.EMPTY, out[3]);
    }

    @Test
    void aTierPixelWithoutThatRunIsBlackAndOneWithItIsShaded() {
        FastPixels p = new FastPixels(2);
        int[] palette = {0x808080};
        p.tierTop[0][0] = 100;
        p.tierBlock[0][0] = 0;
        int[] out = FastLayers.tier(p, palette, 0);
        assertNotEquals(FastLayers.EMPTY, out[0]);
        assertEquals(FastLayers.EMPTY, out[1]);
    }

    @Test
    void waterOnTheMapDarkensWithDepth() {
        FastPixels p = new FastPixels(2);
        int[] palette = {0x4040FF};
        for (int i = 0; i < 4; i++) {
            p.top[i] = 40;
            p.topBlock[i] = 0;
        }
        p.topDepth[0] = 2;
        p.topDepth[1] = 20;
        int[] out = FastLayers.map(p, palette);
        assertTrue((out[1] & 0xFF) < (out[0] & 0xFF));
    }

    @Test
    void hillshadeIsBrighterOnSlopesFacingTheLight() {
        RenderGrid g = RenderGrid.wide(0, 0, 16, 4);
        ColumnSamples s = new ColumnSamples(g, -64, 319, 63);
        for (int py = 0; py < g.side(); py++) {
            for (int px = 0; px < g.side(); px++) {
                s.set(px, py, px < 4 ? 64 + 4 * (4 - px) : 64 + 4 * (px - 4), ColumnSamples.Fluid.NONE, "minecraft:plains");
            }
        }
        int[] out = FastLayers.hillshade(s);
        int facingWest = out[2 * g.side() + 6] & 0xFF;
        int facingEast = out[2 * g.side() + 2] & 0xFF;
        assertTrue(facingWest > facingEast, "the light is in the north-west: a slope rising eastwards faces it");
    }

    @Test
    void aFootprintIsOutlinedInsideTheImage() {
        RenderGrid g = RenderGrid.wide(0, 0, 32, 4);
        int[] base = new int[g.side() * g.side()];
        int[] out = FastLayers.structures(base, g, List.of(new FastLayers.Footprint("minecraft:village", -8, -8, 7, 7)));
        int colour = FastLayers.colour("minecraft:village");
        assertEquals(colour, out[6 * g.side() + 6]);
        assertEquals(0xFFFFFF, out[7 * g.side() + 7]);
    }
}
