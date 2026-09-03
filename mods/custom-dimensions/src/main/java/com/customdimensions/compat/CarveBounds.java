package com.customdimensions.compat;

/** Whether a carver may touch a block, given the level's own height limits. */
public final class CarveBounds {

    private CarveBounds() {
    }

    /**
     * True where {@code y} lies inside {@code [bottomY, topY)} — vanilla's
     * half-open height limit, and the range {@code CarvingMask} indexes
     * without checking. A carver whose configured range runs past either end
     * asks for blocks that cannot exist; those are declined, not clamped.
     */
    public static boolean carvable(int y, int bottomY, int topY) {
        return y >= bottomY && y < topY;
    }
}
