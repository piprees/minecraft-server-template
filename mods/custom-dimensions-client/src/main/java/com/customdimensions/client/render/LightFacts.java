package com.customdimensions.client.render;

/**
 * Light as a number rather than an impression.
 *
 * <p>Two channels kept apart: a destination with sky 15 and block 0 and one
 * with block 15 and sky 0 photograph alike and are different worlds. {@code
 * cells} is the denominator and is always printed — a mean of zero over a grid
 * nobody sampled reads exactly like a dark one (TROUBLESHOOTING.md#t63).
 */
public record LightFacts(int cells, int lit, int blockMin, int blockMax, int blockSum,
        int skyMin, int skyMax, int skySum) {

    /** Nothing sampled. Distinct from every grid that was. */
    public static final LightFacts EMPTY = new LightFacts(0, 0, 0, 0, 0, 0, 0, 0);

    /** The server's packed {@code sky << 4 | block}, one byte per described cell. */
    public static LightFacts ofPacked(byte[] packed) {
        if (packed == null || packed.length == 0) {
            return EMPTY;
        }
        Accumulator sum = new Accumulator();
        for (byte value : packed) {
            int unsigned = value & 0xFF;
            sum.add(unsigned & 0xF, (unsigned >> 4) & 0xF);
        }
        return sum.facts();
    }

    /**
     * One mesh layer's vertices: block light at {@code stride * i + 11}, sky at
     * {@code + 12}, as {@link QuadCapture#light} stored them.
     */
    public static LightFacts ofVertices(float[] data, int floats, int stride) {
        if (data == null || floats < stride || stride < 13) {
            return EMPTY;
        }
        Accumulator sum = new Accumulator();
        for (int at = 0; at + stride <= Math.min(floats, data.length); at += stride) {
            sum.add((int) data[at + 11], (int) data[at + 12]);
        }
        return sum.facts();
    }

    public double blockMean() {
        return this.cells == 0 ? 0.0 : (double) this.blockSum / this.cells;
    }

    public double skyMean() {
        return this.cells == 0 ? 0.0 : (double) this.skySum / this.cells;
    }

    /** One line for a log, denominator first. */
    public String label() {
        if (this.cells == 0) {
            return "cells=0";
        }
        return String.format("cells=%d lit=%d block=%d..%d/%.1f sky=%d..%d/%.1f",
                this.cells, this.lit, this.blockMin, this.blockMax, blockMean(),
                this.skyMin, this.skyMax, skyMean());
    }

    private static final class Accumulator {
        private int cells;
        private int lit;
        private int blockMin = Integer.MAX_VALUE;
        private int blockMax = Integer.MIN_VALUE;
        private int blockSum;
        private int skyMin = Integer.MAX_VALUE;
        private int skyMax = Integer.MIN_VALUE;
        private int skySum;

        void add(int block, int sky) {
            this.cells++;
            if (block > 0 || sky > 0) {
                this.lit++;
            }
            this.blockMin = Math.min(this.blockMin, block);
            this.blockMax = Math.max(this.blockMax, block);
            this.blockSum += block;
            this.skyMin = Math.min(this.skyMin, sky);
            this.skyMax = Math.max(this.skyMax, sky);
            this.skySum += sky;
        }

        LightFacts facts() {
            if (this.cells == 0) {
                return EMPTY;
            }
            return new LightFacts(this.cells, this.lit, this.blockMin, this.blockMax, this.blockSum,
                    this.skyMin, this.skyMax, this.skySum);
        }
    }
}
