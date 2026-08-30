package com.customdimensions.dimension;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps declared climate cells into the window a dimension's router actually
 * samples.
 *
 * <p>Cells are authored across the whole climate space; a dimension samples a
 * sliver of it. Handed to the router unchanged, most of the table is never
 * nearest to any point the world visits — a few entries win everything and the
 * rest are unreachable. The transform is affine and monotone per axis, so
 * ordering, relative position and relative width survive it: a biome its author
 * put at the warm end of its peers stays at the warm end of this world.
 *
 * <p>Pure and generic over the payload so the rules are testable: this suite
 * cannot bootstrap Minecraft's registries. Nothing here touches a hypercube;
 * converting to and from one belongs to the caller.
 */
public final class WindowProjection {

    /** The six climate axes, in the order a hypercube declares them. */
    public static final int AXES = 6;

    /**
     * The fixed point the game rounds climate to: {@code toLong} is
     * {@code (long)(v * 10000f)}, so two values closer than this are the same
     * long and cannot be told apart by a distance.
     */
    static final double QUANTUM = 1.0 / 10000.0;

    /** How many times an endpoint may be moved before it is left alone. */
    static final int MAX_SEPARATION_STEPS = 4;

    /**
     * Values the climate noise piles up on. Weirdness saturates at +-1.0 and
     * plateaus at +-0.5, and +-2.0 is the schema's own edge, so a boundary
     * landing on one is a tie waiting for a sample ([T59]).
     */
    static final double[] RAILS = {-2.0, -1.0, -0.5, 0.5, 1.0, 2.0};

    /** One axis interval. */
    public record Span(double lo, double hi) {
    }

    /**
     * One axis as the world actually samples it. {@code distinct} is the count
     * of different values the grid produced, which is what says whether the
     * axis carries information; {@code hi - lo} does not, because a clamped
     * axis wears a wide span ([T58]).
     */
    public record Window(double lo, double hi, int distinct) {
    }

    /** A cell awaiting projection. A null entry in {@code axes} is unconstrained. */
    public record Cell<T>(T value, List<Span> axes, double offset) {
    }

    /**
     * @param rejectedAxes  axes dropped as carrying no information, so every
     *                      cell is equally free there
     * @param offsetFactor  the {@code g} every offset was multiplied by
     * @param separations   endpoints moved off a rail or off a touching
     *                      neighbour
     * @param unseparated   endpoints that could not be moved without collapsing
     *                      their cell, and so still carry a tie hazard
     */
    public record Result<T>(List<Cell<T>> cells, Set<Integer> rejectedAxes,
                            double offsetFactor, int separations, int unseparated) {
    }

    private WindowProjection() {
    }

    /**
     * Projects a palette into a dimension's window.
     *
     * <p>{@code minDistinct} is the caller's, deliberately: where between
     * {@code distinct = 1} and a full grid a band stops working is unmeasured,
     * and a number invented here is what [K7] exists to warn against.
     */
    public static <T> Result<T> project(List<Cell<T>> palette, List<Window> windows,
                                        int minDistinct) {
        Set<Integer> rejected = new LinkedHashSet<>();
        if (palette == null || palette.isEmpty()) {
            return new Result<>(List.of(), rejected, 1.0, 0, 0);
        }
        Span[] source = new Span[AXES];
        boolean[] live = new boolean[AXES];
        for (int a = 0; a < AXES; a++) {
            Window w = windows == null || a >= windows.size() ? null : windows.get(a);
            source[a] = extentOf(palette, a);
            // An axis is usable only if the world varies across it AND the
            // palette says something about it. Either missing makes the
            // mapping meaningless rather than merely narrow.
            live[a] = w != null && w.distinct() >= minDistinct && width(w.lo(), w.hi()) > 0
                    && source[a] != null && width(source[a].lo(), source[a].hi()) > 0;
            if (!live[a] && source[a] != null) {
                rejected.add(a);
            }
        }

        double g = offsetFactor(windows, source, live);
        List<Cell<T>> out = new ArrayList<>();
        for (Cell<T> cell : palette) {
            List<Span> axes = new ArrayList<>();
            for (int a = 0; a < AXES; a++) {
                Span s = axisOf(cell, a);
                // A rejected axis loses its constraint everywhere, so every
                // cell is equally free there rather than one being narrowed
                // into a window that carries no information.
                axes.add(s == null || !live[a] ? null
                        : mapSpan(s, source[a], windows.get(a)));
            }
            out.add(new Cell<>(cell.value(), axes, cell.offset() * g));
        }
        int[] moved = separate(out);
        return new Result<>(out, rejected, g, moved[0], moved[1]);
    }

