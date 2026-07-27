package com.customdimensions.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DimensionConfigLoaderTest {

    private DimensionConfigLoader.Settings settings;

    private DimensionConfigLoader.Settings defaultSettings() {
        DimensionConfigLoader.Settings s = new DimensionConfigLoader.Settings();
        s.namespace = "adventure";
        return s;
    }

    private void writeDim(Path root, String slug, String json) throws IOException {
        Path dims = root.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve(slug + ".json"), json);
    }

    // --- overlay resolution ---------------------------------------------------

    @Test
    void platformDefaultLoadsWhenNoOverlay(@TempDir Path config, @TempDir Path overlay) throws IOException {
        writeDim(config, "the_claymarsh", "{\"type\":\"overworld\",\"seed\":42}");
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadDimensions(config, overlay, defaultSettings(), null);
        assertEquals(1, dims.size());
        assertEquals(42L, dims.get("the_claymarsh").getSeed());
        assertEquals("adventure", dims.get("the_claymarsh").getNamespace());
    }

    @Test
    void overlayWithoutOverridesReplacesEntirely(@TempDir Path config, @TempDir Path overlay) throws IOException {
        writeDim(config, "the_claymarsh", "{\"type\":\"overworld\",\"seed\":42,\"structureDensity\":\"sparse\"}");
        writeDim(overlay, "the_claymarsh", "{\"type\":\"nether\",\"seed\":7}");
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadDimensions(config, overlay, defaultSettings(), null);
        DimensionConfig dim = dims.get("the_claymarsh");
        assertEquals("nether", dim.getType());
        assertEquals(7L, dim.getSeed());
        // full replace: the platform's structureDensity must NOT survive
        assertNull(dim.getStructureDensity());
    }

    @Test
    void overlayOverridesDeepMergeOverPlatform(@TempDir Path config, @TempDir Path overlay) throws IOException {
        writeDim(config, "the_claymarsh",
                "{\"type\":\"overworld\",\"seed\":42,\"difficulty\":{\"mobMultiplier\":1.8,\"playerLuck\":0.8}}");
        writeDim(overlay, "the_claymarsh",
                "{\"overrides\":{\"difficulty\":{\"mobMultiplier\":1.5}}}");
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadDimensions(config, overlay, defaultSettings(), null);
        DimensionConfig dim = dims.get("the_claymarsh");
        assertEquals("overworld", dim.getType());
        assertEquals(42L, dim.getSeed());
        assertEquals(1.5, dim.getDifficulty().getMobMultiplier());
        // untouched sibling key survives the merge
        assertEquals(0.8, dim.getDifficulty().getPlayerLuck());
    }

    @Test
    void emptyOverlayFileSkipsDimension(@TempDir Path config, @TempDir Path overlay) throws IOException {
        writeDim(config, "the_claymarsh", "{\"type\":\"overworld\"}");
        writeDim(config, "the_gauntlet", "{\"type\":\"overworld\"}");
        writeDim(overlay, "the_claymarsh", "{}");
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadDimensions(config, overlay, defaultSettings(), null);
        assertFalse(dims.containsKey("the_claymarsh"));
        assertTrue(dims.containsKey("the_gauntlet"));
    }

    @Test
    void consumerAddedDimensionGetsBrandNamespace(@TempDir Path config, @TempDir Path overlay) throws IOException {
        writeDim(config, "the_claymarsh", "{\"type\":\"overworld\"}");
        writeDim(overlay, "my_dimension", "{\"type\":\"overworld\",\"seed\":9}");
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadDimensions(config, overlay, defaultSettings(), "mybrand");
        assertEquals("mybrand", dims.get("my_dimension").getNamespace());
        assertEquals("mybrand:my_dimension", dims.get("my_dimension").getDimensionId());
        // platform dims keep the platform namespace
        assertEquals("adventure", dims.get("the_claymarsh").getNamespace());
    }

    @Test
    void consumerAddedFallsBackToPlatformNamespaceWithoutBrandSlug(@TempDir Path config, @TempDir Path overlay) throws IOException {
        writeDim(overlay, "my_dimension", "{\"type\":\"overworld\"}");
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadDimensions(config, overlay, defaultSettings(), null);
        assertEquals("adventure", dims.get("my_dimension").getNamespace());
    }

    @Test
    void missingOverlayDirectoryIsFine(@TempDir Path config) throws IOException {
        writeDim(config, "the_claymarsh", "{\"type\":\"overworld\"}");
        Map<String, DimensionConfig> dims = DimensionConfigLoader.loadDimensions(
                config, config.resolve("overlay"), defaultSettings(), null);
        assertEquals(1, dims.size());
    }

    @Test
    void invalidJsonFileIsSkippedNotFatal(@TempDir Path config, @TempDir Path overlay) throws IOException {
        writeDim(config, "the_claymarsh", "{\"type\":\"overworld\"}");
        writeDim(config, "broken", "{not json!!");
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadDimensions(config, overlay, defaultSettings(), null);
        assertEquals(1, dims.size());
        assertTrue(dims.containsKey("the_claymarsh"));
    }

    // --- settings defaults ----------------------------------------------------

    @Test
    void settingsDefaultsMergeUnderEachDimension(@TempDir Path config, @TempDir Path overlay) throws IOException {
        Files.createDirectories(config);
        Files.writeString(config.resolve("settings.json"), """
                {"namespace":"adventure","idleUnloadMinutes":7,
                 "defaults":{"frameBlock":"minecraft:crying_obsidian",
                             "borders":{"player":4096,"generation":4096},
                             "difficulty":{"mobMultiplier":1.0,"hostileSpawning":true}}}
                """);
        writeDim(config, "a", "{\"type\":\"overworld\"}");
        writeDim(config, "b", "{\"type\":\"overworld\",\"borders\":{\"player\":1024},\"difficulty\":{\"mobMultiplier\":2.0}}");
        writeDim(config, "c", "{\"type\":\"overworld\",\"portal\":{\"igniterItem\":\"minecraft:stick\"}}");

        DimensionConfigLoader.LoadResult result =
                DimensionConfigLoader.loadAllWithSettings(config, overlay);
        assertEquals("adventure", result.settings().namespace);
        assertEquals(7, result.settings().idleUnloadMinutes);

        Map<String, DimensionConfig> dims = result.dimensions();
        // a: pure defaults
        assertEquals(4096, dims.get("a").getPlayerBorderRadius());
        assertEquals(1.0, dims.get("a").getDifficulty().getMobMultiplier());
        // b: own values win, unset default (generation) still applies
        assertEquals(1024, dims.get("b").getPlayerBorderRadius());
        assertEquals(4096, dims.get("b").getGenerationBorderRadius());
        assertEquals(2.0, dims.get("b").getDifficulty().getMobMultiplier());
        // c: default frameBlock only fills in where a portal is declared
        assertEquals("minecraft:crying_obsidian", dims.get("c").getPortal().getFrameBlockId());
        assertFalse(dims.get("a").hasPortal());
    }

    @Test
    void defaultFrameBlockSkipsFrameMaterialsDimensions(@TempDir Path config, @TempDir Path overlay) throws IOException {
        Files.createDirectories(config);
        Files.writeString(config.resolve("settings.json"), """
                {"namespace":"adventure","defaults":{"frameBlock":"minecraft:crying_obsidian"}}
                """);
        writeDim(config, "parts", """
                {"type":"overworld","portal":{"igniterItem":"minecraft:stick",
                 "frameMaterials":{"sides":"#minecraft:logs","bottom":"minecraft:stone"}}}
                """);
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadAllWithSettings(config, overlay).dimensions();
        // The dimension owns its whole frame: no injected frameBlock, no
        // spurious exclusivity warning, per-part forms intact.
        assertNull(dims.get("parts").getPortal().frameBlock);
        assertEquals(java.util.List.of("#minecraft:logs"),
                dims.get("parts").getPortal().getFramePartAcceptForms().get("sides"));
        assertTrue(PortalSafetyValidator.validate(dims.values()).isEmpty());
    }

    @Test
    void loadSettingsReadsFramesBlock(@TempDir Path config) throws IOException {
        Files.writeString(config.resolve("settings.json"), """
                {"namespace":"elfydd","frames":{"overworld":"minecraft:gold_block","nether":"minecraft:obsidian"}}
                """);
        DimensionConfigLoader.Settings s = DimensionConfigLoader.loadSettings(config.resolve("settings.json"));
        assertEquals("elfydd", s.namespace);
        assertEquals("minecraft:gold_block", s.frameOverworld);
        assertEquals("minecraft:obsidian", s.frameNether);
        assertEquals("minecraft:end_stone_bricks", s.frameEnd);
    }

    // --- legacy conversion ----------------------------------------------------
}
