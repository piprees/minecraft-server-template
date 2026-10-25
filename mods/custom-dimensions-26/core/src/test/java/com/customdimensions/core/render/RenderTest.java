package com.customdimensions.core.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderTest {
    @Test
    void theDefaultStepKeepsTheImageAtTheMaximumSide() {
        RenderGrid grid = RenderGrid.of(0, 0, RenderGrid.DEFAULT_RADIUS, null);
        assertEquals(2, grid.step());
        assertEquals(1024, grid.side());
        assertEquals(1, RenderGrid.minimumStep(100));
        assertEquals(200, RenderGrid.of(0, 0, 100, null).side());
        assertEquals(8, RenderGrid.minimumStep(RenderGrid.MAX_RADIUS));
        assertEquals(1024, RenderGrid.of(0, 0, RenderGrid.MAX_RADIUS, null).side());
        assertEquals(256, RenderGrid.of(0, 0, 1024, 8).side());
    }

    @Test
    void anUnevenStepRoundsTheSideUp() {
        RenderGrid grid = RenderGrid.of(0, 0, 100, 3);
        assertEquals(67, grid.side());
        assertTrue(grid.x(grid.side() - 1) < 100);
    }

    @Test
    void pixelsMapToColumnsFromTheNorthWestCorner() {
        RenderGrid grid = RenderGrid.of(10, -20, 64, 4);
        assertEquals(10 - 64, grid.x(0));
        assertEquals(-20 - 64, grid.z(0));
        assertEquals(10 - 64 + 4 * 5, grid.x(5));
        assertEquals(32L * 32, grid.columns());
    }

    @Test
    void radiusAndStepOutsideTheLimitsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RenderGrid.of(0, 0, RenderGrid.MAX_RADIUS + 1, null));
        assertThrows(IllegalArgumentException.class, () -> RenderGrid.of(0, 0, RenderGrid.MIN_RADIUS - 1, null));
        assertThrows(IllegalArgumentException.class, () -> RenderGrid.of(0, 0, 1024, 1));
        assertThrows(IllegalArgumentException.class, () -> RenderGrid.of(0, 0, 100, 0));
    }

    @Test
    void bandsCoverEveryRowOnce() {
        RenderGrid grid = RenderGrid.of(0, 0, 100, 3);
        List<RenderGrid.Band> bands = grid.bands(8);
        assertEquals(9, bands.size());
        assertEquals(3, bands.get(8).rows());
        assertEquals(grid.side(), bands.stream().mapToInt(RenderGrid.Band::rows).sum());
        assertThrows(IllegalArgumentException.class, () -> grid.bands(0));
    }

    @Test
    void vanillaBiomesHaveFixedDistinctColours() {
        assertEquals(67, BiomePalette.fixedIds().size());
        assertEquals(0x2040A0, BiomePalette.colour("minecraft:ocean"));
        assertEquals(BiomePalette.fixedIds().size(),
                BiomePalette.fixedIds().stream().map(BiomePalette::colour).distinct().count());
    }

    @Test
    void unknownBiomesTakeAStableColourFromTheirId() {
        int a = BiomePalette.colour("adventure:glimmer_vale");
        assertEquals(a, BiomePalette.colour("adventure:glimmer_vale"));
        assertNotEquals(a, BiomePalette.colour("adventure:ash_fields"));
        assertFalse(BiomePalette.fixed("adventure:glimmer_vale"));
        for (String id : List.of("a", "b:c", "adventure:x", "")) {
            int rgb = BiomePalette.colour(id);
            int max = Math.max(rgb >> 16 & 0xFF, Math.max(rgb >> 8 & 0xFF, rgb & 0xFF));
            int min = Math.min(rgb >> 16 & 0xFF, Math.min(rgb >> 8 & 0xFF, rgb & 0xFF));
            double lightness = (max + min) / 2.0 / 255;
            assertTrue(lightness >= 0.39 && lightness <= 0.61, id + " lightness " + lightness);
        }
        assertEquals("#00a0c8", BiomePalette.hex(BiomePalette.colour("minecraft:warm_ocean")));
    }

    @Test
    void hslCornersAreTheirColours() {
        assertEquals(0xFF0000, BiomePalette.hsl(0, 1, 0.5f));
        assertEquals(0x00FF00, BiomePalette.hsl(1f / 3, 1, 0.5f));
        assertEquals(0xFFFFFF, BiomePalette.hsl(0.5f, 1, 1));
    }

    private static ColumnSamples samples() {
        RenderGrid grid = RenderGrid.of(0, 0, 16, 4);
        ColumnSamples s = new ColumnSamples(grid, -64, 319, 63);
        for (int py = 0; py < grid.side(); py++) {
            for (int px = 0; px < grid.side(); px++) {
                int h = 60 + px * 4;
                ColumnSamples.Fluid fluid = h < 63 ? ColumnSamples.Fluid.WATER : ColumnSamples.Fluid.NONE;
                s.set(px, py, h, fluid, px < 4 ? "minecraft:ocean" : "adventure:unknown");
            }
        }
        s.set(7, 7, -64, ColumnSamples.Fluid.NONE, "minecraft:the_void");
        s.set(6, 7, 80, ColumnSamples.Fluid.LAVA, "minecraft:basalt_deltas");
        return s;
    }

    @Test
    void samplesInternBiomesAndReportRanges() {
        ColumnSamples s = samples();
        assertEquals("minecraft:ocean", s.biome(0, 0));
        assertEquals(Map.of("minecraft:ocean", 32, "adventure:unknown", 30, "minecraft:the_void", 1, "minecraft:basalt_deltas", 1),
                s.biomeCounts());
        assertTrue(s.empty(7, 7));
        assertArrayEquals(new int[] {60, 88}, s.heightRange());
        ColumnSamples empty = new ColumnSamples(RenderGrid.of(0, 0, 16, 32), 0, 255, 0);
        assertArrayEquals(new int[] {0, 0}, empty.heightRange());
    }

    @Test
    void theHeightLayerTintsWaterAndLavaAndBlacksOutEmptyColumns() {
        ColumnSamples s = samples();
        int[] px = RenderLayers.HEIGHT.paint(s);
        int side = s.grid().side();
        assertEquals(RenderLayers.EMPTY, px[7 * side + 7]);
        assertEquals(RenderLayers.LAVA, px[7 * side + 6]);
        int water = px[0];
        assertTrue((water & 0xFF) > (water >> 16 & 0xFF), "water is blue");
        int low = px[side + 1];
        int high = px[side + 5];
        assertEquals(low >> 16 & 0xFF, low & 0xFF, "land is grey");
        assertTrue((high & 0xFF) > (low & 0xFF), "higher is lighter");
    }

    @Test
    void flatGroundOnABandBoundaryDrawsNoContourAndTheRampSkipsOutliers() {
        RenderGrid grid = RenderGrid.of(0, 0, 100, 2);
        ColumnSamples s = new ColumnSamples(grid, -64, 319, 63);
        for (int py = 0; py < grid.side(); py++) {
            for (int px = 0; px < grid.side(); px++) s.set(px, py, px < 50 ? 64 : 70, ColumnSamples.Fluid.NONE, "minecraft:plains");
        }
        s.set(0, 0, -40, ColumnSamples.Fluid.NONE, "minecraft:plains");
        s.set(1, 0, 300, ColumnSamples.Fluid.NONE, "minecraft:plains");
        assertArrayEquals(new int[] {64, 70}, RenderLayers.greyRange(s));
        assertArrayEquals(new int[] {-40, 300}, s.heightRange());
        int[] px = RenderLayers.HEIGHT.paint(s);
        int side = grid.side();
        assertEquals(px[10 * side + 10], px[20 * side + 20], "flat ground at y 64 is one shade");
        assertEquals(60, px[10 * side + 10] & 0xFF);
        assertEquals(230, px[10 * side + 80] & 0xFF);
    }

    @Test
    void theBiomeLayerUsesThePalette() {
        ColumnSamples s = samples();
        int[] px = RenderLayers.BIOME.paint(s);
        assertEquals(BiomePalette.colour("minecraft:ocean"), px[0]);
        assertEquals(BiomePalette.colour("adventure:unknown"), px[5]);
        assertEquals(List.of("biome", "height"), RenderLayers.standard().stream().map(RenderLayer::name).toList());
    }

    @Test
    void filesAreWrittenWithASidecar(@TempDir Path dir) throws Exception {
        ColumnSamples s = samples();
        RenderLayer extra = new RenderLayer() {
            @Override
            public String name() {
                return "zones";
            }

            @Override
            public int[] paint(ColumnSamples samples) {
                return IntStream.range(0, samples.grid().side() * samples.grid().side()).map(i -> 0x123456).toArray();
            }
        };
        RenderFiles.Written written = RenderFiles.write(dir.resolve("overworld"), s, List.of(RenderLayers.BIOME, extra),
                new RenderFiles.Meta("overworld", -42L, "argument", "surface rule", "surface y", Map.of("sample", 500L), 2));
        Path image = dir.resolve("overworld/-42-r16-zones.png");
        assertEquals(image, written.images().get("zones"));
        BufferedImage read = ImageIO.read(image.toFile());
        assertEquals(8, read.getWidth());
        assertEquals(0x123456, read.getRGB(3, 3) & 0xFFFFFF);
        assertEquals(BiomePalette.colour("minecraft:ocean"),
                ImageIO.read(dir.resolve("overworld/-42-r16-biome.png").toFile()).getRGB(0, 0) & 0xFFFFFF);
        assertEquals(dir.resolve("overworld/-42-r16.json"), written.sidecar());
        JsonObject json = JsonParser.parseString(Files.readString(written.sidecar())).getAsJsonObject();
        assertEquals(-42L, json.get("seed").getAsLong());
        assertEquals(4, json.get("step").getAsInt());
        assertEquals(8, json.get("side").getAsInt());
        assertEquals("surface y", json.get("biomeY").getAsString());
        assertEquals(128, json.getAsJsonObject("timings").get("columnsPerSecond").getAsLong());
        assertTrue(json.getAsJsonObject("timings").has("images"));
        assertEquals("minecraft:ocean", json.getAsJsonObject("legend").getAsJsonArray("biomes").get(0).getAsJsonObject()
                .get("id").getAsString());
        assertEquals("#2040a0", json.getAsJsonObject("legend").getAsJsonArray("biomes").get(0).getAsJsonObject()
                .get("colour").getAsString());
        assertEquals(88, json.getAsJsonObject("legend").getAsJsonObject("height").get("highest").getAsInt());
        try (var files = Files.list(dir.resolve("overworld"))) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
    }

    @Test
    void aLayerOfTheWrongSizeIsRefused(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> RenderFiles.png(dir.resolve("x.png"), 4, new int[15]));
    }
}