    /**
     * {@code g = ||W|| / ||S||}, the ratio of the window box's diagonal to the
     * source box's, over the live axes.
     *
     * <p>{@code offset} is a constant penalty in the same squared units as an
     * axis distance, so shrinking the axes into a window and leaving it alone
     * makes it relatively enormous. This is the unique factor that keeps its
     * share of the axis budget: it solves
     * {@code (g*o)^2 / sum W^2 == o^2 / sum S^2}, independent of the offset and
     * of the sample. Falls back to 1.0 when no axis is live, where scaling
     * nothing by anything is the honest answer.
     */
    static double offsetFactor(List<Window> windows, Span[] source, boolean[] live) {
        double sw = 0.0;
        double ss = 0.0;
        for (int a = 0; a < AXES; a++) {
            if (!live[a]) {
                continue;
            }
            double w = width(windows.get(a).lo(), windows.get(a).hi());
            double s = width(source[a].lo(), source[a].hi());
            sw += w * w;
            ss += s * s;
        }
        return ss <= 0.0 ? 1.0 : Math.sqrt(sw / ss);
    }

    /** The palette's own extent on one axis, or null when nothing constrains it. */
    static <T> Span extentOf(List<Cell<T>> palette, int axis) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (Cell<T> cell : palette) {
            Span s = axisOf(cell, axis);
            if (s == null) {
                continue;
            }
            any = true;
            lo = Math.min(lo, s.lo());
            hi = Math.max(hi, s.hi());
        }
        return any ? new Span(lo, hi) : null;
    }

    /** Affine, monotone, both ends mapped. */
    static Span mapSpan(Span span, Span source, Window window) {
        return new Span(map(span.lo(), source, window), map(span.hi(), source, window));
    }

    private static double map(double x, Span source, Window window) {
        double s = width(source.lo(), source.hi());
        double w = width(window.lo(), window.hi());
        return window.lo() + (x - source.lo()) * w / s;
    }

    /**
     * Moves any endpoint that sits on a rail, or that meets a neighbour's
     * opposite endpoint, one quantum INWARD.
     *
     * <p>Inward never widens a cell and never crosses its other end, so
     * ordering survives. It removes a tie this projection would otherwise
     * CREATE — two cells meeting at a point both answer distance zero there
     * ([T59]). It deliberately does NOT remove authored overlap: two cells that
     * overlap over a region tie across all of it, and narrowing them would
     * invent a constraint their authors did not write.
     *
     * <p>Returns {@code {moved, unmovable}} — an endpoint that cannot move
     * without collapsing its cell is left carrying its hazard and counted.
     */
    static <T> int[] separate(List<Cell<T>> cells) {
        int moved = 0;
        int stuck = 0;
        for (int a = 0; a < AXES; a++) {
            for (int i = 0; i < cells.size(); i++) {
                Span span = axisOf(cells.get(i), a);
                if (span == null) {
                    continue;
                }
                double lo = span.lo();
                double hi = span.hi();
                for (int step = 0; step < MAX_SEPARATION_STEPS
                        && needsMove(cells, a, i, lo, true); step++) {
                    if (lo + QUANTUM > hi) {
                        break;
                    }
                    lo += QUANTUM;
                    moved++;
                }
                for (int step = 0; step < MAX_SEPARATION_STEPS
                        && needsMove(cells, a, i, hi, false); step++) {
                    if (hi - QUANTUM < lo) {
                        break;
                    }
                    hi -= QUANTUM;
                    moved++;
                }
                if (needsMove(cells, a, i, lo, true) || needsMove(cells, a, i, hi, false)) {
                    stuck++;
                }
                if (lo != span.lo() || hi != span.hi()) {
                    cells.get(i).axes().set(a, new Span(lo, hi));
                }
            }
        }
        return new int[]{moved, stuck};
    }

    /** True when a value sits on a rail, or meets another cell's opposite end. */
    private static <T> boolean needsMove(List<Cell<T>> cells, int axis, int self,
                                         double value, boolean isLow) {
        for (double rail : RAILS) {
            if (same(value, rail)) {
                return true;
            }
        }
        for (int j = 0; j < cells.size(); j++) {
            if (j == self) {
                continue;
            }
            Span other = axisOf(cells.get(j), axis);
            if (other != null && same(value, isLow ? other.hi() : other.lo())) {
                return true;
            }
        }
        return false;
    }

    /** Equal once quantised, which is the only equality the game can see. */
    static boolean same(double a, double b) {
        return Math.abs(a - b) < QUANTUM / 2.0;
    }

    private static double width(double lo, double hi) {
        return hi - lo;
    }

    private static <T> Span axisOf(Cell<T> cell, int axis) {
        List<Span> axes = cell.axes();
        return axes == null || axis >= axes.size() ? null : axes.get(axis);
    }
}
