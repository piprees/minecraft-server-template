package com.customdimensions.dimension;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter between the game's hypercubes, this repo's measured windows and
 * {@link WindowProjection}.
 *
 * <p>{@code MultiNoiseUtil} needs no game bootstrap — a hypercube is a record
 * of fixed-point longs — so the conversion is exercised for real here rather
 * than modelled.
 */
class ProjectedSourceTest {

    private static MultiNoiseUtil.ParameterRange range(double lo, double hi) {
        return MultiNoiseUtil.ParameterRange.of((float) lo, (float) hi);
    }

    private static final MultiNoiseUtil.ParameterRange OPEN = range(-2.0, 2.0);

    private static MultiNoiseUtil.NoiseHypercube cube(double tLo, double tHi,
                                                      MultiNoiseUtil.ParameterRange depth,
                                                      double offset) {
        return MultiNoiseUtil.createNoiseHypercube(
                range(tLo, tHi), OPEN, OPEN, OPEN, depth, OPEN, (float) offset);
    }

    // ------------------------------------------------------------ conversion

    @Test
    void aFullSpanAxisReadsAsUnconstrained() {
        // A hypercube always states all six axes; -2..2 is how "no constraint"
        // is written. Carrying it through as a constraint would put every cell
        // in the source extent of every axis and flatten the map.
        WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, String>> cell =
                ProjectedSource.toCell(Pair.of(cube(-0.5, 0.5, OPEN, 0.0), "a"));

        assertNotNull(cell.axes().get(0), "temperature is constrained");
        assertNull(cell.axes().get(1), "humidity spans everything, so it is open");
        assertNull(cell.axes().get(5), "weirdness spans everything, so it is open");
    }

    @Test
    void depthIsNeverOfferedForProjection() {
        // depth is linear in y, not a noise field the router narrows, so its
        // authored range already means here what it meant where it was written.
        WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, String>> cell =
                ProjectedSource.toCell(Pair.of(cube(-0.5, 0.5, range(0.2, 0.9), 0.0), "a"));

        assertNull(cell.axes().get(ProjectedSource.DEPTH));
    }

    @Test
    void depthSurvivesTheRoundTripUntouched() {
        MultiNoiseUtil.NoiseHypercube original = cube(-0.5, 0.5, range(0.2, 0.9), 0.0);
        WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, String>> cell =
                ProjectedSource.toCell(Pair.of(original, "a"));

        MultiNoiseUtil.NoiseHypercube back = ProjectedSource.toHypercube(cell);

        assertEquals(original.depth(), back.depth());
    }

    @Test
    void aRoundTripWithNoProjectionChangesNothing() {
        MultiNoiseUtil.NoiseHypercube original = cube(-0.5, 0.5, range(0.2, 0.9), 0.375);
        MultiNoiseUtil.NoiseHypercube back = ProjectedSource.toHypercube(
                ProjectedSource.toCell(Pair.of(original, "a")));

        assertEquals(original, back, "conversion must be lossless in both directions");
    }

    @Test
    void aProjectedOffsetIsNeverAboveWhatVanillaWillEncode() {
        // The third route to the offset vanilla's codec refuses, after the
        // authored and default band paths. A declared 0.6 in a dimension whose
        // projection factor is 2.0 is 1.2, and the failure is silent all the
        // way to a level.dat with no WorldGenSettings.
        WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, String>> scaled =
                new WindowProjection.Cell<>(
                        Pair.of(cube(-0.5, 0.5, OPEN, 0.6), "a"),
                        ProjectedSource.toCell(Pair.of(cube(-0.5, 0.5, OPEN, 0.6), "a")).axes(),
                        1.2);

        assertEquals(10000L, ProjectedSource.toHypercube(scaled).offset(),
                "offset 1.2 is outside [0,1] and cannot be persisted");
    }

    @Test
    void theClampDoesNotTouchAProjectedOffsetThatFitsAlready() {
        // A ceiling, not a rescale: vanilla's heaviest authored offset at the
        // pack's largest measured projection factor is 0.375 x 1.978 = 0.742,
        // and it must cross untouched.
        WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, String>> scaled =
                new WindowProjection.Cell<>(
                        Pair.of(cube(-0.5, 0.5, OPEN, 0.375), "a"),
                        ProjectedSource.toCell(Pair.of(cube(-0.5, 0.5, OPEN, 0.375), "a")).axes(),
                        0.375 * 1.978);

        assertEquals(7418L, ProjectedSource.toHypercube(scaled).offset());
    }

