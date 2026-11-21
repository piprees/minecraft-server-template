package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;

import java.util.Collection;

/**
 * Coordinate scaling between two dimensions: vanilla's own rule,
 * {@code pos * sourceScale / targetScale}. Scale describes a dimension, so
 * each side is asked for its own; entering a scale-8 world divides by 8 and
 * leaving it multiplies by 8.
 */
public final class PortalScaling {

    private PortalScaling() {
    }

    /** One coordinate carried from a world of {@code sourceScale} to one of {@code targetScale}. */
    public static int scale(int coordinate, double sourceScale, double targetScale) {
        double ratio = targetScale / sourceScale;
        return (int) Math.round(coordinate / ratio);
    }

    /** The arrival column for an opening: its centre column, scaled. */
    public static int[] arrivalColumn(Collection<BlockPos> interior, double sourceScale, double targetScale) {
        int[] centre = PortalGeometry.centreColumn(interior);
        if (centre == null) {
            return null;
        }
        return new int[]{scale(centre[0], sourceScale, targetScale), scale(centre[1], sourceScale, targetScale)};
    }

    /** How far inside a world border an arrival column is kept: the opening and a cell either side. */
    public static final int BORDER_MARGIN = 8;

    /**
     * {@code v} moved inside {@code [min + BORDER_MARGIN, max - BORDER_MARGIN]}; the centre when
     * the border is narrower than twice the margin. An arrival outside the player border is a death.
     */
    public static int insideBorder(int v, double min, double max) {
        double lo = min + BORDER_MARGIN;
        double hi = max - BORDER_MARGIN - 1;
        if (lo > hi) {
            return (int) Math.floor((min + max) / 2);
        }
        return (int) Math.max(Math.ceil(lo), Math.min(Math.floor(hi), v));
    }
}
