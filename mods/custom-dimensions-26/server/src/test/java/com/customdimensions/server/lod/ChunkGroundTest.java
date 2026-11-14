package com.customdimensions.server.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.customdimensions.server.render.fast.ColumnRuns;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The LOD check's ground on the roofed columns its comparison missed: each
 * noise column is the one the generator's {@code getBaseColumn} and the LOD
 * both give at that position (seed -6876984017876508741), the chunk is that
 * column with the reported real surface dug into it.
 */
class ChunkGroundTest {
    static final int MIN_Y = 0;
    static final int TOP = 127;
    static final Predicate<String> SOLID = s -> s.equals("rock") || s.equals("bedrock") || s.equals("glowstone");

    /** A column of {@code TOP - MIN_Y + 1} blocks: air, then each {@code [top, bottom, block]} triple painted over it. */
    static String[] column(Object... runs) {
        String[] out = new String[TOP - MIN_Y + 1];
        java.util.Arrays.fill(out, "air");
        for (int i = 0; i < runs.length; i += 3) {
            for (int y = (int) runs[i + 1]; y <= (int) runs[i]; y++) out[y - MIN_Y] = (String) runs[i + 2];
        }
        return out;
    }

    /** {@code base} with blocks {@code top..bottom} replaced by {@code with}. */
    static String[] dig(String[] base, int top, int bottom, String with) {
        String[] out = base.clone();
        for (int y = bottom; y <= top; y++) out[y - MIN_Y] = with;
        return out;
    }

    /** The surface as the check reads it ({@code Sampler.surface}, as {@link ColumnRuns#surface}). */
    static int surface(IntFunction<String> block) {
        boolean[] solid = new boolean[TOP - MIN_Y + 1];
        for (int y = MIN_Y; y <= TOP; y++) solid[y - MIN_Y] = SOLID.test(block.apply(y));
        return ColumnRuns.of(solid, MIN_Y, solid.length).surface(TOP, MIN_Y);
    }

    static void check(String[] noise, String[] chunk, int lod, int chunkSurface, String dug) {
        IntFunction<String> n = y -> noise[y - MIN_Y];
        IntFunction<String> c = y -> chunk[y - MIN_Y];
        assertEquals(lod, surface(n), "the LOD's surface is the noise column's");
        assertEquals(chunkSurface, surface(c), "the chunk as it stands");
        IntFunction<String> ground = ChunkGround.of(c, n, SOLID);
        assertEquals(lod, surface(ground), "the chunk's ground, its dug blocks read from the noise");
        assertEquals(dug, ChunkGround.dug(c, n, SOLID, TOP, Math.min(lod, chunkSurface) - 1));
    }

    @Test
    void aCaveInTheRoofIsNotTheFloor() {
        String[] noise = column(127, 67, "rock", 31, 22, "lava", 21, 0, "rock");
        check(noise, dig(noise, 120, 114, "air"), 22, 114, "120..114");
    }

    @Test
    void aCaveInAThickRoofOverSeveralFloorsIsNotTheFloor() {
        String[] noise = column(127, 84, "rock", 65, 49, "rock", 47, 33, "rock", 31, 28, "lava", 27, 0, "rock");
        check(noise, dig(noise, 123, 118, "air"), 66, 118, "123..118");
    }

    @Test
    void aCaveInASolidColumnLeavesItSolid() {
        String[] noise = column(127, 0, "rock");
        check(noise, dig(noise, 116, 110, "air"), 128, 110, "116..110");
    }

    @Test
    void aCaveThroughTheFloorFilledWithLavaDoesNotDropItToTheBedrock() {
        String[] noise = column(127, 105, "rock", 31, 29, "lava", 28, 0, "rock");
        String[] chunk = dig(dig(noise, 3, 0, "bedrock"), 28, 4, "lava");
        check(noise, chunk, 29, 4, "28..4");
    }

    @Test
    void aFloorNickedByTwoBlocksKeepsItsHeight() {
        String[] noise = column(127, 74, "rock", 54, 36, "rock", 31, 24, "lava", 23, 0, "rock");
        check(noise, dig(noise, 54, 53, "air"), 55, 53, "54..53");
    }

    @Test
    void groundTheChunkAddsStays() {
        String[] noise = column(127, 67, "rock", 21, 0, "rock");
        String[] chunk = column(127, 67, "rock", 40, 40, "glowstone", 21, 0, "rock");
        IntFunction<String> ground = ChunkGround.of(y -> chunk[y - MIN_Y], y -> noise[y - MIN_Y], SOLID);
        assertEquals(41, surface(ground), "a block a feature placed under the roof is ground in the chunk");
        assertEquals("", ChunkGround.dug(y -> chunk[y - MIN_Y], y -> noise[y - MIN_Y], SOLID, TOP, 0));
    }
}