    @Test
    void theOffsetCrossesTheFixedPointBoundaryIntact() {
        // A hypercube stores offset as v * 10000; the projection works in the
        // plain value. Getting the scale wrong here would be invisible until a
        // BetterNether biome stopped placing.
        MultiNoiseUtil.NoiseHypercube original = cube(-0.5, 0.5, OPEN, 0.375);
        assertEquals(3750L, original.offset());
        assertEquals(0.375,
                ProjectedSource.toCell(Pair.of(original, "a")).offset(), 1e-9);
    }

    // --------------------------------------------------------------- windows

    private static final String DOC = """
            {"perDimension": {
              "the_test": {"grid": 41, "axes": {
                 "temp":  {"span": 0.4, "distinct": 300, "min": -0.3, "max": 0.1},
                 "humid": {"span": 1.0, "distinct": 400, "min": -0.5, "max": 0.5},
                 "cont":  {"span": 1.0, "distinct": 1,   "min": -0.5, "max": 0.5},
                 "eros":  {"span": 1.0, "distinct": 200, "min": -0.5, "max": 0.5},
                 "weird": {"span": 2.0, "distinct": 500, "min": -1.0, "max": 1.0},
                 "depth": {"span": 1.0, "distinct": 90,  "min": -0.5, "max": 0.5}}}}}
            """;

    @Test
    void windowsAreReadInHypercubeAxisOrder() {
        Map<String, List<WindowProjection.Window>> w =
                ProjectedSource.parse(JsonParser.parseString(DOC).getAsJsonObject());

        List<WindowProjection.Window> row = w.get("the_test");
        assertEquals(WindowProjection.AXES, row.size());
        assertEquals(-0.3, row.get(0).lo(), 1e-9, "index 0 is temperature");
        assertEquals(0.5, row.get(1).hi(), 1e-9, "index 1 is humidity");
        assertEquals(500, row.get(5).distinct(), "index 5 is weirdness, not depth");
    }

    @Test
    void theDepthSlotIsLeftEmptyEvenThoughTheFileMeasuresIt() {
        Map<String, List<WindowProjection.Window>> w =
                ProjectedSource.parse(JsonParser.parseString(DOC).getAsJsonObject());

        assertNull(w.get("the_test").get(ProjectedSource.DEPTH),
                "the file measures depth at one height; projecting on it would move bands");
    }

    @Test
    void aDocumentWithNoPerDimensionBlockYieldsNothing() {
        assertTrue(ProjectedSource.parse(new JsonObject()).isEmpty());
    }

    @Test
    void aRejectedAxisLosesItsConstraintRatherThanKeepingIt() {
        // The projection nulls an axis it rejects. Reading that null as "keep
        // the original" would put the constraint back and undo the collapse
        // filter — the axis would go on deciding placement while carrying no
        // information. cont has one distinct value in DOC, so it is rejected.
        ProjectedSource.parse(JsonParser.parseString(DOC).getAsJsonObject());
        MultiNoiseUtil.NoiseHypercube constrained = MultiNoiseUtil.createNoiseHypercube(
                range(-0.2, 0.0), OPEN, range(-0.4, -0.1), OPEN, OPEN, OPEN, 0.0f);
        WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, String>> cell =
                ProjectedSource.toCell(Pair.of(constrained, "a"));
        WindowProjection.Result<Pair<MultiNoiseUtil.NoiseHypercube, String>> projected =
                WindowProjection.project(List.of(cell),
                        ProjectedSource.parse(JsonParser.parseString(DOC).getAsJsonObject())
                                .get("the_test"), ProjectedSource.MIN_DISTINCT);

        MultiNoiseUtil.NoiseHypercube back =
                ProjectedSource.toHypercube(projected.cells().get(0));
        assertTrue(projected.rejectedAxes().contains(2), "cont has one distinct value");
        assertEquals(-20000L, back.continentalness().min(), "the constraint must be gone");
        assertEquals(20000L, back.continentalness().max(), "the constraint must be gone");
    }

