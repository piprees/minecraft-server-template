package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Beardifier resolution chain, pure names, no Bootstrap:
 * per-structure config -> group config -> theme default (fills registry
 * "none" only) -> registry value (null = keep it).
 */
class TerrainAdaptationOverrideTest {

    private static final Map<String, String> THEMES = Map.of(
            "settlements", "beard_thin",
            "dungeons", "bury",
            "landmarks", "beard_box",
            "endgame", "beard_box");

    @Test
    void themeDefaultFillsOnlyARegistryNone() {
        assertEquals("beard_thin", TerrainAdaptationOverride.resolveName(
                Map.of(), "mvs:barn_house", "settlements", THEMES, "none"));
        // An author's explicit adaptation is never overruled by a theme.
        assertNull(TerrainAdaptationOverride.resolveName(
                Map.of(), "tt:house", "settlements", THEMES, "beard_thin"));
        assertNull(TerrainAdaptationOverride.resolveName(
                Map.of(), "dnt:crypt", "dungeons", THEMES, "encapsulate"));
        // Groups without a theme default keep the registry value.
        assertNull(TerrainAdaptationOverride.resolveName(
                Map.of(), "mvs:wreck", "deco", THEMES, "none"));
    }

    @Test
    void dimensionConfigBeatsThemesAndRegistry() {
        Map<String, String> config = Map.of(
                "minecraft:village_plains", "beard_box",
                "dungeons", "none");
        // exact structure id wins over everything
        assertEquals("beard_box", TerrainAdaptationOverride.resolveName(
                config, "minecraft:village_plains", "settlements", THEMES, "beard_thin"));
        // group key wins over theme default AND registry
        assertEquals("none", TerrainAdaptationOverride.resolveName(
                config, "dnt:crypt", "dungeons", THEMES, "encapsulate"));
        // unrelated structures fall through to the theme fill
        assertEquals("beard_thin", TerrainAdaptationOverride.resolveName(
                config, "mvs:barn_house", "settlements", THEMES, "none"));
    }

    @Test
    void nullsAreSafe() {
        assertNull(TerrainAdaptationOverride.resolveName(
                null, null, null, null, "none"));
        assertNull(TerrainAdaptationOverride.resolveName(
                Map.of(), "a:b", null, THEMES, "none"));
    }

    @Test
    void parseAcceptsTheFiveNamesAndRejectsTheRest() {
        assertEquals(net.minecraft.world.gen.StructureTerrainAdaptation.BEARD_THIN,
                TerrainAdaptationOverride.parse("beard_thin", "test"));
        assertEquals(net.minecraft.world.gen.StructureTerrainAdaptation.NONE,
                TerrainAdaptationOverride.parse("NONE", "test"));
        assertEquals(net.minecraft.world.gen.StructureTerrainAdaptation.ENCAPSULATE,
                TerrainAdaptationOverride.parse("encapsulate", "test"));
        assertNull(TerrainAdaptationOverride.parse("beard", "test"));
        assertNull(TerrainAdaptationOverride.parse("", "test"));
        assertNull(TerrainAdaptationOverride.parse(null, "test"));
    }
}
