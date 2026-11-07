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
}
