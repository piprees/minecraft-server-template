package com.customdimensions.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApertureCensusTest {
    /** A 2x3 opening on the x axis at z = 0, cells x 0..1, y 1..3: it faces along z. */
    private static final List<int[]> CELLS = List.of(new int[] {0, 1, 0}, new int[] {1, 1, 0}, new int[] {0, 2, 0},
            new int[] {1, 2, 0}, new int[] {0, 3, 0}, new int[] {1, 3, 0});
    private static final int[] OFFSET = {1000, 0, 0};

    /** A destination with a wall of {@code wall} at z = wallZ (destination x near 1000), held only where told. */
    private static ApertureCensus.Scene wall(String wall, int wallZ, boolean fedBeyond) {
        return new ApertureCensus.Scene() {
            @Override
            public String block(int x, int y, int z) {
                if (!fedBeyond && z > 3) {
                    return null;
                }
                if (z == 0) {
                    return "customdimensions:portal";
                }
                return z == wallZ ? wall : "minecraft:air";
            }

            @Override
            public String biome(int x, int y, int z) {
                return block(x, y, z) == null ? null : "minecraft:plains";
            }
        };
    }

    @Test
    void everyRayThroughTheApertureHitsTheFarWall() {
        double[] eye = {1.0, 2.5, -3.0};
        ApertureCensus.Result r = ApertureCensus.measure(eye, CELLS, 0, OFFSET, wall("minecraft:stone", 3, true), 12, 40);
        assertEquals(144, r.rays());
        assertEquals(144, r.hits());
        assertEquals(144, r.blocks().get("minecraft:stone"));
        assertEquals(144, r.biomes().get("minecraft:plains"));
        assertTrue(r.medianHitDistance() >= 3.0 && r.medianHitDistance() < 4.0, String.valueOf(r.medianHitDistance()));
    }

    @Test
    void theFarPortalsOwnCellsAreNeverAHit() {
        double[] eye = {1.0, 2.5, -3.0};
        ApertureCensus.Result r = ApertureCensus.measure(eye, CELLS, 0, OFFSET, wall("minecraft:stone", 30, true), 6, 40);
        assertTrue(r.blocks().keySet().stream().noneMatch(b -> b.contains("portal")), r.blocks().toString());
    }

    @Test
    void aWallPastTheDepthIsOpenAndAnUnheldChunkIsUnfed() {
        double[] eye = {1.0, 2.5, -3.0};
        ApertureCensus.Result far = ApertureCensus.measure(eye, CELLS, 0, OFFSET, wall("minecraft:stone", 60, true), 6, 40);
        assertEquals(36, far.open());
        assertEquals(0, far.hits());
        assertNull(far.medianHitDistance());
        ApertureCensus.Result cold = ApertureCensus.measure(eye, CELLS, 0, OFFSET, wall("minecraft:stone", 10, false), 6, 40);
        assertEquals(36, cold.unfed());
    }

    @Test
    void fromBehindTheRaysRunTheOtherWay() {
        double[] eye = {1.0, 2.5, 4.0};
        ApertureCensus.Result r = ApertureCensus.measure(eye, CELLS, 0, OFFSET, wall("minecraft:netherrack", -2, true), 6, 40);
        assertEquals(36, r.hits());
        assertEquals(36, r.blocks().get("minecraft:netherrack"));
    }

    @Test
    void aShapedApertureCastsOnlyThroughItsOwnCells() {
        List<int[]> l = List.of(new int[] {0, 1, 0}, new int[] {1, 1, 0}, new int[] {0, 2, 0});
        double[] eye = {1.0, 2.0, -3.0};
        ApertureCensus.Result r = ApertureCensus.measure(eye, l, 0, OFFSET, wall("minecraft:stone", 3, true), 4, 40);
        assertEquals(12, r.rays());
    }

    @Test
    void aFlatOpeningLooksDown() {
        List<int[]> flat = List.of(new int[] {0, 64, 0}, new int[] {1, 64, 0}, new int[] {0, 64, 1}, new int[] {1, 64, 1});
        ApertureCensus.Scene floor = new ApertureCensus.Scene() {
            @Override
            public String block(int x, int y, int z) {
                return y == 60 ? "minecraft:end_stone" : "minecraft:air";
            }

            @Override
            public String biome(int x, int y, int z) {
                return "minecraft:the_end";
            }
        };
        ApertureCensus.Result r = ApertureCensus.measure(new double[] {1.0, 67.0, 1.0}, flat, 1, new int[] {0, 0, 0},
                floor, 4, 40);
        assertEquals(16, r.hits());
        assertEquals(16, r.blocks().get("minecraft:end_stone"));
    }
}
