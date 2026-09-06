package com.customdimensions.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The per-column tint palette. The wire carries a palette of distinct triples
 * and an index per column, so what a column resolves to depends on the palette
 * keeping both its de-duplication and its order.
 *
 * <p>A view can hold as many distinct triples as it has columns:
 * {@code Biome.getGrassColorAt} takes a position, so nothing bounds the palette
 * but the terrain.
 */
class TintGridTest {

    private static final int GRASS = 0x79C05A;
    private static final int FOLIAGE = 0x59AE30;
    private static final int WATER = 0x3F76E4;

    private static CompanionPayloads.Projection.TintGrid grid(int sizeX, int sizeZ) {
        return new CompanionPayloads.Projection.TintGrid(sizeX, sizeZ);
    }

    /** Entry 0 is the absent triple, so an untouched column reads as no tint. */
    @Test
    void anUntouchedColumnPointsAtTheAbsentEntry() {
        CompanionPayloads.Projection.TintGrid grid = grid(4, 6);
        assertArrayEquals(new int[] {-1, -1, -1}, grid.palette());
        for (int column : grid.columns()) {
            assertEquals(0, column);
        }
    }

    @Test
    void aColumnIsIndexedByXTimesSizeZPlusZ() {
        CompanionPayloads.Projection.TintGrid grid = grid(4, 6);
        grid.set(2, 3, GRASS, FOLIAGE, WATER);
        assertEquals(1, grid.columns()[(2 * 6) + 3], "the column landed somewhere else");
        assertEquals(0, grid.columns()[(3 * 6) + 2], "the transposed column was written instead");
    }

    /** The same triple twice is one palette entry, or the payload carries the view twice. */
    @Test
    void repeatingATripleReusesItsEntry() {
        CompanionPayloads.Projection.TintGrid grid = grid(8, 8);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                grid.set(x, z, GRASS, FOLIAGE, WATER);
            }
        }
        assertEquals(2 * 3, grid.palette().length, "the palette grew per column instead of per triple");
        for (int column : grid.columns()) {
            assertEquals(1, column);
        }
    }

    /** Entries are numbered in the order first seen; the columns index into that. */
    @Test
    void entriesKeepTheOrderTheyWereFirstSeenIn() {
        CompanionPayloads.Projection.TintGrid grid = grid(4, 4);
        grid.set(0, 0, 1, 2, 3);
        grid.set(0, 1, 4, 5, 6);
        grid.set(0, 2, 1, 2, 3);

        assertArrayEquals(new int[] {-1, -1, -1, 1, 2, 3, 4, 5, 6}, grid.palette());
        assertEquals(1, grid.columns()[0]);
        assertEquals(2, grid.columns()[1]);
        assertEquals(1, grid.columns()[2]);
    }

    /**
     * The case the palette is unbounded for: every column a different grass.
     * Each has to resolve to its own entry, and the entry has to hold the
     * colours that column was set with.
     */
    @Test
    void aDistinctTriplePerColumnStillResolvesToItsOwnEntry() {
        int size = 48;
        CompanionPayloads.Projection.TintGrid grid = grid(size, size);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                grid.set(x, z, (x * size) + z, FOLIAGE, WATER);
            }
        }
        int[] palette = grid.palette();
        assertEquals((size * size + 1) * 3, palette.length, "distinct triples were merged");

        int[] columns = grid.columns();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int entry = columns[(x * size) + z];
                assertNotEquals(0, entry);
                assertEquals((x * size) + z, palette[entry * 3],
                        "column " + x + "," + z + " resolves to another column's grass");
            }
        }
    }
}
