package com.customdimensions.core.zone;

import java.util.List;

/**
 * The area a zone covers, in blocks relative to its centre. {@link #outside}
 * is how far a point lies beyond the shape's edge: zero or less inside, the
 * distance to the edge outside. A zone's mask ramps down across its blend from
 * that edge, so the blend always lies outside the shape.
 */
public sealed interface ZoneShape {

    /** Blocks beyond the edge at offset {@code (dx, dz)} from the centre; zero or less inside. */
    double outside(double dx, double dz);

    /** The largest distance from the centre to any point of the shape. */
    double extent();

    /** Everything within {@code radius} blocks. */
    record Circle(double radius) implements ZoneShape {
        public Circle {
            if (!(radius > 0)) {
                throw new IllegalArgumentException("circle radius must be positive, got " + radius);
            }
        }

        @Override
        public double outside(double dx, double dz) {
            return Math.hypot(dx, dz) - radius;
        }

        @Override
        public double extent() {
            return radius;
        }
    }

    /** Everything from {@code inner} to {@code outer} blocks; the hole keeps the base worldgen. */
    record Ring(double inner, double outer) implements ZoneShape {
        public Ring {
            if (!(inner >= 0) || !(outer > inner)) {
                throw new IllegalArgumentException("ring needs 0 <= inner < outer, got " + inner + ".." + outer);
            }
        }

        @Override
        public double outside(double dx, double dz) {
            double d = Math.hypot(dx, dz);
            return inner == 0 ? d - outer : Math.max(inner - d, d - outer);
        }

        @Override
        public double extent() {
            return outer;
        }
    }

    /**
     * An axis-aligned rectangle {@code 2 * halfX} by {@code 2 * halfZ}. Outside
     * it, distance is the larger of the two axis distances, so its blend has
     * square corners.
     */
    record Box(double halfX, double halfZ) implements ZoneShape {
        public Box {
            if (!(halfX > 0) || !(halfZ > 0)) {
                throw new IllegalArgumentException("box half sizes must be positive, got " + halfX + " x " + halfZ);
            }
        }

        @Override
        public double outside(double dx, double dz) {
            return Math.max(Math.abs(dx) - halfX, Math.abs(dz) - halfZ);
        }

        @Override
        public double extent() {
            return Math.hypot(halfX, halfZ);
        }
    }

    /**
     * The part of a ring between two compass angles, in degrees from east
     * ({@code +x}) towards south ({@code +z}), running from {@code from} to
     * {@code to} in that direction; {@code inner} 0 is a pie slice.
     */
    record Sector(double inner, double outer, double from, double to) implements ZoneShape {
        public Sector {
            if (!(inner >= 0) || !(outer > inner)) {
                throw new IllegalArgumentException("sector needs 0 <= inner < outer, got " + inner + ".." + outer);
            }
            double sweep = sweep(from, to);
            if (!(sweep > 0) || sweep >= 360) {
                throw new IllegalArgumentException("sector angles " + from + ".." + to + " must sweep between 0 and 360 degrees");
            }
        }

        @Override
        public double outside(double dx, double dz) {
            double d = Math.hypot(dx, dz);
            double radial = inner == 0 ? d - outer : Math.max(inner - d, d - outer);
            double angle = Math.toDegrees(Math.atan2(dz, dx));
            double sweep = sweep(from, to);
            double into = sweep(from, angle);
            if (d == 0 || into <= sweep) {
                return Math.max(radial, -Math.min(rayDistance(dx, dz, from), rayDistance(dx, dz, to)));
            }
            double beyondTo = into - sweep;
            double beforeFrom = 360 - into;
            double nearest = beyondTo <= beforeFrom ? to : from;
            return Math.max(radial, rayDistance(dx, dz, nearest));
        }

        @Override
        public double extent() {
            return outer;
        }

        /** Degrees from {@code a} to {@code b} going towards south, in [0, 360). */
        static double sweep(double a, double b) {
            double s = (b - a) % 360;
            return s < 0 ? s + 360 : s;
        }

        /** Distance from the point to the ray from the centre at {@code degrees}. */
        private static double rayDistance(double dx, double dz, double degrees) {
            double ux = Math.cos(Math.toRadians(degrees));
            double uz = Math.sin(Math.toRadians(degrees));
            double along = dx * ux + dz * uz;
            return along <= 0 ? Math.hypot(dx, dz) : Math.abs(dx * uz - dz * ux);
        }
    }

    /** A simple polygon through {@code points} ({@code [x, z]} pairs), closed back to the first. */
    record Polygon(List<double[]> points) implements ZoneShape {
        public Polygon {
            if (points == null || points.size() < 3) {
                throw new IllegalArgumentException("polygon needs at least 3 points");
            }
            points = points.stream().map(p -> {
                if (p.length != 2) throw new IllegalArgumentException("polygon points are [x, z] pairs");
                return p.clone();
            }).toList();
        }

        @Override
        public double outside(double dx, double dz) {
            boolean inside = false;
            double nearest = Double.MAX_VALUE;
            int n = points.size();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double[] a = points.get(i);
                double[] b = points.get(j);
                if ((a[1] > dz) != (b[1] > dz) && dx < (b[0] - a[0]) * (dz - a[1]) / (b[1] - a[1]) + a[0]) {
                    inside = !inside;
                }
                nearest = Math.min(nearest, segmentDistance(dx, dz, a, b));
            }
            return inside ? -nearest : nearest;
        }

        @Override
        public double extent() {
            double far = 0;
            for (double[] p : points) far = Math.max(far, Math.hypot(p[0], p[1]));
            return far;
        }

        private static double segmentDistance(double x, double z, double[] a, double[] b) {
            double ex = b[0] - a[0];
            double ez = b[1] - a[1];
            double length = ex * ex + ez * ez;
            double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * ex + (z - a[1]) * ez) / length));
            return Math.hypot(x - (a[0] + t * ex), z - (a[1] + t * ez));
        }
    }

    /** The whole dimension: a zone that applies everywhere, such as a global biome swap. */
    record All() implements ZoneShape {
        @Override
        public double outside(double dx, double dz) {
            return -1;
        }

        @Override
        public double extent() {
            return 0;
        }
    }
}
