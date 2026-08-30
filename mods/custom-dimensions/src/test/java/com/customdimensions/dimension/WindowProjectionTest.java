package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapping declared cells into the window a dimension samples:
 * {@link WindowProjection}.
 *
 * <p>Driven with plain spans and string payloads rather than hypercubes and
 * biomes: resolving either initialises {@code Registries}, and this suite
 * cannot bootstrap Minecraft. What is asked here is the arithmetic and the
 * refusals, which is all of it — converting to and from a hypercube belongs to
 * the caller and is not covered.
 */
class WindowProjectionTest {

    private static final double EPS = 1e-9;

    /** A cell constraining the axes named, unconstrained everywhere else. */
    private static WindowProjection.Cell<String> cell(String id, double offset,
                                                      WindowProjection.Span... axes) {
        List<WindowProjection.Span> list = new ArrayList<>(Arrays.asList(axes));
        while (list.size() < WindowProjection.AXES) {
            list.add(null);
        }
        return new WindowProjection.Cell<>(id, list, offset);
    }

    private static WindowProjection.Span span(double lo, double hi) {
        return new WindowProjection.Span(lo, hi);
    }

    /** Windows for the leading axes; the rest are absent and so never live. */
    private static List<WindowProjection.Window> windows(WindowProjection.Window... w) {
        List<WindowProjection.Window> list = new ArrayList<>(Arrays.asList(w));
        while (list.size() < WindowProjection.AXES) {
            list.add(null);
        }
        return list;
    }

    private static WindowProjection.Span axis(WindowProjection.Result<String> r, int i, int a) {
        return r.cells().get(i).axes().get(a);
    }

    // ---------------------------------------------------------------- source