    @Test
    void depthComesFromTheOriginalEvenWhenTheCellCarriesOne() {
        // Nothing populates the depth slot today, but if anything ever did,
        // depth must still be the authored range rather than a projected one.
        MultiNoiseUtil.NoiseHypercube original = cube(-0.5, 0.5, range(0.2, 0.9), 0.0);
        WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, String>> tampered =
                new WindowProjection.Cell<>(Pair.of(original, "a"),
                        new java.util.ArrayList<>(java.util.Arrays.asList(
                                new WindowProjection.Span(-0.1, 0.1), null, null, null,
                                new WindowProjection.Span(-1.9, -1.8), null)), 0.0);

        MultiNoiseUtil.NoiseHypercube back = ProjectedSource.toHypercube(tampered);

        assertEquals(original.depth(), back.depth());
    }

    @Test
    void anUnmeasuredDimensionIsReturnedUntouched() {
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> declared =
                List.of(Pair.of(cube(-0.5, 0.5, OPEN, 0.0), "a"));

        assertEquals(declared, ProjectedSource.project(declared, "nothing_measured_here").cells());
    }

    /**
     * A refusal leaves every declared offset as authored, so the factor it
     * reports has to be the one applied and not the one computable — a caller
     * sizing a band against these cells would otherwise scale against nothing.
     */
    @Test
    void anUnmeasuredDimensionAppliesNoFactor() {
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> declared =
                List.of(Pair.of(cube(-0.5, 0.5, OPEN, 0.0), "a"));

        assertEquals(1.0, ProjectedSource.project(declared, "nothing_measured_here").appliedFactor());
    }

    @Test
    void anEmptyPaletteAppliesNoFactor() {
        assertEquals(1.0, ProjectedSource.project(
                List.<Pair<MultiNoiseUtil.NoiseHypercube, String>>of(), "anything").appliedFactor());
    }

    // ------------------------------------------------- depth out of schema

    /**
     * Sampled depth from the ten End-settings dimensions that carry no
     * {@code noiseSettings} override; every declared cell sits in ±2, so the
     * axis ranks by declared bound alone and places nothing ([T76]).
     */
    private static final String OUT_OF_SCHEMA_DOC = """
            {"perDimension": {
              "the_blighted_maw": {"grid": 41, "axes": {
                 "temp":  {"span": 0.3, "distinct": 255, "min": -0.23, "max": 0.07},
                 "humid": {"span": 0.6, "distinct": 545, "min": -0.49, "max": 0.13},
                 "cont":  {"span": 1.7, "distinct": 862, "min": -0.98, "max": 0.77},
                 "eros":  {"span": 0.7, "distinct": 602, "min": -0.35, "max": 0.35},
                 "weird": {"span": 1.0, "distinct": 500, "min": -0.5,  "max": 0.5},
                 "depth": {"span": 40.0, "distinct": 929, "min": 40.0, "max": 80.0}}},
              "the_catalyst_maw": {"grid": 41, "axes": {
                 "temp":  {"span": 0.3, "distinct": 255, "min": -0.23, "max": 0.07},
                 "humid": {"span": 0.6, "distinct": 545, "min": -0.49, "max": 0.13},
                 "cont":  {"span": 1.7, "distinct": 862, "min": -0.98, "max": 0.77},
                 "eros":  {"span": 0.7, "distinct": 602, "min": -0.35, "max": 0.35},
                 "weird": {"span": 1.0, "distinct": 500, "min": -0.5,  "max": 0.5},
                 "depth": {"span": 1.254, "distinct": 587, "min": 0.046, "max": 1.3}}}}}
            """;

    @Test
    void aDepthWindowDisjointFromTheSchemaPlacesNothing() {
        assertTrue(ProjectedSource.depthCarriesNoInformation(
                new WindowProjection.Window(40.0, 80.0, 929)),
                "no declared cell can reach 40..80, so every one is on the same side");
    }

    @Test
    void aDepthWindowInsideTheSchemaIsLeftAlone() {
        assertFalse(ProjectedSource.depthCarriesNoInformation(
                new WindowProjection.Window(0.046, 1.3, 587)),
                "depth still separates cells by position here");
    }

    @Test
    void aDepthWindowOverrunningTheSchemaSlightlyIsLeftAlone() {
        // the_amplified_reaches reads 0.264..2.155. It overlaps the schema, so
        // the axis still ranks by position and opening it would drop real signal.
        assertFalse(ProjectedSource.depthCarriesNoInformation(
                new WindowProjection.Window(0.264, 2.155, 551)));
    }

