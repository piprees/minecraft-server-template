package com.customdimensions.server.render.fast;

/**
 * The blocks a column's runs show: the material rule's block at each run's
 * top, at the underside of each run with open space below it, and the fluid
 * at the lowest block of the open space above each run. {@code T} is the
 * block type (a {@code BlockState} in the game).
 */
public final class RunDress<T> {
    /** The material rule at one block; null for the default block. */
    public interface Materials<T> {
        /** Called once per column before its blocks, with the surface's gradient towards +x and +z in blocks per step. */
        default void column(int x, int z, int gradientX, int gradientZ) {}

        /**
         * The block at {@code (x, y, z)}, {@code depthAbove} blocks below the
         * open space over it and {@code depthBelow} above the open space under
         * it (both 1 at a face), under fluid up to {@code waterHeight}
         * ({@link Integer#MIN_VALUE} for none).
         */
        T at(int x, int y, int z, int depthAbove, int depthBelow, int waterHeight);
    }

    /** The fluid the level's fluid picker puts at a block of open space. */
    public interface Fluids<T> {
        /** The fluid at {@code (x, y, z)}, or null for air. */
        Fluid<T> at(int x, int y, int z);
    }

    /** A fluid and the block above its surface. */
    public record Fluid<T>(T state, int level) {}

    private final Object[] tops;
    private final Object[] bottoms;
    private final Object[] fluids;
    private final int[] fluidLevels;

    private RunDress(int n) {
        tops = new Object[n];
        bottoms = new Object[n];
        fluids = new Object[n];
        fluidLevels = new int[n];
    }

    /**
     * Dresses {@code runs} at {@code (x, z)} in a noise range up to {@code hi};
     * {@code fallback} stands for the default block where the rule gives none.
     * Undersides are dressed only when {@code undersides}.
     */
    public static <T> RunDress<T> of(ColumnRuns runs, int x, int z, int lo, int hi, Materials<T> materials, Fluids<T> fluids,
                                     T fallback, boolean undersides) {
        return of(runs, x, z, lo, hi, materials, fluids, fallback, undersides, runs.count());
    }

    /**
     * As {@link #of(ColumnRuns, int, int, int, int, Materials, Fluids, Object, boolean)},
     * with the fluid picker filling only the open space over the first
     * {@code openRuns} runs ({@link #openRuns}); the space over the others
     * stays open.
     */
    public static <T> RunDress<T> of(ColumnRuns runs, int x, int z, int lo, int hi, Materials<T> materials, Fluids<T> fluids,
                                     T fallback, boolean undersides, int openRuns) {
        RunDress<T> d = new RunDress<>(runs.count());
        for (int i = 0; i < runs.count(); i++) {
            int top = runs.top(i);
            int bottom = runs.bottom(i);
            int gapTop = i == 0 ? hi : runs.bottom(i - 1) - 1;
            int water = Integer.MIN_VALUE;
            if (i < openRuns && top + 1 <= gapTop) {
                Fluid<T> f = fluids.at(x, top + 1, z);
                if (f != null) {
                    d.fluids[i] = f.state();
                    d.fluidLevels[i] = Math.min(f.level(), gapTop + 1);
                    water = d.fluidLevels[i];
                }
            }
            T block = materials.at(x, top, z, 1, top - bottom + 1, water);
            d.tops[i] = block != null ? block : fallback;
            if (undersides && bottom > lo) {
                T under = materials.at(x, bottom, z, top - bottom + 1, 1, water);
                d.bottoms[i] = under != null ? under : fallback;
            }
        }
        return d;
    }

    /**
     * How many of a column's {@code count} runs, from the top, take the fluid
     * picker's fluid over them: all in a level without aquifers; with
     * aquifers only the topmost of an unroofed column, as the open space under
     * a roof or a run is a cave whose local water the picker does not know.
     */
    public static int openRuns(int count, boolean aquifers, boolean roofed) {
        return !aquifers ? count : roofed ? 0 : Math.min(1, count);
    }

    @SuppressWarnings("unchecked")
    public T top(int i) {
        return (T) tops[i];
    }

    /** The underside's block, or null when the run rests on the noise's floor or undersides were not dressed. */
    @SuppressWarnings("unchecked")
    public T bottom(int i) {
        return (T) bottoms[i];
    }

    /** The fluid over run {@code i}, or null when open space or nothing is above it. */
    @SuppressWarnings("unchecked")
    public T fluid(int i) {
        return (T) fluids[i];
    }

    /** The block above the surface of the fluid over run {@code i}. */
    public int fluidLevel(int i) {
        return fluidLevels[i];
    }
}