    @Test
    void theSourceRangeIsThePalettesOwnExtent() {
        // Mapping from the schema's [-2, 2] would compress a palette that lives
        // in a corner of it into the middle of the window. The palette here
        // spans 0.1..0.3, so its extremes must reach the window's ends.
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("low", 0.0, span(0.1, 0.15)),
                        cell("high", 0.0, span(0.25, 0.3))),
                windows(new WindowProjection.Window(-0.9, 0.7, 90)), 2);

        assertEquals(-0.9, axis(r, 0, 0).lo(), EPS);
        assertEquals(0.7, axis(r, 1, 0).hi(), EPS);
    }

    @Test
    void relativePositionAndWidthSurviveTheMap() {
        // Affine and monotone: a cell twice as wide as its neighbour stays
        // twice as wide, and one sitting a third of the way along stays there.
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("a", 0.0, span(0.0, 0.1)),
                        cell("b", 0.0, span(0.3, 0.5)),
                        cell("c", 0.0, span(0.9, 1.0))),
                windows(new WindowProjection.Window(0.12, 0.9, 90)), 2);

        double wa = axis(r, 0, 0).hi() - axis(r, 0, 0).lo();
        double wb = axis(r, 1, 0).hi() - axis(r, 1, 0).lo();
        assertEquals(0, r.separations(), "this fixture must exercise the map alone");
        assertEquals(2.0, wb / wa, 1e-9, "b was twice a's width and must stay so");
        // b started 0.3 of the way along a 1.0 source and must land 0.3 along.
        assertEquals(0.12 + 0.3 * 0.78, axis(r, 1, 0).lo(), 1e-9);
        assertTrue(axis(r, 0, 0).hi() < axis(r, 1, 0).lo(), "ordering must survive");
        assertTrue(axis(r, 1, 0).hi() < axis(r, 2, 0).lo(), "ordering must survive");
    }

    @Test
    void separationCostsAtMostOneQuantumOfWidth() {
        // Relative width survives the MAP exactly; the separation pass then
        // spends up to one quantum per endpoint moving off a rail. That is
        // below the resolution any author writes at, and it is the price of
        // not shipping a tie — but it is a real departure and is asserted
        // rather than assumed.
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("a", 0.0, span(0.0, 0.1)),
                        cell("b", 0.0, span(0.3, 0.5)),
                        cell("c", 0.0, span(0.9, 1.0))),
                windows(new WindowProjection.Window(0.1, 0.9, 90)), 2);

        double wa = axis(r, 0, 0).hi() - axis(r, 0, 0).lo();
        double wb = axis(r, 1, 0).hi() - axis(r, 1, 0).lo();
        assertEquals(1, r.separations(), "b's high end lands on the 0.5 rail");
        assertEquals(0.16 - WindowProjection.QUANTUM, wb, 1e-9);
        assertTrue(Math.abs(wb / wa - 2.0) < 0.002, "and the distortion stays tiny");
    }

    @Test
    void anUnconstrainedAxisStaysUnconstrained() {
        // Mapping [-2, 2] onto the window would assert a constraint the author
        // did not make, and every entry is at distance 0 there either way.
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("a", 0.0, span(0.0, 1.0))),
                windows(new WindowProjection.Window(0.1, 0.9, 90),
                        new WindowProjection.Window(-0.3, 0.3, 90)), 2);

        assertNotNull(axis(r, 0, 0));
        assertNull(axis(r, 0, 1), "axis 1 was never constrained and must stay open");
    }

    @Test
    void aCellLeavesAnAxisOpenEvenWhereItsPeersConstrainIt() {
        // The case that matters: the axis IS live, because other cells place on
        // it, so there is a window to narrow this cell into and something to
        // narrow it to. Doing so would assert a constraint its author declined
        // to make, and it is inherent to nearest-point that the open cell is
        // the larger target — that is a difference the biomes' authors
        // expressed, not an artefact to correct.
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("narrow", 0.0, span(0.0, 0.4), span(0.1, 0.3)),
                        cell("open", 0.0, span(0.6, 1.0))),
                windows(new WindowProjection.Window(0.12, 0.88, 90),
                        new WindowProjection.Window(-0.42, 0.38, 90)), 2);

        assertFalse(r.rejectedAxes().contains(1), "axis 1 is live — a peer constrains it");
        assertNotNull(axis(r, 0, 1), "the peer that constrains it keeps its band");
        assertNull(axis(r, 1, 1), "the cell that declined the axis must stay open on it");
    }

    // ------------------------------------------------------------- refusals

    @Test
    void anAxisTheWorldBarelyVariesOnIsRejectedNotScaled() {
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("a", 0.0, span(0.0, 1.0), span(0.0, 1.0))),
                windows(new WindowProjection.Window(0.1, 0.9, 90),
                        new WindowProjection.Window(-0.3, 0.3, 1)), 5);

        assertTrue(r.rejectedAxes().contains(1));
        assertFalse(r.rejectedAxes().contains(0));
        assertNull(axis(r, 0, 1), "a rejected axis loses its constraint on every cell");
        assertNotNull(axis(r, 0, 0));
    }

    @Test
    void rejectionIsOnDistinctNeverOnSpan() {
        // A clamped axis wears a wide span ([T58]). Judging by width would keep
        // the dead axis and drop the live one, which is the wrong way round.
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("a", 0.0, span(0.0, 1.0), span(0.0, 1.0))),
                windows(new WindowProjection.Window(-1.0, 1.0, 1),      // wide, dead
                        new WindowProjection.Window(-0.02, 0.02, 90)),  // narrow, alive
                5);

        assertTrue(r.rejectedAxes().contains(0), "wide span with one distinct value is dead");
        assertFalse(r.rejectedAxes().contains(1), "a narrow span that varies is alive");
    }

    @Test
    void theThresholdIsTheCallersAndIsNotInventedHere() {
        List<WindowProjection.Cell<String>> palette =
                List.of(cell("a", 0.0, span(0.0, 1.0)));
        List<WindowProjection.Window> w =
                windows(new WindowProjection.Window(0.1, 0.9, 40));

        assertFalse(WindowProjection.project(palette, w, 40).rejectedAxes().contains(0));
        assertTrue(WindowProjection.project(palette, w, 41).rejectedAxes().contains(0));
    }

    // --------------------------------------------------------------- offset

    @Test
    void theOffsetScalesByTheRatioOfTheBoxDiagonals() {
        // Windows 1.0 and 3.0 wide over sources 2.0 and 1.0, so the per-axis
        // factors are 0.5 and 3.0 and g = sqrt((1+9)/(4+1)) = sqrt(2).
        // That value also rules out the three wrong answers at once:
        // arithmetic mean 1.75, geometric mean 1.2247, minimum 0.5.
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("a", 0.4, span(0.0, 2.0), span(0.2, 1.2))),
                windows(new WindowProjection.Window(0.1, 1.1, 90),
                        new WindowProjection.Window(-1.4, 1.6, 90)), 2);

        assertEquals(Math.sqrt(2.0), r.offsetFactor(), 1e-9);
        assertEquals(0.4 * Math.sqrt(2.0), r.cells().get(0).offset(), 1e-9);
    }

    @Test
    void aRejectedAxisIsNotCountedInTheOffsetFactor() {
        // The dead axis would drag g if it were counted, and scaling an offset
        // by an axis nobody can be placed on is meaningless.
        WindowProjection.Result<String> live = WindowProjection.project(
                List.of(cell("a", 1.0, span(0.0, 2.0))),
                windows(new WindowProjection.Window(0.1, 1.1, 90)), 5);
        WindowProjection.Result<String> withDead = WindowProjection.project(
                List.of(cell("a", 1.0, span(0.0, 2.0), span(0.0, 4.0))),
                windows(new WindowProjection.Window(0.1, 1.1, 90),
                        new WindowProjection.Window(-1.0, 1.0, 1)), 5);

        assertEquals(live.offsetFactor(), withDead.offsetFactor(), 1e-9);
    }

    @Test
    void noLiveAxisLeavesTheOffsetAlone() {
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("a", 0.7, span(0.0, 1.0))),
                windows(new WindowProjection.Window(-1.0, 1.0, 1)), 5);

        assertEquals(1.0, r.offsetFactor(), EPS);
        assertEquals(0.7, r.cells().get(0).offset(), EPS);
    }

    // ----------------------------------------------------------------- ties

    @Test
    void noProjectedBoundaryIsLeftOnARail() {
        // The window's own end is 0.5, so the palette's low extreme maps
        // exactly onto a rail and must be moved off it ([T59]).
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("a", 0.0, span(0.0, 1.0))),
                windows(new WindowProjection.Window(0.5, 0.9, 90)), 2);

        for (double rail : WindowProjection.RAILS) {
            assertFalse(WindowProjection.same(axis(r, 0, 0).lo(), rail),
                    "lo still sits on " + rail);
            assertFalse(WindowProjection.same(axis(r, 0, 0).hi(), rail),
                    "hi still sits on " + rail);
        }
        assertTrue(r.separations() > 0);
    }

    @Test
    void twoCellsThatWouldMeetAreSeparated() {
        // [0,1] and [1,2] tile the source, so they would meet at one point in
        // the window and both answer distance zero there.
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("lower", 0.0, span(0.0, 1.0)),
                        cell("upper", 0.0, span(1.0, 2.0))),
                windows(new WindowProjection.Window(0.1, 0.7, 90)), 2);

        assertFalse(WindowProjection.same(axis(r, 0, 0).hi(), axis(r, 1, 0).lo()),
                "a shared boundary is a tie, not a split");
        assertTrue(axis(r, 0, 0).hi() < axis(r, 1, 0).lo(), "and the order must hold");
        assertEquals(1, r.separations());
    }

    @Test
    void authoredOverlapIsLeftAlone() {
        // Two cells overlapping over a REGION tie across all of it. That is
        // what their authors wrote; narrowing them would invent a constraint.
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(cell("a", 0.0, span(0.0, 1.0)),
                        cell("b", 0.0, span(0.5, 1.5))),
                windows(new WindowProjection.Window(0.1, 0.85, 90)), 2);

        assertTrue(axis(r, 1, 0).lo() < axis(r, 0, 0).hi(), "the overlap must survive");
        assertEquals(0, r.separations(), "nothing here meets at a point");
    }

    @Test
    void anEmptyPaletteProjectsToNothing() {
        WindowProjection.Result<String> r = WindowProjection.project(
                List.of(), windows(new WindowProjection.Window(0.1, 0.9, 90)), 2);

        assertTrue(r.cells().isEmpty());
        assertEquals(1.0, r.offsetFactor(), EPS);
    }
}
