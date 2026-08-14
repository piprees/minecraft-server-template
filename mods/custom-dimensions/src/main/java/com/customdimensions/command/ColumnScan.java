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
     * Blocks discarded below the roof's underside before the ground is looked
     * for — enough to clear what hangs from a ceiling without reaching the
     * terrain under it.
     *
     * <p>Public because {@code TerrainShape} applies the same clip over a
     * density field rather than block states, and two copies of this number
     * are two rules — the exact drift the parity test between them exists to
     * prevent.
     */
    public static final int CEILING_CLIP = 3;

    private ColumnScan() {
    }

    /** One column's playable surface, or the reason it has none. */
    public record Result(int floorY, String absentReason) {

        public boolean isPresent() {
            return absentReason == null;
        }
    }

    /**
     * Take the ceiling off the column, then read it like any other world.
     *
     * <p>Three steps: find the roof, find its underside, discard
     * {@link #CEILING_CLIP} more blocks, and answer the first open block above
     * the highest thing left — which is {@code OCEAN_FLOOR_WG}'s rule
     * exactly, the one an open dimension already uses. Below the ceiling a
     * ceilinged world is just a world, and every height it produces is a real
     * terrain height that shades on a map.
     *
     * <p><b>Why not "the highest floor with room to stand on it".</b> That was
     * the rule here, and it cost the renderer 225 of 1313 Nether columns. The
     * headroom test is cheap over block states and treacherous over the
     * density field {@code TerrainShape} reads: where the density says solid
     * and the generated blocks are air, headroom fails on ground that is
     * genuinely standable and the walk falls through to the next mass down.
     * Measured before this changed — every one of the 216 worst disagreements
     * had its floor above the lava sea, and the renderer answered a median of
     * 43 against the facts' 114, which is the lava-level terrace under the
     * terrain it should have found.
     *
     * <p>The clip does the job headroom was there for. What hangs off a
     * ceiling is within a few blocks of it; what a player stands on is not.
     *
     * <p>Preference order for the absent cases: a column with no roof at all
     * is not a ceilinged case for this scan; a column opaque from the roof to
     * {@code bottom} is entombed; a column open beneath the roof with nothing
     * solid under it has no ground.
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
        int groundY = findRoofY(undersideY - CEILING_CLIP, bottom, isOpaque);
        if (groundY == NONE) {
            return new Result(NONE, "no solid ground between " + CEILING_CLIP
                    + " blocks under the roof underside at y=" + undersideY
                    + " and y=" + bottom);
        }
        return new Result(groundY + 1, null);
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
     * Whether any cell in {@code [bottom, top]} matches {@code isFluid}.
     *
     * <p>A floor is not a precondition for water sitting where one would
     * have been — a groundless column (no roof, or opaque all the way to
     * {@code bottom}) can still hold an aquifer's fluid, and this is the
     * question that tells the two apart. Same shape as {@link #findRoofY},
     * asked of a different predicate.
     */
    public static boolean holdsFluid(int top, int bottom, IntPredicate isFluid) {
        for (int y = top; y >= bottom; y--) {
            if (isFluid.test(y)) {
                return true;
            }
        }
        return false;
    }
}
