package com.customdimensions.compiler.datapack;

import com.customdimensions.core.structure.StructureSpec;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuresCompilerTest {

    private static final String NOISE = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";

    @Test
    void everyDimensionGetsAControlFileForItsLevel() {
        DatapackCompiler.Output out = Fixtures.compile("t", NOISE);
        String text = out.files().get(StructuresCompiler.path("t"));
        StructureSpec spec = StructureSpec.fromJson(JsonParser.parseString(text).getAsJsonObject());
        assertEquals("adventure:t", spec.level());
        assertTrue(spec.noise());
        assertTrue(out.report().entry("t").structures().getFirst().startsWith("density normal; noise placement:"));
        assertTrue(out.report().entry("t").unsupported().stream().noneMatch(u -> u.contains("structures")));
    }

    @Test
    void aBaseWorldsControlNamesItsStem() {
        DatapackCompiler.Output out = Fixtures.compile("overworld", NOISE);
        String text = out.files().get(StructuresCompiler.path("overworld"));
        assertEquals("minecraft:overworld", JsonParser.parseString(text).getAsJsonObject().get("level").getAsString());
    }

    @Test
    void unknownIdsAreWarningsAndKnownOnesAreNot() {
        DatapackCompiler.Output out = Fixtures.compile("t", NOISE + ", \"structures\": {"
                + "\"force\": [{\"structure\": \"minecraft:igloo\", \"x\": 0, \"z\": 0}, {\"structure\": \"gone:tower\", \"x\": 0, \"z\": 0}],"
                + "\"shuns\": {\"village\": {}, \"#minecraft:nothing_here\": {}}, \"exclude\": [\"minecraft:villages\", \"gone:sets\"],"
                + "\"replace\": {\"minecraft:igloo\": \"minecraft:desert_pyramid\"}}");
        List<String> warnings = out.report().entry("t").structureWarnings();
        assertTrue(warnings.contains("force: `gone:tower` is not a vanilla or bridged structure"), warnings.toString());
        assertTrue(warnings.contains("shuns: `#minecraft:nothing_here` is not a vanilla or bridged structure"), warnings.toString());
        assertTrue(warnings.contains("`gone:sets` is not a vanilla or bridged structure set"), warnings.toString());
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(out.reportText().contains("Structure warnings | 3"), out.reportText());
    }

    @Test
    void bridgedIdsAreKnown() {
        StructuresCompiler.Catalogue c = StructuresCompiler.Catalogue.of(Fixtures.vanilla(),
                Fixtures.definitions().resolveSibling("bridges"));
        assertTrue(c.sets().contains("minecraft:villages"));
        assertTrue(c.structures().contains("minecraft:ancient_city"));
        assertTrue(c.tags().contains("#minecraft:village"));
        assertTrue(c.structures().stream().anyMatch(s -> s.startsWith("mes:")), "the Moog bridge adopts mes structures");
        assertFalse(StructuresCompiler.Catalogue.of(Fixtures.vanilla(), null).structures().stream().anyMatch(s -> s.startsWith("mes:")));
    }

    @Test
    void aMalformedBlockStopsTheCompile() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Fixtures.compile("t", NOISE + ", \"structures\": {\"density\": \"lots\"}"));
        assertTrue(e.getMessage().contains("t: structures.density"), e.getMessage());
    }

    @Test
    void bridgesBesideFollowsThePropertyElseTheSiblingFolder() {
        String saved = System.getProperty("customdimensions.bridges");
        try {
            System.clearProperty("customdimensions.bridges");
            assertEquals(Path.of("/a/b/bridges"), StructuresCompiler.bridgesBeside(Path.of("/a/b/dimensions")));
            System.setProperty("customdimensions.bridges", "/x/y");
            assertEquals(Path.of("/x/y"), StructuresCompiler.bridgesBeside(Path.of("/a/b/dimensions")));
        } finally {
            if (saved == null) {
                System.clearProperty("customdimensions.bridges");
            } else {
                System.setProperty("customdimensions.bridges", saved);
            }
        }
    }
}
