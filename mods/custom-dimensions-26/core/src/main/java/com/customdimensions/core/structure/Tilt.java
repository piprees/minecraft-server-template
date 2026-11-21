package com.customdimensions.core.structure;

import java.util.Set;

/**
 * A template tilted off level (a wreck's {@code tilt}): {@code nose} pitches
 * it about its short horizontal axis so its far end on the long axis goes
 * down; {@code side} rolls it about its long axis. The turn is about the
 * centre of the template's floor, and each block of the tilted volume takes
 * the block of the level template nearest its turned-back position, so the
 * tilted hull has no gaps. Game-free; positions are relative to the
 * template's box corner after its rotation and mirror.
 */
public record Tilt(String kind, double angle, int sx, int sy, int sz) {

    public static final Set<String> KINDS = Set.of("none", "nose", "side");
    public static final double NOSE = 35;
    public static final double SIDE = 25;

    /** The tilt {@code kind} at {@code angle} degrees (the kind's default when null) of a box {@code sx} by {@code sy} by {@code sz}. */
    public static Tilt of(String kind, Double angle, int sx, int sy, int sz) {
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("tilt `" + kind + "` must be one of " + KINDS);
        double a = angle != null ? angle : "nose".equals(kind) ? NOSE : "side".equals(kind) ? SIDE : 0;
        return new Tilt(kind, a, sx, sy, sz);
    }

    /** Whether the long axis is z (else x). */
    boolean longZ() {
        return sz >= sx;
    }

    private double cx() {
        return (sx - 1) / 2.0;
    }

    private double cz() {
        return (sz - 1) / 2.0;
    }

    /**
     * {@code p} (x, y, z relative to the box corner) turned by the tilt;
     * {@code inverse} turns it back. A nose tilt lowers the far end of the long
     * axis; a side tilt lowers the +x side (or the +z side of a long-x box).
     */
    public double[] turn(double x, double y, double z, boolean inverse) {
        double a = Math.toRadians("none".equals(kind) ? 0 : angle) * (inverse ? -1 : 1);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double px = x - cx();
        double pz = z - cz();
        boolean pitchOnZ = "nose".equals(kind) == longZ();
        if (pitchOnZ) {
            return new double[] {x, y * c - pz * s, cz() + pz * c + y * s};
        }
        return new double[] {cx() + px * c + y * s, y * c - px * s, z};
    }

    /** The tilted volume's bounds relative to the box corner: {minX, minY, minZ, maxX, maxY, maxZ}. */
    public int[] bounds() {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < 8; i++) {
            double[] p = turn((i & 1) == 0 ? 0 : sx - 1, (i & 2) == 0 ? 0 : sy - 1, (i & 4) == 0 ? 0 : sz - 1, false);
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], p[k]);
                hi[k] = Math.max(hi[k], p[k]);
            }
        }
        return new int[] {(int) Math.floor(lo[0]) - 1, (int) Math.floor(lo[1]) - 1, (int) Math.floor(lo[2]) - 1,
                (int) Math.ceil(hi[0]) + 1, (int) Math.ceil(hi[1]) + 1, (int) Math.ceil(hi[2]) + 1};
    }

    /** The level template's position whose block a tilted position {@code x, y, z} takes, or null when it is outside the template. */
    public int[] source(int x, int y, int z) {
        double[] p = turn(x, y, z, true);
        int qx = (int) Math.round(p[0]);
        int qy = (int) Math.round(p[1]);
        int qz = (int) Math.round(p[2]);
        if (qx < 0 || qy < 0 || qz < 0 || qx >= sx || qy >= sy || qz >= sz) return null;
        return new int[] {qx, qy, qz};
    }

    /** A level template's position turned by the tilt, rounded to a block. */
    public int[] target(int x, int y, int z) {
        double[] p = turn(x, y, z, false);
        return new int[] {(int) Math.round(p[0]), (int) Math.round(p[1]), (int) Math.round(p[2])};
    }

    /** How far the tilted box reaches: {blocks across from its centre, blocks up}. */
    public static int[] reach(String kind, Double angle, int sx, int sy, int sz) {
        Tilt t = of(kind, angle, sx, sy, sz);
        int[] b = t.bounds();
        int across = Math.max(Math.max(b[3] - b[0], b[5] - b[2]) / 2 + 1, (Math.max(sx, sz) + 1) / 2);
        return new int[] {across, Math.max(sy, b[4] - Math.min(0, b[1]))};
    }
}
