package com.customdimensions.core.render;

/**
 * One image of a render, painted from its samples: the file is
 * {@code <seed>-r<radius>-<name>.png}. Zone outlines and structure starts
 * arrive as further layers, each drawn from data the sampler adds.
 */
public interface RenderLayer {
    String name();

    /** Row-major RGB pixels, {@code side × side}. */
    int[] paint(ColumnSamples samples);
}
