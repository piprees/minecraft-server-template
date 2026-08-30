package com.customdimensions.dimension;

import com.mojang.datafixers.util.Pair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placement rules for the biomes a dimension's base source does not place:
 * {@link DimensionManager#dealRemaining}.
 *
 * <p>Driven with strings rather than biomes and hypercubes: resolving either
 * initialises {@code Registries}, and this suite cannot bootstrap Minecraft.
 * Where the declared cells come from is TerraBlender's region API, covered by
 * the running server.
 */
class DealRemainingTest {

    private static final Map<String, List<String>> DECLARED = Map.of(
            "spirit:redwood", List.of("cellA", "cellB", "cellC"),
            "wild:cypress", List.of("cellD"));

    private static List<String> declaredFor(String biome) {
        return DECLARED.getOrDefault(biome, List.of());
    }

    @Test
    void aDeclaringBiomeTakesEveryCellItsModDeclared() {
        DimensionManager.Dealt<String, String> dealt = DimensionManager.dealRemaining(
                List.of("spirit:redwood"), DealRemainingTest::declaredFor, List.of("leftover"));

        assertEquals(List.of(
                Pair.of("cellA", "spirit:redwood"),
                Pair.of("cellB", "spirit:redwood"),
                Pair.of("cellC", "spirit:redwood")), dealt.natural());
    }

    @Test
    void aDeclaringBiomeIsNeverDealtALeftoverRegion() {
        DimensionManager.Dealt<String, String> dealt = DimensionManager.dealRemaining(
                List.of("spirit:redwood", "wild:cypress"),
                DealRemainingTest::declaredFor, List.of("p0", "p1", "p2"));

        assertEquals(List.of(), dealt.foreign());
        assertEquals(List.of(), dealt.filler());
    }

    @Test
    void theLeftoverPoolIsDroppedWhenEveryBiomeIsPlaced() {
        DimensionManager.Dealt<String, String> dealt = DimensionManager.dealRemaining(
                List.of("wild:cypress"), DealRemainingTest::declaredFor,
                List.of("p0", "p1", "p2", "p3"));

        // Dealing the pool here would put one biome everywhere the base source
        // did not want it, on top of the placement its own mod declared.
        assertTrue(dealt.filler().isEmpty());
    }

    @Test
    void aBiomeWithNoDeclaredCellsFallsThroughToTheLeftoverPool() {
        DimensionManager.Dealt<String, String> dealt = DimensionManager.dealRemaining(
                List.of("ru:silver_birch"), DealRemainingTest::declaredFor, List.of("p0", "p1"));

        assertEquals(List.of("ru:silver_birch"), dealt.foreign());
        assertEquals(List.of(), dealt.natural());
        assertEquals(List.of(
                Pair.of("p0", "ru:silver_birch"),
                Pair.of("p1", "ru:silver_birch")), dealt.filler());
    }

    @Test
    void theLeftoverPoolIsDealtRoundRobinAcrossOnlyTheUndeclaredBiomes() {
        DimensionManager.Dealt<String, String> dealt = DimensionManager.dealRemaining(
                List.of("ru:silver_birch", "spirit:redwood", "ru:pine_slopes"),
                DealRemainingTest::declaredFor, List.of("p0", "p1", "p2", "p3", "p4"));

        assertEquals(List.of("ru:silver_birch", "ru:pine_slopes"), dealt.foreign());
        assertEquals(List.of(
                Pair.of("p0", "ru:silver_birch"),
                Pair.of("p1", "ru:pine_slopes"),
                Pair.of("p2", "ru:silver_birch"),
                Pair.of("p3", "ru:pine_slopes"),
                Pair.of("p4", "ru:silver_birch")), dealt.filler());
        assertEquals(3, dealt.natural().size());
    }

    @Test
    void aNullCellListReadsAsNoDeclaration() {
        DimensionManager.Dealt<String, String> dealt = DimensionManager.dealRemaining(
                List.of("galosphere:lichen_caves"), biome -> null, List.of("p0"));

        assertEquals(List.of("galosphere:lichen_caves"), dealt.foreign());
        assertEquals(List.of(Pair.of("p0", "galosphere:lichen_caves")), dealt.filler());
    }

    @Test
    void nothingIsPlacedWhenNothingIsLeftUnplaced() {
        DimensionManager.Dealt<String, String> dealt = DimensionManager.dealRemaining(
                List.of(), DealRemainingTest::declaredFor, List.of("p0", "p1"));

        assertEquals(List.of(), dealt.natural());
        assertEquals(List.of(), dealt.filler());
        assertEquals(List.of(), dealt.foreign());
    }
}