    @Test
    void anUnmeasuredDepthWindowIsLeftAlone() {
        assertFalse(ProjectedSource.depthCarriesNoInformation(null));
    }

    @Test
    void anOutOfSchemaDimensionHasDepthOpenedOnEveryCell() {
        ProjectedSource.apply(JsonParser.parseString(OUT_OF_SCHEMA_DOC).getAsJsonObject());
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> declared = List.of(
                Pair.of(cube(-0.2, -0.1, range(0.25, 0.25), 0.15), "infernal_dunes"),
                Pair.of(cube(-0.1, 0.0, range(0.188, 0.188), 0.0), "wart_forest"),
                Pair.of(cube(0.0, 0.05, range(-0.5, -0.5), 0.26), "toxic_heap"));

        for (Pair<MultiNoiseUtil.NoiseHypercube, String> cell
                : ProjectedSource.project(declared, "the_blighted_maw").cells()) {
            assertEquals(-20000L, cell.getFirst().depth().min(), cell.getSecond());
            assertEquals(20000L, cell.getFirst().depth().max(), cell.getSecond());
        }
    }

    @Test
    void anInSchemaDimensionKeepsEveryAuthoredDepth() {
        // the_catalyst_maw is the control: it carries a noiseSettings override,
        // reads 0.046..1.3, and must come through this change unmoved.
        ProjectedSource.apply(JsonParser.parseString(OUT_OF_SCHEMA_DOC).getAsJsonObject());
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> declared = List.of(
                Pair.of(cube(-0.2, -0.1, range(0.25, 0.25), 0.15), "a"),
                Pair.of(cube(-0.1, 0.0, range(-0.5, -0.5), 0.0), "b"));

        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> out =
                ProjectedSource.project(declared, "the_catalyst_maw").cells();

        assertEquals(2500L, out.get(0).getFirst().depth().min(), "authored depth kept");
        assertEquals(2500L, out.get(0).getFirst().depth().max(), "authored depth kept");
        assertEquals(-5000L, out.get(1).getFirst().depth().min(), "authored depth kept");
    }

    /**
     * The defect itself, as arithmetic on the real cells.
     *
     * <p>{@code incendium:infernal_dunes} declares the highest depth bound of
     * the forty cells in {@code the_blighted_maw}. Against a sample of 70 it
     * beats the next cell by {@code 0.062 x 2 x 70 = 8.65} in squared distance,
     * which no combination of the five axes that do vary can overturn — so it
     * takes every column. Opening depth is what lets them decide instead.
     */
    @Test
    void openingDepthStopsTheHighestDeclaredCellTakingEveryColumn() {
        ProjectedSource.apply(JsonParser.parseString(OUT_OF_SCHEMA_DOC).getAsJsonObject());
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> declared = List.of(
                Pair.of(cube(-0.23, -0.20, range(0.25, 0.25), 0.0), "infernal_dunes"),
                Pair.of(cube(0.04, 0.07, range(0.188, 0.188), 0.0), "wart_forest"));

        // A sample sitting squarely in wart_forest's temperature band, at the
        // depth the router actually produces there.
        long[] sample = {600L, 0L, 0L, 0L, 700000L, 0L};

        assertEquals("infernal_dunes", nearest(declared, sample),
                "unfixed: the max-depth cell wins even inside another cell's band");
        assertEquals("wart_forest",
                nearest(ProjectedSource.project(declared, "the_blighted_maw").cells(), sample),
                "fixed: the five axes that vary decide it");
    }

    /** Vanilla's squared distance-to-range over six axes, plus offset squared. */
    private static String nearest(List<Pair<MultiNoiseUtil.NoiseHypercube, String>> cells,
                                  long[] point) {
        String best = null;
        long bestD = Long.MAX_VALUE;
        for (Pair<MultiNoiseUtil.NoiseHypercube, String> cell : cells) {
            MultiNoiseUtil.NoiseHypercube h = cell.getFirst();
            List<MultiNoiseUtil.ParameterRange> axes = List.of(
                    h.temperature(), h.humidity(), h.continentalness(),
                    h.erosion(), h.depth(), h.weirdness());
            long total = h.offset() * h.offset();
            for (int i = 0; i < axes.size(); i++) {
                long d = axes.get(i).getDistance(point[i]);
                total += d * d;
            }
            if (total < bestD) {
                bestD = total;
                best = cell.getSecond();
            }
        }
        return best;
    }
}
