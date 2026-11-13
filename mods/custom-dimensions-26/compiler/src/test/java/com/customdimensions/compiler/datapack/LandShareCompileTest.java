package com.customdimensions.compiler.datapack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code terrain.landShare}: continentalness wrapped in {@code customdimensions:land_share} at the terrain's coast, over the player border,
 * in the form the runtime binds ({@link VanillaForms#runtimePath}); vanilla reads the unshifted continentalness.
 */
class LandShareCompileTest {

    private static final String CONTINENTS = "data/adventure/worldgen/density_function/terrain/s/continents.json";

    private static JsonObject shift(DatapackCompiler.Output out) {
        assertTrue(!out.files().get(CONTINENTS).contains("land_share"), "vanilla's form is unshifted");
        JsonObject cached = JsonParser.parseString(out.files().get(VanillaForms.runtimePath(CONTINENTS))).getAsJsonObject();
        assertEquals("minecraft:cache", cached.get("type").getAsString(), cached.toString());
        JsonObject f = cached.getAsJsonObject("input");
        assertEquals("customdimensions:land_share", f.get("type").getAsString());
        return f;
    }

    @Test
    void aPresetsOwnTerrainTakesTheShareAtItsCoast() {
        DatapackCompiler.Output out = Fixtures.compile("s", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, "
                + "\"terrain\": {\"landShare\": 0.8}, \"borders\": {\"player\": 512, \"generation\": 1024}");
        JsonObject f = shift(out);
        assertEquals(0.8, f.get("share").getAsDouble());
        assertEquals(-0.19, f.get("coast").getAsDouble());
        assertEquals(512, f.get("radius").getAsInt());
        assertTrue(out.files().get("data/adventure/worldgen/noise_settings/s.json").contains("adventure:terrain/s/"));
        assertTrue(out.reportText().contains("Land share 0.8"), out.reportText());
    }

    @Test
    void aLandTerrainTakesItsOwnCoastAndKeepsItsLayout() {
        DatapackCompiler.Output out = Fixtures.compile("s", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, "
                + "\"terrain\": {\"base\": \"continents\", \"params\": {\"coast\": 0.1}, \"landShare\": 0.3, "
                + "\"layout\": {\"type\": \"radial\", \"radius\": 300}}");
        JsonObject f = shift(out);
        assertEquals(0.1, f.get("coast").getAsDouble());
        assertEquals(RegionLayout.DEFAULT_RADIUS, f.get("radius").getAsInt());
        assertTrue(f.get("argument").toString().contains("minecraft:gradient"), "the layout's shape is inside what is shifted: " + f);
    }

    @Test
    void withoutTheFieldNothingChanges() {
        String body = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"terrain\": {\"base\": \"continents\"}";
        DatapackCompiler.Output out = Fixtures.compile("s", body);
        assertTrue(!out.files().get(CONTINENTS).contains("land_share"));
        assertEquals(out.files(), Fixtures.compile("s", body).files());
    }

    @Test
    void terrainsWithoutALandCoastRefuseIt() {
        for (String terrain : new String[]{"{\"base\": \"islands\", \"landShare\": 0.5}", "{\"base\": \"floating_islands\", \"landShare\": 0.5}"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("s",
                    "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"terrain\": " + terrain));
            assertTrue(e.getMessage().contains("terrain.landShare"), e.getMessage());
        }
        IllegalArgumentException nether = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("s",
                "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, \"terrain\": {\"landShare\": 0.5}"));
        assertTrue(nether.getMessage().contains("scales.climate"), nether.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("s",
                "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"terrain\": {\"landShare\": 1.5}"));
    }
}
