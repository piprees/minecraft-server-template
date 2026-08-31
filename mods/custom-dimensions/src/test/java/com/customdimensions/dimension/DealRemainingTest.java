package com.customdimensions.dimension;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.junit.jupiter.api.DisplayName;
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

    private static final MultiNoiseUtil.ParameterRange FULL =
            MultiNoiseUtil.ParameterRange.of(-2.0f, 2.0f);

    /** A declared cell shaped like the nether's: a point on every axis. */
    private static MultiNoiseUtil.NoiseHypercube at(float t, float h, float c) {
        return MultiNoiseUtil.createNoiseHypercube(
                MultiNoiseUtil.ParameterRange.of(t), MultiNoiseUtil.ParameterRange.of(h),
                MultiNoiseUtil.ParameterRange.of(c), MultiNoiseUtil.ParameterRange.of(0.0f),
                MultiNoiseUtil.ParameterRange.of(0.0f), MultiNoiseUtil.ParameterRange.of(0.0f), 0.0f);
    }

    /** The nether's shape: thirteen points clustered near the middle. */
    private static List<MultiNoiseUtil.NoiseHypercube> clusteredPoints() {
        List<MultiNoiseUtil.NoiseHypercube> claimed = new java.util.ArrayList<>();
        for (int i = 0; i < 13; i++) {
            claimed.add(at(-0.3f + 0.05f * i, 0.25f - 0.04f * i, -0.2f + 0.03f * i));
        }
        return claimed;
    }

    @Test
    @DisplayName("a biome that declares no cell anywhere still gets one")
    void everyUndeclaredBiomeGetsACell() {
        // The nether: 40 wanted, 13 with cells, nothing given up.
        assertEquals(27, DimensionManager.synthesiseFillerCells(27, clusteredPoints()).size());
        assertEquals(27, DimensionManager.synthesiseFillerCells(27, List.of()).size());
        assertEquals(0, DimensionManager.synthesiseFillerCells(0, List.of()).size());
        assertEquals(0, DimensionManager.synthesiseFillerCells(-1, List.of()).size());
    }

    @Test
    @DisplayName("filler never spans an axis the declared cells constrain")
    void fillerIsConfinedToTheDeclaredHull() {
        // The measured failure: filler spanning continentalness, erosion,
        // depth and weirdness paid nothing on four axes where every declared
        // cell paid, and took 93% of the nether and 96% of the End.
        MultiNoiseUtil.NoiseHypercube a = MultiNoiseUtil.createNoiseHypercube(
                MultiNoiseUtil.ParameterRange.of(-0.5f), MultiNoiseUtil.ParameterRange.of(0.0f),
                MultiNoiseUtil.ParameterRange.of(-0.55f), MultiNoiseUtil.ParameterRange.of(0.5f),
                MultiNoiseUtil.ParameterRange.of(0.0f), MultiNoiseUtil.ParameterRange.of(0.0f), 0.2f);
        MultiNoiseUtil.NoiseHypercube b = MultiNoiseUtil.createNoiseHypercube(
                MultiNoiseUtil.ParameterRange.of(0.4f), MultiNoiseUtil.ParameterRange.of(-0.25f),
                MultiNoiseUtil.ParameterRange.of(0.335f), MultiNoiseUtil.ParameterRange.of(-0.3f),
                MultiNoiseUtil.ParameterRange.of(0.25f), MultiNoiseUtil.ParameterRange.of(0.0f), 0.15f);
        List<MultiNoiseUtil.NoiseHypercube> claimed = List.of(a, b);

        for (var cell : DimensionManager.synthesiseFillerCells(9, claimed)) {
            for (int axis = 0; axis < DimensionManager.AXES; axis++) {
                long lo = Math.min(DimensionManager.axisOf(a, axis).min(),
                        DimensionManager.axisOf(b, axis).min());
                long hi = Math.max(DimensionManager.axisOf(a, axis).max(),
                        DimensionManager.axisOf(b, axis).max());
                var range = DimensionManager.axisOf(cell, axis);
                assertTrue(range.min() >= lo && range.max() <= hi,
                        "axis " + axis + " escaped the declared hull");
            }
        }
    }

    @Test
    @DisplayName("filler cells are points, and each is its own place")
    void fillerCellsArePoints() {
        var cells = DimensionManager.synthesiseFillerCells(9, clusteredPoints());
        assertEquals(9, cells.size());
        for (var cell : cells) {
            // A cell with width beats a point anywhere it reaches, which is
            // what makes one filler biome swallow a dimension.
            assertEquals(cell.temperature().min(), cell.temperature().max(),
                    "temperature must be a point, not a window");
            assertEquals(cell.humidity().min(), cell.humidity().max(),
                    "humidity must be a point, not a window");
            assertEquals(0L, cell.offset(), "the filler floor is stamped downstream");
        }
        assertEquals(cells.size(), cells.stream().distinct().count(),
                "each biome needs its own point, or they collide");
    }

    @Test
    @DisplayName("an axis every declared cell agrees on is inherited, not spread over")
    void aSharedAxisIsInherited() {
        // Every nether cell sits at weirdness 0; filler that wandered off it
        // would be paying a cost none of them pay, or dodging one they do.
        var cells = DimensionManager.synthesiseFillerCells(9, clusteredPoints());
        for (var cell : cells) {
            assertEquals(0L, cell.weirdness().min());
            assertEquals(0L, cell.weirdness().max());
        }
    }

    @Test
    @DisplayName("an axis the declared cells already span end to end is left whole")
    void aSchemaWideAxisStaysWhole() {
        // The End: its declared cells cover depth from -2 to 2 between them,
        // so no position on that axis is out of the family's reach.
        var claimed = List.of(
                MultiNoiseUtil.createNoiseHypercube(
                        MultiNoiseUtil.ParameterRange.of(-1.0f), MultiNoiseUtil.ParameterRange.of(-1.0f),
                        FULL, FULL, MultiNoiseUtil.ParameterRange.of(1.9f, 2.0f), FULL, 0.75f),
                MultiNoiseUtil.createNoiseHypercube(
                        MultiNoiseUtil.ParameterRange.of(0.0f), MultiNoiseUtil.ParameterRange.of(-1.0f),
                        FULL, FULL, MultiNoiseUtil.ParameterRange.of(-2.0f), FULL, 1.0f));
        for (var cell : DimensionManager.synthesiseFillerCells(6, claimed)) {
            assertEquals(-20000L, cell.depth().min());
            assertEquals(20000L, cell.depth().max());
        }
    }

    @Test
    @DisplayName("with nothing to read, filler spreads over the live climate square")
    void nothingDeclaredSpreadsOverTemperatureAndHumidity() {
        var cells = DimensionManager.synthesiseFillerCells(9, List.of());
        assertEquals(9, cells.size());
        for (var cell : cells) {
            assertTrue(cell.temperature().min() >= -10000L && cell.temperature().max() <= 10000L,
                    "a filler point must stay inside the range the router produces");
            assertEquals(-20000L, cell.continentalness().min(),
                    "nothing declared constrains continentalness, so nothing here does");
        }
        assertEquals(cells.size(), cells.stream().distinct().count());
    }
}
