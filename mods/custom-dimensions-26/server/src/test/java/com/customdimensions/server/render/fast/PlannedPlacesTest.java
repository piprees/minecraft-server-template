package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.core.render.SliceChoice;
import com.customdimensions.core.render.map.MapTiles;
import com.customdimensions.core.structure.StructureSpec;
import com.customdimensions.server.structure.StructureRuntime;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The places a structure plan names, drawn where nothing else shows them; and which layers a sheet carries. */
class PlannedPlacesTest {
    private static final String FORT = "somemod:fort";

    private static StructureSpec spec(List<StructureSpec.Force> force) {
        return new StructureSpec("adventure:test", "none", false, "density none", 32, 4, -2, Map.of(), "none", List.of(), List.of(),
                List.of(), Map.of(), Map.of(), force, List.of(), List.of(), Map.of(), Map.of(), List.of());
    }

    @Test
    void thePlanNamesEveryForcedStartWhetherTheRegistryHasItOrNot() {
        StructureSpec spec = spec(List.of(new StructureSpec.Force(FORT, 240, -300, null, 0, true),
                new StructureSpec.Force("minecraft:igloo", 100, 100, null, 64, true)));
        List<StructureRuntime.Planned> planned = StructureRuntime.planned(spec, List.of(
                new StructureRuntime.Placed("force", "minecraft:igloo", 7, 5, true),
                new StructureRuntime.Placed("want", "minecraft:village_plains", -3, 2, true)));
        assertEquals(4, planned.size());
        assertEquals(new StructureRuntime.Planned("force", FORT, 240, -300, false), planned.get(0), "not in the registry: its definition's place");
        assertEquals(new StructureRuntime.Planned("force", "minecraft:igloo", 7 * 16 + 8, 5 * 16 + 8, true), planned.get(1),
                "placed: the centre of the chunk its control chose");
        assertEquals(new StructureRuntime.Planned("want", "minecraft:village_plains", -3 * 16 + 8, 2 * 16 + 8, true), planned.get(2));
        assertEquals(new StructureRuntime.Planned("spawn", "spawn", 4 * 16 + 8, -2 * 16 + 8, true), planned.get(3));
    }

    @Test
    void aBareMapShowsThePlannedPlaces() {
        RenderGrid grid = RenderGrid.wide(0, 0, 256, 2);
        int side = grid.side();
        int[] bare = new int[side * side];
        assertTrue(FastLayers.bare(bare));
        List<FastLayers.Marker> markers = List.of(new FastLayers.Marker("force", FORT, 40, -60),
                new FastLayers.Marker("spawn", "spawn", 0, 0));
        int[] drawn = FastLayers.markers(bare, grid, markers);
        assertFalse(FastLayers.bare(drawn), "a void world's map is not black once its places are drawn");
        int half = Math.max(FastLayers.MARKER_PIXELS, FastLayers.MARKER_BLOCKS / grid.step());
        int cx = (40 + 256) / 2;
        int cz = (-60 + 256) / 2;
        assertEquals(FastLayers.colour(FORT), drawn[cz * side + cx + half], "the square's side in the structure's colour");
        assertEquals(0xFFFFFF, drawn[cz * side + cx], "a white cross at its centre");
        assertEquals(0xFFFFFF, drawn[(128 - half) * side + 128], "the spawn's diamond");
        assertEquals(0, bare[cz * side + cx], "the base is left as it was");
    }

    @Test
    void aMarkerAFootprintHoldsIsNotDrawnAgain() {
        RenderGrid grid = RenderGrid.wide(0, 0, 256, 2);
        int side = grid.side();
        int[] base = new int[side * side];
        List<FastLayers.Footprint> footprints = List.of(new FastLayers.Footprint(FORT, 20, -80, 60, -40));
        List<FastLayers.Marker> held = List.of(new FastLayers.Marker("force", FORT, 40, -60));
        int[] withHeld = FastLayers.structures(base, grid, footprints, held);
        org.junit.jupiter.api.Assertions.assertArrayEquals(FastLayers.structures(base, grid, footprints), withHeld);
        int[] withLoose = FastLayers.structures(base, grid, footprints, List.of(new FastLayers.Marker("force", "other:place", -100, 100)));
        assertNotEquals(java.util.Arrays.hashCode(FastLayers.structures(base, grid, footprints)), java.util.Arrays.hashCode(withLoose));
    }

    @Test
    void aWorldWithNoGroundGetsAPlainThumbnailWithItsPlaces() {
        MapTiles plan = MapTiles.of(512);
        int s0 = plan.side(0);
        int[] thumb = MapExport.plainThumb("The Red Monument", plan, List.of(new FastLayers.Marker("force", FORT, 0, 0)));
        assertEquals(s0 * s0, thumb.length);
        assertFalse(MapExport.transparent(thumb));
        for (int p : thumb) assertEquals(0xFF, p >>> 24, "opaque");
        assertTrue(java.util.Arrays.stream(thumb).anyMatch(p -> (p & 0xFFFFFF) == FastLayers.colour(FORT)), "the forced place is drawn");
        assertTrue(java.util.Arrays.stream(thumb).anyMatch(p -> (p & 0xFFFFFF) == com.customdimensions.core.render.ContactSheet.TEXT),
                "the name is written");
        assertTrue(MapExport.transparent(new int[16]));
    }

    @Test
    void theMarkersFileListsPlannedPlacesNoStartHolds() {
        JsonObject start = new JsonObject();
        start.addProperty("type", "minecraft:igloo");
        start.addProperty("x", 0);
        start.addProperty("z", 0);
        JsonArray box = new JsonArray();
        for (int v : new int[] {-10, -10, 10, 10}) box.add(v);
        start.add("box", box);
        JsonObject json = MapExport.markersJson(List.of(start), List.of(new FastLayers.Marker("force", "minecraft:igloo", 4, 4),
                new FastLayers.Marker("force", FORT, 300, 300), new FastLayers.Marker("spawn", "spawn", 50, 50)));
        JsonArray planned = json.getAsJsonArray("planned");
        assertEquals(2, planned.size(), "the igloo's place is held by its start");
        assertEquals("Fort", planned.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Spawn", planned.get(1).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void anOpenWorldWithoutSkyBandsHasNoTierPanels() {
        List<SliceChoice.Slice> caves = List.of(new SliceChoice.Slice("caves", 0, ""), new SliceChoice.Slice("sea-level", 63, ""));
        assertFalse(FastRender.tiersShown(false, caves), "a surface world's lower runs are cave roofs and overhangs");
        assertTrue(FastRender.tiersShown(true, caves), "a roofed world's runs under its roof");
        assertTrue(FastRender.tiersShown(false, List.of(new SliceChoice.Slice("sky-1", 180, ""))), "islands in the sky");
    }

    @Test
    void aRoofedSheetOpensOnItsFloor() {
        assertEquals(List.of("roofoff", "biome", "hillshade", "structures"),
                FastWave.sheetLayers(List.of("map", "roofoff", "cut", "biome", "height", "hillshade", "structures")));
        assertEquals(List.of("map", "biome", "hillshade"), FastWave.sheetLayers(List.of("map", "biome", "height", "hillshade")));
    }
}
