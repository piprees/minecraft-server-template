package com.customdimensions.client.view;

import java.util.ArrayList;
import java.util.List;

/**
 * Polygon work for the far side, on vertices {x, y, z, u, v} in world
 * coordinates. No Minecraft types.
 */
public final class Clip {
    private Clip() {
    }

    /** Sutherland–Hodgman against each half-space in turn; empty when nothing survives. */
    public static List<double[]> clip(List<double[]> polygon, double[][] planes) {
        List<double[]> poly = polygon;
        for (double[] pl : planes) {
            if (poly.isEmpty()) {
                return poly;
            }
            List<double[]> out = new ArrayList<>(poly.size() + 2);
            for (int i = 0; i < poly.size(); i++) {
                double[] p = poly.get(i);
                double[] q = poly.get((i + 1) % poly.size());
                double dp = side(pl, p);
                double dq = side(pl, q);
                if (dp >= 0) {
                    out.add(p);
                }
                if ((dp >= 0) != (dq >= 0)) {
                    double t = dp / (dp - dq);
                    double[] r = new double[p.length];
                    for (int k = 0; k < p.length; k++) {
                        r[k] = p[k] + (q[k] - p[k]) * t;
                    }
                    out.add(r);
                }
            }
            poly = out;
        }
        return poly;
    }

    private static double side(double[] plane, double[] p) {
        return plane[0] * p[0] + plane[1] * p[1] + plane[2] * p[2] + plane[3];
    }

    /**
     * Moves an eye-relative point along its own ray into the slab
     * {@code [d0, d0 + epsilon)} past the eye on the normal axis. The point's
     * place on screen does not change and the order of depths is kept, so the
     * far side draws in front of the source world behind the portal and behind
     * anything standing in front of it.
     */
    public static double squashFactor(double distanceOnNormal, double d0, double epsilon) {
        if (distanceOnNormal <= d0) {
            return 1.0;
        }
        double target = d0 + epsilon * (1.0 - d0 / distanceOnNormal);
        return target / distanceOnNormal;
    }

    /** Fog weight for a distance: 0 inside {@code start}, 1 at {@code end}, linear between. */
    public static double fog(double distance, double start, double end) {
        if (distance <= start) {
            return 0;
        }
        if (distance >= end) {
            return 1;
        }
        return (distance - start) / (end - start);
    }

    /** Mixes two RGB colours, {@code t} of the way from the first to the second. */
    public static int mix(int rgb, int towards, double t) {
        int r = (int) Math.round(((rgb >> 16) & 255) * (1 - t) + ((towards >> 16) & 255) * t);
        int g = (int) Math.round(((rgb >> 8) & 255) * (1 - t) + ((towards >> 8) & 255) * t);
        int b = (int) Math.round((rgb & 255) * (1 - t) + (towards & 255) * t);
        return (r << 16) | (g << 8) | b;
    }

    /** Multiplies an RGB colour by a factor, clamped per channel. */
    public static int scale(int rgb, double f) {
        int r = (int) Math.min(255, Math.round(((rgb >> 16) & 255) * f));
        int g = (int) Math.min(255, Math.round(((rgb >> 8) & 255) * f));
        int b = (int) Math.min(255, Math.round((rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }
}
