package com.customdimensions.core.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureLayerTest {

    @Test
    void aStartIsAWhiteRimmedSquareOverADimmedBiomeMap() {
        RenderGrid g = new RenderGrid(0, 0, 64, 8);
        ColumnSamples s = new ColumnSamples(g, -64, 319, 63);
        for (int py = 0; py < g.side(); py++) for (int px = 0; px < g.side(); px++) s.set(px, py, 70, ColumnSamples.Fluid.NONE, "minecraft:plains");
        StructureLayer layer = new StructureLayer(List.of(new StructureLayer.Marker("minecraft:village_plains", 0, 0),
                new StructureLayer.Marker("minecraft:far", 10_000, 0)));
        int[] rgb = layer.paint(s);
        int side = g.side();
        int centre = 8 * side + 8;
        assertEquals(StructureLayer.colour("minecraft:village_plains"), rgb[centre]);
        assertEquals(0xFFFFFF, rgb[6 * side + 8]);
        int plains = BiomePalette.colour("minecraft:plains");
        int corner = rgb[0];
        assertNotEquals(plains, corner);
        assertEquals((plains >> 16 & 0xFF) / 3, corner >> 16 & 0xFF);
        assertEquals("structures", layer.name());
        assertTrue(layer.legend().containsKey("minecraft:village_plains"));
    }
}
