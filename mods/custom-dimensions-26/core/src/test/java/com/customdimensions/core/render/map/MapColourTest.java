package com.customdimensions.core.render.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapColourTest {
    private static final MapPalette P = MapPalette.vanilla();

    private static int r(int c) {
        return c >> 16 & 0xFF;
    }

    private static int g(int c) {
        return c >> 8 & 0xFF;
    }

    private static int b(int c) {
        return c & 0xFF;
    }

    @Test
    void texturePaletteHoldsEachBlocksMeanTopFace() {
        assertTrue(P.size() > 1000);
        assertEquals(new MapPalette.Entry(0x7E7E7E, 255, MapPalette.Tint.NONE, 0), P.entry("minecraft:stone"));
        MapPalette.Entry sand = P.entry("minecraft:sand");
        assertTrue(r(sand.rgb()) > b(sand.rgb()) + 40, "sand is yellow");
        MapPalette.Entry netherrack = P.entry("minecraft:netherrack");
        assertTrue(r(netherrack.rgb()) > 2 * g(netherrack.rgb()), "netherrack is red");
        assertEquals(MapPalette.Tint.GRASS, P.entry("minecraft:grass_block").tint());
        assertEquals(r(P.entry("minecraft:grass_block").rgb()), g(P.entry("minecraft:grass_block").rgb()), "grass top is grey before its tint");
        assertEquals(MapPalette.Tint.FOLIAGE, P.entry("minecraft:oak_leaves").tint());
        assertTrue(P.entry("minecraft:oak_leaves").alpha() < 255, "leaves let some light through");
        assertTrue(P.entry("minecraft:glass").alpha() < 100, "glass is mostly clear");
        assertTrue(P.entry("minecraft:ice").alpha() < 255 && P.entry("minecraft:ice").alpha() > 100);
        assertEquals(MapPalette.Tint.FIXED, P.entry("minecraft:spruce_leaves").tint());
        assertEquals(0x619961, P.entry("minecraft:spruce_leaves").fixed());
        assertEquals(MapPalette.Tint.WATER, P.entry("minecraft:water").tint());
        assertNull(P.entry("minecraft:air"));
        assertNull(P.entry("somemod:block"));
        assertEquals(64, P.digest().length());
    }

    @Test
    void grassTakesItsBiomesTint() {
        BiomeTint plains = BiomeTint.PLAINS;
        BiomeTint desert = BiomeTint.of(P, 2.0f, 0.0f, null, null, null, 0x3F76E4, BiomeTint.GrassModifier.NONE);
        BiomeTint badlands = BiomeTint.of(P, 2.0f, 0.0f, 0x90814D, null, null, 0x3F76E4, BiomeTint.GrassModifier.NONE);
        BiomeTint swamp = BiomeTint.of(P, 0.8f, 0.9f, null, null, null, 0x617B64, BiomeTint.GrassModifier.SWAMP);
        assertTrue(distance(0x91BD59, plains.grass()) <= 6, "plains grass at temperature 0.8, downfall 0.4: " + Integer.toHexString(plains.grass()));
        assertEquals(0xBFB755, desert.grass(), "the colormap's hot, dry corner");
        assertEquals(0x90814D, badlands.grass(), "an override wins");
        assertEquals(BiomeTint.SWAMP_GRASS, swamp.grass());
        MapPalette.Entry grass = P.entry("minecraft:grass_block");
        int inPlains = MapPalette.colour(grass, plains);
        int inDesert = MapPalette.colour(grass, desert);
        assertNotEquals(inPlains, inDesert);
        assertTrue(g(inPlains) > r(inPlains) + 20, "plains grass is green");
        assertTrue(r(inDesert) >= g(inDesert) - 5, "desert grass is olive");
        assertEquals(MapPalette.multiply(grass.rgb(), plains.grass()), inPlains);
        assertEquals(P.entry("minecraft:stone").rgb(), MapPalette.colour(P.entry("minecraft:stone"), desert), "an untinted block ignores the biome");
    }

    @Test
    void waterDarkensWithDepthAndShowsItsFloorInShallows() {
        int water = MapPalette.colour(P.entry("minecraft:water"), BiomeTint.PLAINS);
        int sand = P.entry("minecraft:sand").rgb();
        int one = MapShade.water(water, sand, 1);
        int five = MapShade.water(water, sand, 5);
        int twenty = MapShade.water(water, sand, 20);
        assertEquals(MapShade.mix(MapShade.scale(water, 1 - 0.35 / 24), sand, 0.25), one, "one block hides a quarter of the floor");
        assertTrue(distance(one, sand) < distance(five, sand) && distance(five, sand) < distance(twenty, sand));
        assertTrue(distance(twenty, MapShade.scale(water, 1 - 0.35 * 20 / 24.0)) <= 2, "twenty blocks hide the floor");
        assertTrue(brightness(twenty) < brightness(five), "deep water is darker");
        assertEquals(sand, MapShade.water(water, sand, 0));
    }

    @Test
    void lightKeepsOneDirectionAtEveryScale() {
        assertEquals(1.0, MapShade.light(0, 0), 1e-9);
        assertTrue(MapShade.light(0.5, 0.5) > 1, "a slope rising to the south-east faces north-west and is lit");
        assertTrue(MapShade.light(-0.5, -0.5) < 1, "a slope facing south-east is in shade");
        short[] fine = {64, 65, 66, 67, 68, 69};
        short[] coarse = {64, 66, 68};
        int[] grey = {0x808080, 0x808080, 0x808080, 0x808080, 0x808080, 0x808080};
        int[] a = MapShade.relief(grey, fine, null, 6, 1, 4);
        int[] c = MapShade.relief(grey, coarse, null, 3, 1, 8);
        assertEquals(a[2], c[1], "the same slope drawn at 4 and 8 blocks a pixel");
        short[] none = {MapShade.NO_HEIGHT};
        assertEquals(0, MapShade.relief(new int[] {0x808080}, none, null, 1, 1, 4)[0] >>> 24);
    }

    @Test
    void anExploredChunkIsDrawnFromItsSavedSections() {
        List<String> blocks = List.of("minecraft:air", "minecraft:grass_block", "minecraft:water", "minecraft:stone", "minecraft:oak_leaves",
                "minecraft:glass", "somemod:thing");
        int[] idx = new int[4096];
        // column (0,0): grass at y 64; (1,0): stone at 64 under water 65..69; (2,0): leaves at 70 over grass at 64; (3,0): nothing.
        idx[(0 * 16 + 0) * 16 + 0] = 1;
        idx[(0 * 16 + 0) * 16 + 1] = 3;
        for (int y = 1; y <= 5; y++) idx[(y * 16 + 0) * 16 + 1] = 2;
        idx[(0 * 16 + 0) * 16 + 2] = 1;
        idx[(6 * 16 + 0) * 16 + 2] = 4;
        idx[(2 * 16 + 1) * 16 + 0] = 6;
        long[] data = PackedIndices.pack(idx, blocks.size(), 4);
        assertEquals(256, data.length, "seven entries pack at 4 bits, 16 a long");
        int[] biomeIdx = new int[64];
        biomeIdx[0] = 1;
        List<String> biomes = List.of("minecraft:plains", "minecraft:desert");
        long[] biomeData = PackedIndices.pack(biomeIdx, 2, 1);
        BiomeTint desert = BiomeTint.of(P, 2.0f, 0.0f, null, null, null, 0x3F76E4, BiomeTint.GrassModifier.NONE);
        List<String> asked = new ArrayList<>();
        ExploredChunk.Resolver resolver = new ExploredChunk.Resolver() {
            @Override
            public MapPalette.Entry block(String id) {
                asked.add(id);
                MapPalette.Entry e = P.entry(id);
                return e != null ? e : MapPalette.Entry.opaque(0x123456);
            }

            @Override
            public BiomeTint biome(String id) {
                return id.equals("minecraft:desert") ? desert : BiomeTint.PLAINS;
            }
        };
        ExploredChunk.Section empty = new ExploredChunk.Section(5, List.of("minecraft:air"), null, List.of("minecraft:plains"), null);
        ExploredChunk.Columns c = ExploredChunk.columns(List.of(empty, new ExploredChunk.Section(4, blocks, data, biomes, biomeData)), resolver);
        MapPalette.Entry grass = P.entry("minecraft:grass_block");
        assertEquals(MapPalette.colour(grass, desert), c.rgb()[0], "grass in the desert cell");
        assertEquals(64, c.height()[0]);
        int water = MapPalette.colour(P.entry("minecraft:water"), BiomeTint.PLAINS);
        assertEquals(MapShade.water(water, P.entry("minecraft:stone").rgb(), 5), c.rgb()[1], "five blocks of water over stone");
        assertEquals(69, c.height()[1]);
        assertTrue(c.water()[1]);
        int leaves = MapPalette.colour(P.entry("minecraft:oak_leaves"), BiomeTint.PLAINS);
        int expected = MapShade.over(leaves, P.entry("minecraft:oak_leaves").alpha(), MapPalette.colour(grass, desert));
        assertTrue(distance(expected, c.rgb()[2]) <= 2, "leaves laid over grass");
        assertEquals(70, c.height()[2]);
        assertEquals(MapShade.NO_HEIGHT, c.height()[3]);
        assertEquals(0x123456, c.rgb()[16], "an unknown block takes the resolver's colour");
        assertNotNull(c);
        assertTrue(asked.contains("somemod:thing"));
    }

    @Test
    void anExploredRegionAveragesCellsAndRoundTrips() throws Exception {
        ExploredRegion region = new ExploredRegion(-1, 2, 4);
        assertEquals(128, region.side());
        int[] rgb = new int[256];
        short[] height = new short[256];
        boolean[] water = new boolean[256];
        java.util.Arrays.fill(height, MapShade.NO_HEIGHT);
        rgb[0] = 0x000000;
        height[0] = 60;
        rgb[1] = 0xFFFFFF;
        height[1] = 64;
        water[1] = true;
        region.add(31, 0, new ExploredChunk.Columns(rgb, height, water));
        int cell = 31 * 4;
        assertTrue(region.filled(cell));
        assertEquals(0x7F7F7F, region.rgb(cell));
        assertEquals(62, region.height(cell));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        region.write(out);
        ExploredRegion back = ExploredRegion.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertEquals(-1, back.rx());
        assertEquals(region.rgb(cell), back.rgb(cell));
        assertEquals(region.height(cell), back.height(cell));
        assertEquals(1, back.filledCells());
    }

    @Test
    void fingerprintsFollowEveryInput() {
        String a = MapFingerprint.of(1, 1024, "p", "gen");
        assertEquals(a, MapFingerprint.of(1, 1024, "p", "gen"));
        assertNotEquals(a, MapFingerprint.of(2, 1024, "p", "gen"));
        assertNotEquals(a, MapFingerprint.of(1, 2048, "p", "gen"));
        assertNotEquals(a, MapFingerprint.of(1, 1024, "q", "gen"));
        assertNotEquals(a, MapFingerprint.of(1, 1024, "p", "gen2"));
        assertNotEquals(MapFingerprint.of(1, 1024, "p", "ab", "c"), MapFingerprint.of(1, 1024, "p", "a", "bc"));
    }

    private static int distance(int a, int b) {
        return Math.abs(r(a) - r(b)) + Math.abs(g(a) - g(b)) + Math.abs(b(a) - b(b));
    }

    private static int brightness(int c) {
        return r(c) + g(c) + b(c);
    }
}
