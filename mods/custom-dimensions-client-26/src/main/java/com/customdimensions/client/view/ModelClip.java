package com.customdimensions.client.view;

import java.util.ArrayList;
import java.util.List;

/**
 * An entity model's quads in the opening, on vertices whose first three values
 * are eye-relative {x, y, z} in world orientation and whose rest ride along
 * (uv, colour). They are clipped by the far side's own planes and squashed by
 * its own {@link Clip#squashFactor}, so a model keeps its depth order against
 * the far side's blocks and against the source world. No Minecraft types.
 */
public final class ModelClip {
    private ModelClip() {
    }

    /**
     * One polygon clipped to {@code planes} (world space, the eye at
     * {@code eye}) and squashed onto the slab {@code d0 .. d0 + epsilon} past the
     * eye on {@code normalAxis}; each result vertex is eye-relative again, with
     * its unsquashed distance from the eye appended. Empty when nothing survives.
     */
    public static List<double[]> clip(double[][] polygon, double[][] planes, double[] eye, int normalAxis, double d0,
                                      double epsilon) {
        List<double[]> world = new ArrayList<>(polygon.length);
        for (double[] v : polygon) {
            double[] w = v.clone();
            w[0] += eye[0];
            w[1] += eye[1];
            w[2] += eye[2];
            world.add(w);
        }
        List<double[]> kept = Clip.clip(world, planes);
        if (kept.size() < 3) {
            return List.of();
        }
        List<double[]> out = new ArrayList<>(kept.size());
        for (double[] w : kept) {
            double rx = w[0] - eye[0];
            double ry = w[1] - eye[1];
            double rz = w[2] - eye[2];
            double along = Math.abs(normalAxis == 0 ? rx : normalAxis == 1 ? ry : rz);
            double q = Clip.squashFactor(along, d0, epsilon);
            double[] r = new double[w.length + 1];
            System.arraycopy(w, 0, r, 0, w.length);
            r[0] = rx * q;
            r[1] = ry * q;
            r[2] = rz * q;
            r[w.length] = Math.sqrt(rx * rx + ry * ry + rz * rz);
            out.add(r);
        }
        return out;
    }

    /**
     * Where a destination position is drawn on the source side: the frame's
     * offset subtracted, never divided (the server already spent the portal's
     * scale deriving it), so the player's own eye is the camera on the far side.
     */
    public static double[] source(double[] destination, int dx, int dy, int dz) {
        return new double[] {destination[0] - dx, destination[1] - dy, destination[2] - dz};
    }

    /** The bounding sphere an entity of this size standing at {@code feet} is culled by: centre x, y, z and radius. */
    public static double[] bounds(double[] feet, double width, double height) {
        return new double[] {feet[0], feet[1] + height / 2, feet[2], Math.max(width, height) + 1.0};
    }

    /** Whether a sphere lies wholly outside any one of the half-spaces, so nothing of it can survive the clip. */
    public static boolean outside(double[][] planes, double[] centre, double radius) {
        for (double[] p : planes) {
            double len = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            if (len == 0) {
                continue;
            }
            double d = (p[0] * centre[0] + p[1] * centre[1] + p[2] * centre[2] + p[3]) / len;
            if (d < -radius) {
                return true;
            }
        }
        return false;
    }

    /**
     * A polygon of {@code m} vertices as quads, by vertex index: a fan from the
     * first vertex two triangles at a time, the last quad of an odd fan closing
     * on a repeated vertex (the far side's own blocks are emitted the same way).
     */
    public static int[][] quads(int m) {
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i + 1 < m; i += 2) {
            out.add(new int[] {0, i, i + 1, Math.min(i + 2, m - 1)});
        }
        return out.toArray(new int[0][]);
    }
}
