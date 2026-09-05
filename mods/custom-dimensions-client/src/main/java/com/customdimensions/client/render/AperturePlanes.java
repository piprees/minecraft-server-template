package com.customdimensions.client.render;

/**
 * The half-spaces a portal's opening frames, and the clip against them.
 *
 * <p>Four planes per clip rectangle, each through the camera and one edge,
 * oriented so that rectangle's own centre is on the kept side. What survives
 * every plane is the intersection of the cones — the sightline through a hole
 * with thickness rather than through a plane.
 *
 * <p>Every plane runs through the camera, so the cone has a mirror image behind
 * it. Geometry known to lie in front is safe; anything else needs
 * {@link #add} with the portal surface to cut the mirror away.
 *
 * <p>No Minecraft types: the polygons are flat float arrays of {@code stride}
 * per vertex, position first, so the whole clip is unit-testable.
 */
public final class AperturePlanes {

    /** Four corners plus one cut per plane. */
    public static final int MAX_POLY = 16;

    private final double[] planes;
    private final int stride;
    private int count;

    public AperturePlanes(int maxPlanes, int stride) {
        this.planes = new double[maxPlanes * 4];
        this.stride = stride;
    }

    /** How many planes {@link #build} and {@link #add} have left standing. */
    public int count() {
        return this.count;
    }

    /**
     * Four planes per rectangle in {@code rects}, which holds {@code count}
     * rectangles of four {@code x, y, z} corners walked so consecutive pairs are
     * edges. False — and no planes at all — when the camera is level with the
     * plane of a corner and the cone degenerates.
     */
    public boolean build(double[] rects, int rectCount, double camX, double camY, double camZ) {
        this.count = 0;
        if (rectCount <= 0) {
            return false;
        }
        for (int rect = 0; rect < rectCount; rect++) {
            int base = rect * 12;
            double cx = 0.0;
            double cy = 0.0;
            double cz = 0.0;
            for (int i = 0; i < 4; i++) {
                cx += rects[base + i * 3] / 4.0;
                cy += rects[base + i * 3 + 1] / 4.0;
                cz += rects[base + i * 3 + 2] / 4.0;
            }
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) & 3;
                double ax = rects[base + i * 3] - camX;
                double ay = rects[base + i * 3 + 1] - camY;
                double az = rects[base + i * 3 + 2] - camZ;
                double bx = rects[base + j * 3] - camX;
                double by = rects[base + j * 3 + 1] - camY;
                double bz = rects[base + j * 3 + 2] - camZ;
                double nx = ay * bz - az * by;
                double ny = az * bx - ax * bz;
                double nz = ax * by - ay * bx;
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 1.0e-9) {
                    this.count = 0;
                    return false;
                }
                nx /= length;
                ny /= length;
                nz /= length;
                double d = -(nx * camX + ny * camY + nz * camZ);
                if (nx * cx + ny * cy + nz * cz + d < 0.0) {
                    nx = -nx;
                    ny = -ny;
                    nz = -nz;
                    d = -d;
                }
                put(this.count++, nx, ny, nz, d);
            }
        }
        return true;
    }

    /**
     * One more half-space, keeping {@code n . p + d >= 0}. Refused when the set
     * is full, so a caller that adds one too many clips against what it has
     * rather than overwriting a cone plane.
     */
    public boolean add(double nx, double ny, double nz, double d) {
        if ((this.count + 1) * 4 > this.planes.length) {
            return false;
        }
        put(this.count++, nx, ny, nz, d);
        return true;
    }

    /**
     * The half-space beyond a plane at {@code coordinate} on one axis, which is
     * how the portal surface enters the set: {@code facing} is +1 when the
     * destination lies towards higher coordinates and -1 when it lies the other
     * way.
     */
    public boolean addAxisPlane(int axis, double coordinate, double facing) {
        return add(axis == 0 ? facing : 0.0, axis == 1 ? facing : 0.0,
                axis == 2 ? facing : 0.0, -facing * coordinate);
    }

    private void put(int plane, double nx, double ny, double nz, double d) {
        int at = plane * 4;
        this.planes[at] = nx;
        this.planes[at + 1] = ny;
        this.planes[at + 2] = nz;
        this.planes[at + 3] = d;
    }

    /** Sutherland-Hodgman against one plane; returns the new vertex count. */
    public int clip(float[] in, int vertices, float[] out, int plane) {
        double nx = this.planes[plane * 4];
        double ny = this.planes[plane * 4 + 1];
        double nz = this.planes[plane * 4 + 2];
        double d = this.planes[plane * 4 + 3];
        int written = 0;
        for (int i = 0; i < vertices; i++) {
            int a = i * this.stride;
            int b = ((i + 1) % vertices) * this.stride;
            double da = nx * in[a] + ny * in[a + 1] + nz * in[a + 2] + d;
            double db = nx * in[b] + ny * in[b + 1] + nz * in[b + 2] + d;
            if (da >= 0.0 && written < MAX_POLY) {
                System.arraycopy(in, a, out, written * this.stride, this.stride);
                written++;
            }
            if ((da >= 0.0) != (db >= 0.0) && written < MAX_POLY) {
                float t = (float) (da / (da - db));
                for (int e = 0; e < this.stride; e++) {
                    out[written * this.stride + e] = in[a + e] + (in[b + e] - in[a + e]) * t;
                }
                written++;
            }
        }
        return written;
    }

    /**
     * Every plane in turn, leaving the survivors in {@code poly}. Returns the
     * corner count, 0 for nothing left to draw.
     */
    public int clipAll(float[] poly, int vertices, float[] scratch) {
        int corners = vertices;
        for (int plane = 0; plane < this.count && corners >= 3; plane++) {
            corners = clip(poly, corners, scratch, plane);
            System.arraycopy(scratch, 0, poly, 0, corners * this.stride);
        }
        return corners < 3 ? 0 : corners;
    }
}
