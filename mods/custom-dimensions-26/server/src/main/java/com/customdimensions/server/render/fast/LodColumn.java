package com.customdimensions.server.render.fast;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * A column's runs as the contiguous vertical segments a level-of-detail store
 * takes, topmost first, from the level's top to its floor: open space above
 * each run (air, and the run's fluid up to its level), the run's top block,
 * its body in the default block, and its underside block when it was
 * dressed. Each segment carries the light of the block above it, as a store
 * built from chunks records it: sky light 15 over the topmost run and in a
 * tall open band under a run of an unroofed column ({@link #OPEN_BAND}
 * blocks or more: sky tiers, islands over a void), 0 elsewhere and in a
 * level without sky light, less one per block of fluid; block light is the
 * emission of the block above. {@code T} is the block type, {@code B} the
 * biome's. Game-free.
 */
public final class LodColumn {
    /** An open band under a run at least this tall is lit by the sky from its sides; a shorter one is a cave. */
    public static final int OPEN_BAND = 16;
    public static final int MAX_LIGHT = 15;

    /** Blocks {@code bottom} (inclusive) to {@code top} (exclusive), in level coordinates. */
    public record Segment<T, B>(int bottom, int top, T block, B biome, int skyLight, int blockLight) {}

    /** What a column is dressed and lit with. */
    public record Palette<T>(T air, T body, ToIntFunction<T> emission) {}

    private LodColumn() {}

    /**
     * Appends the segments of {@code runs} to {@code out}, topmost first,
     * covering {@code minY} to {@code top} (exclusive) without a gap.
     *
     * @param biomes  each run's biome, index as the runs (its open space and fluid take it too); for a column
     *                without runs, one entry: the column's
     * @param roofed  whether the runs' topmost is a roof: no sky light reaches under it
     * @param skylit  whether the level has sky light
     */
    public static <T, B> void segments(ColumnRuns runs, RunDress<T> dress, List<B> biomes, int minY, int top, boolean roofed,
                                       boolean skylit, Palette<T> palette, List<Segment<T, B>> out) {
        int cursor = top;
        B above = biomes.isEmpty() ? null : biomes.get(0);
        for (int i = 0; i < runs.count(); i++) {
            int runTop = runs.top(i);
            int runBottom = runs.bottom(i);
            B biome = biomes.get(i);
            int light = i == 0 ? (skylit && !roofed ? MAX_LIGHT : 0) : openLight(cursor - runTop - 1, skylit, roofed);
            int faceSky = light;
            int faceBlock = 0;
            T fluid = dress.fluid(i);
            int fluidTop = Math.min(dress.fluidLevel(i), cursor);
            if (fluid != null && fluidTop > runTop + 1) {
                add(out, fluidTop, cursor, palette.air(), biome, light, 0);
                add(out, runTop + 1, fluidTop, fluid, biome, light, 0);
                faceSky = Math.max(0, light - (fluidTop - runTop - 1));
                faceBlock = palette.emission().applyAsInt(fluid);
            } else {
                add(out, runTop + 1, cursor, palette.air(), biome, light, 0);
            }
            T topBlock = dress.top(i);
            add(out, runTop, runTop + 1, topBlock, biome, faceSky, faceBlock);
            T under = dress.bottom(i);
            int bodyBottom = under != null && runTop > runBottom ? runBottom + 1 : runBottom;
            add(out, bodyBottom, runTop, palette.body(), biome, 0, palette.emission().applyAsInt(topBlock));
            if (bodyBottom > runBottom) add(out, runBottom, bodyBottom, under, biome, 0, palette.emission().applyAsInt(palette.body()));
            cursor = runBottom;
            above = biome;
        }
        int light = runs.count() == 0 ? (skylit ? MAX_LIGHT : 0) : openLight(cursor - minY, skylit, roofed);
        add(out, minY, cursor, palette.air(), above, light, 0);
    }

    /** The sky light of an open band {@code height} blocks tall under a run. */
    static int openLight(int height, boolean skylit, boolean roofed) {
        return skylit && !roofed && height >= OPEN_BAND ? MAX_LIGHT : 0;
    }

    private static <T, B> void add(List<Segment<T, B>> out, int bottom, int top, T block, B biome, int sky, int blockLight) {
        if (top <= bottom) return;
        out.add(new Segment<>(bottom, top, block, biome, sky, Math.clamp(blockLight, 0, MAX_LIGHT)));
    }
}
