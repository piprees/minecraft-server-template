package com.customdimensions.server.lod;

import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * The ground the LOD check reads from a generated chunk: the chunk's block,
 * except where the chunk is open (air or fluid) and the level's noise stage
 * was solid. A carver's cave or a feature that digs took that block after
 * the noise stage, and the LOD draws neither, so the noise's block is read
 * there: a cave cut into a roof is not a floor under it, and one cut through
 * a floor does not drop the floor to the bedrock. Ground the chunk adds over
 * the noise (a feature, a structure) stays. Game-free: {@code T} is the
 * block type.
 */
final class ChunkGround {
    private ChunkGround() {}

    static <T> IntFunction<T> of(IntFunction<T> chunk, IntFunction<T> noise, Predicate<T> solid) {
        return y -> {
            T c = chunk.apply(y);
            if (solid.test(c)) return c;
            T n = noise.apply(y);
            return solid.test(n) ? n : c;
        };
    }

    /** The runs from {@code top} down to {@code bottom} where the chunk is open and the noise solid, as {@code top..bottom} joined by commas. */
    static <T> String dug(IntFunction<T> chunk, IntFunction<T> noise, Predicate<T> solid, int top, int bottom) {
        StringBuilder out = new StringBuilder();
        int y = top;
        while (y >= bottom) {
            if (solid.test(chunk.apply(y)) || !solid.test(noise.apply(y))) {
                y--;
                continue;
            }
            int from = y;
            while (y >= bottom && !solid.test(chunk.apply(y)) && solid.test(noise.apply(y))) y--;
            out.append(out.isEmpty() ? "" : ",").append(from).append("..").append(y + 1);
        }
        return out.toString();
    }
}
