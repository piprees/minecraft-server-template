package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.Direction;
import com.customdimensions.core.config.PortalDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IgnitionScanTest {

    /** Air everywhere except the blocks placed; "minecraft:stone" is solid and not frame. */
    static class Fixture implements FrameView {
        final Map<BlockPos, String> blocks = new HashMap<>();

        Fixture put(int x, int y, int z, String id) {
            blocks.put(new BlockPos(x, y, z), id);
            return this;
        }

        @Override
        public boolean isFillable(BlockPos pos) {
            return !blocks.containsKey(pos);
        }

        @Override
        public boolean matches(BlockPos pos, FrameForms frame) {
            String id = blocks.get(pos);
            return id != null && frame.acceptsBlockId(id);
        }

        /** An upright ring on the X axis (z fixed) around a w x h opening whose bottom-left cell is (x, y). */
        Fixture ringX(int x, int y, int z, int w, int h, String id) {
            for (int dx = -1; dx <= w; dx++) {
                for (int dy = -1; dy <= h; dy++) {
                    boolean inside = dx >= 0 && dx < w && dy >= 0 && dy < h;
                    if (!inside) {
                        put(x + dx, y + dy, z, id);
                    }
                }
            }
            return this;
        }

        /** A flat ring (y fixed) around a w x d opening. */
        Fixture ringY(int x, int y, int z, int w, int d, String id) {
            for (int dx = -1; dx <= w; dx++) {
                for (int dz = -1; dz <= d; dz++) {
                    boolean inside = dx >= 0 && dx < w && dz >= 0 && dz < d;
                    if (!inside) {
                        put(x + dx, y, z + dz, id);
                    }
                }
            }
            return this;
        }
    }

    static PortalDefinition def(String frame) {
        return new PortalDefinition("test", frame, "minecraft:flint_and_steel", "adventure:test", "FFFFFF", 11);
    }

    @Test
    void lightsAStandardUprightFrame() {
        Fixture f = new Fixture().ringX(0, 64, 0, 2, 3, "minecraft:basalt");
        IgnitionScan.Attempt a = IgnitionScan.sweep(f, new BlockPos(-1, 64, 0), def("minecraft:basalt"));
        assertNotNull(a.site());
        assertEquals(Direction.Axis.X, a.site().axis());
        assertEquals(6, a.site().fill().size());
    }

    @Test
    void lightsAFlatFrameOnTheYAxis() {
        Fixture f = new Fixture().ringY(0, 64, 0, 3, 3, "minecraft:basalt");
        IgnitionScan.Attempt a = IgnitionScan.sweep(f, new BlockPos(-1, 64, 0), def("minecraft:basalt"));
        assertNotNull(a.site());
        assertEquals(Direction.Axis.Y, a.site().axis());
        assertEquals(9, a.site().fill().size());
    }

    @Test
    void wrongMaterialHasNoCandidateFrame() {
        Fixture f = new Fixture().ringX(0, 64, 0, 2, 3, "minecraft:stone");
        IgnitionScan.Attempt a = IgnitionScan.sweep(f, new BlockPos(-1, 64, 0), def("minecraft:basalt"));
        assertNull(a.site());
        // The upright fill hits stone; the flat one leaks into open air, which got further.
        assertEquals(IgnitionRefusal.OPENING_TOO_LARGE, a.refusal().reason());
        Fixture walled = new Fixture().ringX(0, 64, 0, 2, 3, "minecraft:stone");
        PortalDefinition upright = def("minecraft:basalt");
        upright.setOrientation("vertical_x");
        IgnitionScan.Attempt b = IgnitionScan.sweep(walled, new BlockPos(-1, 64, 0), upright);
        assertTrue(b.refusal().reason() == IgnitionRefusal.OPENING_TOO_LARGE
                || b.refusal().reason() == IgnitionRefusal.OPENING_NOT_ENCLOSED);
    }

    @Test
    void anOpenFrameIsTooLargeOrUnenclosed() {
        Fixture f = new Fixture().put(0, 64, 0, "minecraft:basalt");
        IgnitionScan.Attempt a = IgnitionScan.sweep(f, new BlockPos(0, 64, 0), def("minecraft:basalt"));
        assertNull(a.site());
        assertEquals(IgnitionRefusal.OPENING_TOO_LARGE, a.refusal().reason());
    }

    @Test
    void shapePresetRefusesTheWrongOpening() {
        Fixture f = new Fixture().ringX(0, 64, 0, 3, 3, "minecraft:basalt");
        PortalDefinition d = def("minecraft:basalt");
        d.setShape(PortalShape.DOORWAY);
        IgnitionScan.Attempt a = IgnitionScan.sweep(f, new BlockPos(-1, 64, 0), d);
        assertNull(a.site());
        assertEquals(IgnitionRefusal.SHAPE_MISMATCH, a.refusal().reason());
        Fixture ok = new Fixture().ringX(0, 64, 0, 2, 3, "minecraft:basalt");
        assertNotNull(IgnitionScan.sweep(ok, new BlockPos(-1, 64, 0), d).site());
    }

    @Test
    void endGatewayIsOneCellAndDoorIsOneByTwo() {
        PortalDefinition gateway = def("minecraft:basalt");
        gateway.setShape(PortalShape.END_GATEWAY);
        assertNotNull(IgnitionScan.sweep(new Fixture().ringX(0, 64, 0, 1, 1, "minecraft:basalt"),
                new BlockPos(-1, 64, 0), gateway).site());
        PortalDefinition door = def("minecraft:basalt");
        door.setShape(PortalShape.DOOR);
        assertNotNull(IgnitionScan.sweep(new Fixture().ringX(0, 64, 0, 1, 2, "minecraft:basalt"),
                new BlockPos(-1, 64, 0), door).site());
        assertEquals(IgnitionRefusal.SHAPE_MISMATCH, IgnitionScan.sweep(new Fixture().ringX(0, 64, 0, 2, 2,
                "minecraft:basalt"), new BlockPos(-1, 64, 0), door).refusal().reason());
    }

    @Test
    void orientationIsAskedLast() {
        Fixture f = new Fixture().ringX(0, 64, 0, 2, 3, "minecraft:basalt");
        PortalDefinition d = def("minecraft:basalt");
        d.setOrientation("horizontal");
        IgnitionScan.Attempt a = IgnitionScan.sweep(f, new BlockPos(-1, 64, 0), d);
        assertNull(a.site());
        assertEquals(IgnitionRefusal.AXIS_NOT_ALLOWED, a.refusal().reason());
    }

    @Test
    void endExitForcesTheFlatPlane() {
        PortalDefinition d = def("minecraft:basalt");
        d.setShape(PortalShape.END_EXIT);
        assertEquals(IgnitionRefusal.SHAPE_MISMATCH, IgnitionScan.sweep(new Fixture().ringX(0, 64, 0, 2, 3,
                "minecraft:basalt"), new BlockPos(-1, 64, 0), d).refusal().reason());
        assertEquals(Direction.Axis.Y, IgnitionScan.sweep(new Fixture().ringY(0, 64, 0, 3, 3, "minecraft:basalt"),
                new BlockPos(-1, 64, 0), d).site().axis());
    }

    @Test
    void mixedFramesAcceptEveryListedBlock() {
        Fixture f = new Fixture().ringX(0, 64, 0, 2, 3, "minecraft:cobblestone");
        f.put(-1, 65, 0, "minecraft:stone_bricks").put(2, 64, 0, "minecraft:stone_bricks");
        PortalDefinition d = def("minecraft:cobblestone");
        d.setFrameAccepts(List.of("minecraft:cobblestone", "minecraft:stone_bricks"));
        assertNotNull(IgnitionScan.sweep(f, new BlockPos(-1, 64, 0), d).site());
    }

    @Test
    void framePartsCheckEachPartOfTheRing() {
        PortalDefinition d = def("minecraft:gold_block");
        d.setFrameAccepts(List.of("minecraft:gold_block", "minecraft:iron_block"));
        d.setFramePartAccepts(Map.of("top", List.of("minecraft:gold_block"), "sides", List.of("minecraft:iron_block"),
                "bottom", List.of("minecraft:iron_block")));
        Fixture good = new Fixture().ringX(0, 64, 0, 2, 3, "minecraft:iron_block");
        for (int x = -1; x <= 2; x++) {
            good.put(x, 67, 0, "minecraft:gold_block");
        }
        assertNotNull(IgnitionScan.sweep(good, new BlockPos(-1, 64, 0), d).site());
        Fixture bad = new Fixture().ringX(0, 64, 0, 2, 3, "minecraft:gold_block");
        assertEquals(IgnitionRefusal.FRAME_PART_MISMATCH,
                IgnitionScan.sweep(bad, new BlockPos(-1, 64, 0), d).refusal().reason());
    }

    @Test
    void patternTemplatesMatchExactly() {
        PortalDefinition d = def("minecraft:basalt");
        d.setShape(PortalShape.PATTERN);
        d.setShapeTemplate(List.of("FFF", "F.F", "F.F", "FFF"));
        d.setShapeLegend(Map.of("F", "frame", ".", "interior"));
        assertNotNull(IgnitionScan.sweep(new Fixture().ringX(0, 64, 0, 1, 2, "minecraft:basalt"),
                new BlockPos(-1, 64, 0), d).site());
        assertEquals(IgnitionRefusal.PATTERN_MISMATCH, IgnitionScan.sweep(new Fixture().ringX(0, 64, 0, 2, 2,
                "minecraft:basalt"), new BlockPos(-1, 64, 0), d).refusal().reason());
    }

    @Test
    void theFurthestRefusalWins() {
        assertEquals(IgnitionRefusal.AXIS_NOT_ALLOWED,
                IgnitionRefusal.furthest(IgnitionRefusal.OPENING_NOT_ENCLOSED, IgnitionRefusal.AXIS_NOT_ALLOWED));
        assertEquals(IgnitionRefusal.SHAPE_MISMATCH, IgnitionRefusal.furthest(IgnitionRefusal.SHAPE_MISMATCH, null));
        assertNull(IgnitionRefusal.furthest(null, null));
    }

    @Test
    void noFrameMaterialAndNoCandidate() {
        PortalDefinition empty = def(null);
        assertEquals(IgnitionRefusal.NO_FRAME_MATERIAL,
                IgnitionScan.sweep(new Fixture(), new BlockPos(0, 64, 0), empty).refusal().reason());
        Fixture solid = new Fixture() {
            @Override
            public boolean isFillable(BlockPos pos) {
                return false;
            }
        };
        assertEquals(IgnitionRefusal.NO_CANDIDATE_CELL,
                IgnitionScan.sweep(solid, new BlockPos(0, 64, 0), def("minecraft:basalt")).refusal().reason());
    }

    @Test
    void igniterSpend() {
        assertEquals(IgniterSpend.NOTHING, IgniterSpend.of(true, true, true));
        assertEquals(IgniterSpend.CONSUME, IgniterSpend.of(false, false, true));
        assertEquals(IgniterSpend.DAMAGE, IgniterSpend.of(true, false, false));
        assertEquals(IgniterSpend.NOTHING, IgniterSpend.of(false, false, false));
    }

    @Test
    void geometryRingAndCentre() {
        Set<BlockPos> interior = ArrivalSite.standardInterior(10, 64, 20, Direction.Axis.X);
        assertEquals(10, PortalGeometry.frameRing(interior, Direction.Axis.X).size());
        assertArrayEquals(new int[]{10, 20}, PortalGeometry.centreColumn(interior));
        assertEquals(new BlockPos(10, 64, 20), PortalGeometry.keyCell(interior));
        assertEquals("bottom", PortalGeometry.classifyFramePart(new BlockPos(0, 63, 0), 64, 66));
        assertEquals("top", PortalGeometry.classifyFramePart(new BlockPos(0, 67, 0), 64, 66));
        assertEquals("sides", PortalGeometry.classifyFramePart(new BlockPos(0, 65, 0), 64, 66));
        assertEquals(12, PortalGeometry.frameRing(ArrivalSite.standardInterior(0, 64, 0, Direction.Axis.Y),
                Direction.Axis.Y).size());
    }
}
