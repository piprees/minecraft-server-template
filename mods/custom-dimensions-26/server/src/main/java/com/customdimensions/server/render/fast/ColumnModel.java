package com.customdimensions.server.render.fast;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One thread's source of column runs, biomes, run faces and fluids for
 * {@link FastRender}: a {@link TileSampler} for a noise generator, the
 * generator's own noise columns otherwise. A tile's faces and fluids are
 * asked for after its {@link #scan}, before the next.
 */
public interface ColumnModel extends AutoCloseable {
    ColumnRuns[] scan(int x0, int z0, int step, int nx, int nz);

    Holder<Biome> biome(int x, int y, int z);

    RunDress.Materials<BlockState> materials();

    RunDress.Fluids<BlockState> fluids();

    BlockState defaultBlock();

    /** The density's interpolation cell {xz, y}, {0, 0} when the scout reads it directly or there is no density. */
    int[] cell();

    /** Density evaluations (scout points or interpolation corners, summed over terms), cells refined block by block, and columns, so far. */
    long[] counts();

    /** How runs are found: {@link TileSampler#LATTICE}, {@link TileSampler#REFINE}, or {@code noise-column}. */
    default String mode() {
        return "noise-column";
    }

    @Override
    void close();
}
