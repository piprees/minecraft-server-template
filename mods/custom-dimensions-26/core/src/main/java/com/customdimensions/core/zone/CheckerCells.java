package com.customdimensions.core.zone;

/**
 * The cells of vanilla's checkerboard biome source: at block {@code (x, z)}
 * the source lays biome {@code floorMod((qx >> s) + (qz >> s), count)} of its
 * list, where {@code qx} and {@code qz} are quart coordinates and {@code s}
 * is its {@code scale} plus 2, so a cell is {@code 2^(scale + 4)} blocks wide.
 */
public final class CheckerCells {
    private CheckerCells() {
    }

    /** The index into the checkerboard's biome list at block {@code (x, z)}. */
    public static int index(int x, int z, int scale, int count) {
        int shift = scale + 2;
        return Math.floorMod(((x >> 2) >> shift) + ((z >> 2) >> shift), count);
    }

    /** The width of one cell in blocks. */
    public static int width(int scale) {
        return 1 << (scale + 4);
    }
}
