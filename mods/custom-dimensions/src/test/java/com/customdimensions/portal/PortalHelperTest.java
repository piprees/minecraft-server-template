package com.customdimensions.portal;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalHelperTest {

    @Test
    void parseColorValidHex() {
        assertEquals(0xFF0000, PortalHelper.parseColor("FF0000"));
        assertEquals(0x00FF00, PortalHelper.parseColor("00FF00"));
        assertEquals(0x0000FF, PortalHelper.parseColor("0000FF"));
        assertEquals(0xAABBCC, PortalHelper.parseColor("AABBCC"));
    }

    @Test
    void parseColorWithHashPrefix() {
        assertEquals(0xFF0000, PortalHelper.parseColor("#FF0000"));
        assertEquals(0x00AAAA, PortalHelper.parseColor("#00AAAA"));
    }

    @Test
    void parseColorLowercase() {
        assertEquals(0xaabbcc, PortalHelper.parseColor("aabbcc"));
        assertEquals(0xaabbcc, PortalHelper.parseColor("#aabbcc"));
    }

    @Test
    void parseColorInvalidReturnsFallback() {
        assertEquals(0x8844FF, PortalHelper.parseColor("ZZZZZZ"));
        assertEquals(0x8844FF, PortalHelper.parseColor("not-a-color"));
    }

    @Test
    void parseColorNullReturnsFallback() {
        assertEquals(0x8844FF, PortalHelper.parseColor(null));
    }

    @Test
    void classifyFramePartByInteriorYRange() {
        net.minecraft.util.math.BlockPos below = new net.minecraft.util.math.BlockPos(0, 59, 0);
        net.minecraft.util.math.BlockPos level = new net.minecraft.util.math.BlockPos(1, 61, 0);
        net.minecraft.util.math.BlockPos above = new net.minecraft.util.math.BlockPos(0, 63, 0);
        assertEquals("bottom", PortalHelper.classifyFramePart(below, 60, 62));
        assertEquals("sides", PortalHelper.classifyFramePart(level, 60, 62));
        assertEquals("top", PortalHelper.classifyFramePart(above, 60, 62));
        // boundary rows belong to sides — only strictly past the interior counts
        assertEquals("sides", PortalHelper.classifyFramePart(
                new net.minecraft.util.math.BlockPos(0, 60, 0), 60, 62));
        assertEquals("sides", PortalHelper.classifyFramePart(
                new net.minecraft.util.math.BlockPos(0, 62, 0), 60, 62));
    }

    @Test
    void parseColorEmptyReturnsFallback() {
        assertEquals(0x8844FF, PortalHelper.parseColor(""));
    }

    @Test
    void planeDirectionsXAxis() {
        Direction[] dirs = PortalHelper.planeDirections(Direction.Axis.X);
        assertEquals(4, dirs.length);
        assertArrayEquals(new Direction[]{Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN}, dirs);
    }

    @Test
    void planeDirectionsZAxis() {
        Direction[] dirs = PortalHelper.planeDirections(Direction.Axis.Z);
        assertEquals(4, dirs.length);
        assertArrayEquals(new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN}, dirs);
    }

    @Test
    void planeDirectionsYAxis() {
        Direction[] dirs = PortalHelper.planeDirections(Direction.Axis.Y);
        assertEquals(4, dirs.length);
        assertArrayEquals(new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}, dirs);
    }

    @Test
    void planeDirectionsAllAxesCovered() {
        for (Direction.Axis axis : Direction.Axis.values()) {
            Direction[] dirs = PortalHelper.planeDirections(axis);
            assertEquals(4, dirs.length, "Expected 4 directions for axis " + axis);
        }
    }
}
