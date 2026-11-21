package com.customdimensions.core.render.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapTilesTest {
    @Test
    void zoomZeroHoldsTheWholeBorderInOneTile() {
        for (int radius : new int[] {16, 256, 688, 1024, 1040, 2048, 4096, 8192, 16384}) {
            MapTiles t = MapTiles.of(radius);
            assertEquals(1, t.tiles(0), "radius " + radius);
            assertTrue((long) MapTiles.TILE * t.blocksPerPixel(0) >= 2L * radius);
            if (t.maxZoom() > 0) assertTrue((long) MapTiles.TILE * t.blocksPerPixel(1) < 2L * radius, "zoom 0 is the coarsest needed");
        }
    }

    @Test
    void finestIsFourBlocksUpToTheWidestSingleRender() {
        assertEquals(4, MapTiles.of(8192).finest());
        assertEquals(4096, MapTiles.of(8192).side(MapTiles.of(8192).maxZoom()));
        assertEquals(4, MapTiles.of(8192).maxZoom());
        assertEquals(8, MapTiles.of(16384).finest());
        assertEquals(1, MapTiles.of(1024).maxZoom());
        assertEquals(0, MapTiles.of(256).maxZoom());
    }

    @Test
    void anOddBorderEndsInsideItsLastTile() {
        MapTiles t = MapTiles.of(688);
        assertEquals(1, t.maxZoom());
        assertEquals(344, t.side(1));
        assertEquals(2, t.tiles(1));
        assertEquals(172, t.side(0));
        MapTiles odd = MapTiles.of(1040);
        assertEquals(520, odd.side(odd.maxZoom()));
        assertEquals(3, odd.tiles(odd.maxZoom()));
        assertEquals(2, odd.maxZoom());
        assertEquals(130, odd.side(0));
    }

    @Test
    void aCoarsePixelIsAFinePixelsColumn() {
        MapTiles t = MapTiles.of(4096);
        for (int z = 0; z <= t.maxZoom(); z++) {
            for (int i = 0; i < t.side(z); i += 37) {
                assertEquals(t.block(z, i), t.block(t.maxZoom(), i << (t.maxZoom() - z)));
            }
        }
        assertEquals(-4096, t.block(0, 0));
        assertEquals(0, t.pixel(-4096));
        assertEquals(1024, t.pixel(0));
        assertEquals(-1, t.pixel(-4097));
        assertThrows(IllegalArgumentException.class, () -> t.blocksPerPixel(t.maxZoom() + 1));
    }

    @Test
    void piecesCutIntoTheSameTilesAsOneImage() throws Exception {
        MapTiles t = MapTiles.of(1040);
        int side = t.side(t.maxZoom());
        int[] image = new int[side * side];
        for (int i = 0; i < image.length; i++) image[i] = 0xFF000000 | (i * 7919);
        Map<String, int[]> whole = new HashMap<>();
        TilePyramid one = new TilePyramid(t, false, (z, x, y, argb) -> whole.put(z + "/" + x + "/" + y, argb));
        one.piece(0, 0, side, side, image);
        one.finish();
        Map<String, int[]> parts = new HashMap<>();
        TilePyramid many = new TilePyramid(t, false, (z, x, y, argb) -> parts.put(z + "/" + x + "/" + y, argb));
        for (int y0 = 0; y0 < side; y0 += 256) {
            for (int x0 = 0; x0 < side; x0 += 256) {
                int w = Math.min(256, side - x0);
                int h = Math.min(256, side - y0);
                int[] piece = new int[w * h];
                for (int y = 0; y < h; y++) System.arraycopy(image, (y0 + y) * side + x0, piece, y * w, w);
                many.piece(x0, y0, w, h, piece);
            }
        }
        many.finish();
        assertEquals(whole.keySet(), parts.keySet());
        whole.forEach((k, v) -> assertArrayEquals(v, parts.get(k), k));
        assertEquals(9 + 4 + 1, whole.size());
        int[] edge = whole.get("2/2/0");
        assertEquals(0, edge[8] >>> 24, "past the border is transparent");
        assertFalse(edge[7] >>> 24 == 0);
        assertThrows(IllegalArgumentException.class, () -> many.piece(10, 0, 4, 4, new int[16]));
    }

    @Test
    void continuousLayersAverageAndCategoricalKeepTheNorthWestPixel() throws Exception {
        MapTiles t = MapTiles.of(1024);
        int side = t.side(1);
        int[] image = new int[side * side];
        image[0] = 0xFF000000;
        image[1] = 0xFFFFFFFF;
        image[side] = 0x00000000;
        image[side + 1] = 0xFFFFFFFF;
        Map<String, int[]> mean = new HashMap<>();
        TilePyramid a = new TilePyramid(t, false, (z, x, y, argb) -> mean.put(z + "/" + x + "/" + y, argb));
        a.piece(0, 0, side, side, image);
        a.finish();
        int p = mean.get("0/0/0")[0];
        assertEquals(0xBF, p >>> 24, "three of four opaque");
        assertEquals(0xAA, p & 0xFF, "the transparent pixel adds no colour");
        Map<String, int[]> kept = new HashMap<>();
        TilePyramid c = new TilePyramid(t, true, (z, x, y, argb) -> kept.put(z + "/" + x + "/" + y, argb));
        c.piece(0, 0, side, side, image);
        c.finish();
        assertEquals(0xFF000000, kept.get("0/0/0")[0]);
        assertFalse(mean.containsKey("1/1/1"), "an empty tile is not written");
    }
}
