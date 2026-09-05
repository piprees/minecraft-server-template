package com.customdimensions.client.render;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;

import java.util.HashMap;
import java.util.Map;

/**
 * A vertex sink that cuts everything drawn through it to the portal opening.
 *
 * <p>1.21.1's framebuffer carries no stencil attachment, so a model drawn by a
 * vanilla dispatcher cannot be masked by the hardware. It is masked here
 * instead: each quad is caught, clipped against the opening's own half-spaces
 * by {@link ClippedQuads}, and only the survivors reach the buffer underneath.
 * That is the cut the meshed terrain gets, applied to geometry whose vertices
 * this code never writes.
 *
 * <p>Vertices arrive already transformed by the caller's matrix stack, so the
 * planes must be in that same space — camera-relative, for a stack translated
 * by {@code origin - camera}.
 *
 * <p>A layer that is not drawn as quads is discarded rather than passed
 * through: four vertices at a time is what makes a quad here, and letting an
 * unrecognised layer past would paint outside the frame.
 */
public final class ClippedConsumers implements VertexConsumerProvider {

    private static final int STRIDE = QuadCapture.STRIDE;

    private final VertexConsumerProvider delegate;
    private final AperturePlanes planes;
    private final Map<RenderLayer, Clipped> buffers = new HashMap<>();

    /** The layer part-way through a quad, which nothing else may interrupt. */
    private Clipped open;

    private int layersRefused;

    public ClippedConsumers(VertexConsumerProvider delegate, AperturePlanes planes) {
        this.delegate = delegate;
        this.planes = planes;
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        Clipped clipped = this.buffers.computeIfAbsent(layer, Clipped::new);
        if (this.open != null && this.open != clipped) {
            this.open.flush();
        }
        this.open = clipped;
        return clipped;
    }

    /** Commits the vertex in hand. Nothing is drawn until this has run. */
    public void flush() {
        if (this.open != null) {
            this.open.flush();
            this.open = null;
        }
    }

    /** Quads offered, whether or not the opening let them through. */
    public int quadsIn() {
        int total = 0;
        for (Clipped clipped : this.buffers.values()) {
            total += clipped.quads.quadsIn();
        }
        return total;
    }

    /** Quads that reached a buffer, whole or trimmed. */
    public int quadsOut() {
        int total = 0;
        for (Clipped clipped : this.buffers.values()) {
            total += clipped.quads.quadsOut();
        }
        return total;
    }

    /** Layers dropped for not being drawn as quads. */
    public int layersRefused() {
        return this.layersRefused;
    }

    /**
     * One layer's clip. Attributes arrive after the {@code vertex} call they
     * belong to, so a vertex is committed when the next one starts and by
     * {@link #flush} at the end.
     */
    private final class Clipped implements VertexConsumer, ClippedQuads.Sink {

        private final RenderLayer layer;
        private final boolean drawsQuads;
        private final ClippedQuads quads;
        private final float[] current = new float[STRIDE];

        private boolean started;
        private VertexConsumer out;

        private Clipped(RenderLayer layer) {
            this.layer = layer;
            this.drawsQuads = layer.getDrawMode() == VertexFormat.DrawMode.QUADS;
            this.quads = new ClippedQuads(planes, this);
            if (!this.drawsQuads) {
                layersRefused++;
            }
        }

        @Override
        public void begin() {
            this.out = delegate.getBuffer(this.layer);
        }

        @Override
        public void vertex(float[] data, int at) {
            this.out.vertex(data[at], data[at + 1], data[at + 2])
                    .color(data[at + 3], data[at + 4], data[at + 5], data[at + 6])
                    .texture(data[at + 7], data[at + 8])
                    .overlay((int) data[at + 9], (int) data[at + 10])
                    .light(((int) data[at + 11]) << 4, ((int) data[at + 12]) << 4)
                    .normal(data[at + 13], data[at + 14], data[at + 15]);
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            commit();
            this.started = true;
            this.current[0] = x;
            this.current[1] = y;
            this.current[2] = z;
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
            // Unpacked, because the clip interpolates the two channels and a
            // packed lightmap coordinate cannot be interpolated as one number.
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
            if (this.drawsQuads) {
                this.quads.add(this.current, 0);
            }
        }

        private void flush() {
            commit();
            this.quads.flush();
        }
    }
}
