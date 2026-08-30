package com.customdimensions.dimension;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals(declared, ProjectedSource.project(declared, "nothing_measured_here"));
    }
}
