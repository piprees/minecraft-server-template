package com.customdimensions.client.render;

import net.minecraft.client.render.VertexConsumer;

/**
 * Catches what the block renderer emits instead of writing it to a buffer.
 *
 * <p>The mesh is built once per projection and clipped against the aperture
 * every frame, so the vertices have to survive the build — a
 * {@code BufferBuilder} would have been drawn and discarded.
 *
 * <p>{@link #vertex} starts a vertex; the attribute calls that follow belong
 * to it. Four of them make a quad. Fluids are rendered by vanilla at
 * chunk-relative coordinates, so {@link #setOffset} corrects them back into
 * the volume's own space.
 */
public final class QuadCapture implements VertexConsumer {

    /** x y z, r g b a, u v, overlayU overlayV, lightBlock lightSky, nx ny nz. */
    public static final int STRIDE = 16;
    private static final int QUAD = STRIDE * 4;

    private float[] data = new float[QUAD * 256];
    private int size;

    private final float[] current = new float[STRIDE];
    private boolean started;

    private float offsetX;
    private float offsetY;
    private float offsetZ;

    public void setOffset(float x, float y, float z) {
        this.offsetX = x;
        this.offsetY = y;
        this.offsetZ = z;
    }

    public float[] data() {
        return this.data;
    }

    public int quadCount() {
        commit();
        return this.size / QUAD;
    }

    public int floatCount() {
        commit();
        return this.size;
    }

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        commit();
        this.started = true;
        this.current[0] = x + this.offsetX;
        this.current[1] = y + this.offsetY;
        this.current[2] = z + this.offsetZ;
        this.current[3] = 1.0f;
        this.current[4] = 1.0f;
        this.current[5] = 1.0f;
        this.current[6] = 1.0f;
        this.current[7] = 0.0f;
        this.current[8] = 0.0f;
        this.current[9] = 0.0f;
        this.current[10] = 10.0f;
        this.current[11] = 15.0f;
        this.current[12] = 15.0f;
        this.current[13] = 0.0f;
        this.current[14] = 1.0f;
        this.current[15] = 0.0f;
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        this.current[3] = red / 255.0f;
        this.current[4] = green / 255.0f;
        this.current[5] = blue / 255.0f;
        this.current[6] = alpha / 255.0f;
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        this.current[7] = u;
        this.current[8] = v;
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        this.current[9] = u;
        this.current[10] = v;
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        // Kept unpacked: clipping interpolates the two channels, and a packed
        // lightmap coordinate cannot be interpolated as one number.
        this.current[11] = u >> 4;
        this.current[12] = v >> 4;
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        this.current[13] = x;
        this.current[14] = y;
        this.current[15] = z;
        return this;
    }

    private void commit() {
        if (!this.started) {
            return;
        }
        this.started = false;
        if (this.size + STRIDE > this.data.length) {
            float[] grown = new float[this.data.length * 2];
            System.arraycopy(this.data, 0, grown, 0, this.size);
            this.data = grown;
        }
        System.arraycopy(this.current, 0, this.data, this.size, STRIDE);
        this.size += STRIDE;
    }

    /** Drops a partly-emitted quad, which would otherwise read past its end. */
    public void finish() {
        commit();
        int ragged = this.size % QUAD;
        this.size -= ragged;
    }
}
