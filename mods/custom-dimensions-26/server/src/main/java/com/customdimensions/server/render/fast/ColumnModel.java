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

    /** As {@link #scan(int, int, int, int, int)} over blocks {@code from} to {@code to}, when {@link #align()} is above 0. */
    default ColumnRuns[] scan(int x0, int z0, int step, int nx, int nz, int from, int to) {
        return scan(x0, z0, step, nx, nz);
    }

    /** The cell height a window passed to {@link #scan(int, int, int, int, int, int, int)} snaps to; 0 when only whole columns scan. */
    default int align() {
        return 0;
    }

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
