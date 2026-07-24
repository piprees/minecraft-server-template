package com.customdimensions.portal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PortalShapeTest {

    private static Set<BlockPos> column(int x, int z, int yFrom, int height) {
        Set<BlockPos> out = new HashSet<>();
        for (int dy = 0; dy < height; dy++) {
            out.add(new BlockPos(x, yFrom + dy, z));
        }
        return out;
    }

    private static Set<BlockPos> box(int x, int y, int z, int sx, int sy, int sz) {
        Set<BlockPos> out = new HashSet<>();
        for (int dx = 0; dx < sx; dx++) {
            for (int dy = 0; dy < sy; dy++) {
                for (int dz = 0; dz < sz; dz++) {
                    out.add(new BlockPos(x + dx, y + dy, z + dz));
                }
            }
        }
        return out;
    }

    @Test
    void standardAndAbsentAcceptAnything() {
        Set<BlockPos> odd = box(0, 60, 0, 3, 1, 1);
        assertTrue(PortalShape.matches(null, odd, Direction.Axis.X));
        assertTrue(PortalShape.matches("", odd, Direction.Axis.Z));
        assertTrue(PortalShape.matches("standard", odd, Direction.Axis.Y));
    }

    @Test
    void unknownShapeAcceptsNothing() {
        assertFalse(PortalShape.matches("hexagon", box(0, 60, 0, 2, 3, 1), Direction.Axis.X));
    }

    @Test
    void emptyInteriorNeverMatches() {
        assertFalse(PortalShape.matches("standard", Set.of(), Direction.Axis.X));
    }

    @Test
    void doorIsExactlyOneByTwoVertical() {
        assertTrue(PortalShape.matches("door", column(5, 5, 60, 2), Direction.Axis.X));
        assertTrue(PortalShape.matches("door", column(5, 5, 60, 2), Direction.Axis.Z));
        // wrong sizes
        assertFalse(PortalShape.matches("door", column(5, 5, 60, 1), Direction.Axis.X));
        assertFalse(PortalShape.matches("door", column(5, 5, 60, 3), Direction.Axis.X));
        // right count, wrong arrangement (side by side, not stacked)
        Set<BlockPos> sideBySide = Set.of(new BlockPos(0, 60, 0), new BlockPos(1, 60, 0));
        assertFalse(PortalShape.matches("door", sideBySide, Direction.Axis.X));
        // never horizontal
        assertFalse(PortalShape.matches("door", column(5, 5, 60, 2), Direction.Axis.Y));
    }

    @Test
    void doorwayIsExactlyTwoByThreeVertical() {
        assertTrue(PortalShape.matches("doorway", box(0, 60, 0, 2, 3, 1), Direction.Axis.X));
        assertTrue(PortalShape.matches("doorway", box(0, 60, 0, 1, 3, 2), Direction.Axis.Z));
        // axis/extent mismatch: a Z-oriented opening under an X-axis fill
        assertFalse(PortalShape.matches("doorway", box(0, 60, 0, 1, 3, 2), Direction.Axis.X));
        // wrong sizes
        assertFalse(PortalShape.matches("doorway", box(0, 60, 0, 2, 2, 1), Direction.Axis.X));
        assertFalse(PortalShape.matches("doorway", box(0, 60, 0, 3, 3, 1), Direction.Axis.X));
        assertFalse(PortalShape.matches("doorway", box(0, 60, 0, 2, 3, 1), Direction.Axis.Y));
        // six blocks in an L, bounding box 2x3 but not full — must fail
        Set<BlockPos> lShape = new HashSet<>(column(0, 0, 60, 3));
        lShape.addAll(column(1, 0, 60, 2));
        lShape.add(new BlockPos(2, 60, 0));
        assertFalse(PortalShape.matches("doorway", lShape, Direction.Axis.X));
    }

    @Test
    void endExitForcesHorizontal() {
        Set<BlockPos> pad = box(0, 60, 0, 3, 1, 3);
        assertTrue(PortalShape.matches("end_exit", pad, Direction.Axis.Y));
        assertFalse(PortalShape.matches("end_exit", pad, Direction.Axis.X));
        // any horizontal footprint is acceptable — thematic, not prescriptive
        assertTrue(PortalShape.matches("end_exit", box(0, 60, 0, 5, 1, 2), Direction.Axis.Y));
    }

    @Test
    void impliedOrientations() {
        assertEquals("vertical", PortalShape.impliedOrientation("door"));
        assertEquals("vertical", PortalShape.impliedOrientation("doorway"));
        assertEquals("horizontal", PortalShape.impliedOrientation("end_exit"));
        assertNull(PortalShape.impliedOrientation("standard"));
        assertNull(PortalShape.impliedOrientation(null));
        assertNull(PortalShape.impliedOrientation("hexagon"));
    }

    @Test
    void centreOfOddPadIsTheMiddle() {
        assertEquals(new BlockPos(1, 60, 1), PortalShape.centreOf(box(0, 60, 0, 3, 1, 3)));
    }

    @Test
    void centreOfRingFallsBackToNearestInteriorCell() {
        // 3x3 pad with the middle missing: the ideal centre isn't in the
        // interior, so the pick falls back deterministically to a neighbour.
        Set<BlockPos> ring = box(0, 60, 0, 3, 1, 3);
        ring.remove(new BlockPos(1, 60, 1));
        BlockPos centre = PortalShape.centreOf(ring);
        assertTrue(ring.contains(centre));
        assertEquals(1, centre.getManhattanDistance(new BlockPos(1, 60, 1)));
    }

    @Test
    void normaliseCollapsesBlanks() {
        assertEquals("standard", PortalShape.normalise(null));
        assertEquals("standard", PortalShape.normalise("  "));
        assertEquals("door", PortalShape.normalise(" door "));
    }
}
