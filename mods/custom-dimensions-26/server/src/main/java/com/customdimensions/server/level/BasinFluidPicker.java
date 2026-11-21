package com.customdimensions.server.level;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

/**
 * A level's global fluid rule with a terrain's basins filled: in a column
 * where the terrain's {@code basin} function is above 0, open cells from
 * {@link #FLOOR_REACH} under the basin floor up to {@code level} hold the
 * basins' fluid; every other cell takes the rule beneath, so the void and the
 * sky stay as they were. The basin and floor are sampled once per column and
 * kept for the chunk the thread is filling. A column whose basin value is below
 * 1 lies within reach of the rim, where a gap opens the basin to the void;
 * fluid there is scheduled to flow ({@code DisabledAquiferMixin}).
 */
public final class BasinFluidPicker implements Aquifer.FluidPicker {

    /** Blocks under the basin floor the fluid may start, where a cave entrance opens the floor's top. */
    public static final int FLOOR_REACH = com.customdimensions.compiler.terrain.TerrainFamily.BASIN_FLOOR_REACH;

    private final Aquifer.FluidPicker beneath;
    private final RandomState random;
    private final DensityFunction basin;
    private final DensityFunction floor;
    private final Aquifer.FluidStatus fluid;
    private final int level;
    private final ThreadLocal<Columns> columns = ThreadLocal.withInitial(Columns::new);
    private final java.util.concurrent.atomic.AtomicLong scheduled = new java.util.concurrent.atomic.AtomicLong();

    public BasinFluidPicker(Aquifer.FluidPicker beneath, RandomState random, DensityFunction basin, DensityFunction floor,
                            int level, BlockState fluid) {
        this.beneath = beneath;
        this.random = random;
        this.basin = basin;
        this.floor = floor;
        this.level = level;
        this.fluid = new Aquifer.FluidStatus(level, fluid);
    }

    public int level() {
        return level;
    }

    /** The column's basin value: above 0 inside a basin, below 1 within reach of its rim. */
    public double basin(int x, int z) {
        return columns.get().at(this, x, z)[0];
    }

    /** Counts a cell of fluid scheduled to flow. */
    public void scheduled() {
        scheduled.incrementAndGet();
    }

    /** Cells of fluid scheduled to flow so far. */
    public long scheduledCount() {
        return scheduled.get();
    }

    /** The basin floor's height in the column, where {@link #basin} is above 0. */
    public double floor(int x, int z) {
        return columns.get().at(this, x, z)[1];
    }

    /** The basin floor's height in any column, sampled afresh: the rim and its outer lip included. */
    public double floorAnywhere(int x, int z) {
        return random.sampleBlockValueUncached(floor, x, 0, z);
    }

    /** Whether the basins' fluid stands in this cell, if it is open. */
    public boolean holds(int x, int y, int z) {
        if (y >= level) return false;
        double[] c = columns.get().at(this, x, z);
        return c[0] > 0 && y >= c[1] - FLOOR_REACH;
    }

    @Override
    public Aquifer.FluidStatus computeFluid(int x, int y, int z) {
        return holds(x, y, z) ? fluid : beneath.computeFluid(x, y, z);
    }

    /** One chunk's columns: basin value and floor height, sampled on first use. */
    private static final class Columns {
        private long chunk = Long.MIN_VALUE;
        private final double[][] values = new double[256][];

        double[] at(BasinFluidPicker picker, int x, int z) {
            long key = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
            if (key != chunk) {
                chunk = key;
                java.util.Arrays.fill(values, null);
            }
            int i = (x & 15) << 4 | (z & 15);
            double[] v = values[i];
            if (v == null) {
                double b = picker.random.sampleBlockValueUncached(picker.basin, x, 0, z);
                double f = b > 0 ? picker.random.sampleBlockValueUncached(picker.floor, x, 0, z) : 0;
                v = values[i] = new double[] {b, f};
            }
            return v;
        }
    }
}
