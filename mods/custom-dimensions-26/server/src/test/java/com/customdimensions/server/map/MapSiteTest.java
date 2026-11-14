package com.customdimensions.server.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.render.map.MapImages;
import com.customdimensions.server.render.fast.MapExport;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapSiteTest {
    @Test
    void theManifestListsEveryDimensionDrawnOrNot(@TempDir Path root) throws Exception {
        Path drawn = MapSite.dimension(root, "the_aviary");
        Files.createDirectories(drawn.resolve("explored"));
        Files.writeString(drawn.resolve("layers.json"), "{\"generatedAt\": 100, \"stats\": {\"bytes\": 4096}}");
        Files.writeString(drawn.resolve(ExploredExport.JSON), "{\"updatedAt\": 250, \"tiles\": 3, \"bytes\": 900}");
        Path empty = MapSite.dimension(root, "the_void");
        Files.createDirectories(empty.resolve("explored"));
        Files.writeString(empty.resolve("layers.json"), "{\"generatedAt\": 100, \"stats\": {\"bytes\": 0}}");
        Files.writeString(empty.resolve(ExploredExport.JSON), "{\"updatedAt\": 250, \"tiles\": 0, \"bytes\": 0}");
        int[] green = new int[16];
        java.util.Arrays.fill(green, 0xFF306030);
        MapImages.png(drawn.resolve("thumb.png"), 4, 4, green);
        MapSite.manifest(root, List.of(new MapSite.Entry("the_aviary", "Islands in the sky", new int[] {1, 90, -2}),
                new MapSite.Entry("overworld", null, null)));
        JsonObject m = JsonParser.parseString(Files.readString(root.resolve(MapSite.MANIFEST))).getAsJsonObject();
        JsonArray dims = m.getAsJsonArray("dimensions");
        assertEquals(2, dims.size());
        JsonObject a = dims.get(0).getAsJsonObject();
        assertEquals("the_aviary", a.get("slug").getAsString());
        assertEquals("The Aviary", a.get("name").getAsString());
        assertEquals("/maps/the_aviary/layers.json", a.get("layers").getAsString());
        assertEquals("/maps/the_aviary/thumb.png", a.get("thumb").getAsString());
        assertEquals(100, a.get("renderedAt").getAsLong());
        assertEquals(250, a.get("version").getAsLong(), "the version follows the newer of the two layers");
        assertTrue(a.get("explored").getAsBoolean());
        assertEquals("#102010", a.get("background").getAsString());
        assertEquals("[1,90,-2]", a.get("spawn").toString());
        JsonObject o = dims.get(1).getAsJsonObject();
        assertEquals("Overworld", o.get("name").getAsString());
        assertFalse(o.has("layers"), "not drawn yet");
        assertFalse(o.get("explored").getAsBoolean());
        assertFalse(o.has("thumb"));
        assertEquals(4096, a.getAsJsonObject("bytes").get("generated").getAsLong());
        assertEquals(900, a.getAsJsonObject("bytes").get("explored").getAsLong());
        assertEquals(0, o.getAsJsonObject("bytes").get("explored").getAsLong(), "nothing written reads 0");
        MapSite.manifest(root, List.of(new MapSite.Entry("the_void", null, null)));
        JsonObject v = JsonParser.parseString(Files.readString(root.resolve(MapSite.MANIFEST))).getAsJsonObject()
                .getAsJsonArray("dimensions").get(0).getAsJsonObject();
        assertFalse(v.get("explored").getAsBoolean(), "an explored layer of no tiles is not offered");
        assertEquals(0, v.getAsJsonObject("bytes").get("explored").getAsLong());
    }

    @Test
    void theShellIsCopiedOnlyWhenItDiffers(@TempDir Path root) throws Exception {
        MapSite.shell(root);
        for (String f : MapSite.SHELL_FILES) assertTrue(Files.size(root.resolve(f)) > 100, f);
        long stamp = Files.getLastModifiedTime(root.resolve("app.js")).toMillis();
        Files.setLastModifiedTime(root.resolve("app.js"), java.nio.file.attribute.FileTime.fromMillis(stamp - 60_000));
        MapSite.shell(root);
        assertEquals(stamp - 60_000, Files.getLastModifiedTime(root.resolve("app.js")).toMillis(), "an equal file is left alone");
        Files.writeString(root.resolve("app.css"), "old");
        MapSite.shell(root);
        assertTrue(Files.readString(root.resolve("app.css")).contains("#map"));
    }

    @Test
    void structureNamesReadFromTheirIds() {
        assertEquals("Village Plains", MapExport.structureName("minecraft:village_plains"));
        assertEquals("Ruined Portal Nether", MapExport.structureName("minecraft:ruined_portal_nether"));
        assertEquals("Tower Spire", MapExport.structureName("somemod:tower/spire"));
    }
}
