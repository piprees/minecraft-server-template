package com.customdimensions.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure merging/precedence logic in BiomeParamCollector.
 *
 * <p>Covers:
 * <ul>
 *   <li>Static-only biomes are resolved, not duplicated</li>
 *   <li>TB-only biomes are resolved via tbEntries</li>
 *   <li>TB entries for biomes already in static are filtered out</li>
 *   <li>Biomes in the source but in neither set are unresolved</li>
 *   <li>Unresolved list is sorted</li>
 *   <li>Empty inputs produce empty outputs</li>
 * </ul>
 *
 * Bootstrap-free: no Minecraft types.
 */
class BiomeParamCollectorTest {

    private static BiomeParamCollector.Entry entry(String biome) {
        return new BiomeParamCollector.Entry(biome,
                -1.0, 1.0, -1.0, 1.0, -1.0, 1.0,
                -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, 0.0);
    }

    @Test
    void staticOnlyBiomesAreResolved() {
        var result = BiomeParamCollector.merge(
                List.of(entry("minecraft:plains"), entry("minecraft:forest")),
                List.of(),
                Set.of("minecraft:plains", "minecraft:forest"));
        assertEquals(2, result.staticEntries().size());
        assertTrue(result.tbEntries().isEmpty());
        assertTrue(result.unresolved().isEmpty());
        assertEquals(Set.of("minecraft:plains", "minecraft:forest"),
                result.resolvedBiomes());
    }

    @Test
    void tbOnlyBiomesAreResolved() {
        var result = BiomeParamCollector.merge(
                List.of(),
                List.of(entry("natures_spirit:cypress_wetlands")),
                Set.of("natures_spirit:cypress_wetlands"));
        assertTrue(result.staticEntries().isEmpty());
        assertEquals(1, result.tbEntries().size());
        assertEquals("natures_spirit:cypress_wetlands",
                result.tbEntries().get(0).biomeId());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    void tbEntriesForStaticBiomesAreFiltered() {
        var result = BiomeParamCollector.merge(
                List.of(entry("minecraft:plains")),
                List.of(entry("minecraft:plains"), entry("natures_spirit:hot_springs")),
                Set.of("minecraft:plains", "natures_spirit:hot_springs"));
        assertEquals(1, result.staticEntries().size());
        assertEquals(1, result.tbEntries().size());
        assertEquals("natures_spirit:hot_springs",
                result.tbEntries().get(0).biomeId());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    void unresolvedBiomesDetected() {
        var result = BiomeParamCollector.merge(
                List.of(entry("minecraft:plains")),
                List.of(entry("natures_spirit:hot_springs")),
                Set.of("minecraft:plains", "natures_spirit:hot_springs",
                        "some_mod:mystery_biome"));
        assertEquals(1, result.unresolved().size());
        assertEquals("some_mod:mystery_biome",
                result.unresolved().get(0).biomeId());
    }

    @Test
    void unresolvedListIsSorted() {
        var result = BiomeParamCollector.merge(
                List.of(),
                List.of(),
                Set.of("z_mod:zebra", "a_mod:alpha", "m_mod:middle"));
        assertEquals(3, result.unresolved().size());
        assertEquals("a_mod:alpha", result.unresolved().get(0).biomeId());
        assertEquals("m_mod:middle", result.unresolved().get(1).biomeId());
        assertEquals("z_mod:zebra", result.unresolved().get(2).biomeId());
    }

    @Test
    void emptyInputsProduceEmptyOutputs() {
        var result = BiomeParamCollector.merge(
                List.of(), List.of(), Set.of());
        assertTrue(result.staticEntries().isEmpty());
        assertTrue(result.tbEntries().isEmpty());
        assertTrue(result.unresolved().isEmpty());
        assertTrue(result.resolvedBiomes().isEmpty());
    }

    @Test
    void multipleTbEntriesForSameBiomeAllKept() {
        var result = BiomeParamCollector.merge(
                List.of(),
                List.of(
                        new BiomeParamCollector.Entry("mod:biome_a",
                                -0.5, 0.0, -1.0, 1.0, -1.0, 1.0,
                                -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, 0.0),
                        new BiomeParamCollector.Entry("mod:biome_a",
                                0.0, 0.5, -1.0, 1.0, -1.0, 1.0,
                                -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, 0.0)),
                Set.of("mod:biome_a"));
        assertEquals(2, result.tbEntries().size());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    void resolvedBiomesSpansBothSources() {
        var result = BiomeParamCollector.merge(
                List.of(entry("minecraft:desert")),
                List.of(entry("natures_spirit:maple_forest")),
                Set.of("minecraft:desert", "natures_spirit:maple_forest",
                        "unknown:biome"));
        assertEquals(Set.of("minecraft:desert", "natures_spirit:maple_forest"),
                result.resolvedBiomes());
        assertEquals(1, result.unresolved().size());
    }

    @Test
    void allBiomesResolvedProducesNoUnresolved() {
        var result = BiomeParamCollector.merge(
                List.of(entry("minecraft:plains")),
                List.of(entry("mod:custom")),
                Set.of("minecraft:plains", "mod:custom"));
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    void biomeInAllButNotInSourceStillUnresolved() {
        // allBiomeIds is what the biome source claims — if a biome is in
        // that set but has no entries, it is unresolved regardless of its
        // origin.
        var result = BiomeParamCollector.merge(
                List.of(),
                List.of(),
                Set.of("orphan:biome"));
        assertEquals(1, result.unresolved().size());
        assertEquals("orphan:biome", result.unresolved().get(0).biomeId());
    }

    @Test
    void emptyTbWithStaticOnlyMatchesEndDimensionScenario() {
        // End-family dimensions: TB has no END region type, so TB
        // extraction returns empty. Biomes from a Nullscape-modded end
        // MNBS appear in static entries (datapack JSON). Any biome the
        // source claims but static doesn't cover is unresolved.
        var result = BiomeParamCollector.merge(
                List.of(entry("nullscape:void_barrens"),
                        entry("nullscape:null_end"),
                        entry("minecraft:the_end")),
                List.of(),
                Set.of("nullscape:void_barrens", "nullscape:null_end",
                        "minecraft:the_end", "nullscape:unconfigured_biome"));
        assertEquals(3, result.staticEntries().size());
        assertTrue(result.tbEntries().isEmpty());
        assertEquals(1, result.unresolved().size());
        assertEquals("nullscape:unconfigured_biome",
                result.unresolved().get(0).biomeId());
    }

    @Test
    void netherStaticWithEmptyTbMatchesIncendiumScenario() {
        // Nether-family dimensions: Incendium ships inline MNBS entries
        // in its dimension override (Phase 1 static). No nether TB
        // regions are registered in this mod set, so TB returns empty.
        var result = BiomeParamCollector.merge(
                List.of(entry("incendium:volcanic_deltas"),
                        entry("incendium:quartz_flats"),
                        entry("minecraft:soul_sand_valley")),
                List.of(),
                Set.of("incendium:volcanic_deltas", "incendium:quartz_flats",
                        "minecraft:soul_sand_valley"));
        assertEquals(3, result.staticEntries().size());
        assertTrue(result.tbEntries().isEmpty());
        assertTrue(result.unresolved().isEmpty());
    }
}
