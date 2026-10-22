package com.customdimensions.core.zone;

/**
 * How strongly a zone applies at a column: 1 inside the shape, 0 outside, and a
 * linear ramp across {@code blend} blocks beyond each edge. The compiler emits
 * the same function as density-function JSON, so this is the reference it is
 * tested against.
 */
public record ZoneMask(double centreX, double centreZ, ZoneShape shape, double blend) {

    public ZoneMask {
        if (!(blend > 0)) {
            throw new IllegalArgumentException("blend must be positive, got " + blend);
        }
        if (shape instanceof ZoneShape.Ring ring && ring.inner() > 0 && blend > ring.inner()) {
            throw new IllegalArgumentException("blend " + blend + " is wider than the ring's hole (" + ring.inner() + ")");
        }
    }

    public double at(double x, double z) {
        double distance = Math.hypot(x - centreX, z - centreZ);
        return switch (shape) {
            case ZoneShape.Circle circle -> fallOff(distance, circle.radius());
            case ZoneShape.Ring ring -> riseIn(distance, ring.inner()) * fallOff(distance, ring.outer());
        };
    }

    /** 1 up to {@code edge}, falling to 0 at {@code edge + blend}. */
    double fallOff(double distance, double edge) {
        return clamp((edge + blend - distance) / blend);
    }

    /** 0 up to {@code edge - blend}, rising to 1 at {@code edge}; a zero-radius hole is no hole. */
    double riseIn(double distance, double edge) {
        return edge == 0 ? 1 : clamp((distance - (edge - blend)) / blend);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
