package com.customdimensions.client.realtime;

/**
 * Which cells of the box are worth reading: the sightline cone, floored to the
 * near field's own width so nothing close to the opening is lost.
 *
 * <p>The box has to be a rectangular array — the mesh reads it at unit steps
 * and vanilla's face cull asks it for unit neighbours — so the shape is a
 * predicate over that array rather than a different array. A cell outside it
 * stays air and is never read, which is the whole saving.
 *
 * <p>No Minecraft types: all of it is arithmetic and all of it is tested.
 */
public final class ViewShape {

    private ViewShape() {}

    /**
     * Half-width of the cone at one block past the opening, never below
     * {@code near}. The cone widens at {@code 1 / coneRatio} per block, so the
     * floor is what holds the first {@code near * coneRatio} blocks at full
     * width — the near field, unchanged.
     */
    public static int halfWidthAt(int depthIndex, int near, int coneRatio) {
        int depth = depthIndex + 1;
        int widened = (depth + coneRatio - 1) / Math.max(1, coneRatio);
        return Math.max(near, widened);
    }

    /**
     * True when an in-plane index is inside the cone at that depth.
     * {@code lead} is where the aperture starts in the array, {@code span} how
     * many cells it covers.
     */
    public static boolean withinAxis(int index, int lead, int span, int half) {
        return index >= lead - half && index < lead + span + half;
    }

    /** Cells the shape holds, for a budget the walk can be checked against. */
    public static int cells(int spanA, int spanB, int depth, int near, int coneRatio) {
        int total = 0;
        for (int n = 0; n < depth; n++) {
            int half = halfWidthAt(n, near, coneRatio);
            total += (spanA + 2 * half) * (spanB + 2 * half);
        }
        return total;
    }

    /** Columns the shape holds: the widest in-plane run at each depth. */
    public static int columns(int spanA, int depth, int near, int coneRatio) {
        int total = 0;
        for (int n = 0; n < depth; n++) {
            total += spanA + 2 * halfWidthAt(n, near, coneRatio);
        }
        return total;
    }
}
