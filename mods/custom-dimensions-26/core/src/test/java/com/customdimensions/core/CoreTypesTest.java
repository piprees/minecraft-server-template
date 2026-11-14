package com.customdimensions.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Minecraft-shaped value types core uses in place of Minecraft's own. */
class CoreTypesTest {

    @Test
    void idParsesWithTheDefaultNamespace() {
        assertEquals(new Id("minecraft", "stone"), Id.parse("stone"));
        assertEquals(new Id("minecraft", "stone"), Id.parse(":stone"));
        assertEquals(new Id("adventure", "the_glasswood"), Id.parse("adventure:the_glasswood"));
        assertEquals("adventure:a/b.c-d", Id.parse("adventure:a/b.c-d").toString());
    }

    @Test
    void idRejectsWhatMinecraftRejects() {
        assertNull(Id.tryParse("Minecraft:stone"));
        assertNull(Id.tryParse("minecraft:Stone"));
        assertNull(Id.tryParse("mine/craft:stone"));
        assertNull(Id.tryParse("a:b:c"));
        assertNull(Id.tryParse("#minecraft:logs"));
        assertNull(Id.tryParse("not an id"));
        assertThrows(IllegalArgumentException.class, () -> Id.parse("bad id"));
        assertThrows(IllegalArgumentException.class, () -> Id.of("minecraft", "Bad"));
    }

    @Test
    void idAcceptsAnEmptyPathAsMinecraftDoes() {
        assertEquals(new Id("minecraft", ""), Id.tryParse("minecraft:"));
    }

    @Test
    void chunkPosPacksAsMinecraftDoes() {
        assertEquals(0L, ChunkPos.toLong(0, 0));
        assertEquals(0x0000000200000001L, ChunkPos.toLong(1, 2));
        assertEquals(0xFFFFFFFFFFFFFFFFL, ChunkPos.toLong(-1, -1));
        assertEquals(0x00000005FFFFFFFDL, new ChunkPos(-3, 5).toLong());
        assertEquals(new ChunkPos(4, -4), new ChunkPos(4, -4));
    }

    @Test
    void blockPosOrdersByYThenZThenX() {
        List<BlockPos> list = new ArrayList<>(List.of(
                new BlockPos(0, 1, 0), new BlockPos(1, 0, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, 0)));
        list.sort(null);
        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1), new BlockPos(0, 1, 0)), list);
        assertEquals(6, new BlockPos(1, 2, 3).getManhattanDistance(new BlockPos(0, 0, 0)));
    }

    @Test
    void directionsKnowTheirAxis() {
        assertEquals(Direction.Axis.Y, Direction.UP.getAxis());
        assertEquals(Direction.Axis.Z, Direction.NORTH.getAxis());
        assertEquals(Direction.Axis.X, Direction.EAST.getAxis());
    }

    @Test
    void logFillsPlaceholdersInOrder() {
        assertEquals("a 1 b 2", Log.format("a {} b {}", new Object[]{1, 2}, 2));
        assertEquals("a 1 b {}", Log.format("a {} b {}", new Object[]{1}, 1));
        assertEquals("no args {}", Log.format("no args {}", new Object[0], 0));
        assertTrue(Log.format("x {}", new Object[]{null}, 1).endsWith("null"));
    }
}
