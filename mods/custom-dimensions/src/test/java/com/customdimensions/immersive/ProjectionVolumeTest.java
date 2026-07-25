package com.customdimensions.immersive;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionVolumeTest {

    /** A 2x3 doorway interior in the XY plane (zone axis X) at z. */
    private static Set<BlockPos> doorwayX(int x, int y, int z) {
        Set<BlockPos> out = new HashSet<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                out.add(new BlockPos(x + dx, y + dy, z));
            }
        }
        return out;
    }

    /** A 2x3 doorway interior in the ZY plane (zone axis Z) at x. */
    private static Set<BlockPos> doorwayZ(int x, int y, int z) {
        Set<BlockPos> out = new HashSet<>();
        for (int dz = 0; dz < 2; dz++) {
            for (int dy = 0; dy < 3; dy++) {
                out.add(new BlockPos(x, y + dy, z + dz));
            }
        }
        return out;
    }

    /** A 3x3 horizontal pad (zone axis Y) at y. */
    private static Set<BlockPos> pad(int x, int y, int z) {
        Set<BlockPos> out = new HashSet<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                out.add(new BlockPos(x + dx, y, z + dz));
            }
        }
        return out;
    }

    @Test
    void normalAxisFollowsThePortalPlane() {
        assertEquals(Direction.Axis.Z, ProjectionVolume.normalAxis(Direction.Axis.X));
        assertEquals(Direction.Axis.X, ProjectionVolume.normalAxis(Direction.Axis.Z));
        assertEquals(Direction.Axis.Y, ProjectionVolume.normalAxis(Direction.Axis.Y));
    }

    @Test
    void testVerticalPortalXAxis() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        List<BlockPos> volume = ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.X, Direction.SOUTH, 4, 1);

        // 2 wide + 2*1 pad = 4, 3 tall + 2*1 pad = 5, depth 4.
        assertEquals(4 * 5 * 4, volume.size());
        Set<BlockPos> positions = new HashSet<>(volume);
        assertEquals(volume.size(), positions.size(), "no duplicate positions");

        for (BlockPos p : positions) {
            // The slab starts one block PAST the plane and never touches it.
            assertTrue(p.getZ() >= 21 && p.getZ() <= 24, "depth range: " + p);
            assertTrue(p.getX() >= 9 && p.getX() <= 12, "padded width: " + p);
            assertTrue(p.getY() >= 63 && p.getY() <= 67, "padded height: " + p);
        }
        // The portal plane itself is excluded, so no interior block is faked.
        for (BlockPos p : interior) {
            assertFalse(positions.contains(p), "interior must keep its real blocks");
        }
        assertTrue(positions.contains(new BlockPos(10, 64, 21)));
        assertTrue(positions.contains(new BlockPos(9, 63, 24)));
    }

    @Test
    void testVerticalPortalZAxis() {
        Set<BlockPos> interior = doorwayZ(-5, 70, -30);
        List<BlockPos> volume = ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.Z, Direction.WEST, 4, 1);

        assertEquals(4 * 5 * 4, volume.size());
        for (BlockPos p : volume) {
            // WEST is -X, so the slab sits below the plane's X.
            assertTrue(p.getX() >= -9 && p.getX() <= -6, "depth range: " + p);
            assertTrue(p.getZ() >= -31 && p.getZ() <= -28, "padded width: " + p);
            assertTrue(p.getY() >= 69 && p.getY() <= 73, "padded height: " + p);
        }
    }

    @Test
    void testHorizontalPortal() {
        Set<BlockPos> interior = pad(0, 100, 0);
        List<BlockPos> volume = ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.Y, Direction.DOWN, 4, 1);

        // 3 + 2 pad on both horizontal axes, depth downward.
        assertEquals(5 * 5 * 4, volume.size());
        for (BlockPos p : volume) {
            assertTrue(p.getY() >= 96 && p.getY() <= 99, "depth extends downward: " + p);
            assertTrue(p.getX() >= -1 && p.getX() <= 3);
            assertTrue(p.getZ() >= -1 && p.getZ() <= 3);
        }
    }

    @Test
    void defaultSettingsProduceTheDocumentedPacketBudget() {
        // 2x3 doorway, depth 8, radius 2 -> 6 * 7 * 8 = 336 positions, the
        // worst-case initial send the packet-budget analysis is built on.
        assertEquals(336, ProjectionVolume.computeSourcePositions(
                doorwayX(0, 64, 0), Direction.Axis.X, Direction.NORTH, 8, 2).size());
    }

    @Test
    void degenerateInputsProduceNoVolume() {
        assertTrue(ProjectionVolume.computeSourcePositions(
                Set.of(), Direction.Axis.X, Direction.NORTH, 8, 2).isEmpty());
        assertTrue(ProjectionVolume.computeSourcePositions(
                doorwayX(0, 64, 0), Direction.Axis.X, Direction.NORTH, 0, 2).isEmpty());
        assertTrue(ProjectionVolume.computeSourcePositions(
                doorwayX(0, 64, 0), Direction.Axis.X, null, 8, 2).isEmpty());
        // radius 0 is legal: the slab is exactly the interior's footprint.
        assertEquals(2 * 3 * 8, ProjectionVolume.computeSourcePositions(
                doorwayX(0, 64, 0), Direction.Axis.X, Direction.NORTH, 8, 0).size());
    }

    @Test
    void farSideIsAlwaysOppositeTheViewer() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        // Viewer north of the plane (smaller Z) -> project south.
        assertEquals(Direction.SOUTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, new BlockPos(10, 64, 14), null));
        // Viewer south of it -> project north.
        assertEquals(Direction.NORTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, new BlockPos(10, 64, 26), null));

        Set<BlockPos> zInterior = doorwayZ(-5, 70, -30);
        assertEquals(Direction.EAST, ProjectionVolume.viewerFarSide(
                zInterior, Direction.Axis.Z, new BlockPos(-12, 70, -30), null));
        assertEquals(Direction.WEST, ProjectionVolume.viewerFarSide(
                zInterior, Direction.Axis.Z, new BlockPos(2, 70, -30), null));

        // Horizontal portal: a viewer above sees down through it.
        assertEquals(Direction.DOWN, ProjectionVolume.viewerFarSide(
                pad(0, 100, 0), Direction.Axis.Y, new BlockPos(1, 104, 1), null));
        assertEquals(Direction.UP, ProjectionVolume.viewerFarSide(
                pad(0, 100, 0), Direction.Axis.Y, new BlockPos(1, 96, 1), null));
    }

    @Test
    void farSideHoldsSteadyWhileTheViewerIsInThePlane() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        BlockPos inDoorway = new BlockPos(10, 64, 20);
        // Standing in the doorway must not flip an established side...
        assertEquals(Direction.NORTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, inDoorway, Direction.NORTH));
        assertEquals(Direction.SOUTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, inDoorway, Direction.SOUTH));
        // ...and with nothing established, the positive side is the default.
        assertEquals(Direction.SOUTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, inDoorway, null));
    }

    @Test
    void testTargetMapping() {
        // Interior columns x = 100/101, z = 200; average truncates to
        // (100, 200). At scale 0.5 the arrival column is (50, 100).
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 0.5);

        assertEquals(50, mapping.arrivalX());
        assertEquals(100, mapping.arrivalZ());
        assertEquals(-50, mapping.dx());
        assertEquals(-100, mapping.dz());
        assertEquals(64, mapping.interiorMinY());

        // The interior's floor row lands exactly on the arrival surface.
        assertEquals(new BlockPos(50, 72, 100),
                ProjectionVolume.toTarget(new BlockPos(100, 64, 200), mapping, 72));
        // Height above the floor is preserved, and depth/width offsets ride along.
        assertEquals(new BlockPos(51, 74, 101),
                ProjectionVolume.toTarget(new BlockPos(101, 66, 201), mapping, 72));
        // Positions below the portal floor map below the arrival surface.
        assertEquals(new BlockPos(50, 70, 100),
                ProjectionVolume.toTarget(new BlockPos(100, 62, 200), mapping, 72));
    }

    @Test
    void scaleOneIsAPureVerticalRemap() {
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 1.0);
        assertEquals(0, mapping.dx());
        assertEquals(0, mapping.dz());
        assertEquals(new BlockPos(100, 90, 200),
                ProjectionVolume.toTarget(new BlockPos(100, 64, 200), mapping, 90));
    }

    @Test
    void targetChunksCoverBothSidesAndTheArrivalColumn() {
        // Scale 1, so target coordinates equal source coordinates.
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 1.0);
        List<ChunkPos> chunks = ProjectionVolume.targetChunks(
                interior, Direction.Axis.X, mapping, 8, 2);

        // X: 100..101 padded by 2 -> 98..103 (chunk 6).
        // Z: 200 reaching 8 blocks BOTH ways -> 192..208 (chunks 12 and 13).
        assertEquals(Set.of(new ChunkPos(6, 12), new ChunkPos(6, 13)), new HashSet<>(chunks));
        assertEquals(2, chunks.size(), "no duplicate chunk columns");
        // The arrival column's chunk must be in the set — it is what
        // arrivalSurfaceY samples the heightmap from.
        assertTrue(chunks.contains(new ChunkPos(mapping.arrivalX() >> 4, mapping.arrivalZ() >> 4)));
    }

    @Test
    void targetChunksFollowTheScaledOffset() {
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 0.5);
        List<ChunkPos> chunks = ProjectionVolume.targetChunks(
                interior, Direction.Axis.X, mapping, 8, 2);

        // dx = -50, dz = -100: X 48..53 (chunk 3), Z 92..108 (chunks 5, 6).
        assertEquals(Set.of(new ChunkPos(3, 5), new ChunkPos(3, 6)), new HashSet<>(chunks));
        assertTrue(chunks.contains(new ChunkPos(mapping.arrivalX() >> 4, mapping.arrivalZ() >> 4)));
    }

    @Test
    void targetChunksForAnchorPortalsLandOnTheAnchor() {
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.anchorMapping(interior, -1000, 3000);
        List<ChunkPos> chunks = ProjectionVolume.targetChunks(
                interior, Direction.Axis.X, mapping, 8, 2);

        assertTrue(chunks.contains(new ChunkPos(-1000 >> 4, 3000 >> 4)),
                "the anchor's own chunk must be held: " + chunks);
        assertEquals(Set.of(new ChunkPos(-63, 187), new ChunkPos(-63, 188)), new HashSet<>(chunks));
    }

    @Test
    void horizontalPortalDepthDoesNotWidenTheChunkFootprint() {
        // A Y-normal portal reaches vertically, so depth must not expand the
        // chunk columns — only the radius pad does.
        Set<BlockPos> interior = pad(0, 100, 0);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 1.0);
        List<ChunkPos> shallow = ProjectionVolume.targetChunks(
                interior, Direction.Axis.Y, mapping, 1, 2);
        List<ChunkPos> deep = ProjectionVolume.targetChunks(
                interior, Direction.Axis.Y, mapping, 16, 2);
        assertEquals(new HashSet<>(shallow), new HashSet<>(deep));
        // x/z 0..2 padded by 2 -> -2..4, i.e. chunks -1 and 0 on both axes.
        assertEquals(4, deep.size());
    }

    @Test
    void targetChunksStayBoundedAtMaximumSettings() {
        // A large interior at the config maxima (depth 16, radius 4) must
        // still ticket a handful of chunks, not a region.
        Set<BlockPos> wide = new HashSet<>();
        for (int dx = 0; dx < 16; dx++) {
            for (int dy = 0; dy < 8; dy++) {
                wide.add(new BlockPos(dx, 64 + dy, 0));
            }
        }
        List<ChunkPos> chunks = ProjectionVolume.targetChunks(
                wide, Direction.Axis.X, ProjectionVolume.scaledMapping(wide, 1.0), 16, 4);
        assertTrue(chunks.size() <= 12, "unbounded chunk ticket set: " + chunks.size());
        assertFalse(chunks.isEmpty());
    }

    @Test
    void targetChunksAreEmptyForDegenerateInput() {
        assertTrue(ProjectionVolume.targetChunks(
                Set.of(), Direction.Axis.X,
                new ProjectionVolume.TargetMapping(0, 0, 0, 0, 0), 8, 2).isEmpty());
        assertTrue(ProjectionVolume.targetChunks(
                doorwayX(0, 64, 0), Direction.Axis.X, null, 8, 2).isEmpty());
    }

    @Test
    void testAnchorTargetMapping() {
        // Anchor portals translate the interior's MIN corner onto the
        // anchor position — matching ServerWorldMixin.teleportToAnchor,
        // which uses minX/minZ and NOT the averaged centre.
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.anchorMapping(interior, -1000, 3000);

        assertEquals(-1000, mapping.arrivalX());
        assertEquals(3000, mapping.arrivalZ());
        assertEquals(-1100, mapping.dx());
        assertEquals(2800, mapping.dz());
        assertEquals(64, mapping.interiorMinY());

        assertEquals(new BlockPos(-1000, 80, 3000),
                ProjectionVolume.toTarget(new BlockPos(100, 64, 200), mapping, 80));
        assertEquals(new BlockPos(-999, 82, 3000),
                ProjectionVolume.toTarget(new BlockPos(101, 66, 200), mapping, 80));
        // Scale is irrelevant for anchors: they never consult it.
        assertNotEquals(ProjectionVolume.scaledMapping(interior, 0.5).dx(), mapping.dx());
    }
}
