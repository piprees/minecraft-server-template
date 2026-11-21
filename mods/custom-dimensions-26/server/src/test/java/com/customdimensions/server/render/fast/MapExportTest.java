package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.render.SliceChoice;
import com.customdimensions.core.render.map.BiomeTint;
import com.customdimensions.core.render.map.MapPalette;
import com.customdimensions.core.render.map.MapShade;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapExportTest {
    private static final MapPalette P = MapPalette.vanilla();

    @Test
    void anAquiferLevelsCavesHoldNoPickerFluid() {
        assertTrue(MapExport.pickerFluidShown(true, false), "open sky: the sea is drawn");
        assertFalse(MapExport.pickerFluidShown(true, true), "under a roof in an aquifer level: no flooded cave");
        assertTrue(MapExport.pickerFluidShown(false, true), "a level without aquifers fills its caves from the picker");
        MapPalette.Entry water = P.entry("minecraft:water");
        MapPalette.Entry floor = P.entry("minecraft:deepslate");
        BiomeTint t = BiomeTint.PLAINS;
        int dry = MapExport.surfaceColour(water, floor, 0, t);
        assertEquals(MapPalette.colour(floor, t), dry, "a dropped fluid shows the ground under it");
        int wet = MapExport.surfaceColour(water, floor, 6, t);
        assertEquals(MapShade.water(MapPalette.colour(water, t), MapPalette.colour(floor, t), 6), wet);
        MapPalette.Entry lava = P.entry("minecraft:lava");
        assertEquals(MapPalette.colour(lava, t), MapExport.surfaceColour(lava, floor, 3, t), "lava hides its floor");
        assertEquals(MapPalette.colour(floor, t), MapExport.surfaceColour(floor, null, 0, t));
    }

    @Test
    void aRunBelongsToTheHighestBandItsTopReaches() {
        List<SliceChoice.Band> bands = List.of(new SliceChoice.Band(190, 220, 205, 10), new SliceChoice.Band(120, 140, 131, 10));
        assertEquals(0, MapExport.bandOf(bands, 212));
        assertEquals(1, MapExport.bandOf(bands, 150));
        assertEquals(-1, MapExport.bandOf(bands, 80));
    }
}
