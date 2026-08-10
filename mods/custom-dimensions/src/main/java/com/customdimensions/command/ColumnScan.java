package com.customdimensions.command;

import java.util.function.IntPredicate;

/**
 * Finds a roof, its underside, and the playable floor beneath it from
 * nothing but an opacity test indexed by Y — no block state, no world.
 *
 * <p>{@link SpikeSampler} feeds this real blocks from a headless
 * {@code VerticalBlockSample}; a test feeds it a synthetic column. Vanilla's
 * {@code WORLD_SURFACE_WG} heightmap answers the roof in a ceilinged
 * dimension, identically for every column, because it is simply the highest
 * opaque block and the roof sits above everything else. This scan walks
 * DOWN through the roof to find the space underneath it instead.
 */
public final class ColumnScan {

    /** The scan found nothing in the requested range. */
    public static final int NONE = Integer.MIN_VALUE;

    /**
     * Open cells required above a candidate floor: feet and head. Without
     * this, a single open cell wedged between two solid masses reads as a
     * standable floor when a player there would suffocate.
     */
    private static final int MIN_CLEARANCE = 2;

    private ColumnScan() {
    }

    /** One column's playable surface, or the reason it has none. */
    public record Result(int floorY, String absentReason) {

        public boolean isPresent() {
            return absentReason == null;
        }
    }

    /**
     * Roof, underside, and playable floor in one pass, in preference order:
     * a column with no roof at all is not a ceilinged case for this scan; a
     * column opaque from the roof to {@code bottom} is entombed; a column
     * open beneath the roof but with no solid ground above {@code bottom}
     * has no floor to stand on. Only the last of these returns a floor.
     */
    public static Result scan(int top, int bottom, IntPredicate isOpaque) {
        int roofY = findRoofY(top, bottom, isOpaque);
        if (roofY == NONE) {
            return new Result(NONE, "no opaque block between y=" + top + " and y=" + bottom
                    + ", so this column has no roof to scan under");
        }
        int undersideY = findRoofUndersideY(roofY, bottom, isOpaque);
        if (undersideY == NONE) {
            return new Result(NONE, "column is opaque from the roof at y=" + roofY
                    + " to y=" + bottom + ", no open interior beneath it");
        }
        int floorY = findPlayableFloorY(undersideY, bottom, isOpaque);
        if (floorY == NONE) {
            return new Result(NONE, "no solid ground between the roof underside at y="
                    + undersideY + " and y=" + bottom);
        }
        return new Result(floorY, null);
    }

    /** The Y of the highest opaque block in {@code [bottom, top]}, or {@link #NONE}. */
    public static int findRoofY(int top, int bottom, IntPredicate isOpaque) {
        for (int y = top; y >= bottom; y--) {
            if (isOpaque.test(y)) {
                return y;
            }
        }
        return NONE;
    }

    /**
     * The first open Y walking down from {@code roofY} through the
     * CONTIGUOUS opaque slab, or {@link #NONE} when the column is opaque all
     * the way to {@code bottom} (entombed). Starting anywhere below
     * {@code roofY} risks opening a pocket inside a roof that runs dozens of
     * blocks thick rather than in the space below it.
     */
    public static int findRoofUndersideY(int roofY, int bottom, IntPredicate isOpaque) {
        for (int y = roofY; y >= bottom; y--) {
            if (!isOpaque.test(y)) {
                return y;
            }
        }
        return NONE;
    }

    /**
     * The playable surface in {@code [top, bottom]}: one above the highest
     * opaque block with at least {@link #MIN_CLEARANCE} open cells above it
     * — matching the {@code WORLD_SURFACE_WG} heightmap convention, but
     * rejecting a sliver too shallow to stand in rather than accepting the
     * first one. Walks through any fluid or open layers above ground without
     * mistaking them for it. {@link #NONE} when nothing in range has both an
     * opaque block and room to stand on it.
     */
    public static int findPlayableFloorY(int top, int bottom, IntPredicate isOpaque) {
        for (int y = top; y >= bottom; y--) {
            if (isOpaque.test(y) && hasClearance(y + 1, isOpaque)) {
                return y + 1;
            }
        }
        return NONE;
    }

    private static boolean hasClearance(int floorY, IntPredicate isOpaque) {
        for (int i = 0; i < MIN_CLEARANCE; i++) {
            if (isOpaque.test(floorY + i)) {
                return false;
            }
        }
        return true;
    }
}
