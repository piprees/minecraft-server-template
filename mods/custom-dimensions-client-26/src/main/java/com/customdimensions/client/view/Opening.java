package com.customdimensions.client.view;

import java.util.ArrayList;
import java.util.List;

/**
 * One portal opening as plain numbers: the axis its normal runs along, the
 * block coordinate of its cells on that axis, and its rectangle on the two
 * in-plane axes. Axes are 0 = x, 1 = y, 2 = z. No Minecraft types, so every
 * rule here is tested.
 *
 * <p>The far side is drawn on a surface just inside the cell's face nearest
 * the eye, in front of the portal block's own pane, so the pane fails the
 * depth test wherever the far side is drawn and shows wherever it is not.
 */
public record Opening(int normalAxis, int plane, double a0, double a1, double b0, double b1) {
    /** How far inside the cell's near face the drawing surface sits, in blocks. */
    public static final double INSET = 0.02;

    /**
     * From the cells and the portal's own axis ordinal (0 = x, 1 = y, 2 = z, as
     * the server sends it): an x-axis portal faces along z, a z-axis portal
     * along x, a flat one along y.
     */
    public static Opening of(List<int[]> cells, int portalAxis) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("an opening has at least one cell");
        }
        int n = switch (portalAxis) {
            case 0 -> 2;
            case 2 -> 0;
            case 1 -> 1;
            default -> throw new IllegalArgumentException("portal axis " + portalAxis);
        };
        int a = aAxis(n);
        int b = bAxis(n);
        double a0 = Double.MAX_VALUE;
        double a1 = -Double.MAX_VALUE;
        double b0 = Double.MAX_VALUE;
        double b1 = -Double.MAX_VALUE;
        for (int[] c : cells) {
            a0 = Math.min(a0, c[a]);
            a1 = Math.max(a1, c[a] + 1.0);
            b0 = Math.min(b0, c[b]);
            b1 = Math.max(b1, c[b] + 1.0);
        }
        return new Opening(n, cells.get(0)[n], a0, a1, b0, b1);
    }

    /** The first in-plane axis: x, unless the normal is x, then z. */
    public static int aAxis(int normalAxis) {
        return normalAxis == 0 ? 2 : 0;
    }

    /** The second in-plane axis: y for an upright opening, z for a flat one. */
    public static int bAxis(int normalAxis) {
        return normalAxis == 1 ? 2 : 1;
    }

    /** Whether the far side lies towards higher coordinates for an eye at this normal coordinate. */
    public boolean towardsHigh(double eyeN) {
        return eyeN < plane + 0.5;
    }

    /** The normal coordinate of the drawing surface. */
    public double surface(boolean high) {
        return high ? plane + INSET : plane + 1 - INSET;
    }

    /** The first layer of cells drawn: one past the opening's own, so the far portal is never drawn. */
    public int firstLayer(boolean high) {
        return high ? plane + 1 : plane - 1;
    }

    /** Whether an eye stands in the opening's own cell layer and rectangle, where nothing is drawn. */
    public boolean contains(double[] eye) {
        double n = eye[normalAxis];
        double a = eye[aAxis(normalAxis)];
        double b = eye[bAxis(normalAxis)];
        return n >= plane && n <= plane + 1 && a >= a0 && a <= a1 && b >= b0 && b <= b1;
    }

    /** A world point from coordinates on this opening's axes. */
    public double[] point(double a, double b, double n) {
        double[] p = new double[3];
        p[aAxis(normalAxis)] = a;
        p[bAxis(normalAxis)] = b;
        p[normalAxis] = n;
        return p;
    }

    /**
     * The half-spaces the far side is kept in, each {nx, ny, nz, d} with the
     * kept side {@code n·p + d >= 0}: four through the eye and the rectangle's
     * edges on the surface, then the surface itself, keeping what lies beyond it.
     */
    public double[][] clipPlanes(double[] eye) {
        boolean high = towardsHigh(eye[normalAxis]);
        double s = surface(high);
        double[][] corners = {point(a0, b0, s), point(a1, b0, s), point(a1, b1, s), point(a0, b1, s)};
        double[] mid = point((a0 + a1) / 2, (b0 + b1) / 2, s);
        double[][] planes = new double[5][];
        for (int i = 0; i < 4; i++) {
            double[] u = sub(corners[i], eye);
            double[] v = sub(corners[(i + 1) % 4], eye);
            double[] nrm = cross(u, v);
            double d = -dot(nrm, eye);
            if (dot(nrm, mid) + d < 0) {
                nrm = new double[] {-nrm[0], -nrm[1], -nrm[2]};
                d = -d;
            }
            planes[i] = new double[] {nrm[0], nrm[1], nrm[2], d};
        }
        double[] surfaceNormal = new double[3];
        surfaceNormal[normalAxis] = high ? 1 : -1;
        planes[4] = new double[] {surfaceNormal[0], surfaceNormal[1], surfaceNormal[2], high ? -s : s};
        return planes;
    }

    /**
     * The cells worth reading for this eye, as {x, y, z}: every layer from
     * {@link #firstLayer} to {@code depth} layers on, each holding the cone's
     * cross-section through that layer widened by one cell and clamped to
     * {@code radius} cells round the opening's centre.
     */
    public List<int[]> cellsInCone(double[] eye, int depth, int radius) {
        boolean high = towardsHigh(eye[normalAxis]);
        int step = high ? 1 : -1;
        double s = surface(high);
        double eyeN = eye[normalAxis];
        double eyeA = eye[aAxis(normalAxis)];
        double eyeB = eye[bAxis(normalAxis)];
        double d0 = Math.abs(s - eyeN);
        double midA = (a0 + a1) / 2;
        double midB = (b0 + b1) / 2;
        List<int[]> out = new ArrayList<>();
        if (d0 < 1e-6) {
            return out;
        }
        int first = firstLayer(high);
        for (int i = 0; i < depth; i++) {
            int layer = first + i * step;
            double near = Math.abs((high ? layer : layer + 1) - eyeN);
            double far = Math.abs((high ? layer + 1 : layer) - eyeN);
            double kn = near / d0;
            double kf = far / d0;
            double la = Math.min(project(eyeA, a0, kn), project(eyeA, a0, kf));
            double ha = Math.max(project(eyeA, a1, kn), project(eyeA, a1, kf));
            double lb = Math.min(project(eyeB, b0, kn), project(eyeB, b0, kf));
            double hb = Math.max(project(eyeB, b1, kn), project(eyeB, b1, kf));
            int ia0 = (int) Math.max(Math.floor(la) - 1, Math.floor(midA) - radius);
            int ia1 = (int) Math.min(Math.floor(ha) + 1, Math.floor(midA) + radius);
            int ib0 = (int) Math.max(Math.floor(lb) - 1, Math.floor(midB) - radius);
            int ib1 = (int) Math.min(Math.floor(hb) + 1, Math.floor(midB) + radius);
            for (int ca = ia0; ca <= ia1; ca++) {
                for (int cb = ib0; cb <= ib1; cb++) {
                    int[] cell = new int[3];
                    cell[aAxis(normalAxis)] = ca;
                    cell[bAxis(normalAxis)] = cb;
                    cell[normalAxis] = layer;
                    out.add(cell);
                }
            }
        }
        return out;
    }

    private static double project(double eye, double edge, double k) {
        return eye + (edge - eye) * k;
    }

    static double[] sub(double[] p, double[] q) {
        return new double[] {p[0] - q[0], p[1] - q[1], p[2] - q[2]};
    }

    static double[] cross(double[] u, double[] v) {
        return new double[] {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
    }

    static double dot(double[] u, double[] v) {
        return u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
    }
}
