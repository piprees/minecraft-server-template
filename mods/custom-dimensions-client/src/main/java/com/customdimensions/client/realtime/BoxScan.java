package com.customdimensions.client.realtime;

/**
 * How far a box walk has got, and how much of it the next tick may do.
 *
 * <p>The walk is a fixed number of units — one per cell, then one per column
 * for the tints, which need every cell of their column already read. A slice
 * is a half-open range of that order, so a walk resumes exactly where the last
 * one stopped and the per-tick cost is the budget rather than the box.
 *
 * <p>No Minecraft types: all of it is arithmetic and all of it is tested.
 */
public record BoxScan(int cells, int columns, int cursor) {

    public static BoxScan of(int sizeX, int sizeY, int sizeZ) {
        return new BoxScan(sizeX * sizeY * sizeZ, sizeX * sizeZ, 0);
    }

    /** Cells first, then columns. */
    public int units() {
        return this.cells + this.columns;
    }

    public boolean done() {
        return this.cursor >= units();
    }

    /** Where this slice stops, never past the end and never short of one unit. */
    public int end(int budget) {
        return Math.min(units(), this.cursor + Math.max(1, budget));
    }

    public BoxScan advancedTo(int end) {
        return new BoxScan(this.cells, this.columns, Math.min(units(), Math.max(this.cursor, end)));
    }

    /**
     * The three local coordinates of a cell index, inverting the payload's own
     * {@code ((x * sizeZ) + z) * sizeY + y}. A slice reads cells in storage
     * order, so it never revisits a cell and never skips one.
     */
    public static int localY(int index, int sizeY) {
        return index % sizeY;
    }

    public static int localZ(int index, int sizeY, int sizeZ) {
        return (index / sizeY) % sizeZ;
    }

    public static int localX(int index, int sizeY, int sizeZ) {
        return index / (sizeY * sizeZ);
    }

    /** A column index past the cells, as its two in-plane coordinates. */
    public static int columnX(int unit, int cells, int sizeZ) {
        return (unit - cells) / sizeZ;
    }

    public static int columnZ(int unit, int cells, int sizeZ) {
        return (unit - cells) % sizeZ;
    }
}
