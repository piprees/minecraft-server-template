package com.customdimensions.core.zone;

/**
 * How strongly a zone applies at a column: 1 inside the shape, 0 outside, and a
 * linear ramp across {@code blend} blocks beyond its edge. The compiled mask adds
 * noise to the edge distance, two standard deviations of which are
 * {@code jitter} blocks; this reference leaves it out, so {@link #at} is the
 * mask with a still edge. The compiler emits
 * the same function as density-function JSON, so this is the reference it is
 * tested against.
 */
public record ZoneMask(double centreX, double centreZ, ZoneShape shape, double blend, double jitter) {

    /** Below this, a blend is a hard edge one block wide. */
    public static final double MIN_BLEND = 1;

    public ZoneMask {
        if (!(blend >= 0)) {
            throw new IllegalArgumentException("blend must not be negative, got " + blend);
        }
        if (!(jitter >= 0)) {
            throw new IllegalArgumentException("edgeJitter must not be negative, got " + jitter);
        }
        if (shape instanceof ZoneShape.Ring ring && ring.inner() > 0 && blend > ring.inner()) {
            throw new IllegalArgumentException("blend " + blend + " is wider than the ring's hole (" + ring.inner() + ")");
        }
        if (shape instanceof ZoneShape.Sector sector && sector.inner() > 0 && blend > sector.inner()) {
            throw new IllegalArgumentException("blend " + blend + " is wider than the sector's hole (" + sector.inner() + ")");
        }
    }

    public ZoneMask(double centreX, double centreZ, ZoneShape shape, double blend) {
        this(centreX, centreZ, shape, blend, 0);
    }

    /** The blend the ramp uses: {@link #MIN_BLEND} for a hard edge. */
    public double rampWidth() {
        return Math.max(MIN_BLEND, blend);
    }

    public double at(double x, double z) {
        return weight(shape.outside(x - centreX, z - centreZ));
    }

    /** The mask at {@code outside} blocks beyond the edge. */
    public double weight(double outside) {
        return clamp(1 - outside / rampWidth());
    }

    /** Whether the jittered edge could reach {@code (x, z)}: inside the shape, its blend or four deviations of jitter. */
    public boolean mayCover(double x, double z) {
        return shape.outside(x - centreX, z - centreZ) < rampWidth() + 2 * jitter;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
