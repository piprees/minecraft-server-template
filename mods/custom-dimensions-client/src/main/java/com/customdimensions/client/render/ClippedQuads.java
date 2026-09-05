package com.customdimensions.client.render;

/**
 * Quads caught four vertices at a time, cut against the opening, and handed on.
 *
 * <p>The rule half of {@link ClippedConsumers}, with no Minecraft types in it:
 * a vertex is {@link QuadCapture#STRIDE} floats with position first, which is
 * what the clip needs and all it needs.
 */
public final class ClippedQuads {

    private static final int STRIDE = QuadCapture.STRIDE;
    private static final int POLY = STRIDE * AperturePlanes.MAX_POLY;

    /** Where the survivors go. {@link #begin} opens one clipped polygon. */
    public interface Sink {
        void begin();

        void vertex(float[] data, int at);
    }

    private final AperturePlanes planes;
    private final Sink sink;
    private final float[] quad = new float[STRIDE * 4];
    private final float[] poly = new float[POLY];
    private final float[] scratch = new float[POLY];

    private int corners;
    private int quadsIn;
    private int quadsOut;

    public ClippedQuads(AperturePlanes planes, Sink sink) {
        this.planes = planes;
        this.sink = sink;
    }

    /** One finished vertex, {@code STRIDE} floats from {@code at}. */
    public void add(float[] vertex, int at) {
        System.arraycopy(vertex, at, this.quad, this.corners * STRIDE, STRIDE);
        this.corners++;
        if (this.corners == 4) {
            this.corners = 0;
            emit();
        }
    }

    /** Drops a quad left part-written rather than reading past its end. */
    public void flush() {
        this.corners = 0;
    }

    /** Quads offered, whether or not the opening let them through. */
    public int quadsIn() {
        return this.quadsIn;
    }

    /** Quads that reached the sink, whole or trimmed. */
    public int quadsOut() {
        return this.quadsOut;
    }

    private void emit() {
        this.quadsIn++;
        System.arraycopy(this.quad, 0, this.poly, 0, STRIDE * 4);
        int kept = this.planes.clipAll(this.poly, 4, this.scratch);
        if (kept < 3) {
            return;
        }
        this.quadsOut++;
        this.sink.begin();
        if (kept == 4) {
            for (int v = 0; v < 4; v++) {
                this.sink.vertex(this.poly, v * STRIDE);
            }
            return;
        }
        // A fan of degenerate quads renders a clipped polygon as triangles
        // without a second draw mode.
        for (int v = 1; v + 1 < kept; v++) {
            this.sink.vertex(this.poly, 0);
            this.sink.vertex(this.poly, v * STRIDE);
            this.sink.vertex(this.poly, (v + 1) * STRIDE);
            this.sink.vertex(this.poly, (v + 1) * STRIDE);
        }
    }
}
