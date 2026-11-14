package com.customdimensions.client.view;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;

/**
 * Takes an entity model's vertices (eye-relative, world orientation: the model
 * was posed on an identity stack) a quad at a time, clips and squashes each
 * quad with {@link ModelClip}, mixes in the destination fog by distance as the
 * far side's blocks do, and passes what survives to the real consumer through
 * the submit's own pose. Takes both the eleven-argument vertex and the builder
 * calls; a quad is complete when its fourth vertex is followed by another, or by
 * {@link #finish}.
 */
final class ClipConsumer implements VertexConsumer {
    private static final int X = 0;
    private static final int U = 3;
    private static final int A = 5;
    private static final int STRIDE = 9;

    private final VertexConsumer out;
    private final PoseStack.Pose pose;
    private final double[][] planes;
    private final double[] eye;
    private final int normalAxis;
    private final double d0;
    private final double epsilon;
    private final int fog;
    private final double fogStart;
    private final double fogEnd;

    private final double[][] quad = new double[4][STRIDE];
    private final int[] overlay = new int[4];
    private final int[] light = new int[4];
    private final float[][] normal = new float[4][3];
    private int count;
    int quadsIn;
    int quadsOut;

    ClipConsumer(VertexConsumer out, PoseStack.Pose pose, double[][] planes, double[] eye, int normalAxis, double d0,
                 double epsilon, int fog, double fogStart, double fogEnd) {
        this.out = out;
        this.pose = pose;
        this.planes = planes;
        this.eye = eye;
        this.normalAxis = normalAxis;
        this.d0 = d0;
        this.epsilon = epsilon;
        this.fog = fog;
        this.fogStart = fogStart;
        this.fogEnd = fogEnd;
    }

    private int begin(float x, float y, float z) {
        if (count == 4) {
            emit();
        }
        int i = count++;
        double[] v = quad[i];
        v[X] = x;
        v[X + 1] = y;
        v[X + 2] = z;
        v[U] = 0;
        v[U + 1] = 0;
        v[A] = 255;
        v[A + 1] = 255;
        v[A + 2] = 255;
        v[A + 3] = 255;
        overlay[i] = 0;
        light[i] = 0;
        normal[i][0] = 0;
        normal[i][1] = 1;
        normal[i][2] = 0;
        return i;
    }

    private int current() {
        return Math.max(0, count - 1);
    }

    /** Emits a complete last quad; call once the model has been rendered. */
    void finish() {
        if (count == 4) {
            emit();
        }
        count = 0;
    }

    private void emit() {
        count = 0;
        quadsIn++;
        List<double[]> poly = ModelClip.clip(quad, planes, eye, normalAxis, d0, epsilon);
        if (poly.isEmpty()) {
            return;
        }
        quadsOut++;
        int dist = STRIDE;
        for (int[] q : ModelClip.quads(poly.size())) {
            for (int k : q) {
                double[] v = poly.get(k);
                int a = (int) Math.round(v[A]);
                int rgb = ((int) Math.round(v[A + 1]) << 16) | ((int) Math.round(v[A + 2]) << 8) | (int) Math.round(v[A + 3]);
                rgb = Clip.mix(rgb, fog, Clip.fog(v[dist], fogStart, fogEnd));
                out.addVertex(pose, (float) v[X], (float) v[X + 1], (float) v[X + 2])
                        .setColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Math.max(0, Math.min(255, a)))
                        .setUv((float) v[U], (float) v[U + 1])
                        .setOverlay(overlay[0])
                        .setLight(light[0])
                        .setNormal(pose, normal[0][0], normal[0][1], normal[0][2]);
            }
        }
    }

    @Override
    public void addVertex(float x, float y, float z, int colour, float u, float v, int overlayCoords, int lightCoords,
                          float nx, float ny, float nz) {
        int i = begin(x, y, z);
        setArgb(i, colour);
        quad[i][U] = u;
        quad[i][U + 1] = v;
        overlay[i] = overlayCoords;
        light[i] = lightCoords;
        normal[i][0] = nx;
        normal[i][1] = ny;
        normal[i][2] = nz;
    }

    private void setArgb(int i, int argb) {
        quad[i][A] = (argb >>> 24) & 255;
        quad[i][A + 1] = (argb >> 16) & 255;
        quad[i][A + 2] = (argb >> 8) & 255;
        quad[i][A + 3] = argb & 255;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        begin(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        int i = current();
        quad[i][A] = a;
        quad[i][A + 1] = r;
        quad[i][A + 2] = g;
        quad[i][A + 3] = b;
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        setArgb(current(), argb);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        int i = current();
        quad[i][U] = u;
        quad[i][U + 1] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        overlay[current()] = (u & 0xFFFF) | (v << 16);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        light[current()] = (u & 0xFFFF) | (v << 16);
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float nx, float ny, float nz) {
        int i = current();
        normal[i][0] = nx;
        normal[i][1] = ny;
        normal[i][2] = nz;
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }
}
