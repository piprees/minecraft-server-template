package com.customdimensions.client.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class LightLayersTest {
    @Test
    void aSentArrayIsReadByNibbleAndAnEmptyOneIsDarkAndAnAbsentOneTakesTheDefault() {
        byte[] data = new byte[2048];
        int index = (5 << 8) | (3 << 4) | 2;
        data[index >> 1] = (byte) (index % 2 == 0 ? 0x0B : 0xB0);
        BitSet mask = new BitSet();
        mask.set(2);
        BitSet empty = new BitSet();
        empty.set(3);
        LightLayers light = LightLayers.of(4, mask, empty, List.of(data), 15);
        assertEquals(11, light.get(1, 2, 5, 3), "slot 2 is section 1");
        assertEquals(0, light.get(1, 2, 6, 3));
        assertEquals(0, light.get(2, 0, 0, 0), "slot 3 is sent empty");
        assertEquals(15, light.get(0, 0, 0, 0), "slot 1 is absent");
        assertEquals(15, light.get(40, 0, 0, 0), "past the top");
        assertEquals(15, light.get(-5, 0, 0, 0), "below the bottom");
    }

    @Test
    void worldCoordinatesWrapIntoTheSection() {
        byte[] data = new byte[2048];
        data[0] = 0x07;
        BitSet mask = new BitSet();
        mask.set(1);
        LightLayers light = LightLayers.of(1, mask, new BitSet(), List.of(data), 0);
        assertEquals(7, light.get(0, 3072, -64, 16));
    }
}
