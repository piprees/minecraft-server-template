package com.customdimensions.client.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClipConsumerTest {
    /** Every vertex the consumer passes on, as {x, y, z, r, g, b, a, u, v, overlay, light}. */
    static final class Recorder implements VertexConsumer {
        final List<double[]> vertices = new ArrayList<>();

        double[] last() {
            return vertices.get(vertices.size() - 1);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new double[] {x, y, z, 0, 0, 0, 0, 0, 0, 0, 0});
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            last()[3] = r;
            last()[4] = g;
            last()[5] = b;
            last()[6] = a;
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            return setColor((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, argb >>> 24);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            last()[7] = u;
            last()[8] = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            last()[9] = (u & 0xFFFF) | (v << 16);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            last()[10] = (u & 0xFFFF) | (v << 16);
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float w) {
            return this;
        }
    }

    static final double[] EYE = ModelClipTest.EYE;

    static ClipConsumer consumer(Recorder out, int fog) {
        return new ClipConsumer(out, new PoseStack().last(), ModelClipTest.OP.clipPlanes(EYE), EYE, 2,
                ModelClipTest.d0(), PortalRenderer.EPSILON, fog, 10, 40);
    }

    /** One eye-relative quad at world z, 0.4 wide round the opening's centre line. */
    static float[][] quad(double z) {
        return new float[][] {{-0.2f, -0.3f, (float) (z - EYE[2])}, {0.2f, -0.3f, (float) (z - EYE[2])},
                {0.2f, 0.3f, (float) (z - EYE[2])}, {-0.2f, 0.3f, (float) (z - EYE[2])}};
    }

    @Test
    void theBulkAndBuilderVertexFormsGiveTheSameQuad() {
        Recorder a = new Recorder();
        ClipConsumer bulk = consumer(a, 0x000000);
        for (float[] p : quad(20)) {
            bulk.addVertex(p[0], p[1], p[2], 0xFF808080, 0.5f, 0.25f, 7 << 16, 0xF000F0, 0, 0, -1);
        }
        bulk.finish();
        Recorder b = new Recorder();
        ClipConsumer builder = consumer(b, 0x000000);
        for (float[] p : quad(20)) {
            builder.addVertex(p[0], p[1], p[2]).setColor(128, 128, 128, 255).setUv(0.5f, 0.25f).setUv1(0, 7)
                    .setUv2(0xF0, 0xF0).setNormal(0, 0, -1);
        }
        builder.finish();
        assertEquals(4, a.vertices.size());
        assertEquals(1, bulk.quadsIn);
        assertEquals(1, bulk.quadsOut);
        for (int i = 0; i < 4; i++) {
            for (int k = 0; k < 11; k++) {
                assertEquals(a.vertices.get(i)[k], b.vertices.get(i)[k], 1e-5, "vertex " + i + " field " + k);
            }
        }
        double[] v = a.vertices.get(0);
        assertTrue(v[2] >= ModelClipTest.d0() && v[2] < ModelClipTest.d0() + PortalRenderer.EPSILON, "squashed");
        assertEquals(0.5, v[7], 1e-6);
        assertEquals(7 << 16, (int) v[9], "the overlay is kept");
        assertEquals(0xF000F0, (int) v[10], "the light is kept");
        assertEquals(255, v[6], "the alpha is kept");
    }

    @Test
    void consecutiveQuadsAreSplitAtEveryFourthVertexAndTheFogDeepens() {
        Recorder out = new Recorder();
        ClipConsumer c = consumer(out, 0x0000FF);
        for (double z : new double[] {12, 60}) {
            for (float[] p : quad(z)) {
                c.addVertex(p[0], p[1], p[2], 0xFFFF0000, 0, 0, 0, 0, 0, 0, -1);
            }
        }
        c.finish();
        assertEquals(2, c.quadsIn);
        assertEquals(2, c.quadsOut);
        assertEquals(8, out.vertices.size());
        assertEquals(255, out.vertices.get(0)[3], "near: no fog");
        assertEquals(0, out.vertices.get(0)[5]);
        assertEquals(0, out.vertices.get(4)[3], "past the fog's end: all fog");
        assertEquals(255, out.vertices.get(4)[5]);
    }

    @Test
    void aQuadTheOpeningDoesNotShowPassesNothing() {
        Recorder out = new Recorder();
        ClipConsumer c = consumer(out, 0);
        for (float[] p : quad(8)) {
            c.addVertex(p[0], p[1], p[2], -1, 0, 0, 0, 0, 0, 0, -1);
        }
        c.finish();
        assertEquals(1, c.quadsIn);
        assertEquals(0, c.quadsOut);
        assertTrue(out.vertices.isEmpty());
    }

    @Test
    void anUnfinishedQuadIsDropped() {
        Recorder out = new Recorder();
        ClipConsumer c = consumer(out, 0);
        float[][] q = quad(20);
        for (int i = 0; i < 3; i++) {
            c.addVertex(q[i][0], q[i][1], q[i][2], -1, 0, 0, 0, 0, 0, 0, -1);
        }
        c.finish();
        assertEquals(0, c.quadsIn);
        assertTrue(out.vertices.isEmpty());
    }
}
